package server.agents.integration.typesafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Permutation-invariant Choice answers, a port of TypeLLM/pijev.
 *
 * <p>Jev's Choice probabilities shift with option order. Each Choice in a request is expanded
 * into several copies whose criteria are reordered; all copies travel in the same System One
 * round trip, their probabilities are aligned by label and averaged, and the highest mean wins.
 * By Jensen's inequality the averaged distribution's Brier score and log loss are no worse than
 * the mean over the included orderings. Noul and Score questions pass through unchanged (Score
 * levels are ordered by meaning, so they must not be shuffled).
 *
 * <ul>
 *   <li>{@link Mode#OFF}: requests are sent unchanged.</li>
 *   <li>{@link Mode#SHADOW}: the original Choice travels alongside its permutations; callers
 *       receive the original answer and the averaged one is only compared and counted.</li>
 *   <li>{@link Mode#LIVE}: callers receive the averaged answer.</li>
 * </ul>
 *
 * <p>A request whose expansion would exceed the question cap, or whose {@link JevRequest#kind()}
 * is exempt, is sent unchanged.
 */
public final class JevChoicePermutations {
    private static final Logger log = LoggerFactory.getLogger(JevChoicePermutations.class);
    private static final String ID_PREFIX = "__perm_";

    public enum Mode {
        OFF, SHADOW, LIVE;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return OFF;
            }
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** The label order and generated question ids of one expanded Choice. */
    record Group(List<String> labels, List<String> ids) {
    }

    /** How one request was expanded; {@code groups} is keyed by the caller's question id. */
    record Plan(JevRequest original, JevRequest expanded, Map<String, Group> groups) {
    }

    private final Mode mode;
    private final int budget;
    private final int maxQuestions;
    private final Set<String> exemptKinds;
    private final Supplier<RandomGenerator> random;
    private final LongAdder shadowComparisons = new LongAdder();
    private final LongAdder shadowAgreements = new LongAdder();
    private final DoubleAdder shadowProbabilityShift = new DoubleAdder();
    private final LongAdder skippedOverCap = new LongAdder();

    public JevChoicePermutations(Mode mode, int budget, int maxQuestions) {
        this(mode, budget, maxQuestions, Set.of());
    }

    public JevChoicePermutations(Mode mode, int budget, int maxQuestions, Set<String> exemptKinds) {
        this(mode, budget, maxQuestions, exemptKinds, ThreadLocalRandom::current);
    }

    JevChoicePermutations(Mode mode, int budget, int maxQuestions, Set<String> exemptKinds,
                          Supplier<RandomGenerator> random) {
        if (budget < 1) {
            throw new IllegalArgumentException("permutation budget must be positive");
        }
        if (maxQuestions < 1) {
            throw new IllegalArgumentException("question cap must be positive");
        }
        this.mode = mode == null ? Mode.OFF : mode;
        this.budget = budget;
        this.maxQuestions = maxQuestions;
        this.exemptKinds = Set.copyOf(exemptKinds);
        this.random = random;
    }

    public static JevChoicePermutations off() {
        return new JevChoicePermutations(Mode.OFF, 1, 1);
    }

    public static JevChoicePermutations fromTuning() {
        return new JevChoicePermutations(
                Mode.parse(config.AgentTuning.stringValue(
                        "server.agents.integration.typesafe.JevChoicePermutations.MODE")),
                config.AgentTuning.intValue(
                        "server.agents.integration.typesafe.JevChoicePermutations.BUDGET"),
                config.AgentTuning.intValue(
                        "server.agents.integration.typesafe.JevChoicePermutations.MAX_QUESTIONS"),
                parseKinds(config.AgentTuning.stringValue(
                        "server.agents.integration.typesafe.JevChoicePermutations.EXEMPT_KINDS")));
    }

    /** Comma-separated request kinds; {@code none} or blank means no exemptions. */
    static Set<String> parseKinds(String value) {
        Set<String> kinds = new HashSet<>();
        if (value != null) {
            for (String kind : value.split(",")) {
                String trimmed = kind.trim();
                if (!trimmed.isEmpty() && !"none".equalsIgnoreCase(trimmed)) {
                    kinds.add(trimmed);
                }
            }
        }
        return kinds;
    }

    public Mode mode() {
        return mode;
    }

    /** The expansion for {@code request}, or {@code null} when it should be sent unchanged. */
    Plan plan(JevRequest request) {
        if (mode == Mode.OFF || budget < 2 || exemptKinds.contains(request.kind())) {
            return null;
        }
        Set<String> reserved = new HashSet<>(request.questions().keySet());
        Map<String, JevQuestion> expanded = new LinkedHashMap<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        int generated = 0;
        for (Map.Entry<String, JevQuestion> entry : request.questions().entrySet()) {
            if (!(entry.getValue() instanceof JevQuestion.Choice choice)) {
                expanded.put(entry.getKey(), entry.getValue());
                continue;
            }
            if (mode == Mode.SHADOW) {
                expanded.put(entry.getKey(), choice);
            }
            List<String> labels = new ArrayList<>(choice.criteria().keySet());
            labels.sort(null);
            List<String> ids = new ArrayList<>();
            for (List<String> order : orders(labels)) {
                String id = ID_PREFIX + generated++;
                while (!reserved.add(id)) {
                    id += "_";
                }
                Map<String, Object> criteria = new LinkedHashMap<>();
                for (String label : order) {
                    criteria.put(label, choice.criteria().get(label));
                }
                expanded.put(id, new JevQuestion.Choice(choice.instructions(), criteria));
                ids.add(id);
            }
            groups.put(entry.getKey(), new Group(List.copyOf(labels), List.copyOf(ids)));
        }
        if (groups.isEmpty()) {
            return null;
        }
        if (expanded.size() > maxQuestions) {
            skippedOverCap.increment();
            return null;
        }
        return new Plan(request, new JevRequest(request.state(), expanded, request.kind()), groups);
    }

    /**
     * Maps the expanded response back onto the caller's question ids. Throws
     * {@link IllegalStateException} in LIVE mode when a permutation answer is missing or
     * malformed; in SHADOW mode such a response only skips the comparison.
     */
    JevResponse aggregate(JevResponse raw, Plan plan) {
        Map<String, JevAnswer> answers = new LinkedHashMap<>();
        for (String id : plan.original().questions().keySet()) {
            Group group = plan.groups().get(id);
            if (group == null || mode == Mode.SHADOW) {
                JevAnswer answer = raw.answers().get(id);
                if (answer != null) {
                    answers.put(id, answer);
                }
            }
            if (group == null) {
                continue;
            }
            if (mode == Mode.LIVE) {
                answers.put(id, average(raw, group));
            } else {
                compare(id, raw.answers().get(id), raw, group);
            }
        }
        return new JevResponse(raw.model(), answers, raw.inputTokens(), raw.outputTokens(), raw.latencyMs());
    }

    public String diagnostics() {
        long comparisons = shadowComparisons.sum();
        StringBuilder text = new StringBuilder("permutations=").append(mode);
        if (mode != Mode.OFF) {
            text.append(" budget=").append(budget).append(" cap=").append(maxQuestions)
                    .append(" skippedOverCap=").append(skippedOverCap.sum());
        }
        if (comparisons > 0) {
            text.append(String.format(Locale.ROOT, " shadow[n=%d agree=%.1f%% meanShift=%.3f]",
                    comparisons,
                    100.0d * shadowAgreements.sum() / comparisons,
                    shadowProbabilityShift.sum() / comparisons));
        }
        return text.toString();
    }

    long shadowComparisons() {
        return shadowComparisons.sum();
    }

    long shadowAgreements() {
        return shadowAgreements.sum();
    }

    private void compare(String id, JevAnswer original, JevResponse raw, Group group) {
        if (original == null || !original.isChoice()) {
            return;
        }
        JevAnswer averaged;
        try {
            averaged = average(raw, group);
        } catch (IllegalStateException malformed) {
            log.debug("Permutation shadow skipped for {}: {}", id, malformed.getMessage());
            return;
        }
        String originalWinner = original.mostLikelyOption().orElse("");
        shadowComparisons.increment();
        if (originalWinner.equals(averaged.choice())) {
            shadowAgreements.increment();
        }
        shadowProbabilityShift.add(Math.abs(
                original.probabilityOf(originalWinner) - averaged.probabilityOf(originalWinner)));
        log.debug("Permutation shadow {}: original {} ({}) vs averaged {} ({})", id,
                originalWinner, original.probabilityOf(originalWinner),
                averaged.choice(), averaged.confidenceOrZero());
    }

    private static JevAnswer average(JevResponse raw, Group group) {
        Map<String, Double> sums = new LinkedHashMap<>();
        for (String label : group.labels()) {
            sums.put(label, 0.0d);
        }
        Set<String> expectedLabels = new HashSet<>(group.labels());
        for (String id : group.ids()) {
            JevAnswer answer = raw.answers().get(id);
            if (answer == null || !answer.isChoice()) {
                throw new IllegalStateException("missing Choice answer " + id);
            }
            Map<String, Double> probabilities = answer.probabilities();
            if (!probabilities.keySet().equals(expectedLabels)) {
                throw new IllegalStateException("answer " + id + " does not cover exactly the requested labels");
            }
            double mass = 0.0d;
            for (double p : probabilities.values()) {
                if (!Double.isFinite(p) || p < 0.0d || p > 1.0d) {
                    throw new IllegalStateException("answer " + id + " has a probability outside [0, 1]");
                }
                mass += p;
            }
            // The API rounds each probability to two decimals, so a large Choice can sum to 0.99;
            // allow half a hundredth per option and renormalise below.
            if (mass <= 0.0d || Math.abs(mass - 1.0d) > 0.005d * group.labels().size() + 1e-9) {
                throw new IllegalStateException("answer " + id + " probabilities do not sum to 1");
            }
            for (String label : group.labels()) {
                sums.merge(label, probabilities.get(label) / mass, Double::sum);
            }
        }
        int rows = group.ids().size();
        Map<String, Double> means = new LinkedHashMap<>();
        String winner = null;
        for (String label : group.labels()) {
            double mean = sums.get(label) / rows;
            means.put(label, mean);
            if (winner == null || mean > means.get(winner)) {
                winner = label;
            }
        }
        return new JevAnswer("choice", winner, means, null, null, null, means.get(winner));
    }

    /** Every ordering when there are at most {@code budget}, else a uniform sample without replacement. */
    private List<List<String>> orders(List<String> labels) {
        long total = 1L;
        for (int n = 2; n <= labels.size() && total <= budget; n++) {
            total *= n;
        }
        if (total <= budget) {
            List<List<String>> all = new ArrayList<>();
            permute(new ArrayList<>(labels), 0, all);
            return all;
        }
        RandomGenerator generator = random.get();
        Set<List<String>> selected = new LinkedHashSet<>();
        while (selected.size() < budget) {
            List<String> order = new ArrayList<>(labels);
            for (int i = order.size() - 1; i > 0; i--) {
                int j = generator.nextInt(i + 1);
                String swap = order.get(i);
                order.set(i, order.get(j));
                order.set(j, swap);
            }
            selected.add(List.copyOf(order));
        }
        return new ArrayList<>(selected);
    }

    private static void permute(List<String> labels, int from, List<List<String>> out) {
        if (from == labels.size() - 1) {
            out.add(List.copyOf(labels));
            return;
        }
        for (int i = from; i < labels.size(); i++) {
            Collections.swap(labels, from, i);
            permute(labels, from + 1, out);
            Collections.swap(labels, from, i);
        }
    }
}

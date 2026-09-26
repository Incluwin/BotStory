package server.agents.diagnostics.arena;

import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pure decision rules of the combat arena, kept apart from the world so they are testable:
 * which trial won, which candidates deserve a longer confirmation run, whether two rounds
 * agree, and from how many targets the area skill beats the single-target skill.
 */
public final class ArenaOptimizer {
    private ArenaOptimizer() {
    }

    /**
     * Skills whose damage is a function of the target's current HP rather than the character's
     * stats: Cosmic deals {@code monster.getHp() - 1} for Heaven's Hammer and the full HP bar for
     * Combo Tempest against non-bosses. On an arena dummy with an overridden HP pool they "deal"
     * the whole pool, so their DPS says something about the dummy, not about the skill. They are
     * kept out of the ranking and reported separately.
     */
    public static final List<Integer> TARGET_HP_PROPORTIONAL_SKILLS = List.of(1221011, 21120006);

    public static boolean rankableByDps(int skillId) {
        return !TARGET_HP_PROPORTIONAL_SKILLS.contains(skillId);
    }

    /** Damage per second, the reward every comparison uses. */
    public static double dps(Trial trial) {
        return trial.seconds() <= 0 ? 0 : (double) trial.damage() / trial.seconds();
    }

    public static Optional<Trial> best(List<Trial> trials) {
        return trials.stream().max(Comparator.comparingDouble(ArenaOptimizer::dps));
    }

    /** Skill ids of the top {@code count} trials by DPS, best first, without duplicates. */
    public static List<Integer> topSkills(List<Trial> trials, int count) {
        List<Integer> top = new ArrayList<>();
        trials.stream().sorted(Comparator.comparingDouble(ArenaOptimizer::dps).reversed())
                .forEach(trial -> {
                    if (top.size() < count && !top.contains(trial.skillId())) {
                        top.add(trial.skillId());
                    }
                });
        return top;
    }

    /** Mean DPS per skill over the given trials (used when a skill was run more than once). */
    public static Map<Integer, Double> meanDpsBySkill(List<Trial> trials) {
        Map<Integer, double[]> sums = new LinkedHashMap<>();
        for (Trial trial : trials) {
            double[] acc = sums.computeIfAbsent(trial.skillId(), ignored -> new double[2]);
            acc[0] += dps(trial);
            acc[1] += 1;
        }
        Map<Integer, Double> means = new LinkedHashMap<>();
        sums.forEach((skillId, acc) -> means.put(skillId, acc[0] / acc[1]));
        return means;
    }

    /**
     * Median DPS per skill. Repeated trials of one skill vary by tens of percent (mob spawn
     * position, buff refreshes, damage range), so the middle reading represents a skill better
     * than the average, which one unlucky or lucky run drags around.
     */
    public static Map<Integer, Double> medianDpsBySkill(List<Trial> trials) {
        Map<Integer, List<Double>> bySkill = new LinkedHashMap<>();
        for (Trial trial : trials) {
            bySkill.computeIfAbsent(trial.skillId(), ignored -> new ArrayList<>()).add(dps(trial));
        }
        Map<Integer, Double> medians = new LinkedHashMap<>();
        bySkill.forEach((skillId, values) -> medians.put(skillId, median(values)));
        return medians;
    }

    public static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    /** Spread (max minus min) of the DPS readings per skill; 0 when a skill was run once. */
    public static Map<Integer, Double> spreadBySkill(List<Trial> trials) {
        Map<Integer, double[]> range = new LinkedHashMap<>();
        for (Trial trial : trials) {
            double[] acc = range.computeIfAbsent(trial.skillId(),
                    ignored -> new double[] {Double.MAX_VALUE, -Double.MAX_VALUE});
            acc[0] = Math.min(acc[0], dps(trial));
            acc[1] = Math.max(acc[1], dps(trial));
        }
        Map<Integer, Double> spreads = new LinkedHashMap<>();
        range.forEach((skillId, acc) -> spreads.put(skillId, acc[1] - acc[0]));
        return spreads;
    }

    public static int bestSkillByMedian(List<Trial> trials) {
        return medianDpsBySkill(trials).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * Whether a win is bigger than the measurement noise. The gap between the two medians has to
     * clear the wider of the two spreads; inside that, the arena reports a tie rather than
     * pretending the ranking means something.
     */
    public static boolean decisive(double winnerMedian, double runnerUpMedian,
                                   double winnerSpread, double runnerUpSpread) {
        return winnerMedian - runnerUpMedian > Math.max(winnerSpread, runnerUpSpread);
    }

    /** Learned vs prior over runs paired by condition: means, the mean paired difference and its standard error. */
    public record PairedComparison(double learnedMean, double priorMean, double meanDifference, double standardError) {
        /** The difference clears two standard errors: not a coin flip. */
        public boolean decisive() {
            return standardError > 0 && Math.abs(meanDifference) > 2 * standardError;
        }
    }

    /**
     * Compares two policies measured in alternating pairs, each pair fought under the same
     * conditions (transformed or not, buffs at the same point). Pairing removes what the pair has
     * in common, which is most of the run-to-run variance when conditions alternate.
     */
    public static PairedComparison paired(List<Double> learned, List<Double> prior) {
        int n = Math.min(learned.size(), prior.size());
        if (n == 0) {
            return new PairedComparison(0, 0, 0, 0);
        }
        double sumLearned = 0;
        double sumPrior = 0;
        double[] differences = new double[n];
        for (int i = 0; i < n; i++) {
            sumLearned += learned.get(i);
            sumPrior += prior.get(i);
            differences[i] = learned.get(i) - prior.get(i);
        }
        double meanDifference = (sumLearned - sumPrior) / n;
        double squares = 0;
        for (double difference : differences) {
            squares += (difference - meanDifference) * (difference - meanDifference);
        }
        double standardError = n < 2 ? 0 : Math.sqrt(squares / (n - 1)) / Math.sqrt(n);
        return new PairedComparison(sumLearned / n, sumPrior / n, meanDifference, standardError);
    }

    public static int bestSkillByMean(List<Trial> trials) {
        return meanDpsBySkill(trials).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * The smallest target count at which the area skill out-damages the single-target skill,
     * given DPS measured per target count for both; 0 when the area skill never wins.
     */
    public static int aoeThreshold(Map<Integer, Double> singleDpsByTargets, Map<Integer, Double> multiDpsByTargets) {
        int threshold = 0;
        for (int targets : multiDpsByTargets.keySet().stream().sorted().toList()) {
            Double single = singleDpsByTargets.get(targets);
            Double multi = multiDpsByTargets.get(targets);
            if (single == null || multi == null) {
                continue;
            }
            if (multi > single) {
                return targets;
            }
        }
        return threshold;
    }

    /** Special moves pay off when the run with them out-damages the run without, allowing a small tolerance. */
    public static boolean specialMovesPayOff(double withDps, double withoutDps) {
        return withDps >= withoutDps * 0.97;
    }
}

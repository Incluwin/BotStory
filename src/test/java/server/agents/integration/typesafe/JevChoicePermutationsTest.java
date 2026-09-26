package server.agents.integration.typesafe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevChoicePermutationsTest {
    private static final Map<String, Double> TRUE_PREFERENCE = Map.of("a", 0.5d, "b", 0.3d, "c", 0.2d);

    @Test
    void choicePreservesCallerOptionOrder() {
        JevQuestion.Choice choice = new JevQuestion.Choice("pick", criteria("c", "a", "b"));

        assertEquals(List.of("c", "a", "b"), new ArrayList<>(choice.criteria().keySet()));
    }

    @Test
    void offSendsTheRequestUnchanged() {
        assertNull(JevChoicePermutations.off().plan(request()));
    }

    @Test
    void liveEnumeratesEveryOrderingAndCancelsPositionBias() {
        AtomicReference<JevRequest> sent = new AtomicReference<>();
        JevClient client = client(new JevChoicePermutations(JevChoicePermutations.Mode.LIVE, 8, 40), sent);

        JevResponse response = client.ask(request()).orElseThrow();

        Set<List<String>> orders = new HashSet<>();
        sent.get().questions().values().stream()
                .filter(JevQuestion.Choice.class::isInstance)
                .forEach(q -> orders.add(new ArrayList<>(((JevQuestion.Choice) q).criteria().keySet())));
        assertEquals(6, orders.size(), "all 3! orderings, each once");
        assertEquals(7, sent.get().questions().size(), "six permutations plus the untouched Noul");

        JevAnswer intent = response.answer("intent").orElseThrow();
        assertEquals("a", intent.choice(), "the position bias averages out, leaving the true preference");
        assertEquals(0.5d * 0.5d + 0.5d / 3, intent.probabilityOf("a"), 1e-9);
        assertEquals(intent.probabilityOf("a"), intent.confidenceOrZero(), 1e-9);
        assertEquals(0.7d, response.yesProbability("question"), 1e-9);
        assertEquals(Set.of("intent", "question"), response.answers().keySet());
    }

    @Test
    void shadowReturnsTheOriginalAnswerAndCountsDisagreement() {
        JevChoicePermutations permutations = new JevChoicePermutations(JevChoicePermutations.Mode.SHADOW, 8, 40);
        AtomicReference<JevRequest> sent = new AtomicReference<>();
        JevClient client = client(permutations, sent);

        JevAnswer intent = client.ask(request()).orElseThrow().answer("intent").orElseThrow();

        assertEquals("c", intent.choice(), "caller order c,a,b puts c first, so the biased original picks c");
        assertEquals(8, sent.get().questions().size(), "original + six permutations + Noul");
        assertEquals(1L, permutations.shadowComparisons());
        assertEquals(0L, permutations.shadowAgreements());
        assertTrue(client.diagnostics().contains("permutations=SHADOW"));
    }

    @Test
    void largeChoiceSamplesDistinctOrderingsUpToTheBudget() {
        JevChoicePermutations permutations = new JevChoicePermutations(
                JevChoicePermutations.Mode.LIVE, 8, 40, Set.of(), () -> new Random(7));
        JevRequest request = JevRequest.builder("s")
                .choice("pick", "pick", criteria("a", "b", "c", "d", "e"))
                .build();

        JevChoicePermutations.Plan plan = permutations.plan(request);

        Set<List<String>> orders = new HashSet<>();
        plan.expanded().questions().values()
                .forEach(q -> orders.add(new ArrayList<>(((JevQuestion.Choice) q).criteria().keySet())));
        assertEquals(8, orders.size());
    }

    @Test
    void expansionOverTheCapIsSentUnchanged() {
        JevChoicePermutations permutations = new JevChoicePermutations(JevChoicePermutations.Mode.LIVE, 8, 5);

        assertNull(permutations.plan(request()));
        assertTrue(permutations.diagnostics().contains("skippedOverCap=1"));
    }

    @Test
    void liveAcceptsTwoDecimalRoundingAndRenormalises() {
        JevClient client = new JevClient(configured(), (request, settings) -> {
            Map<String, JevAnswer> answers = new LinkedHashMap<>();
            request.questions().forEach((id, question) -> answers.put(id, question instanceof JevQuestion.Choice
                    ? new JevAnswer("choice", "a", Map.of("a", 0.66d, "b", 0.22d, "c", 0.11d), null, null, null, 0.66d)
                    : noul(0.7d)));
            return new JevResponse("m", answers, 1L, 1L, 1L);
        }, new JevCircuitBreaker(), new JevUsageMeter(),
                new JevChoicePermutations(JevChoicePermutations.Mode.LIVE, 8, 40));

        JevAnswer intent = client.ask(request()).orElseThrow().answer("intent").orElseThrow();

        assertEquals(0.66d / 0.99d, intent.probabilityOf("a"), 1e-9);
    }

    @Test
    void liveMissingPermutationAnswerFailsClosed() {
        JevClient client = new JevClient(configured(), (request, settings) -> new JevResponse("m",
                Map.of("question", noul(0.7d)), 1L, 1L, 1L),
                new JevCircuitBreaker(), new JevUsageMeter(),
                new JevChoicePermutations(JevChoicePermutations.Mode.LIVE, 8, 40));

        Optional<JevResponse> response = client.ask(request());

        assertTrue(response.isEmpty(), "a malformed expansion falls back like any other failure");
        assertEquals(1L, client.meter().failures());
    }

    private static JevRequest request() {
        return JevRequest.builder("state")
                .choice("intent", "What does the player want?", criteria("c", "a", "b"))
                .noul("question", "Is it a question?")
                .build();
    }

    private static Map<String, Object> criteria(String... options) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (String option : options) {
            criteria.put(option, "option " + option);
        }
        return criteria;
    }

    /** A fake Jev with a strong first-position bias: half its mass goes to whichever option is listed first. */
    private static JevClient client(JevChoicePermutations permutations, AtomicReference<JevRequest> sent) {
        return new JevClient(configured(), (request, settings) -> {
            sent.set(request);
            Map<String, JevAnswer> answers = new LinkedHashMap<>();
            request.questions().forEach((id, question) -> {
                if (question instanceof JevQuestion.Choice choice) {
                    String first = choice.criteria().keySet().iterator().next();
                    Map<String, Double> probabilities = new LinkedHashMap<>();
                    choice.criteria().keySet().forEach(label -> probabilities.put(label,
                            0.5d * TRUE_PREFERENCE.get(label) + (label.equals(first) ? 0.5d : 0.0d)));
                    String winner = probabilities.entrySet().stream()
                            .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                    answers.put(id, new JevAnswer("choice", winner, probabilities, null, null, null,
                            probabilities.get(winner)));
                } else {
                    answers.put(id, noul(0.7d));
                }
            });
            return new JevResponse("m", answers, 10L, 2L, 5L);
        }, new JevCircuitBreaker(), new JevUsageMeter(), permutations);
    }

    private static JevAnswer noul(double p) {
        return new JevAnswer("noul", null, null, p, null, null, null);
    }

    private static TypeSafeSettings configured() {
        return new TypeSafeSettings("test-key", TypeSafeSettings.DEFAULT_ENDPOINT, TypeSafeSettings.DEFAULT_MODEL, 1_000);
    }
}

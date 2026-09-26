package server.agents.capabilities.combat.learning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatLearnerTest {
    private static final int BRANDISH = 1121008;
    private static final int PANIC = 1111003;
    private static final int MAX_ORBS = 5;

    @Test
    void targetAddsTheDiscountedNextValue() {
        assertEquals(100 + 0.81 * 50, AgentCombatLearner.target(100, 2.0, 0.9, 50), 1e-9);
    }

    @Test
    void targetWithoutAnEstimateAssumesTheSameReturnKeepsComing() {
        // 100 per second forever, discounted at 0.9/s: 100 / (1 - 0.9) = 1000.
        assertEquals(1000, AgentCombatLearner.target(100, 1.0, 0.9, Double.NaN), 1e-9);
    }

    @Test
    void exploreTriesTheLeastTriedOptionFirst() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        table.update("s", 1, 500, 0.05, 0, 0, 1);
        table.update("s", 1, 500, 0.05, 0, 0, 1);
        table.update("s", 2, 10, 0.05, 0, 0, 1);
        Integer pick = AgentCombatLearner.explore(table, "s", List.of(1, 2, 3), 0.0, 3, new Random(1));
        assertEquals(3, pick);  // never tried beats a high value
    }

    @Test
    void exploreIsGreedyOnceEveryOptionHasItsTries() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        for (int i = 0; i < 3; i++) {
            table.update("s", 1, 100, 0.05, 0, 0, 1);
            table.update("s", 2, 300, 0.05, 0, 0, 1);
        }
        assertEquals(2, AgentCombatLearner.explore(table, "s", List.of(1, 2), 0.0, 3, new Random(1)));
    }

    @Test
    void priorsChargeEachOptionForItsTimeAndDropHopelessOnes() {
        java.util.Map<Integer, double[]> options = new java.util.LinkedHashMap<>();
        options.put(1, new double[] {1000, 1.0});   // 1000 dps, the best
        options.put(2, new double[] {600, 1.0});    // 600 dps
        options.put(3, new double[] {0, 1.0});      // lands nothing: 0 dps
        options.put(4, new double[] {3000, 1.0});   // a finisher: 3000 dps once, then the stack is gone
        AgentCombatLearner.Estimates estimates = AgentCombatLearner.estimates(options, java.util.Set.of(4),
                Double.NaN, 0.9, 0.35);
        assertEquals(List.of(1, 2, 4), estimates.worthTrying());
        // The finisher's number cannot be kept up, so the carry-on rate is the best skill that spends nothing.
        assertEquals(1000, estimates.bestDps(), 1e-9);
        // Carrying on at that rate is worth 1000 / -ln(0.9); an option adds its damage and loses its time.
        double carryOn = 1000 / -Math.log(0.9);
        assertEquals(1000 + 0.9 * carryOn, estimates.priors().get(1), 1e-6);
        assertTrue(estimates.priors().get(1) > estimates.priors().get(2));
        // A zero-damage cast that locks for 1.9 s is priced well below the best attack.
        assertTrue(AgentCombatLearner.prior(0, 1.9, 1000, 0.9) < 0.9 * estimates.priors().get(1));
        // Once the Agent has a measured rate, that is what everything carries on at.
        assertEquals(800, AgentCombatLearner.estimates(options, java.util.Set.of(4), 800, 0.9, 0.35).bestDps(), 1e-9);
    }

    @Test
    void aSeededPriorCountsAsPseudoVisits() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        table.seed("s", 1, 1000);
        table.update("s", 1, 0, 0.01, 3, 0, 1);   // one terrible roll against a prior worth 3 visits
        assertEquals(750, table.cell("s", 1).value(), 1e-9);
        table.seed("s", 1, 5000);           // seeding never overwrites what was learned
        assertEquals(750, table.cell("s", 1).value(), 1e-9);
        assertEquals(1, table.bestAction("s", List.of(1), 0));
    }

    /**
     * The acceptance test the combat plan sets: from no knowledge, the learner rediscovers the
     * "hold Combo to a full stack" rule that was hand-written as a prior. In this model a Panic at
     * two orbs out-damages the Brandish that would have been cast instead, so a learner that only
     * looked at each hit would spend the stack early; holding to five orbs is what pays over time.
     */
    @Test
    void learnsToHoldTheComboStackAlthoughAnEarlyFinisherHitsHarder() {
        double myopicBrandish = brandish(2);
        double myopicPanic = panic(2);
        assertTrue(myopicPanic > myopicBrandish, "the model must tempt a one-hit-greedy learner");

        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        Random random = new Random(7);
        int orbs = 0;
        String open = null;
        int openAction = 0;
        double openReward = 0;
        for (int step = 0; step < 30_000; step++) {
            String context = context(orbs);
            List<Integer> actions = orbs >= 1 ? List.of(BRANDISH, PANIC) : List.of(BRANDISH);
            if (open != null) {
                double next = table.bestValue(context, actions, 1);
                table.update(open, openAction, AgentCombatLearner.target(openReward, 1.0, 0.9, next), 0.05, 0, openReward, 1.0);
            }
            int action = AgentCombatLearner.explore(table, context, actions, 0.1, 3, random);
            open = context;
            openAction = action;
            if (action == PANIC) {
                openReward = panic(orbs);
                orbs = 0;
            } else {
                openReward = brandish(orbs);
                orbs = Math.min(MAX_ORBS, orbs + 1);
            }
        }

        for (int held = 1; held < MAX_ORBS; held++) {
            assertEquals(BRANDISH, table.bestAction(context(held), List.of(BRANDISH, PANIC), 1),
                    "at " + held + " orbs the learner should keep building");
        }
        assertEquals(PANIC, table.bestAction(context(MAX_ORBS), List.of(BRANDISH, PANIC), 1),
                "with a full stack the learner should cash it in");
    }

    private static String context(int orbs) {
        return AgentCombatLearningContext.key(1, false,
                AgentCombatLearningContext.orbBucket(orbs + 1, MAX_ORBS), false, false, null, false, false);
    }

    /** Brandish: steady damage, raised by the flat per-orb Combo bonus; adds an orb. */
    private static double brandish(int orbs) {
        return 100 * (1 + 0.1 * orbs);
    }

    /** Panic: the server's finisher multiplier by orbs (x1.2 at 2 ... x2.5 at 5); spends the stack. */
    private static double panic(int orbs) {
        double multiplier = switch (orbs) {
            case 2 -> 1.2;
            case 3 -> 1.54;
            case 4 -> 2.0;
            case 5 -> 2.5;
            default -> 1.0;
        };
        return 150 * multiplier * (1 + 0.1 * orbs);
    }
}

package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/ECONOMY_PLAN.md 9 R4: one setting drives an unwatched Agent's attack lock and touch-hit cooldown, so a fight
 * speeds up evenly: at every step it takes about as many touch hits a swing as on a watched map.
 */
class AgentBackgroundCombatClockTest {
    private static final int BACKGROUND_TICK_MS = 250;
    private static final int STEP_MS = 50;
    private static final int SWING_MS = 700;
    private static final int TOUCH_COOLDOWN_MS = 1_500;

    @Test
    void stepsFollowTheSettingUpToWhatMovementRuns() {
        for (int configured = 1; configured <= 5; configured++) {
            assertEquals(configured, AgentBackgroundCombatClock.steps(5, configured));
        }
        assertEquals(5, AgentBackgroundCombatClock.steps(5, 9), "never more than movement runs");
        assertEquals(1, AgentBackgroundCombatClock.steps(1, 5), "a foreground Agent counts one step");
        assertEquals(1, AgentBackgroundCombatClock.steps(0, 3));
        assertEquals(1, AgentBackgroundCombatClock.steps(5, 0), "never under one");
    }

    @Test
    void itShipsTheOldClock() {
        assertEquals(1, config.AgentTuning.intValue("server.agents.capabilities.combat.AgentBackgroundCombatClock.STEPS"));
        assertEquals(1, AgentBackgroundCombatClock.steps(5));
    }

    @Test
    void hitsASwingStayAsOnAWatchedMapAtEveryStep() {
        double watched = (double) SWING_MS / TOUCH_COOLDOWN_MS;
        for (int steps = 1; steps <= 5; steps++) {
            double ratio = (double) heldMs(SWING_MS, steps) / heldMs(TOUCH_COOLDOWN_MS, steps);
            assertTrue(ratio >= watched * 0.9 && ratio <= watched * 1.2, steps + " steps: " + ratio + " vs " + watched);
        }
        // why one setting: either clock alone puts a kill about five times off
        double lockAlone = (double) heldMs(SWING_MS, 5) / heldMs(TOUCH_COOLDOWN_MS, 1);
        double touchAlone = (double) heldMs(SWING_MS, 1) / heldMs(TOUCH_COOLDOWN_MS, 5);
        assertTrue(lockAlone < watched / 4, "attack lock alone: " + lockAlone);
        assertTrue(touchAlone > watched * 4, "touch cooldown alone: " + touchAlone);
    }

    /** How long a {@code ms} countdown holds a BACKGROUND_ACTIVE Agent (5 substeps) with the clock at {@code configured}. */
    private static int heldMs(int ms, int configured) {
        java.util.function.IntUnaryOperator tick = AgentCombatDamageRuntime.repeated(
                AgentBackgroundCombatClock.steps(5, configured), left -> Math.max(0, left - STEP_MS));
        int held = 0;
        for (int left = ms; left > 0; left = tick.applyAsInt(left)) {
            held += BACKGROUND_TICK_MS;
        }
        return held;
    }
}

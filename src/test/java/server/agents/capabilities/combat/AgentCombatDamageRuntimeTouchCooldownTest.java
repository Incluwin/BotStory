package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * docs/ECONOMY_PLAN.md 9 R3: on a map no player sees (BACKGROUND_ACTIVE, 250 ms ticks, 5 movement substeps) the touch-hit
 * cooldown counted one 50 ms step a tick, so a 1.5 s cooldown protected an Agent for 7.5 s. It now counts
 * {@link AgentBackgroundCombatClock#steps} steps, the same count as the attack lock (R4).
 */
class AgentCombatDamageRuntimeTouchCooldownTest {
    private static final int STEP_MS = 50;
    private static final IntUnaryOperator STEP = ms -> Math.max(0, ms - STEP_MS);

    @Test
    void theOldClockCountsOneStepATickWhateverMovementRuns() {
        assertEquals(1450, countdown(5, 1).applyAsInt(1500));
        assertEquals(1450, countdown(1, 1).applyAsInt(1500));
    }

    @Test
    void itCountsTheClockStepsInTheBackgroundAndForegroundStaysOneStep() {
        assertEquals(1250, countdown(5, 5).applyAsInt(1500));
        assertEquals(1400, countdown(5, 2).applyAsInt(1500));
        assertEquals(1450, countdown(1, 5).applyAsInt(1500));
        assertEquals(1450, countdown(0, 5).applyAsInt(1500), "at least one step");
    }

    @Test
    void itStopsAtZero() {
        assertEquals(0, countdown(5, 5).applyAsInt(120));
    }

    @Test
    void aBackgroundHitProtectsForAboutTheCooldownOnlyAtFullSpeed() {
        assertEquals(7_500, clearedAfterMs(5, 1, 250), "the old clock: five times the cooldown");
        assertEquals(3_750, clearedAfterMs(5, 2, 250));
        assertEquals(1_500, clearedAfterMs(5, 5, 250));
        assertEquals(1_500, clearedAfterMs(1, 1, STEP_MS), "foreground, at any setting");
        assertEquals(1_500, clearedAfterMs(1, 5, STEP_MS));
    }

    @Test
    void itShipsTheOldClock() {
        assertEquals(1450, AgentCombatDamageRuntime.touchCooldownCountdown(5, STEP).applyAsInt(1500));
    }

    private static IntUnaryOperator countdown(int substeps, int configured) {
        return AgentCombatDamageRuntime.repeated(AgentBackgroundCombatClock.steps(substeps, configured), STEP);
    }

    private static int clearedAfterMs(int substeps, int configured, int tickMs) {
        IntUnaryOperator countdown = countdown(substeps, configured);
        int left = 1_500;
        int elapsed = 0;
        while (left > 0) {
            left = countdown.applyAsInt(left);
            elapsed += tickMs;
        }
        return elapsed;
    }
}

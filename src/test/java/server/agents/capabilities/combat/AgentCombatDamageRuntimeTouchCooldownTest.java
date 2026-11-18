package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * docs/ECONOMY_PLAN.md 9 R3: on a map no player sees (BACKGROUND_ACTIVE, 250 ms ticks, 5 movement substeps) the touch-hit
 * cooldown counted one 50 ms step a tick, so a 1.5 s cooldown protected an Agent for 7.5 s. With the switch on it counts
 * as many steps as movement runs, as on a watched map.
 */
class AgentCombatDamageRuntimeTouchCooldownTest {
    private static final int STEP_MS = 50;
    private static final IntUnaryOperator STEP = ms -> Math.max(0, ms - STEP_MS);

    @Test
    void offItCountsOneStepATickWhateverMovementRuns() {
        assertEquals(1450, AgentCombatDamageRuntime.touchCooldownCountdown(5, false, STEP).applyAsInt(1500));
        assertEquals(1450, AgentCombatDamageRuntime.touchCooldownCountdown(1, false, STEP).applyAsInt(1500));
    }

    @Test
    void onItCountsAsManyStepsAsMovementRunsAndForegroundStaysOneStep() {
        assertEquals(1250, AgentCombatDamageRuntime.touchCooldownCountdown(5, true, STEP).applyAsInt(1500));
        assertEquals(1450, AgentCombatDamageRuntime.touchCooldownCountdown(1, true, STEP).applyAsInt(1500));
        assertEquals(1450, AgentCombatDamageRuntime.touchCooldownCountdown(0, true, STEP).applyAsInt(1500),
                "at least one step");
    }

    @Test
    void itStopsAtZero() {
        assertEquals(0, AgentCombatDamageRuntime.touchCooldownCountdown(5, true, STEP).applyAsInt(120));
    }

    @Test
    void aBackgroundHitProtectsForAboutTheCooldownOnlyWhenOn() {
        assertEquals(7_500, clearedAfterMs(5, false, 250), "today: five times the cooldown");
        assertEquals(1_500, clearedAfterMs(5, true, 250));
        assertEquals(1_500, clearedAfterMs(1, false, STEP_MS), "foreground, either way");
        assertEquals(1_500, clearedAfterMs(1, true, STEP_MS));
    }

    @Test
    void itShipsOff() {
        assertEquals(1450, AgentCombatDamageRuntime.touchCooldownCountdown(5, STEP).applyAsInt(1500));
    }

    private static int clearedAfterMs(int substeps, boolean enabled, int tickMs) {
        IntUnaryOperator countdown = AgentCombatDamageRuntime.touchCooldownCountdown(substeps, enabled, STEP);
        int left = 1_500;
        int elapsed = 0;
        while (left > 0) {
            left = countdown.applyAsInt(left);
            elapsed += tickMs;
        }
        return elapsed;
    }
}

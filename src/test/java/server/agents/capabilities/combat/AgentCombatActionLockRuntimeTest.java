package server.agents.capabilities.combat;

import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.agents.runtime.simulation.AgentSimulationMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatActionLockRuntimeTest {
    @Test
    void ticksAttackCooldownBeforeMoveWindow() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, 120);
        AgentCombatCooldownStateRuntime.maxMoveWindow(entry, 90);

        AgentCombatActionLockRuntime.tickActionLock(entry);

        assertEquals(70, AgentCombatCooldownStateRuntime.attackCooldownMs(entry));
        assertEquals(90, AgentCombatCooldownStateRuntime.moveWindowMs(entry));
    }

    @Test
    void ticksMoveWindowAfterAttackCooldownClears() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentCombatCooldownStateRuntime.maxMoveWindow(entry, 90);

        AgentCombatActionLockRuntime.tickActionLock(entry);

        assertEquals(40, AgentCombatCooldownStateRuntime.moveWindowMs(entry));
    }

    @Test
    void publicTickFollowsTheConfiguredSwitchInTheBackground() {
        boolean on = config.AgentTuning.booleanValue(
                "server.agents.capabilities.combat.AgentCombatActionLockRuntime.BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED");
        AgentRuntimeEntry entry = background();
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, 700);

        AgentCombatActionLockRuntime.tickActionLock(entry);

        int steps = on ? backgroundSubsteps() : 1;
        assertEquals(700 - steps * movementTickMs(), AgentCombatCooldownStateRuntime.attackCooldownMs(entry));
    }

    @Test
    void switchOnRunsMovementSubstepsOnlyInBackgroundActive() {
        assertEquals(1, AgentCombatActionLockRuntime.countdownSteps(background(), false));
        assertEquals(1, AgentCombatActionLockRuntime.countdownSteps(new AgentRuntimeEntry(null, null, null), true));
        AgentRuntimeEntry abstractEntry = new AgentRuntimeEntry(null, null, null);
        abstractEntry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ABSTRACT, 0L);
        assertEquals(1, AgentCombatActionLockRuntime.countdownSteps(abstractEntry, true));
        assertEquals(backgroundSubsteps(), AgentCombatActionLockRuntime.countdownSteps(background(), true));
    }

    @Test
    void backgroundLockClearsInAboutItsAnimationTime() {
        int tickMs = backgroundTickMs();
        for (int animationMs : new int[] {300, 540, 600, 700, 810, 900, 1_200, 2_000}) {
            AgentRuntimeEntry entry = background();
            AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, animationMs);

            int heldMs = heldMs(entry, AgentCombatActionLockRuntime.countdownSteps(entry, true), tickMs);

            // The swing that set the lock is followed by one on the first scheduled tick at or after its animation.
            assertTrue(heldMs >= animationMs, animationMs + " ms swing released early, after " + heldMs + " ms");
            assertTrue(heldMs < animationMs + tickMs, animationMs + " ms swing held " + heldMs + " ms");
        }
    }

    @Test
    void backgroundLockWithoutTheFixHoldsFiveTimesItsAnimation() {
        AgentRuntimeEntry entry = background();
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, 700);

        int heldMs = heldMs(entry, AgentCombatActionLockRuntime.countdownSteps(entry, false), backgroundTickMs());

        assertEquals(700 / movementTickMs() * backgroundTickMs(), heldMs);
    }

    @Test
    void foregroundLockTimingIsUnchangedWithTheSwitchOn() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, 700);

        int heldMs = heldMs(entry, AgentCombatActionLockRuntime.countdownSteps(entry, true), movementTickMs());

        assertEquals(700, heldMs);
    }

    @Test
    void timeLeftAfterTheLockClearsGoesOnToTheMoveWindow() {
        AgentRuntimeEntry entry = background();
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, 100);
        AgentCombatCooldownStateRuntime.maxMoveWindow(entry, 200);

        AgentCombatActionLockRuntime.tickActionLock(entry, 5);

        assertFalse(AgentCombatCooldownStateRuntime.hasAttackCooldown(entry));
        assertEquals(200 - 3 * movementTickMs(), AgentCombatCooldownStateRuntime.moveWindowMs(entry));
    }

    @Test
    void backgroundMoveWindowAloneClearsInItsOwnTime() {
        AgentRuntimeEntry entry = background();
        AgentCombatCooldownStateRuntime.setMoveWindowMs(entry, 1_000);

        int ticks = 0;
        while (AgentCombatCooldownStateRuntime.hasMoveWindow(entry)) {
            AgentCombatActionLockRuntime.tickActionLock(entry, AgentCombatActionLockRuntime.countdownSteps(entry, true));
            ticks++;
        }

        assertEquals(1_000, ticks * backgroundTickMs());
    }

    @Test
    void anIdleLockRunsNoSteps() {
        AgentRuntimeEntry entry = background();

        AgentCombatActionLockRuntime.tickActionLock(entry, 5);

        assertEquals(0, AgentCombatCooldownStateRuntime.attackCooldownMs(entry));
        assertEquals(0, AgentCombatCooldownStateRuntime.moveWindowMs(entry));
    }

    /** Scheduled ticks until the attack lock clears, in milliseconds of {@code tickMs} each. */
    private static int heldMs(AgentRuntimeEntry entry, int stepsPerTick, int tickMs) {
        int ticks = 0;
        while (AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)) {
            AgentCombatActionLockRuntime.tickActionLock(entry, stepsPerTick);
            ticks++;
            if (ticks > 10_000) {
                throw new AssertionError("the lock never cleared");
            }
        }
        return ticks * tickMs;
    }

    private static AgentRuntimeEntry background() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        return entry;
    }

    private static int movementTickMs() {
        return AgentMovementPhysicsConfig.configuredMovementTickMs();
    }

    private static int backgroundTickMs() {
        return (int) AgentSchedulerConfig.fromSystemProperties().backgroundActiveTickMs();
    }

    private static int backgroundSubsteps() {
        return backgroundTickMs() / movementTickMs();
    }
}

package server.agents.capabilities.combat;

import server.agents.capabilities.movement.AgentMovementSubstepPolicy;
import server.agents.capabilities.movement.AgentMovementTimers;

import server.agents.runtime.AgentRuntimeEntry;

/**
 * Counts the attack lock and, once it is clear, the move window down for one scheduled tick.
 *
 * <p>Each step takes off one movement tick (AgentMovementTimers.tickDown, MOVEMENT_TICK_MS). A foreground Agent runs
 * one step a tick. A BACKGROUND_ACTIVE Agent is ticked every DEFAULT_BACKGROUND_ACTIVE_TICK_MS (250 ms) and its
 * movement runs AgentMovementSubstepPolicy's substeps (5) to cover that time; with BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED
 * its lock runs the same number of steps, so a 700 ms swing holds the next one about 750 ms, not 3.5 s.</p>
 */
public final class AgentCombatActionLockRuntime {
    private static final boolean BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.combat.AgentCombatActionLockRuntime.BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED");

    private AgentCombatActionLockRuntime() {
    }

    public static void tickActionLock(AgentRuntimeEntry entry) {
        tickActionLock(entry, countdownSteps(entry, BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED));
    }

    /** Steps this tick counts down: the movement substeps when the switch is on (1 outside BACKGROUND_ACTIVE), else 1. */
    static int countdownSteps(AgentRuntimeEntry entry, boolean backgroundSubstepCountdown) {
        return backgroundSubstepCountdown ? AgentMovementSubstepPolicy.substeps(entry) : 1;
    }

    /**
     * {@code steps} foreground ticks of countdown: each step takes a movement tick off the attack lock, or off the move
     * window once the lock is clear, as that many 50 ms ticks would, so time left over after the lock clears goes on
     * to the move window.
     */
    static void tickActionLock(AgentRuntimeEntry entry, int steps) {
        for (int step = 0; step < steps; step++) {
            if (AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)) {
                AgentCombatCooldownStateRuntime.tickAttackCooldown(entry, AgentMovementTimers::tickDown);
            } else if (AgentCombatCooldownStateRuntime.hasMoveWindow(entry)) {
                AgentCombatCooldownStateRuntime.tickMoveWindow(entry, AgentMovementTimers::tickDown);
            } else {
                return;
            }
        }
    }
}

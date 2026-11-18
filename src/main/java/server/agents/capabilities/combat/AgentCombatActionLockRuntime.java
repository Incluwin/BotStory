package server.agents.capabilities.combat;

import server.agents.capabilities.movement.AgentMovementTimers;

import server.agents.runtime.AgentRuntimeEntry;

/**
 * Counts the attack lock and, once it is clear, the move window down for one scheduled tick.
 *
 * <p>Each step takes off one movement tick (AgentMovementTimers.tickDown, MOVEMENT_TICK_MS). A foreground Agent runs
 * one step a tick. A BACKGROUND_ACTIVE Agent is ticked every DEFAULT_BACKGROUND_ACTIVE_TICK_MS (250 ms) and its
 * movement runs AgentMovementSubstepPolicy's substeps (5) to cover that time; its lock runs
 * {@link AgentBackgroundCombatClock#steps} of them, so at full speed (5) a 700 ms swing holds the next one about 750 ms,
 * not 3.5 s. The runtime tick (AgentCommonTickRuntime) passes that substep count in, so combat takes no new dependency on
 * movement policy.</p>
 */
public final class AgentCombatActionLockRuntime {
    private AgentCombatActionLockRuntime() {
    }

    /** One foreground tick: one step of countdown. */
    public static void tickActionLock(AgentRuntimeEntry entry) {
        countDown(entry, 1);
    }

    /**
     * One scheduled tick of an Agent whose movement runs {@code movementSubsteps} physics steps this tick
     * (AgentMovementSubstepPolicy.substeps): {@link AgentBackgroundCombatClock#steps} steps of countdown.
     */
    public static void tickActionLock(AgentRuntimeEntry entry, int movementSubsteps) {
        countDown(entry, AgentBackgroundCombatClock.steps(movementSubsteps));
    }

    /**
     * {@code steps} foreground ticks of countdown: each step takes a movement tick off the attack lock, or off the move
     * window once the lock is clear, as that many 50 ms ticks would, so time left over after the lock clears goes on
     * to the move window.
     */
    static void countDown(AgentRuntimeEntry entry, int steps) {
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

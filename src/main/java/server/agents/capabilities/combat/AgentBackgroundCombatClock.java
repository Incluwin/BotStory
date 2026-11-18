package server.agents.capabilities.combat;

/**
 * How fast an unwatched Agent's combat clocks run (docs/ECONOMY_PLAN.md 9 R4).
 *
 * <p>A BACKGROUND_ACTIVE Agent is ticked every 250 ms and its movement runs 5 physics substeps of 50 ms a tick. Its
 * attack lock ({@link AgentCombatActionLockRuntime}) and its touch-hit cooldown ({@link AgentCombatDamageRuntime}) both
 * count down {@code STEPS} of those 50 ms steps a tick, never more than movement runs and never fewer than one. At 1
 * both clocks run about five times slow, so an unwatched fight is slow but takes about as many hits a swing as a
 * watched one; at 5 both run at full speed. One setting drives both so a fight speeds up evenly: the attack lock alone
 * would make each kill about five times easier, the touch cooldown alone about five times harder. A foreground Agent
 * runs one substep, so it always counts one step. Read once at class load.</p>
 */
public final class AgentBackgroundCombatClock {
    private static final int STEPS = config.AgentTuning.intValue(
            "server.agents.capabilities.combat.AgentBackgroundCombatClock.STEPS");

    private AgentBackgroundCombatClock() {
    }

    /** Steps a scheduled tick counts down for an Agent whose movement runs {@code movementSubsteps} this tick. */
    public static int steps(int movementSubsteps) {
        return steps(movementSubsteps, STEPS);
    }

    static int steps(int movementSubsteps, int configured) {
        return Math.max(1, Math.min(movementSubsteps, configured));
    }
}

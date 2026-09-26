package server.agents.diagnostics.arena;

/**
 * Plausibility checks the arena runs against its own readings.
 *
 * <p>Measuring damage per second is not enough on its own: every wrong answer this harness has
 * produced so far was a number that looked perfectly ordinary in the table and was only caught by
 * someone who knew how the class actually plays. A skill that "deals" the dummy's whole HP bar, a
 * keydown nuke cast at a rate no player can reach, a charge buff standing that the damage handler
 * ignores - each one wins its scenario and reads as a result.</p>
 *
 * <p>So the arena checks its readings against the game's own data and reports what does not add
 * up, instead of leaving it to be noticed. The rules here are deliberately about impossibility,
 * not about expectations of which skill "should" win - the point of measuring is to be surprised.</p>
 */
public final class ArenaAnomalyChecks {
    private ArenaAnomalyChecks() {
    }

    /**
     * Whether a skill was cast faster than its own animation allows. {@code expectedIntervalMs} is
     * the skill's animation plus any keydown charge; attack speed and boosters legitimately shave
     * this down, so only a reading well under the floor counts.
     */
    public static boolean castRateImpossible(int attacks, int seconds, int expectedIntervalMs, double tolerance) {
        if (attacks <= 0 || seconds <= 0 || expectedIntervalMs <= 0) {
            return false;
        }
        double observedIntervalMs = (seconds * 1000.0) / attacks;
        return observedIntervalMs < expectedIntervalMs * tolerance;
    }

    public static double observedIntervalMs(int attacks, int seconds) {
        return attacks <= 0 ? 0 : (seconds * 1000.0) / attacks;
    }

    /**
     * Whether the reading is bounded by the dummy rather than by the skill. True both for damage
     * that is defined as the target's HP (Heaven's Hammer) and for a skill strong enough to empty
     * the pool inside the trial - in either case the number measures the dummy, not the rotation.
     */
    public static boolean damageBoundedByDummy(long damage, long mobMaxHp, int targets) {
        if (damage <= 0 || mobMaxHp <= 0 || targets <= 0) {
            return false;
        }
        return damage >= (long) (mobMaxHp * (long) targets * 0.9);
    }

    /** A trial that planned and attacked but moved no HP at all: the plan is not reaching the target. */
    public static boolean attackedWithoutDamage(int attacks, long damage) {
        return attacks > 0 && damage <= 0;
    }
}

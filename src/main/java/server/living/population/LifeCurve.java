package server.living.population;

/**
 * How fast a casual player levels (plan 3.4): one level takes {@code 0.5 + level/20} hours of
 * play (the bench ledger's model) and a citizen plays about {@link #HOURS_PER_DAY} hours on an
 * average day, scaled by its personal pace. Past its career cap growth slows to a fifth so long
 * lived citizens plateau instead of racing to 200.
 */
public final class LifeCurve {
    static final double HOURS_PER_DAY = 1.2d;
    private static final double PAST_CAP_FACTOR = 0.2d;

    private LifeCurve() {
    }

    /** Hours of play needed to go from level {@code level} to the next. */
    public static double hoursForLevel(int level) {
        return 0.5d + level / 20.0d;
    }

    /** Level a citizen of this age and pace should be at, given its career cap. */
    public static int levelTarget(int ageDays, double pace, int levelCap, double lifePace) {
        double hours = Math.max(0, ageDays) * HOURS_PER_DAY * Math.max(0.1d, pace) * Math.max(0.1d, lifePace);
        int level = 1;
        while (level < 200) {
            double need = hoursForLevel(level) * (level >= levelCap ? 1.0d / PAST_CAP_FACTOR : 1.0d);
            if (hours < need) {
                break;
            }
            hours -= need;
            level++;
        }
        return level;
    }

    /** EXP per hour a citizen of this level earns on the ledger (a level's worth spread over its hours). */
    public static double expPerHour(int level) {
        return constants.game.ExpTable.getExpNeededForLevel(Math.max(1, Math.min(199, level))) / hoursForLevel(level);
    }
}

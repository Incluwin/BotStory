package server.living.audit;

/**
 * Where each tell audit measure looks human (docs/BOT_CONCEPT_PLAN.md C2). The red lines are the plan's "tell when"
 * values; the amber margins are priors that the 24 h live baseline (C2.4) is to settle. A measure with fewer samples
 * than its band asks for has no verdict yet.
 */
public final class TellBands {
    public enum Verdict {
        GREEN, AMBER, RED, NO_DATA, INFO;

        /** One letter for the compact hourly row: G, A, R, - (no data) or i (information only). */
        char letter() {
            return switch (this) {
                case GREEN -> 'G';
                case AMBER -> 'A';
                case RED -> 'R';
                case NO_DATA -> '-';
                case INFO -> 'i';
            };
        }
    }

    /**
     * {@code higherIsHuman}: a value under {@code red} is red and under {@code amber} amber; else a value over
     * {@code red} is red and over {@code amber} amber. {@code minN}: samples (or bots) needed for a verdict.
     */
    public record Band(String human, boolean higherIsHuman, double red, double amber, long minN) {
        Verdict judge(double value, long n) {
            if (n < minN || Double.isNaN(value)) {
                return Verdict.NO_DATA;
            }
            if (higherIsHuman) {
                return value < red ? Verdict.RED : value < amber ? Verdict.AMBER : Verdict.GREEN;
            }
            return value > red ? Verdict.RED : value > amber ? Verdict.AMBER : Verdict.GREEN;
        }
    }

    /** Each bot's own reactions to new mobs vary: their coefficient of variation, the median over bots. */
    static final Band COMBAT_REACTION_CV = new Band("each bot's reaction times vary (per-bot CV >= 0.25)",
            true, 0.25, 0.30, 3);
    /** Nobody reacts faster than about 150 ms: judged at the 5th percentile, so one stray sample does not decide. */
    static final Band REACTION_P5_MS = new Band("reactions no faster than a person's (5th percentile >= 150 ms)",
            true, 150, 180, 30);
    static final Band CHAT_REPLY_CV = new Band("chat reply delays vary (CV >= 0.25)", true, 0.25, 0.30, 20);
    static final Band CHAT_CADENCE_CV = new Band("each speaker's gaps between lines vary (per-speaker CV >= 0.25)",
            true, 0.25, 0.30, 3);
    static final Band CHAT_REPEAT = new Band("a line is seldom said again on one map within 10 min (<= 2%)",
            false, 0.02, 0.005, 50);
    static final Band STACKING = new Band("idle bots seldom stand 3 or more within 30 px (<= 5% of idle samples)",
            false, 0.05, 0.02, 100);
    static final Band ONLINE_SHARE = new Band("no citizen online more than 20 h a day (share of its days online)",
            false, 20.0 / 24, 18.0 / 24, 1);
    static final Band FIELD_AFK = new Band("no AFK longer than 2 min on a field", false, 0, 0, 1);
    static final Band ROUTE_REPEAT = new Band("repeat walks seldom retrace the same route (<= 60%)",
            false, 0.60, 0.45, 10);
    static final Band POTION_POINT = new Band("drinks spread over HP points (<= 80% at one HP %)",
            false, 0.80, 0.65, 30);
    static final Band BUFF_RECAST = new Band("recasts spread over the buff's run (<= 80% at one % of duration)",
            false, 0.80, 0.65, 30);

    private TellBands() {
    }
}

package server.living.population;

import server.agents.capabilities.behavior.AgentJumpNoise;

/**
 * The jump-quest noise's calibration (docs/BOT_CONCEPT_PLAN.md F5b). Navigation picks a jump's launch inside the edge's
 * window, a margin from its ends; the noise (AgentJumpNoise.launchX) moves it by a whole-pixel draw from N(0, sigma),
 * kept on the platform (never past navigation's own pick), and keeps the press only when the jump probe calls it safe,
 * else draws again (REDRAWS tries, then navigation's own pick). The jump lands when the kept press is one the probe lands
 * on its target (JumpQuestCourses: every pixel of the platform, so a one-pixel hole in a landing run does not cut it short).
 * A jump's chance is the average over the picks, a stage's the product over its jumps, and {@link #sigmaFor} finds the
 * sigma that gives a stage the citizen's chance: a narrow jump fails more often than a wide one, as for people, and each
 * citizen clears a stage about as often as its skill says.
 */
public final class JumpWindows {
    /** Navigation keeps its pick this far inside a window (two walk steps; AgentNavigationWaypointService). */
    static final int PICK_MARGIN_PX = 12;
    public static final double SIGMA_MIN = 0.5d;
    public static final double SIGMA_MAX = 40.0d;
    /** A landing window open on that side: it reaches the platform's end, and a press past the end launches from there. */
    public static final int OPEN = 1_000_000;
    private static final int PICKS = 7;

    private JumpWindows() {
    }

    /**
     * One jump: navigation picks its launch in {@code pickLow..pickHigh} (the graph window); a press goes no farther than
     * {@code pressLow..pressHigh} (the platform less the noise's edge margin; never past navigation's own pick). For a
     * press from {@code x} in {@code first..first + lands.length - 1}: {@code lands} whether the probe lands it on the
     * jump's target, {@code safe} whether the noise keeps it (AgentJumpNoise.safePress over its readiness band). A
     * press outside that run is never drawn (MAX_OFFSET_PX).
     */
    public record Jump(int pickLow, int pickHigh, int pressLow, int pressHigh, int first, boolean[] lands, boolean[] safe) {
        /**
         * A jump that lands from {@code okLow..okHigh} ({@code -OPEN}/{@code OPEN}: to the end of what a press can
         * reach on that side) and misses safely from everywhere else, on a platform with no end in reach.
         */
        public Jump(int pickLow, int pickHigh, int okLow, int okHigh) {
            this(pickLow, pickHigh, Integer.MIN_VALUE, Integer.MAX_VALUE, pickLow - AgentJumpNoise.MAX_OFFSET_PX,
                    run(pickLow - AgentJumpNoise.MAX_OFFSET_PX, pickHigh + AgentJumpNoise.MAX_OFFSET_PX, okLow, okHigh),
                    run(pickLow - AgentJumpNoise.MAX_OFFSET_PX, pickHigh + AgentJumpNoise.MAX_OFFSET_PX,
                            Integer.MIN_VALUE, Integer.MAX_VALUE));
        }

        private static boolean[] run(int from, int to, int low, int high) {
            boolean[] out = new boolean[to - from + 1];
            for (int x = from; x <= to; x++) {
                out[x - from] = x >= low && x <= high;
            }
            return out;
        }

        /** True when the probe lands a press from {@code x} on the jump's target. */
        public boolean landsFrom(int x) {
            int i = x - first;
            return i >= 0 && i < lands.length && lands[i];
        }

        /** True when the noise keeps a press from {@code x} (a refused one is drawn again). */
        public boolean keptFrom(int x) {
            int i = x - first;
            return i >= 0 && i < safe.length && safe[i];
        }
    }

    /**
     * The chance a stage of {@code jumps} jumps is cleared with, for a citizen whose skill is its chance on a stage of
     * JumpQuestCourses.STAGE_JUMPS: a shorter stage (one that ends at a rope's top) is that much easier, so a course is as
     * hard however its stages fall.
     */
    public static double targetFor(double skill, int jumps) {
        return jumps <= 0 ? 1.0d : Math.pow(skill, jumps / (double) JumpQuestCourses.STAGE_JUMPS);
    }

    /** The chance {@code jump} lands with a press spread of {@code sigma} pixels. */
    public static double jumpSuccess(Jump jump, double sigma) {
        return jumpSuccess(jump, sigma, offsets(sigma));
    }

    /**
     * As AgentJumpNoise.launchX takes it, for each of the picks: a draw of {@code offset} pixels (whole, capped at
     * MAX_OFFSET_PX) is kept on the platform and never past the pick; the pick itself is taken as navigation would,
     * any other press only when the probe keeps it, else the next draw, and after REDRAWS refused draws the pick.
     */
    private static double jumpSuccess(Jump jump, double sigma, double[] offsets) {
        int width = Math.max(0, jump.pickHigh() - jump.pickLow());
        int margin = Math.min(width / 2, PICK_MARGIN_PX);
        int low = jump.pickLow() + margin;
        int high = jump.pickHigh() - margin;
        int reach = (offsets.length - 1) / 2;
        double sum = 0.0d;
        for (int i = 0; i < PICKS; i++) {
            int pick = high <= low ? (low + high) / 2 : (int) Math.round(low + (high - low) * i / (double) (PICKS - 1));
            double exact = jump.landsFrom(pick) ? 1.0d : 0.0d;
            if (sigma <= 0.0d) {
                sum += exact;
                continue;
            }
            int pressLow = Math.min(jump.pressLow(), pick);
            int pressHigh = Math.max(jump.pressHigh(), pick);
            double hit = 0.0d;
            double refused = 0.0d;
            for (int k = -reach; k <= reach; k++) {
                double chance = offsets[k + reach];
                int press = Math.max(pressLow, Math.min(pressHigh, pick + k));
                if (press == pick) {
                    hit += chance * exact;
                } else if (!jump.keptFrom(press)) {
                    refused += chance;
                } else if (jump.landsFrom(press)) {
                    hit += chance;
                }
            }
            double kept = 1.0d;
            double tries = 0.0d;
            for (int attempt = 0; attempt < AgentJumpNoise.REDRAWS; attempt++) {
                tries += kept;
                kept *= refused;
            }
            sum += hit * tries + kept * exact;
        }
        return sum / PICKS;
    }

    /** The chance of each whole-pixel draw, -reach..reach (reach: MAX_OFFSET_PX, or less where the rest is nil). */
    private static double[] offsets(double sigma) {
        if (sigma <= 0.0d) {
            return new double[]{1.0d};
        }
        int reach = (int) Math.min(AgentJumpNoise.MAX_OFFSET_PX, Math.ceil(8.0d * sigma) + 1);
        double[] out = new double[2 * reach + 1];
        for (int k = -reach; k <= reach; k++) {
            double below = k == -reach ? 0.0d : phi((k - 0.5d) / sigma);
            double above = k == reach ? 1.0d : phi((k + 0.5d) / sigma);
            out[k + reach] = above - below;
        }
        return out;
    }

    /** The chance every jump of a stage lands. */
    public static double stageSuccess(Jump[] jumps, double sigma) {
        double[] offsets = offsets(sigma);
        double chance = 1.0d;
        for (Jump jump : jumps) {
            chance *= jumpSuccess(jump, sigma, offsets);
        }
        return chance;
    }

    /**
     * The press spread that clears a stage of these jumps with chance {@code target}: within SIGMA_MIN..SIGMA_MAX, the
     * nearer end when no spread reaches it (a stage of wide or stacked jumps can hardly be failed).
     */
    public static double sigmaFor(Jump[] jumps, double target) {
        if (jumps.length == 0 || stageSuccess(jumps, SIGMA_MAX) >= target) {
            return SIGMA_MAX;
        }
        if (stageSuccess(jumps, SIGMA_MIN) <= target) {
            return SIGMA_MIN;
        }
        double low = SIGMA_MIN;
        double high = SIGMA_MAX;
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) / 2.0d;
            if (stageSuccess(jumps, mid) > target) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2.0d;
    }

    /** The standard normal distribution function (Abramowitz and Stegun 7.1.26, error under 1.5e-7). */
    static double phi(double z) {
        double x = Math.abs(z) / Math.sqrt(2.0d);
        double t = 1.0d / (1.0d + 0.3275911d * x);
        double erf = 1.0d - (((((1.061405429d * t - 1.453152027d) * t) + 1.421413741d) * t - 0.284496736d) * t
                + 0.254829592d) * t * Math.exp(-x * x);
        return z >= 0 ? 0.5d * (1.0d + erf) : 0.5d * (1.0d - erf);
    }
}

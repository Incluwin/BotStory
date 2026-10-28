package server.living.population;

/**
 * The jump-quest noise's calibration (docs/BOT_CONCEPT_PLAN.md F5b). Navigation picks a jump's launch inside the edge's
 * window, a margin from its ends; the noise (AgentJumpNoise) moves it by a draw from N(0, sigma); the jump lands when the
 * moved launch is inside the jump's real landing window, measured with the jump probe (JumpQuestCourses: wider than the
 * graph's window, which is trimmed to be safe). A jump's chance is the average over the picks, a stage's the product
 * over its jumps, and {@link #sigmaFor} finds the sigma that gives a stage the citizen's chance: a narrow jump fails more
 * often than a wide one, as for people, and each citizen clears a stage about as often as its skill says.
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
     * One jump: navigation picks its launch in {@code pickLow..pickHigh} (the graph window), and it lands from any
     * launch in {@code okLow..okHigh} (the probe's; {@code -OPEN}/{@code OPEN} where it reaches the platform's end).
     */
    public record Jump(int pickLow, int pickHigh, int okLow, int okHigh) {
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
        if (jump.okHigh() < jump.okLow()) {
            return 0.0d;
        }
        int width = Math.max(0, jump.pickHigh() - jump.pickLow());
        int margin = Math.min(width / 2, PICK_MARGIN_PX);
        double low = jump.pickLow() + margin;
        double high = jump.pickHigh() - margin;
        double sum = 0.0d;
        for (int i = 0; i < PICKS; i++) {
            double pick = high <= low ? (low + high) / 2.0d : low + (high - low) * i / (PICKS - 1);
            if (sigma <= 0.0d) {
                sum += pick >= jump.okLow() && pick <= jump.okHigh() ? 1.0d : 0.0d;
            } else {
                sum += phi((jump.okHigh() + 0.5d - pick) / sigma) - phi((jump.okLow() - 0.5d - pick) / sigma);
            }
        }
        return sum / PICKS;
    }

    /** The chance every jump of a stage lands. */
    public static double stageSuccess(Jump[] jumps, double sigma) {
        double chance = 1.0d;
        for (Jump jump : jumps) {
            chance *= jumpSuccess(jump, sigma);
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

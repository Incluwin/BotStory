package server.living.audit;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The tell audit's arithmetic: running distributions, per-bot spread, the share of the commonest value and stacked
 * idle bots. Adding a sample allocates nothing; reading one builds a small view. Pure, so its tests need no server.
 */
final class TellStats {
    /** Recent samples kept for quantiles; count, mean, spread and extremes cover every sample. */
    static final int RECENT = 512;

    private TellStats() {
    }

    /** A distribution of one measure across all bots. Thread-safe. */
    static final class Dist {
        private final long[] recent = new long[RECENT];
        private long n;
        private double sum;
        private double sumSq;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;

        synchronized void add(long value) {
            recent[(int) (n % RECENT)] = value;
            n++;
            sum += value;
            sumSq += (double) value * value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        synchronized View view() {
            if (n == 0) {
                return View.EMPTY;
            }
            long[] sorted = Arrays.copyOf(recent, (int) Math.min(n, RECENT));
            Arrays.sort(sorted);
            return new View(n, sum / n, cv(n, sum, sumSq), min, max,
                    quantile(sorted, 0.05), quantile(sorted, 0.5), quantile(sorted, 0.9));
        }
    }

    /** {@code p5}/{@code p50}/{@code p90} are over the last {@link #RECENT} samples; the rest over all of them. */
    record View(long n, double mean, double cv, long min, long max, long p5, long p50, long p90) {
        static final View EMPTY = new View(0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * One measure kept per bot (its reactions, its gaps between chat lines): a watcher sees one bot at a time, so the
     * spread that matters is each bot's own, not the pool's, which differences between bots would widen. Thread-safe.
     */
    static final class PerBot {
        private final Map<Integer, double[]> byBot = new HashMap<>();

        /** Adds {@code value} to {@code botId}'s count, sum and sum of squares. */
        synchronized void add(int botId, double value) {
            double[] acc = byBot.computeIfAbsent(botId, id -> new double[3]);
            acc[0]++;
            acc[1] += value;
            acc[2] += value * value;
        }

        /** The median of the bots' coefficients of variation, over bots with at least {@code minSamples}; NaN if none. */
        synchronized Spread medianCv(int minSamples) {
            double[] cvs = new double[byBot.size()];
            int bots = 0;
            for (double[] acc : byBot.values()) {
                if (acc[0] >= minSamples) {
                    cvs[bots++] = cv((long) acc[0], acc[1], acc[2]);
                }
            }
            return new Spread(bots, median(cvs, bots));
        }
    }

    /** {@code bots} bots had enough samples; {@code medianCv} is NaN when none did. */
    record Spread(int bots, double medianCv) {
    }

    /** Counts of whole percents 0-100 (a potion point, a buff recast point). Thread-safe. */
    static final class Percents {
        private final long[] counts = new long[101];
        private long n;

        synchronized void add(int percent) {
            counts[Math.max(0, Math.min(100, percent))]++;
            n++;
        }

        /** The commonest percent and the share of samples at it (0 when empty). */
        synchronized Mode mode() {
            int at = 0;
            for (int percent = 1; percent <= 100; percent++) {
                if (counts[percent] > counts[at]) {
                    at = percent;
                }
            }
            return new Mode(n, at, n == 0 ? 0 : (double) counts[at] / n);
        }
    }

    record Mode(long n, int value, double share) {
    }

    /** Coefficient of variation (standard deviation over mean); 0 for fewer than two samples or a zero mean. */
    static double cv(long n, double sum, double sumSq) {
        if (n < 2) {
            return 0;
        }
        double mean = sum / n;
        if (mean == 0) {
            return 0;
        }
        double variance = Math.max(0, sumSq / n - mean * mean);
        return Math.sqrt(variance) / Math.abs(mean);
    }

    /** The value at quantile {@code q} (0-1) of ascending {@code sorted} (nearest rank); 0 when empty. */
    static long quantile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
    }

    /** The median of the first {@code count} values (reordered); NaN when there are none. */
    static double median(double[] values, int count) {
        if (count == 0) {
            return Double.NaN;
        }
        Arrays.sort(values, 0, count);
        return count % 2 == 1 ? values[count / 2] : (values[count / 2 - 1] + values[count / 2]) / 2;
    }

    /**
     * How many of the first {@code count} points stand in a stack: with at least {@code group - 1} other points
     * within {@code radiusPx} of them. Three bots idling inside 30 px of each other is a stack; two is not.
     */
    static int stacked(int[] xs, int[] ys, int count, int radiusPx, int group) {
        long radiusSq = (long) radiusPx * radiusPx;
        int stacked = 0;
        for (int i = 0; i < count; i++) {
            int near = 0;
            for (int j = 0; j < count && near < group - 1; j++) {
                long dx = xs[i] - xs[j];
                long dy = ys[i] - ys[j];
                if (j != i && dx * dx + dy * dy <= radiusSq) {
                    near++;
                }
            }
            if (near >= group - 1) {
                stacked++;
            }
        }
        return stacked;
    }
}

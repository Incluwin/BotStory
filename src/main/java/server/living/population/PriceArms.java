package server.living.population;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

/**
 * One learned price lever (docs/WORLD_SYSTEMS_PLAN.md I2): a small bandit whose arms are price multipliers, kept per
 * key (an item class, a board kind). Each arm keeps how many rewards it was given and their sum. Its estimate is the
 * observed mean shrunk toward a prior, {@code (sum + k x prior(m)) / (n + k)} with k = {@code priorSamples}, so the
 * fixed rule the lever replaces is where it starts; it is picked by the shared {@link Ucb} arithmetic: the estimate
 * plus {@code explore x sqrt(ln(1 + N) / (1 + n))}, drawn with weight {@code exp((ucb - best) / temperature)} so the
 * sellers pricing at the same moment do not all post one price. Thread-safe; in memory, with {@link #rows} and
 * {@link #load} for the store.
 */
public final class PriceArms {
    /** The item classes asks are learned for, from the item id's type block. */
    public enum ItemClass {
        POTION, ORE, SCROLL, EQUIP, OTHER;

        /**
         * EQUIP: 1xxxxxx. POTION: the recovery blocks 200-202. SCROLL: 204. ORE: the ores, plates, jewels and crystals
         * blocks of 4004-4005 and 401-402 (what the Free Market calls ore). OTHER: everything else (stars, ETC loot).
         */
        public static ItemClass of(int itemId) {
            int block = itemId / 10_000;
            if (itemId / 1_000_000 == 1) {
                return EQUIP;
            }
            if (block >= 200 && block <= 202) {
                return POTION;
            }
            if (block == 204) {
                return SCROLL;
            }
            int thousands = itemId / 1_000;
            if (block == 401 || block == 402 || thousands == 4004 || thousands == 4005) {
                return ORE;
            }
            return OTHER;
        }
    }

    /** How a pick is made: the UCB weight, the draw's temperature, and how much the prior weighs, in rewards. */
    public record Tuning(double explore, double temperature, double priorSamples) { }

    /** One arm of one key as stored: its multiplier, the rewards it was given and their mean (0 when none). */
    public record Row(String key, int arm, double multiplier, double n, double mean) { }

    private final String lever;
    private final double[] multipliers;
    private final DoubleUnaryOperator prior;
    /** key -> per arm {n, sum}. */
    private final Map<String, double[][]> stats = new TreeMap<>();

    /**
     * {@code lever}: the name rows are stored under ("ASK", "OFFER", "BENCH"); {@code multipliers}: the arms, in order;
     * {@code prior}: the reward the fixed rule is believed to earn at a multiplier (0 where the rule never priced).
     */
    public PriceArms(String lever, double[] multipliers, DoubleUnaryOperator prior) {
        this.lever = lever;
        this.multipliers = multipliers.clone();
        this.prior = prior;
    }

    /** {@code from}, {@code from + step}, ... up to {@code to}, each rounded to the cent (0.85, 0.90 ... 1.30). */
    public static double[] steps(double from, double to, double step) {
        int count = (int) Math.round((to - from) / step) + 1;
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = Math.round((from + i * step) * 100.0d) / 100.0d;
        }
        return out;
    }

    public String lever() {
        return lever;
    }

    public int arms() {
        return multipliers.length;
    }

    public double multiplier(int arm) {
        return multipliers[arm];
    }

    /** The stored key of {@code key} under this lever, at most 16 characters (companion_yield.job_branch). */
    public String storedKey(String key) {
        String stored = lever + "_" + key;
        return stored.length() <= 16 ? stored : stored.substring(0, 16);
    }

    private double[][] of(String key) {
        return stats.computeIfAbsent(key, k -> new double[multipliers.length][2]);
    }

    /** The arm's estimate: its observed mean shrunk toward the prior. */
    public synchronized double estimate(String key, int arm, double priorSamples) {
        double[] arm0 = of(key)[arm];
        double k = Math.max(0.0d, priorSamples);
        double denominator = arm0[0] + k;
        return denominator <= 0.0d ? 0.0d : (arm0[1] + k * prior.applyAsDouble(multipliers[arm])) / denominator;
    }

    /** Each arm's UCB score for {@code key}: estimate plus exploration bonus. */
    public synchronized double[] scores(String key, Tuning tuning) {
        double[][] arms = of(key);
        double total = 0.0d;
        for (double[] arm : arms) {
            total += arm[0];
        }
        double[] out = new double[arms.length];
        for (int i = 0; i < arms.length; i++) {
            out[i] = estimate(key, i, tuning.priorSamples()) + Ucb.bonus(tuning.explore(), total, arms[i][0]);
        }
        return out;
    }

    /** The arm to price at for {@code key} (one {@code random.nextDouble()} when the temperature is above 0). */
    public synchronized int pick(String key, Tuning tuning, Random random) {
        return Ucb.draw(scores(key, tuning), tuning.temperature(), random);
    }

    /** The arm with the best estimate (no exploration): what the lever has learned is best for {@code key}. */
    public synchronized int best(String key, double priorSamples) {
        double[] estimates = new double[multipliers.length];
        for (int i = 0; i < estimates.length; i++) {
            estimates[i] = estimate(key, i, priorSamples);
        }
        return Ucb.best(estimates);
    }

    /** {@code count} rewards of {@code reward} each for an arm of {@code key}; the arm's stored row after it. */
    public synchronized Row reward(String key, int arm, double reward, double count) {
        double[] stat = of(key)[arm];
        if (count > 0.0d && Double.isFinite(reward)) {
            stat[0] += count;
            stat[1] += reward * count;
        }
        return row(key, arm, stat);
    }

    private Row row(String key, int arm, double[] stat) {
        return new Row(key, arm, multipliers[arm], stat[0], stat[0] <= 0.0d ? 0.0d : stat[1] / stat[0]);
    }

    /** A stored row back in memory; one for an arm this lever has not (an old step) is skipped. */
    public synchronized void load(String key, int arm, double n, double mean) {
        if (arm < 0 || arm >= multipliers.length || n < 0.0d || !Double.isFinite(mean)) {
            return;
        }
        double[] stat = of(key)[arm];
        stat[0] = n;
        stat[1] = n * mean;
    }

    /** Every arm with at least one reward, by key then arm. */
    public synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, double[][]> entry : stats.entrySet()) {
            for (int i = 0; i < entry.getValue().length; i++) {
                if (entry.getValue()[i][0] > 0.0d) {
                    out.add(row(entry.getKey(), i, entry.getValue()[i]));
                }
            }
        }
        return out;
    }

    /** {"KEY":{"best":1.05,"arms":[{"m":0.85,"n":3,"mean":0.85},...]},...}: every key with rewards, for the admin route. */
    public synchronized String json(double priorSamples) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, double[][]> entry : stats.entrySet()) {
            double[][] arms = entry.getValue();
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(entry.getKey()).append("\":{\"best\":")
                    .append(fmt(multipliers[best(entry.getKey(), priorSamples)])).append(",\"arms\":[");
            for (int i = 0; i < arms.length; i++) {
                out.append(i == 0 ? "" : ",").append("{\"m\":").append(fmt(multipliers[i])).append(",\"n\":")
                        .append(fmt(arms[i][0])).append(",\"mean\":")
                        .append(fmt(arms[i][0] <= 0.0d ? 0.0d : arms[i][1] / arms[i][0])).append('}');
            }
            out.append("]}");
        }
        return out.append('}').toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}

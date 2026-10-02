package server.agents.capabilities.partyquest.pace;

import java.util.Arrays;
import java.util.List;

/**
 * A probability mass function over consecutive integers: {@code p[i]} is the chance of {@code offset + i}. The clear-time
 * estimate draws the PQ's random content from it exactly (KPQ: the sum of Cloto's coupon answers, the position of the
 * right puzzle combination), since an exact table of a few hundred points costs microseconds.
 */
public record Pmf(int offset, double[] p) {
    public Pmf {
        if (p == null || p.length == 0) throw new IllegalArgumentException("a pmf needs at least one value");
        p = p.clone();
        double total = 0.0d;
        for (double mass : p) {
            if (!Double.isFinite(mass) || mass < 0.0d) throw new IllegalArgumentException("pmf mass must be finite and >= 0");
            total += mass;
        }
        if (Math.abs(total - 1.0d) > 1e-9) throw new IllegalArgumentException("pmf mass must sum to 1, was " + total);
    }

    @Override
    public double[] p() {
        return p.clone();
    }

    public int min() {
        return offset;
    }

    public int max() {
        return offset + p.length - 1;
    }

    public double probability(int value) {
        int index = value - offset;
        return index < 0 || index >= p.length ? 0.0d : p[index];
    }

    public double mean() {
        double mean = 0.0d;
        for (int index = 0; index < p.length; index++) mean += (offset + index) * p[index];
        return mean;
    }

    public double variance() {
        double mean = mean();
        double variance = 0.0d;
        for (int index = 0; index < p.length; index++) {
            double delta = offset + index - mean;
            variance += delta * delta * p[index];
        }
        return variance;
    }

    /** The smallest value whose cumulative chance reaches {@code q} (the lower quantile; 0.5 gives the median). */
    public int quantile(double q) {
        if (!(q >= 0.0d && q <= 1.0d)) throw new IllegalArgumentException("quantile must be in [0, 1]");
        double cumulative = 0.0d;
        for (int index = 0; index < p.length; index++) {
            cumulative += p[index];
            if (cumulative >= q - 1e-12) return offset + index;
        }
        return max();
    }

    /** The distribution of the sum of an independent draw from this and one from {@code other}. */
    public Pmf plus(Pmf other) {
        double[] sum = new double[p.length + other.p.length - 1];
        for (int left = 0; left < p.length; left++) {
            if (p[left] == 0.0d) continue;
            for (int right = 0; right < other.p.length; right++) sum[left + right] += p[left] * other.p[right];
        }
        return new Pmf(offset + other.offset, normalized(sum));
    }

    public static Pmf point(int value) {
        return new Pmf(value, new double[]{1.0d});
    }

    /** Every integer from {@code lo} to {@code hi} equally likely. */
    public static Pmf uniform(int lo, int hi) {
        if (hi < lo) throw new IllegalArgumentException("uniform needs lo <= hi");
        double[] mass = new double[hi - lo + 1];
        Arrays.fill(mass, 1.0d / mass.length);
        return new Pmf(lo, mass);
    }

    /**
     * The sum of {@code draws} independent draws, each one of {@code values} picked uniformly (a value listed twice is
     * twice as likely, as Cloto's answer list holds 25 twice).
     */
    public static Pmf sumOfDraws(List<Integer> values, int draws) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("values are required");
        if (draws < 0) throw new IllegalArgumentException("draws must be >= 0");
        int lo = values.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int hi = values.stream().mapToInt(Integer::intValue).max().orElseThrow();
        double[] single = new double[hi - lo + 1];
        for (int value : values) single[value - lo] += 1.0d / values.size();
        Pmf one = new Pmf(lo, normalized(single));
        Pmf sum = point(0);
        for (int draw = 0; draw < draws; draw++) sum = sum.plus(one);
        return sum;
    }

    private static double[] normalized(double[] mass) {
        double total = 0.0d;
        for (double value : mass) total += value;
        for (int index = 0; index < mass.length; index++) mass[index] /= total;
        return mass;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pmf pmf && pmf.offset == offset && Arrays.equals(pmf.p, p);
    }

    @Override
    public int hashCode() {
        return 31 * offset + Arrays.hashCode(p);
    }

    @Override
    public String toString() {
        return "Pmf[offset=" + offset + ", p=" + Arrays.toString(p) + "]";
    }
}

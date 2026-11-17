package server.pvp;

import java.util.List;

/**
 * Glicko-2 ratings for the coliseum ladder (PVP_PLAN V4.2), after Glickman's "Example of the Glicko-2 system".
 *
 * <p>Pure: no clock, no storage. A rating carries its deviation (how unsure it is) and volatility (how erratic the
 * player is). The ladder treats every match as one rating period with one game, and widens an idle rating with
 * {@link #idle} for the days that passed since its last match.
 */
public final class Glicko2 {
    /** Starting rating, deviation and volatility (Glickman's suggestions). */
    public static final double START_RATING = 1500d;
    public static final double START_DEVIATION = 350d;
    public static final double START_VOLATILITY = 0.06d;
    /** The system constant tau: how much volatility may change per period. 0.5 sits in the suggested 0.3-1.2. */
    public static final double TAU = 0.5d;
    /** A deviation never drops below this, so a long-standing rating can still move. */
    public static final double MIN_DEVIATION = 30d;

    private static final double SCALE = 173.7178d;
    private static final double EPSILON = 0.000001d;

    private Glicko2() {
    }

    /** A rating on the familiar scale: 1500 start, deviation 350. */
    public record Rating(double rating, double deviation, double volatility) {
        public static Rating initial() {
            return new Rating(START_RATING, START_DEVIATION, START_VOLATILITY);
        }
    }

    /** One game in a period: the opponent's rating before it, and the score (1 win, 0.5 draw, 0 loss). */
    public record Result(Rating opponent, double score) {
    }

    /** The chance that {@code a} beats {@code b}, from both ratings and both deviations. */
    public static double winChance(Rating a, Rating b) {
        double mu = (a.rating() - START_RATING) / SCALE;
        double muOpp = (b.rating() - START_RATING) / SCALE;
        double phi = Math.hypot(a.deviation() / SCALE, b.deviation() / SCALE);
        return expected(mu, muOpp, phi);
    }

    /** One match as its own rating period. */
    public static Rating update(Rating self, Rating opponent, double score) {
        return update(self, List.of(new Result(opponent, score)));
    }

    /** A rating period with these games. With none, only the deviation widens. */
    public static Rating update(Rating self, List<Result> results) {
        double mu = (self.rating() - START_RATING) / SCALE;
        double phi = self.deviation() / SCALE;
        double sigma = self.volatility();
        if (results.isEmpty()) {
            return idle(self, 1d);
        }

        double inverseV = 0d;
        double sum = 0d;
        for (Result result : results) {
            double muOpp = (result.opponent().rating() - START_RATING) / SCALE;
            double phiOpp = result.opponent().deviation() / SCALE;
            double g = g(phiOpp);
            double e = expected(mu, muOpp, phiOpp);
            inverseV += g * g * e * (1d - e);
            sum += g * (result.score() - e);
        }
        double v = 1d / inverseV;
        double delta = v * sum;

        double sigmaNext = volatility(phi, sigma, v, delta);
        double phiStar = Math.sqrt(phi * phi + sigmaNext * sigmaNext);
        double phiNext = 1d / Math.sqrt(1d / (phiStar * phiStar) + 1d / v);
        double muNext = mu + phiNext * phiNext * sum;

        double deviation = Math.max(MIN_DEVIATION, Math.min(START_DEVIATION, phiNext * SCALE));
        return new Rating(START_RATING + muNext * SCALE, deviation, sigmaNext);
    }

    /** Widens the deviation for {@code periods} idle periods (days on the ladder), capped at the starting deviation. */
    public static Rating idle(Rating self, double periods) {
        if (periods <= 0d) {
            return self;
        }
        double phi = self.deviation() / SCALE;
        double sigma = self.volatility();
        double phiNext = Math.sqrt(phi * phi + periods * sigma * sigma);
        double deviation = Math.max(MIN_DEVIATION, Math.min(START_DEVIATION, phiNext * SCALE));
        return new Rating(self.rating(), deviation, sigma);
    }

    private static double g(double phi) {
        return 1d / Math.sqrt(1d + 3d * phi * phi / (Math.PI * Math.PI));
    }

    private static double expected(double mu, double muOpp, double phiOpp) {
        return 1d / (1d + Math.exp(-g(phiOpp) * (mu - muOpp)));
    }

    /** Step 5: the new volatility, by the Illinois algorithm. */
    private static double volatility(double phi, double sigma, double v, double delta) {
        double a = Math.log(sigma * sigma);
        double deltaSq = delta * delta;
        double phiSq = phi * phi;
        java.util.function.DoubleUnaryOperator f = x -> {
            double ex = Math.exp(x);
            double denominator = phiSq + v + ex;
            return ex * (deltaSq - phiSq - v - ex) / (2d * denominator * denominator) - (x - a) / (TAU * TAU);
        };

        double upper = a;
        double lower;
        if (deltaSq > phiSq + v) {
            lower = Math.log(deltaSq - phiSq - v);
        } else {
            int k = 1;
            while (f.applyAsDouble(a - k * TAU) < 0d) {
                k++;
            }
            lower = a - k * TAU;
        }
        double fUpper = f.applyAsDouble(upper);
        double fLower = f.applyAsDouble(lower);
        for (int i = 0; i < 100 && Math.abs(lower - upper) > EPSILON; i++) {
            double c = upper + (upper - lower) * fUpper / (fLower - fUpper);
            double fc = f.applyAsDouble(c);
            if (fc * fLower <= 0d) {
                upper = lower;
                fUpper = fLower;
            } else {
                fUpper = fUpper / 2d;
            }
            lower = c;
            fLower = fc;
        }
        return Math.exp(upper / 2d);
    }
}

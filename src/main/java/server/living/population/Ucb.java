package server.living.population;

import java.util.Random;

/**
 * The upper-confidence-bound arithmetic the living world's small bandits share (docs/WORLD_SYSTEMS_PLAN.md I2.1): the
 * exploration bonus {@code explore x sqrt(ln(1 + N) / (1 + n))} of an arm measured {@code n} times out of {@code N},
 * and the weighted draw over arm scores, each arm with weight {@code exp((score - best) / temperature)}, so callers
 * planning at the same moment do not all pick the one best arm. Users: CitizenSpots (where to train), InviteBandit (when
 * to ask a human along to a run) and PriceArms (stall asks, board offers, bench stall asks). Stateless.
 */
public final class Ucb {
    private Ucb() {
    }

    /** The exploration bonus of an arm measured {@code n} times when all arms together were measured {@code total}. */
    public static double bonus(double explore, double total, double n) {
        return explore * Math.sqrt(Math.log(1.0d + total) / (1.0d + n));
    }

    /** The index of the highest score, the first on a tie; -1 when there is none. */
    public static int best(double[] scores) {
        int best = -1;
        for (int i = 0; i < scores.length; i++) {
            if (best < 0 || scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    /** exp((score - best) / temperature) for each score, in order: the best weighs 1, the rest less. */
    public static double[] weights(double[] scores, double temperature) {
        double best = Double.NEGATIVE_INFINITY;
        for (double score : scores) {
            best = Math.max(best, score);
        }
        double[] out = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            out[i] = Math.exp((scores[i] - best) / temperature);
        }
        return out;
    }

    /**
     * A weighted draw over {@code scores} (see the class note): the index drawn, the best when temperature is 0 or
     * less (no random number used then), -1 when there are no scores. One {@code random.nextDouble()} otherwise.
     */
    public static int draw(double[] scores, double temperature, Random random) {
        int best = best(scores);
        if (best < 0 || temperature <= 0.0d) {
            return best;
        }
        double[] weights = weights(scores, temperature);
        double total = 0.0d;
        for (double weight : weights) {
            total += weight;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < scores.length; i++) {
            roll -= weights[i];
            if (roll < 0.0d) {
                return i;
            }
        }
        return best;
    }
}

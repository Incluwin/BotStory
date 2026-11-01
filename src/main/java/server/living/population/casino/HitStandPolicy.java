package server.living.population.casino;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * How a citizen at a blackjack table decides to hit or stand: a value per (total, soft, dealer's up card) and action,
 * learned from the hands it plays. SoloMapling's BlackjackAI rule is only the prior (always hit to 11, hit 12-16 with
 * a chance that falls as the total rises, hit a 17 one time in ten, never above), worth a few hands of experience, so
 * a new citizen plays like a casual player and drifts toward what pays. A softmax over the two values keeps the odd
 * human mistake; {@code temperature} sets how often.
 */
final class HitStandPolicy {
    private static final int TOTALS = 22;
    private static final int UP_CARDS = 12;
    /** Hands of experience the prior counts as. */
    private static final double PRIOR_WEIGHT = 3;

    private final double[][] value = new double[2 * TOTALS * UP_CARDS][2];
    private final double[][] count = new double[2 * TOTALS * UP_CARDS][2];
    private final double temperature;
    private final Random random;
    private final List<int[]> visited = new ArrayList<>();

    HitStandPolicy(double temperature, Random random) {
        this.temperature = Math.max(0.01, temperature);
        this.random = random;
        for (int total = 0; total < TOTALS; total++) {
            for (int up = 0; up < UP_CARDS; up++) {
                for (int soft = 0; soft < 2; soft++) {
                    int state = state(total, soft == 1, up);
                    // values whose softmax gives the prior's hit chance at the start
                    double p = Math.max(0.02, Math.min(0.98, priorHitChance(total)));
                    double lean = this.temperature * Math.log(p / (1 - p)) / 2;
                    value[state][1] = lean;
                    value[state][0] = -lean;
                    count[state][0] = PRIOR_WEIGHT;
                    count[state][1] = PRIOR_WEIGHT;
                }
            }
        }
    }

    /** SoloMapling's casual rule, as the chance of hitting on a total. */
    static double priorHitChance(int total) {
        if (total <= 11) {
            return 1;
        }
        if (total <= 16) {
            return (17.0 - total) / 10.0;
        }
        return total == 17 ? 0.1 : 0;
    }

    /** Hit or stand on this hand; the choice is remembered until {@link #settle}. */
    boolean hit(int total, boolean soft, int dealerUp) {
        if (total >= 21) {
            return false;
        }
        int state = state(total, soft, dealerUp);
        double stand = value[state][0] / temperature;
        double hit = value[state][1] / temperature;
        double pHit = 1 / (1 + Math.exp(stand - hit));
        boolean choice = random.nextDouble() < pHit;
        visited.add(new int[]{state, choice ? 1 : 0});
        return choice;
    }

    /** The hand is over: {@code reward} per chip bet (-1 lost, 0 push, 1 won, 1.5 blackjack). */
    void settle(double reward) {
        for (int[] step : visited) {
            double n = ++count[step[0]][step[1]];
            value[step[0]][step[1]] += (reward - value[step[0]][step[1]]) / n;
        }
        visited.clear();
    }

    /** The learned value of an action, for tests and the GM readout. */
    double value(int total, boolean soft, int dealerUp, boolean hit) {
        return value[state(total, soft, dealerUp)][hit ? 1 : 0];
    }

    private static int state(int total, boolean soft, int dealerUp) {
        int t = Math.max(0, Math.min(TOTALS - 1, total));
        int u = Math.max(0, Math.min(UP_CARDS - 1, dealerUp));
        return ((soft ? 1 : 0) * TOTALS + t) * UP_CARDS + u;
    }
}

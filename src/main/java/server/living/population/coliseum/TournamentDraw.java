package server.living.population.coliseum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A knockout draw (docs/PVP_PLAN.md V5): {@code seats} positions (2, 4 or 8), filled by seed
 * ({@link TournamentRules#order}); a seed with no one in it is a bye, and its opponent goes through. Entrants are
 * indices 0..n-1 in seed order (0 = seed 1). Pure: the controller says who won each pair, the draw says who meets next
 * and how far each one got.
 */
final class TournamentDraw {
    /** A pair this round: its index, and the two entrants (-1 for an empty seat). */
    record Pair(int index, int a, int b) {
        boolean bye() {
            return a < 0 || b < 0;
        }
    }

    private static final int UNDECIDED = -2;

    private final int seats;
    private final int rounds;
    private final int[] reached;
    private int round;
    private int[] current;
    private int[] winners;
    private int champion = -1;

    TournamentDraw(int seats, int entrants) {
        if (entrants < 0 || entrants > seats) {
            throw new IllegalArgumentException(entrants + " entrants for " + seats + " seats");
        }
        this.seats = seats;
        this.rounds = TournamentRules.rounds(seats);
        this.reached = new int[entrants];
        Arrays.fill(reached, TournamentRules.NONE);
        int[] order = TournamentRules.order(seats);
        this.current = new int[seats];
        for (int i = 0; i < seats; i++) {
            current[i] = order[i] <= entrants ? order[i] - 1 : -1;
        }
        startRound();
    }

    private void startRound() {
        winners = new int[current.length / 2];
        Arrays.fill(winners, UNDECIDED);
        for (int i = 0; i < winners.length; i++) {
            int a = current[2 * i];
            int b = current[2 * i + 1];
            if (a < 0 || b < 0) {
                winners[i] = a < 0 ? b : a; // a bye: the one there goes through (-1 if neither is)
            }
        }
    }

    int round() {
        return round;
    }

    int rounds() {
        return rounds;
    }

    int seats() {
        return seats;
    }

    /** This round's pairs, byes included. */
    List<Pair> pairs() {
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < winners.length; i++) {
            out.add(new Pair(i, current[2 * i], current[2 * i + 1]));
        }
        return out;
    }

    /** This round's pairs still to fight. */
    List<Pair> open() {
        List<Pair> out = new ArrayList<>();
        for (Pair pair : pairs()) {
            if (winners[pair.index()] == UNDECIDED) {
                out.add(pair);
            }
        }
        return out;
    }

    /** Pair {@code index} is decided: side 0 ({@code a}) or 1 ({@code b}) goes through; the other is out. */
    void decide(int index, int side) {
        if (winners[index] != UNDECIDED) {
            return;
        }
        int a = current[2 * index];
        int b = current[2 * index + 1];
        int winner = side == 0 ? a : b;
        int loser = side == 0 ? b : a;
        winners[index] = winner;
        if (loser >= 0) {
            reached[loser] = TournamentRules.reachedWhenOut(round, rounds);
        }
    }

    boolean roundDone() {
        for (int winner : winners) {
            if (winner == UNDECIDED) {
                return false;
            }
        }
        return true;
    }

    /** Moves to the next round once this one is done; the last round's winner is the champion. False when over. */
    boolean next() {
        if (!roundDone() || over()) {
            return false;
        }
        if (round == rounds - 1) {
            champion = winners[0];
            if (champion >= 0) {
                reached[champion] = TournamentRules.CHAMPION;
            }
            round++;
            return false;
        }
        current = winners.clone();
        round++;
        startRound();
        return true;
    }

    boolean over() {
        return round >= rounds;
    }

    /** The champion's entrant index, or -1 before the final is decided (or with nobody seated). */
    int champion() {
        return champion;
    }

    /** How far entrant {@code i} got ({@link TournamentRules#TROPHIES}'s index), or NONE while still in. */
    int reached(int i) {
        return reached[i];
    }

    /** The final's loser, or -1. */
    int runnerUp() {
        for (int i = 0; i < reached.length; i++) {
            if (reached[i] == 2) {
                return i;
            }
        }
        return -1;
    }
}

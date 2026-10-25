package server.agents.capabilities.partyquest.apq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deduces an Amorian Challenge combination from Amos's answers, as a party works it out (APQ plan A4 S2/S3): every
 * combination the answers so far allow stays a candidate, and the next try is the arrangement whose worst answer leaves
 * the fewest of them (minimax), a candidate before a non-candidate, the likelier draw first, then the one that moves the
 * fewest bodies from where they stand. Not a list walked in order: KPQ's AgentKpqCombinationOrder has no hints to use.
 *
 * <p>Stage 2 (Heart Strings): five bodies on three ropes, each combination a count per rope; Amos says how many ropes
 * weigh the same (0, or 1: two right force the third). Stage 3 (the Twisted Switcher): five of nine platforms, one body
 * each; the slimes that appear say how many stand right. Arrangements are counts per area, in the map's area order, as
 * 9201044.js compares them. Not thread-safe: its session's puzzle state locks it.</p>
 */
final class AgentApqCombinationSolver {
    /** How a try is answered: the answer a party is told for {@code tried} against the hidden {@code answer}. */
    @FunctionalInterface
    interface Judge {
        int answer(int[] tried, int[] answer);
    }

    private final List<int[]> universe;
    private final double[] weights;
    private final Judge judge;
    private final List<Integer> alive = new ArrayList<>();

    private AgentApqCombinationSolver(List<int[]> universe, double[] weights, Judge judge) {
        this.universe = universe;
        this.weights = weights;
        this.judge = judge;
        reset();
    }

    /**
     * Heart Strings: the 21 ways five bodies fall on three ropes, weighted as the generator draws them (each of the five
     * on a rope at random: multinomial), judged as Amos counts ropes that weigh the same.
     */
    static AgentApqCombinationSolver ropes() {
        List<int[]> combinations = new ArrayList<>();
        int bodies = AgentApqDefinition.PUZZLE_BODIES;
        for (int left = 0; left <= bodies; left++) {
            for (int middle = 0; middle + left <= bodies; middle++) {
                combinations.add(new int[] {left, middle, bodies - left - middle});
            }
        }
        double[] weights = new double[combinations.size()];
        for (int i = 0; i < weights.length; i++) {
            int[] counts = combinations.get(i);
            weights[i] = factorial(bodies) / (factorial(counts[0]) * factorial(counts[1]) * factorial(counts[2]));
        }
        return new AgentApqCombinationSolver(combinations, weights, AgentApqCombinationSolver::sameCounts);
    }

    /** The Twisted Switcher: the 126 ways to pick five of nine platforms, all as likely, judged by bodies right. */
    static AgentApqCombinationSolver platforms() {
        List<int[]> combinations = new ArrayList<>();
        int areas = AgentApqDefinition.PLATFORM_AREAS.size();
        for (int mask = 0; mask < 1 << areas; mask++) {
            if (Integer.bitCount(mask) != AgentApqDefinition.PUZZLE_BODIES) {
                continue;
            }
            int[] chosen = new int[areas];
            for (int area = 0; area < areas; area++) {
                chosen[area] = (mask >> area) & 1;
            }
            combinations.add(chosen);
        }
        double[] weights = new double[combinations.size()];
        Arrays.fill(weights, 1.0d);
        return new AgentApqCombinationSolver(combinations, weights, AgentApqCombinationSolver::bodiesRight);
    }

    /** 9201044.js on stage 2: the ropes whose count matches (it says "all weigh differently" only for none). */
    static int sameCounts(int[] tried, int[] answer) {
        int same = 0;
        for (int i = 0; i < tried.length; i++) {
            if (tried[i] == answer[i]) {
                same++;
            }
        }
        return same;
    }

    /** 9201044.js on stage 3: the platforms occupied and right, one slime each. */
    static int bodiesRight(int[] tried, int[] answer) {
        int right = 0;
        for (int i = 0; i < tried.length; i++) {
            if (tried[i] > 0 && tried[i] == answer[i]) {
                right++;
            }
        }
        return right;
    }

    /** Every combination is a candidate again: a new draw (stage 2's seventh miss). */
    void reset() {
        alive.clear();
        for (int i = 0; i < universe.size(); i++) {
            alive.add(i);
        }
    }

    /** How many combinations the answers so far allow. */
    int remaining() {
        return alive.size();
    }

    /** A copy of every combination still allowed. */
    List<int[]> candidates() {
        List<int[]> left = new ArrayList<>();
        for (int i : alive) {
            left.add(universe.get(i).clone());
        }
        return left;
    }

    /**
     * Amos answered {@code answer} to {@code tried}: keeps only the combinations that would have drawn the same answer.
     * An answer no candidate gives (the board moved under the party, a body slipped) empties nothing: the solver starts
     * over rather than chase a contradiction. True when the answers still allow a combination.
     */
    boolean observe(int[] tried, int answer) {
        List<Integer> kept = new ArrayList<>();
        for (int i : alive) {
            if (judge.answer(tried, universe.get(i)) == answer) {
                kept.add(i);
            }
        }
        if (kept.isEmpty()) {
            reset();
            return false;
        }
        alive.clear();
        alive.addAll(kept);
        return true;
    }

    /**
     * The next arrangement to try, given where the bodies stand now ({@code standing}: counts per area, null when
     * unknown): minimax over the answers, a candidate first, the likelier draw first, then the fewest bodies moved.
     */
    int[] next(int[] standing) {
        int best = -1;
        int bestWorst = Integer.MAX_VALUE;
        boolean bestCandidate = false;
        double bestWeight = -1.0d;
        int bestMoves = Integer.MAX_VALUE;
        for (int guess = 0; guess < universe.size(); guess++) {
            int[] tried = universe.get(guess);
            // the answer a right try draws: that try ends the puzzle, so its share counts as nothing left
            int solved = judge.answer(tried, tried);
            Map<Integer, Integer> sizes = new HashMap<>();
            for (int i : alive) {
                sizes.merge(judge.answer(tried, universe.get(i)), 1, Integer::sum);
            }
            int worst = 0;
            for (Map.Entry<Integer, Integer> size : sizes.entrySet()) {
                worst = Math.max(worst, size.getKey() == solved ? 0 : size.getValue());
            }
            boolean candidate = alive.contains(guess);
            double weight = candidate ? weights[guess] : 0.0d;
            int moves = standing == null ? 0 : moves(standing, tried);
            if (better(worst, candidate, weight, moves, bestWorst, bestCandidate, bestWeight, bestMoves)) {
                best = guess;
                bestWorst = worst;
                bestCandidate = candidate;
                bestWeight = weight;
                bestMoves = moves;
            }
        }
        return universe.get(best).clone();
    }

    private static boolean better(int worst, boolean candidate, double weight, int moves,
                                  int bestWorst, boolean bestCandidate, double bestWeight, int bestMoves) {
        if (worst != bestWorst) {
            return worst < bestWorst;
        }
        if (candidate != bestCandidate) {
            return candidate;
        }
        if (weight != bestWeight) {
            return weight > bestWeight;
        }
        return moves < bestMoves;
    }

    /** The bodies that must move to turn {@code from} into {@code to}: the surplus summed over the areas. */
    static int moves(int[] from, int[] to) {
        int moves = 0;
        for (int i = 0; i < to.length; i++) {
            int have = i < from.length ? from[i] : 0;
            moves += Math.max(0, to[i] - have);
        }
        return moves;
    }

    private static double factorial(int n) {
        double value = 1.0d;
        for (int i = 2; i <= n; i++) {
            value *= i;
        }
        return value;
    }
}

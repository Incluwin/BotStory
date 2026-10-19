package server.agents.capabilities.partyquest.opq;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-18: the way up's lever puzzle, as a party plays it. Eak (2013001.js) wants exactly two of the five levers at
 * the top pushed, a pair he drew (stage6_c: ten pairs). After a wrong check he says either that one of the pushed
 * levers is correct or that both are wrong. The party keeps the pairs that agree with every answer so far and pushes
 * next the pair that leaves the fewest candidates whichever answer comes (the sealed room's rule, every pair weighted
 * alike). It never reads the drawn pair; at most four checks find any of them.
 */
final class AgentOpqLeverSearch {
    /** Two pushed levers, by their index (0 for the lever named "1", 4 for "5"), first below second. */
    record Pair(int first, int second) {
        Pair {
            if (first < 0 || second > 4 || first >= second) throw new IllegalArgumentException("invalid lever pair");
        }

        boolean pushes(int lever) {
            return lever == first || lever == second;
        }

        /** The levers this pair and {@code other} both push. */
        int shared(Pair other) {
            return (other.pushes(first) ? 1 : 0) + (other.pushes(second) ? 1 : 0);
        }

        /** The five levers as 2013001.js writes them, "1" for pushed: "11000" for the first two. */
        String key() {
            StringBuilder key = new StringBuilder();
            for (int lever = 0; lever < 5; lever++) key.append(pushes(lever) ? '1' : '0');
            return key.toString();
        }

        /** The pair {@link #key} wrote, or null for anything else (not exactly two levers pushed). */
        static Pair parse(String key) {
            if (key == null || key.length() != 5) return null;
            int first = -1;
            int second = -1;
            for (int lever = 0; lever < 5; lever++) {
                char state = key.charAt(lever);
                if (state == '1') {
                    if (first < 0) first = lever;
                    else if (second < 0) second = lever;
                    else return null;
                } else if (state != '0') {
                    return null;
                }
            }
            return second < 0 ? null : new Pair(first, second);
        }
    }

    /** Every pair of the five levers, in 2013001.js's left-to-right order. */
    static final List<Pair> PAIRS = pairs();

    private AgentOpqLeverSearch() {
    }

    /** The pair to push next among {@code candidates}: the least squared split of the two answers, then the earlier. */
    static Pair next(List<Pair> candidates) {
        Pair best = null;
        long bestScore = Long.MAX_VALUE;
        for (Pair guess : candidates) {
            long oneRight = 0L;
            long bothWrong = 0L;
            for (Pair other : candidates) {
                if (other.equals(guess)) continue;
                if (guess.shared(other) > 0) oneRight++;
                else bothWrong++;
            }
            long score = oneRight * oneRight + bothWrong * bothWrong;
            if (score < bestScore) {
                best = guess;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The candidates left after {@code checked} was wrong: every other one that agrees with Eak's answer
     * ({@code oneRight}; null when it was not heard, which only removes {@code checked}). An answer that agrees with no
     * pair starts the search over without {@code checked}.
     */
    static List<Pair> afterWrongCheck(List<Pair> candidates, Pair checked, Boolean oneRight) {
        List<Pair> left = new ArrayList<>();
        for (Pair candidate : candidates) {
            if (candidate.equals(checked)) continue;
            if (oneRight == null || (checked.shared(candidate) > 0) == oneRight) left.add(candidate);
        }
        if (!left.isEmpty()) return List.copyOf(left);
        return PAIRS.stream().filter(pair -> !pair.equals(checked)).toList();
    }

    private static List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        for (int first = 0; first < 5; first++) {
            for (int second = first + 1; second < 5; second++) pairs.add(new Pair(first, second));
        }
        return List.copyOf(pairs);
    }
}

package server.agents.capabilities.partyquest.opq;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 2026-10-17: the sealed room's search, as a party plays it. Eak (2013001.js) wants three players on the three
 * platforms in a split he drew (stage4_0..2, summing to 3: ten splits). After a wrong check he says either that one of
 * the platforms has the right number of players or that all of them are wrong; he never says two, since the third is
 * then right as well. The party keeps the splits that agree with every answer so far and checks next the one that
 * leaves the least candidate weight whichever answer comes, a split's weight being how often this server's runs have
 * solved it (AgentOpqSealedSplitMemory), so the order is learned. It never reads the drawn split.
 */
final class AgentOpqSealedSearch {
    /** Players on the left, middle and right platform. */
    record Split(int left, int middle, int right) {
        int on(int platform) {
            return switch (platform) {
                case 0 -> left;
                case 1 -> middle;
                case 2 -> right;
                default -> throw new IllegalArgumentException("invalid sealed platform");
            };
        }

        /** The platforms on which this split and {@code other} put as many players. */
        int matches(Split other) {
            int matches = 0;
            for (int platform = 0; platform < 3; platform++) {
                if (on(platform) == other.on(platform)) matches++;
            }
            return matches;
        }

        /** "111" for one player on each platform. */
        String key() {
            return "" + left + middle + right;
        }

        /** The split {@link #key} wrote, or null for anything else. */
        static Split parse(String key) {
            if (key == null || key.length() != 3) return null;
            for (Split split : SPLITS) {
                if (split.key().equals(key)) return split;
            }
            return null;
        }
    }

    /** Every split of three players over three platforms, left-major. */
    static final List<Split> SPLITS = splits();

    private AgentOpqSealedSearch() {
    }

    /**
     * The split to check next among {@code candidates}: the one whose two possible answers leave the least squared
     * candidate weight, the heavier of two equal ones, then the earlier. Null for no candidates.
     */
    static Split next(List<Split> candidates, ToLongFunction<Split> weight) {
        Split best = null;
        long bestScore = Long.MAX_VALUE;
        long bestWeight = Long.MIN_VALUE;
        for (Split guess : candidates) {
            long oneRight = 0L;
            long allWrong = 0L;
            for (Split other : candidates) {
                if (other.equals(guess)) continue;
                if (guess.matches(other) > 0) oneRight += weight.applyAsLong(other);
                else allWrong += weight.applyAsLong(other);
            }
            long score = oneRight * oneRight + allWrong * allWrong;
            long own = weight.applyAsLong(guess);
            if (score < bestScore || score == bestScore && own > bestWeight) {
                best = guess;
                bestScore = score;
                bestWeight = own;
            }
        }
        return best;
    }

    /**
     * The candidates left after {@code checked} was wrong: every other one that agrees with Eak's answer
     * ({@code onePlatformRight}; null when his answer was not heard, which only removes {@code checked}). Answers that
     * agree with no split (a player Eak did not count) start the search over without {@code checked}.
     */
    static List<Split> afterWrongCheck(List<Split> candidates, Split checked, Boolean onePlatformRight) {
        List<Split> left = new ArrayList<>();
        for (Split candidate : candidates) {
            if (candidate.equals(checked)) continue;
            if (onePlatformRight == null || (checked.matches(candidate) > 0) == onePlatformRight) left.add(candidate);
        }
        if (!left.isEmpty()) return List.copyOf(left);
        return SPLITS.stream().filter(split -> !split.equals(checked)).toList();
    }

    private static List<Split> splits() {
        List<Split> splits = new ArrayList<>();
        for (int left = 0; left <= 3; left++) {
            for (int middle = 0; middle <= 3 - left; middle++) splits.add(new Split(left, middle, 3 - left - middle));
        }
        return List.copyOf(splits);
    }
}

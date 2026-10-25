package server.agents.capabilities.expedition.gpq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stage 3, the vassals' offerings as the party works them out (GPQ plan G3): the fountain (9040006) hides four
 * offerings, one per area before it, each of four kinds (0 Medal of Valor, 1 Scroll of Wisdom, 2 Spoiled Food, 3 Jr.
 * Necki Drink), and after each wrong try it says aloud how many vassals are pleased (right kind, right area), how many
 * got an incorrect offering (right kind, wrong area) and how many an unknown one (decision 7: one map line per count).
 * That is Mastermind with 4 places and 4 kinds: 256 combinations, and the fountain gives seven tries per draw. The
 * party keeps every combination still possible and tries the one that splits them best (fewest left in the worst
 * case), so a draw falls in five tries at most. Owned by its run; every method locks this object.
 */
final class AgentGpqVassals {
    static final int KINDS = 4;
    static final int AREAS = 4;
    static final int CODES = 256;

    /** What one judged try told the party. */
    record Verdict(int pleased, int incorrect, int unknown) {
    }

    private final List<Integer> left = new ArrayList<>();
    private int trying = -1;
    /** The fountain's wrong effects seen so far (EventInstanceManager.wrongEffectCount starts at 0). */
    private int judgedSeen;
    private int draws;

    AgentGpqVassals() {
        reset();
    }

    /** Forgets everything, for a fresh run. */
    synchronized void reset() {
        startOver();
        judgedSeen = 0;
    }

    /** The fountain drew a new combination (its seventh miss): start over, counting the draw and the misses seen. */
    synchronized void newDraw() {
        startOver();
        draws++;
    }

    private void startOver() {
        left.clear();
        for (int code = 0; code < CODES; code++) {
            left.add(code);
        }
        trying = -1;
    }

    synchronized int draws() {
        return draws;
    }

    /** The try being set now, kind per area (area 0 first); it picks one when none is set. */
    synchronized int[] trying() {
        if (trying < 0) {
            trying = best();
        }
        return digits(trying);
    }

    /** How many combinations are still possible. */
    synchronized int left() {
        return left.size();
    }

    /**
     * The fountain judged the try that stood ({@code judged} counts the judgements seen so far: its wrong effects): the
     * combinations that would not have answered {@code verdict} to it are ruled out, and a new try is picked. A count
     * already seen changes nothing. True when this judgement was new.
     */
    synchronized boolean judged(int judged, int[] stood, Verdict verdict) {
        if (judged <= judgedSeen) {
            return false;
        }
        judgedSeen = judged;
        int tried = code(stood);
        left.removeIf(code -> !verdict(tried, code).equals(verdict));
        left.remove(Integer.valueOf(tried));
        trying = -1;
        return true;
    }

    /** Marks {@code judged} as seen without learning from it (a judgement of a try the party did not set). */
    synchronized void seen(int judged) {
        judgedSeen = Math.max(judgedSeen, judged);
    }

    synchronized int judgedSeen() {
        return judgedSeen;
    }

    /**
     * The fountain's answer to {@code guess} when it hides {@code combo}, counted exactly as 9040006.js counts it: a
     * place whose kinds match pleases its vassal; of the rest, kinds offered more often than hidden are unknown, and
     * whatever is neither is incorrect.
     */
    static Verdict verdict(int guess, int combo) {
        int[] g = digits(guess);
        int[] c = digits(combo);
        int[] guessKinds = new int[KINDS];
        int[] comboKinds = new int[KINDS];
        int pleased = 0;
        for (int area = 0; area < AREAS; area++) {
            if (g[area] == c[area]) {
                pleased++;
            } else {
                guessKinds[g[area]]++;
                comboKinds[c[area]]++;
            }
        }
        int unknown = 0;
        for (int kind = 0; kind < KINDS; kind++) {
            unknown += Math.max(0, guessKinds[kind] - comboKinds[kind]);
        }
        return new Verdict(pleased, AREAS - pleased - unknown, unknown);
    }

    /** The combination whose worst answer leaves the fewest possible, a possible one first among equals. */
    private int best() {
        if (left.size() == 1) {
            return left.getFirst();
        }
        if (left.size() == CODES) {
            return code(new int[]{0, 0, 1, 1}); // the classic opening: two kinds, two places each
        }
        int bestCode = left.getFirst();
        int bestWorst = Integer.MAX_VALUE;
        boolean bestPossible = true;
        for (int guess = 0; guess < CODES; guess++) {
            int[] counts = new int[25];
            for (int code : left) {
                Verdict answer = verdict(guess, code);
                counts[answer.pleased() * 5 + answer.incorrect()]++;
            }
            int worst = Arrays.stream(counts).max().orElse(0);
            boolean possible = left.contains(guess);
            if (worst < bestWorst || worst == bestWorst && possible && !bestPossible) {
                bestWorst = worst;
                bestCode = guess;
                bestPossible = possible;
            }
        }
        return bestCode;
    }

    /** The kinds of {@code code}, area 0 first. */
    static int[] digits(int code) {
        return new int[]{(code >> 6) & 3, (code >> 4) & 3, (code >> 2) & 3, code & 3};
    }

    static int code(int[] digits) {
        return digits[0] << 6 | digits[1] << 4 | digits[2] << 2 | digits[3];
    }

    /**
     * The event's stage3combo (a number whose thousands digit is area 0, as 9040006.js builds it; "23" is 0023) as a
     * code; -1 while it is unset or reset.
     */
    static int comboOf(String property) {
        if (property == null || property.isBlank() || "reset".equals(property)) {
            return -1;
        }
        int value;
        try {
            value = (int) Double.parseDouble(property.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
        int[] digits = {value / 1000 % 10, value / 100 % 10, value / 10 % 10, value % 10};
        for (int digit : digits) {
            if (digit < 0 || digit >= KINDS) {
                return -1;
            }
        }
        return code(digits);
    }
}

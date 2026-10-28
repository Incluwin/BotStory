package server.living.mentor;

import java.util.Random;

/**
 * How a mentor stays ahead of its newcomer (docs/BOT_CONCEPT_PLAN.md F4.1): {@code lead} levels above them, one level at
 * a time, each a random 30 s - 5 min (MENTOR_LEVEL_DELAY_*) after theirs, so the two never level in lock-step. Its level
 * never goes down; a newcomer that jumps two levels gets two raises in turn.
 */
public final class MentorLevels {
    private MentorLevels() {
    }

    /** The level it is set on now. */
    public static int target(int newcomerLevel, int lead) {
        return Math.min(200, newcomerLevel + lead);
    }

    /** True while it is below its target. */
    public static boolean behind(int mentorLevel, int newcomerLevel, int lead) {
        return mentorLevel < target(newcomerLevel, lead);
    }

    /** The wait before its next level: uniform in {@code minMs..maxMs}. */
    public static long delayMs(Random random, long minMs, long maxMs) {
        return maxMs <= minMs ? minMs : minMs + (long) (random.nextDouble() * (maxMs - minMs));
    }
}

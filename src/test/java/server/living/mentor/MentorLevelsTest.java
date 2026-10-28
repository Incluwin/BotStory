package server.living.mentor;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a mentor stays ahead (docs/BOT_CONCEPT_PLAN.md F4.1): its lead above its newcomer, never past 200, raised while
 * below it, each raise a random 30 s - 5 min after theirs so the two never level in lock-step.
 */
class MentorLevelsTest {
    @Test
    void itAimsItsLeadAboveItsNewcomer() {
        assertEquals(4, MentorLevels.target(1, 3));
        assertEquals(15, MentorLevels.target(10, 5));
        assertEquals(200, MentorLevels.target(198, 5), "never past the cap");
    }

    @Test
    void itIsBehindOnlyBelowItsTargetAndNeverComesDown() {
        assertTrue(MentorLevels.behind(4, 2, 3), "the newcomer levelled: one raise due");
        assertFalse(MentorLevels.behind(5, 2, 3), "on target");
        assertFalse(MentorLevels.behind(12, 2, 3), "ahead (a newcomer that died a lot): it waits, never goes down");
    }

    @Test
    void eachRaiseWaitsWithinTheBand() {
        Random random = new Random(7);
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            long delay = MentorLevels.delayMs(random, 30_000L, 300_000L);
            assertTrue(delay >= 30_000L && delay < 300_000L, String.valueOf(delay));
            low = Math.min(low, delay);
            high = Math.max(high, delay);
        }
        assertTrue(high - low > 200_000L, "spread across the band, not in lock-step");
        assertEquals(40_000L, MentorLevels.delayMs(random, 40_000L, 40_000L));
    }
}

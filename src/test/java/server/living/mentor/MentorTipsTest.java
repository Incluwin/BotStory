package server.living.mentor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mentor's milestone tips (docs/BOT_CONCEPT_PLAN.md F4.2): each milestone once, at most MENTOR_TIPS_PER_HOUR in an
 * hour, none while its newcomer asked for quiet, and a milestone that came while the hour was spent is told later.
 */
class MentorTipsTest {
    private static final long HOUR = 3_600_000L;

    @Test
    void eachMilestoneIsToldOnce() {
        MentorTips tips = new MentorTips();
        assertTrue(tips.reached(MentorTips.Milestone.LOW_HP));
        assertFalse(tips.reached(MentorTips.Milestone.LOW_HP), "already due");
        assertEquals(MentorTips.Milestone.LOW_HP, tips.next(1_000, 4));
        tips.told(MentorTips.Milestone.LOW_HP, 1_000);
        assertTrue(tips.wasTold(MentorTips.Milestone.LOW_HP));
        assertFalse(tips.reached(MentorTips.Milestone.LOW_HP), "told: never again");
        assertNull(tips.next(2_000, 4));
    }

    @Test
    void theHourCapHoldsTipsBackAndTellsThemLater() {
        MentorTips tips = new MentorTips();
        tips.reached(MentorTips.Milestone.LOW_HP);
        tips.reached(MentorTips.Milestone.FIRST_DEATH);
        tips.reached(MentorTips.Milestone.LV8);
        tips.told(tips.next(0 + 1, 2), 1);
        tips.told(tips.next(2, 2), 2);
        assertNull(tips.next(3, 2), "two in the hour: the third waits");
        MentorTips.Milestone later = tips.next(1 + HOUR, 2);
        assertEquals(MentorTips.Milestone.LV8, later, "an hour on, there is room again");
    }

    @Test
    void muteHoldsEveryTipAndUnmuteLetsThemThrough() {
        MentorTips tips = new MentorTips();
        tips.muted(true);
        tips.reached(MentorTips.Milestone.LV10);
        assertNull(tips.next(1_000, 4), "quiet please");
        tips.muted(false);
        assertEquals(MentorTips.Milestone.LV10, tips.next(2_000, 4), "it stayed due");
    }

    @Test
    void aRestartRestoresTheToldOnesWithoutSpendingTheHour() {
        MentorTips tips = new MentorTips();
        tips.told(MentorTips.Milestone.VICTORIA, 0L); // read back from living_event_log
        assertFalse(tips.reached(MentorTips.Milestone.VICTORIA));
        tips.reached(MentorTips.Milestone.LV8);
        assertEquals(MentorTips.Milestone.LV8, tips.next(5, 1), "the restored one took no room in the hour");
    }

    @Test
    void theQuietVerbsAndTheirUndo() {
        assertTrue(MentorTips.muteVerb("quiet please"));
        assertTrue(MentorTips.muteVerb("Shhh"));
        assertTrue(MentorTips.muteVerb("ok no more tips lol"));
        assertTrue(MentorTips.muteVerb("can you stop the tips"));
        assertFalse(MentorTips.muteVerb("quietly walking"), "a word inside a word");
        assertFalse(MentorTips.muteVerb("tips on"));
        assertFalse(MentorTips.muteVerb(null));
        assertTrue(MentorTips.unmuteVerb("tips on"));
        assertTrue(MentorTips.unmuteVerb("ok you can talk"));
        assertFalse(MentorTips.unmuteVerb("thanks for the tip"));
    }
}

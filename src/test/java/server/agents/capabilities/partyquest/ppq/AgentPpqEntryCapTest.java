package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;

/**
 * The PPQ review's findings 4 and 5 and its split item (every mode): the run's 75-minute cap counts from entering the
 * event, and the time before entry has caps of its own: 30 seconds for a companion run, whose human leader clicks
 * Guon himself, and 15 minutes for every run. A human leader down inside the bound event is PiratePQ.js's to judge
 * (his OK ends the event); one down anywhere else is gone.
 */
class AgentPpqEntryCapTest {
    private static final long MINUTE = 60_000L;

    /** A run not yet in its event: the leader is in town, the session unbound. */
    private static PpqRunFixture beforeEntry(boolean companionRun) {
        PpqRunFixture run = new PpqRunFixture(companionRun, AgentPpqDefinition.RECRUIT_MAP);
        run.session.clearEventInstance();
        return run;
    }

    @Test
    void theCapCountsFromEntry() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_TWO_MAP)) {
            run.session.clearEventInstance();
            run.tick(1_000L + 9 * MINUTE); // Guon at nine minutes: the bind
            for (int minute = 10; minute <= 83; minute++) {
                long now = 1_000L + minute * MINUTE;
                run.session.markProgress(now);
                run.tick(now);
            }
            assertFalse(run.session.terminal(), "74 minutes in: " + run.session.failure());

            long now = 1_000L + 84 * MINUTE;
            run.session.markProgress(now);
            run.tick(now);

            assertEquals("PPQ event timer expired", run.session.failure());
        }
    }

    @Test
    void aRunThatNeverEntersEndsAtThePreEntryCap() {
        try (PpqRunFixture run = beforeEntry(false)) {
            for (int minute = 1; minute < 15; minute++) {
                long now = 1_000L + minute * MINUTE;
                run.session.markProgress(now);
                run.tick(now);
            }
            assertFalse(run.session.terminal(), run.session.failure());

            run.session.markProgress(1_000L + 15 * MINUTE);
            run.tick(1_000L + 15 * MINUTE);

            assertEquals("PPQ entry timed out", run.session.failure());
        }
    }

    @Test
    void aCompanionRunWhoseLeaderNeverEntersEndsAfterThirtySeconds() {
        try (PpqRunFixture run = beforeEntry(true)) {
            run.tick(1_000L + 29_999L);
            assertFalse(run.session.terminal());

            run.tick(1_000L + 30_000L);

            assertEquals("The PPQ leader did not start the event in time", run.session.failure());
        }
    }

    @Test
    void aLeaderWhoEnteredAtTheLastMomentIsBoundFirst() {
        try (PpqRunFixture run = beforeEntry(true)) {
            run.onMap(BEN, AgentPpqDefinition.ENTRY_MAP);

            run.tick(1_000L + 31_000L);

            assertFalse(run.session.terminal(), run.session.failure());
            assertEquals(1_000L + 31_000L, run.session.enteredAtMs());
        }
    }

    @Test
    void pinAStockRunIsNotHeldToThirtySeconds() {
        try (PpqRunFixture run = beforeEntry(false)) {
            run.tick(1_000L + MINUTE);

            assertFalse(run.session.terminal(), run.session.failure());
        }
    }

    @Test
    void aHumanLeaderDownInsideTheEventIsTheEventsToJudge() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            when(run.character(BEN).getHp()).thenReturn(0);

            run.tick(2_000L);

            assertFalse(run.session.terminal(), run.session.failure());
        }
    }

    @Test
    void aLeaderDownOutsideTheEventIsGone() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            when(run.character(BEN).getHp()).thenReturn(0);
            when(run.character(BEN).getEventInstance()).thenReturn(null);

            run.tick(2_000L);

            assertEquals("PPQ event leader is unavailable", run.session.failure());
        }
    }
}

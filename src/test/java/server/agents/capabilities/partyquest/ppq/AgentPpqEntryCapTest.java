package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_TWO_MAP,
                AgentPpqSession.Mode.HUMAN_MEMBER)) {
            when(run.character(BEN).getEventInstance()).thenReturn(null); // only Agents inside: the cap applies
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
    void aHumanInsideKeepsTheRunPastTheCap() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DOOR_MAP)) {
            for (int minute = 1; minute <= 90; minute++) {
                long now = 1_000L + minute * MINUTE;
                run.session.markProgress(now);
                run.tick(now);
            }

            assertFalse(run.session.terminal(), "PiratePQ.js's own timers end a run a human is in: "
                    + run.session.failure());
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
    void anAgentLeaderDownInsideTheEventIsGone() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP,
                AgentPpqSession.Mode.HUMAN_MEMBER)) {
            when(run.character(PpqRunFixture.MIRA).getHp()).thenReturn(0);

            run.tick(2_000L);

            assertEquals("PPQ event leader is unavailable", run.session.failure());
        }
    }

    @Test
    void afterTheClearALeaderWhoLoggedOutLeavesTheRunToTheAfterClearRules() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            when(run.online.findOnlineCharacterById(BEN)).thenReturn(null);
            for (int id : PpqRunFixture.AGENTS) run.onMap(id, AgentPpqDefinition.RECRUIT_MAP);

            run.tick(3_000L);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase(), run.session.failure());
        }
    }

    @Test
    void afterTheClearADeadAgentLeaderOnTheBossMapTalksToNobody() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.BOSS_MAP,
                AgentPpqSession.Mode.HUMAN_MEMBER)) {
            when(run.event.isEventCleared()).thenReturn(true);
            when(run.character(PpqRunFixture.MIRA).getHp()).thenReturn(0);

            run.tick(2_000L);

            assertFalse(run.session.terminal(), run.session.failure());
            verify(run.actions, never()).npcPosition(any(), eq(AgentPpqDefinition.RESCUE_NPC));
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

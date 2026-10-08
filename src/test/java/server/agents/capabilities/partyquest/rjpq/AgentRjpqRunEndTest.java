package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * Step 3 on the run's own tick: the leader going down is called out but ends nothing; the event's end, the leader
 * leaving, a lone corpse or a stall end the run with the companions' own words; past the clear nothing fails and the
 * run completes once its companions are out.
 */
class AgentRjpqRunEndTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.SPECIAL_LAB_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private void tick(long nowMs) {
        AgentRjpqCoordinator.tickSession(fixture.session, nowMs);
    }

    @Test
    void theLeaderGoingDownIsCalledOutOnceAndEndsNothing() {
        when(fixture.character(BEN).getHp()).thenReturn(0);

        tick(2_000L);
        tick(3_000L);

        assertTrue(fixture.session.leaderWentDown());
        assertFalse(fixture.session.terminal());
        assertEquals(List.of("Ben went down!"), fixture.said);
    }

    @Test
    void theEventEndingAfterHeWentDownBeforeTheFilesIsDefeat() {
        when(fixture.character(BEN).getHp()).thenReturn(0);
        tick(2_000L);
        when(fixture.event.isEventDisposed()).thenReturn(true);

        tick(3_000L);

        assertTrue(fixture.session.terminal());
        assertEquals("The RJPQ event ended before the clear", fixture.session.failure());
        assertEquals("Ben, we can't hand in the files without you. let's head back.", fixture.said.getLast());
    }

    @Test
    void afterTheFilesItIsLeaderDown() {
        fixture.properties.put("statusStg4", "1");
        when(fixture.character(BEN).getHp()).thenReturn(0);
        tick(2_000L);
        when(fixture.event.isEventDisposed()).thenReturn(true);

        tick(3_000L);

        assertEquals("Ben, we couldn't finish it without you. let's head back.", fixture.said.getLast());
    }

    @Test
    void theTimerRunningOutIsOutOfTime() {
        when(fixture.event.getTimeLeft()).thenReturn(900L);
        tick(2_000L);
        when(fixture.event.isEventDisposed()).thenReturn(true);

        tick(3_000L);

        assertEquals(List.of("out of time... yulete got away."), fixture.said);
    }

    @Test
    void theLeaderOfflineIsGoneAndAPassedLeadIsTheGenericEnd() {
        when(fixture.online.findOnlineCharacterById(BEN)).thenReturn(null);
        tick(2_000L);
        assertEquals("The RJPQ event leader is offline", fixture.session.failure());
        assertEquals(List.of("Ben's gone. we'll head back."), fixture.said);
    }

    @Test
    void aPassedLeadEndsTheRun() {
        when(fixture.event.getLeaderId()).thenReturn(MIRA);
        tick(2_000L);
        assertTrue(fixture.session.terminal());
        assertEquals(List.of("that's it for this one."), fixture.said);
    }

    @Test
    void aLoneCorpseEndsTheRunInItsOwnWordsButOneWithAFriendStandingWaits() {
        when(fixture.character(MIRA).getHp()).thenReturn(0);
        when(fixture.character(TOBI).getHp()).thenReturn(0);
        tick(2_000L);
        assertFalse(fixture.session.terminal());

        when(fixture.character(UNA).getHp()).thenReturn(0);
        tick(3_000L);

        assertTrue(fixture.session.terminal());
        assertEquals(List.of("i'm down too... that's it for us."), fixture.said);
        verify(fixture.packets).broadcastChatText(fixture.character(MIRA), "i'm down too... that's it for us.",
                false, 0);
    }

    @Test
    void aStalledRunIsCalledOffAfterTheWatchdogTime() {
        tick(2_000L);
        assertFalse(fixture.session.terminal());

        tick(2_000L + 600_000L);

        assertTrue(fixture.session.terminal());
        assertEquals(List.of("we're stuck. let's call it."), fixture.said);
    }

    @Test
    void pastTheClearNothingFailsAndTheRunCompletesOnceItsCompanionsAreOut() {
        when(fixture.event.isEventCleared()).thenReturn(true);
        when(fixture.character(BEN).getHp()).thenReturn(0);
        tick(2_000L);
        assertFalse(fixture.session.terminal());

        for (int id : RjpqStageFixture.COMPANIONS) {
            when(fixture.character(id).getEventInstance()).thenReturn(null);
        }
        tick(3_000L);

        assertEquals(AgentRjpqSession.Phase.COMPLETED, fixture.session.phase());
        assertTrue(fixture.said.isEmpty());
    }
}

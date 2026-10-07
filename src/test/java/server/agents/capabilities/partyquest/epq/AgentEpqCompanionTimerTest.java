package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Step 2.3: a companion run's event clock starts when the leader takes the party in (EllinPQ's own 30-minute timer
 * starts at his Ellin click), plus a short slack so the stock timer always ends the event first; it never runs past the
 * clear. A stock run keeps counting from its admission.
 */
class AgentEpqCompanionTimerTest {
    private static final long START = 1_000L;
    private static final long ENTERED = START + 600_000L;
    private static final long TIMER = 1_800_000L;
    private static final long SLACK = 2_000L;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun) {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        session.markEnteredEvent(ENTERED);
        return session;
    }

    private void tick(AgentEpqSession session, long nowMs) {
        session.markProgress(nowMs);
        AgentEpqCoordinator.tickSession(session, nowMs);
    }

    @Test
    void aCompanionRunCountsFromTheLeadersEntry() {
        AgentEpqSession session = session(true);
        tick(session, START + TIMER);
        assertFalse(session.terminal());
        tick(session, ENTERED + TIMER + SLACK - 1L);
        assertFalse(session.terminal());

        tick(session, ENTERED + TIMER + SLACK);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals("EPQ event timer expired", session.failure());
    }

    @Test
    void aClearedCompanionRunNeverTimesOut() {
        AgentEpqSession session = session(true);
        when(run.event.isEventCleared()).thenReturn(true);

        tick(session, ENTERED + TIMER + SLACK + 60_000L);

        assertFalse(session.phase() == AgentEpqSession.Phase.FAILED
                && "EPQ event timer expired".equals(session.failure()));
    }

    @Test
    void pinAStockRunCountsFromItsAdmission() {
        AgentEpqSession session = session(false);

        tick(session, START + TIMER);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals("EPQ event timer expired", session.failure());
    }
}

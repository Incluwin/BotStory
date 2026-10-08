package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 1 (all modes): the run's 15-minute timer is the event's. It runs from the run's first FARMING entry
 * (LudiMazePQ.js starts its timer as the party enters), for the catalog's eventTimerMs plus TIMEOUT_SLACK_MS (5 s),
 * and never after the clear (clearPQ stops the event's timer). Before, it ran from admission, so a party that waited
 * at Rolly lost that time, and a human idling on the reward map after the clear was failed by it.
 */
class AgentLmpqTimerFromEntryTest {
    private static final long ADMITTED = 1_000L;
    private static final long ENTERED = 300_000L;
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession enteredAt(long enteredAtMs) {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.PREPARING,
                ben, mira, kes, pel);
        session.transition(AgentLmpqSession.Phase.FARMING, enteredAtMs);
        return session;
    }

    private static void tick(AgentLmpqSession session, long nowMs) {
        session.markProgress(nowMs); // the run is busy: only the timer can end it
        AgentLmpqCoordinator.tickSession(session, nowMs);
    }

    @Test
    void theTimerRunsFromTheMazeEntryNotFromAdmission() {
        AgentLmpqSession session = enteredAt(ENTERED);

        tick(session, ADMITTED + 15 * 60_000L + 60_000L);
        assertFalse(session.terminal(), "16 minutes after admission, 11 after the entry");

        tick(session, ENTERED + 900_000L + 4_999L);
        assertFalse(session.terminal(), "inside the slack");

        tick(session, ENTERED + 900_000L + 5_000L);
        assertTrue(session.terminal());
        assertEquals("LMPQ event timer expired", session.failure());
    }

    @Test
    void aRunThatNeverEnteredHasNoTimer() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.PREPARING,
                ben, mira, kes, pel);
        session.clearEventInstance();
        when(ben.getEventInstance()).thenReturn(null);

        tick(session, ADMITTED + 20 * 60_000L);

        assertFalse(session.terminal(), "the stall bounds a wait at Rolly, not the event timer");
    }

    @Test
    void theTimerStopsAtTheClear() {
        AgentLmpqSession session = enteredAt(ENTERED);
        session.transition(AgentLmpqSession.Phase.REWARD, ENTERED + 600_000L);
        when(run.event.isEventCleared()).thenReturn(true);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);

        tick(session, ENTERED + 20 * 60_000L);

        assertFalse(session.terminal(), "a human may still claim after the clear");
    }

    @Test
    void theTimerIsTheCatalogsEventTimerPlusTheSlack() throws Exception {
        AgentLmpqSession session = enteredAt(ENTERED);
        assertTrue(AgentLmpqCoordinator.eventTimerExpired(session, ENTERED + 905_000L));
        assertFalse(AgentLmpqCoordinator.eventTimerExpired(session, ENTERED + 904_999L));
        assertEquals(0, LmpqSource.of("AgentLmpqCoordinator").count("\\bEVENT_TIMEOUT_MS\\b"),
                "the admission-based timer is gone");
    }
}

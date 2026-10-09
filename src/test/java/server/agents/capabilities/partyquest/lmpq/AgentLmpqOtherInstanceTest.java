package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6h review (LOW): "a participant left the active maze" fails a run only for a member this event was seen holding.
 * A member the event never registered who stands in another event instance (he started another quest, a Doll House
 * instance, before Rolly's start) is not a member who left the maze: he departs as never-registered after
 * ENTRY_HOLD_GRACE_MS, like any member the event never held, and the run goes on.
 */
class AgentLmpqOtherInstanceTest {
    private static final long BIND = 10_000L;
    private static final int DOLL_HOUSE_MAP = 922_000_010;
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character ann = run.human(5, "Ann");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aNeverRegisteredMemberInAnotherInstanceDepartsAfterTheGrace() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.PREPARING,
                ben, mira, kes, pel, ann);
        session.clearEventInstance();
        when(ann.getMapId()).thenReturn(DOLL_HOUSE_MAP);
        when(ann.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        run.livePlayers(ben, mira, kes, pel);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, BIND);
            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase(), "bound on Ben's entry");
            assertFalse(session.terminal(), session.failure());

            AgentLmpqCoordinator.tickSession(session, BIND + 5_000L);

            assertFalse(session.terminal(), session.failure());
            assertTrue(session.departed(ann.getId()));
            assertEquals(List.of("LMPQ departed: session=" + session.sessionId() + " member=Ann(5) type=HUMAN"
                            + " map=" + DOLL_HOUSE_MAP + " phase=FARMING registered=4 companionRun=false"
                            + " reason=never-registered"),
                    lines.startingWith("LMPQ departed:"));
        }
    }

    @Test
    void aMemberTheEventHeldWhoIsNowInAnotherInstanceStillFailsTheRun() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel, ann);
        AgentLmpqCoordinator.tickSession(session, BIND); // the event holds everyone
        when(ann.getMapId()).thenReturn(DOLL_HOUSE_MAP);
        when(ann.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        run.livePlayers(ben, mira, kes, pel);

        AgentLmpqCoordinator.tickSession(session, BIND + 1_000L);

        assertEquals("An LMPQ participant left the active maze", session.failure());
    }
}

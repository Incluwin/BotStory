package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A member the event never registers (a human who walked off 220000000 or went offline before Rolly; LudiMazePQ.js
 * starts without him when three or more are eligible) departs ENTRY_HOLD_GRACE_MS (5 s) after the bind, as
 * "never-registered", so the rendezvous stops waiting for him. A member registered shortly after the bind (the event
 * registers a party one member at a time) is never departed.
 */
class AgentLmpqNeverRegisteredTest {
    private static final long BIND = 10_000L;
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

    /** A run at Rolly; at BIND Ben is inside and the event holds {@code held}. Ann stayed in town. */
    private AgentLmpqSession boundAt(Character... held) {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.PREPARING,
                ben, mira, kes, pel, ann);
        session.clearEventInstance();
        when(ann.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);
        when(ann.getEventInstance()).thenReturn(null);
        run.livePlayers(held);
        AgentLmpqCoordinator.tickSession(session, BIND);
        assertEquals(AgentLmpqSession.Phase.FARMING, session.phase(), "bound on Ben's entry");
        return session;
    }

    @Test
    void aMemberTheEventNeverHeldDepartsAfterTheGraceNotBefore() {
        AgentLmpqSession session = boundAt(ben, mira, kes, pel);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, BIND + 4_999L);
            assertFalse(session.departed(ann.getId()), "still within the grace");

            AgentLmpqCoordinator.tickSession(session, BIND + 5_000L);
            AgentLmpqCoordinator.tickSession(session, BIND + 6_000L);

            assertTrue(session.departed(ann.getId()));
            assertEquals(List.of("LMPQ departed: session=" + session.sessionId() + " member=Ann(5) type=HUMAN"
                            + " map=220000000 phase=FARMING registered=4 companionRun=false reason=never-registered"),
                    lines.startingWith("LMPQ departed:"));
            assertFalse(session.terminal(), session.failure());
        }
    }

    @Test
    void aMemberRegisteredJustAfterTheBindIsNeverDeparted() {
        AgentLmpqSession session = boundAt(ben);
        run.livePlayers(ben, mira, kes, pel, ann);
        when(ann.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(7));
        when(ann.getEventInstance()).thenReturn(run.event);
        AgentLmpqCoordinator.tickSession(session, BIND + 100L);

        AgentLmpqCoordinator.tickSession(session, BIND + 20_000L);

        for (Character member : List.of(ben, mira, kes, pel, ann)) assertFalse(session.departed(member.getId()));
    }

    @Test
    void theRendezvousStopsWaitingForAMemberNeverRegistered() {
        AgentLmpqSession session = boundAt(ben, mira, kes, pel);
        session.transition(AgentLmpqSession.Phase.REGROUPING, BIND + 1_000L);
        for (Character member : List.of(ben, mira, kes, pel)) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(session.rendezvousRoom()));
        }
        when(ben.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(30);

        AgentLmpqCoordinator.tickSession(session, BIND + 2_000L);
        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase(), "Ann may still be entering");

        AgentLmpqCoordinator.tickSession(session, BIND + 5_000L);
        assertEquals(AgentLmpqSession.Phase.CLEARING, session.phase());
    }
}

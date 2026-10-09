package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RJPQ run's own state: four members, a forward-only phase, one failure, and a floor of four for deaths. */
class AgentRjpqSessionTest {
    private static AgentRjpqSession session() {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ZENUMIST, 7L, 865, 1_000L);
        session.addMember(865, AgentRjpqMemberState.MemberType.HUMAN);
        session.addMember(11, AgentRjpqMemberState.MemberType.AGENT);
        session.addMember(12, AgentRjpqMemberState.MemberType.AGENT);
        session.addMember(13, AgentRjpqMemberState.MemberType.AGENT);
        session.setLeadership(865, 11);
        return session;
    }

    @Test
    void aRunHoldsFourMembersAndNoFifth() {
        AgentRjpqSession session = session();

        assertEquals(4, session.memberCount());
        assertThrows(IllegalStateException.class, () -> session.addMember(14, AgentRjpqMemberState.MemberType.AGENT));
        assertThrows(IllegalStateException.class, () -> session.addMember(12, AgentRjpqMemberState.MemberType.AGENT));
        assertEquals(865, session.eventLeaderId());
        assertFalse(session.companionAdopted(), "only the public admit adopts a run");
    }

    @Test
    void thePhaseOnlyMovesForwardAndSaysWhatChanged() {
        AgentRjpqSession session = session();

        AgentRjpqSession.Change change = session.advance(AgentRjpqSession.Phase.BOOKS, 4_000L);

        assertEquals(new AgentRjpqSession.Change(
                AgentRjpqSession.Phase.PREPARING, AgentRjpqSession.Phase.BOOKS, 3_000L, 3_000L), change);
        assertNull(session.advance(AgentRjpqSession.Phase.ENTERING, 5_000L), "never backwards");
        assertNull(session.advance(AgentRjpqSession.Phase.BOOKS, 5_000L), "never to the same phase");
        assertNull(session.advance(AgentRjpqSession.Phase.COMPLETED, 5_000L), "the end is complete() or fail()");
        assertEquals(4_000L, session.lastProgressAtMs());
        session.fail("why", 6_000L);
        assertNull(session.advance(AgentRjpqSession.Phase.UNITS, 7_000L), "a terminal run never moves");
    }

    @Test
    void onlyOneThreadFailsTheRun() {
        AgentRjpqSession session = session();

        assertTrue(session.claimFailure());
        assertFalse(session.claimFailure());
    }

    @Test
    void beforeTheClearEveryDeathIsHeldAtFourOfFour() {
        AgentRjpqSession session = session();

        AgentRjpqSession.Departure first = session.reserveDeparture(12, () -> Set.of(865, 11, 12, 13), 4, 10_000L);
        AgentRjpqSession.Departure later = session.reserveDeparture(12, () -> Set.of(865, 11, 12, 13), 4, 610_000L);

        assertFalse(first.proceed());
        assertTrue(first.firstHold());
        assertFalse(later.proceed(), "ten minutes later it is still held");
        assertFalse(later.firstHold());
        assertEquals(600_000L, later.heldMs());
        assertEquals(1, session.heldDeadCount());
        assertEquals(600_000L, session.releaseHold(12, 610_000L));
        assertEquals(0, session.heldDeadCount());
    }

    @Test
    void lineKeysAndTheLeadersFallAreSaidOnce() {
        AgentRjpqSession session = session();

        assertTrue(session.narrateOnce("B1"));
        assertFalse(session.narrateOnce("B1"));
        assertFalse(session.narrateOnce(" "));
        assertTrue(session.markLeaderWentDown());
        assertFalse(session.markLeaderWentDown());
        assertTrue(session.markDown(12, 3_000L));
        assertFalse(session.markDown(12, 4_000L));
        assertEquals(3_000L, session.downSinceMs(12));
        assertTrue(session.clearDown(12));
        assertEquals(0L, session.downSinceMs(12));
    }

    @Test
    void theEventIsBoundOnceAtTheEntry() {
        AgentRjpqSession session = session();
        scripting.event.EventInstanceManager event = org.mockito.Mockito.mock(scripting.event.EventInstanceManager.class);

        assertTrue(session.bindEventInstance(event, 9_000L));
        assertFalse(session.bindEventInstance(event, 9_500L));
        assertEquals(9_000L, session.enteredEventAtMs());
        assertEquals(12_000L, session.observeCleared(12_000L));
        assertEquals(12_000L, session.observeCleared(15_000L));
    }
}

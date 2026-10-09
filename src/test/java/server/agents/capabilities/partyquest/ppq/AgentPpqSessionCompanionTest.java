package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPQ Step 1 (plan 4.3): a session that can be a companion run. The flag is off unless the companion admit sets it; a
 * failure is claimed once; a line key is said once; a dead member leaves only while more than the stock floor of three
 * would stay; a phase change comes back as a Change for the coordinator to log.
 */
class AgentPpqSessionCompanionTest {
    private static AgentPpqSession session() {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, 1L, 10, false, 1_000L);
        session.addMember(10, AgentPpqMemberState.MemberType.HUMAN);
        for (int id = 11; id <= 13; id++) {
            session.addMember(id, AgentPpqMemberState.MemberType.AGENT);
        }
        return session;
    }

    @Test
    void aSessionIsACompanionRunOnlyWhenTheAdmitSaysSo() {
        AgentPpqSession session = session();
        assertFalse(session.companionAdopted());
        session.setCompanionAdopted(true);
        assertTrue(session.companionAdopted());
        session.setSkipChestRooms(true);
        assertTrue(session.skipChestRooms());
    }

    @Test
    void aMemberIsRemovedOnceAFailureClaimedOnceAndALineSaidOnce() {
        AgentPpqSession session = session();
        assertTrue(session.removeMember(13));
        assertFalse(session.removeMember(13));
        assertEquals(3, session.memberCount());
        assertTrue(session.claimFailure());
        assertFalse(session.claimFailure());
        assertTrue(session.narrateOnce("M1"));
        assertFalse(session.narrateOnce("M1"));
    }

    @Test
    void aDeadMemberLeavesOnlyWhileMoreThanThreeWouldStay() {
        AgentPpqSession session = session();
        Set<Integer> registered = Set.of(10, 11, 12, 13);

        AgentPpqSession.Departure first = session.reserveDeparture(11, () -> registered, 3, 2_000L);
        assertTrue(first.proceed(), "four registered: one may go");
        AgentPpqSession.Departure second = session.reserveDeparture(12, () -> registered, 3, 2_000L);
        assertFalse(second.proceed(), "the first is still in flight: three would stay");
        assertTrue(second.firstHold());
        assertFalse(session.reserveDeparture(12, () -> registered, 3, 3_000L).firstHold());
        assertEquals(1, session.heldDeadCount());
        session.endDeparture(11);
        assertEquals(1_000L, session.releaseHold(12, 3_000L));
        assertEquals(0, session.heldDeadCount());
    }

    @Test
    void aPhaseChangeComesBackForTheCoordinatorToLog() {
        AgentPpqSession session = session();

        AgentPpqSession.Change change = session.transition(AgentPpqSession.Phase.ACTIVE, 4_000L);

        assertEquals(new AgentPpqSession.Change(AgentPpqSession.Phase.PREPARING, AgentPpqSession.Phase.ACTIVE, 3_000L,
                3_000L), change);
        assertNull(session.transition(AgentPpqSession.Phase.ACTIVE, 5_000L), "no change");
        assertNull(session.transition(AgentPpqSession.Phase.ENTERING, 5_000L), "never backwards");
        assertEquals(new AgentPpqSession.Change(AgentPpqSession.Phase.ACTIVE, AgentPpqSession.Phase.CLEARING, 2_000L,
                5_000L), session.transition(AgentPpqSession.Phase.CLEARING, 6_000L));
    }
}

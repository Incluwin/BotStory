package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EPQ session as a companion host holds it (AgentCompanionRunSession), plus the step-1 memory the run's log lines
 * read: phase changes with their times, members seen down, once-only log marks and the rescue counts.
 */
class AgentEpqCompanionRunSessionTest {

    @Test
    void theSessionIsTheHostsCompanionRunHandle() {
        AgentEpqSession session = session();
        AgentCompanionRunSession handle = session;

        assertEquals(session.sessionId(), handle.sessionId());
        assertEquals(10, handle.eventLeaderId());
        session.setLeadership(12, 11);
        assertEquals(12, handle.eventLeaderId());
        assertFalse(handle.companionAdopted(), "only the public admit adopts");
        session.setCompanionAdopted(true);
        assertTrue(handle.companionAdopted());

        assertFalse(session.memberCoordinationChatEnabled());
        handle.setMemberCoordinationChatEnabled(true);
        assertTrue(session.memberCoordinationChatEnabled());
        handle.setMemberCoordinationChatEnabled(false);
        assertFalse(session.memberCoordinationChatEnabled());

        handle.setPaceCalibration(null); // EPQ keeps no calibration
        assertEquals(AgentEpqSession.Phase.PREPARING, session.phase());
    }

    @Test
    void potionsAtEntryDefaultToEmpty() {
        AgentEpqSession session = session();
        assertEquals("", session.potionsAtEntry());
        session.setPotionsAtEntry("{Mira:12/30}");
        assertEquals("{Mira:12/30}", session.potionsAtEntry());
        session.setPotionsAtEntry(null);
        assertEquals("", session.potionsAtEntry());
    }

    @Test
    void removeMemberSaysWhetherTheMemberWasThere() {
        AgentEpqSession session = session();

        assertTrue(session.removeMember(13));
        assertFalse(session.removeMember(13));
        assertNull(session.member(13));
        assertEquals(3, session.memberCount());
        assertFalse(session.removeMember(99));
    }

    @Test
    void advanceReportsEachForwardChangeOnceWithItsTimes() {
        AgentEpqSession session = session();

        AgentEpqSession.Change entering = session.advance(AgentEpqSession.Phase.ENTERING, 1_510L);
        assertEquals(new AgentEpqSession.Change(
                AgentEpqSession.Phase.PREPARING, AgentEpqSession.Phase.ENTERING, 500L, 500L), entering);
        assertEquals(AgentEpqSession.Phase.ENTERING, session.phase());
        assertEquals(1_510L, session.phaseEnteredAtMs());
        assertEquals(1_510L, session.lastProgressAtMs());

        assertNull(session.advance(AgentEpqSession.Phase.ENTERING, 1_600L), "a repeat");
        assertNull(session.advance(AgentEpqSession.Phase.PREPARING, 1_700L), "backwards");
        assertNull(session.advance(null, 1_700L));
        assertEquals(1_510L, session.phaseEnteredAtMs());

        AgentEpqSession.Change boss = session.advance(AgentEpqSession.Phase.BOSS, 4_010L);
        assertEquals(new AgentEpqSession.Change(
                AgentEpqSession.Phase.ENTERING, AgentEpqSession.Phase.BOSS, 2_500L, 3_000L), boss);

        session.fail("stalled", 5_000L);
        assertNull(session.advance(AgentEpqSession.Phase.COMPLETED, 5_100L), "terminal");
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void transitionKeepsItsForwardOnlySemantics() {
        AgentEpqSession session = session();
        session.transition(AgentEpqSession.Phase.STAGE_ONE, 2_000L);
        session.transition(AgentEpqSession.Phase.STAGE_ONE, 3_000L);
        session.transition(AgentEpqSession.Phase.ENTERING, 4_000L);

        assertEquals(AgentEpqSession.Phase.STAGE_ONE, session.phase());
        assertEquals(2_000L, session.phaseEnteredAtMs());
        assertEquals(2_000L, session.lastProgressAtMs());
    }

    @Test
    void aMemberIsSeenDownOnceUntilItIsUpAgain() {
        AgentEpqSession session = session();

        assertTrue(session.markMemberDown(11));
        assertFalse(session.markMemberDown(11));
        assertTrue(session.markMemberDown(12), "each member on its own");
        session.clearMemberDown(11);
        assertTrue(session.markMemberDown(11));
        assertFalse(session.markMemberDown(12));
    }

    @Test
    void logMarksAreOnceOnlyAndApartFromTheChatAnnouncements() {
        AgentEpqSession session = session();

        assertTrue(session.claimLogMark("cleared"));
        assertFalse(session.claimLogMark("cleared"));
        assertTrue(session.claimAnnouncement("cleared"), "chat keys are their own");
        assertTrue(session.claimLogMark("golem-9300181"));
    }

    @Test
    void rescuesAreCountedPerKindInTheOrderFirstSeen() {
        AgentEpqSession session = session();
        assertEquals(Map.of(), session.rescueCounts());

        assertEquals(1, session.countRescue("respawn-flowers"));
        assertEquals(1, session.countRescue("kill-dummy"));
        assertEquals(2, session.countRescue("respawn-flowers"));
        assertEquals(3, session.countRescue("respawn-flowers"));

        Map<String, Integer> counts = session.rescueCounts();
        assertEquals(List.of("respawn-flowers", "kill-dummy"), List.copyOf(counts.keySet()));
        assertEquals(new LinkedHashMap<>(Map.of("respawn-flowers", 3, "kill-dummy", 1)), counts);
        assertEquals("{respawn-flowers=3, kill-dummy=1}", counts.toString());
        session.countRescue("respawn-bugs");
        assertEquals(2, counts.size(), "a copy");
    }

    private static AgentEpqSession session() {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, 10, 1_010L);
        session.addMember(10, AgentEpqMemberState.MemberType.HUMAN);
        session.addMember(11, AgentEpqMemberState.MemberType.AGENT);
        session.addMember(12, AgentEpqMemberState.MemberType.AGENT);
        session.addMember(13, AgentEpqMemberState.MemberType.AGENT);
        session.setLeadership(10, 11);
        return session;
    }
}

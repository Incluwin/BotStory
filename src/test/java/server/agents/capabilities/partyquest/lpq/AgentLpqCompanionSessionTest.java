package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6d LPQ step 1 (C3), the session seam a companion host steers an LPQ run by: the AgentCompanionRunSession handle
 * (chat flag, pace calibration), and the state later steps read (taking a member out, the release having begun, when
 * each phase was first entered, the companions' potions at entry). Nothing here changes how a run plays.
 */
class AgentLpqCompanionSessionTest {
    private static final int BEN = 742_700;
    private static final int MIRA = 742_701;
    private static final int KES = 742_702;

    private AgentLpqSession registered;
    private AgentLpqSession other;

    @AfterEach
    void tearDown() {
        AgentLpqSessionRegistry.remove(registered);
        AgentLpqSessionRegistry.remove(other);
    }

    private static AgentLpqSession session(int leader, int... members) {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, leader, 6, 1_000L);
        session.addMember(leader, AgentLpqMemberState.MemberType.HUMAN);
        for (int member : members) session.addMember(member, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(leader, members[0]);
        return session;
    }

    @Test
    void anLpqSessionIsACompanionRunHandle() {
        AgentLpqSession session = session(BEN, MIRA, KES);
        AgentCompanionRunSession handle = session;

        assertEquals(session.sessionId(), handle.sessionId());
        assertEquals(BEN, handle.eventLeaderId());
        assertFalse(handle.companionAdopted());
        session.setCompanionAdopted(true);
        assertTrue(handle.companionAdopted());
    }

    @Test
    void theHostTurnsCoordinationChatOnAndHandsItsCalibration() {
        AgentLpqSession session = session(BEN, MIRA, KES);
        AgentCompanionRunSession handle = session;
        assertFalse(session.memberCoordinationChatEnabled());
        assertNull(session.paceCalibration());

        handle.setMemberCoordinationChatEnabled(true);
        AgentPqPaceCalibration calibration = new AgentPqPaceCalibration("lpq", 1, Map.of(), Map.of(), 0.0d, 0,
                AgentPqPaceCalibration.Source.PRIOR, Map.of());
        handle.setPaceCalibration(calibration);

        assertTrue(session.memberCoordinationChatEnabled());
        assertSame(calibration, session.paceCalibration());
        handle.setMemberCoordinationChatEnabled(false);
        assertFalse(session.memberCoordinationChatEnabled());
    }

    @Test
    void aMemberCanBeTakenOutButNeverTheLeader() {
        AgentLpqSession session = session(BEN, MIRA, KES);

        session.removeMember(KES);
        assertNull(session.member(KES));
        assertEquals(List.of(BEN, MIRA), session.members().stream().map(AgentLpqMemberState::characterId).toList());
        session.removeMember(KES); // already out: nothing happens
        assertEquals(2, session.memberCount());

        assertThrows(IllegalArgumentException.class, () -> session.removeMember(BEN));
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(2, session.memberCount());
    }

    @Test
    void terminationStartedFollowsBeginTermination() {
        AgentLpqSession session = session(BEN, MIRA, KES);
        assertFalse(session.terminationStarted());
        assertTrue(session.beginTermination());
        assertTrue(session.terminationStarted());
        assertFalse(session.beginTermination());
        assertTrue(session.terminationStarted());
    }

    @Test
    void eachPhaseKeepsTheTimeItWasFirstEntered() {
        AgentLpqSession session = session(BEN, MIRA, KES);
        assertEquals(0L, session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_1));

        session.transition(AgentLpqSession.Phase.ENTERING, 2_000L);
        session.transition(AgentLpqSession.Phase.STAGE_1, 3_000L);
        session.transition(AgentLpqSession.Phase.STAGE_2, 4_000L);
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_000L); // the leader steps back: not a new first entry
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_500L); // the same phase: no transition at all
        session.transition(AgentLpqSession.Phase.STAGE_2, 6_000L);

        assertEquals(2_000L, session.firstEnteredAtMs(AgentLpqSession.Phase.ENTERING));
        assertEquals(3_000L, session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_1));
        assertEquals(4_000L, session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_2));
        assertEquals(0L, session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_3));
        assertEquals(0L, session.firstEnteredAtMs(AgentLpqSession.Phase.PREPARING));

        session.fail("The LPQ event timer expired", 7_000L);
        assertEquals(7_000L, session.firstEnteredAtMs(AgentLpqSession.Phase.FAILED));
        session.transition(AgentLpqSession.Phase.STAGE_3, 8_000L); // terminal: ignored
        assertEquals(0L, session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_3));

        AgentLpqSession completed = session(BEN, MIRA, KES);
        completed.transition(AgentLpqSession.Phase.EXITING, 9_000L);
        completed.complete(10_000L);
        assertEquals(10_000L, completed.firstEnteredAtMs(AgentLpqSession.Phase.COMPLETED));
        assertEquals(9_000L, completed.firstEnteredAtMs(AgentLpqSession.Phase.EXITING));
    }

    @Test
    void thePotionsAtEntryAreKeptAsLogged() {
        AgentLpqSession session = session(BEN, MIRA, KES);
        assertNull(session.potionsAtEntry());
        session.setPotionsAtEntry("{Mira:40/50, Kes:12/30}");
        assertEquals("{Mira:40/50, Kes:12/30}", session.potionsAtEntry());
    }

    @Test
    void unindexingAMemberDropsOnlyThatSessionsEntry() {
        registered = session(BEN, MIRA, KES);
        AgentLpqSessionRegistry.registerComplete(registered);
        other = session(742_710, 742_711, 742_712);
        AgentLpqSessionRegistry.registerComplete(other);

        AgentLpqSessionRegistry.unindexMember(other, KES); // not other's member: nothing changes
        assertSame(registered, AgentLpqSessionRegistry.forMember(KES));

        AgentLpqSessionRegistry.unindexMember(registered, KES);
        assertNull(AgentLpqSessionRegistry.forMember(KES));
        assertSame(registered, AgentLpqSessionRegistry.forMember(MIRA));
        assertSame(registered, AgentLpqSessionRegistry.forOperator(BEN));
        assertSame(other, AgentLpqSessionRegistry.forMember(742_711));
        assertTrue(AgentLpqSessionRegistry.sessions().contains(registered));
        assertEquals(3, registered.memberCount(), "the registry index only: the session keeps its member");
    }
}

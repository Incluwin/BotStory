package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h LMPQ step 1, C3: the session seam a companion host and Steps 2-4 build on. An LMPQ session is a companion-run
 * handle, keeps the first time it entered each phase, its companions' potions on entry and the coupons at the
 * clearing, can lose a member (never its leader), and says when its release has begun; the registry can drop one
 * member's index.
 */
class AgentLmpqCompanionSessionTest {
    private static final int BEN = 781_700;
    private static final int MIRA = 781_701;
    private static final int KES = 781_702;
    private final List<AgentLmpqSession> registered = new ArrayList<>();

    @AfterEach
    void tearDown() {
        registered.forEach(AgentLmpqSessionRegistry::remove);
    }

    private static AgentLmpqSession session(int leader, int... agents) {
        AgentLmpqSession session = new AgentLmpqSession(
                AgentLmpqSession.Mode.HUMAN_LEADER, 7L, leader, agents.length + 1, 1_000L);
        session.addMember(leader, AgentLmpqMemberState.MemberType.HUMAN);
        for (int agent : agents) session.addMember(agent, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(leader, agents[0]);
        return session;
    }

    @Test
    void anLmpqSessionIsACompanionRunHandle() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        AgentCompanionRunSession handle = session;
        assertEquals(session.sessionId(), handle.sessionId());
        assertEquals(BEN, handle.eventLeaderId());
        assertFalse(handle.companionAdopted());
        session.setCompanionAdopted(true);
        assertTrue(handle.companionAdopted());
    }

    @Test
    void theHostTurnsCoordinationChatOnAndHandsItsCalibration() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        AgentCompanionRunSession handle = session;
        assertFalse(session.memberCoordinationChatEnabled());
        assertNull(session.paceCalibration());

        AgentPqPaceCalibration calibration = new AgentPqPaceCalibration("lmpq", 1, Map.of(), Map.of(), 0.0d, 0,
                AgentPqPaceCalibration.Source.PRIOR, Map.of());
        handle.setMemberCoordinationChatEnabled(true);
        handle.setPaceCalibration(calibration);

        assertTrue(session.memberCoordinationChatEnabled());
        assertSame(calibration, session.paceCalibration());
        handle.setMemberCoordinationChatEnabled(false);
        assertFalse(session.memberCoordinationChatEnabled());
    }

    @Test
    void aMemberCanBeTakenOutButNeverTheLeader() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        session.removeMember(KES);

        assertNull(session.member(KES));
        assertEquals(List.of(BEN, MIRA),
                session.members().stream().map(AgentLmpqMemberState::characterId).toList());
        assertEquals(2, session.memberCount());
        assertEquals(3, session.requestedPartySize(), "the admitted size stays");
        assertThrows(IllegalArgumentException.class, () -> session.removeMember(BEN));
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(2, session.memberCount());
    }

    @Test
    void terminationStartedFollowsBeginTermination() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        assertFalse(session.terminationStarted());
        assertTrue(session.beginTermination());
        assertTrue(session.terminationStarted());
        assertFalse(session.beginTermination());
        assertTrue(session.terminationStarted());
    }

    @Test
    void eachPhaseKeepsTheTimeItWasFirstEntered() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        assertEquals(0L, session.firstEnteredAtMs(AgentLmpqSession.Phase.FARMING));

        session.transition(AgentLmpqSession.Phase.ENTERING, 2_000L);
        session.transition(AgentLmpqSession.Phase.FARMING, 3_000L);
        session.transition(AgentLmpqSession.Phase.FARMING, 3_500L); // same phase: nothing new
        session.transition(AgentLmpqSession.Phase.ENTERING, 3_600L); // backwards: refused
        session.transition(AgentLmpqSession.Phase.REGROUPING, 4_000L);

        assertEquals(2_000L, session.firstEnteredAtMs(AgentLmpqSession.Phase.ENTERING));
        assertEquals(3_000L, session.firstEnteredAtMs(AgentLmpqSession.Phase.FARMING));
        assertEquals(4_000L, session.firstEnteredAtMs(AgentLmpqSession.Phase.REGROUPING));
        assertEquals(0L, session.firstEnteredAtMs(AgentLmpqSession.Phase.CLEARING));
        assertEquals(0L, session.firstEnteredAtMs(AgentLmpqSession.Phase.PREPARING));
        assertEquals(0L, session.firstEnteredAtMs(null));

        session.fail("LMPQ stalled in REGROUPING", 7_000L);
        session.fail("again", 8_000L);
        assertEquals(7_000L, session.firstEnteredAtMs(AgentLmpqSession.Phase.FAILED));
        assertEquals(0L, session.firstEnteredAtMs(AgentLmpqSession.Phase.CLEARING));

        AgentLmpqSession completed = session(BEN, MIRA, KES);
        completed.transition(AgentLmpqSession.Phase.REWARD, 9_000L);
        completed.complete(10_000L);
        completed.complete(11_000L);
        assertEquals(10_000L, completed.firstEnteredAtMs(AgentLmpqSession.Phase.COMPLETED));
        assertEquals(9_000L, completed.firstEnteredAtMs(AgentLmpqSession.Phase.REWARD));
    }

    @Test
    void thePotionsAtEntryAreKeptOnceAndTheCouponsAtTheClearing() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        assertNull(session.potionsAtEntry());
        assertFalse(session.recordPotionsAtEntry(null));
        assertTrue(session.recordPotionsAtEntry("{Mira:40/50, Kes:12/30}"));
        assertFalse(session.recordPotionsAtEntry("{Mira:39/50, Kes:12/30}"), "the first caller wins");
        assertEquals("{Mira:40/50, Kes:12/30}", session.potionsAtEntry());

        assertEquals(0, session.couponsAtClearing());
        session.setCouponsAtClearing(34);
        assertEquals(34, session.couponsAtClearing());
        session.setCouponsAtClearing(-1);
        assertEquals(0, session.couponsAtClearing());
    }

    @Test
    void unindexingAMemberDropsOnlyThatSessionsEntry() {
        AgentLmpqSession session = session(BEN, MIRA, KES);
        AgentLmpqSession other = session(781_710, 781_711, 781_712);
        AgentLmpqSessionRegistry.registerComplete(session);
        AgentLmpqSessionRegistry.registerComplete(other);
        registered.add(session);
        registered.add(other);
        assertSame(session, AgentLmpqSessionRegistry.forMember(KES));

        AgentLmpqSessionRegistry.unindexMember(session, KES);
        AgentLmpqSessionRegistry.unindexMember(other, MIRA); // not other's member: nothing happens
        AgentLmpqSessionRegistry.unindexMember(null, MIRA);

        assertNull(AgentLmpqSessionRegistry.forMember(KES));
        assertSame(session, AgentLmpqSessionRegistry.forMember(MIRA));
        assertSame(session, AgentLmpqSessionRegistry.forOperator(BEN));
        assertSame(other, AgentLmpqSessionRegistry.forMember(781_711));
        assertTrue(AgentLmpqSessionRegistry.sessions().contains(session));
        assertEquals(3, session.memberCount(), "the registry index only: the session keeps its member");
    }
}

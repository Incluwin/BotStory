package server.agents.capabilities.partyquest.opq;

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
 * P6h OPQ step 1 (C3), the session seam a companion host steers an OPQ run by: the AgentCompanionRunSession handle
 * (chat flag, pace calibration), a party of five or six, and the state later steps read (taking a member out, the
 * release having begun, when each phase was first entered, the potions at entry, when the clear was first seen). The
 * registry admits a session of the size it was opened for and names what blocks a new one. Nothing here changes how
 * a run plays.
 */
class AgentOpqCompanionSessionTest {
    private static final int BEN = 743_700;
    private static final int MIRA = 743_701;
    private static final int KES = 743_702;
    private static final int PEL = 743_703;
    private static final int HAL = 743_704;
    private static final int GUS = 743_705;

    private AgentOpqSession registered;
    private AgentOpqSession other;

    @AfterEach
    void tearDown() {
        AgentOpqSessionRegistry.remove(registered);
        AgentOpqSessionRegistry.remove(other);
    }

    private static AgentOpqSession session(int size, int leader, int... agents) {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, leader, size, 1_000L);
        session.addMember(leader, AgentOpqMemberState.MemberType.HUMAN);
        for (int agent : agents) session.addMember(agent, AgentOpqMemberState.MemberType.AGENT);
        session.setLeadership(leader, agents[0]);
        return session;
    }

    @Test
    void anOpqSessionIsACompanionRunHandle() {
        AgentOpqSession session = session(6, BEN, MIRA, KES);
        AgentCompanionRunSession handle = session;

        assertEquals(session.sessionId(), handle.sessionId());
        assertEquals(BEN, handle.eventLeaderId());
        assertFalse(handle.companionAdopted());
        session.setCompanionAdopted(true);
        assertTrue(handle.companionAdopted());
    }

    @Test
    void theHostTurnsCoordinationChatOnAndHandsItsCalibration() {
        AgentOpqSession session = session(6, BEN, MIRA, KES);
        AgentCompanionRunSession handle = session;
        assertFalse(session.memberCoordinationChatEnabled());
        assertNull(session.paceCalibration());

        handle.setMemberCoordinationChatEnabled(true);
        AgentPqPaceCalibration calibration = new AgentPqPaceCalibration("opq", 1, Map.of(), Map.of(), 0.0d, 0,
                AgentPqPaceCalibration.Source.PRIOR, Map.of());
        handle.setPaceCalibration(calibration);

        assertTrue(session.memberCoordinationChatEnabled());
        assertSame(calibration, session.paceCalibration());
        handle.setMemberCoordinationChatEnabled(false);
        assertFalse(session.memberCoordinationChatEnabled());
    }

    @Test
    void aSessionIsOpenedForFiveOrSixAndHoldsNoMore() {
        AgentOpqSession stock = new AgentOpqSession(AgentOpqSession.Mode.AUTONOMOUS, 7L, BEN, 1_000L);
        assertEquals(6, stock.requestedPartySize(), "the stock constructor opens a six-member session");
        assertEquals(5, new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, BEN, 5, 1_000L)
                .requestedPartySize());
        for (int refused : new int[]{0, 4, 7}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, BEN, refused, 1_000L),
                    "size " + refused);
            assertEquals("valid OPQ session required", failure.getMessage());
        }

        AgentOpqSession five = session(5, BEN, MIRA, KES, PEL, HAL);
        IllegalStateException full = assertThrows(IllegalStateException.class,
                () -> five.addMember(GUS, AgentOpqMemberState.MemberType.AGENT));
        assertEquals("invalid or duplicate OPQ member", full.getMessage());
        assertEquals(5, five.memberCount());
        AgentOpqSession six = session(6, BEN, MIRA, KES, PEL, HAL);
        six.addMember(GUS, AgentOpqMemberState.MemberType.AGENT);
        assertEquals(6, six.memberCount());
    }

    @Test
    void theRegistryAdmitsASessionOfTheSizeItWasOpenedFor() {
        registered = session(5, BEN, MIRA, KES, PEL, HAL);
        AgentOpqSessionRegistry.registerComplete(registered);
        assertSame(registered, AgentOpqSessionRegistry.forOperator(BEN));
        assertSame(registered, AgentOpqSessionRegistry.forMember(HAL));

        AgentOpqSession shortOfFive = session(5, 743_710, 743_711, 743_712, 743_713);
        assertEquals("complete OPQ session required", assertThrows(IllegalArgumentException.class,
                () -> AgentOpqSessionRegistry.registerComplete(shortOfFive)).getMessage());
        AgentOpqSession shortOfSix = session(6, 743_710, 743_711, 743_712, 743_713, 743_714);
        assertEquals("complete six-member OPQ session required", assertThrows(IllegalArgumentException.class,
                () -> AgentOpqSessionRegistry.registerComplete(shortOfSix)).getMessage());
        assertEquals("complete six-member OPQ session required", assertThrows(IllegalArgumentException.class,
                () -> AgentOpqSessionRegistry.registerComplete(null)).getMessage());
        assertNull(AgentOpqSessionRegistry.forOperator(743_710));
    }

    @Test
    void theBlockerNamesTheOperatorFirstThenTheFirstHeldMember() {
        registered = session(5, BEN, MIRA, KES, PEL, HAL);
        AgentOpqSessionRegistry.registerComplete(registered);

        assertEquals("operator-session", AgentOpqSessionRegistry.registrationBlocker(BEN, List.of(743_799)));
        assertEquals("member-" + KES, AgentOpqSessionRegistry.registrationBlocker(743_790, List.of(743_799, KES, PEL)));
        assertEquals("", AgentOpqSessionRegistry.registrationBlocker(743_790, List.of(743_799, GUS)));
        assertEquals("", AgentOpqSessionRegistry.registrationBlocker(743_790, null));
    }

    @Test
    void aMemberCanBeTakenOutButNeverTheLeader() {
        AgentOpqSession session = session(6, BEN, MIRA, KES);

        session.removeMember(KES);
        assertNull(session.member(KES));
        assertEquals(List.of(BEN, MIRA), session.members().stream().map(AgentOpqMemberState::characterId).toList());
        session.removeMember(KES); // already out: nothing happens
        assertEquals(2, session.memberCount());
        assertEquals(6, session.requestedPartySize(), "the run was opened for six; a departure does not change it");

        assertThrows(IllegalArgumentException.class, () -> session.removeMember(BEN));
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(2, session.memberCount());
    }

    @Test
    void terminationStartedFollowsBeginTermination() {
        AgentOpqSession session = session(6, BEN, MIRA, KES);
        assertFalse(session.terminationStarted());
        assertTrue(session.beginTermination());
        assertTrue(session.terminationStarted());
        assertFalse(session.beginTermination());
        assertTrue(session.terminationStarted());
    }

    @Test
    void eachPhaseKeepsTheTimeItWasFirstEntered() {
        AgentOpqSession session = session(6, BEN, MIRA, KES);
        assertEquals(0L, session.firstEnteredAtMs(AgentOpqSession.Phase.ENTRANCE));

        session.transition(AgentOpqSession.Phase.ENTERING, 2_000L);
        session.transition(AgentOpqSession.Phase.ENTRANCE, 3_000L);
        session.transition(AgentOpqSession.Phase.ENTRANCE, 3_500L); // the same phase: progress only
        session.transition(AgentOpqSession.Phase.SPLIT_ROOMS, 4_000L);
        session.transition(AgentOpqSession.Phase.ENTRANCE, 5_000L); // backwards: ignored

        assertEquals(2_000L, session.firstEnteredAtMs(AgentOpqSession.Phase.ENTERING));
        assertEquals(3_000L, session.firstEnteredAtMs(AgentOpqSession.Phase.ENTRANCE));
        assertEquals(4_000L, session.firstEnteredAtMs(AgentOpqSession.Phase.SPLIT_ROOMS));
        assertEquals(0L, session.firstEnteredAtMs(AgentOpqSession.Phase.GARDEN));
        assertEquals(0L, session.firstEnteredAtMs(AgentOpqSession.Phase.PREPARING));
        assertEquals(AgentOpqSession.Phase.SPLIT_ROOMS, session.phase());

        session.fail("No legal OPQ progress before timeout", 7_000L);
        assertEquals(7_000L, session.firstEnteredAtMs(AgentOpqSession.Phase.FAILED));
        session.transition(AgentOpqSession.Phase.GARDEN, 8_000L); // terminal: ignored
        assertEquals(0L, session.firstEnteredAtMs(AgentOpqSession.Phase.GARDEN));

        AgentOpqSession completed = session(6, BEN, MIRA, KES);
        completed.transition(AgentOpqSession.Phase.CLAIMING_REWARD, 9_000L);
        completed.complete(10_000L);
        assertEquals(10_000L, completed.firstEnteredAtMs(AgentOpqSession.Phase.COMPLETED));
        assertEquals(9_000L, completed.firstEnteredAtMs(AgentOpqSession.Phase.CLAIMING_REWARD));
    }

    @Test
    void thePotionsAtEntryAndTheClearAreKeptAsFirstSeen() {
        AgentOpqSession session = session(6, BEN, MIRA, KES);
        assertNull(session.potionsAtEntry());
        session.setPotionsAtEntry("{Mira:40/50, Kes:12/30}");
        assertEquals("{Mira:40/50, Kes:12/30}", session.potionsAtEntry());

        assertEquals(0L, session.clearSeenAtMs());
        session.markClearSeen(20_000L);
        session.markClearSeen(21_000L);
        assertEquals(20_000L, session.clearSeenAtMs());
    }

    @Test
    void unindexingAMemberDropsOnlyThatSessionsEntry() {
        registered = session(5, BEN, MIRA, KES, PEL, HAL);
        AgentOpqSessionRegistry.registerComplete(registered);
        other = session(5, 743_720, 743_721, 743_722, 743_723, 743_724);
        AgentOpqSessionRegistry.registerComplete(other);

        AgentOpqSessionRegistry.unindexMember(other, KES); // not other's member: nothing changes
        assertSame(registered, AgentOpqSessionRegistry.forMember(KES));

        AgentOpqSessionRegistry.unindexMember(registered, KES);
        assertNull(AgentOpqSessionRegistry.forMember(KES));
        assertSame(registered, AgentOpqSessionRegistry.forMember(MIRA));
        assertSame(registered, AgentOpqSessionRegistry.forOperator(BEN));
        assertSame(other, AgentOpqSessionRegistry.forMember(743_721));
        assertTrue(AgentOpqSessionRegistry.sessions().contains(registered));
        assertEquals(5, registered.memberCount(), "the registry index only: the session keeps its member");
    }
}

package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2e: in a companion run a companion below TAUROMACIS_MIN_LEVEL (65, provisional) keeps out of Room 6, says
 * so once, leaves Room 7's Tauromacis box (8091004) alone, never fights a Tauromacis and walks out of a room where one
 * is loose; the Tauromacis hold does not wait for rooms no companion of the run can work. A companion at the level,
 * and every stock run, choose as before.
 */
class AgentLmpqDangerRoomTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");

    @AfterEach
    void close() {
        run.close();
    }

    private AgentLmpqSession farming(boolean companionRun) {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, companionRun,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void level(Character agent, int level) {
        when(agent.getLevel()).thenReturn(level);
    }

    @Test
    void theMinimumLevelIsTheProvisionalSixtyFive() {
        assertEquals(65, AgentLmpqCompanionRun.TAUROMACIS_MIN_LEVEL);
    }

    @Test
    void belowTheLevelACompanionKeepsOutOfRoomSixAndSaysSoOnce() {
        level(mira, 55);
        AgentLmpqSession session = farming(true);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        int first = session.member(41).targetRoom();
        session.member(41).clearTargetRoom();
        session.rooms().releaseOwner(41, 3_100L);
        session.member(41).deferUntil(0L);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_200L);

        assertNotEquals(0, first);
        assertNotEquals(AgentLmpqDefinition.TAUROMACIS_ROOM, first);
        assertNotEquals(AgentLmpqDefinition.TAUROMACIS_ROOM, session.member(41).targetRoom());
        verify(run.party, times(1)).sendPartyChat(mira, AgentLmpqCompanionPlay.skippingRoomSix());
    }

    @Test
    void atTheLevelRoomSixIsItsBestRoomAsBefore() {
        level(mira, 70);
        AgentLmpqSession session = farming(true);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        assertEquals(AgentLmpqDefinition.TAUROMACIS_ROOM, session.member(41).targetRoom());
        verify(run.party, never()).sendPartyChat(any(), eq(AgentLmpqCompanionPlay.skippingRoomSix()));
    }

    @Test
    void aStockRunChoosesAsBefore() {
        level(mira, 55);
        AgentLmpqSession session = farming(false);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        assertEquals(AgentLmpqDefinition.TAUROMACIS_ROOM, session.member(41).targetRoom());
    }

    /** Mira works Room 7: its 8091004 box (nearer) and an 8098000 box. */
    private Reactor[] roomSeven(AgentLmpqSession session) {
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(7));
        when(mira.getPosition()).thenReturn(new Point(0, 145));
        session.rooms().reserve(7, 41, 2_500L);
        session.member(41).assignTargetRoom(7);
        Reactor bull = box(701, AgentLmpqDefinition.TAUROMACIS_BOX, new Point(95, 23));
        Reactor plain = box(702, 8_098_000, new Point(274, 85));
        when(run.actions.reactors(mira)).thenReturn(List.of(bull, plain));
        return new Reactor[]{bull, plain};
    }

    private static Reactor box(int objectId, int id, Point at) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getObjectId()).thenReturn(objectId);
        when(reactor.getId()).thenReturn(id);
        when(reactor.getPosition()).thenReturn(at);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.isActive()).thenReturn(true);
        return reactor;
    }

    @Test
    void belowTheLevelRoomSevensTauromacisBoxIsLeftAlone() {
        level(mira, 55);
        AgentLmpqSession session = farming(true);
        roomSeven(session);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        assertEquals(702, session.member(41).committedReactorObjectId());
    }

    @Test
    void atTheLevelTheNearestBoxIsTakenAsBefore() {
        level(mira, 70);
        AgentLmpqSession session = farming(true);
        roomSeven(session);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        assertEquals(701, session.member(41).committedReactorObjectId());
    }

    @Test
    void belowTheLevelATauromacisIsNeverFoughtAndALooseOneSendsTheCompanionOut() {
        level(mira, 55);
        AgentLmpqSession session = farming(true);
        roomSeven(session);
        Set<Integer> withoutBull = new java.util.HashSet<>(AgentLmpqDefinition.COUPON_MOBS);
        withoutBull.remove(AgentLmpqDefinition.TAUROMACIS);
        when(run.actions.liveMonsterCount(mira, Set.copyOf(withoutBull))).thenReturn(2);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        verify(run.actions).grind(run.entry(mira), Set.copyOf(withoutBull));

        when(run.actions.liveMonsterCount(mira, Set.of(AgentLmpqDefinition.TAUROMACIS))).thenReturn(1);
        session.member(41).deferUntil(0L);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 4_000L);

        assertEquals(0, session.member(41).targetRoom(), "it gave the room up");
        assertTrue(session.member(41).avoids(7));
        assertEquals(AgentLmpqRoomLedger.State.AVAILABLE, session.rooms().room(7).state(), "free for one at the level");
        verify(run.party).sendPartyChat(mira, AgentLmpqCompanionPlay.bullLoose());
        session.member(41).deferUntil(0L);
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(3));
        when(run.actions.liveMonsterCount(mira, Set.of(AgentLmpqDefinition.TAUROMACIS))).thenReturn(0);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 5_000L);
        assertFalse(List.of(0, 6, 7).contains(session.member(41).targetRoom()), "never back to Room 7 or into 6");
    }

    @Test
    void atTheLevelTheTauromacisIsFoughtAsBefore() {
        level(mira, 70);
        AgentLmpqSession session = farming(true);
        roomSeven(session);
        when(run.actions.liveMonsterCount(mira, AgentLmpqDefinition.COUPON_MOBS)).thenReturn(1);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        verify(run.actions).grind(run.entry(mira), AgentLmpqDefinition.COUPON_MOBS);
        assertEquals(7, session.member(41).targetRoom());
    }

    @Test
    void theHoldDoesNotWaitForRoomsNoCompanionCanWork() {
        level(mira, 55);
        level(kes, 60);
        AgentLmpqSession session = farming(true);

        assertTrue(AgentLmpqCoordinator.readyToRegroup(session, 36, 3_000L), "nobody here can face Tauromacis");

        level(kes, 66);
        assertFalse(AgentLmpqCoordinator.readyToRegroup(session, 36, 3_000L), "Kes can: the hold waits for him");
    }

    @Test
    void aStockRunHoldsAsBefore() {
        level(mira, 55);
        level(kes, 55);
        AgentLmpqSession session = farming(false);

        assertFalse(AgentLmpqCoordinator.readyToRegroup(session, 36, 3_000L));
    }
}

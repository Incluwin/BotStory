package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentAutopotPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.4: in a companion run the walking exits (portal 4 of the entrance, portal 3 of stages 1 and 2) are taken with
 * the leader, not ahead of him: a companion walks to the portal and waits there until he stands within
 * LEADER_AT_EXIT_PX of it or is already on a later stage, and after LEADER_HINT_MS of waiting one of them says E0. A
 * stock run takes the exit at once, and a companion's top-up still comes first.
 */
class AgentEpqCompanionExitTest {
    private static final Point PORTAL = new Point(600, 0);

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.ENTRANCE_MAP);

    AgentEpqCompanionExitTest() {
        for (Character member : List.of(ben, mira, tobi, una)) when(member.getCurrentMaxHp()).thenReturn(500);
        when(run.actions.portalPosition(any(), anyInt())).thenReturn(new Point(PORTAL));
        when(run.epq.enterPortal(any(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun, int mapId, AgentEpqSession.Phase phase) {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, mapId);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, phase,
                ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void miraAtThePortal() {
        when(mira.getPosition()).thenReturn(new Point(PORTAL));
    }

    @Test
    void aCompanionWalksToTheEntrancePortalAndWaitsForAFarLeader() {
        AgentEpqSession session = session(true, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        verify(run.actions).navigate(run.entry(mira), PORTAL, true);
        miraAtThePortal();
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        verify(run.epq, never()).enterPortal(any(), anyInt());
        verify(run.actions).stop(run.entry(mira));
    }

    @Test
    void itGoesThroughOnceTheLeaderStandsByThePortal() {
        AgentEpqSession session = session(true, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);
        miraAtThePortal();
        when(ben.getPosition()).thenReturn(new Point(PORTAL.x - 150, 0));

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.epq).enterPortal(mira, 4);
    }

    @Test
    void itWaitsWhileTheLeaderIsJustOutOfReach() {
        AgentEpqSession session = session(true, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);
        miraAtThePortal();
        when(ben.getPosition()).thenReturn(new Point(PORTAL.x - 151, 0));

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.epq, never()).enterPortal(any(), anyInt());
        verify(run.actions).stop(run.entry(mira));
    }

    @Test
    void itGoesThroughWhenTheLeaderIsAlreadyOnTheNextStage() {
        AgentEpqSession session = session(true, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);
        miraAtThePortal();
        run.onMap(ben, AgentEpqDefinition.STAGE_ONE_MAP);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.epq).enterPortal(mira, 4);
    }

    @Test
    void theStageOneExitWaitsForTheLeaderToo() {
        AgentEpqSession session = session(true, AgentEpqDefinition.STAGE_ONE_MAP, AgentEpqSession.Phase.STAGE_ONE);
        miraAtThePortal(); // no tree left: the stage's exit is open

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        verify(run.epq, never()).enterPortal(any(), anyInt());

        when(ben.getPosition()).thenReturn(new Point(PORTAL.x + 40, 0));
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        verify(run.epq).enterPortal(mira, 3);
    }

    @Test
    void theStageTwoExitWaitsForTheLeaderToo() {
        AgentEpqSession session = session(true, AgentEpqDefinition.STAGE_TWO_MAP, AgentEpqSession.Phase.STAGE_TWO);
        thornsGone();
        miraAtThePortal();

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        verify(run.epq, never()).enterPortal(any(), anyInt());

        run.onMap(ben, AgentEpqDefinition.STAGE_THREE_MAP);
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        verify(run.epq).enterPortal(mira, 3);
    }

    @Test
    void aCompanionWaitingAtTheExitTellsItsLeaderOnce() {
        AgentEpqSession session = session(true, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);
        miraAtThePortal();
        String line = "ready when you are, Ben. the portal's over here.";

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L + 19_999L);
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L + 20_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L + 25_000L);
        verify(run.packets, times(1)).broadcastChatText(mira, line, false, 0);
        verify(run.packets, times(1)).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void pinAStockRunTakesTheExitAtOnce() {
        AgentEpqSession session = session(false, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);
        miraAtThePortal();

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.epq).enterPortal(mira, 4);
    }

    @Test
    void pinACompanionStillTopsUpBeforeTheExit() {
        AgentEpqSession session = session(true, AgentEpqDefinition.ENTRANCE_MAP, AgentEpqSession.Phase.ENTERING);
        miraAtThePortal();
        run.onMap(ben, AgentEpqDefinition.STAGE_ONE_MAP); // he went ahead: the exit is hers to take
        when(mira.getHp()).thenReturn(50);
        when(mira.getCurrentMaxHp()).thenReturn(4_400);
        when(run.actions.hpPotionCount(mira)).thenReturn(113);
        when(run.actions.useItem(any(), eq(2_000_002))).thenReturn(true);
        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class)) {
            potions.when(() -> AgentPotionService.selectAutopotForCurrentDeficit(any(), eq(true)))
                    .thenReturn(new AgentAutopotPolicy.AutopotItemChoice(2_000_002, (short) 1, null));

            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        }

        verify(run.actions).useItem(mira, 2_000_002);
        verify(run.epq, never()).enterPortal(any(), anyInt());
    }

    @Test
    void theExitRuleTable() {
        assertTrue(AgentEpqCompanionPlay.mayTakeWalkingExit(1, 0, -1L, 150), "he went ahead");
        assertTrue(AgentEpqCompanionPlay.mayTakeWalkingExit(0, 0, 150L, 150), "he stands at the exit");
        assertTrue(AgentEpqCompanionPlay.mayTakeWalkingExit(0, 0, 0L, 150));
        assertFalse(AgentEpqCompanionPlay.mayTakeWalkingExit(0, 0, 151L, 150), "too far yet");
        assertFalse(AgentEpqCompanionPlay.mayTakeWalkingExit(0, 0, -1L, 150), "where he stands is unknown");
        assertFalse(AgentEpqCompanionPlay.mayTakeWalkingExit(-1, 0, -1L, 150), "out of the event");
        assertFalse(AgentEpqCompanionPlay.mayTakeWalkingExit(0, 1, 10L, 150), "he is behind");
    }

    private void thornsGone() {
        Reactor spine = reactor(new Point(-400, 0), (byte) 4);
        Reactor pond = reactor(new Point(400, 0), (byte) 0);
        when(run.map.getReactorById(AgentEpqDefinition.SPINE_REACTOR)).thenReturn(spine);
        when(run.map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
    }

    private static Reactor reactor(Point position, byte state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.getState()).thenReturn(state);
        when(reactor.getArea()).thenReturn(new Rectangle(position.x - 30, position.y - 30, 60, 60));
        return reactor;
    }
}

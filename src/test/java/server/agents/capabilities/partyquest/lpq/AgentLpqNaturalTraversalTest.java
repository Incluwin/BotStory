package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.4: where a companion run withholds a traversal rescue, the companion walks as a player does: a laggard
 * walks the authored route (no absolute move), the gather at the next portal walks (no teleport), and a stalled stage
 * portal walk or trap-room exit is re-planned, never moved. Stock runs keep their recoveries (pins).
 */
class AgentLpqNaturalTraversalTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private static final int S2 = AgentLpqDefinition.stage(2).mapId();
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private static final int S4 = AgentLpqDefinition.stage(4).mapId();
    private static final int TRAP = AgentLpqDefinition.STAGE_2_TRAP_MAP;
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession run(boolean companion, AgentLpqSession.Phase phase, List<Character> crew) {
        return fx.session(companion, phase, crew.toArray(Character[]::new));
    }

    private List<Character> laggardOnStageThree() {
        List<Character> crew = fx.crew(S4, new Point(0, 0));
        fx.onMap(crew.get(2), S3, new Point(0, 0));
        fx.portal(S3, 2, new Point(300, 0), S4);
        return crew;
    }

    @Test
    void aCompanionLaggardWalksTheAuthoredRouteWhenTheAbsoluteMoveIsDue() {
        List<Character> crew = laggardOnStageThree();
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_4, crew);

        AgentLpqCoordinator.catchUpLaggingMembers(session, 4, S4, 17_001L);

        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
        verify(fx.actions).navigate(fx.entry(crew.get(2)), new Point(300, 0), true);
    }

    @Test
    void aStockLaggardIsStillMovedAbsolutely() {
        List<Character> crew = laggardOnStageThree();
        AgentLpqSession session = run(false, AgentLpqSession.Phase.STAGE_4, crew);

        AgentLpqCoordinator.catchUpLaggingMembers(session, 4, S4, 17_001L);

        verify(fx.mapGateway).changeMapNear(eq(crew.get(2)), eq(fx.map(S4)), any());
    }

    @Test
    void aCompanionLaggardsStalledWalkIsReplannedNotMoved() {
        List<Character> crew = laggardOnStageThree();
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_4, crew);

        AgentLpqCoordinator.catchUpLaggingMembers(session, 4, S4, 3_000L);
        AgentLpqCoordinator.catchUpLaggingMembers(session, 4, S4, 48_001L);

        verify(fx.actions).refreshNavigation(fx.entry(crew.get(2)), crew.get(2));
        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
    }

    private List<Character> gatherAfterStageThree() {
        List<Character> crew = fx.crew(S3, new Point(-400, 0));
        fx.portal(S3, 2, new Point(300, 0), S4);
        fx.property("3stageclear", "1");
        return crew;
    }

    @Test
    void theGatherWalksToThePortalWhenItsRecoveryIsDue() {
        List<Character> crew = gatherAfterStageThree();
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_3, crew);

        AgentLpqCoordinator.movePartyToNextStageTogether(session, 3, 3_000L);
        AgentLpqCoordinator.movePartyToNextStageTogether(session, 3, 18_001L);

        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions, atLeastOnce()).navigate(fx.entry(crew.get(1)), new Point(300, 0), true);
    }

    @Test
    void theStockGatherStillPlacesItsAgentsAtThePortal() {
        List<Character> crew = gatherAfterStageThree();
        AgentLpqSession session = run(false, AgentLpqSession.Phase.STAGE_3, crew);

        AgentLpqCoordinator.movePartyToNextStageTogether(session, 3, 3_000L);
        AgentLpqCoordinator.movePartyToNextStageTogether(session, 3, 18_001L);

        verify(fx.actions).stagePosition(fx.entry(crew.get(1)), crew.get(1), new Point(300, 0));
    }

    private Character scoutOnStageOne(List<Character> crew) {
        Character scout = crew.get(1);
        fx.portal(S1, 2, new Point(500, 0), S2);
        return scout;
    }

    @Test
    void aStalledStagePortalWalkIsReplanned() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        Character scout = scoutOnStageOne(crew);
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_2, crew);
        AgentLpqMemberState member = session.member(scout.getId());

        AgentLpqCoordinator.enterStagePortalWithRecovery(session, member, fx.entry(scout), scout, S2, 3_000L, "t");
        AgentLpqCoordinator.enterStagePortalWithRecovery(session, member, fx.entry(scout), scout, S2, 18_001L, "t");

        verify(fx.actions).refreshNavigation(fx.entry(scout), scout);
        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
    }

    @Test
    void aStockStalledStagePortalWalkIsStillMoved() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        Character scout = scoutOnStageOne(crew);
        AgentLpqSession session = run(false, AgentLpqSession.Phase.STAGE_2, crew);
        AgentLpqMemberState member = session.member(scout.getId());

        AgentLpqCoordinator.enterStagePortalWithRecovery(session, member, fx.entry(scout), scout, S2, 3_000L, "t");
        AgentLpqCoordinator.enterStagePortalWithRecovery(session, member, fx.entry(scout), scout, S2, 18_001L, "t");

        verify(fx.mapGateway).changeMapNear(eq(scout), eq(fx.map(S2)), any());
    }

    private Character inTheTrapRoom(List<Character> crew) {
        Character scout = crew.get(1);
        fx.onMap(scout, TRAP, new Point(0, 0));
        fx.portal(TRAP, 2, new Point(0, -500), S2);
        return scout;
    }

    @Test
    void aStalledTrapRoomExitIsReplannedAndWalked() {
        List<Character> crew = fx.crew(S2, new Point(0, 0));
        Character scout = inTheTrapRoom(crew);
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_2, crew);

        AgentLpqCoordinator.useStageTwoTrapExit(session, fx.entry(scout), scout, 3_000L);
        AgentLpqCoordinator.useStageTwoTrapExit(session, fx.entry(scout), scout, 33_001L);

        verify(fx.actions).refreshNavigation(fx.entry(scout), scout);
        verify(fx.actions, times(2)).navigate(eq(fx.entry(scout)), any(), eq(true));
        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
    }

    @Test
    void aStockStalledTrapRoomExitIsStillMoved() {
        List<Character> crew = fx.crew(S2, new Point(0, 0));
        Character scout = inTheTrapRoom(crew);
        AgentLpqSession session = run(false, AgentLpqSession.Phase.STAGE_2, crew);

        AgentLpqCoordinator.useStageTwoTrapExit(session, fx.entry(scout), scout, 3_000L);
        AgentLpqCoordinator.useStageTwoTrapExit(session, fx.entry(scout), scout, 33_001L);

        verify(fx.mapGateway).changeMapNear(eq(scout), eq(fx.map(S2)), any());
    }
}

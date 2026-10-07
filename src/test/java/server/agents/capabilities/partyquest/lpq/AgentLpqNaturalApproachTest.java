package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.4: where a companion run withholds an approach teleport (the maze's row portal, a stage-8 box, the reward
 * NPC, a committed box), the companion re-plans its route and keeps walking; and a companion with nothing to mark a
 * split-room door with walks in unmarked instead of retrying forever. Stock runs keep their teleports (pins).
 */
class AgentLpqNaturalApproachTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private static final int S5 = AgentLpqDefinition.stage(5).mapId();
    private static final int S6 = AgentLpqDefinition.stage(6).mapId();
    private static final int S8 = AgentLpqDefinition.stage(8).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession run(boolean companion, AgentLpqSession.Phase phase, List<Character> crew) {
        return fx.session(companion, phase, crew.toArray(Character[]::new));
    }

    private boolean rowPortalStalled(boolean companion) {
        List<Character> crew = fx.crew(S6, new Point(0, 0));
        Character kes = crew.get(2);
        fx.portal(S6, 7, new Point(400, -100), 0);
        Portal portal = fx.map(S6).getPortal(7);
        AgentLpqSession session = run(companion, AgentLpqSession.Phase.STAGE_6, crew);
        AgentLpqMemberState member = session.member(kes.getId());
        AgentLpqCoordinator.approachStageSixPortal(session, member, fx.entry(kes), kes, portal, 1, 3_000L);
        return AgentLpqCoordinator.approachStageSixPortal(session, member, fx.entry(kes), kes, portal, 1, 48_001L);
    }

    @Test
    void aStalledMazeRowIsReplannedAndItsPortalNeverEnteredFromAfar() {
        assertFalse(rowPortalStalled(true));
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
        verify(fx.actions, atLeastOnce()).refreshNavigation(any(), any());
    }

    @Test
    void aStockStalledMazeRowIsStillPlacedAtItsPortal() {
        assertTrue(rowPortalStalled(false));
        verify(fx.actions).stagePosition(any(), any(), eq(new Point(400, -100)));
    }

    private boolean stageEightBoxStalled(boolean companion) {
        List<Character> crew = fx.crew(S8, new Point(0, 0));
        Character kes = crew.get(2);
        AgentLpqSession session = run(companion, AgentLpqSession.Phase.STAGE_8, crew);
        AgentLpqMemberState member = session.member(kes.getId());
        Rectangle area = new Rectangle(500, -250, 60, 40);
        AgentLpqCoordinator.occupyStageEightPlatformWithRecovery(session, member, fx.entry(kes), kes, area, 3,
                3_000L);
        return AgentLpqCoordinator.occupyStageEightPlatformWithRecovery(session, member, fx.entry(kes), kes, area, 3,
                48_001L);
    }

    @Test
    void aStalledWalkToAStageEightBoxIsReplanned() {
        assertFalse(stageEightBoxStalled(true));
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions).refreshNavigation(any(), any());
    }

    @Test
    void aStockStalledWalkToAStageEightBoxIsStillPlaced() {
        stageEightBoxStalled(false);
        verify(fx.actions).stagePosition(any(), any(), eq(new Point(530, -250)));
    }

    private void rewardNpcStalled(boolean companion) {
        List<Character> crew = fx.crew(AgentLpqDefinition.CLEAR_MAP, new Point(0, 0));
        Character kes = crew.get(2);
        fx.npcAt(AgentLpqDefinition.CLEAR_NPC, new Point(-34, 474));
        AgentLpqSession session = run(companion, AgentLpqSession.Phase.BONUS, crew);
        AgentLpqMemberState member = session.member(kes.getId());
        for (long now : new long[]{3_000L, 18_001L}) {
            AgentLpqCoordinator.approachRewardNpc(session, member, fx.entry(kes), kes, AgentLpqDefinition.CLEAR_NPC,
                    "bonus exit balloon", now);
        }
    }

    @Test
    void aStalledWalkToTheRewardNpcIsReplanned() {
        rewardNpcStalled(true);
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions, atLeastOnce()).refreshNavigation(any(), any());
    }

    @Test
    void aStockStalledWalkToTheRewardNpcIsStillPlaced() {
        rewardNpcStalled(false);
        verify(fx.actions).stagePosition(any(), any(), eq(new Point(-34, 474)));
    }

    private AgentLpqMemberState committedBoxStalled(boolean companion) {
        List<Character> crew = fx.crew(S3, new Point(0, 0));
        Character kes = crew.get(2);
        Reactor box = mock(Reactor.class);
        when(box.isAlive()).thenReturn(true);
        when(box.isActive()).thenReturn(true);
        when(box.getObjectId()).thenReturn(55);
        when(box.getPosition()).thenReturn(new Point(700, -600));
        AgentLpqSession session = run(companion, AgentLpqSession.Phase.STAGE_3, crew);
        AgentLpqMemberState member = session.member(kes.getId());
        member.commitReactorTarget(S3, 55, 3_000L);
        AgentLpqCoordinator.hitReactorFromCandidates(fx.entry(kes), kes, 93_001L, member, false, Set.of(),
                List.of(box), true);
        return member;
    }

    @Test
    void aStalledCommittedBoxIsRecommittedAndWalkedTo() {
        AgentLpqMemberState member = committedBoxStalled(true);
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions).refreshNavigation(any(), any());
        assertEquals(0, member.reactorTargetObjectId(), "the box is let go, to be committed afresh");
    }

    @Test
    void aStockStalledCommittedBoxIsStillPlacedBeside() {
        committedBoxStalled(false);
        verify(fx.actions).stagePosition(any(), any(), eq(new Point(700, -600)));
    }

    @Test
    void aCompanionWithNothingToMarkTheDoorWithWalksInUnmarked() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        for (int door = 2; door <= 7; door++) {
            fx.portal(S5, door, new Point(door * 100, 0), 922_010_499 + door);
        }
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_5, crew);
        session.narrateOnce("s5-start");
        fx.tick(session, 3_000L);
        AgentLpqMemberState assigned = session.members().stream()
                .filter(member -> member.assignedMapId() >= 922_010_502 && member.assignedMapId() <= 922_010_505)
                .findFirst().orElseThrow();
        Character walker = crew.stream().filter(member -> member.getId() == assigned.characterId()).findFirst()
                .orElseThrow();
        int door = AgentLpqExitRoutePolicy.portalId(S5, assigned.assignedMapId());
        fx.moveTo(walker, new Point(door * 100, 0));
        when(fx.lpq.enterPortal(walker, door)).thenReturn(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 4_000L);

            verify(fx.lpq).enterPortal(walker, door);
            assertEquals(1, log.startingWith("LPQ room marker skipped: session=" + session.sessionId() + " member="
                    + walker.getName() + "(" + walker.getId() + ") room=" + assigned.assignedMapId()).size());
            assertEquals(List.of(), log.startingWith("LPQ room marker unavailable"));
        }
    }
}

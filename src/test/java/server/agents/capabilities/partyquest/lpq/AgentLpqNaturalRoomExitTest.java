package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.4: a companion run's split rooms are left and fought as a player does: the room exit is walked (pressed
 * up at the portal with the stock bookkeeping, re-planned once per traversal window when stalled), a stage-4 room
 * owner fights on past 90 s without the hard recovery, and an unwatched stage-5 box is walked to, never teleported to.
 */
class AgentLpqNaturalRoomExitTest {
    private static final int S4 = AgentLpqDefinition.stage(4).mapId();
    private static final int S5 = AgentLpqDefinition.stage(5).mapId();
    private static final int ROOM = 922_010_503;
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Character ownerInRoom(List<Character> crew, Point at) {
        Character owner = crew.get(3);
        fx.onMap(owner, ROOM, at);
        fx.portal(ROOM, 2, new Point(400, 0), S5);
        return owner;
    }

    private AgentLpqSession companionRun(List<Character> crew, Character owner) {
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        session.rooms().reserve(ROOM, owner.getId(), 2_000L);
        session.member(owner.getId()).assign(AgentLpqMemberState.Role.GENERAL, ROOM);
        return session;
    }

    @Test
    void farFromThePortalTheExitIsWalked() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character owner = ownerInRoom(crew, new Point(0, 0));
        AgentLpqSession session = companionRun(crew, owner);

        boolean out = AgentLpqCoordinator.forceRoomExitThroughPortal(session, session.member(owner.getId()),
                fx.entry(owner), owner, S5, 3_000L);

        assertFalse(out);
        verify(fx.actions).navigate(fx.entry(owner), new Point(400, 0), true);
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
        verify(fx.actions, never()).stagePosition(any(), any(), any());
    }

    @Test
    void atThePortalTheOwnerPressesUpAndTheRoomIsLetGo() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character owner = ownerInRoom(crew, new Point(380, 0));
        AgentLpqSession session = companionRun(crew, owner);
        when(fx.lpq.enterPortal(owner, 2)).thenReturn(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            boolean out = AgentLpqCoordinator.forceRoomExitThroughPortal(session, session.member(owner.getId()),
                    fx.entry(owner), owner, S5, 3_000L);

            assertTrue(out);
            verify(fx.lpq).enterPortal(owner, 2);
            verify(fx.actions, never()).stagePosition(any(), any(), any());
            assertNull(session.rooms().owner(ROOM));
            assertEquals(AgentLpqMemberState.Role.GENERAL, session.member(owner.getId()).role());
            assertEquals(0, session.member(owner.getId()).assignedMapId());
            assertEquals(List.of("LPQ room exit walked: session=" + session.sessionId() + " member=Pel(4) source="
                    + ROOM + " destination=" + S5), log.startingWith("LPQ room exit walked: "));
        }
    }

    @Test
    void aStalledExitWalkIsReplannedOncePerTraversalWindow() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character owner = ownerInRoom(crew, new Point(0, 0));
        AgentLpqSession session = companionRun(crew, owner);
        AgentLpqMemberState member = session.member(owner.getId());

        for (long now : new long[]{3_000L, 48_001L, 50_000L, 70_000L}) {
            AgentLpqCoordinator.forceRoomExitThroughPortal(session, member, fx.entry(owner), owner, S5, now);
        }
        verify(fx.actions, times(1)).refreshNavigation(fx.entry(owner), owner);
        AgentLpqCoordinator.forceRoomExitThroughPortal(session, member, fx.entry(owner), owner, S5, 95_001L);
        verify(fx.actions, times(2)).refreshNavigation(fx.entry(owner), owner);
        verify(fx.actions, never()).stagePosition(any(), any(), any());
    }

    @Test
    void aStockRoomExitIsStillPlacedAtThePortal() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character owner = ownerInRoom(crew, new Point(0, 0));
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));

        AgentLpqCoordinator.forceRoomExitThroughPortal(session, session.member(owner.getId()), fx.entry(owner),
                owner, S5, 3_000L);

        verify(fx.actions).stagePosition(fx.entry(owner), owner, new Point(400, 0));
    }

    private Character ownerInRoomFourOhFour(List<Character> crew) {
        Character owner = crew.get(4);
        fx.onMap(owner, 922_010_404, new Point(0, 0));
        fx.monster(922_010_404, AgentLpqDefinition.STAGE_4_PHYSICAL_MOB, new Point(600, 0));
        return owner;
    }

    @Test
    void aStageFourOwnerFightsOnPastNinetySeconds() {
        List<Character> crew = fx.crew(S4, new Point(0, 0));
        Character owner = ownerInRoomFourOhFour(crew);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_4, crew.toArray(Character[]::new));
        session.narrateOnce("s4-start");

        fx.tick(session, 3_000L);
        clearInvocations(fx.actions);
        fx.tick(session, 93_001L);

        verify(fx.map(922_010_404), never()).killMonsterWithDrops(anyInt());
        verify(fx.mapGateway, never()).removeItemDrop(any(), any(MapItem.class), anyInt(), anyInt());
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions).grind(eq(fx.entry(owner)), eq(Set.of(AgentLpqDefinition.STAGE_4_PHYSICAL_MOB)));
    }

    @Test
    void aStockStageFourRoomIsStillHardRecoveredAtNinetySeconds() {
        List<Character> crew = fx.crew(S4, new Point(0, 0));
        Character owner = ownerInRoomFourOhFour(crew);
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_4, crew.toArray(Character[]::new));

        fx.tick(session, 3_000L);
        fx.tick(session, 93_001L);

        verify(fx.map(922_010_404), atLeastOnce()).killMonsterWithDrops(AgentLpqDefinition.STAGE_4_PHYSICAL_MOB);
    }

    private Reactor boxInRoom(Character owner) {
        Reactor box = mock(Reactor.class);
        when(box.isAlive()).thenReturn(true);
        when(box.isActive()).thenReturn(true);
        when(box.getObjectId()).thenReturn(77);
        when(box.getPosition()).thenReturn(new Point(600, -900));
        when(fx.actions.reactors(owner)).thenReturn(List.of(box));
        when(fx.actions.observedByPlayer(owner)).thenReturn(false);
        return box;
    }

    @Test
    void anUnwatchedStageFiveBoxIsWalkedToNeverTeleportedTo() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character owner = ownerInRoom(crew, new Point(0, 0));
        Reactor box = boxInRoom(owner);
        AgentLpqSession session = companionRun(crew, owner);
        AgentLpqMemberState member = session.member(owner.getId());

        try (LpqLogLines coordinator = new LpqLogLines(AgentLpqCoordinator.class);
             LpqLogLines rescues = new LpqLogLines(AgentLpqRescue.class)) {
            for (long now = 3_000L; now <= 40_000L; now += 1_000L) {
                assertTrue(AgentLpqCoordinator.hitReactorFromCandidates(fx.entry(owner), owner, now, member, false,
                        Set.of(), List.of(box), true));
            }
            // The assist is not even attempted (its withheld teleport would log, and it would skip the walk).
            assertEquals(List.of(), coordinator.startingWith("LPQ Stage 5 unobserved ordinary-room box assist"));
            assertEquals(List.of(), rescues.startingWith("LPQ rescue: "));
        }

        verify(fx.actions, never()).stagePosition(any(), any(), any());
        verify(fx.actions, times(38)).navigate(eq(fx.entry(owner)), any(), eq(true));
    }

    @Test
    void aStockUnwatchedStageFiveBoxIsStillTeleportedTo() {
        List<Character> crew = fx.crew(S5, new Point(0, 0));
        Character owner = ownerInRoom(crew, new Point(0, 0));
        Reactor box = boxInRoom(owner);
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        AgentLpqMemberState member = session.member(owner.getId());

        for (long now = 3_000L; now <= 40_000L; now += 1_000L) {
            AgentLpqCoordinator.hitReactorFromCandidates(fx.entry(owner), owner, now, member, false, Set.of(),
                    List.of(box), true);
        }

        verify(fx.actions).stagePosition(eq(fx.entry(owner)), eq(owner), any());
    }
}

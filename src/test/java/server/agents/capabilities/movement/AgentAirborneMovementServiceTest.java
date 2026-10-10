package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationDebugStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAirborneMovementServiceTest {
    private AgentEpqSession registered;

    @AfterEach
    void clearSession() {
        if (registered != null) AgentEpqSessionRegistry.remove(registered);
    }

    @Test
    void appliesAirSteeringWhenNoFixedArcGraceOrNavigationEdgeExists() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertTrue(AgentAirborneMovementService.shouldApplyAirSteering(entry));
    }

    @Test
    void fixedAirArcSuppressesAirSteering() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementPhysicsStateRuntime.setFixedAirArc(entry, true);

        assertFalse(AgentAirborneMovementService.shouldApplyAirSteering(entry));
    }

    @Test
    void downJumpGraceSuppressesAirSteering() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementStateRuntime.setDownJumpGracePeriodMs(entry, 10L);

        assertFalse(AgentAirborneMovementService.shouldApplyAirSteering(entry));
    }

    @Test
    void epqAirborneStepWaitsForNewMapTrackingBeforeUsingPhysics() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(7);
        when(agent.getMapId()).thenReturn(AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMapStateRuntime.setMapTracking(
                entry, AgentEpqDefinition.STAGE_THREE_MAP, Map.of());

        registered = new AgentEpqSession(
                AgentEpqSession.Mode.TEST_OBSERVATION, 11L, 7, 10L);
        for (int id = 7; id <= 11; id++) {
            registered.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        registered.bindEventInstance(mock(EventInstanceManager.class));
        AgentEpqSessionRegistry.registerComplete(registered);

        assertTrue(AgentAirborneMovementService.awaitingEpqMapTransition(entry, agent));
        AgentMapStateRuntime.setMapTracking(
                entry, AgentEpqDefinition.STAGE_FOUR_MAP, Map.of());
        assertFalse(AgentAirborneMovementService.awaitingEpqMapTransition(entry, agent));
    }

    @Test
    void ropeGrabAfterTickCatchesWhereTheGraphDoesBeforeTheLooseTickEndCheck() {
        // The tick ends 1 px below the rope, inside the loose tick-end band that would clamp the
        // Agent onto the rope bottom; the graph catches the rope mid-tick at y 41 instead.
        Rope rope = new Rope(100, 0, 50, false);
        MapleMap map = mock(MapleMap.class);
        when(map.getRopes()).thenReturn(List.of(rope));
        Character agent = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(104, 51));
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getHp()).thenReturn(1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentClimbStateRuntime.setClimbUpIntent(entry, true);

        assertTrue(AgentAirborneMovementService.successfullyGrabbedRopeAfterTick(
                entry, agent, new Point(116, 20), new Point(104, 51)));

        assertEquals(new Point(100, 41), agent.getPosition());
        assertEquals(rope, AgentClimbStateRuntime.climbRope(entry));
    }

    @Test
    void committedRopeJumpCatchesOnlyItsTargetRope() {
        // The Road to Garden of 3 Colors (200010100): the jump from rope x148 to rope x69 passes
        // within reach of rope x68 (y -646..-361) while the top of rope x69 (y -305) is still
        // below. The graph's rope-grab simulation tests only rope x69.
        GardenRopes ropes = gardenRopes();
        AgentNavigationDebugStateRuntime.setActiveNavigationEdge(ropes.entry(), ropes.transfer());

        assertFalse(AgentAirborneMovementService.successfullyGrabbedRopeAfterTick(
                ropes.entry(), ropes.agent(), new Point(85, -437), new Point(68, -389)));
        assertTrue(AgentAirborneMovementService.successfullyGrabbedRopeAfterTick(
                ropes.entry(), ropes.agent(), new Point(68, -358), new Point(68, -271)));

        assertEquals(69, AgentClimbStateRuntime.climbRope(ropes.entry()).x());
    }

    @Test
    void withoutACommittedRopeEdgeTheFirstRopeWithinReachIsCaught() {
        GardenRopes ropes = gardenRopes();

        assertTrue(AgentAirborneMovementService.successfullyGrabbedRopeAfterTick(
                ropes.entry(), ropes.agent(), new Point(85, -437), new Point(68, -389)));

        assertEquals(68, AgentClimbStateRuntime.climbRope(ropes.entry()).x());
    }

    private record GardenRopes(AgentRuntimeEntry entry, Character agent, AgentNavigationGraph.Edge transfer) {
    }

    private static GardenRopes gardenRopes() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(200010100);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        Rope source = gardenRope(map, 148);
        int sourceRegion = graph.findRopeRegionId(new Point(148, -500));
        int targetRegion = graph.findRopeRegionId(new Point(69, -200));
        AgentNavigationGraph.Edge transfer = graph.getOutgoing(sourceRegion).stream()
                .filter(edge -> edge.toRegionId == targetRegion && edge.startPoint.y == -500)
                .findFirst()
                .orElseThrow();
        Character agent = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(85, -437));
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getHp()).thenReturn(1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentClimbStateRuntime.setClimbUpIntent(entry, true);
        AgentClimbStateRuntime.setBlockedRopeGrab(entry, source);
        return new GardenRopes(entry, agent, transfer);
    }

    private static Rope gardenRope(MapleMap map, int x) {
        return map.getRopes().stream().filter(rope -> rope.x() == x).findFirst().orElseThrow();
    }
}

package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentFootholdIndexService;
import server.agents.capabilities.movement.AgentMapStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentRopeMovementService;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Launched edges flown by the live background move-target loop on real Map.wz geometry. An airborne
 * Agent has no current region, and edge validation compared it with the edge's source first: every
 * committed jump, drop and rope jump-off was rejected at the first AI tick of its flight, charged a
 * failure and replaced by a hold at the mid-air point, which air steering then pulled the Agent back to.
 */
class AgentNavigationLaunchedEdgeFlightTest {
    // The Path of Peril (610020004): both flights were pulled back and fell past their ledge.
    private static final int PATH_OF_PERIL = 610020004;
    // Henesys Hunting Ground I (104040000): the drop landed anyway, but was rejected in the air.
    private static final int HENESYS_HUNTING_GROUND = 104040000;
    private static final int MAX_TICKS = 60;
    private static final int STAY_TICKS = 10;

    @Test
    void ropeJumpOffFliesItsArcAndStaysOnTheLedge() {
        assertFlownInBothPhases(PATH_OF_PERIL, AgentNavigationGraph.EdgeType.CLIMB,
                new Point(849, -1_350), new Point(755, -1_112));
    }

    @Test
    void groundJumpKeepsItsEdgeUntilItLands() {
        assertFlownInBothPhases(PATH_OF_PERIL, AgentNavigationGraph.EdgeType.JUMP,
                new Point(876, -1_892), new Point(748, -1_532));
    }

    @Test
    void walkOffDropKeepsItsEdgeUntilItLands() {
        assertFlownInBothPhases(HENESYS_HUNTING_GROUND, AgentNavigationGraph.EdgeType.DROP,
                new Point(901, -1_105), new Point(727, -565));
    }

    @Test
    void ropeCrossingEndsOnTheLedgeItsJumpOffLandsOn() {
        // The route to the ledge below climbs the rope at x -368 and jumps off it 73 px out. Kept
        // through the flight, the crossing now completes on the ledge, and its post-exit nudge to
        // the rope's side (x -400) walked the Agent back toward the rope and off the ledge.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(PATH_OF_PERIL);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        AgentNavigationGraph.Edge toLedge = edge(graph, AgentNavigationGraph.EdgeType.JUMP,
                new Point(-362, -2_672), new Point(-450, -2_552));
        for (int phase = 0; phase < 2; phase++) {
            Flight flight = fly(map, graph, toLedge, phase);

            assertTrue(flight.reachedAt() >= 0, "phase " + phase + " ended at " + flight.position());
            assertEquals(toLedge.toRegionId, graph.findRegionId(map, flight.position()),
                    "phase " + phase + " left the ledge for " + flight.position());
        }
    }

    private static void assertFlownInBothPhases(int mapId, AgentNavigationGraph.EdgeType type,
                                                Point start, Point end) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        AgentNavigationGraph.Edge edge = edge(graph, type, start, end);
        for (int phase = 0; phase < 2; phase++) {
            Flight flight = fly(map, graph, edge, phase);
            String context = mapId + " " + type + " phase " + phase;

            assertTrue(flight.reachedAt() >= 0, context + " ended at " + flight.position());
            assertEquals(edge.toRegionId, graph.findRegionId(map, flight.position()),
                    context + " left the landing region for " + flight.position());
            assertEquals(0, flight.airborneAiTicksOffEdge(), context + ": AI ticks in flight without the edge");
            assertEquals(0, flight.failures(), context + ": reliability failures charged to the edge");
        }
    }

    /**
     * Runs live background movement (five substeps, an AI tick every second tick) from the edge's start
     * toward its end, until the Agent has stood in the edge's destination region for STAY_TICKS ticks.
     */
    private static Flight fly(MapleMap map, AgentNavigationGraph graph, AgentNavigationGraph.Edge edge, int phase) {
        AtomicReference<Point> position = new AtomicReference<>(new Point(edge.startPoint));
        Character agent = agentAt(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        AgentMapStateRuntime.setMapTracking(entry, map, AgentFootholdIndexService.buildFhIndex(map));
        AgentNavigationGraph.Region from = graph.getRegion(edge.fromRegionId);
        if (from.isRopeRegion) {
            AgentRopeMovementService.attachToRope(entry, agent,
                    AgentNavigationGraphService.findRopeFromRegion(map, from), edge.startPoint.y);
        }
        AgentModeService.startMoveTo(entry, new Point(edge.endPoint), true);

        int reachedAt = -1;
        int airborneAiTicksOffEdge = 0;
        for (int tick = 0; reachedAt < 0 ? tick < MAX_TICKS : tick <= reachedAt + STAY_TICKS; tick++) {
            boolean aiTick = (tick + phase) % 2 == 0;
            boolean launched = AgentMovementStateRuntime.inAir(entry);
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(entry, agent, aiTick);
            Object active = AgentNavigationDebugStateRuntime.activeNavigationEdge(entry);
            if (aiTick && launched && AgentMovementStateRuntime.inAir(entry)
                    && !(active instanceof AgentNavigationGraph.Edge committed
                    && AgentNavigationCommittedEdgeService.sameEdge(committed, edge))) {
                airborneAiTicksOffEdge++;
            }
            if (reachedAt < 0 && !AgentMovementStateRuntime.inAir(entry) && !AgentClimbStateRuntime.climbing(entry)
                    && graph.findRegionId(map, position.get()) == edge.toRegionId) {
                reachedAt = tick;
            }
        }
        int failures = 0;
        for (AgentNavigationEdgeReliabilityState.EdgeFailure failure
                : AgentNavigationEdgeReliabilityRuntime.snapshot(entry, System.currentTimeMillis()).edges()) {
            if (failure.type() == edge.type && failure.toRegionId() == edge.toRegionId
                    && failure.startX() == edge.startPoint.x && failure.startY() == edge.startPoint.y) {
                failures += failure.failureCount();
            }
        }
        return new Flight(reachedAt, airborneAiTicksOffEdge, failures, position.get());
    }

    private record Flight(int reachedAt, int airborneAiTicksOffEdge, int failures, Point position) {
    }

    private static AgentNavigationGraph.Edge edge(AgentNavigationGraph graph, AgentNavigationGraph.EdgeType type,
                                                  Point start, Point end) {
        AgentNavigationGraph.Edge found = null;
        for (AgentNavigationGraph.Region region : graph.regions) {
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == type && edge.startPoint.equals(start) && edge.endPoint.equals(end)) {
                    found = edge;
                }
            }
        }
        assertNotNull(found, "no " + type + " edge " + start + " -> " + end);
        return found;
    }

    private static Character agentAt(MapleMap map, AtomicReference<Point> position) {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getId()).thenReturn(1);
        when(agent.getHp()).thenReturn(100);
        when(agent.getMaxHp()).thenReturn(100);
        when(agent.isAlive()).thenReturn(true);
        when(agent.getTotalMoveSpeedStat()).thenReturn(100);
        when(agent.getTotalJumpStat()).thenReturn(100);
        return agent;
    }
}

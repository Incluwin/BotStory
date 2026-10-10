package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentClimbMovementService;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementPhaseService;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentMovementSubstepPolicy;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentNavigationLadderTransferTest {
    // Sleepy Dungeon IV (105040302). From the platform y=435 the only way up is the authored
    // 2-px ladder 6 (x -414, y 372..374) and a rope jump onto ladder 5 (x -415, y 77..358).
    private static final int SLEEPY_DUNGEON_302 = 105040302;
    private static final int LADDER_6_X = -414;
    private static final int LADDER_5_X = -415;

    @Test
    void backgroundSubstepsAfterTheTransferStayOnTheCaughtLadder() {
        // A background tick runs five movement substeps after one navigation resolve. The
        // transfer jump catches ladder 5 in the first substep; the vertical transaction still
        // pointed at its exit height on ladder 6 (y 374, below ladder 5's bottom 358), so the
        // other substeps climbed down off ladder 5 and dropped the Agent back to y=435.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(SLEEPY_DUNGEON_302);
        AgentMovementProfile profile = AgentMovementProfile.base();
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, profile);
        Rope ladder6 = rope(map, LADDER_6_X);
        int platform = graph.findRegionId(map, new Point(-415, 435));
        int ladder6Region = graph.findRopeRegionId(new Point(LADDER_6_X, 374));
        int ladder5Region = graph.findRopeRegionId(new Point(LADDER_5_X, 300));
        AgentNavigationGraph.Edge entryEdge = edge(graph, platform, ladder6Region);
        AgentNavigationGraph.Edge transfer = edge(graph, ladder6Region, ladder5Region);

        AtomicReference<Point> position = new AtomicReference<>(new Point(transfer.startPoint));
        Character agent = agentAt(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, profile);
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        AgentClimbStateRuntime.setClimbingOnRope(entry, ladder6);
        entry.verticalTraversalState().begin(graph, entryEdge, transfer, new Point(-560, -125), -1);
        entry.verticalTraversalState().observeRopeAttachment();
        AgentNavigationDebugStateRuntime.setActiveNavigationEdge(entry, transfer);

        AgentClimbMovementService.jumpToRope(entry, agent, transfer.launchStepX);
        int substeps = AgentMovementSubstepPolicy.substeps(entry);
        assertTrue(substeps > 1, "background substeps " + substeps);
        for (int step = 0; step < substeps; step++) {
            AgentMovementPhaseService.tickMovementPhase(entry, new Point(transfer.endPoint), false);
        }

        assertTrue(AgentClimbStateRuntime.climbing(entry), "fell to " + position.get());
        assertFalse(AgentMovementStateRuntime.inAir(entry));
        assertEquals(LADDER_5_X, AgentClimbStateRuntime.climbRope(entry).x());
        assertEquals(transfer.endPoint, position.get());
    }

    @Test
    void backgroundAgentClimbsFromThePlatformToTheOut00Ladder() {
        // Live cadence: one AI tick per 100 ms, background ticks of 250 ms, so every second
        // background tick is an AI tick. Both phases of that cadence must reach out00.
        for (int phase = 0; phase < 2; phase++) {
            MapleMap map = AgentNavigationMapLoader.loadMapGeometry(SLEEPY_DUNGEON_302);
            Portal out00 = map.getPortal("out00");
            Point target = AgentPortalApproachService.navigableTarget(map, out00);
            AtomicReference<Point> position = new AtomicReference<>(new Point(-415, 435));
            Character agent = agentAt(map, position);
            AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
            AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
            AgentModeService.startMoveTo(entry, target, true);
            entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);

            int reachedAt = -1;
            for (int tick = 0; tick < 80 && reachedAt < 0; tick++) {
                AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(
                        entry, agent, (tick + phase) % 2 == 0);
                if (position.get().distanceSq(out00.getPosition()) <= 45 * 45) {
                    reachedAt = tick;
                }
            }

            assertTrue(reachedAt >= 0, "phase " + phase + " stalled at " + position.get());
        }
    }

    private static AgentNavigationGraph.Edge edge(AgentNavigationGraph graph, int from, int to) {
        AgentNavigationGraph.Edge found = null;
        for (AgentNavigationGraph.Edge edge : graph.getOutgoing(from)) {
            if (edge.toRegionId == to && edge.type == AgentNavigationGraph.EdgeType.CLIMB
                    && (found == null || edge.startPoint.y > found.startPoint.y)) {
                found = edge;
            }
        }
        assertNotNull(found, "no CLIMB edge r" + from + " -> r" + to);
        return found;
    }

    private static Rope rope(MapleMap map, int x) {
        for (Rope rope : map.getRopes()) {
            if (rope.x() == x) {
                return rope;
            }
        }
        throw new AssertionError("no rope at x=" + x);
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

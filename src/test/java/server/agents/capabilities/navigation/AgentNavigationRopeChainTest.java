package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentAirborneMovementService;
import server.agents.capabilities.movement.AgentClimbMovementService;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentNavigationRopeChainTest {
    // Dungeon: Drake Hunting Ground. Its west exit east00 is reached only over a chain of hanging
    // ropes 78-89 px apart (x 154, 232, 321, 407, 490).
    private static final int DRAKE_HUNTING_GROUND = 105080000;
    private static final List<Integer> WEST_CHAIN_ROPE_XS = List.of(154, 232, 321, 407, 490);

    @Test
    void baseProfileCrossesTheDrakeHuntingGroundRopeChainWestbound() {
        // At speed 100 a rope jump flies 6.25 px per 50 ms tick. Flown at a rounded 6 px and
        // grabbed only at tick ends, the jumps 407->321 and 321->232 fell past the next rope, so
        // every base-speed Agent arriving from Sleepywood's deep dungeon stalled on this map.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_HUNTING_GROUND);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        int eastArrival = graph.findRegionId(map, map.getPortal("east01").getPosition());
        int westExit = graph.findRegionId(map, map.getPortal("east00").getPosition());

        assertTrue(graph.canReach(eastArrival, westExit), "east01 r" + eastArrival + " -> east00 r" + westExit);
        assertTrue(graph.canReach(westExit, eastArrival), "east00 r" + westExit + " -> east01 r" + eastArrival);
        assertTrue(hasRopeTransfer(graph, 407, 321), "no rope transfer 407 -> 321");
        assertTrue(hasRopeTransfer(graph, 321, 232), "no rope transfer 321 -> 232");
    }

    @Test
    void liveRopeJumpGrabsTheGraphEndpointOfEveryWestChainTransfer() {
        // The graph and the live airborne executor must fly the same arc: a transfer the graph
        // keeps but the Agent misses drops it onto the cave floor and the route replans forever.
        for (AgentMovementProfile profile : List.of(AgentMovementProfile.base(), new AgentMovementProfile(105, 100))) {
            MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_HUNTING_GROUND);
            AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, profile);
            List<AgentNavigationGraph.Edge> transfers = westChainTransfers(graph);
            assertTrue(transfers.size() > 20, profile + " transfers " + transfers.size());

            for (AgentNavigationGraph.Edge transfer : transfers) {
                Rope source = AgentNavigationGraphService.findRopeFromRegion(map, graph.getRegion(transfer.fromRegionId));
                Rope target = AgentNavigationGraphService.findRopeFromRegion(map, graph.getRegion(transfer.toRegionId));
                String label = profile + " " + source.x() + "@" + transfer.startPoint.y + " -> " + target.x();

                Point grab = flyLive(map, profile, source, transfer);

                assertNotNull(grab, label + " grabbed nothing");
                assertEquals(transfer.endPoint, grab, label);
            }
        }
    }

    private static Point flyLive(MapleMap map,
                                 AgentMovementProfile profile,
                                 Rope source,
                                 AgentNavigationGraph.Edge transfer) {
        Character agent = agentAt(map, transfer.startPoint);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, profile);
        AgentClimbStateRuntime.setClimbingOnRope(entry, source);
        AgentNavigationDebugStateRuntime.setActiveNavigationEdge(entry, transfer);

        AgentClimbMovementService.jumpToRope(entry, agent, transfer.launchStepX);
        for (int tick = 0; tick < 100 && AgentMovementStateRuntime.inAir(entry); tick++) {
            AgentAirborneMovementService.tickAirborne(entry, null);
        }
        Rope grabbed = AgentClimbStateRuntime.climbRope(entry);
        if (grabbed == null || grabbed.x() == source.x()) {
            return null;
        }
        assertFalse(AgentMovementStateRuntime.inAir(entry));
        return agent.getPosition();
    }

    private static List<AgentNavigationGraph.Edge> westChainTransfers(AgentNavigationGraph graph) {
        List<AgentNavigationGraph.Edge> transfers = new ArrayList<>();
        for (AgentNavigationGraph.Region region : graph.regions) {
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                AgentNavigationGraph.Region to = graph.getRegion(edge.toRegionId);
                if (edge.type == AgentNavigationGraph.EdgeType.CLIMB
                        && region.isRopeRegion && to.isRopeRegion
                        && WEST_CHAIN_ROPE_XS.contains(region.minX)
                        && WEST_CHAIN_ROPE_XS.contains(to.minX)) {
                    transfers.add(edge);
                }
            }
        }
        return transfers;
    }

    private static boolean hasRopeTransfer(AgentNavigationGraph graph, int fromRopeX, int toRopeX) {
        return westChainTransfers(graph).stream().anyMatch(edge ->
                graph.getRegion(edge.fromRegionId).minX == fromRopeX
                        && graph.getRegion(edge.toRegionId).minX == toRopeX);
    }

    private static Character agentAt(MapleMap map, Point start) {
        Character agent = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getId()).thenReturn(1);
        when(agent.getHp()).thenReturn(100);
        return agent;
    }
}

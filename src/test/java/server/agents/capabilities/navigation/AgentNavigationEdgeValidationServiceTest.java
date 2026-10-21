package server.agents.capabilities.navigation;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.Rope;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentNavigationEdgeValidationServiceTest {
    @Test
    void rejectsUnexpectedSourceRegion() {
        AgentNavigationGraph graph = graph();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        AgentNavigationEdgeValidationService.Result result =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, 2, new Point(0, 100), jump(), 1_000);

        assertTrue(result.rejected());
        assertEquals("unexpected-source-region", result.reason());
    }

    @Test
    void rejectsGroundEdgeWhileClimbing() {
        AgentNavigationGraph graph = graph();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentClimbStateRuntime.setClimbingOnRope(entry, mock(Rope.class));

        AgentNavigationEdgeValidationService.Result result =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, 1, new Point(0, 100), jump(), 1_000);

        assertTrue(result.rejected());
        assertEquals("ground-edge-while-climbing", result.reason());
    }

    @Test
    void rejectsClimbExitWhenAgentIsNotClimbing() {
        AgentNavigationGraph graph = graphWithRope();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentNavigationGraph.Edge exit = new AgentNavigationGraph.Edge(
                3, 2, AgentNavigationGraph.EdgeType.CLIMB,
                new Point(50, 50), new Point(100, 100),
                1, -1, 50, 50, 150, 100);

        AgentNavigationEdgeValidationService.Result result =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, 3, new Point(50, 50), exit, 1_000);

        assertTrue(result.rejected());
        assertEquals("climb-exit-not-climbing", result.reason());
    }

    @Test
    void launchedJumpAndDropsStayInProgressThroughTheirFlight() {
        // An airborne Agent has no current region (-1). The source-region check ran first and
        // rejected every committed flight at its first AI tick, charging the edge a failure.
        AgentNavigationGraph graph = graphWithLowerFloor();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentNavigationGraph.Edge walkOff = new AgentNavigationGraph.Edge(
                1, 4, AgentNavigationGraph.EdgeType.DROP,
                new Point(40, 100), new Point(45, 200),
                40, 45, 4, -1, 0, 0, 0, 100);
        AgentNavigationGraph.Edge downJump = new AgentNavigationGraph.Edge(
                1, 4, AgentNavigationGraph.EdgeType.DROP,
                new Point(0, 100), new Point(0, 200),
                0, 0, 0, -1, 0, 0, 0, 100);

        for (AgentNavigationGraph.Edge edge : List.of(jump(), walkOff, downJump)) {
            AgentNavigationEdgeValidationService.Result result =
                    AgentNavigationEdgeValidationService.validate(
                            graph, entry, 100, -1, new Point(30, 60), edge, 1_000);

            assertEquals(AgentNavigationEdgeValidationService.Status.IN_PROGRESS, result.status(),
                    edge.type + " step " + edge.launchStepX);
            assertEquals("airborne-traversal", result.reason());
        }
    }

    @Test
    void ropeJumpOffStaysInProgressAndAirborneRopeEntryStillApproachesTheRope() {
        AgentNavigationGraph graph = graphWithRope();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentNavigationGraph.Edge jumpOff = new AgentNavigationGraph.Edge(
                3, 2, AgentNavigationGraph.EdgeType.CLIMB,
                new Point(50, 50), new Point(100, 100),
                1, -1, 50, 50, 150, 100);
        AgentNavigationGraph.Edge jumpGrab = new AgentNavigationGraph.Edge(
                1, 3, AgentNavigationGraph.EdgeType.CLIMB,
                new Point(30, 100), new Point(50, 80),
                1, -1, 50, 50, 150, 100);

        AgentNavigationEdgeValidationService.Result exit =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, -1, new Point(70, 40), jumpOff, 1_000);
        AgentNavigationEdgeValidationService.Result entryResult =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, -1, new Point(40, 70), jumpGrab, 1_000);

        assertEquals(AgentNavigationEdgeValidationService.Status.IN_PROGRESS, exit.status());
        assertEquals("climb-exit-airborne", exit.reason());
        assertEquals(AgentNavigationEdgeValidationService.Status.APPROACH, entryResult.status());
        assertEquals("rope-entry-airborne", entryResult.reason());
    }

    @Test
    void airborneAgentResolvedToAnotherRegionIsStillRejected() {
        AgentNavigationGraph graph = graph();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);

        AgentNavigationEdgeValidationService.Result result =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, 2, new Point(120, 60), jump(), 1_000);

        assertTrue(result.rejected());
        assertEquals("unexpected-source-region", result.reason());
    }

    @Test
    void validFixedJumpRemainsExecutableAndOffGraphLandingIsRejected() {
        AgentNavigationGraph graph = graph();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentNavigationEdgeValidationService.Result valid =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, 1, new Point(0, 100), jump(), 1_000);
        AgentNavigationGraph.Edge invalidLanding = new AgentNavigationGraph.Edge(
                1, 2, AgentNavigationGraph.EdgeType.JUMP,
                new Point(0, 100), new Point(100, 400),
                -10, 10, 0, -1, 0, 0, 0, 100);
        AgentNavigationEdgeValidationService.Result invalid =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, 100, 1, new Point(0, 100), invalidLanding, 1_000);

        assertFalse(valid.rejected());
        assertTrue(valid.ready());
        assertTrue(invalid.rejected());
        assertEquals("unreachable-edge-anchor", invalid.reason());
    }

    private static AgentNavigationGraph graph() {
        return graph(List.of(ground(1, -50, 50, 100), ground(2, 50, 150, 100)));
    }

    private static AgentNavigationGraph graphWithLowerFloor() {
        return graph(List.of(ground(1, -50, 50, 100), ground(2, 50, 150, 100), ground(4, -50, 50, 200)));
    }

    private static AgentNavigationGraph graphWithRope() {
        return graph(List.of(ground(1, -50, 50, 100),
                ground(2, 50, 150, 100), new AgentNavigationGraph.Region(3, 50, 50, 150, false)));
    }

    private static AgentNavigationGraph graph(List<AgentNavigationGraph.Region> regions) {
        Map<Integer, AgentNavigationGraph.Region> byId = new java.util.HashMap<>();
        for (AgentNavigationGraph.Region region : regions) {
            byId.put(region.id, region);
        }
        return new AgentNavigationGraph(100, 1, regions, byId, Map.of(), Map.of(), Set.of());
    }

    private static AgentNavigationGraph.Region ground(int id, int x1, int x2, int y) {
        return new AgentNavigationGraph.Region(id, List.of(new AgentNavigationGraph.Segment(
                new Foothold(new Point(x1, y), new Point(x2, y), id))));
    }

    private static AgentNavigationGraph.Edge jump() {
        return new AgentNavigationGraph.Edge(1, 2, AgentNavigationGraph.EdgeType.JUMP,
                new Point(0, 100), new Point(100, 100),
                -10, 10, 0, -1, 0, 0, 0, 100);
    }
}

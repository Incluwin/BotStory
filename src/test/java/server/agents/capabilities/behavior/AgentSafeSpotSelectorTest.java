package server.agents.capabilities.behavior;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.maps.Foothold;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Respite spots are walked to from the Agent's current region, so a drop-only roof that shares
 * the street's connected component must not be offered to an Agent standing on the street.
 */
class AgentSafeSpotSelectorTest {
    private static final int STREET = 1;
    private static final int ROOF = 2;
    private static final int LEDGE = 3;
    private static final Point THREAT = new Point(400, 300);

    @Test
    void streetAgentIsNotSentToADropOnlyRoof() {
        AgentNavigationGraph graph = graph();
        assertEquals(graph.connectedComponentId(STREET), graph.connectedComponentId(ROOF));
        assertFalse(graph.canReach(STREET, ROOF));

        Point fromStreet = AgentSafeSpotSelector.select(
                graph, STREET, List.of(THREAT), List.of(), new Point(500, 300));

        assertEquals(graph.getRegion(LEDGE).centerPoint(), fromStreet,
                "the farthest spot the Agent can walk to wins; the roof is farther but unclimbable");
    }

    @Test
    void roofAgentAndUnresolvedRegionKeepTheRoof() {
        AgentNavigationGraph graph = graph();
        Point roofCenter = graph.getRegion(ROOF).centerPoint();

        assertEquals(roofCenter, AgentSafeSpotSelector.select(
                graph, ROOF, List.of(THREAT), List.of(), new Point(800, 100)),
                "an Agent already on the roof may rest there");
        assertEquals(roofCenter, AgentSafeSpotSelector.select(
                graph, -1, List.of(THREAT), List.of(), new Point(500, 300)),
                "an unresolved current region leaves every region eligible, as before");
    }

    /** Street 1 (0..1000, y 300); drop-only roof 2 (700..900, y 100); climbable ledge 3 (100..300, y 200). */
    private static AgentNavigationGraph graph() {
        List<AgentNavigationGraph.Region> regions = List.of(
                ground(STREET, 0, 1000, 300), ground(ROOF, 700, 900, 100), ground(LEDGE, 100, 300, 200));
        Map<Integer, AgentNavigationGraph.Region> regionsById = new HashMap<>();
        Map<Integer, Integer> regionIdByFootholdId = new HashMap<>();
        for (AgentNavigationGraph.Region region : regions) {
            regionsById.put(region.id, region);
            regionIdByFootholdId.put(region.id, region.id);
        }
        Map<Integer, List<AgentNavigationGraph.Edge>> outgoing = Map.of(
                STREET, List.of(edge(STREET, LEDGE, AgentNavigationGraph.EdgeType.JUMP,
                        new Point(200, 300), new Point(200, 200))),
                ROOF, List.of(edge(ROOF, STREET, AgentNavigationGraph.EdgeType.DROP,
                        new Point(800, 100), new Point(800, 300))),
                LEDGE, List.of(edge(LEDGE, STREET, AgentNavigationGraph.EdgeType.DROP,
                        new Point(200, 200), new Point(200, 300))));
        return new AgentNavigationGraph(1, 1, AgentMovementProfile.base(), regions, regionsById,
                regionIdByFootholdId, outgoing, Set.of());
    }

    private static AgentNavigationGraph.Region ground(int id, int x1, int x2, int y) {
        return new AgentNavigationGraph.Region(id, List.of(new AgentNavigationGraph.Segment(
                new Foothold(new Point(x1, y), new Point(x2, y), id))));
    }

    private static AgentNavigationGraph.Edge edge(int from,
                                                  int to,
                                                  AgentNavigationGraph.EdgeType type,
                                                  Point start,
                                                  Point end) {
        return new AgentNavigationGraph.Edge(from, to, type, start, end, 0, 0, 0, 0, 0, 10);
    }
}

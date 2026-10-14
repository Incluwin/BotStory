package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-14: Horntail's focus ledges on the real altar (240060200), on the offline navigation graph a base-speed Agent
 * uses. Every ledge the shipped focus steps name lies on its foothold and has a searched path up from the floor where
 * members fight the Legs and Tail from the left (x -217 and further left) and back down to it.
 */
class AgentBossHorntailAnchorReachTest {
    private static final int ALTAR = 240_060_200;
    /** Floor spots left of the Legs' touch area, where members stand while the Legs and Tail live. */
    private static final List<Point> FLOOR = List.of(new Point(-217, 260), new Point(-400, 260), new Point(-560, 260));

    private static MapleMap map;
    private static AgentNavigationGraph graph;

    @BeforeAll
    static void loadTheAltar() {
        map = AgentNavigationMapLoader.loadMapGeometry(ALTAR);
        graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
    }

    @Test
    void everyFocusLedgeLiesOnAFootholdWithAPathUpFromTheFloorAndBackDown() {
        List<Point> ledges = new ArrayList<>();
        for (AgentBossRules.Focus step : AgentBossRules.of("horntail").focus()) {
            for (AgentBossRules.Anchor anchor : step.anchors()) {
                ledges.add(new Point(anchor.x(), anchor.y()));
            }
        }
        assertEquals(5, ledges.size(), "two low ledges for the wings and hands, three high ones for the heads");
        for (Point ledge : ledges) {
            assertEquals(ledge, ground(ledge), "ledge on its foothold: " + ledge);
            int ledgeRegion = region(ledge);
            assertTrue(ledgeRegion >= 0, "ledge on a region: " + ledge);
            for (Point spot : FLOOR) {
                assertEquals(spot, ground(spot), "floor spot on the floor: " + spot);
                int spotRegion = region(spot);
                assertFalse(path(spot, spotRegion, ledge, ledgeRegion).isEmpty(), "path up " + spot + " -> " + ledge);
                assertFalse(path(ledge, ledgeRegion, spot, spotRegion).isEmpty(), "path down " + ledge + " -> " + spot);
            }
        }
    }

    private static List<AgentNavigationGraph.Edge> path(Point from, int fromRegion, Point to, int toRegion) {
        List<AgentNavigationGraph.Edge> path = AgentNavigationPathService.findPath(
                graph, map, from, fromRegion, toRegion, to);
        return path == null ? List.of() : path;
    }

    private static Point ground(Point point) {
        return CosmicPrimitiveCapabilityGateway.INSTANCE.groundPoint(map, point);
    }

    /** The region under a point, looked up from just above it, as the gateway's canReach does. */
    private static int region(Point point) {
        return graph.findRegionId(map, new Point(point.x, point.y - 1));
    }
}

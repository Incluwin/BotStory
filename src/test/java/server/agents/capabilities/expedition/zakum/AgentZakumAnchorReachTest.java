package server.agents.capabilities.expedition.zakum;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zakum fight's places on the real altar (280030000), on the offline navigation graph a base-speed Agent uses.
 * The plan named four candidate platforms for melee companions to reach the high arms from; this test sets the
 * shipped PLATFORM_ANCHORS: exactly the candidates that lie on a foothold and have a searched path up from every
 * pre-summon spot on the floor and back down to it. On this map all four do (left: a rope and a jump; right: two
 * jumps and the rope, or three jumps), so the default is all four. The pre-summon line and the battle walls come from
 * the same map: the walls are its foothold bounds, and every spot of the line stands on the floor by the altar.
 */
class AgentZakumAnchorReachTest {
    /** The plan's four platforms: left upper, left top, right top, right upper. */
    private static final List<Point> CANDIDATES = List.of(new Point(-345, -488), new Point(-373, -556),
            new Point(446, -530), new Point(473, -391));

    private static MapleMap map;
    private static AgentNavigationGraph graph;
    private static Point altar;

    @BeforeAll
    static void loadTheAltar() {
        map = AgentNavigationMapLoader.loadMapGeometry(AgentZakumDefinition.BATTLE_MAP_ID);
        graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        Data mapData = DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map2/280030000.img");
        for (Data reactor : mapData.getChildByPath("reactor").getChildren()) {
            // the id is a WZ string, as MapFactory reads it
            if (Integer.toString(AgentZakumDefinition.ALTAR_REACTOR_ID)
                    .equals(DataTool.getString(reactor.getChildByPath("id")))) {
                altar = new Point(DataTool.getInt("x", reactor), DataTool.getInt("y", reactor));
            }
        }
    }

    @Test
    void theShippedAnchorsAreExactlyTheReachableCandidates() {
        List<Point> reachable = CANDIDATES.stream()
                .filter(AgentZakumAnchorReachTest::reachableBothWays)
                .toList();

        assertEquals(reachable, AgentZakumScenario.platformAnchors());
        assertEquals(CANDIDATES, reachable, "every candidate platform is reachable");
    }

    @Test
    void everyShippedAnchorLiesOnAFootholdWithAPathUpFromEverySpotAndBackDown() {
        assertFalse(AgentZakumScenario.platformAnchors().isEmpty(), "the shipped value names platforms");
        for (Point anchor : AgentZakumScenario.platformAnchors()) {
            assertEquals(anchor, ground(anchor), "anchor on its foothold: " + anchor);
            int anchorRegion = region(anchor);
            assertTrue(anchorRegion >= 0, "anchor on a region: " + anchor);
            for (Point spot : spots()) {
                int spotRegion = region(spot);
                assertTrue(graph.canReach(spotRegion, anchorRegion), "up " + spot + " -> " + anchor);
                assertTrue(graph.canReach(anchorRegion, spotRegion), "down " + anchor + " -> " + spot);
                assertFalse(path(spot, spotRegion, anchor, anchorRegion).isEmpty(), "path up " + spot + " -> " + anchor);
                assertFalse(path(anchor, anchorRegion, spot, spotRegion).isEmpty(), "path down " + anchor + " -> " + spot);
            }
        }
    }

    @Test
    void theWallsAreTheAltarsFootholdBoundsAndTheLineStandsOnTheFloorInsideThem() {
        assertNotNull(altar, "the altar reactor 2111001 is on the map");
        assertEquals(new Point(-10, -204), altar);
        assertEquals(-570, map.getFootholds().getMinDropX());
        assertEquals(660, map.getFootholds().getMaxDropX());

        List<Point> spots = spots();
        assertEquals(14, spots.size(), "fourteen spots fit between x=-160 and the west wall at the shipped spacing");
        Set<Integer> xs = new HashSet<>();
        for (Point spot : spots) {
            assertTrue(spot.x >= -570 && spot.x <= 660, "inside the walls: " + spot);
            assertTrue(Math.abs(spot.y - altar.y) <= 30, "on the floor by the altar, not a platform: " + spot);
            assertTrue(region(spot) >= 0, "on a region: " + spot);
            xs.add(spot.x);
        }
        assertEquals(spots.size(), xs.size(), "no two spots share a place");
    }

    /** Each spot of the pre-summon line that fits on the floor, grounded as the fight grounds it. */
    private static List<Point> spots() {
        int minX = map.getFootholds().getMinDropX();
        int maxX = map.getFootholds().getMaxDropX();
        List<Point> spots = new ArrayList<>();
        Set<Point> seen = new HashSet<>();
        for (int ordinal = 0; ; ordinal++) {
            Point spot = AgentZakumScenario.preSummonSpot(altar, ordinal, AgentZakumScenario.preSummonOffsetPx(),
                    AgentZakumScenario.preSummonSpacingPx(), minX, maxX);
            if (!seen.add(spot)) {
                return spots;
            }
            Point grounded = ground(spot);
            assertNotNull(grounded, "spot " + ordinal + " has ground under it");
            spots.add(grounded);
        }
    }

    private static boolean reachableBothWays(Point anchor) {
        if (!anchor.equals(ground(anchor)) || region(anchor) < 0) {
            return false;
        }
        int anchorRegion = region(anchor);
        for (Point spot : spots()) {
            int spotRegion = region(spot);
            if (spotRegion < 0 || path(spot, spotRegion, anchor, anchorRegion).isEmpty()
                    || path(anchor, anchorRegion, spot, spotRegion).isEmpty()) {
                return false;
            }
        }
        return true;
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

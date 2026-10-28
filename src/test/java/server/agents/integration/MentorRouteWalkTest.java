package server.agents.integration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mentor's road walked on the real maps (docs/BOT_CONCEPT_PLAN.md F4.3, the plan's "prove it offline" risk): on each
 * map of Mushroom Town -> Amherst -> Southperry -> Shanks's ship -> Lith Harbor, a bot set down where a traveller comes
 * in walks, by the companions' own navigation over the map's WZ geometry, to where the mentor leads next: the exit
 * portal, Shanks on the beach, the ship deck's hidden way into town, the taxi. MentorRouteTest checks that those
 * portals join the maps; this checks each map can be crossed. Single-map: the lab mocks map changes, so the hop itself
 * (a portal, the ship's warp) is the live check's.
 */
class MentorRouteWalkTest {
    private static final int REACH_PX = 60;
    private static final int TICKS = 6_000;

    /** One map of the road: from where a traveller lands, to the next leg's door. */
    record Leg(String name, int mapId, String fromPortal, Point fromPoint, String toPortal, Point toPoint) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Leg> legs() {
        return Stream.of(
                new Leg("Mushroom Town to the Snail Garden", 10_000, "sp", null, "out00", null),
                new Leg("Snail Garden", 20_000, "in00", null, "out00", null),
                new Leg("Snail Field of Flowers", 30_000, "in00", null, "out00", null),
                new Leg("In a Small Forest", 40_000, "in00", null, "out00", null),
                new Leg("Dangerous Forest to Amherst", 50_000, "in00", null, "east00", null),
                new Leg("Amherst", 1_000_000, "west00", null, "east00", null),
                new Leg("Amherst, back to the Dangerous Forest (a come-back)", 1_000_000, "east00", null, "west00", null),
                new Leg("Adventurer Training Center entrance", 1_010_000, "west00", null, "east00", null),
                new Leg("Split Road of Destiny", 1_020_000, "west00", null, "east00", null),
                // Shanks (22000) stands at (3341,-105) in WZ (MentorRouteTest reads it)
                new Leg("Southperry to Shanks", 2_000_000, "west00", null, null, new Point(3341, -105)),
                // 22000.js lands the ship on Lith Harbor's portal 0, the deck; its way down is hidden portal 30
                new Leg("Lith Harbor ship deck to its way down", 104_000_000, "sp", null, "h_west01", null),
                // the Regular Cab (1002000) stands at (790,338) in WZ: a walk down from the deck, no portal
                new Leg("Lith Harbor ship deck to the taxi", 104_000_000, "sp", null, null, new Point(790, 338)));
    }

    /**
     * Short of the fare it walks: off the deck by its hidden way (h_west01, checked above), then across town to the road
     * out (east00, to 104000100). That crossing takes Lith's own hidden in-map portals, which the lab cannot enter (its
     * characters have no client), so here only the navigation graph's path is checked; the walk is a live check.
     */
    @org.junit.jupiter.api.Test
    void lithsFarSideReachesTheRoadOutOnTheNavigationGraph() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(104_000_000);
        BotMovementSimulationLab.fromMap(map);
        server.agents.capabilities.navigation.AgentNavigationGraph graph =
                server.agents.capabilities.navigation.AgentNavigationGraphService.getGraph(map);
        Point from = point(map, "h_east01", null);
        Point to = point(map, "east00", null);
        int fromRegion = graph.findRegionId(map, from);
        int toRegion = graph.findRegionId(map, to);
        java.util.List<server.agents.capabilities.navigation.AgentNavigationGraph.Edge> path =
                server.agents.capabilities.navigation.AgentNavigationPathService.findPath(graph, map, from, fromRegion,
                        toRegion, to);
        assertTrue(fromRegion >= 0 && toRegion >= 0, "both ends stand on the graph");
        assertTrue(fromRegion == toRegion || path != null && !path.isEmpty(),
                "a path from r" + fromRegion + " to r" + toRegion);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    void aTravellerCrossesTheMapToTheMentorsNextDoor(Leg leg) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Point from = point(map, leg.fromPortal(), leg.fromPoint());
        Point to = point(map, leg.toPortal(), leg.toPoint());
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnBot("MENTOR", 900, map, from);
        lab.setMoveTarget("MENTOR", to, true);
        lab.setAiAccumulator("MENTOR", 50);

        lab.step(TICKS);

        Point end = lab.position("MENTOR");
        assertTrue(end.distanceSq(to) <= REACH_PX * REACH_PX, () -> leg + ": from " + from + " toward " + to
                + " it stopped at " + end + ":\n" + String.join("\n", lab.formatRecentTrace("MENTOR", 60)));
    }

    private static Point point(MapleMap map, String portalName, Point fixed) {
        if (fixed != null) {
            return fixed;
        }
        Portal portal = map.getPortal(portalName);
        assertNotNull(portal, portalName + " on " + map.getId());
        return new Point(portal.getPosition());
    }
}

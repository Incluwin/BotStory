package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A movement search that hits its edge-check budget returns only a closest-frontier fragment.
 * On Kerning City such fragments were reported as complete routes when they happened to end in
 * the target region, and a committed route from region 113 livelocked: its recovery search
 * capped (219K edge checks against the 160K budget) and the mover followed a fragment back to
 * region 126, from where the complete route led up through region 113 again.
 */
class AgentNavigationCappedSearchTest {
    private static final int KERNING_CITY = 103000000;
    private static final AgentTravelVariationRuntime.RouteVariation VARIATION =
            new AgentTravelVariationRuntime.RouteVariation(3L, 1.30d);

    @Test
    void cappedFragmentThatEndsInTheTargetRegionIsNotAReachedRoute() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(192, -101);
        Point target = new Point(2534, 100);
        int startRegionId = graph.findRegionId(map, start);
        int targetRegionId = graph.findRegionId(map, target);

        AgentNavigationPathService.SearchOutcome bounded = AgentNavigationPathService.runSearch(
                graph, map, start, startRegionId, targetRegionId, target, "maple-island-varied",
                false, false, AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS, VARIATION);
        AgentNavigationPathService.SearchOutcome complete = AgentNavigationPathService.runSearch(
                graph, map, start, startRegionId, targetRegionId, target, "maple-island-varied",
                false, false, Integer.MAX_VALUE, VARIATION);

        assertTrue(bounded.capped());
        assertEquals(targetRegionId, bounded.finalRegionId(), "the frontier lies in the target region");
        assertFalse(bounded.reached(), "no goal was popped");
        assertTrue(bounded.bestEffort());
        assertEquals(AgentNavigationPathService.RouteCompleteness.PARTIAL, bounded.completeness());
        assertEquals(Integer.MAX_VALUE, bounded.cost());
        assertTrue(complete.reached());
        assertNotEquals(complete.path().get(0), bounded.path().get(0),
                "the fragment starts off the complete route");

        AgentNavigationPathService.MovementPathSelection selection =
                AgentNavigationPathService.movementPathSelection(graph, map, start, startRegionId,
                        targetRegionId, target, VARIATION,
                        AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                        AgentNavigationPathService.MAX_EDGE_CHECKS, false, null, null, null);

        assertTrue(selection.recoverySearch(), "the capped fragment sends the mover to recovery");
        assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE, selection.completeness());
        assertEquals(complete.path(), selection.path());
    }

    @Test
    void kerningCommittedRouteFromRegion113ArrivesWithoutOscillating() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(-253, -117);
        Point target = new Point(-1670, -408);
        int startRegionId = graph.findRegionId(map, start);
        int targetRegionId = graph.findRegionId(map, target);
        assertEquals(113, startRegionId);
        assertEquals(56, targetRegionId);

        AtomicInteger edgeTests = new AtomicInteger();
        AgentNavigationPathService.MovementPathSelection planned =
                AgentNavigationPathService.movementPathSelection(graph, map, start, startRegionId,
                        targetRegionId, target, null, Integer.MAX_VALUE, Integer.MAX_VALUE, false,
                        null, edge -> {
                            edgeTests.incrementAndGet();
                            return true;
                        }, null);
        assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE, planned.completeness());
        assertEquals(106, planned.activeEdge().toRegionId, "the route climbs from region 113");
        assertTrue(edgeTests.get() < AgentNavigationPathService.MAX_EDGE_CHECKS,
                "the complete search fits the recovery budget: " + edgeTests);

        // The mover takes one edge per decision and decides again where it lands, with no route
        // memory, so nothing but the searches themselves can keep it on the route.
        List<AgentNavigationGraph.Edge> taken = new ArrayList<>();
        Point position = start;
        int regionId = startRegionId;
        while (regionId != targetRegionId && taken.size() < 3 * planned.path().size()) {
            AgentNavigationGraph.Edge edge = AgentNavigationPathService.movementPathSelection(
                    graph, map, position, regionId, targetRegionId, target, null,
                    AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                    AgentNavigationPathService.MAX_EDGE_CHECKS, false, null, null, null)
                    .activeEdge();
            taken.add(edge);
            position = new Point(edge.endPoint);
            regionId = edge.toRegionId;
        }

        assertEquals(planned.path(), taken, "the mover follows the complete route to region 56");
    }

    @Test
    void kerningMainStreetWalkWestNeverTakesTheInRegionPortalEast() {
        // Once main-street searches completed, the cheapest route from its west half to west00
        // left through portal ph03 to the east end, climbed down to the sewer and came back
        // through its portal. The mover drops a same-region route at the first edge that leaves
        // the region, so agents took ph03 east, walked back west and took it again, forever.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point west00 = new Point(-2279, 153);
        Point westHalf = new Point(-700, 156);
        int mainStreet = graph.findRegionId(map, westHalf);
        assertEquals(mainStreet, graph.findRegionId(map, west00));
        AgentNavigationPathService.MovementPathSelection unconstrained =
                AgentNavigationPathService.movementPathSelection(graph, map, westHalf, mainStreet,
                        mainStreet, west00, null, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null,
                        null, null);
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, unconstrained.activeEdge().type);
        assertEquals(1313, unconstrained.activeEdge().endPoint.x);
        assertTrue(unconstrained.path().stream().anyMatch(edge -> edge.toRegionId != mainStreet),
                "the portal starts a route that leaves the main street");

        AgentRuntimeEntry fromWestHalf = entryAt(westHalf, map);
        AgentNavigationTargetService.NavigationDirective walk =
                AgentNavigationTargetService.resolveTarget(fromWestHalf, west00, true);
        assertEquals(west00, walk.targetPos(), "walks straight to west00");
        assertNull(AgentNavigationDebugStateRuntime.activeNavigationEdge(fromWestHalf));

        // Out of the portal's reach, so the mover plans it without entering it.
        AgentRuntimeEntry fromEastEnd = entryAt(new Point(1000, 156), map);
        AgentNavigationTargetService.resolveTarget(fromEastEnd, west00, true);
        AgentNavigationGraph.Edge shortcut = (AgentNavigationGraph.Edge)
                AgentNavigationDebugStateRuntime.activeNavigationEdge(fromEastEnd);
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, shortcut.type);
        assertEquals(-855, shortcut.endPoint.x, "the in-region portal still shortens the walk west");
    }

    private static AgentRuntimeEntry entryAt(Point position, MapleMap map) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(81);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(position));
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        return entry;
    }
}

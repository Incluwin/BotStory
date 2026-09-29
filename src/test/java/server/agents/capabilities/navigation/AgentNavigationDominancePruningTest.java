package server.agents.capabilities.navigation;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentNavigationDominancePruningTest {
    private static final int KERNING_PQ_STAGE_ONE = 103000800;
    private static final int KERNING_CITY = 103000000;

    @Test
    void prunedSearchReturnsTheUnprunedRouteOnKerningPqStageOne() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_PQ_STAGE_ONE);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Map<Integer, Point> anchors = regionAnchors(graph);
        List<AgentTravelVariationRuntime.RouteVariation> variations = java.util.Arrays.asList(
                null, new AgentTravelVariationRuntime.RouteVariation(7L, 1.35d));

        long unprunedExpanded = 0;
        long prunedExpanded = 0;
        int compared = 0;
        for (AgentTravelVariationRuntime.RouteVariation variation : variations) {
            for (Map.Entry<Integer, Point> start : anchors.entrySet()) {
                for (Map.Entry<Integer, Point> target : anchors.entrySet()) {
                    if (start.getKey().equals(target.getKey()) || target.getKey() % 2 != 0) {
                        continue;
                    }
                    AgentNavigationPathService.SearchOutcome unpruned = search(
                            graph, map, start, target, AgentNavigationPathService.MAX_EDGE_CHECKS,
                            variation, false);
                    AgentNavigationPathService.SearchOutcome pruned = search(
                            graph, map, start, target, AgentNavigationPathService.MAX_EDGE_CHECKS,
                            variation, true);
                    assertFalse(unpruned.capped(), "pair " + start.getKey() + "->" + target.getKey());
                    assertSameRoute(unpruned, pruned);
                    unprunedExpanded += unpruned.expandedNodes();
                    prunedExpanded += pruned.expandedNodes();
                    compared++;
                }
            }
        }

        assertTrue(compared > 200, "compared " + compared + " searches");
        assertTrue(prunedExpanded * 2 <= unprunedExpanded,
                "expanded " + unprunedExpanded + " -> " + prunedExpanded);
    }

    @Test
    void pruningLetsABudgetedSearchThroughADropFanFindTheSameRoute() {
        // One ledge fans out to 30 landing points on a long platform; landing farther away is
        // never cheaper than landing near and walking. Unpruned, every landing rescans the
        // platform's 30 drops before the far drop to the target is proven best, which is the
        // Kerning PQ shape that pushed scoring and recovery searches past their budget.
        List<AgentNavigationGraph.Region> regions = List.of(
                groundRegion(1, 0, 100, 0),
                groundRegion(2, 0, 3200, 100),
                groundRegion(3, 0, 3200, 200),
                groundRegion(4, 0, 3200, 300));
        AgentNavigationGraph probe = graph(regions, Map.of());
        List<AgentNavigationGraph.Edge> ledge = new ArrayList<>();
        List<AgentNavigationGraph.Edge> platform = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int landingX = 100 + 100 * i;
            int airtime = 500 + 2 * AgentNavigationPathService.intraRegionTravelCost(
                    probe, 2, new Point(100, 100), new Point(landingX, 100));
            ledge.add(edge(1, 2, AgentNavigationGraph.EdgeType.JUMP,
                    new Point(100, 0), new Point(landingX, 100), 0, airtime));
        }
        for (int j = 0; j < 29; j++) {
            int dropX = 150 + 100 * j;
            platform.add(edge(2, 4, AgentNavigationGraph.EdgeType.DROP,
                    new Point(dropX, 100), new Point(dropX, 300), 0, 300));
        }
        platform.add(edge(2, 3, AgentNavigationGraph.EdgeType.DROP,
                new Point(3100, 100), new Point(3100, 200), 0, 300));
        AgentNavigationGraph graph = graph(regions, Map.of(1, ledge, 2, platform));
        Map.Entry<Integer, Point> start = Map.entry(1, new Point(50, 0));
        Map.Entry<Integer, Point> target = Map.entry(3, new Point(3100, 200));

        AgentNavigationPathService.SearchOutcome complete =
                search(graph, null, start, target, Integer.MAX_VALUE, null, false);
        AgentNavigationPathService.SearchOutcome unpruned =
                search(graph, null, start, target, 200, null, false);
        AgentNavigationPathService.SearchOutcome pruned =
                search(graph, null, start, target, 200, null, true);

        assertTrue(complete.reached());
        assertTrue(unpruned.capped());
        assertEquals(AgentNavigationPathService.RouteCompleteness.UNREACHABLE, unpruned.completeness());
        assertFalse(pruned.capped());
        assertSameRoute(complete, pruned);
    }

    @Test
    void prunedSearchReturnsTheUnprunedRouteOnKerningCityForFractionalWalkCosts() {
        // At speed 135 one pixel of walking costs 5.93 ms, so walk costs round both ways; the
        // dominance slack and the edge-interval scan must stay exact under that rounding.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        int compared = 0;
        for (AgentMovementProfile profile : List.of(
                AgentMovementProfile.base(), new AgentMovementProfile(135, 115))) {
            AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, profile);
            List<Map.Entry<Integer, Point>> anchors = new ArrayList<>(regionAnchors(graph).entrySet());
            for (AgentTravelVariationRuntime.RouteVariation variation : java.util.Arrays.asList(
                    null, new AgentTravelVariationRuntime.RouteVariation(7L, 1.35d))) {
                for (int i = 0; i < 24; i++) {
                    Map.Entry<Integer, Point> start = anchors.get((i * 37) % anchors.size());
                    Map.Entry<Integer, Point> target = anchors.get((i * 53 + 11) % anchors.size());
                    if (start.getKey().equals(target.getKey())) {
                        continue;
                    }
                    AgentNavigationPathService.SearchOutcome unpruned = search(
                            graph, map, start, target, Integer.MAX_VALUE, variation, false);
                    AgentNavigationPathService.SearchOutcome pruned = search(
                            graph, map, start, target, Integer.MAX_VALUE, variation, true);
                    assertSameRoute(unpruned, pruned);
                    compared++;
                }
            }
        }
        assertTrue(compared > 80, "compared " + compared + " searches");
    }

    @Test
    void landingsThatTieWithWalkingAlongThePlatformAreDominated() {
        // A ledge above a platform drops onto it every 6 px. Each landing costs exactly what
        // walking there along the platform from the first landing costs, so none of them can
        // relax an edge more cheaply (Kerning City: region 126 over the main street). Kept, each
        // landing rescanned all of the platform's edges.
        List<AgentNavigationGraph.Region> regions = List.of(
                groundRegion(1, 0, 600, 0),
                groundRegion(2, 0, 600, 100),
                groundRegion(3, 0, 600, 300),
                groundRegion(4, 550, 650, 500));
        List<AgentNavigationGraph.Edge> ledge = new ArrayList<>();
        for (int x = 0; x <= 600; x += 6) {
            ledge.add(edge(1, 2, AgentNavigationGraph.EdgeType.DROP,
                    new Point(x, 0), new Point(x, 100), 0, 400));
        }
        List<AgentNavigationGraph.Edge> platform = new ArrayList<>();
        for (int x = 0; x < 600; x += 12) {
            platform.add(edge(2, 3, AgentNavigationGraph.EdgeType.DROP,
                    new Point(x, 100), new Point(x, 300), 0, 300));
        }
        platform.add(edge(2, 4, AgentNavigationGraph.EdgeType.DROP,
                new Point(600, 100), new Point(600, 500), 0, 400));
        AgentNavigationGraph graph = graph(regions, Map.of(1, ledge, 2, platform));
        Map.Entry<Integer, Point> start = Map.entry(1, new Point(0, 0));
        Map.Entry<Integer, Point> target = Map.entry(4, new Point(600, 500));
        Map<AgentNavigationGraph.Edge, Integer> tests = new HashMap<>();

        AgentNavigationPathService.MovementPathSelection selection = countingSearch(
                graph, start, target, tests);

        assertSameRoute(search(graph, null, start, target, Integer.MAX_VALUE, null, false),
                selection.outcome());
        for (AgentNavigationGraph.Edge edge : platform) {
            assertEquals(1, tests.get(edge), "platform edge at x=" + edge.startPoint.x);
        }
    }

    @Test
    void aStateBetweenTwoExpandedStatesScansOnlyTheEdgesBetweenThem() {
        // Jumps land on the platform at x=1000 (10 ms), x=2000 (11 ms) and x=1500 (20 ms). The
        // landing at 1500 is not dominated (walking from either neighbour takes 4000 ms), but the
        // edges at or beyond its expanded neighbours are cheaper from them, so it scans only the
        // edges between them, which include the two equally cheap ways down to the target.
        List<AgentNavigationGraph.Region> regions = List.of(
                groundRegion(1, 0, 10, 0),
                groundRegion(2, 0, 3000, 100),
                groundRegion(3, 0, 3000, 300),
                groundRegion(4, 1400, 1600, 500));
        AgentNavigationGraph.Edge landing1500 = edge(1, 2, AgentNavigationGraph.EdgeType.JUMP,
                new Point(0, 0), new Point(1500, 100), 0, 20);
        List<AgentNavigationGraph.Edge> ledge = List.of(
                edge(1, 2, AgentNavigationGraph.EdgeType.JUMP, new Point(0, 0), new Point(1000, 100), 0, 10),
                edge(1, 2, AgentNavigationGraph.EdgeType.JUMP, new Point(0, 0), new Point(2000, 100), 0, 11),
                landing1500);
        Map<Integer, AgentNavigationGraph.Edge> platformByX = new java.util.TreeMap<>();
        for (int x : new int[]{500, 1000, 1200, 1800, 2000, 2500}) {
            platformByX.put(x, edge(2, 3, AgentNavigationGraph.EdgeType.DROP,
                    new Point(x, 100), new Point(x, 300), 0, 300));
        }
        AgentNavigationGraph.Edge eastExit = edge(2, 4, AgentNavigationGraph.EdgeType.DROP,
                new Point(1600, 100), new Point(1500, 500), 0, 100);
        AgentNavigationGraph.Edge westExit = edge(2, 4, AgentNavigationGraph.EdgeType.DROP,
                new Point(1400, 100), new Point(1500, 500), 0, 100);
        platformByX.put(1600, eastExit);
        platformByX.put(1400, westExit);
        // The east exit is listed first, so it wins the tie from x=1500 in an unpruned search.
        List<AgentNavigationGraph.Edge> platform = List.of(platformByX.get(500), eastExit,
                platformByX.get(1000), platformByX.get(1200), westExit, platformByX.get(1800),
                platformByX.get(2000), platformByX.get(2500));
        AgentNavigationGraph graph = graph(regions, Map.of(1, ledge, 2, platform));
        Map.Entry<Integer, Point> start = Map.entry(1, new Point(0, 0));
        Map.Entry<Integer, Point> target = Map.entry(4, new Point(1500, 500));
        Map<AgentNavigationGraph.Edge, Integer> tests = new HashMap<>();

        AgentNavigationPathService.MovementPathSelection selection = countingSearch(
                graph, start, target, tests);

        assertEquals(List.of(landing1500, eastExit), selection.path());
        assertSameRoute(search(graph, null, start, target, Integer.MAX_VALUE, null, false),
                selection.outcome());
        Map<Integer, Integer> testsByX = new java.util.TreeMap<>();
        platformByX.forEach((x, edge) -> testsByX.put(x, tests.get(edge)));
        // 1000 scans all; 2000 scans beyond 1000; 1500 scans between 1000 and 2000.
        assertEquals(Map.of(500, 1, 1000, 1, 1200, 3, 1400, 3, 1600, 3, 1800, 3, 2000, 2, 2500, 2),
                testsByX);
    }

    @Test
    void portalArrivalNeverDominatesAWalkInThatAvoidsTheChainedPortalCooldown() {
        // Arriving through portal 1 and immediately taking portal 2 pays the portal cooldown. A
        // drop that lands a few pixels away is costlier on arrival but walks into portal 2 for
        // free, so the portal arrival must not prune it.
        List<AgentNavigationGraph.Region> regions = List.of(
                groundRegion(1, 0, 100, 0),
                groundRegion(2, 0, 1000, 100),
                groundRegion(3, 0, 1000, 200));
        AgentNavigationGraph probe = graph(regions, Map.of());
        int walk = AgentNavigationPathService.intraRegionTravelCost(
                probe, 2, new Point(500, 100), new Point(504, 100));
        AgentNavigationGraph.Edge portalIn = edge(1, 2, AgentNavigationGraph.EdgeType.PORTAL,
                new Point(0, 0), new Point(500, 100), 1, 0);
        AgentNavigationGraph.Edge drop = edge(1, 2, AgentNavigationGraph.EdgeType.DROP,
                new Point(0, 0), new Point(504, 100), 0, walk + 20);
        AgentNavigationGraph.Edge portalOut = edge(2, 3, AgentNavigationGraph.EdgeType.PORTAL,
                new Point(500, 100), new Point(500, 200), 2, 0);
        AgentNavigationGraph graph = graph(regions, Map.of(
                1, List.of(portalIn, drop),
                2, List.of(portalOut)));
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(portal.getPortalStatus()).thenReturn(true);
        when(map.getPortal(anyInt())).thenReturn(portal);
        Map.Entry<Integer, Point> start = Map.entry(1, new Point(0, 0));
        Map.Entry<Integer, Point> target = Map.entry(3, new Point(500, 200));

        AgentNavigationPathService.SearchOutcome unpruned = search(
                graph, map, start, target, AgentNavigationPathService.MAX_EDGE_CHECKS, null, false);
        AgentNavigationPathService.SearchOutcome pruned = search(
                graph, map, start, target, AgentNavigationPathService.MAX_EDGE_CHECKS, null, true);

        assertEquals(List.of(drop, portalOut), unpruned.path());
        assertSameRoute(unpruned, pruned);
    }

    private static AgentNavigationPathService.SearchOutcome search(
            AgentNavigationGraph graph,
            MapleMap map,
            Map.Entry<Integer, Point> start,
            Map.Entry<Integer, Point> target,
            int budget,
            AgentTravelVariationRuntime.RouteVariation variation,
            boolean dominancePruning) {
        return AgentNavigationPathService.runSearch(graph, map, start.getValue(), start.getKey(),
                target.getKey(), target.getValue(), "target-score", budget, variation,
                dominancePruning);
    }

    /** The live movement search with dominance pruning as configured, counting edge tests. */
    private static AgentNavigationPathService.MovementPathSelection countingSearch(
            AgentNavigationGraph graph,
            Map.Entry<Integer, Point> start,
            Map.Entry<Integer, Point> target,
            Map<AgentNavigationGraph.Edge, Integer> tests) {
        assertTrue(AgentNavigationPathService.DOMINANCE_PRUNING);
        return AgentNavigationPathService.movementPathSelection(graph, mock(MapleMap.class),
                start.getValue(), start.getKey(), target.getKey(), target.getValue(), null,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false, null,
                edge -> {
                    tests.merge(edge, 1, Integer::sum);
                    return true;
                }, null);
    }

    private static void assertSameRoute(AgentNavigationPathService.SearchOutcome expected,
                                        AgentNavigationPathService.SearchOutcome actual) {
        assertEquals(expected.reached(), actual.reached());
        assertEquals(expected.cost(), actual.cost());
        assertEquals(expected.finalRegionId(), actual.finalRegionId());
        assertEquals(expected.path().size(), actual.path().size());
        for (int i = 0; i < expected.path().size(); i++) {
            assertSame(expected.path().get(i), actual.path().get(i));
        }
    }

    /** A deterministic in-region point per region: its lowest outgoing edge start point. */
    private static Map<Integer, Point> regionAnchors(AgentNavigationGraph graph) {
        Map<Integer, Point> anchors = new java.util.TreeMap<>();
        for (AgentNavigationGraph.Region region : graph.regions) {
            graph.getOutgoing(region.id).stream()
                    .map(edge -> edge.startPoint)
                    .min(Comparator.<Point>comparingInt(point -> point.x)
                            .thenComparingInt(point -> point.y))
                    .ifPresent(point -> anchors.put(region.id, point));
        }
        return anchors;
    }

    private static AgentNavigationGraph graph(List<AgentNavigationGraph.Region> regions,
                                              Map<Integer, List<AgentNavigationGraph.Edge>> outgoing) {
        Map<Integer, AgentNavigationGraph.Region> regionsById = new HashMap<>();
        Map<Integer, Integer> regionIdByFootholdId = new HashMap<>();
        for (AgentNavigationGraph.Region region : regions) {
            regionsById.put(region.id, region);
            for (AgentNavigationGraph.Segment segment : region.segments) {
                regionIdByFootholdId.put(segment.footholdId, region.id);
            }
        }
        return new AgentNavigationGraph(1, 1, AgentMovementProfile.base(), regions,
                regionsById, regionIdByFootholdId, outgoing, Set.of());
    }

    private static AgentNavigationGraph.Region groundRegion(int id, int x1, int x2, int y) {
        return new AgentNavigationGraph.Region(id, List.of(new AgentNavigationGraph.Segment(
                new Foothold(new Point(x1, y), new Point(x2, y), id))));
    }

    private static AgentNavigationGraph.Edge edge(int fromRegionId,
                                                  int toRegionId,
                                                  AgentNavigationGraph.EdgeType type,
                                                  Point start,
                                                  Point end,
                                                  int portalId,
                                                  int cost) {
        return new AgentNavigationGraph.Edge(fromRegionId, toRegionId, type,
                start, end, 0, portalId, 0, 0, 0, cost);
    }
}

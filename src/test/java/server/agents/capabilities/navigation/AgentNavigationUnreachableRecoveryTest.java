package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Movement recovery searches that provably cannot reach their target must not re-run on every
 * movement decision (Kerning City citizens re-ran a 110K edge-check search to the drop-only roof
 * region 80 every ~0.5 s), while the selection handed to the mover stays exactly as before.
 */
class AgentNavigationUnreachableRecoveryTest {
    private static final int KERNING_CITY = 103000000;
    private static final int CLOSED_PORTAL_ID = 7;

    @Test
    void directedReachabilityRejectsADropOnlyRoofThatSharesTheGroundComponent() {
        AgentNavigationGraph graph = graph(
                List.of(ground(1, 0, 400, 300), ground(2, 100, 200, 100)),
                Map.of(1, List.of(edge(1, 2, AgentNavigationGraph.EdgeType.FLASH_JUMP,
                                new Point(150, 300), new Point(150, 100))),
                        2, List.of(edge(2, 1, AgentNavigationGraph.EdgeType.DROP,
                                new Point(150, 100), new Point(150, 300)))));

        assertEquals(graph.connectedComponentId(1), graph.connectedComponentId(2));
        assertTrue(graph.canReach(2, 1));
        assertTrue(graph.canReach(1, 1));
        assertFalse(graph.canReach(1, 2), "a movement-skill edge is not a walking route");
    }

    @Test
    void kerningRoofRecoveryIsSkippedWithTheSameSelection() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(-1826, -233);
        Point roof = new Point(-2127, -338);
        int startRegionId = graph.findRegionId(map, start);
        int roofRegionId = graph.findRegionId(map, roof);
        assertEquals(97, startRegionId);
        assertEquals(80, roofRegionId);
        assertEquals(graph.connectedComponentId(startRegionId), graph.connectedComponentId(roofRegionId));
        assertFalse(graph.canReach(startRegionId, roofRegionId));

        // The recovery search this skips never reaches the roof. Live it exhausted after ~110K
        // edge checks; on the base profile it even spends the whole budget, which a cache of
        // exhausted searches alone would never catch.
        AgentNavigationPathService.SearchOutcome recovery = AgentNavigationPathService.runSearch(
                graph, map, start, startRegionId, roofRegionId, roof, "maple-island-varied-recovery",
                false, false, AgentNavigationPathService.MAX_EDGE_CHECKS);
        assertFalse(recovery.reached());
        assertTrue(recovery.path().isEmpty());

        Character agent = bot(1, map);
        AtomicInteger edgeTests = new AtomicInteger();
        AgentNavigationPathService.MovementPathSelection selection = select(
                graph, map, start, startRegionId, roofRegionId, roof, agent,
                counting(edgeTests, edge -> true));
        AtomicInteger boundedEdgeTests = new AtomicInteger();
        AgentNavigationPathService.MovementPathSelection bounded = boundedOnly(
                graph, map, start, startRegionId, roofRegionId, roof, agent,
                counting(boundedEdgeTests, edge -> true));

        assertTrue(selection.outcome().capped(), "the bounded search still runs and caps");
        assertEquals(boundedEdgeTests.get(), edgeTests.get(), "only the bounded search ran");
        assertSameSelection(bounded, selection);
    }

    @Test
    void exhaustedRecoveryIsRememberedPerAgentAndFilterView() {
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(map.getPortal(CLOSED_PORTAL_ID)).thenReturn(portal);
        when(portal.getPortalStatus()).thenReturn(false);
        // Region 2 is statically reachable, but only through a closed portal.
        AgentNavigationGraph graph = graph(
                List.of(ground(1, 0, 100, 100), ground(2, 400, 500, 100),
                        ground(3, 100, 200, 100), ground(4, -100, 0, 100)),
                Map.of(1, List.of(
                                portal(1, 2, new Point(50, 100), new Point(450, 100)),
                                edge(1, 3, AgentNavigationGraph.EdgeType.WALK,
                                        new Point(100, 100), new Point(100, 100)),
                                edge(1, 4, AgentNavigationGraph.EdgeType.WALK,
                                        new Point(0, 100), new Point(0, 100))),
                        3, List.of(edge(3, 1, AgentNavigationGraph.EdgeType.WALK,
                                new Point(100, 100), new Point(100, 100))),
                        4, List.of(edge(4, 1, AgentNavigationGraph.EdgeType.WALK,
                                new Point(0, 100), new Point(0, 100)))));
        assertTrue(graph.canReach(1, 2));
        Point start = new Point(50, 100);
        Point target = new Point(450, 100);
        Character agent = bot(11, map);

        AtomicInteger first = new AtomicInteger();
        AgentNavigationPathService.MovementPathSelection searched = select(
                graph, map, start, 1, 2, target, agent, counting(first, edge -> true));
        AtomicInteger repeat = new AtomicInteger();
        AgentNavigationPathService.MovementPathSelection remembered = select(
                graph, map, start, 1, 2, target, agent, counting(repeat, edge -> true));
        AtomicInteger bounded = new AtomicInteger();
        boundedOnly(graph, map, start, 1, 2, target, agent, counting(bounded, edge -> true));

        assertTrue(first.get() > bounded.get(), "the first call ran the recovery search");
        // Plus one filter test per remaining edge of the fragment the Agent keeps following.
        assertEquals(bounded.get() + remembered.path().size(), repeat.get(),
                "the repeat ran only the bounded search");
        assertSameSelection(searched, remembered);

        AtomicInteger otherAgent = new AtomicInteger();
        select(graph, map, start, 1, 2, target, bot(12, map), counting(otherAgent, edge -> true));
        assertEquals(first.get(), otherAgent.get(), "another Agent's view is searched afresh");

        AtomicInteger unfiltered = new AtomicInteger();
        AgentNavigationPathService.movementPathSelection(graph, map, start, 1, 2, target, VARIATION,
                1, AgentNavigationPathService.MAX_EDGE_CHECKS, false, agent, null,
                edge -> {
                    unfiltered.incrementAndGet();
                    return 0;
                });
        assertTrue(unfiltered.get() > 0);
        AtomicInteger unfilteredRepeat = new AtomicInteger();
        AgentNavigationPathService.movementPathSelection(graph, map, start, 1, 2, target, VARIATION,
                1, AgentNavigationPathService.MAX_EDGE_CHECKS, false, agent, null,
                edge -> {
                    unfilteredRepeat.incrementAndGet();
                    return 0;
                });
        assertTrue(unfilteredRepeat.get() < unfiltered.get(),
                "an unfiltered search is remembered separately from the filtered one");
    }

    @Test
    void rememberedRecoveryExpiresAfterItsTtl() {
        AgentNavigationPathService.UnreachableRecoveryCache cache =
                new AgentNavigationPathService.UnreachableRecoveryCache();
        AgentNavigationGraph graph = graph(List.of(ground(1, 0, 100, 100)), Map.of());
        AgentNavigationPathService.UnreachableRecoveryCache.Key key =
                new AgentNavigationPathService.UnreachableRecoveryCache.Key(graph, 5, 1, 2, false, true);
        AgentNavigationPathService.UnreachableRecoveryCache.Key otherGraph =
                new AgentNavigationPathService.UnreachableRecoveryCache.Key(
                        graph(List.of(ground(1, 0, 100, 100)), Map.of()), 5, 1, 2, false, true);

        cache.remember(key, 1_000L, 3_000L);

        assertTrue(cache.remembered(key, 3_999L));
        assertFalse(cache.remembered(otherGraph, 3_999L), "a rebuilt graph is a different view");
        assertFalse(cache.remembered(key, 4_000L), "a reopened portal is searched again after the TTL");
        assertEquals(0, cache.size(), "the expired entry is dropped");

        cache.remember(key, 10_000L, 0L);
        assertFalse(cache.remembered(key, 10_000L), "a zero TTL disables the cache");
        cache.remember(key, 20_000L, 1_000L);
        cache.remember(otherGraph, 30_000L, 1_000L);
        assertEquals(1, cache.size(), "inserts sweep expired entries");
    }

    private static final AgentTravelVariationRuntime.RouteVariation VARIATION =
            new AgentTravelVariationRuntime.RouteVariation(3L, 1.30d);

    private static AgentNavigationPathService.MovementPathSelection select(
            AgentNavigationGraph graph,
            MapleMap map,
            Point start,
            int startRegionId,
            int targetRegionId,
            Point target,
            Character agent,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed) {
        return AgentNavigationPathService.movementPathSelection(graph, map, start, startRegionId,
                targetRegionId, target, VARIATION, movementBudget(graph),
                AgentNavigationPathService.MAX_EDGE_CHECKS, false, agent, edgeAllowed, edge -> 0);
    }

    /** The same call with no recovery budget above the bounded one, so recovery never runs. */
    private static AgentNavigationPathService.MovementPathSelection boundedOnly(
            AgentNavigationGraph graph,
            MapleMap map,
            Point start,
            int startRegionId,
            int targetRegionId,
            Point target,
            Character agent,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed) {
        return AgentNavigationPathService.movementPathSelection(graph, map, start, startRegionId,
                targetRegionId, target, VARIATION, movementBudget(graph), movementBudget(graph),
                false, agent, edgeAllowed, edge -> 0);
    }

    private static int movementBudget(AgentNavigationGraph graph) {
        return graph.mapId == KERNING_CITY ? AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS : 1;
    }

    private static void assertSameSelection(AgentNavigationPathService.MovementPathSelection expected,
                                            AgentNavigationPathService.MovementPathSelection actual) {
        assertEquals(expected.path(), actual.path());
        assertEquals(expected.activeEdge(), actual.activeEdge());
        assertEquals(expected.completeness(), actual.completeness());
        assertEquals(expected.recoverySearch(), actual.recoverySearch());
        assertEquals(expected.outcome().capped(), actual.outcome().capped());
        assertEquals(expected.outcome().finalRegionId(), actual.outcome().finalRegionId());
    }

    private static Predicate<AgentNavigationGraph.Edge> counting(
            AtomicInteger counter, Predicate<AgentNavigationGraph.Edge> delegate) {
        return edge -> {
            counter.incrementAndGet();
            return delegate.test(edge);
        };
    }

    private static Character bot(int id, MapleMap map) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getMap()).thenReturn(map);
        return bot;
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

    private static AgentNavigationGraph.Edge portal(int from, int to, Point start, Point end) {
        return new AgentNavigationGraph.Edge(from, to, AgentNavigationGraph.EdgeType.PORTAL,
                start, end, 0, CLOSED_PORTAL_ID, 0, 0, 0, 0);
    }
}

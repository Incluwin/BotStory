package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.monitoring.AgentPerformanceMonitor;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kerning City citizens repeated the same movement search on every AI tick of a walk: the
 * committed-edge refresh re-searched although it can only switch to a WALK edge (towns have
 * none), same-region decisions searched although only a self-loop can start a route the mover
 * keeps, and every decision after the first edge of a long recovered route recovered the rest
 * of that route again. None of those searches may change what the mover does.
 */
class AgentNavigationRepeatedSearchTest {
    private static final int KERNING_CITY = 103000000;
    private static final int MAIN_STREET = 148;
    private static final int OPEN_PORTAL_ID = 3;
    private static final AgentTravelVariationRuntime.RouteVariation VARIATION =
            new AgentTravelVariationRuntime.RouteVariation(3L, 1.30d);

    @Test
    void kerningRefreshSkipsSearchesThatCouldNeverReplaceTheCommittedEdge() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        for (AgentNavigationGraph.Region region : graph.regions) {
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                assertFalse(edge.type == AgentNavigationGraph.EdgeType.WALK,
                        "a WALK exit is the only replacement a refresh can take");
            }
        }
        // The logged route: the citizen walked across region 126 re-searching every AI tick.
        Point start = new Point(-61, -54);
        Point target = new Point(1546, -971);
        int startRegionId = graph.findRegionId(map, start);
        int targetRegionId = graph.findRegionId(map, target);
        AgentNavigationGraph.Edge committed = AgentNavigationPathService.movementPathSelection(
                graph, map, start, startRegionId, targetRegionId, target, VARIATION,
                AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                AgentNavigationPathService.MAX_EDGE_CHECKS, false, null, null, null).activeEdge();
        assertNotNull(committed);
        Character agent = bot(1, map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        for (int step = 0; step <= 4; step++) {
            Point walking = graph.getRegion(startRegionId).pointAt(
                    start.x + (committed.startPoint.x - start.x) * step / 4);
            AgentNavigationCommittedEdgeService.NextEdgeFinder search =
                    (activeGraph, activeBot, from, to, targetPos) ->
                            AgentNavigationPathService.movementPathSelection(activeGraph, map,
                                    walking, from, to, targetPos, VARIATION,
                                    AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                                    AgentNavigationPathService.MAX_EDGE_CHECKS, false, null,
                                    null, null).activeEdge();
            AtomicInteger searches = new AtomicInteger();
            AgentNavigationGraph.Edge refreshed = AgentNavigationCommittedEdgeService
                    .refreshCommittedGroundEdge(graph, entry, agent, startRegionId,
                            targetRegionId, target, committed, true,
                            (activeGraph, activeBot, from, to, targetPos) -> {
                                searches.incrementAndGet();
                                return search.findNextEdge(activeGraph, activeBot, from, to, targetPos);
                            });
            // What the search would have decided: without a graph the refresh cannot skip it.
            AgentNavigationGraph.Edge searched = AgentNavigationCommittedEdgeService
                    .refreshCommittedGroundEdge(null, entry, agent, startRegionId,
                            targetRegionId, target, committed, true,
                            (ignored, activeBot, from, to, targetPos) ->
                                    search.findNextEdge(graph, activeBot, from, to, targetPos));

            assertEquals(0, searches.get(), "no search at step " + step);
            assertSame(committed, refreshed);
            assertSame(searched, refreshed, "the search would have kept the edge at step " + step);
        }
    }

    @Test
    void kerningSameRegionDecisionsSearchOnlyWhereARouteCanStayOnTheRegion() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        List<Integer> searchable = new ArrayList<>();
        for (AgentNavigationGraph.Region region : graph.regions) {
            if (AgentNavigationPathService.canRouteWithinRegion(graph, region.id)) {
                searchable.add(region.id);
            }
        }
        assertEquals(List.of(MAIN_STREET), searchable, "only the main street has in-region portals");

        // Region 151 capped the bounded search at 20K edge checks (61-147 ms) for these walks.
        for (Point[] walk : new Point[][]{
                {new Point(381, 426), new Point(-1347, 426)},
                {new Point(-1593, 426), new Point(844, 426)}}) {
            int regionId = graph.findRegionId(map, walk[0]);
            assertEquals(regionId, graph.findRegionId(map, walk[1]));
            AgentNavigationPathService.MovementPathSelection searched = searchWithoutCap(
                    graph, map, walk[0], regionId, walk[1]);
            AgentNavigationPathService.MovementPathSelection direct =
                    AgentNavigationPathService.directWalkSelection(graph, regionId, walk[0], walk[1]);

            assertNull(kept(searched.activeEdge(), regionId), "the mover discards any searched edge");
            assertNull(direct.activeEdge());
            assertTrue(direct.path().isEmpty());
            assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE, direct.completeness());
            assertEquals(AgentNavigationPathService.intraRegionTravelCost(
                    graph, regionId, walk[0], walk[1]), direct.outcome().cost());
        }

        // On the main street a portal can beat the walk, so that decision still searches.
        Point west = new Point(-900, 156);
        Point east = new Point(1400, 156);
        AgentNavigationGraph.Edge viaPortal = searchWithoutCap(graph, map, west, MAIN_STREET, east)
                .activeEdge();
        assertNotNull(kept(viaPortal, MAIN_STREET));
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, viaPortal.type);
    }

    @Test
    void sameRegionDecisionOffTheMainStreetWalksDirectlyWithoutSearching() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(381, 426);
        Point target = new Point(-1347, 426);
        AgentRuntimeEntry entry = entryAt(start, map);
        boolean monitoring = AgentPerformanceMonitor.enabled();
        try {
            AgentPerformanceMonitor.setEnabled(true);
            AgentNavigationTargetService.NavigationDirective directive =
                    AgentNavigationTargetService.resolveTarget(entry, target, true);

            assertEquals(target, directive.targetPos());
            assertEquals("same-region", AgentNavigationDebugStateRuntime.lastDecision(entry));
            assertEquals(graph.findRegionId(map, start), AgentNavigationTraceRuntime
                    .snapshot(entry, System.currentTimeMillis()).targetRegionId());
            assertEquals(0L, pathfinds(), "no search ran");

            AgentRuntimeEntry onMainStreet = entryAt(new Point(-900, 156), map);
            AgentNavigationTargetService.resolveTarget(onMainStreet, new Point(1400, 156), true);
            assertTrue(pathfinds() > 0L, "a main-street decision still searches");
        } finally {
            AgentPerformanceMonitor.setEnabled(monitoring);
        }
    }

    private static long pathfinds() {
        return AgentPerformanceMonitor.snapshot().stream()
                .filter(section -> section.section().startsWith("pathfind"))
                .mapToLong(AgentPerformanceMonitor.SectionSnapshot::count)
                .sum();
    }

    @Test
    void kerningLongRouteResumesTheRecoveredRouteInsteadOfRecoveringAfterEveryEdge() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(-61, -54);
        Point target = new Point(1546, -971);
        int targetRegionId = graph.findRegionId(map, target);
        Character agent = bot(21, map);

        AgentNavigationPathService.MovementPathSelection planned = select(
                graph, map, start, graph.findRegionId(map, start), targetRegionId, target, agent,
                edge -> true, edge -> 0);
        assertTrue(planned.recoverySearch());
        assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE, planned.completeness());

        int resumed = 0;
        List<AgentNavigationGraph.Edge> path = planned.path();
        for (int i = 1; i < path.size(); i++) {
            Point landing = path.get(i - 1).endPoint;
            int regionId = path.get(i).fromRegionId;
            AtomicInteger edgeTests = new AtomicInteger();
            AgentNavigationPathService.MovementPathSelection decision = select(
                    graph, map, landing, regionId, targetRegionId, target, agent,
                    counting(edgeTests), edge -> 0);
            AtomicInteger freshEdgeTests = new AtomicInteger();
            AgentNavigationPathService.MovementPathSelection fresh = select(
                    graph, map, landing, regionId, targetRegionId, target, null,
                    counting(freshEdgeTests), edge -> 0);

            assertEquals(fresh.path(), decision.path(), "same route after edge " + i);
            assertEquals(fresh.activeEdge(), decision.activeEdge());
            if (resumed(decision)) {
                resumed++;
                assertTrue(fresh.recoverySearch(), "resumed only where it would recover");
                AtomicInteger boundedEdgeTests = new AtomicInteger();
                AgentNavigationPathService.movementPathSelection(graph, map, landing, regionId,
                        targetRegionId, target, VARIATION,
                        AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                        AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS, false, null,
                        counting(boundedEdgeTests), edge -> 0);
                // Only the bounded search ran, plus one filter test per remaining edge.
                assertTrue(edgeTests.get() - boundedEdgeTests.get() <= decision.path().size(),
                        "edge " + i + ": " + edgeTests + " vs bounded " + boundedEdgeTests);
                assertTrue(freshEdgeTests.get() > 2 * edgeTests.get());
            }
        }
        assertTrue(resumed >= 3, "resumed decisions: " + resumed);
    }

    @Test
    void resumedRouteDoesNotTakeAnIntraRegionPortalTwice() {
        MapleMap map = mapWithPortal(new AtomicBoolean(true));
        AgentNavigationGraph.Edge portal = portal(1, 1, new Point(0, 100), new Point(1000, 100));
        AgentNavigationGraph.Edge jump = edge(1, 2, new Point(1000, 100), new Point(1200, 100), 10);
        AgentNavigationGraph graph = graph(List.of(ground(1, 0, 1000), ground(2, 1200, 1300)),
                Map.of(1, List.of(portal, jump)));
        Character agent = bot(31, map);
        Point target = new Point(1250, 100);

        AgentNavigationPathService.MovementPathSelection planned = cappedSelect(
                graph, map, new Point(10, 100), 1, 2, target, agent, null, null);
        AgentNavigationPathService.MovementPathSelection afterPortal = cappedSelect(
                graph, map, new Point(1000, 100), 1, 2, target, agent, null, null);

        assertEquals(List.of(portal, jump), planned.path());
        assertSame(jump, afterPortal.activeEdge(), "the Agent is past the portal it just took");
    }

    @Test
    void rememberedRouteIsResumedOnlyWhileTheInputsThatChoseItHold() {
        AtomicBoolean portalOpen = new AtomicBoolean(true);
        MapleMap map = mapWithPortal(portalOpen);
        AgentNavigationGraph.Edge first = edge(1, 2, new Point(100, 100), new Point(200, 100), 10);
        AgentNavigationGraph.Edge second = edge(2, 3, new Point(300, 100), new Point(400, 100), 10);
        AgentNavigationGraph.Edge third = portal(3, 4, new Point(500, 100), new Point(600, 100));
        AgentNavigationGraph.Edge shortcut = edge(2, 4, new Point(300, 100), new Point(650, 100), 5);
        // A dead end keeps the one-edge bounded search capping in region 3 even when the portal
        // is closed, so the remembered route itself has to notice the closed portal.
        AgentNavigationGraph.Edge deadEnd = edge(3, 5, new Point(500, 100), new Point(800, 100), 1_000);
        AgentNavigationGraph graph = graph(
                List.of(ground(1, 0, 100), ground(2, 200, 300), ground(3, 400, 500),
                        ground(4, 600, 700), ground(5, 800, 900)),
                Map.of(1, List.of(first), 2, List.of(second, shortcut), 3, List.of(third, deadEnd)));
        Point target = new Point(650, 100);
        Point atRegion2 = new Point(200, 100);
        AtomicBoolean shortcutAllowed = new AtomicBoolean(false);
        AtomicInteger secondPenalty = new AtomicInteger();
        Predicate<AgentNavigationGraph.Edge> filter = edge -> edge != shortcut || shortcutAllowed.get();
        ToIntFunction<AgentNavigationGraph.Edge> penalty = edge -> edge == second ? secondPenalty.get() : 0;
        // Every case plans with its own Agent: a case whose recovery exhausts (closed portal) is
        // remembered per Agent by the unreachable-recovery cache and would mask later cases.
        AtomicInteger agentIds = new AtomicInteger(40);
        Supplier<Character> planned = () -> {
            Character agent = bot(agentIds.incrementAndGet(), map);
            assertEquals(List.of(first, second, third), cappedSelect(
                    graph, map, new Point(50, 100), 1, 4, target, agent, filter, penalty).path());
            return agent;
        };
        Function<Character, AgentNavigationPathService.MovementPathSelection>
                inRegion2 = agent -> cappedSelect(graph, map, atRegion2, 2, 4, target, agent,
                filter, penalty);

        Character steady = planned.get();
        AgentNavigationPathService.MovementPathSelection resumed = inRegion2.apply(steady);
        assertTrue(resumed(resumed));
        assertEquals(List.of(second, third), resumed.path());
        assertTrue(resumed(inRegion2.apply(steady)), "a repeated decision in the same region resumes too");

        Character relaxedAgent = planned.get();
        shortcutAllowed.set(true);
        AgentNavigationPathService.MovementPathSelection relaxed = inRegion2.apply(relaxedAgent);
        assertFalse(resumed(relaxed), "a filtered edge the search skipped is allowed again");
        assertSame(shortcut, relaxed.activeEdge());
        shortcutAllowed.set(false);

        Character closedAgent = planned.get();
        portalOpen.set(false);
        assertFalse(resumed(inRegion2.apply(closedAgent)), "a remaining portal closed");
        portalOpen.set(true);

        Character penalizedAgent = planned.get();
        secondPenalty.set(40);
        assertFalse(resumed(inRegion2.apply(penalizedAgent)),
                "a remaining edge's reliability penalty changed");
        secondPenalty.set(0);

        Character movedAgent = planned.get();
        assertFalse(resumed(cappedSelect(graph, map, atRegion2, 2, 4, new Point(680, 100), movedAgent,
                filter, penalty)), "the target moved");

        Character rejectedAgent = planned.get();
        AgentNavigationPathService.forgetRecoveredRoute(rejectedAgent);
        assertFalse(resumed(inRegion2.apply(rejectedAgent)),
                "the mover dropped the route after a rejected or timed-out edge");

        planned.get();
        assertFalse(resumed(inRegion2.apply(bot(99, map))), "another Agent has its own route");

        Character completedAgent = planned.get();
        AgentNavigationPathService.movementPathSelection(graph, map, atRegion2, 2, 4, target, null,
                AgentNavigationPathService.MAX_EDGE_CHECKS, AgentNavigationPathService.MAX_EDGE_CHECKS,
                false, completedAgent, filter, penalty);
        assertFalse(resumed(inRegion2.apply(completedAgent)),
                "a decision that completed within the movement budget replaced the route");
    }

    @Test
    void aGuardLiftedBehindTheAgentKeepsTheRoute() {
        MapleMap map = mapWithPortal(new AtomicBoolean(true));
        AgentNavigationGraph.Edge first = edge(1, 2, new Point(100, 100), new Point(200, 100), 10);
        AgentNavigationGraph.Edge second = edge(2, 3, new Point(300, 100), new Point(400, 100), 10);
        AgentNavigationGraph.Edge third = edge(3, 4, new Point(500, 100), new Point(600, 100), 10);
        // Like the rope re-entry guard: blocked while the route was planned, lifted a moment later.
        AgentNavigationGraph.Edge guarded = edge(1, 3, new Point(0, 100), new Point(450, 100), 1);
        AgentNavigationGraph graph = graph(
                List.of(ground(1, 0, 100), ground(2, 200, 300), ground(3, 400, 500),
                        ground(4, 600, 700)),
                Map.of(1, List.of(first, guarded), 2, List.of(second), 3, List.of(third)));
        AtomicBoolean guardActive = new AtomicBoolean(true);
        Predicate<AgentNavigationGraph.Edge> filter = edge -> edge != guarded || !guardActive.get();
        Point target = new Point(650, 100);
        Character agent = bot(71, map);

        assertEquals(List.of(first, second, third), cappedSelect(
                graph, map, new Point(90, 100), 1, 4, target, agent, filter, null).path());
        guardActive.set(false);
        AgentNavigationPathService.MovementPathSelection past = cappedSelect(
                graph, map, new Point(200, 100), 2, 4, target, agent, filter, null);

        assertTrue(resumed(past), "region 1 is behind the Agent");
        assertEquals(List.of(second, third), past.path());
    }

    @Test
    void closedPortalThatReopensIsSearchedAgain() {
        AtomicBoolean portalOpen = new AtomicBoolean(false);
        MapleMap map = mapWithPortal(portalOpen);
        AgentNavigationGraph.Edge first = edge(1, 2, new Point(100, 100), new Point(200, 100), 10);
        AgentNavigationGraph.Edge second = edge(2, 3, new Point(300, 100), new Point(400, 100), 10);
        AgentNavigationGraph.Edge third = edge(3, 4, new Point(500, 100), new Point(600, 100), 10);
        AgentNavigationGraph.Edge portalShortcut = portal(2, 4, new Point(300, 100), new Point(650, 100));
        AgentNavigationGraph graph = graph(
                List.of(ground(1, 0, 100), ground(2, 200, 300), ground(3, 400, 500),
                        ground(4, 600, 700)),
                Map.of(1, List.of(first), 2, List.of(second, portalShortcut), 3, List.of(third)));
        Point target = new Point(650, 100);
        Character agent = bot(51, map);

        assertEquals(List.of(first, second, third), cappedSelect(
                graph, map, new Point(50, 100), 1, 4, target, agent, null, null).path());
        assertTrue(resumed(cappedSelect(graph, map, new Point(200, 100), 2, 4, target, agent, null, null)));
        portalOpen.set(true);
        AgentNavigationPathService.MovementPathSelection reopened = cappedSelect(
                graph, map, new Point(200, 100), 2, 4, target, agent, null, null);

        assertFalse(resumed(reopened));
        assertSame(portalShortcut, reopened.activeEdge());
    }

    private static boolean resumed(AgentNavigationPathService.MovementPathSelection selection) {
        return selection.recoverySearch() && selection.outcome().expandedNodes() == 0;
    }

    private static AgentNavigationGraph.Edge kept(AgentNavigationGraph.Edge selected, int regionId) {
        return AgentNavigationTargetService.leavesResolvedTargetRegion(selected, regionId, regionId)
                ? null : selected;
    }

    private static AgentNavigationPathService.MovementPathSelection searchWithoutCap(
            AgentNavigationGraph graph, MapleMap map, Point start, int regionId, Point target) {
        return AgentNavigationPathService.movementPathSelection(graph, map, start, regionId,
                regionId, target, VARIATION, Integer.MAX_VALUE, Integer.MAX_VALUE, false, null,
                null, null);
    }

    private static AgentNavigationPathService.MovementPathSelection select(
            AgentNavigationGraph graph,
            MapleMap map,
            Point start,
            int startRegionId,
            int targetRegionId,
            Point target,
            Character agent,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        return AgentNavigationPathService.movementPathSelection(graph, map, start, startRegionId,
                targetRegionId, target, VARIATION, AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                AgentNavigationPathService.MAX_EDGE_CHECKS, false, agent, edgeAllowed, edgePenalty);
    }

    /** A one-edge movement budget caps every synthetic search, so each decision needs recovery. */
    private static AgentNavigationPathService.MovementPathSelection cappedSelect(
            AgentNavigationGraph graph,
            MapleMap map,
            Point start,
            int startRegionId,
            int targetRegionId,
            Point target,
            Character agent,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        return AgentNavigationPathService.movementPathSelection(graph, map, start, startRegionId,
                targetRegionId, target, null, 1, AgentNavigationPathService.MAX_EDGE_CHECKS, false,
                agent, edgeAllowed, edgePenalty);
    }

    private static Predicate<AgentNavigationGraph.Edge> counting(AtomicInteger counter) {
        return edge -> {
            counter.incrementAndGet();
            return true;
        };
    }

    private static AgentRuntimeEntry entryAt(Point position, MapleMap map) {
        Character bot = bot(61, map);
        when(bot.getPosition()).thenReturn(new Point(position));
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        return entry;
    }

    private static MapleMap mapWithPortal(AtomicBoolean open) {
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(map.getPortal(OPEN_PORTAL_ID)).thenReturn(portal);
        when(portal.getPortalStatus()).thenAnswer(invocation -> open.get());
        return map;
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

    private static AgentNavigationGraph.Region ground(int id, int x1, int x2) {
        return new AgentNavigationGraph.Region(id, List.of(new AgentNavigationGraph.Segment(
                new Foothold(new Point(x1, 100), new Point(x2, 100), id))));
    }

    private static AgentNavigationGraph.Edge edge(int from, int to, Point start, Point end, int cost) {
        return new AgentNavigationGraph.Edge(from, to, AgentNavigationGraph.EdgeType.JUMP,
                start, end, 0, 0, 0, 0, 0, cost);
    }

    private static AgentNavigationGraph.Edge portal(int from, int to, Point start, Point end) {
        return new AgentNavigationGraph.Edge(from, to, AgentNavigationGraph.EdgeType.PORTAL,
                start, end, 0, OPEN_PORTAL_ID, 0, 0, 0, 0);
    }
}

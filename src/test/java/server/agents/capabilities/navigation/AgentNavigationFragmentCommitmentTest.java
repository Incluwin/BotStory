package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Agent sent to a platform no route reaches (Kerning City's drop-only roofs) follows capped
 * closest-frontier fragments. Deciding afresh after every edge let the next fragment point back,
 * and the Agent jumped between two platforms forever; following each fragment to its end, which
 * lies strictly closer to the target than where its search began, cannot cycle.
 */
class AgentNavigationFragmentCommitmentTest {
    private static final int KERNING_CITY = 103000000;
    private static final int MAX_DECISIONS = 60;

    @Test
    void kerningAgentBoundForRoof75StopsCloserInsteadOfJumpingBackAndForth() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(2387, -204);
        Point roof = new Point(-1027, -342);
        AgentTravelVariationRuntime.RouteVariation variation =
                new AgentTravelVariationRuntime.RouteVariation(0L, 1.30d);
        assertEquals(104, graph.findRegionId(map, start));
        assertEquals(75, graph.findRegionId(map, roof));

        assertHeadsForTheRoofWithoutCycling(graph, map, start, roof, variation, bot(8_101, map));
    }

    @Test
    void kerningFastAgentBoundForRoof80FollowsItsFragmentPastASearchThatExhausts() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(
                map, new AgentMovementProfile(135, 115));
        Point start = new Point(271, -97);
        Point roof = new Point(-2238, -337);
        AgentTravelVariationRuntime.RouteVariation variation =
                new AgentTravelVariationRuntime.RouteVariation(39L, 1.30d);
        assertEquals(117, graph.findRegionId(map, start));
        assertEquals(80, graph.findRegionId(map, roof));

        List<Decision> decisions = assertHeadsForTheRoofWithoutCycling(
                graph, map, start, roof, variation, bot(8_102, map));

        // On the main street every state reachable from there fits the movement budget and none
        // reaches the roof, so the search completes with no route. The Agent is part-way along a
        // fragment that still ends closer, and goes on instead of stopping there.
        boolean exhaustedAlongFragment = false;
        for (Decision decision : decisions) {
            if (resumed(decision.selection())) {
                AgentNavigationPathService.SearchOutcome search = AgentNavigationPathService.runSearch(
                        graph, map, decision.position(), decision.regionId(), 80, roof,
                        "maple-island-varied", false, false,
                        AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS, variation);
                exhaustedAlongFragment |= !search.capped() && !search.reached();
            }
        }
        assertTrue(exhaustedAlongFragment, "a search along the way exhausted");
    }

    @Test
    void fragmentResumesAfterTheEdgeLastGivenEvenWhereItCrossesARegionTwice() {
        MapleMap map = mock(MapleMap.class);
        AgentNavigationGraph graph = graph(List.of(ground(1, 0, 100), ground(2, 200, 2000),
                ground(3, 300, 1800), ground(4, 2100, 2200), ground(7, 3000, 3100)), Map.of());
        AgentNavigationGraph.Edge onto2 = edge(1, 2, new Point(90, 100), new Point(210, 100));
        AgentNavigationGraph.Edge up = edge(2, 3, new Point(250, 100), new Point(310, 100));
        AgentNavigationGraph.Edge down = edge(3, 2, new Point(1790, 100), new Point(1900, 100));
        AgentNavigationGraph.Edge onto4 = edge(2, 4, new Point(1990, 100), new Point(2110, 100));
        List<AgentNavigationGraph.Edge> fragment = List.of(onto2, up, down, onto4);
        Character agent = bot(8_103, map);
        AgentNavigationPathService.RecoveredRouteMemory.Key key = key(graph, 8_103, new Point(3050, 100));
        AgentNavigationPathService.CappedFragmentMemory memory =
                new AgentNavigationPathService.CappedFragmentMemory();

        memory.remember(key, capped(fragment));
        assertEquals(List.of(onto2, up, down, onto4), resume(memory, key, map, agent, 1),
                "a decision before the first edge is taken");
        assertEquals(List.of(up, down, onto4), resume(memory, key, map, agent, 2));
        assertEquals(List.of(up, down, onto4), resume(memory, key, map, agent, 2),
                "a repeated decision before the edge is taken");
        assertEquals(List.of(down, onto4), resume(memory, key, map, agent, 3));
        assertEquals(List.of(onto4), resume(memory, key, map, agent, 2),
                "back on region 2, past the edge that left it first");
        assertNull(memory.resume(key, map, agent, 4, edge -> true), "the fragment ended");
        assertNull(memory.resume(key, map, agent, 2, edge -> true), "and was dropped");

        memory.remember(key, capped(fragment));
        assertEquals(List.of(down, onto4), resume(memory, key, map, agent, 3),
                "decisions the Agent skipped leave it in the one region a later edge leaves");

        memory.remember(key, capped(fragment));
        assertNull(memory.resume(key, map, agent, 7, edge -> true), "off the fragment");

        memory.remember(key, capped(fragment));
        assertNull(memory.resume(key(graph, 8_103, new Point(3080, 100)), map, agent, 2, edge -> true),
                "the target moved");

        memory.remember(key, capped(fragment));
        assertNull(memory.resume(key, map, agent, 2, edge -> edge != down),
                "a remaining edge is no longer allowed");

        memory.remember(key, capped(fragment));
        memory.forget(8_103, "edge-failed");
        assertNull(memory.resume(key, map, agent, 2, edge -> true), "the mover dropped it");

        memory.remember(key, capped(List.of()));
        assertNull(memory.resume(key, map, agent, 1, edge -> true), "an empty fragment is not kept");
    }

    @Test
    void failedEdgeOrCompletedSearchDropsTheFragment() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point start = new Point(2387, -204);
        Point roof = new Point(-1027, -342);
        AgentTravelVariationRuntime.RouteVariation variation =
                new AgentTravelVariationRuntime.RouteVariation(0L, 1.30d);
        Character agent = bot(8_104, map);
        AgentNavigationGraph.Edge first = decide(graph, map, start, 104, roof, variation, agent).activeEdge();
        Point landing = new Point(first.endPoint);
        int regionId = first.toRegionId;

        assertTrue(resumed(decide(graph, map, landing, regionId, roof, variation, agent)));
        AgentNavigationPathService.forgetRecoveredRoute(agent);
        assertFalse(resumed(decide(graph, map, landing, regionId, roof, variation, agent)),
                "a rejected or timed-out edge makes the Agent decide afresh");

        assertTrue(resumed(decide(graph, map, landing, regionId, roof, variation, agent)));
        AgentNavigationPathService.movementPathSelection(graph, map, landing, regionId, 75, roof,
                variation, AgentNavigationPathService.MAX_EDGE_CHECKS,
                AgentNavigationPathService.MAX_EDGE_CHECKS, false, agent, edge -> true, edge -> 0);
        assertFalse(resumed(decide(graph, map, landing, regionId, roof, variation, agent)),
                "a decision the movement budget completed replaced the fragment");
    }

    private record Decision(Point position,
                            int regionId,
                            AgentNavigationPathService.MovementPathSelection selection) {
    }

    /**
     * Replays the mover (one decision per edge, landing where each edge ends) with and without an
     * Agent to remember fragments, and checks that only the remembering one stops, having followed
     * every fragment to its end, each end closer to the target than the one before.
     */
    private static List<Decision> assertHeadsForTheRoofWithoutCycling(
            AgentNavigationGraph graph,
            MapleMap map,
            Point start,
            Point roof,
            AgentTravelVariationRuntime.RouteVariation variation,
            Character agent) {
        int startRegionId = graph.findRegionId(map, start);
        int roofRegionId = graph.findRegionId(map, roof);
        assertFalse(graph.canReach(startRegionId, roofRegionId), "no route reaches the roof");

        List<Decision> forgetful = walk(graph, map, start, roof, variation, null);
        assertNotNull(forgetful.get(forgetful.size() - 1).selection().activeEdge(),
                "deciding afresh after every edge never stops");
        assertTrue(revisitsALanding(forgetful), "it jumps back and forth between platforms");

        List<Decision> decisions = walk(graph, map, start, roof, variation, agent);
        Decision last = decisions.get(decisions.size() - 1);
        assertNull(last.selection().activeEdge(), "the Agent stops");
        assertFalse(revisitsALanding(decisions));
        assertTrue(last.position().distance(roof) < start.distance(roof),
                "it stops closer to the roof than it started: " + last.position());
        Point fragmentEnd = null;
        int fragments = 0;
        for (Decision decision : decisions) {
            AgentNavigationPathService.MovementPathSelection selection = decision.selection();
            if (resumed(selection) || selection.path().isEmpty()) {
                continue;
            }
            fragments++;
            assertEquals(AgentNavigationPathService.RouteCompleteness.PARTIAL, selection.completeness());
            if (fragmentEnd != null) {
                assertEquals(fragmentEnd, decision.position(), "the previous fragment was followed to its end");
            }
            Point end = selection.path().get(selection.path().size() - 1).endPoint;
            assertTrue(end.distance(roof) < decision.position().distance(roof),
                    "a fragment ends closer to the roof than where its search began");
            fragmentEnd = end;
        }
        assertTrue(fragments >= 1);
        assertTrue(decisions.stream().anyMatch(decision -> resumed(decision.selection())),
                "later decisions resume the fragment");
        return decisions;
    }

    private static List<Decision> walk(AgentNavigationGraph graph,
                                       MapleMap map,
                                       Point start,
                                       Point target,
                                       AgentTravelVariationRuntime.RouteVariation variation,
                                       Character agent) {
        List<Decision> decisions = new ArrayList<>();
        Point position = start;
        int regionId = graph.findRegionId(map, start);
        for (int i = 0; i < MAX_DECISIONS; i++) {
            AgentNavigationPathService.MovementPathSelection selection =
                    decide(graph, map, position, regionId, target, variation, agent);
            decisions.add(new Decision(position, regionId, selection));
            AgentNavigationGraph.Edge edge = selection.activeEdge();
            if (edge == null) {
                break;
            }
            position = new Point(edge.endPoint);
            regionId = edge.toRegionId;
        }
        return decisions;
    }

    private static AgentNavigationPathService.MovementPathSelection decide(
            AgentNavigationGraph graph,
            MapleMap map,
            Point position,
            int regionId,
            Point target,
            AgentTravelVariationRuntime.RouteVariation variation,
            Character agent) {
        return AgentNavigationPathService.movementPathSelection(graph, map, position, regionId,
                graph.findRegionId(map, target), target, variation,
                AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS,
                AgentNavigationPathService.MAX_EDGE_CHECKS, false, agent, edge -> true, edge -> 0);
    }

    private static boolean revisitsALanding(List<Decision> decisions) {
        Set<String> landings = new HashSet<>();
        for (Decision decision : decisions) {
            if (!landings.add(decision.regionId() + "@" + decision.position())) {
                return true;
            }
        }
        return false;
    }

    private static boolean resumed(AgentNavigationPathService.MovementPathSelection selection) {
        return !selection.path().isEmpty() && selection.outcome().expandedNodes() == 0;
    }

    private static List<AgentNavigationGraph.Edge> resume(
            AgentNavigationPathService.CappedFragmentMemory memory,
            AgentNavigationPathService.RecoveredRouteMemory.Key key,
            MapleMap map,
            Character agent,
            int regionId) {
        AgentNavigationPathService.MovementPathSelection selection =
                memory.resume(key, map, agent, regionId, edge -> true);
        assertNotNull(selection, "resumed in region " + regionId);
        assertFalse(selection.recoverySearch());
        assertEquals(AgentNavigationPathService.RouteCompleteness.PARTIAL, selection.completeness());
        assertEquals(4, selection.outcome().finalRegionId());
        return selection.path();
    }

    private static AgentNavigationPathService.SearchOutcome capped(List<AgentNavigationGraph.Edge> path) {
        return new AgentNavigationPathService.SearchOutcome(path, Integer.MAX_VALUE, 5, false,
                false, true, !path.isEmpty(), path.isEmpty() ? 1 : 4, 0L);
    }

    private static AgentNavigationPathService.RecoveredRouteMemory.Key key(
            AgentNavigationGraph graph, int agentId, Point target) {
        return new AgentNavigationPathService.RecoveredRouteMemory.Key(
                graph, agentId, 7, target, null, false, true);
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

    private static AgentNavigationGraph.Edge edge(int from, int to, Point start, Point end) {
        return new AgentNavigationGraph.Edge(from, to, AgentNavigationGraph.EdgeType.JUMP,
                start, end, 0, 0, 0, 0, 0, 10);
    }
}

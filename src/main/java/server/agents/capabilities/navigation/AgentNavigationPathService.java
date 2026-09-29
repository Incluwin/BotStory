package server.agents.capabilities.navigation;

import client.Character;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.monitoring.AgentPerformanceMonitor;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.maps.Portal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Agent-owned seam for navigation path search while the legacy search body migrates.
 */
public final class AgentNavigationPathService {
    private static final Logger log = LoggerFactory.getLogger(AgentNavigationPathService.class);
    private static final int NO_MOVEMENT_WALK_TOLERANCE = config.AgentTuning.intValue("server.agents.capabilities.navigation.AgentNavigationPathService.NO_MOVEMENT_WALK_TOLERANCE");
    private static final long PORTAL_USE_COOLDOWN_MS = config.AgentTuning.longValue("server.agents.capabilities.navigation.AgentNavigationPathService.PORTAL_USE_COOLDOWN_MS");
    private static final long SLOW_PATHFIND_WARN_NS = config.AgentTuning.longValue("server.agents.capabilities.navigation.AgentNavigationPathService.SLOW_PATHFIND_WARN_NS");
    static final int MAX_EDGE_CHECKS = config.AgentTuning.intValue(
            "server.agents.capabilities.navigation.AgentNavigationPathService.MAX_EDGE_CHECKS");
    // Live movement can continue from a closest-frontier route on the next decision pass.
    // Bound only that path so dense town graphs cannot consume several 50 ms movement slots;
    // scoring and diagnostic searches retain their existing accuracy budget.
    static final int MOVEMENT_MAX_EDGE_CHECKS = config.AgentTuning.intValue(
            "server.agents.capabilities.navigation.AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS");
    // Skips search states that an already expanded state on the same region provably dominates.
    // The result is identical; only wasted edge scans (and budget caps they caused) go away.
    static final boolean DOMINANCE_PRUNING = config.AgentTuning.booleanValue(
            "server.agents.capabilities.navigation.AgentNavigationPathService.DOMINANCE_PRUNING");
    // How long an exhausted movement recovery search is trusted before it may run again. Live
    // reachability can change (portal status, per-Agent edge suppression), so this stays short.
    static final long UNREACHABLE_RECOVERY_TTL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.navigation.AgentNavigationPathService.UNREACHABLE_RECOVERY_TTL_MS");
    private static final UnreachableRecoveryCache UNREACHABLE_RECOVERIES = new UnreachableRecoveryCache();
    // Resumes the remaining route of an Agent's last complete recovery search instead of
    // repeating the bounded and recovery searches at every later movement decision.
    static final boolean RECOVERED_ROUTE_REUSE = config.AgentTuning.booleanValue(
            "server.agents.capabilities.navigation.AgentNavigationPathService.RECOVERED_ROUTE_REUSE");
    private static final RecoveredRouteMemory RECOVERED_ROUTES = new RecoveredRouteMemory();
    // Follows a capped closest-frontier fragment to its end when no complete route is found,
    // instead of deciding again after every edge, where the next fragment can point back.
    static final boolean CAPPED_FRAGMENT_COMMITMENT = config.AgentTuning.booleanValue(
            "server.agents.capabilities.navigation.AgentNavigationPathService.CAPPED_FRAGMENT_COMMITMENT");
    private static final CappedFragmentMemory CAPPED_FRAGMENTS = new CappedFragmentMemory();

    private static final boolean USE_ZERO_HEURISTIC = false;

    private AgentNavigationPathService() {
    }

    private static final class SearchNode {
        final SearchState state;
        final int cost;
        final int score;

        SearchNode(SearchState state, int cost, int score) {
            this.state = state;
            this.cost = cost;
            this.score = score;
        }
    }

    // viaPortal: true when this state was reached by a PORTAL edge. It distinguishes "arrived here
    // by teleport" from "arrived by walk/jump/etc." so the search can charge the portal cooldown to
    // a portal that chains straight off another portal.
    private record SearchState(int regionId, Point point, boolean viaPortal) {
    }

    /**
     * Same-region dominance over the (region, entry point) search states.
     *
     * <p>Drop-dense maps land hundreds of distinct entry points on one platform (Kerning PQ
     * stage 1 has ~1,600 states over 28 regions), and every state rescans all of its region's
     * outgoing edges. Walking inside a region costs {@code round(c * d)} for a distance
     * {@code d} along one axis (x on ground, y on a rope), so a state reached at cost {@code g}
     * is dominated when an already expanded, non-portal state on the same region was reached at
     * a cost {@code g'} with {@code g' + ceil(c * d(between)) <= g}: since
     * {@code round(c * (a + b)) <= ceil(c * a) + round(c * b)}, every edge taken from the
     * dominated state is then at least as cheap from the expanded one, which has already relaxed
     * it, and the search rejects ties, so skipping the state leaves the result unchanged. The
     * bound is tight: a landing that exactly ties with walking there along the platform is
     * dominated (a drop fan from a parallel platform lands one such state every few pixels; a
     * Kerning City search from region 113 expanded the 947-edge main street 194 times, 164 of
     * them such ties). Portal arrivals are never recorded because chaining a portal from its
     * exit adds a cooldown that the dominated state would not pay. Route variation stretches a
     * transition by at most {@code maxRouteStretch}, so the slack is stretched by the same bound.
     *
     * <p>The same argument prunes the edges an undominated state {@code s} scans. For an edge
     * that starts at or beyond a recorded state {@code e}, on the side away from {@code s}, the
     * walk from {@code s} is the walk to {@code e} plus the walk on from {@code e}, less at most
     * 1 ms of rounding. So when {@code e} cost no more than {@code s} and lies at least 1 ms
     * away, {@code e} (or, by the same argument, a recorded state beyond it) has already relaxed
     * that edge at least as cheaply. Only the edges strictly between the nearest such states on
     * either side are scanned, in their list order.
     */
    private static final class RegionDominance {
        private final AgentNavigationGraph graph;
        private final double stretch;
        private final Map<Integer, TreeMap<Integer, Integer>> expandedCostByRegion = new HashMap<>();

        RegionDominance(AgentNavigationGraph graph,
                        AgentTravelVariationRuntime.RouteVariation routeVariation) {
            this.graph = graph;
            this.stretch = routeVariation == null
                    ? 1.0d
                    : Math.max(1.0d, routeVariation.maxRouteStretch());
        }

        boolean dominated(SearchState state, int cost) {
            TreeMap<Integer, Integer> expanded = expandedCostByRegion.get(state.regionId);
            if (expanded == null) {
                return false;
            }
            boolean rope = ropeRegion(state.regionId);
            int axis = rope ? state.point.y : state.point.x;
            return dominates(expanded.floorEntry(axis), state, rope, cost)
                    || dominates(expanded.ceilingEntry(axis), state, rope, cost);
        }

        void expanded(SearchState state, int cost) {
            if (state.viaPortal) {
                return;
            }
            int axis = ropeRegion(state.regionId) ? state.point.y : state.point.x;
            expandedCostByRegion.computeIfAbsent(state.regionId, ignored -> new TreeMap<>())
                    .merge(axis, cost, Math::min);
        }

        /**
         * Indices into the region's outgoing list of the edges an undominated state can still
         * relax, ascending, or null to scan them all.
         */
        int[] edgesToScan(SearchState state, int cost) {
            TreeMap<Integer, Integer> expanded = expandedCostByRegion.get(state.regionId);
            if (expanded == null) {
                return null;
            }
            boolean rope = ropeRegion(state.regionId);
            int axis = rope ? state.point.y : state.point.x;
            Map.Entry<Integer, Integer> below = covering(expanded.lowerEntry(axis), state, rope, cost);
            Map.Entry<Integer, Integer> above = covering(expanded.higherEntry(axis), state, rope, cost);
            if (below == null && above == null) {
                return null;
            }
            AgentNavigationGraph.OutgoingAxisOrder order = graph.outgoingAxisOrder(state.regionId);
            int from = below == null ? 0 : order.firstAfter(below.getKey());
            int to = above == null ? order.size() : order.firstAtOrAfter(above.getKey());
            int[] scan = new int[Math.max(0, to - from)];
            for (int i = 0; i < scan.length; i++) {
                scan[i] = order.index(from + i);
            }
            // List order keeps tie-breaking between equal-cost relaxations unchanged.
            Arrays.sort(scan);
            return scan;
        }

        // The recorded state, when it has already relaxed every edge beyond it.
        private Map.Entry<Integer, Integer> covering(Map.Entry<Integer, Integer> expanded,
                                                     SearchState state,
                                                     boolean rope,
                                                     int cost) {
            if (expanded == null || expanded.getValue() > cost) {
                return null;
            }
            return intraRegionTravelCost(graph, state.regionId,
                    pointAt(state, rope, expanded.getKey()), state.point) >= 1 ? expanded : null;
        }

        private boolean dominates(Map.Entry<Integer, Integer> expanded,
                                  SearchState state,
                                  boolean rope,
                                  int cost) {
            if (expanded == null) {
                return false;
            }
            double travel = unroundedIntraRegionTravelCost(
                    graph, state.regionId, pointAt(state, rope, expanded.getKey()), state.point);
            long slack = (long) Math.ceil(Math.ceil(travel) * stretch);
            return (long) expanded.getValue() + slack <= cost;
        }

        private static Point pointAt(SearchState state, boolean rope, int axis) {
            return rope ? new Point(state.point.x, axis) : new Point(axis, state.point.y);
        }

        private boolean ropeRegion(int regionId) {
            AgentNavigationGraph.Region region = graph.getRegion(regionId);
            return region != null && region.isRopeRegion;
        }
    }

    /**
     * Short-lived memory of movement recovery searches that exhausted every reachable state
     * without reaching the target region.
     *
     * <p>Whether a region is reachable does not depend on the start point inside the start
     * region, the target point inside the target region, or route variation (which only rescales
     * costs). It does depend on the graph, the Agent's map instance and edge reliability state
     * (both covered by the Agent id), whether movement-skill edges were usable, and whether a
     * per-Agent edge filter applied (an unfiltered search is a superset of a filtered one). Entries
     * expire after a TTL because portal status and edge suppression change at runtime.
     */
    static final class UnreachableRecoveryCache {
        record Key(AgentNavigationGraph graph,
                   int agentId,
                   int startRegionId,
                   int targetRegionId,
                   boolean skillRoute,
                   boolean filtered) {
        }

        private final Map<Key, Long> expiresAtMs = new ConcurrentHashMap<>();

        boolean remembered(Key key, long nowMs) {
            Long expiresAt = expiresAtMs.get(key);
            if (expiresAt == null) {
                return false;
            }
            if (expiresAt > nowMs) {
                return true;
            }
            expiresAtMs.remove(key, expiresAt);
            return false;
        }

        void remember(Key key, long nowMs, long ttlMs) {
            if (ttlMs <= 0L) {
                return;
            }
            // Inserts are rare (one per exhausted search per TTL), so sweeping here keeps the map
            // at the live entries without a background task or a size bound.
            expiresAtMs.values().removeIf(expiresAt -> expiresAt <= nowMs);
            expiresAtMs.put(key, nowMs + ttlMs);
        }

        int size() {
            return expiresAtMs.size();
        }
    }

    /**
     * The remaining route of each Agent's last complete movement recovery search.
     *
     * <p>A long route in a dense town graph caps the bounded movement search from every point
     * along it, and the mover commits only the first edge of the recovered route, so every later
     * decision recovered the rest of the same route again (Kerning City: 75-88K edge checks after
     * each edge). Every suffix of that route is a complete route from the state it starts at, so
     * when the bounded search caps again, a decision whose Agent stands in the one region the next
     * remaining edge leaves resumes the route there instead of recovering. Decisions whose bounded
     * search completes are unchanged, and drop the route.
     *
     * <p>A route is resumed only while the inputs that chose it still hold: the same graph,
     * target, route variation, movement-skill availability, edge filter view and danger costs;
     * every remaining edge still allowed, usable (open portal, route overlay) and at the same
     * reliability penalty; and every edge the search found filtered out, closed or penalized that
     * leaves a region the remaining route passes through still so, since lifting it could make
     * another way on cheaper. Edges behind the Agent do not count: the mover's filter also holds
     * short reversal guards (the rope just left, a cycling edge), which lift within seconds of
     * the recovery that saw them. Otherwise the route is dropped and the decision recovers afresh.
     * The mover also drops it when an edge is rejected or times out. Each drop is counted in the
     * performance monitor as {@code route-memory-miss-<reason>}.
     */
    static final class RecoveredRouteMemory {
        record Key(AgentNavigationGraph graph,
                   int agentId,
                   int targetRegionId,
                   Point targetPos,
                   AgentTravelVariationRuntime.RouteVariation variation,
                   boolean skillRoute,
                   boolean filtered) {
        }

        /**
         * The recovery search's edge filter and penalty, recording the edges whose state could
         * later make another route cheaper: filtered out, closed (portals, and skill edges when
         * skills are usable at all) or penalized.
         */
        static final class PlanningView {
            private final AgentNavigationGraph graph;
            private final MapleMap map;
            private final Character agent;
            private final boolean skillRoute;
            private final Predicate<AgentNavigationGraph.Edge> edgeAllowed;
            private final ToIntFunction<AgentNavigationGraph.Edge> edgePenalty;
            private final Set<AgentNavigationGraph.Edge> filtered = new HashSet<>();
            private final Set<AgentNavigationGraph.Edge> closed = new HashSet<>();
            private final Map<AgentNavigationGraph.Edge, Integer> penalties = new HashMap<>();

            PlanningView(AgentNavigationGraph graph,
                         MapleMap map,
                         Character agent,
                         boolean skillRoute,
                         Predicate<AgentNavigationGraph.Edge> edgeAllowed,
                         ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
                this.graph = graph;
                this.map = map;
                this.agent = agent;
                this.skillRoute = skillRoute;
                this.edgeAllowed = edgeAllowed;
                this.edgePenalty = edgePenalty;
            }

            boolean allows(AgentNavigationGraph.Edge edge) {
                if (edgeAllowed != null && !edgeAllowed.test(edge)) {
                    filtered.add(edge);
                    return false;
                }
                if (liveUsability(edge, skillRoute) && !isEdgeUsable(graph, map, agent, edge, false)) {
                    closed.add(edge);
                }
                return true;
            }

            int penalty(AgentNavigationGraph.Edge edge) {
                int penalty = RecoveredRouteMemory.penalty(edgePenalty, edge);
                if (penalty != 0) {
                    penalties.put(edge, penalty);
                }
                return penalty;
            }
        }

        private record Route(Key key,
                             List<AgentNavigationGraph.Edge> path,
                             Map<Integer, Integer> dangerCosts,
                             Set<AgentNavigationGraph.Edge> filtered,
                             Set<AgentNavigationGraph.Edge> closed,
                             Map<AgentNavigationGraph.Edge, Integer> penalties,
                             int cost,
                             boolean capped,
                             int nextIndex) {
            Route resumedAt(int index) {
                return new Route(key, path, dangerCosts, filtered, closed, penalties,
                        cost, capped, index);
            }
        }

        private final Map<Integer, Route> routes = new ConcurrentHashMap<>();

        void remember(Key key,
                      SearchOutcome outcome,
                      Map<Integer, Integer> dangerCosts,
                      PlanningView view) {
            routes.put(key.agentId(), new Route(key, List.copyOf(outcome.path()),
                    Map.copyOf(dangerCosts), Set.copyOf(view.filtered), Set.copyOf(view.closed),
                    Map.copyOf(view.penalties), outcome.cost(), outcome.capped(), 0));
        }

        MovementPathSelection resume(Key key,
                                     MapleMap map,
                                     Character agent,
                                     int startRegionId,
                                     Map<Integer, Integer> dangerCosts,
                                     Predicate<AgentNavigationGraph.Edge> edgeAllowed,
                                     ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
            Route route = routes.get(key.agentId());
            if (route == null) {
                return null;
            }
            int index = route.key().equals(key)
                    ? nextEdgeFrom(route.path(), route.nextIndex(), startRegionId)
                    : -1;
            String miss = !route.key().equals(key) ? "other-target"
                    : index < 0 ? (leaves(route.path(), route.nextIndex(), startRegionId)
                    ? "region-left-twice" : "off-route")
                    : !route.dangerCosts().equals(dangerCosts) ? "danger-changed"
                    : changedInput(route, index, map, agent, edgeAllowed, edgePenalty);
            if (miss != null) {
                routes.remove(key.agentId(), route);
                AgentPerformanceMonitor.record("route-memory-miss-" + miss, 0L);
                return null;
            }
            routes.put(key.agentId(), route.resumedAt(index));
            List<AgentNavigationGraph.Edge> suffix = route.path().subList(index, route.path().size());
            return selection(new SearchOutcome(suffix, route.cost(), 0, usesPortal(suffix),
                    true, route.capped(), false, key.targetRegionId(), 0L), true);
        }

        void forget(int agentId, String reason) {
            if (routes.remove(agentId) != null) {
                AgentPerformanceMonitor.record("route-memory-miss-" + reason, 0L);
            }
        }

        int size() {
            return routes.size();
        }

        // The remaining edge that leaves the Agent's region, or -1 unless exactly one does. A route
        // may leave one region twice (an intra-region portal, a hop onto a rope and back); the
        // region alone cannot tell whether the Agent already took the first of those edges, and
        // resuming at the wrong one would send it round the same loop again.
        private static int nextEdgeFrom(List<AgentNavigationGraph.Edge> path,
                                        int nextIndex,
                                        int startRegionId) {
            int found = -1;
            for (int i = nextIndex; i < path.size(); i++) {
                if (path.get(i).fromRegionId == startRegionId) {
                    if (found >= 0) {
                        return -1;
                    }
                    found = i;
                }
            }
            return found;
        }

        private static boolean leaves(List<AgentNavigationGraph.Edge> path,
                                      int nextIndex,
                                      int regionId) {
            for (int i = nextIndex; i < path.size(); i++) {
                if (path.get(i).fromRegionId == regionId) {
                    return true;
                }
            }
            return false;
        }

        // Why the remembered route may no longer be the one the recovery search would choose, or
        // null while it still is.
        private static String changedInput(Route route,
                                           int index,
                                           MapleMap map,
                                           Character agent,
                                           Predicate<AgentNavigationGraph.Edge> edgeAllowed,
                                           ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
            AgentNavigationGraph graph = route.key().graph();
            for (int i = index; i < route.path().size(); i++) {
                AgentNavigationGraph.Edge edge = route.path().get(i);
                if ((edgeAllowed != null && !edgeAllowed.test(edge))
                        || !AgentNavigationRouteOverlayPolicy.allows(
                                graph, route.key().targetRegionId(), edge)
                        || !isEdgeUsable(graph, map, agent, edge, false)
                        || penalty(edgePenalty, edge) != route.penalties().getOrDefault(edge, 0)) {
                    return "remaining-edge-changed";
                }
            }
            Set<Integer> ahead = new HashSet<>();
            for (int i = index; i < route.path().size(); i++) {
                ahead.add(route.path().get(i).fromRegionId);
            }
            for (AgentNavigationGraph.Edge edge : route.filtered()) {
                if (ahead.contains(edge.fromRegionId) && (edgeAllowed == null || edgeAllowed.test(edge))) {
                    return "alternative-allowed";
                }
            }
            for (AgentNavigationGraph.Edge edge : route.closed()) {
                if (ahead.contains(edge.fromRegionId) && isEdgeUsable(graph, map, agent, edge, false)) {
                    return "alternative-opened";
                }
            }
            for (Map.Entry<AgentNavigationGraph.Edge, Integer> penalized
                    : route.penalties().entrySet()) {
                if (ahead.contains(penalized.getKey().fromRegionId)
                        && penalty(edgePenalty, penalized.getKey()) != penalized.getValue()) {
                    return "alternative-penalty-changed";
                }
            }
            return null;
        }

        private static boolean liveUsability(AgentNavigationGraph.Edge edge, boolean skillRoute) {
            return switch (edge.type) {
                case PORTAL -> true;
                case FLASH_JUMP, TELEPORT -> skillRoute;
                case WALK, JUMP, DROP, CLIMB -> false;
            };
        }

        private static int penalty(ToIntFunction<AgentNavigationGraph.Edge> edgePenalty,
                                   AgentNavigationGraph.Edge edge) {
            return edgePenalty == null ? 0 : Math.max(0, edgePenalty.applyAsInt(edge));
        }
    }

    /**
     * The remaining part of the capped fragment each Agent follows when no complete route is found.
     *
     * <p>When the bounded movement search caps and nothing replaces its closest-frontier fragment
     * (the target region is unreachable in the directed graph, or the recovery search exhausted or
     * capped), the mover commits only the fragment's first edge and decides again where it lands.
     * That next search caps at another frontier and can point straight back: Kerning City Agents
     * sent to the drop-only roof regions 75 and 80 jumped between two platforms forever. A fragment
     * ends strictly closer (straight-line) to the target than the point its search started from, so
     * an Agent that follows every fragment to its end ends each one closer than the one before and
     * cannot cycle; it stops where a search from a fragment's end finds nothing closer.
     *
     * <p>So while no complete route is found, a decision resumes the Agent's fragment at its next
     * edge: the one after the edge it was last given when it stands where that edge leads, that
     * edge again when it has not left yet, or else the one remaining edge that leaves its region.
     * A search that exhausts part-way along does not stop the Agent either, since the rest of the
     * fragment still ends closer than where it began. Otherwise the decision commits to the new
     * bounded fragment. The fragment is dropped at its end, when the target, route variation,
     * movement-skill availability or edge filter view changes, when the Agent is off it, when a
     * remaining edge is filtered, closed or overlay-blocked, when an edge was rejected or timed
     * out, and when the Agent follows a complete route instead. Danger costs and reliability
     * penalties only rank routes, so they do not drop it; danger costs also leave out the Agent's
     * own region and so change as it walks. Each drop is counted in the performance monitor as
     * {@code fragment-memory-miss-<reason>}.
     */
    static final class CappedFragmentMemory {
        private record Fragment(RecoveredRouteMemory.Key key,
                                List<AgentNavigationGraph.Edge> path,
                                int nextIndex) {
            Fragment resumedAt(int index) {
                return new Fragment(key, path, index);
            }

            int finalRegionId() {
                return path.get(path.size() - 1).toRegionId;
            }
        }

        private final Map<Integer, Fragment> fragments = new ConcurrentHashMap<>();

        void remember(RecoveredRouteMemory.Key key, SearchOutcome bounded) {
            if (!bounded.path().isEmpty()) {
                fragments.put(key.agentId(), new Fragment(key, List.copyOf(bounded.path()), 0));
            }
        }

        MovementPathSelection resume(RecoveredRouteMemory.Key key,
                                     MapleMap map,
                                     Character agent,
                                     int startRegionId,
                                     Predicate<AgentNavigationGraph.Edge> edgeAllowed) {
            Fragment fragment = fragments.get(key.agentId());
            if (fragment == null) {
                return null;
            }
            boolean sameKey = fragment.key().equals(key);
            int index = sameKey ? nextEdge(fragment, startRegionId) : -1;
            String miss = !sameKey ? "other-target"
                    : index >= 0 ? (blocked(fragment, index, map, agent, edgeAllowed)
                    ? "remaining-edge-changed" : null)
                    : startRegionId == fragment.finalRegionId() ? "ended"
                    : RecoveredRouteMemory.leaves(fragment.path(), fragment.nextIndex() + 1, startRegionId)
                    ? "region-left-twice" : "off-route";
            if (miss != null) {
                fragments.remove(key.agentId(), fragment);
                AgentPerformanceMonitor.record("fragment-memory-miss-" + miss, 0L);
                return null;
            }
            fragments.put(key.agentId(), fragment.resumedAt(index));
            List<AgentNavigationGraph.Edge> suffix = fragment.path().subList(index, fragment.path().size());
            return selection(new SearchOutcome(suffix, Integer.MAX_VALUE, 0, usesPortal(suffix),
                    false, true, true, fragment.finalRegionId(), 0L), false);
        }

        void forget(int agentId, String reason) {
            if (fragments.remove(agentId) != null) {
                AgentPerformanceMonitor.record("fragment-memory-miss-" + reason, 0L);
            }
        }

        // The remaining edge the Agent takes next, or -1. The mover decides once per edge, so the
        // Agent either landed where the edge it was last given leads (a fragment may cross a main
        // street more than once, so its region alone would be ambiguous) or has not left yet.
        // After decisions it skipped, it must stand in the one region a later edge leaves. An
        // Agent in the region an intra-region portal leaves and enters counts as through it.
        private static int nextEdge(Fragment fragment, int startRegionId) {
            List<AgentNavigationGraph.Edge> path = fragment.path();
            int given = fragment.nextIndex();
            if (path.get(given).toRegionId == startRegionId) {
                return given + 1 < path.size() ? given + 1 : -1;
            }
            if (path.get(given).fromRegionId == startRegionId) {
                return given;
            }
            return RecoveredRouteMemory.nextEdgeFrom(path, given + 1, startRegionId);
        }

        // Whether the mover can no longer take a remaining edge.
        private static boolean blocked(Fragment fragment,
                                       int index,
                                       MapleMap map,
                                       Character agent,
                                       Predicate<AgentNavigationGraph.Edge> edgeAllowed) {
            AgentNavigationGraph graph = fragment.key().graph();
            for (int i = index; i < fragment.path().size(); i++) {
                AgentNavigationGraph.Edge edge = fragment.path().get(i);
                if ((edgeAllowed != null && !edgeAllowed.test(edge))
                        || !AgentNavigationRouteOverlayPolicy.allows(
                                graph, fragment.key().targetRegionId(), edge)
                        || !isEdgeUsable(graph, map, agent, edge, false)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record PathfindProfile(long elapsedNs,
                                   int expandedNodes,
                                   int staleNodes,
                                   int dominatedNodes,
                                   int edgeChecks,
                                   int usableEdges,
                                   int relaxations,
                                   int openPeak,
                                   int bestGoalCost,
                                   int resultEdges,
                                   boolean capped) {
    }

    public record SearchOutcome(List<AgentNavigationGraph.Edge> path,
                                int cost,
                                int expandedNodes,
                                boolean usesPortal,
                                boolean reached,
                                boolean capped,
                                boolean bestEffort,
                                int finalRegionId,
                                long elapsedMicroseconds) {
        public RouteCompleteness completeness() {
            if (reached) {
                return RouteCompleteness.COMPLETE;
            }
            return path.isEmpty() ? RouteCompleteness.UNREACHABLE : RouteCompleteness.PARTIAL;
        }
    }

    public record MovementPathSelection(List<AgentNavigationGraph.Edge> path,
                                        AgentNavigationGraph.Edge activeEdge,
                                        SearchOutcome outcome,
                                        boolean recoverySearch) {
        public MovementPathSelection {
            path = List.copyOf(path);
        }

        public RouteCompleteness completeness() {
            return outcome.completeness();
        }
    }

    public enum RouteCompleteness {
        COMPLETE,
        PARTIAL,
        UNREACHABLE
    }

    public record PathOptimality(int currentCost, int optimalCost, boolean currentUsesPortal,
                                 boolean optimalUsesPortal, int currentExpanded, int optimalExpanded) {
        public boolean reachable() {
            return currentCost != Integer.MAX_VALUE && optimalCost != Integer.MAX_VALUE;
        }

        public boolean suboptimal() {
            return reachable() && currentCost > optimalCost;
        }

        public int costDelta() {
            return reachable() ? currentCost - optimalCost : 0;
        }

        public boolean portalSkipped() {
            return suboptimal() && optimalUsesPortal && !currentUsesPortal;
        }
    }

    public static List<AgentNavigationGraph.Edge> findPath(AgentNavigationGraph graph,
                                                           Character bot,
                                                           int startRegionId,
                                                           int targetRegionId,
                                                           Point targetPos) {
        MapleMap map = bot.getMap();
        return runSearch(graph, map, bot.getPosition(), startRegionId, targetRegionId, targetPos,
                null, USE_ZERO_HEURISTIC, true, MAX_EDGE_CHECKS, null,
                AgentNavigationDangerCostService.intermediateRegionCosts(
                        graph, map, startRegionId, targetRegionId)).path();
    }

    public static List<AgentNavigationGraph.Edge> findPath(AgentNavigationGraph graph,
                                                           MapleMap map,
                                                           Point startPos,
                                                           int startRegionId,
                                                           int targetRegionId,
                                                           Point targetPos) {
        return findPath(graph, map, startPos, startRegionId, targetRegionId, targetPos, null);
    }

    public static List<AgentNavigationGraph.Edge> findPathForTargetScore(AgentNavigationGraph graph,
                                                                         MapleMap map,
                                                                         Point startPos,
                                                                         int startRegionId,
                                                                         int targetRegionId,
                                                                         Point targetPos) {
        return findPath(graph, map, startPos, startRegionId, targetRegionId, targetPos, "target-score");
    }

    /** Complete-result contract used by combat target acceptance and reliability-aware scoring. */
    public static SearchOutcome findRouteForTargetScore(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "target-score", USE_ZERO_HEURISTIC, true, MAX_EDGE_CHECKS,
                null, AgentNavigationDangerCostService.intermediateRegionCosts(
                        graph, map, startRegionId, targetRegionId),
                null, false, edgeAllowed, edgePenalty);
    }

    /**
     * Returns the cost of a complete, executable route, or {@code unreachableCost} when the
     * search only reached a closest frontier or its first live traversal edge cannot execute.
     */
    public static long reliableRouteCost(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            Point targetPos,
            int targetRegionId,
            long localTravelCost,
            AgentRuntimeEntry entry,
            Character agent,
            long unreachableCost) {
        if (graph == null || startPos == null || targetPos == null
                || startRegionId < 0 || targetRegionId < 0) {
            return unreachableCost;
        }
        if (startRegionId == targetRegionId) {
            return Math.max(0L, localTravelCost);
        }

        long nowMs = System.currentTimeMillis();
        ToIntFunction<AgentNavigationGraph.Edge> reliabilityPenalty =
                AgentNavigationEdgeReliabilityRuntime.edgePenalty(entry, graph.mapId, nowMs);
        SearchOutcome outcome = findRouteForTargetScore(
                graph, map, startPos, startRegionId, targetRegionId, targetPos,
                AgentNavigationEdgeReliabilityRuntime.edgeFilter(entry, graph.mapId, nowMs),
                reliabilityPenalty);
        if (completeRouteCost(outcome, unreachableCost) == unreachableCost
                || !firstEdgeExecutable(
                graph, entry, agent, startRegionId, startPos, outcome, nowMs)) {
            return unreachableCost;
        }

        long cost = 0L;
        for (AgentNavigationGraph.Edge edge : outcome.path()) {
            long penalty = AgentNavigationReliabilityConfig.influencesRouting()
                    ? Math.max(0, reliabilityPenalty.applyAsInt(edge)) : 0L;
            cost = saturatingAdd(cost, Math.max(0L, (long) edge.cost + penalty), unreachableCost);
        }
        return cost;
    }

    public static long completeRouteCost(SearchOutcome outcome, long unreachableCost) {
        if (outcome == null || outcome.completeness() != RouteCompleteness.COMPLETE) {
            return unreachableCost;
        }
        return Math.max(0L, outcome.cost());
    }

    public static boolean firstEdgeExecutable(
            AgentNavigationGraph graph,
            AgentRuntimeEntry entry,
            Character agent,
            int startRegionId,
            Point startPos,
            SearchOutcome outcome,
            long nowMs) {
        if (graph == null || entry == null || agent == null || startPos == null
                || outcome == null || outcome.completeness() != RouteCompleteness.COMPLETE) {
            return false;
        }
        if (outcome.path().isEmpty()) {
            return true;
        }
        AgentNavigationGraph.Edge firstEdge = collapseLeadingWalkEdges(outcome.path());
        if (firstEdge == null) {
            return true;
        }
        AgentNavigationEdgeValidationService.Result validation =
                AgentNavigationEdgeValidationService.validate(
                        graph, entry, agent, graph.mapId, startRegionId,
                        startPos, firstEdge, nowMs);
        return !validation.rejected();
    }

    private static long saturatingAdd(long left, long right, long cap) {
        if (left >= cap || right >= cap || left > cap - right) {
            return cap;
        }
        return left + right;
    }

    public static List<AgentNavigationGraph.Edge> findPathForRetreatProbe(AgentNavigationGraph graph,
                                                                          MapleMap map,
                                                                          Point startPos,
                                                                          int startRegionId,
                                                                          int targetRegionId,
                                                                          Point targetPos) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "retreat-probe", false, true).path();
    }

    public static List<AgentNavigationGraph.Edge> findPathForApproachProbe(AgentNavigationGraph graph,
                                                                           MapleMap map,
                                                                           Point startPos,
                                                                           int startRegionId,
                                                                           int targetRegionId,
                                                                           Point targetPos) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "approach-probe", false, true).path();
    }

    public static AgentNavigationGraph.Edge findNextEdge(AgentNavigationGraph graph,
                                                         Character bot,
                                                         int startRegionId,
                                                         int targetRegionId,
                                                         Point targetPos) {
        MapleMap map = bot.getMap();
        List<AgentNavigationGraph.Edge> path = movementPath(
                graph, bot, bot.getPosition(), startRegionId, targetRegionId, targetPos, null);
        if (path.isEmpty()) {
            return null;
        }
        return collapseLeadingWalkEdges(path);
    }

    public static AgentNavigationGraph.Edge findNextEdgeVaried(
            AgentNavigationGraph graph,
            Character bot,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation) {
        return findNextEdgeVaried(graph, bot, bot.getPosition(), startRegionId,
                targetRegionId, targetPos, variation);
    }

    public static AgentNavigationGraph.Edge findNextEdgeVaried(
            AgentNavigationGraph graph,
            Character bot,
            Point startPosition,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation) {
        List<AgentNavigationGraph.Edge> path = movementPath(
                graph, bot, startPosition, startRegionId, targetRegionId, targetPos, variation);
        return path.isEmpty() ? null : collapseLeadingWalkEdges(path);
    }

    public static AgentNavigationGraph.Edge findNextEdgeVaried(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation) {
        if (variation == null || variation.maxRouteStretch() <= 1.0d) {
            List<AgentNavigationGraph.Edge> path = movementPath(
                    graph, map, startPos, startRegionId, targetRegionId, targetPos, null);
            return path.isEmpty() ? null : collapseLeadingWalkEdges(path);
        }
        List<AgentNavigationGraph.Edge> path = movementPath(
                graph, map, startPos, startRegionId, targetRegionId, targetPos, variation);
        return path.isEmpty() ? null : collapseLeadingWalkEdges(path);
    }

    static AgentNavigationGraph.Edge findNextEdgeVaried(
            AgentNavigationGraph graph,
            Character bot,
            Point startPosition,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed) {
        return findNextEdgeSelectionVaried(
                graph, bot, startPosition, startRegionId, targetRegionId, targetPos,
                variation, edgeAllowed).activeEdge();
    }

    static MovementPathSelection findNextEdgeSelectionVaried(
            AgentNavigationGraph graph,
            Character bot,
            Point startPosition,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed) {
        return findNextEdgeSelectionVaried(graph, bot, startPosition, startRegionId,
                targetRegionId, targetPos, variation, edgeAllowed, null);
    }

    static MovementPathSelection findNextEdgeSelectionVaried(
            AgentNavigationGraph graph,
            Character bot,
            Point startPosition,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        return movementPathSelection(
                graph, bot.getMap(), startPosition, startRegionId, targetRegionId, targetPos,
                variation, MOVEMENT_MAX_EDGE_CHECKS, MAX_EDGE_CHECKS, true, bot,
                edgeAllowed, edgePenalty);
    }

    private static List<AgentNavigationGraph.Edge> movementPath(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation) {
        return movementPath(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                variation, MOVEMENT_MAX_EDGE_CHECKS, MAX_EDGE_CHECKS, true);
    }

    private static List<AgentNavigationGraph.Edge> movementPath(
            AgentNavigationGraph graph,
            Character bot,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation) {
        return movementPath(graph, bot.getMap(), startPos, startRegionId, targetRegionId, targetPos,
                variation, MOVEMENT_MAX_EDGE_CHECKS, MAX_EDGE_CHECKS, true, bot, null, null);
    }

    static List<AgentNavigationGraph.Edge> movementPath(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation,
            int movementBudget,
            int recoveryBudget,
            boolean instrument) {
        return movementPath(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                variation, movementBudget, recoveryBudget, instrument, null, null, null);
    }

    private static List<AgentNavigationGraph.Edge> movementPath(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation,
            int movementBudget,
            int recoveryBudget,
            boolean instrument,
            Character skillAgent,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        return movementPathSelection(graph, map, startPos, startRegionId, targetRegionId,
                targetPos, variation, movementBudget, recoveryBudget, instrument,
                skillAgent, edgeAllowed, edgePenalty).path();
    }

    static MovementPathSelection movementPathSelection(
            AgentNavigationGraph graph,
            MapleMap map,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos,
            AgentTravelVariationRuntime.RouteVariation variation,
            int movementBudget,
            int recoveryBudget,
            boolean instrument,
            Character skillAgent,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        String caller = variation == null ? "committed" : "maple-island-varied";
        Map<Integer, Integer> dangerCosts = AgentNavigationDangerCostService.intermediateRegionCosts(
                graph, map, startRegionId, targetRegionId);
        SearchOutcome bounded = runSearch(
                graph, map, startPos, startRegionId, targetRegionId, targetPos,
                caller, USE_ZERO_HEURISTIC, instrument, movementBudget,
                variation, dangerCosts, skillAgent, false, edgeAllowed, edgePenalty);
        if (bounded.reached() || movementBudget >= recoveryBudget) {
            // The Agent now follows this route, not a remembered one.
            if (skillAgent != null) {
                RECOVERED_ROUTES.forget(skillAgent.getId(), "search-completed");
                CAPPED_FRAGMENTS.forget(skillAgent.getId(), "search-completed");
            }
            return selection(bounded, false);
        }
        boolean skillRoute = skillAgent != null
                && AgentMovementSkillPolicy.canUseAnyActiveMovementSkill(skillAgent);
        // Both remembered routes, a recovered one and a capped fragment, hold for one Agent,
        // target and planning view.
        RecoveredRouteMemory.Key memoryKey = skillAgent == null
                || startRegionId == targetRegionId || targetPos == null
                ? null
                : new RecoveredRouteMemory.Key(graph, skillAgent.getId(), targetRegionId,
                new Point(targetPos), variation, skillRoute, edgeAllowed != null);
        if (!bounded.capped()) {
            // No route leads on from here, so no recovery can find one either. An Agent part-way
            // along a capped fragment still follows it to its end, which lies closer to the target
            // than where the fragment's search began; any other Agent stops, as before.
            if (skillAgent != null) {
                RECOVERED_ROUTES.forget(skillAgent.getId(), "search-completed");
            }
            return fragmentSelection(bounded, memoryKey, map, skillAgent, startRegionId,
                    edgeAllowed, caller, instrument);
        }

        // A capped closest-frontier path is only a fragment, not proof that it reaches the target.
        // Accepting that fragment makes dense maps replan after every edge; the next partial search
        // can choose an inverse transition and oscillate forever. Retry any incomplete capped search
        // with the accuracy budget, then keep the bounded fragment only if recovery found nothing.
        //
        // A recovery that exhausts the graph returns that same bounded fragment, so skip it when
        // it is known to exhaust: the target region is not reachable in the directed graph, or
        // this Agent's same search exhausted moments ago. Movement is agent-paced, so without
        // this an unreachable target re-ran a full 100K+ edge search on every decision.
        if (!skillRoute && !graph.canReach(startRegionId, targetRegionId)) {
            return fragmentSelection(bounded, memoryKey, map, skillAgent, startRegionId,
                    edgeAllowed, caller, instrument);
        }
        UnreachableRecoveryCache.Key unreachableKey = skillAgent == null
                ? null
                : new UnreachableRecoveryCache.Key(graph, skillAgent.getId(), startRegionId,
                targetRegionId, skillRoute, edgeAllowed != null);
        long nowMs = System.currentTimeMillis();
        if (unreachableKey != null && UNREACHABLE_RECOVERIES.remembered(unreachableKey, nowMs)) {
            return fragmentSelection(bounded, memoryKey, map, skillAgent, startRegionId,
                    edgeAllowed, caller, instrument);
        }
        // A long route caps the bounded search from every point along it, so each decision after
        // the first edge of a recovered route would recover the rest of that same route again.
        RecoveredRouteMemory.Key routeKey = RECOVERED_ROUTE_REUSE ? memoryKey : null;
        if (routeKey != null) {
            long resumeStartedAt = System.nanoTime();
            MovementPathSelection resumed = RECOVERED_ROUTES.resume(routeKey, map, skillAgent,
                    startRegionId, dangerCosts, edgeAllowed, edgePenalty);
            if (resumed != null) {
                if (instrument) {
                    AgentPerformanceMonitor.recordPathfind(
                            caller + "-resumed", System.nanoTime() - resumeStartedAt);
                }
                CAPPED_FRAGMENTS.forget(skillAgent.getId(), "route-recovered");
                return resumed;
            }
        }
        RecoveredRouteMemory.PlanningView view = routeKey == null
                ? null
                : new RecoveredRouteMemory.PlanningView(
                        graph, map, skillAgent, skillRoute, edgeAllowed, edgePenalty);
        SearchOutcome recovered = runSearch(
                graph, map, startPos, startRegionId, targetRegionId, targetPos,
                caller + "-recovery", USE_ZERO_HEURISTIC, instrument, recoveryBudget,
                variation, dangerCosts, skillAgent, false,
                view == null ? edgeAllowed : view::allows,
                view == null ? edgePenalty : view::penalty);
        if (unreachableKey != null && !recovered.reached() && !recovered.capped()) {
            UNREACHABLE_RECOVERIES.remember(unreachableKey, nowMs, UNREACHABLE_RECOVERY_TTL_MS);
        }
        if (view != null && recovered.reached() && !recovered.path().isEmpty()) {
            RECOVERED_ROUTES.remember(routeKey, recovered, dangerCosts, view);
        }
        if (recovered.path().isEmpty()) {
            return fragmentSelection(bounded, memoryKey, map, skillAgent, startRegionId,
                    edgeAllowed, caller, instrument);
        }
        if (skillAgent != null) {
            CAPPED_FRAGMENTS.forget(skillAgent.getId(), "route-recovered");
        }
        return selection(recovered, true);
    }

    /**
     * The capped bounded fragment the mover follows when no complete route replaces it: the rest
     * of the Agent's current fragment while that still holds, else this search's fragment, which
     * the Agent then follows to its end.
     */
    private static MovementPathSelection fragmentSelection(
            SearchOutcome bounded,
            RecoveredRouteMemory.Key key,
            MapleMap map,
            Character agent,
            int startRegionId,
            Predicate<AgentNavigationGraph.Edge> edgeAllowed,
            String caller,
            boolean instrument) {
        if (!CAPPED_FRAGMENT_COMMITMENT || key == null) {
            return selection(bounded, false);
        }
        long resumeStartedAt = System.nanoTime();
        MovementPathSelection resumed = CAPPED_FRAGMENTS.resume(
                key, map, agent, startRegionId, edgeAllowed);
        if (resumed != null) {
            if (instrument) {
                AgentPerformanceMonitor.recordPathfind(
                        caller + "-fragment-resumed", System.nanoTime() - resumeStartedAt);
            }
            return resumed;
        }
        CAPPED_FRAGMENTS.remember(key, bounded);
        return selection(bounded, false);
    }

    /**
     * Forgets the Agent's resumable recovered route and capped fragment, so a later capped
     * movement decision searches afresh. The mover calls this when an edge is rejected or times
     * out.
     */
    static void forgetRecoveredRoute(Character agent) {
        if (agent != null) {
            RECOVERED_ROUTES.forget(agent.getId(), "edge-failed");
            CAPPED_FRAGMENTS.forget(agent.getId(), "edge-failed");
        }
    }

    /**
     * Whether a route from {@code regionId} can start with an edge the mover keeps when the
     * target lies on that same region: a self-loop (an intra-region portal) or a no-movement
     * walk seam that {@link #collapseLeadingWalkEdges} merges with the edges after it. Without
     * one, every first edge leaves the region and the mover discards it, so a same-region
     * decision is always a direct walk.
     */
    static boolean canRouteWithinRegion(AgentNavigationGraph graph, int regionId) {
        for (AgentNavigationGraph.Edge edge : graph.getOutgoing(regionId)) {
            if (staysWithinRegion(edge, regionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an edge can be part of a route the mover keeps for a target on {@code regionId}:
     * one that ends on that region, or a no-movement walk seam merged with the edge after it.
     */
    static boolean staysWithinRegion(AgentNavigationGraph.Edge edge, int regionId) {
        return edge.toRegionId == regionId
                || (edge.type == AgentNavigationGraph.EdgeType.WALK
                && isNoMovementWalk(edge.startPoint, edge.endPoint));
    }

    /** The selection a search returns when walking straight to the target is the best route. */
    static MovementPathSelection directWalkSelection(AgentNavigationGraph graph,
                                                     int regionId,
                                                     Point startPos,
                                                     Point targetPos) {
        return selection(new SearchOutcome(List.of(),
                intraRegionTravelCost(graph, regionId, startPos, targetPos), 0, false,
                true, false, false, regionId, 0L), false);
    }

    private static MovementPathSelection selection(SearchOutcome outcome,
                                                   boolean recoverySearch) {
        List<AgentNavigationGraph.Edge> path = outcome.path();
        AgentNavigationGraph.Edge active = path.isEmpty()
                ? null
                : collapseLeadingWalkEdges(path);
        return new MovementPathSelection(path, active, outcome, recoverySearch);
    }

    public static List<AgentNavigationGraph.Edge> findPath(AgentNavigationGraph graph,
                                                           MapleMap map,
                                                           Point startPos,
                                                           int startRegionId,
                                                           int targetRegionId,
                                                           Point targetPos,
                                                           String pathfindCaller) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, USE_ZERO_HEURISTIC, true).path();
    }

    public static List<AgentNavigationGraph.Edge> findPath(AgentNavigationGraph graph,
                                                           MapleMap map,
                                                           Point startPos,
                                                           int startRegionId,
                                                           int targetRegionId,
                                                           Point targetPos,
                                                           String pathfindCaller,
                                                           boolean zeroHeuristic,
                                                           boolean instrument) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument).path();
    }

    public static SearchOutcome runSearch(AgentNavigationGraph graph,
                                          MapleMap map,
                                          Point startPos,
                                          int startRegionId,
                                          int targetRegionId,
                                          Point targetPos,
                                          String pathfindCaller,
                                          boolean zeroHeuristic,
                                          boolean instrument) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument, MAX_EDGE_CHECKS);
    }

    static SearchOutcome runSearch(AgentNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   int edgeCheckBudget) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument, edgeCheckBudget, null);
    }

    static SearchOutcome runSearch(AgentNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   int edgeCheckBudget,
                                   AgentTravelVariationRuntime.RouteVariation routeVariation) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument, edgeCheckBudget, routeVariation, Map.of());
    }

    static SearchOutcome runSearch(AgentNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   int edgeCheckBudget,
                                   AgentTravelVariationRuntime.RouteVariation routeVariation,
                                   Map<Integer, Integer> regionEntryCosts) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument, edgeCheckBudget, routeVariation,
                regionEntryCosts, null, false, null, null);
    }

    /** Runs the same search with dominance pruning forced on or off, for equivalence checks. */
    static SearchOutcome runSearch(AgentNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   int edgeCheckBudget,
                                   AgentTravelVariationRuntime.RouteVariation routeVariation,
                                   boolean dominancePruning) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, USE_ZERO_HEURISTIC, false, edgeCheckBudget, routeVariation,
                Map.of(), null, false, null, null, dominancePruning);
    }

    private static SearchOutcome runSearch(AgentNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   int edgeCheckBudget,
                                   AgentTravelVariationRuntime.RouteVariation routeVariation,
                                   Map<Integer, Integer> regionEntryCosts,
                                   Character skillAgent,
                                   boolean shadowSkillEdges,
                                   Predicate<AgentNavigationGraph.Edge> edgeAllowed,
                                   ToIntFunction<AgentNavigationGraph.Edge> edgePenalty) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument, edgeCheckBudget, routeVariation,
                regionEntryCosts, skillAgent, shadowSkillEdges, edgeAllowed, edgePenalty,
                DOMINANCE_PRUNING);
    }

    private static SearchOutcome runSearch(AgentNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   int edgeCheckBudget,
                                   AgentTravelVariationRuntime.RouteVariation routeVariation,
                                   Map<Integer, Integer> regionEntryCosts,
                                   Character skillAgent,
                                   boolean shadowSkillEdges,
                                   Predicate<AgentNavigationGraph.Edge> edgeAllowed,
                                   ToIntFunction<AgentNavigationGraph.Edge> edgePenalty,
                                   boolean dominancePruning) {
        long startedAt = System.nanoTime();
        PathfindProfile profile = null;
        try {
            boolean skillRoute = skillAgent != null && (shadowSkillEdges
                    ? AgentMovementSkillPolicy.canUseAnyShadowMovementSkill(skillAgent)
                    : AgentMovementSkillPolicy.canUseAnyActiveMovementSkill(skillAgent));
            if (!skillRoute) {
                int startComponent = graph.connectedComponentId(startRegionId);
                int targetComponent = graph.connectedComponentId(targetRegionId);
                if (startComponent < 0 || targetComponent < 0 || startComponent != targetComponent) {
                    return new SearchOutcome(List.of(), Integer.MAX_VALUE, 0, false,
                            false, false, false, startRegionId,
                            Math.max(0L, (System.nanoTime() - startedAt) / 1_000L));
                }
            }

            PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingInt(node -> node.score));
            Map<SearchState, Integer> gScore = new HashMap<>();
            Map<SearchState, SearchState> cameFrom = new HashMap<>();
            Map<SearchState, AgentNavigationGraph.Edge> cameByEdge = new HashMap<>();
            SearchState startState = new SearchState(startRegionId, new Point(startPos), false);
            SearchState bestGoalState = null;
            SearchState closestState = startState;
            long closestDistance = rawDistance(startPos, targetPos);
            int bestGoalCost = Integer.MAX_VALUE;
            int expandedNodes = 0;
            int staleNodes = 0;
            int dominatedNodes = 0;
            int edgeChecks = 0;
            int usableEdges = 0;
            int relaxations = 0;
            int openPeak = 1;
            boolean capped = false;
            Map<Integer, Integer> costToGoal = zeroHeuristic || skillRoute
                    ? Map.of()
                    : graph.costToGoal(targetRegionId);
            RegionDominance dominance = dominancePruning
                    ? new RegionDominance(graph, routeVariation)
                    : null;

            gScore.put(startState, 0);
            open.add(new SearchNode(startState, 0,
                    zeroHeuristic ? 0 : heuristic(startRegionId, targetRegionId, costToGoal)));

            while (!open.isEmpty()) {
                SearchNode current = open.poll();
                if (current.cost != gScore.getOrDefault(current.state, Integer.MAX_VALUE)) {
                    staleNodes++;
                    continue;
                }
                if (bestGoalState != null && current.score >= bestGoalCost) {
                    break;
                }
                if (dominance != null && dominance.dominated(current.state, current.cost)) {
                    dominatedNodes++;
                    continue;
                }
                expandedNodes++;

                if (current.state.regionId == targetRegionId) {
                    int goalCost = current.cost + intraRegionTravelCost(graph, current.state.regionId, current.state.point, targetPos);
                    if (goalCost < bestGoalCost) {
                        bestGoalCost = goalCost;
                        bestGoalState = current.state;
                    }
                    // Keep expanding this state: a self-loop portal, or a route that briefly
                    // leaves and re-enters the region, can still beat a long direct walk.
                }

                List<AgentNavigationGraph.Edge> outgoing = graph.getOutgoing(current.state.regionId);
                int[] scan = dominance == null ? null : dominance.edgesToScan(current.state, current.cost);
                int scanCount = scan == null ? outgoing.size() : scan.length;
                for (int scanIndex = 0; scanIndex < scanCount; scanIndex++) {
                    AgentNavigationGraph.Edge edge = outgoing.get(scan == null ? scanIndex : scan[scanIndex]);
                    if (edgeAllowed != null && !edgeAllowed.test(edge)) {
                        continue;
                    }
                    if (!AgentNavigationRouteOverlayPolicy.allows(graph, targetRegionId, edge)) {
                        continue;
                    }
                    if (!isEdgeUsable(graph, map, skillAgent, edge, shadowSkillEdges)) {
                        continue;
                    }
                    edgeChecks++;
                    if (edgeChecks > Math.max(1, edgeCheckBudget)) {
                        capped = true;
                        break;
                    }
                    usableEdges++;

                    boolean isPortal = edge.type == AgentNavigationGraph.EdgeType.PORTAL;
                    // Portals are free on their own (edge.cost == 0). Charge the portal cooldown
                    // only when the bot enters a portal through the exit of the one it just took.
                    boolean enteredThroughExit = current.state.viaPortal
                            && current.state.point.equals(edge.startPoint);
                    int edgeCost = isPortal && enteredThroughExit ? (int) PORTAL_USE_COOLDOWN_MS : edge.cost;
                    int transitionCost = intraRegionTravelCost(
                            graph, current.state.regionId, current.state.point, edge.startPoint) + edgeCost;
                    transitionCost = variedTransitionCost(transitionCost, edge, routeVariation);
                    transitionCost = saturatedAdd(
                            transitionCost, regionEntryCosts.getOrDefault(edge.toRegionId, 0));
                    if (edgePenalty != null) {
                        transitionCost = saturatedAdd(
                                transitionCost, Math.max(0, edgePenalty.applyAsInt(edge)));
                    }
                    int tentativeCost = saturatedAdd(current.cost, transitionCost);
                    SearchState nextState = new SearchState(edge.toRegionId, edge.endPoint, isPortal);
                    if (tentativeCost >= gScore.getOrDefault(nextState, Integer.MAX_VALUE)) {
                        continue;
                    }

                    relaxations++;
                    gScore.put(nextState, tentativeCost);
                    cameFrom.put(nextState, current.state);
                    cameByEdge.put(nextState, edge);
                    int fScore = tentativeCost + (zeroHeuristic
                            ? 0 : heuristic(edge.toRegionId, targetRegionId, costToGoal));
                    open.add(new SearchNode(nextState, tentativeCost, fScore));
                    openPeak = Math.max(openPeak, open.size());
                    long reachedDistance = rawDistance(edge.endPoint, targetPos);
                    if (reachedDistance < closestDistance) {
                        closestDistance = reachedDistance;
                        closestState = nextState;
                    }
                }
                if (capped) {
                    break;
                }
                if (dominance != null) {
                    dominance.expanded(current.state, current.cost);
                }
            }

            SearchState resultState = bestGoalState;
            if (resultState == null && capped && bestEffortCaller(pathfindCaller)
                    && !closestState.equals(startState)) {
                resultState = closestState;
            }
            List<AgentNavigationGraph.Edge> path = reconstructPath(startState, resultState, cameFrom, cameByEdge);
            profile = new PathfindProfile(
                    System.nanoTime() - startedAt,
                    expandedNodes,
                    staleNodes,
                    dominatedNodes,
                    edgeChecks,
                    usableEdges,
                    relaxations,
                    openPeak,
                    bestGoalCost,
                    path.size(),
                    capped);
            int finalRegionId = resultState == null ? startRegionId : resultState.regionId;
            // A capped closest-frontier fragment may end in the target region without the search
            // having popped a goal; it is still a fragment, not a complete route.
            boolean reached = bestGoalState != null;
            return new SearchOutcome(path, bestGoalCost, expandedNodes, usesPortal(path),
                    reached, capped, !reached && !path.isEmpty(), finalRegionId,
                    Math.max(0L, (System.nanoTime() - startedAt) / 1_000L));
        } finally {
            if (instrument) {
                if (profile == null) {
                    profile = new PathfindProfile(
                            System.nanoTime() - startedAt,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            Integer.MAX_VALUE,
                            0,
                            false);
                }
                logSlowPathfind(graph, map, startPos, startRegionId, targetRegionId, targetPos, pathfindCaller, profile);
                AgentPerformanceMonitor.recordPathfind(pathfindCaller, System.nanoTime() - startedAt);
            }
        }
    }

    static int variedTransitionCost(
            int baseCost,
            AgentNavigationGraph.Edge edge,
            AgentTravelVariationRuntime.RouteVariation variation) {
        if (baseCost <= 0 || edge == null || variation == null || variation.maxRouteStretch() <= 1.0d) {
            return Math.max(0, baseCost);
        }
        long edgeSeed = variation.seed();
        edgeSeed = AgentTravelVariationRuntime.mix(edgeSeed ^ edge.fromRegionId);
        edgeSeed = AgentTravelVariationRuntime.mix(edgeSeed ^ edge.toRegionId);
        edgeSeed = AgentTravelVariationRuntime.mix(edgeSeed ^ edge.type.ordinal());
        edgeSeed = AgentTravelVariationRuntime.mix(
                edgeSeed ^ (((long) edge.startPoint.x) << 32) ^ (edge.startPoint.y & 0xffffffffL));
        edgeSeed = AgentTravelVariationRuntime.mix(
                edgeSeed ^ (((long) edge.endPoint.x) << 32) ^ (edge.endPoint.y & 0xffffffffL));
        edgeSeed = AgentTravelVariationRuntime.mix(edgeSeed ^ edge.portalId ^ edge.launchStepX);
        double multiplier = 1.0d + AgentTravelVariationRuntime.unitDouble(edgeSeed)
                * (variation.maxRouteStretch() - 1.0d);
        long varied = (long) Math.ceil(baseCost * multiplier);
        return (int) Math.min(Integer.MAX_VALUE, varied);
    }

    private static boolean usesPortal(List<AgentNavigationGraph.Edge> path) {
        for (AgentNavigationGraph.Edge edge : path) {
            if (edge.type == AgentNavigationGraph.EdgeType.PORTAL) {
                return true;
            }
        }
        return false;
    }

    private static int saturatedAdd(int left, int right) {
        long total = (long) left + right;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static boolean bestEffortCaller(String caller) {
        return caller == null || caller.isBlank() || "committed".equals(caller)
                || "maple-island-varied".equals(caller);
    }

    private static long rawDistance(Point from, Point to) {
        if (from == null || to == null) {
            return Long.MAX_VALUE;
        }
        long dx = (long) to.x - from.x;
        long dy = (long) to.y - from.y;
        return dx * dx + dy * dy;
    }

    public static int intraRegionTravelCost(AgentNavigationGraph graph, Point from, Point to) {
        return Math.max(0, (int) Math.round(unroundedWalkCost(graph, from, to)));
    }

    public static int intraRegionTravelCost(AgentNavigationGraph graph, int regionId, Point from, Point to) {
        return Math.max(0, (int) Math.round(unroundedIntraRegionTravelCost(graph, regionId, from, to)));
    }

    // The walk (ground) or climb (rope) time in ms before intraRegionTravelCost rounds it.
    private static double unroundedIntraRegionTravelCost(AgentNavigationGraph graph,
                                                         int regionId,
                                                         Point from,
                                                         Point to) {
        AgentNavigationGraph.Region region = graph.getRegion(regionId);
        if (region != null && region.isRopeRegion) {
            int travel = Math.abs(to.y - from.y);
            return (travel * 1000.0) / Math.max(1, AgentMovementPhysicsConfig.configuredClimbSpeedPxs());
        }
        return unroundedWalkCost(graph, from, to);
    }

    private static double unroundedWalkCost(AgentNavigationGraph graph, Point from, Point to) {
        int dx = Math.abs(to.x - from.x);
        return (dx * 1000.0) / Math.max(1.0, graph.movementProfile.walkVelocityPxs());
    }

    public static int heuristic(AgentNavigationGraph graph, Point from, Point targetPos) {
        return intraRegionTravelCost(graph, from, targetPos);
    }

    private static int heuristic(int regionId,
                                 int targetRegionId,
                                 Map<Integer, Integer> costToGoal) {
        if (regionId == targetRegionId) {
            return 0;
        }
        return costToGoal.getOrDefault(regionId, 0);
    }

    public static AgentNavigationGraph.Edge collapseLeadingWalkEdges(List<AgentNavigationGraph.Edge> path) {
        AgentNavigationGraph.Edge first = path.get(0);
        if (first.type != AgentNavigationGraph.EdgeType.WALK) {
            return first;
        }

        if (!isNoMovementWalk(first.startPoint, first.endPoint)) {
            return first;
        }

        int totalCost = 0;
        int walkCount = 0;
        while (walkCount < path.size()) {
            AgentNavigationGraph.Edge edge = path.get(walkCount);
            if (edge.type != AgentNavigationGraph.EdgeType.WALK
                    || !isNoMovementWalk(edge.startPoint, edge.endPoint)) {
                break;
            }
            totalCost += edge.cost;
            walkCount++;
        }

        if (walkCount >= path.size()) {
            return null;
        }

        AgentNavigationGraph.Edge next = path.get(walkCount);
        return new AgentNavigationGraph.Edge(first.fromRegionId, next.toRegionId, next.type,
                next.startPoint, next.endPoint, next.launchMinX, next.launchMaxX, next.launchStepX, next.portalId,
                next.ropeX, next.ropeTopY, next.ropeBottomY, totalCost + next.cost);
    }

    public static boolean shouldUsePreciseWalkTarget(AgentNavigationGraph.Edge edge) {
        return edge != null
                && edge.type == AgentNavigationGraph.EdgeType.WALK
                && !isNoMovementWalk(edge.startPoint, edge.endPoint);
    }

    public static boolean isEdgeUsable(AgentNavigationGraph graph, Character bot, AgentNavigationGraph.Edge edge) {
        return isEdgeUsable(graph, bot.getMap(), bot, edge, false);
    }

    public static boolean isEdgeUsable(AgentNavigationGraph graph, MapleMap map, AgentNavigationGraph.Edge edge) {
        return isEdgeUsable(graph, map, null, edge, false);
    }

    private static boolean isEdgeUsable(AgentNavigationGraph graph,
                                        MapleMap map,
                                        Character skillAgent,
                                        AgentNavigationGraph.Edge edge,
                                        boolean shadowSkillEdges) {
        return switch (edge.type) {
            case WALK, JUMP, DROP, CLIMB -> true;
            case FLASH_JUMP, TELEPORT -> skillAgent != null
                    && (shadowSkillEdges
                    ? AgentMovementSkillPolicy.canUseShadowPath(skillAgent, edge)
                    : AgentMovementSkillPolicy.canUseActivePath(skillAgent, edge));
            case PORTAL -> {
                Portal portal = map.getPortal(edge.portalId);
                yield portal != null && portal.getPortalStatus();
            }
        };
    }

    public static List<AgentNavigationGraph.Edge> findShadowSkillPath(
            AgentNavigationGraph graph,
            Character bot,
            Point startPos,
            int startRegionId,
            int targetRegionId,
            Point targetPos) {
        return runSearch(graph, bot.getMap(), startPos, startRegionId, targetRegionId, targetPos,
                "movement-skill-shadow", USE_ZERO_HEURISTIC, false, MOVEMENT_MAX_EDGE_CHECKS,
                null, Map.of(), bot, true, null, null).path();
    }

    private static boolean isNoMovementWalk(Point start, Point end) {
        return Math.abs(end.x - start.x) <= NO_MOVEMENT_WALK_TOLERANCE
                && Math.abs(end.y - start.y) <= NO_MOVEMENT_WALK_TOLERANCE;
    }

    public static PathOptimality measureOptimality(AgentNavigationGraph graph,
                                                   MapleMap map,
                                                   Point startPos,
                                                   int startRegionId,
                                                   int targetRegionId,
                                                   Point targetPos) {
        SearchOutcome current = runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "measure", false, false);
        SearchOutcome optimal = runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "measure", true, false);
        return new PathOptimality(current.cost(), optimal.cost(), current.usesPortal(),
                optimal.usesPortal(), current.expandedNodes(), optimal.expandedNodes());
    }

    private static void logSlowPathfind(AgentNavigationGraph graph,
                                        MapleMap map,
                                        Point startPos,
                                        int startRegionId,
                                        int targetRegionId,
                                        Point targetPos,
                                        String pathfindCaller,
                                        PathfindProfile profile) {
        if (profile.elapsedNs() < SLOW_PATHFIND_WARN_NS) {
            return;
        }
        int regionCount = graph != null && graph.regions != null ? graph.regions.size() : -1;
        int outgoingFromStart = graph != null ? graph.getOutgoing(startRegionId).size() : -1;
        String caller = pathfindCaller == null || pathfindCaller.isBlank() ? "default" : pathfindCaller;
        int bestGoalCost = profile.bestGoalCost() == Integer.MAX_VALUE ? -1 : profile.bestGoalCost();
        log.warn(
                "Slow bot pathfind: caller={} took {} ms map={} startRegion={} targetRegion={} regions={} startOut={} startPos=({}, {}) targetPos=({}, {}) expanded={} stale={} dominated={} edgeChecks={} usableEdges={} relaxations={} openPeak={} bestGoalCost={} resultEdges={} capped={}",
                caller,
                String.format("%.1f", profile.elapsedNs() / 1_000_000.0),
                map != null ? map.getId() : -1,
                startRegionId,
                targetRegionId,
                regionCount,
                outgoingFromStart,
                startPos != null ? startPos.x : -1,
                startPos != null ? startPos.y : -1,
                targetPos != null ? targetPos.x : -1,
                targetPos != null ? targetPos.y : -1,
                profile.expandedNodes(),
                profile.staleNodes(),
                profile.dominatedNodes(),
                profile.edgeChecks(),
                profile.usableEdges(),
                profile.relaxations(),
                profile.openPeak(),
                bestGoalCost,
                profile.resultEdges(),
                profile.capped());
    }

    private static List<AgentNavigationGraph.Edge> reconstructPath(SearchState startState,
                                                                   SearchState goalState,
                                                                   Map<SearchState, SearchState> cameFrom,
                                                                   Map<SearchState, AgentNavigationGraph.Edge> cameByEdge) {
        if (goalState == null || !cameByEdge.containsKey(goalState)) {
            return List.of();
        }

        List<AgentNavigationGraph.Edge> path = new ArrayList<>();
        SearchState cursor = goalState;
        while (!cursor.equals(startState)) {
            AgentNavigationGraph.Edge edge = cameByEdge.get(cursor);
            if (edge == null) {
                return List.of();
            }

            path.add(0, edge);
            SearchState previousState = cameFrom.get(cursor);
            if (previousState == null) {
                return List.of();
            }
            cursor = previousState;
        }
        return path;
    }
}

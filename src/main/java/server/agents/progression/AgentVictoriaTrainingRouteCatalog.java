package server.agents.progression;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * WZ-verified standard portal graph (Victoria Island plus the continents outside it, which no portal joins to
 * Victoria), the scripted portals of the level-15 slice and of the world graph (the Time Gate, An Empty House's door,
 * the dragon flights to the Temple of Time), and the scripted edges used by the level-15 slice.
 */
final class AgentVictoriaTrainingRouteCatalog {
    private static final List<AgentVictoriaPortalRouteGraph.ScriptedPortal> SCRIPTED = scripted();
    private static final List<AgentVictoriaPortalRouteGraph.ScriptedPortal> GATED =
            SCRIPTED.stream().filter(portal -> portal.gate() != null).toList();
    private static final Map<Integer, Set<Integer>> EDGES = edges();
    /** Maps joined by portals in either direction share a landmass id; no route ever leaves its landmass. */
    private static final Map<Integer, Integer> LANDMASS = landmasses(EDGES);
    /** Maps of the other continents' graph (world-portal-route-graph.json): none of them is on Victoria Island. */
    private static final Set<Integer> OUTSIDE_VICTORIA = worldMaps();

    private AgentVictoriaTrainingRouteCatalog() {
    }

    static Integer nextHop(int source, int destination) {
        return nextHop(source, destination, Set.of());
    }

    static Integer nextHop(int source, int destination, Set<Long> excludedEdges) {
        if (source == destination) {
            return destination;
        }
        if (apart(source, destination)) {
            return null;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> previous = new HashMap<>();
        queue.add(source);
        previous.put(source, source);
        while (!queue.isEmpty() && !previous.containsKey(destination)) {
            int current = queue.removeFirst();
            for (int next : EDGES.getOrDefault(current, Set.of())) {
                if (excludedEdges != null && excludedEdges.contains(edgeKey(current, next))) {
                    continue;
                }
                if (previous.putIfAbsent(next, current) == null) {
                    queue.addLast(next);
                }
            }
        }
        if (!previous.containsKey(destination)) {
            return null;
        }
        int step = destination;
        while (previous.get(step) != source) {
            step = previous.get(step);
        }
        return step;
    }

    /** One step of a path: a portal walked from one map to the next ({@code leg} null), or a travel leg ridden. */
    record Step(int fromMapId, int toMapId, AgentTravelLegCatalog.Leg leg) {
    }

    /**
     * The cheapest path walking the portal graph (one a portal, {@code excludedEdges} left out as for nextHop) and
     * riding the travel legs {@code usable} accepts ({@code rideHopCost} a leg). Empty when nothing reaches the
     * destination, or the Agent already stands on it. Ties go to the path found first, so it is deterministic.
     */
    static List<Step> ridePath(int source, int destination, Set<Long> excludedEdges,
                               Predicate<AgentTravelLegCatalog.Leg> usable, int rideHopCost) {
        if (source == destination
                || !LANDMASS.containsKey(destination) && !AgentTravelLegCatalog.station(destination)) {
            // every leg lands on the graphs or on a map only legs reach (Happyville): a map off both is never reached
            return List.of();
        }
        int legCost = Math.max(1, rideHopCost);
        Map<Integer, Integer> cost = new HashMap<>();
        Map<Integer, Step> via = new HashMap<>();
        // {cost, insertion order, map}: the order breaks ties first-come.
        PriorityQueue<long[]> queue = new PriorityQueue<>(
                Comparator.<long[]>comparingLong(entry -> entry[0]).thenComparingLong(entry -> entry[1]));
        long order = 0L;
        cost.put(source, 0);
        queue.add(new long[]{0L, order++, source});
        while (!queue.isEmpty()) {
            long[] head = queue.poll();
            int current = (int) head[2];
            if (head[0] > cost.get(current)) {
                continue;
            }
            if (current == destination) {
                break;
            }
            int reached = (int) head[0];
            for (int next : EDGES.getOrDefault(current, Set.of())) {
                if (excludedEdges != null && excludedEdges.contains(edgeKey(current, next))) {
                    continue;
                }
                if (relax(cost, via, new Step(current, next, null), reached + 1)) {
                    queue.add(new long[]{reached + 1L, order++, next});
                }
            }
            for (AgentTravelLegCatalog.Leg leg : AgentTravelLegCatalog.from(current)) {
                if (usable != null && !usable.test(leg)) {
                    continue;
                }
                if (relax(cost, via, new Step(current, leg.toMapId(), leg), reached + legCost)) {
                    queue.add(new long[]{(long) reached + legCost, order++, leg.toMapId()});
                }
            }
        }
        if (!via.containsKey(destination)) {
            return List.of();
        }
        ArrayDeque<Step> path = new ArrayDeque<>();
        for (int at = destination; at != source; at = via.get(at).fromMapId()) {
            path.addFirst(via.get(at));
        }
        return List.copyOf(path);
    }

    private static boolean relax(Map<Integer, Integer> cost, Map<Integer, Step> via, Step step, int reached) {
        Integer known = cost.get(step.toMapId());
        if (known != null && known <= reached) {
            return false;
        }
        cost.put(step.toMapId(), reached);
        via.put(step.toMapId(), step);
        return true;
    }

    static boolean canRoute(int source, int destination) {
        return source == destination || nextHop(source, destination) != null;
    }

    static int distance(int source, int destination) {
        return distance(source, destination, Set.of());
    }

    /** Portal hops nextHop would take with these edges excluded, or -1 when it cannot get there. */
    static int distance(int source, int destination, Set<Long> excludedEdges) {
        if (source == destination) {
            return 0;
        }
        if (apart(source, destination)) {
            return -1;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> distances = new HashMap<>();
        queue.add(source);
        distances.put(source, 0);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int nextDistance = distances.get(current) + 1;
            for (int next : EDGES.getOrDefault(current, Set.of())) {
                if (excludedEdges != null && excludedEdges.contains(edgeKey(current, next))) {
                    continue;
                }
                if (distances.putIfAbsent(next, nextDistance) != null) {
                    continue;
                }
                if (next == destination) {
                    return nextDistance;
                }
                queue.addLast(next);
            }
        }
        return -1;
    }

    /**
     * True when no portal path joins the two maps even ignoring direction (another continent, or a map the graphs
     * do not know), so a search for the many unreachable suppliers of other continents costs nothing.
     */
    private static boolean apart(int source, int destination) {
        Integer from = LANDMASS.get(source);
        return from == null || !from.equals(LANDMASS.get(destination));
    }

    /** True for a map of the other continents' portal graph; false on Victoria Island or off both graphs. */
    static boolean outsideVictoria(int mapId) {
        return OUTSIDE_VICTORIA.contains(mapId);
    }

    static long edgeKey(int source, int destination) {
        return ((long) source << 32) ^ (destination & 0xffffffffL);
    }

    static Integer scriptedPortalId(int source, int destination) {
        for (AgentVictoriaLevel15Catalog.ScriptedPortal portal : catalog().scriptedPortals()) {
            if (portal.sourceMapId() == source && portal.destinationMapId() == destination) {
                return portal.portalId();
            }
        }
        for (AgentVictoriaPortalRouteGraph.ScriptedPortal portal : SCRIPTED) {
            if (portal.sourceMapId() == source && portal.destinationMapId() == destination) {
                return portal.portalId();
            }
        }
        return null;
    }

    /** The route graphs' scripted portals that only let some travellers through (the script's own quest gate). */
    static List<AgentVictoriaPortalRouteGraph.ScriptedPortal> gatedPortals() {
        return GATED;
    }

    private static List<AgentVictoriaPortalRouteGraph.ScriptedPortal> scripted() {
        List<AgentVictoriaPortalRouteGraph.ScriptedPortal> portals = new java.util.ArrayList<>();
        for (AgentVictoriaPortalRouteGraphRepository repository : List.of(
                AgentVictoriaPortalRouteGraphRepository.defaultRepository(),
                AgentVictoriaPortalRouteGraphRepository.worldRepository())) {
            portals.addAll(repository.graph().scriptedPortals());
        }
        return List.copyOf(portals);
    }

    private static Map<Integer, Set<Integer>> edges() {
        Map<Integer, java.util.LinkedHashSet<Integer>> mutable = new LinkedHashMap<>();
        for (AgentVictoriaLevel15Catalog.RouteCorridor route : catalog().routeCorridors()) {
            List<Integer> corridor = route.mapIds();
            for (int i = 1; i < corridor.size(); i++) {
                int left = corridor.get(i - 1);
                int right = corridor.get(i);
                mutable.computeIfAbsent(left, ignored -> new java.util.LinkedHashSet<>()).add(right);
                mutable.computeIfAbsent(right, ignored -> new java.util.LinkedHashSet<>()).add(left);
            }
        }
        for (AgentVictoriaPortalRouteGraphRepository repository : List.of(
                AgentVictoriaPortalRouteGraphRepository.defaultRepository(),
                AgentVictoriaPortalRouteGraphRepository.worldRepository())) {
            for (AgentVictoriaPortalRouteGraph.Edge edge : repository.graph().edges()) {
                mutable.computeIfAbsent(edge.fromMapId(), ignored -> new java.util.LinkedHashSet<>())
                        .add(edge.toMapId());
            }
            for (AgentVictoriaPortalRouteGraph.ScriptedPortal portal : repository.graph().scriptedPortals()) {
                mutable.computeIfAbsent(portal.sourceMapId(), ignored -> new java.util.LinkedHashSet<>())
                        .add(portal.destinationMapId());
            }
        }
        for (AgentVictoriaLevel15Catalog.Career career : catalog().careers()) {
            AgentVictoriaLevel15Catalog.TrainingGround trainingGround = career.trainingGround();
            if (trainingGround == null) {
                continue;
            }
            for (int instanceMapId : trainingGround.instanceMapIds()) {
                mutable.computeIfAbsent(instanceMapId, ignored -> new java.util.LinkedHashSet<>())
                        .add(trainingGround.entranceMapId());
            }
        }
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        mutable.forEach((mapId, adjacent) -> result.put(mapId,
                Collections.unmodifiableSet(new java.util.LinkedHashSet<>(adjacent))));
        return Map.copyOf(result);
    }

    private static Set<Integer> worldMaps() {
        Set<Integer> maps = new java.util.HashSet<>();
        for (AgentVictoriaPortalRouteGraph.Edge edge
                : AgentVictoriaPortalRouteGraphRepository.worldRepository().graph().edges()) {
            maps.add(edge.fromMapId());
            maps.add(edge.toMapId());
        }
        return Set.copyOf(maps);
    }

    private static Map<Integer, Integer> landmasses(Map<Integer, Set<Integer>> edges) {
        Map<Integer, Set<Integer>> undirected = new HashMap<>();
        edges.forEach((from, targets) -> {
            for (int to : targets) {
                undirected.computeIfAbsent(from, ignored -> new java.util.HashSet<>()).add(to);
                undirected.computeIfAbsent(to, ignored -> new java.util.HashSet<>()).add(from);
            }
        });
        Map<Integer, Integer> landmass = new HashMap<>();
        for (int start : undirected.keySet()) {
            if (landmass.containsKey(start)) {
                continue;
            }
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            landmass.put(start, start);
            while (!queue.isEmpty()) {
                for (int next : undirected.getOrDefault(queue.removeFirst(), Set.of())) {
                    if (landmass.putIfAbsent(next, start) == null) {
                        queue.addLast(next);
                    }
                }
            }
        }
        return Map.copyOf(landmass);
    }

    private static AgentVictoriaLevel15Catalog catalog() {
        return AgentVictoriaLevel15CatalogRepository.defaultRepository().catalog();
    }
}

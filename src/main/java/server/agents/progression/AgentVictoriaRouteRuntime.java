package server.agents.progression;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.objective.AgentNpcInteractionReachabilityService;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Map-to-map travel: through standard (and authored scripted) portals while a portal path reaches the destination;
 * otherwise by the travel legs (npc-travel-legs.json): it buys a ticket from its seller when the path ahead needs one,
 * talks to the boarding NPC and waits out the ride on its waiting room or ride map until the server sets it down.
 */
public final class AgentVictoriaRouteRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentVictoriaRouteRuntime.class);
    private static final int PORTAL_DISTANCE_PX = config.AgentTuning.intValue("server.agents.progression.AgentVictoriaRouteRuntime.PORTAL_DISTANCE_PX");
    private static final long FAILED_EDGE_BLOCK_MS = config.AgentTuning.longValue("server.agents.progression.AgentVictoriaRouteRuntime.FAILED_EDGE_BLOCK_MS");
    private static final long PORTAL_ARRIVAL_SETTLE_MS = config.AgentTuning.longValue(
            "server.agents.progression.AgentVictoriaRouteRuntime.PORTAL_ARRIVAL_SETTLE_MS");
    private static final long SCRIPTED_PORTAL_OBSERVER_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.progression.AgentVictoriaRouteRuntime.SCRIPTED_PORTAL_OBSERVER_GRACE_MS");
    private static final int RIDE_HOP_COST = config.AgentTuning.intValue(
            "server.agents.progression.AgentVictoriaRouteRuntime.RIDE_HOP_COST");
    private static final int NPC_INTERACTION_DISTANCE_PX = config.AgentTuning.intValue(
            "server.agents.progression.AgentVictoriaRouteRuntime.NPC_INTERACTION_DISTANCE_PX");
    private static final long NPC_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.progression.AgentVictoriaRouteRuntime.NPC_RETRY_MS");
    private static final long BOARDING_PATIENCE_MS = config.AgentTuning.longValue(
            "server.agents.progression.AgentVictoriaRouteRuntime.BOARDING_PATIENCE_MS");

    public enum Status {
        ARRIVED,
        MOVING,
        NO_ROUTE,
        PORTAL_UNAVAILABLE
    }

    public record TravelOutcome(Status status, int sourceMapId, int nextMapId, int destinationMapId,
                                boolean edgeBlocked) {
    }

    /** A traveller that may ride, and the mesos it may spend on fares and tickets. Null: it only walks. */
    public record RideBudget(long spendableMesos) {
    }

    /** What a ride plan decided: an outcome to return now, or the next map to walk to through a portal. */
    private record Ride(TravelOutcome outcome, Integer walkTo) {
    }

    /** A conversation with a travel NPC: still walking up or waiting to talk again, not on this map, or held. */
    private enum Talk { APPROACHING, WAITING, MISSING, FAILED, HELD }

    private AgentVictoriaRouteRuntime() {
    }

    /** Returns true while travel is still required. */
    static boolean travel(AgentRuntimeEntry entry,
                          Character agent,
                          int destinationMapId,
                          PrimitiveCapabilityGateway gateway) {
        return travelStatus(entry, agent, destinationMapId, gateway, System.currentTimeMillis()).status()
                != Status.ARRIVED;
    }

    /** Travel on foot only, through standard and authored scripted portals: what every caller does today. */
    public static TravelOutcome travelStatus(AgentRuntimeEntry entry,
                                             Character agent,
                                             int destinationMapId,
                                             PrimitiveCapabilityGateway gateway,
                                             long nowMs) {
        return travelStatus(entry, agent, destinationMapId, gateway, nowMs, null);
    }

    /**
     * Travel that walks wherever a portal path reaches and, with {@code rides} (a caller that opts in), otherwise
     * rides the travel legs within its budget. Null {@code rides}: on foot only.
     */
    public static TravelOutcome travelStatus(AgentRuntimeEntry entry,
                                             Character agent,
                                             int destinationMapId,
                                             PrimitiveCapabilityGateway gateway,
                                             long nowMs,
                                             RideBudget rides) {
        int sourceMapId = agent.getMapId();
        AgentVictoriaRouteState state = entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY);
        state.observeMap(sourceMapId, nowMs);
        if (state.settlingAt(sourceMapId, nowMs, gateway.observedByPlayer(agent))) {
            gateway.stop(entry);
            return new TravelOutcome(Status.MOVING, sourceMapId, sourceMapId, destinationMapId, false);
        }
        if (sourceMapId == destinationMapId) {
            state.clearActiveTravel();
            return new TravelOutcome(Status.ARRIVED, sourceMapId, sourceMapId, destinationMapId, false);
        }
        if (rides != null && AgentTravelLegCatalog.rideMap(sourceMapId)) {
            // In a waiting room, on a ship, on Hak's back: the server sets it down at the far end.
            state.clearActiveTravel();
            return new TravelOutcome(Status.MOVING, sourceMapId, sourceMapId, destinationMapId, false);
        }
        Set<Long> blockedEdges = state.blockedEdges(nowMs);
        Integer nextMap = AgentVictoriaTrainingRouteCatalog.nextHop(sourceMapId, destinationMapId, blockedEdges);
        if (nextMap == null && rides == null) {
            state.clearActiveTravel();
            return new TravelOutcome(Status.NO_ROUTE, sourceMapId, -1, destinationMapId, false);
        }
        if (nextMap == null) {
            Ride ride = ride(entry, agent, destinationMapId, gateway, state, blockedEdges, rides, nowMs);
            if (ride.outcome() != null) {
                return ride.outcome();
            }
            nextMap = ride.walkTo();
        }
        // Authored scripted entrances are semantic route contracts. They must win over
        // inferred/direct edges so job rooms and other scripted interiors use the same
        // entrance and destination portal as a real client.
        Integer portalId = AgentVictoriaTrainingRouteCatalog.scriptedPortalId(sourceMapId, nextMap);
        boolean scriptedPortal = portalId != null;
        if (portalId == null) {
            portalId = reachablePortalTo(entry, agent, nextMap, gateway, state);
        }
        if (portalId == null) {
            state.clearActiveTravel();
            return unavailable(state, sourceMapId, nextMap, destinationMapId, nowMs);
        }
        Point portal = gateway.portalPosition(agent, portalId);
        if (portal == null) {
            state.clearActiveTravel();
            return unavailable(state, sourceMapId, nextMap, destinationMapId, nowMs);
        }
        state.markActiveTravel(sourceMapId, destinationMapId);
        if (agent.getPosition().distanceSq(portal) <= PORTAL_DISTANCE_PX * PORTAL_DISTANCE_PX) {
            if (gateway.enterPortal(agent, portalId)) {
                boolean destinationObserved = gateway.observedByPlayer(agent);
                state.recordPortalSuccess(nextMap, nowMs, PORTAL_ARRIVAL_SETTLE_MS,
                        scriptedPortal && !destinationObserved,
                        SCRIPTED_PORTAL_OBSERVER_GRACE_MS);
            } else {
                return unavailable(state, sourceMapId, nextMap, destinationMapId, nowMs);
            }
        } else {
            gateway.navigate(entry, portal, true);
        }
        return new TravelOutcome(Status.MOVING, sourceMapId, nextMap, destinationMapId, false);
    }

    /**
     * The open portal to nextMapId this Agent walks to: the one it chose on this map before, while its own navigation
     * graph does not rule that one out; else the first, in directPortalIdTo's order, that the graph does not rule out;
     * else directPortalIdTo's answer as before. Live 2026-10-09: on Another Entrance (105090200) the first portal to
     * 105090100, west00, stands on the top floor that nothing below climbs to; four companions arriving at under00 on
     * the bottom floor held still there for 8+ minutes, while west01 on the middle floor is two climbs away. The choice
     * is kept because canReach cannot rule anything out while the Agent is in the air (a jump to the rope), and a new
     * target mid-jump would send it back toward the first portal. It looks one hop ahead only: whether the landing on
     * the next map reaches the portal after it is not checked.
     */
    private static Integer reachablePortalTo(AgentRuntimeEntry entry,
                                             Character agent,
                                             int nextMapId,
                                             PrimitiveCapabilityGateway gateway,
                                             AgentVictoriaRouteState state) {
        List<Integer> portalIds = gateway.directPortalIdsTo(agent, nextMapId);
        if (portalIds.isEmpty()) {
            return gateway.directPortalIdTo(agent, nextMapId);
        }
        Integer kept = state.keptPortal(nextMapId);
        if (kept != null && portalIds.contains(kept) && notRuledOut(entry, agent, kept, gateway)) {
            return kept;
        }
        for (Integer portalId : portalIds) {
            if (notRuledOut(entry, agent, portalId, gateway)) {
                state.keepPortal(nextMapId, portalId);
                return portalId;
            }
        }
        return portalIds.get(0);
    }

    private static boolean notRuledOut(AgentRuntimeEntry entry,
                                       Character agent,
                                       int portalId,
                                       PrimitiveCapabilityGateway gateway) {
        Point portal = gateway.portalPosition(agent, portalId);
        return portal != null && gateway.canReach(entry, agent, portal);
    }

    /**
     * Portal hops travelStatus would currently take from sourceMapId to destinationMapId,
     * honouring the edges this Agent has blocked after failed portal entries; -1 when it
     * cannot get there on foot. Walking only: supply trips measure with this, so they never
     * cross the sea (every continent has its own potion shops); rides serve plain travel.
     */
    public static int routeHops(AgentRuntimeEntry entry,
                                int sourceMapId,
                                int destinationMapId,
                                long nowMs) {
        Set<Long> blockedEdges = entry == null ? Set.of()
                : entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY).blockedEdges(nowMs);
        return AgentVictoriaTrainingRouteCatalog.distance(sourceMapId, destinationMapId, blockedEdges);
    }

    /** One step of a planned way: a portal from one map to the next ({@code boardingNpcId} 0), or a leg boarded there. */
    public record PlannedStep(int fromMapId, int toMapId, int boardingNpcId) {
        public boolean ride() {
            return boardingNpcId > 0;
        }
    }

    /**
     * The whole way from sourceMapId to destinationMapId, for a host that plans around a walk (a citizen choosing where
     * it boards, or which map it comes into its destination from): the portal hops while a portal path reaches it, with
     * the edges this Agent has blocked left out as travelStatus leaves them out; else the cheapest way that also boards
     * the travel legs (RIDE_HOP_COST a leg), fares and tickets not counted, and legs that kept refusing it left out.
     * Empty when nothing reaches the destination, or the Agent is there. Ties go as ridePath breaks them, so the hops
     * may differ from nextHop's where two walking paths are as short.
     */
    public static List<PlannedStep> plannedRoute(AgentRuntimeEntry entry,
                                                 int sourceMapId,
                                                 int destinationMapId,
                                                 long nowMs) {
        Set<Long> blockedEdges = entry == null ? Set.of()
                : entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY).blockedEdges(nowMs);
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                sourceMapId, destinationMapId, blockedEdges, leg -> false, RIDE_HOP_COST);
        if (path.isEmpty()) {
            path = AgentVictoriaTrainingRouteCatalog.ridePath(sourceMapId, destinationMapId, blockedEdges,
                    leg -> !blockedEdges.contains(legKey(leg)), RIDE_HOP_COST);
        }
        return path.stream().map(step -> new PlannedStep(step.fromMapId(), step.toMapId(),
                step.leg() == null ? 0 : step.leg().npcId())).toList();
    }

    /**
     * Keeps this Agent's route off the hop from sourceMapId to nextMapId until untilMs, as if its portal had kept
     * refusing entry (FAILED_EDGE_BLOCK_MS): travelStatus and routeHops walk around it, or find no route. For a caller
     * that saw the Agent fail to cross it some other way: a resupply walk that stood on sourceMapId too long.
     */
    public static void avoidHop(AgentRuntimeEntry entry, int sourceMapId, int nextMapId, long untilMs) {
        if (entry == null) {
            return;
        }
        entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY)
                .blockEdge(AgentVictoriaTrainingRouteCatalog.edgeKey(sourceMapId, nextMapId), untilMs);
    }

    /** True only while a Victoria plan is physically crossing this map toward another map. */
    public static boolean activeInterMapTravel(AgentRuntimeEntry entry, int currentMapId) {
        if (entry == null) {
            return false;
        }
        return entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY)
                .activeTravelIn(currentMapId);
    }

    /** Prevents a plan reconciler from skipping the visible arrival boundary after a portal transition. */
    static boolean settlingAfterPortalArrival(AgentRuntimeEntry entry,
                                              Character agent,
                                              PrimitiveCapabilityGateway gateway,
                                              long nowMs) {
        return entry != null && entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY)
                .settlingAt(agent.getMapId(), nowMs, gateway.observedByPlayer(agent));
    }

    /**
     * No portal path reaches the destination: plan with the travel legs it can pay for (the fare, and the ticket's
     * price when it holds none) and that have not kept refusing it, and go only when the whole way's unpaid fares and
     * tickets fit its budget, so it is never stranded halfway. The first ticket the way needs and it lacks is bought on
     * the spot when its seller stands here; when the way does not pass the seller before that leg, it goes to the
     * seller first, by legs that need no ticket it lacks.
     */
    private static Ride ride(AgentRuntimeEntry entry,
                             Character agent,
                             int destinationMapId,
                             PrimitiveCapabilityGateway gateway,
                             AgentVictoriaRouteState state,
                             Set<Long> blockedEdges,
                             RideBudget rides,
                             long nowMs) {
        int sourceMapId = agent.getMapId();
        long budget = Math.max(0L, rides.spendableMesos());
        Predicate<AgentTravelLegCatalog.Leg> usable = leg -> !blockedEdges.contains(legKey(leg))
                && leg.fare() + (holds(agent, leg.ticket(), gateway) ? 0L : leg.ticket().price()) <= budget;
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                sourceMapId, destinationMapId, blockedEdges, usable, RIDE_HOP_COST);
        if (path.isEmpty() || unpaid(agent, path, gateway) > budget) {
            state.clearActiveTravel();
            return new Ride(new TravelOutcome(Status.NO_ROUTE, sourceMapId, -1, destinationMapId, false), null);
        }
        for (int i = 0; i < path.size(); i++) {
            AgentTravelLegCatalog.Leg leg = path.get(i).leg();
            if (leg == null || holds(agent, leg.ticket(), gateway)) {
                continue;
            }
            int sellerMapId = leg.ticket().sellerMapId();
            if (sellerMapId == sourceMapId) {
                return new Ride(buyTicket(entry, agent, leg, gateway, state, destinationMapId, nowMs), null);
            }
            if (!passes(path, i, sellerMapId)) {
                path = AgentVictoriaTrainingRouteCatalog.ridePath(sourceMapId, sellerMapId, blockedEdges,
                        usable.and(errand -> holds(agent, errand.ticket(), gateway)), RIDE_HOP_COST);
            }
            break;
        }
        if (path.isEmpty()) {
            state.clearActiveTravel();
            return new Ride(new TravelOutcome(Status.NO_ROUTE, sourceMapId, -1, destinationMapId, false), null);
        }
        AgentVictoriaTrainingRouteCatalog.Step first = path.get(0);
        if (first.leg() == null) {
            return new Ride(null, first.toMapId());
        }
        return new Ride(board(entry, agent, first.leg(), gateway, state, destinationMapId, nowMs), null);
    }

    /** True when one of the steps before {@code legIndex} lands on {@code mapId}. */
    private static boolean passes(List<AgentVictoriaTrainingRouteCatalog.Step> path, int legIndex, int mapId) {
        for (int i = 0; i < legIndex; i++) {
            if (path.get(i).toMapId() == mapId) {
                return true;
            }
        }
        return false;
    }

    /** The fares and the prices of the tickets it does not hold yet, over a whole path (a held ticket serves once). */
    private static long unpaid(Character agent, List<AgentVictoriaTrainingRouteCatalog.Step> path,
                               PrimitiveCapabilityGateway gateway) {
        Map<Integer, Integer> held = new HashMap<>();
        long total = 0L;
        for (AgentVictoriaTrainingRouteCatalog.Step step : path) {
            AgentTravelLegCatalog.Leg leg = step.leg();
            if (leg == null) {
                continue;
            }
            total += leg.fare();
            AgentTravelLegCatalog.Ticket ticket = leg.ticket();
            if (ticket != null) {
                int left = held.computeIfAbsent(ticket.itemId(), item -> gateway.itemCount(agent, item));
                if (left > 0) {
                    held.put(ticket.itemId(), left - 1);
                } else {
                    total += ticket.price();
                }
            }
        }
        return total;
    }

    private static TravelOutcome buyTicket(AgentRuntimeEntry entry,
                                           Character agent,
                                           AgentTravelLegCatalog.Leg leg,
                                           PrimitiveCapabilityGateway gateway,
                                           AgentVictoriaRouteState state,
                                           int destinationMapId,
                                           long nowMs) {
        int sourceMapId = agent.getMapId();
        AgentTravelLegCatalog.Ticket ticket = leg.ticket();
        Talk talk = talk(entry, agent, ticket.sellerNpcId(),
                ticket.selections().stream().mapToInt(Integer::intValue).toArray(), gateway, state, nowMs);
        if (talk == Talk.APPROACHING || talk == Talk.WAITING) {
            state.markActiveTravel(sourceMapId, destinationMapId);
            return new TravelOutcome(Status.MOVING, sourceMapId, sourceMapId, destinationMapId, false);
        }
        if (talk != Talk.HELD || !holds(agent, ticket, gateway)) {
            return unavailableLeg(state, leg, sourceMapId, destinationMapId, nowMs);
        }
        log.info("Agent '{}' bought ticket {} from NPC {} on map {} for the {} ride",
                agent.getName(), ticket.itemId(), ticket.sellerNpcId(), sourceMapId, leg.id());
        state.markActiveTravel(sourceMapId, destinationMapId);
        return new TravelOutcome(Status.MOVING, sourceMapId, sourceMapId, destinationMapId, false);
    }

    /**
     * Boards the leg: lands on its destination (then pauses as after a portal) or on its ride. A scheduled ride whose
     * gate is still closed is asked again after NPC_RETRY_MS without counting against it for BOARDING_PATIENCE_MS; any
     * other refusal (and a gate closed past that) counts toward blocking the leg like a portal that keeps refusing.
     */
    private static TravelOutcome board(AgentRuntimeEntry entry,
                                       Character agent,
                                       AgentTravelLegCatalog.Leg leg,
                                       PrimitiveCapabilityGateway gateway,
                                       AgentVictoriaRouteState state,
                                       int destinationMapId,
                                       long nowMs) {
        int sourceMapId = agent.getMapId();
        Talk talk = talk(entry, agent, leg.npcId(), leg.selectionArray(), gateway, state, nowMs);
        if (talk == Talk.APPROACHING || talk == Talk.WAITING) {
            state.markActiveTravel(sourceMapId, destinationMapId);
            return new TravelOutcome(Status.MOVING, sourceMapId, leg.toMapId(), destinationMapId, false);
        }
        if (talk != Talk.HELD) {
            return unavailableLeg(state, leg, sourceMapId, destinationMapId, nowMs);
        }
        int nowOnMapId = agent.getMapId();
        if (nowOnMapId == leg.toMapId()) {
            state.clearBoardingRefusal();
            state.recordPortalSuccess(nowOnMapId, nowMs, PORTAL_ARRIVAL_SETTLE_MS);
            log.info("Agent '{}' rode {} from map {} to map {} on the way to map {}",
                    agent.getName(), leg.id(), sourceMapId, nowOnMapId, destinationMapId);
            return new TravelOutcome(Status.MOVING, sourceMapId, nowOnMapId, destinationMapId, false);
        }
        if (AgentTravelLegCatalog.rideMap(nowOnMapId) || agent.getEventInstance() != null) {
            state.clearBoardingRefusal();
            state.clearActiveTravel();
            log.info("Agent '{}' boarded {} on map {} (now on map {}) on the way to map {}",
                    agent.getName(), leg.id(), sourceMapId, nowOnMapId, destinationMapId);
            return new TravelOutcome(Status.MOVING, sourceMapId, nowOnMapId, destinationMapId, false);
        }
        if (!leg.rideMapIds().isEmpty() && state.boardingRefusedFor(legKey(leg), nowMs) < BOARDING_PATIENCE_MS) {
            state.markActiveTravel(sourceMapId, destinationMapId);
            return new TravelOutcome(Status.MOVING, sourceMapId, leg.toMapId(), destinationMapId, false);
        }
        return unavailableLeg(state, leg, sourceMapId, destinationMapId, nowMs);
    }

    /** Walks up to a travel NPC and holds its conversation with these selections, at most once per NPC_RETRY_MS. */
    private static Talk talk(AgentRuntimeEntry entry,
                             Character agent,
                             int npcId,
                             int[] selections,
                             PrimitiveCapabilityGateway gateway,
                             AgentVictoriaRouteState state,
                             long nowMs) {
        if (nowMs < state.npcRetryAtMs()) {
            return Talk.WAITING;
        }
        Point npc = gateway.npcPosition(agent, npcId);
        if (npc == null) {
            return Talk.MISSING;
        }
        if (!gateway.grounded(agent) || !AgentNpcInteractionReachabilityService.canInteract(
                entry, agent, npc, NPC_INTERACTION_DISTANCE_PX)) {
            gateway.navigate(entry, npc, true);
            return Talk.APPROACHING;
        }
        gateway.facePosition(agent, npc);
        state.npcRetryAt(nowMs + NPC_RETRY_MS);
        return gateway.runNpcScript(agent, npcId, selections) ? Talk.HELD : Talk.FAILED;
    }

    private static boolean holds(Character agent, AgentTravelLegCatalog.Ticket ticket,
                                 PrimitiveCapabilityGateway gateway) {
        return ticket == null || gateway.itemCount(agent, ticket.itemId()) > 0;
    }

    /** A leg's failures and blocks share the blocked-edge memory under a key no portal edge can have. */
    private static long legKey(AgentTravelLegCatalog.Leg leg) {
        return AgentVictoriaTrainingRouteCatalog.edgeKey(-leg.fromMapId(), leg.toMapId());
    }

    private static TravelOutcome unavailableLeg(AgentVictoriaRouteState state,
                                                AgentTravelLegCatalog.Leg leg,
                                                int sourceMapId,
                                                int destinationMapId,
                                                long nowMs) {
        state.clearActiveTravel();
        boolean blocked = state.recordFailure(legKey(leg), nowMs, FAILED_EDGE_BLOCK_MS);
        return new TravelOutcome(Status.PORTAL_UNAVAILABLE, sourceMapId, leg.toMapId(), destinationMapId, blocked);
    }

    private static TravelOutcome unavailable(AgentVictoriaRouteState state,
                                             int sourceMapId,
                                             int nextMapId,
                                             int destinationMapId,
                                             long nowMs) {
        long edge = AgentVictoriaTrainingRouteCatalog.edgeKey(sourceMapId, nextMapId);
        boolean blocked = state.recordFailure(edge, nowMs, FAILED_EDGE_BLOCK_MS);
        return new TravelOutcome(Status.PORTAL_UNAVAILABLE, sourceMapId, nextMapId,
                destinationMapId, blocked);
    }
}

package server.agents.progression;

import client.Character;
import client.QuestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.objective.AgentNpcInteractionReachabilityService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.HashMap;
import java.util.HashSet;
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
        Set<Long> blockedEdges = withGates(state.blockedEdges(nowMs), agent);
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
            PortalChoice choice = reachablePortalTo(entry, agent, nextMap, destinationMapId, blockedEdges, gateway,
                    state);
            Integer around = choice.trapped()
                    ? hopAround(entry, agent, nextMap, destinationMapId, blockedEdges, gateway) : null;
            if (around != null) {
                nextMap = around;
                portalId = AgentVictoriaTrainingRouteCatalog.scriptedPortalId(sourceMapId, nextMap);
                scriptedPortal = portalId != null;
                if (portalId == null) {
                    choice = reachablePortalTo(entry, agent, nextMap, destinationMapId, blockedEdges, gateway, state);
                }
            }
            if (portalId == null) {
                portalId = choice.portalId();
            }
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
     * target mid-jump would send it back toward the first portal. Of the portals it can walk to, one that does not
     * strand it on the next map comes first: on The Tunnel That Lost Light II (105090100) both portals lead to Another
     * Entrance, but east01 sets the Agent down on its middle floor, which never reaches east00 to 105090300 on the top
     * floor; east00 sets it down there.
     */
    private static PortalChoice reachablePortalTo(AgentRuntimeEntry entry,
                                                  Character agent,
                                                  int nextMapId,
                                                  int destinationMapId,
                                                  Set<Long> blockedEdges,
                                                  PrimitiveCapabilityGateway gateway,
                                                  AgentVictoriaRouteState state) {
        List<Integer> portalIds = gateway.directPortalIdsTo(agent, nextMapId);
        if (portalIds.isEmpty()) {
            return new PortalChoice(gateway.directPortalIdTo(agent, nextMapId), false);
        }
        Integer kept = state.keptPortal(nextMapId);
        if (kept != null && portalIds.contains(kept) && notRuledOut(entry, agent, kept, gateway)
                && !strands(entry, agent, kept, nextMapId, destinationMapId, blockedEdges, gateway)) {
            return new PortalChoice(kept, false);
        }
        Integer stranding = null;
        for (Integer portalId : portalIds) {
            if (!notRuledOut(entry, agent, portalId, gateway)) {
                continue;
            }
            if (!strands(entry, agent, portalId, nextMapId, destinationMapId, blockedEdges, gateway)) {
                state.keepPortal(nextMapId, portalId);
                return new PortalChoice(portalId, false);
            }
            if (stranding == null) {
                stranding = portalId;
            }
        }
        if (stranding != null) {
            state.keepPortal(nextMapId, stranding);
            return new PortalChoice(stranding, true);
        }
        return new PortalChoice(portalIds.get(0), true);
    }

    /** The portal travelStatus walks to, and whether no portal to that map gets the Agent through it. */
    private record PortalChoice(Integer portalId, boolean trapped) {
    }

    private static boolean notRuledOut(AgentRuntimeEntry entry,
                                       Character agent,
                                       int portalId,
                                       PrimitiveCapabilityGateway gateway) {
        Point portal = gateway.portalPosition(agent, portalId);
        return portal != null && gateway.canReach(entry, agent, portal)
                && !gateway.unwalkablePortal(entry, agent, portalId);
    }

    /**
     * For a hop the Agent can walk to none of the portals of from here, or every one it can strands it there: the next
     * hop of a route around it, when one is walkable, else null. Live 2026-10-25 to 10-29, 1,579 resupply
     * walks stalled 300 s on a map without reaching its portal; 698 stood on hops like these. Crimsonwood Mountain's
     * shortest way down (610020002/3/5) is its pit portals, which no walk reaches, and 610010004's portal to
     * 610010005 stands on a ledge nothing climbs to (513); Another Entrance (105090200) reached on its middle or
     * bottom floor has its only portal to 105090300 on the top floor (185). canReach ignores movement-skill edges, so
     * this only prefers a hop that is walkable over one that looks trapped: with no walkable alternative the hop stays
     * as before, and the trip's no-progress watchdog ends a walk that reaches no portal.
     */
    private static Integer hopAround(AgentRuntimeEntry entry,
                                     Character agent,
                                     int nextMapId,
                                     int destinationMapId,
                                     Set<Long> blockedEdges,
                                     PrimitiveCapabilityGateway gateway) {
        int sourceMapId = agent.getMapId();
        Set<Long> passed = new HashSet<>(blockedEdges);
        Integer hop = nextMapId;
        while (hop != null && hop != sourceMapId) {
            passed.add(AgentVictoriaTrainingRouteCatalog.edgeKey(sourceMapId, hop));
            hop = AgentVictoriaTrainingRouteCatalog.nextHop(sourceMapId, destinationMapId, passed);
            if (hop != null && walkable(entry, agent, hop, destinationMapId, blockedEdges, gateway) == Boolean.TRUE) {
                return hop;
            }
        }
        return null;
    }

    /**
     * TRUE when a portal to hop is authored or one the Agent can walk to does not strand it there, FALSE when the
     * portals to hop are known and none does, null when the gateway lists none.
     */
    private static Boolean walkable(AgentRuntimeEntry entry,
                                    Character agent,
                                    int hop,
                                    int destinationMapId,
                                    Set<Long> blockedEdges,
                                    PrimitiveCapabilityGateway gateway) {
        if (AgentVictoriaTrainingRouteCatalog.scriptedPortalId(agent.getMapId(), hop) != null) {
            return Boolean.TRUE;
        }
        List<Integer> portalIds = gateway.directPortalIdsTo(agent, hop);
        if (portalIds.isEmpty()) {
            return null;
        }
        for (Integer portalId : portalIds) {
            if (notRuledOut(entry, agent, portalId, gateway)
                    && !strands(entry, agent, portalId, hop, destinationMapId, blockedEdges, gateway)) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * True when the gateway knows which maps the Agent walks on to from where portalId sets it down on hop, and none
     * of them leads on to the destination: every way on from hop leaves by a portal it cannot walk to from there.
     * Authored scripted portals on hop count as walkable.
     */
    private static boolean strands(AgentRuntimeEntry entry,
                                   Character agent,
                                   int portalId,
                                   int hop,
                                   int destinationMapId,
                                   Set<Long> blockedEdges,
                                   PrimitiveCapabilityGateway gateway) {
        if (hop == destinationMapId) {
            return false;
        }
        Set<Integer> exits = gateway.landingExits(entry, agent, portalId);
        if (exits == null || exits.isEmpty()) {
            return false;
        }
        Set<Long> unwalked = new HashSet<>(blockedEdges);
        for (int next : AgentVictoriaTrainingRouteCatalog.adjacentMaps(hop)) {
            if (!exits.contains(next) && AgentVictoriaTrainingRouteCatalog.scriptedPortalId(hop, next) == null) {
                unwalked.add(AgentVictoriaTrainingRouteCatalog.edgeKey(hop, next));
            }
        }
        return AgentVictoriaTrainingRouteCatalog.nextHop(hop, destinationMapId, unwalked) == null;
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
        blockedEdges = withGates(blockedEdges, AgentRuntimeIdentityRuntime.bot(entry));
        return AgentVictoriaTrainingRouteCatalog.distance(sourceMapId, destinationMapId, blockedEdges);
    }

    /**
     * One step of a planned way: a portal from one map to the next ({@code boardingNpcId} 0, no
     * {@code boardingPortal}), or a leg boarded there by talking to {@code boardingNpcId} or walking into
     * {@code boardingPortal}.
     */
    public record PlannedStep(int fromMapId, int toMapId, int boardingNpcId, String boardingPortal) {
        public PlannedStep(int fromMapId, int toMapId, int boardingNpcId) {
            this(fromMapId, toMapId, boardingNpcId, null);
        }

        public boolean ride() {
            return boardingNpcId > 0 || boardingPortal != null;
        }
    }

    /**
     * The whole way from sourceMapId to destinationMapId, for a host that plans around a walk (a citizen choosing where
     * it boards, or which map it comes into its destination from): the portal hops while a portal path reaches it, with
     * the edges this Agent has blocked left out as travelStatus leaves them out; else the cheapest way that also boards
     * the travel legs (RIDE_HOP_COST a leg), fares and tickets not counted, and legs that kept refusing it left out.
     * Empty when nothing reaches the destination, or the Agent is there. Ties go as ridePath breaks them, so the hops
     * may differ from nextHop's where two walking paths are as short. Gated portals it may not pass and legs above its
     * level are left out as travelStatus leaves them out; with no Agent to ask, both are.
     */
    public static List<PlannedStep> plannedRoute(AgentRuntimeEntry entry,
                                                 int sourceMapId,
                                                 int destinationMapId,
                                                 long nowMs) {
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        Set<Long> blocked = entry == null ? Set.of()
                : entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY).blockedEdges(nowMs);
        Set<Long> blockedEdges = withGates(blocked, agent);
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                sourceMapId, destinationMapId, blockedEdges, leg -> false, RIDE_HOP_COST);
        if (path.isEmpty()) {
            path = AgentVictoriaTrainingRouteCatalog.ridePath(sourceMapId, destinationMapId, blockedEdges,
                    leg -> !blockedEdges.contains(legKey(leg)) && levelFits(agent, leg), RIDE_HOP_COST);
        }
        return path.stream().map(step -> new PlannedStep(step.fromMapId(), step.toMapId(),
                step.leg() == null ? 0 : step.leg().npcId(), step.leg() == null ? null : step.leg().portal()))
                .toList();
    }

    /**
     * {@code blocked} plus the edges of the gated scripted portals this Agent may not pass yet (An Empty House's door
     * before the Mushroom Castle quests), so no route walks it into a portal that refuses it. Unknown Agent: none open.
     */
    static Set<Long> withGates(Set<Long> blocked, Character agent) {
        Set<Long> out = null;
        for (AgentVictoriaPortalRouteGraph.ScriptedPortal portal : AgentVictoriaTrainingRouteCatalog.gatedPortals()) {
            if (agent != null && opens(portal.gate(), agent)) {
                continue;
            }
            if (out == null) {
                out = new HashSet<>(blocked);
            }
            out.add(AgentVictoriaTrainingRouteCatalog.edgeKey(portal.sourceMapId(), portal.destinationMapId()));
        }
        return out == null ? blocked : out;
    }

    /** As the portal script asks: one of completedAny completed, or one of activeAny started or completed. */
    static boolean opens(AgentVictoriaPortalRouteGraph.QuestGate gate, Character agent) {
        for (int quest : gate.completedAny()) {
            if (agent.getQuestStatus(quest) == QuestStatus.Status.COMPLETED.getId()) {
                return true;
            }
        }
        for (int quest : gate.activeAny()) {
            int status = agent.getQuestStatus(quest);
            if (status == QuestStatus.Status.STARTED.getId() || status == QuestStatus.Status.COMPLETED.getId()) {
                return true;
            }
        }
        return false;
    }

    /** True when the leg's NPC lets this Agent on (Jeff from level 50); a leg with no floor lets anyone on. */
    private static boolean levelFits(Character agent, AgentTravelLegCatalog.Leg leg) {
        return leg.minLevel() <= 0 || agent != null && agent.getLevel() >= leg.minLevel();
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
        Predicate<AgentTravelLegCatalog.Leg> usable = leg -> !blockedEdges.contains(legKey(leg)) && levelFits(agent, leg)
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
     * A leg to a saved location that sets the rider down elsewhere than its default (Spinel's way back to where the
     * tour began) has done its job: the route goes on from there.
     */
    private static TravelOutcome board(AgentRuntimeEntry entry,
                                       Character agent,
                                       AgentTravelLegCatalog.Leg leg,
                                       PrimitiveCapabilityGateway gateway,
                                       AgentVictoriaRouteState state,
                                       int destinationMapId,
                                       long nowMs) {
        int sourceMapId = agent.getMapId();
        Talk talk = leg.portal() != null ? walkInto(entry, agent, leg.portal(), gateway, state, nowMs)
                : talk(entry, agent, leg.npcId(), leg.selectionArray(), gateway, state, nowMs);
        if (talk == Talk.APPROACHING || talk == Talk.WAITING) {
            state.markActiveTravel(sourceMapId, destinationMapId);
            return new TravelOutcome(Status.MOVING, sourceMapId, leg.toMapId(), destinationMapId, false);
        }
        if (talk != Talk.HELD) {
            return unavailableLeg(state, leg, sourceMapId, destinationMapId, nowMs);
        }
        int nowOnMapId = agent.getMapId();
        if (nowOnMapId == leg.toMapId() || leg.savedLocation() != null && nowOnMapId != sourceMapId
                && !AgentTravelLegCatalog.rideMap(nowOnMapId)) {
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

    /**
     * Walks up to a boarding portal and goes through it (Kerning Square Station's exit starts the train back), at most
     * once per NPC_RETRY_MS: HELD once the portal took it, FAILED when it refused.
     */
    private static Talk walkInto(AgentRuntimeEntry entry,
                                 Character agent,
                                 String portalName,
                                 PrimitiveCapabilityGateway gateway,
                                 AgentVictoriaRouteState state,
                                 long nowMs) {
        if (nowMs < state.npcRetryAtMs()) {
            return Talk.WAITING;
        }
        Integer portalId = gateway.portalId(agent, portalName);
        Point portal = portalId == null ? null : gateway.portalPosition(agent, portalId);
        if (portal == null) {
            return Talk.MISSING;
        }
        if (agent.getPosition().distanceSq(portal) > PORTAL_DISTANCE_PX * PORTAL_DISTANCE_PX) {
            gateway.navigate(entry, portal, true);
            return Talk.APPROACHING;
        }
        state.npcRetryAt(nowMs + NPC_RETRY_MS);
        return gateway.enterPortal(agent, portalId) ? Talk.HELD : Talk.FAILED;
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

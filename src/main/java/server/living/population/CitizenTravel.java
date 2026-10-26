package server.living.population;

import client.Character;
import config.PopulationConfig;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.progression.AgentVictoriaRouteRuntime.PlannedStep;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A move a citizen makes on foot because someone can see it (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md 2): it walks to the
 * right portal on its map and goes through, from map to map along the route graphs, and on a watched dock walks up to
 * the boarding NPC and is gone the way a boarding player is (user decision 2026-10-25). The map it stands on decides each
 * second: once nobody can see it there, the move goes the cheap way (PresenceScheduler's delayed map change), and the
 * cheap way re-checks before it moves, so a citizen never vanishes, or appears off a portal, in front of a player. The
 * session holds the trip while it lasts ({@link CitizenSession#trip}); the presence lock is never held here.
 */
final class CitizenTravel {
    private static final Logger log = LoggerFactory.getLogger(CitizenTravel.class);
    /** WZ portal types: a start point ("sp"), and a visible scripted portal (a town's Free Market portal). */
    private static final int START_POINT = 0;
    private static final int SCRIPTED_VISIBLE = 7;
    /** The town portals into the Free Market run market01.js..market56.js: each saves the town and warps to out00. */
    private static final String MARKET_SCRIPT_PREFIX = "market";
    private static final String MARKET_ARRIVAL_PORTAL = "out00";
    /**
     * The maps outside the Free Market with a portal into it (Map.wz: a portal running a market script; Ellinia, Kerning,
     * Lith Harbor and Sleepywood have none). A citizen bound for the market walks to the nearest and goes in by that
     * portal. CitizenTravelMarketMapsTest re-derives the set from Map.wz.
     */
    static final Set<Integer> MARKET_MAPS = Set.of(100000100, 102000000, 120000200, 130000200, 140000000, 200000000,
            211000100, 220000000, 221000000, 222000000, 230000000, 240000000, 250000000, 251000000, 260000000, 261000000,
            540000000, 541000000, 550000000, 551000000, 600000000, 801000300);

    private final Population population;
    private final PresenceScheduler presence;

    /** One move on foot. */
    static final class Trip {
        final CitizenSession.Move move;
        final MapleMap target;
        final long startedAtMs;
        final int fromMapId;
        /** The engine walk it is on now; null between two walks. */
        volatile AgentMapWalk.Walk walk;
        /** The leg it walks up to the boarding NPC of; null when it walks to no dock. */
        volatile PlannedStep boarding;
        /** When it is gone from the boarding NPC it stands at; 0 while it stands at none. */
        volatile long boardAtMs;
        /** It has tried walking out by the nearest portal once. */
        volatile boolean walkedOut;
        /** It found no way on and waits for nobody to watch its map. */
        volatile boolean lingering;

        Trip(CitizenSession.Move move, MapleMap target, long startedAtMs, int fromMapId) {
            this.move = move;
            this.target = target;
            this.startedAtMs = startedAtMs;
            this.fromMapId = fromMapId;
        }
    }

    CitizenTravel(Population population, PresenceScheduler presence) {
        this.population = population;
        this.presence = presence;
    }

    private PopulationConfig config() {
        return population.config();
    }

    boolean enabled() {
        return config().PORTAL_TRAVEL_ENABLED;
    }

    /** True when a move to {@code target} is made on foot: on, it stands elsewhere, and someone can see its map. */
    boolean walks(Character agent, MapleMap target) {
        return enabled() && agent != null && target != null && agent.getMapId() != target.getId() && watched(agent.getMap());
    }

    /** True while a player is on {@code map}: the lock-free count of real clients the Agent mode policy reads too. */
    static boolean watched(MapleMap map) {
        return map != null && map.isObservedByPlayer();
    }

    /** Sets off on foot for {@code move}; the first step runs on the timer at once. */
    void begin(CitizenSession session, CitizenSession.Move move, MapleMap target, long nowMs) {
        Character agent = session.agent();
        if (agent == null) {
            return;
        }
        cancel(session);
        AgentRuntimeEntry entry = session.entry;
        if (entry != null) { // from a cheap pause or a linger: what it was doing there winds down as travel's start does
            if (AgentTownLifeRuntime.active(entry)) {
                AgentTownLifeRuntime.stop(entry, agent);
                AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
            }
            AgentMovementCommandRuntime.stop(entry);
        }
        Trip trip = new Trip(move, target, nowMs, agent.getMapId());
        session.trip = trip;
        log.info("citizen {} sets off on foot from {} to {} ({}): someone can see it", session.name(), agent.getMapId(),
                target.getId(), move.activity());
        later(session, trip, 0L);
    }

    /** Ends the session's trip on foot, if it has one, and the walk and movement it left. */
    void cancel(CitizenSession session) {
        Trip trip = session.trip;
        if (trip != null) {
            end(session, trip);
        }
    }

    private void later(CitizenSession session, Trip trip, long delayMs) {
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> step(session, trip), delayMs);
    }

    /** One check of a trip: arrived, handed to the cheap way, or one more look at its walk. */
    void step(CitizenSession session, Trip trip) {
        if (session.trip != trip) {
            return; // ended, or a newer move replaced it
        }
        try {
            Character agent = session.agent();
            if (agent == null || !presence.holds(session) || trip.move != session.latestMove) {
                end(session, trip);
                return;
            }
            if (PresenceScheduler.onSupplyTrip(session)) { // it ran low on the way: its own shop trip moves it first
                end(session, trip);
                presence.waitForSupplyTrip(session, agent, trip.move);
                return;
            }
            long now = System.currentTimeMillis();
            presence.visibleCap().refresh(session, now); // on its way: it keeps its slot on the destination
            MapleMap here = agent.getMap();
            session.mapId = agent.getMapId(); // chatter and the party quests read the map it stands on
            if (agent.getMapId() == trip.target.getId()) {
                end(session, trip);
                log.info("citizen {} arrived on foot at {} from {} in {} s", session.name(), trip.target.getId(),
                        trip.fromMapId, (now - trip.startedAtMs) / 1_000L);
                presence.landed(session, trip.move, trip.target, now);
                return;
            }
            if (!agent.isAlive()) {
                later(session, trip, config().PORTAL_TRAVEL_CHECK_MS); // down on the way: it goes on once it is up
                return;
            }
            if (!watched(here)) {
                end(session, trip);
                log.info("citizen {} is on map {} where nobody can see it now; it goes on to {} the cheap way",
                        session.name(), agent.getMapId(), trip.target.getId());
                presence.cheapMove(session, trip.move, trip.target);
                return;
            }
            advance(session, trip, agent, here, now);
            if (session.trip == trip) {
                later(session, trip, config().PORTAL_TRAVEL_CHECK_MS);
            }
        } catch (RuntimeException failure) {
            log.warn("citizen {}: its walk to {} failed; the move is dropped", session.name(), trip.target.getId(), failure);
            end(session, trip);
        }
    }

    /** Someone can see it: it lets its TownLife finish, then walks, boards, or walks out. */
    private void advance(CitizenSession session, Trip trip, Character agent, MapleMap here, long now) {
        AgentRuntimeEntry entry = session.entry;
        if (trip.lingering || entry == null) {
            return; // it waits for nobody to watch (the step's unwatched check)
        }
        if (now - trip.startedAtMs >= config().PORTAL_TRAVEL_MAX_MS) {
            linger(session, trip, agent, here, now, "still on foot after " + (now - trip.startedAtMs) / 1_000L + " s");
            return;
        }
        if (AgentTownLifeRuntime.active(entry)) {
            if (now - trip.startedAtMs < config().PORTAL_TRAVEL_TOWNLIFE_GRACE_MS) {
                return; // it finishes what it is doing first, as it did before it walked
            }
            AgentTownLifeRuntime.forceStop(entry, agent, "citizen sets off on foot");
            AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
        }
        if (trip.boardAtMs > 0L) {
            if (now >= trip.boardAtMs) {
                board(session, trip, agent, now);
            }
            return;
        }
        AgentMapWalk.Walk walk = trip.walk;
        if (walk == null) {
            next(session, trip, agent, here, now);
            return;
        }
        switch (walk.status()) {
            case WALKING -> {
            }
            case ARRIVED -> arrived(session, trip, agent, walk, now);
            case NO_ROUTE, STALLED -> lost(session, trip, agent, here, walk, now);
        }
    }

    /**
     * Its next walk from where it stands: the route graphs' walking route, else a portal on this map straight to the
     * destination (a Free Market door, a town's market portal), else, for the market, the nearest map with a market
     * portal, else the route to its first sea leg's dock and up to the boarding NPC, else out by the nearest visible
     * portal.
     */
    private void next(CitizenSession session, Trip trip, Character agent, MapleMap here, long now) {
        int destination = trip.target.getId();
        List<PlannedStep> route = AgentVictoriaRouteRuntime.plannedRoute(session.entry, here.getId(), destination, now);
        PlannedStep ride = route.stream().filter(PlannedStep::ride).findFirst().orElse(null);
        if (!route.isEmpty() && ride == null) {
            walk(session, trip, agent, AgentMapWalk.Target.map(destination), now);
            return;
        }
        Integer door = doorTo(here, destination);
        if (door != null) {
            walk(session, trip, agent, AgentMapWalk.Target.enter(here.getId(), door), now);
            return;
        }
        int market = destination == FieldCatalog.FM_ENTRANCE ? nearestMarketMap(session, here.getId(), now) : 0;
        if (market > 0) {
            walk(session, trip, agent, AgentMapWalk.Target.map(market), now); // there, its market portal is the door
            return;
        }
        if (ride != null) {
            trip.boarding = ride;
            walk(session, trip, agent, AgentMapWalk.Target.npc(ride.fromMapId(), ride.boardingNpcId()), now);
            return;
        }
        walkOut(session, trip, agent, here, now);
    }

    /**
     * The map with a market portal fewest portals away on foot; 0 when none is (another continent's, or in the market).
     * routeHops is a walking BFS that turns down another continent's map at once.
     */
    private static int nearestMarketMap(CitizenSession session, int fromMapId, long now) {
        if (FieldCatalog.isFreeMarket(fromMapId)) {
            return 0;
        }
        int nearest = 0;
        int fewest = Integer.MAX_VALUE;
        for (int market : MARKET_MAPS) {
            int hops = AgentVictoriaRouteRuntime.routeHops(session.entry, fromMapId, market, now);
            if (hops > 0 && hops < fewest) {
                nearest = market;
                fewest = hops;
            }
        }
        return nearest;
    }

    private void walk(CitizenSession session, Trip trip, Character agent, AgentMapWalk.Target target, long now) {
        trip.walk = AgentMapWalk.start(session.entry, agent, target, config().PORTAL_TRAVEL_HOP_TIMEOUT_MS, now);
    }

    /** Its walk got there: at a dock's NPC it stands a moment, as if talking; at the portal it walked out by, it is gone. */
    private void arrived(CitizenSession session, Trip trip, Character agent, AgentMapWalk.Walk walk, long now) {
        stopWalk(trip, agent.getId());
        switch (walk.target().finish()) {
            case NPC -> trip.boardAtMs = now + config().PORTAL_TRAVEL_BOARD_MS;
            case REACH_PORTAL -> {
                end(session, trip);
                log.info("citizen {} walked out of {} by portal {} for {}", session.name(), agent.getMapId(),
                        walk.target().id(), trip.target.getId());
                MapleMap target = trip.target;
                CitizenSession.Move move = trip.move;
                MapVisibleCap cap = presence.visibleCap();
                if (!cap.holds(session, target, now) && !cap.admit(session, target, now)) {
                    // gone through the portal in front of a player, so it must come out somewhere: its slot lapsed
                    // and the destination filled, so another place of the same kind, else the destination after all
                    cap.countRefusedArrival();
                    PresenceScheduler.Placement other = presence.elsewhere(session, move.activity(), target.getId(), now);
                    if (other != null && cap.admit(session, other.map(), now)) {
                        target = other.map();
                        move = new CitizenSession.Move(other.activity(), target.getId());
                        session.latestMove = move;
                    } else {
                        log.warn("citizen {} comes out on full map {}: nowhere else has room", session.name(), target.getId());
                    }
                }
                presence.placeAndLand(session, agent, move, target, entryPortal(session, agent.getMapId(), target, now), now);
            }
            default -> {
                // MAP: it stands on its destination, which the next step lands; ENTER_PORTAL: the portal took it on,
                // and the next step goes on from the map it came out on
            }
        }
    }

    /** It is gone from the dock the way a boarding player is, and comes off at the leg's far end (portal 0, Boats.js). */
    private void board(CitizenSession session, Trip trip, Character agent, long now) {
        PlannedStep leg = trip.boarding;
        trip.boardAtMs = 0L;
        trip.boarding = null;
        MapleMap dock = leg == null ? null : population.map(session.channel, leg.toMapId());
        if (dock == null) {
            linger(session, trip, agent, agent.getMap(), now, "no map at the far end of its ride");
            return;
        }
        log.info("citizen {} boards at {} on {} for {} on its way to {}", session.name(), leg.boardingNpcId(),
                agent.getMapId(), dock.getId(), trip.target.getId());
        AgentMovementCommandRuntime.stop(session.entry);
        presence.placeOnTick(session, agent, dock, dock.getPortal(0));
        session.mapId = dock.getId();
    }

    /** Its walk found no route or stood too long: it walks out by the nearest portal once, else waits for nobody to watch. */
    private void lost(CitizenSession session, Trip trip, Character agent, MapleMap here, AgentMapWalk.Walk walk, long now) {
        stopWalk(trip, agent.getId());
        trip.boarding = null;
        AgentMovementCommandRuntime.stop(session.entry);
        String why = walk.status() == AgentMapWalk.Status.STALLED ? "stood on one map too long" : "found no way";
        if (!trip.walkedOut && walk.target().finish() != AgentMapWalk.Finish.REACH_PORTAL) {
            log.info("citizen {}: its walk to {} {} on map {}; it walks out by the nearest portal", session.name(),
                    walk.target().mapId(), why, agent.getMapId());
            walkOut(session, trip, agent, here, now);
            return;
        }
        linger(session, trip, agent, here, now, "its walk " + why);
    }

    private void walkOut(CitizenSession session, Trip trip, Character agent, MapleMap here, long now) {
        trip.walkedOut = true;
        Portal exit = exitPortal(here, agent.getPosition());
        if (exit == null) {
            linger(session, trip, agent, here, now, "no portal to walk out by");
            return;
        }
        walk(session, trip, agent, AgentMapWalk.Target.reach(here.getId(), exit.getId()), now);
    }

    /** No way on that a player could watch: it goes back to what it was doing there until its map is empty. */
    private void linger(CitizenSession session, Trip trip, Character agent, MapleMap here, long now, String why) {
        trip.lingering = true;
        stopWalk(trip, agent.getId());
        log.info("citizen {} on map {} waits for nobody to watch before it goes on to {}: {}", session.name(),
                agent.getMapId(), trip.target.getId(), why);
        if (here != null) {
            presence.linger(session, here, now);
        }
    }

    private void end(CitizenSession session, Trip trip) {
        if (session.trip == trip) {
            session.trip = null;
        }
        boolean walked = trip.walk != null;
        stopWalk(trip, session.characterId);
        if (walked && session.entry != null) {
            AgentMovementCommandRuntime.stop(session.entry); // the walk's last move would carry it on to that portal
        }
    }

    private static void stopWalk(Trip trip, int agentId) {
        AgentMapWalk.Walk walk = trip.walk;
        trip.walk = null;
        AgentMapWalk.stop(agentId, walk);
    }

    // ---- portals ---------------------------------------------------------------------------

    /**
     * Where a citizen coming from {@code fromMapId} comes into {@code target}, as a player would: the Free Market's out00
     * (where its market portals and doors put players); after a ride, portal 0; one portal away, the portal its own
     * map's portal names (what GenericPortal.enterPortal puts a player at); else the portal on the target that leads back
     * to the map the route enters it from. Map.wz 2026-10-25: 86% of the Victoria graph's edges and 89% of the other
     * continents' have that return portal; the named portal covers all but 3 and 5. Null when neither: the caller keeps
     * portal 0.
     */
    Portal entryPortal(CitizenSession session, int fromMapId, MapleMap target, long now) {
        if (FieldCatalog.isFreeMarket(target.getId())) {
            Portal out = target.getPortal(MARKET_ARRIVAL_PORTAL);
            if (out != null) {
                return out;
            }
        }
        List<PlannedStep> route = AgentVictoriaRouteRuntime.plannedRoute(session.entry, fromMapId, target.getId(), now);
        int previous = fromMapId;
        if (!route.isEmpty()) {
            PlannedStep last = route.get(route.size() - 1);
            if (last.ride()) {
                return target.getPortal(0);
            }
            previous = last.fromMapId();
        }
        Character agent = session.agent();
        Portal named = previous == fromMapId && agent != null && agent.getMapId() == fromMapId
                ? namedPortal(agent.getMap(), target) : null; // its own map is loaded; a map further back may not be
        if (named != null) {
            return named;
        }
        Collection<Portal> portals = target.getPortals();
        if (portals == null) {
            return null;
        }
        for (Portal portal : portals) {
            if (open(portal) && portal.getType() != START_POINT && portal.getTargetMapId() == previous) {
                return portal;
            }
        }
        return null;
    }

    /** The portal on {@code target} that a portal of {@code from} to it names; null when none does. */
    private static Portal namedPortal(MapleMap from, MapleMap target) {
        Collection<Portal> portals = from == null ? null : from.getPortals();
        if (portals == null) {
            return null;
        }
        for (Portal portal : portals) {
            String name = open(portal) && portal.getTargetMapId() == target.getId() ? portal.getTarget() : null;
            Portal named = name == null || name.isEmpty() ? null : target.getPortal(name);
            if (named != null) {
                return named;
            }
        }
        return null;
    }

    /**
     * A portal on {@code here} that goes straight to {@code destination} (a Free Market door or out00), or, for the
     * Free Market entrance from outside it, the town's market portal; null when none does.
     */
    static Integer doorTo(MapleMap here, int destination) {
        Collection<Portal> portals = here.getPortals();
        if (portals == null) {
            return null;
        }
        boolean market = destination == FieldCatalog.FM_ENTRANCE && !FieldCatalog.isFreeMarket(here.getId());
        for (Portal portal : portals) {
            if (!open(portal) || portal.getType() == START_POINT || portal.getType() == Portal.DOOR_PORTAL) {
                continue;
            }
            String script = portal.getScriptName();
            if (script == null ? portal.getTargetMapId() == destination
                    : market && script.startsWith(MARKET_SCRIPT_PREFIX)) {
                return portal.getId();
            }
        }
        return null;
    }

    /** The nearest portal on {@code here} a player could be seen going through: an open map portal, or a visible scripted one. */
    static Portal exitPortal(MapleMap here, Point from) {
        Collection<Portal> portals = here == null ? null : here.getPortals();
        if (portals == null || from == null) {
            return null;
        }
        Portal best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals) {
            boolean leads = portal != null && (portal.getType() == Portal.MAP_PORTAL
                    && portal.getTargetMapId() != MapId.NONE && portal.getTargetMapId() != here.getId()
                    || portal.getType() == SCRIPTED_VISIBLE);
            if (!leads || !open(portal)) {
                continue;
            }
            double distance = portal.getPosition().distanceSq(from);
            if (distance < bestDistance) {
                best = portal;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean open(Portal portal) {
        return portal != null && portal.getPosition() != null && portal.getPortalStatus();
    }
}

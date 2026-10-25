package server.agents.capabilities.expedition.gpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The moves every GPQ stage play makes, as the PQ plays make them: walk up, stand on the ground within reach, then act,
 * with a pause after each portal, hit, pick-up or drop so one tick never acts twice.
 */
final class AgentGpqMoves {
    private static final Logger log = LoggerFactory.getLogger(AgentGpqMoves.class);
    /** How close a member stands to a portal to take it, to a reactor to hit it, and to a drop to pick it up. */
    static final int REACH_PX = 45;
    /** How close a member stands to its spot to drop an item on a gate (the gates' boxes are 60-150 px wide). */
    static final int DROP_REACH_PX = 18;
    /** How close a member waiting somewhere stands to its spot. */
    static final int WAIT_PX = 120;
    static final long PORTAL_PAUSE_MS = 900L;
    static final long HIT_PAUSE_MS = 650L;
    static final long ITEM_PAUSE_MS = 700L;

    private AgentGpqMoves() {
    }

    /** The members in the order they were adopted (their formation slots). */
    static List<Character> bySlot(List<Character> members, AgentGpqRun run) {
        List<Character> out = new ArrayList<>(members);
        out.sort(Comparator.comparingInt(member -> run.ordinal(member.getId())));
        return out;
    }

    /** Walks onto the portal and takes it (a script portal runs as the player's would). */
    static void takePortal(AgentGpqPorts ports, AgentGpqRun run, Character member, String portalName, long nowMs) {
        MapleMap map = member.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (portal == null || entry == null || !standAt(ports, entry, member, new Point(portal.getPosition()), REACH_PX)
                || !run.ready(member.getId(), nowMs)) {
            return;
        }
        ports.gateway().stop(entry);
        ports.partyQuest().enterPortal(member, portal.getId());
        run.busy(member.getId(), nowMs + PORTAL_PAUSE_MS);
    }

    /** Walks to wait near {@code spot}, out of the way of the members at work. */
    static void waitAt(AgentGpqPorts ports, Character member, Point spot) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (spot != null && entry != null && !near(member.getPosition(), spot, WAIT_PX)) {
            ports.gateway().navigate(entry, spot, false);
        }
    }

    /** Walks to wait near the portal {@code portalName}. */
    static void waitByPortal(AgentGpqPorts ports, Character member, String portalName) {
        MapleMap map = member.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        if (portal != null) {
            waitAt(ports, member, new Point(portal.getPosition()));
        }
    }

    /** True once the member stands on the ground within {@code reachPx} of {@code stand}; else it walks there. */
    static boolean standAt(AgentGpqPorts ports, AgentRuntimeEntry entry, Character member, Point stand, int reachPx) {
        if (near(member.getPosition(), stand, reachPx) && ports.gateway().grounded(member)) {
            return true;
        }
        ports.gateway().navigate(entry, stand, true);
        return false;
    }

    /** Walks beside {@code reactor} and hits it once, as a player's swing at it; true when it hit this tick. */
    static boolean hit(AgentGpqPorts ports, AgentGpqRun run, Character member, Reactor reactor, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry == null || reactor == null || !standAt(ports, entry, member, beside(ports, member,
                reactor.getPosition()), REACH_PX) || !run.ready(member.getId(), nowMs)) {
            return false;
        }
        ports.gateway().stop(entry);
        ports.gateway().facePosition(member, reactor.getPosition());
        boolean hit = ports.gateway().hitReactor(member, reactor.getObjectId());
        run.busy(member.getId(), nowMs + HIT_PAUSE_MS);
        return hit;
    }

    /** Walks to the nearest {@code itemId} lying within {@code seekPx} and picks it up; true when one lies there. */
    static boolean pickUpNearest(AgentGpqPorts ports, AgentGpqRun run, Character member, int itemId, int seekPx,
                                 long nowMs) {
        MapItem drop = nearestDrop(ports, member, itemId, seekPx);
        if (drop == null) {
            return false;
        }
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry != null && standAt(ports, entry, member, new Point(drop.getPosition()), REACH_PX)
                && run.ready(member.getId(), nowMs)) {
            ports.gateway().stop(entry);
            ports.pickUp(member, itemId);
            run.busy(member.getId(), nowMs + ITEM_PAUSE_MS);
        }
        return true;
    }

    /** The nearest {@code itemId} lying on the member's map within {@code seekPx} of it; null for none. */
    static MapItem nearestDrop(AgentGpqPorts ports, Character member, int itemId, int seekPx) {
        Point at = member.getPosition();
        if (at == null) {
            return null;
        }
        MapItem nearest = null;
        double best = (double) seekPx * seekPx;
        for (MapItem drop : ports.items(member)) {
            if (drop.getItemId() != itemId || drop.getPosition() == null || drop.isPickedUp()) {
                continue;
            }
            double distance = drop.getPosition().distanceSq(at);
            if (distance <= best) {
                best = distance;
                nearest = drop;
            }
        }
        return nearest;
    }

    /** The drops of {@code itemId} lying inside {@code box} on the member's map. */
    static List<MapItem> dropsIn(AgentGpqPorts ports, Character member, int itemId, Rectangle box) {
        List<MapItem> found = new ArrayList<>();
        for (MapItem drop : ports.items(member)) {
            if (drop.getItemId() == itemId && drop.getPosition() != null && !drop.isPickedUp()
                    && box.contains(drop.getPosition())) {
                found.add(drop);
            }
        }
        return found;
    }

    /**
     * Walks onto {@code stand} and drops {@code quantity} of {@code itemId} there, as a player puts an item on a gate;
     * true when it dropped this tick.
     */
    static boolean dropAt(AgentGpqPorts ports, AgentGpqRun run, Character member, int itemId, int quantity,
                          Point stand, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry == null || !standAt(ports, entry, member, stand, DROP_REACH_PX) || !run.ready(member.getId(), nowMs)) {
            return false;
        }
        ports.gateway().stop(entry);
        boolean dropped = ports.dropEtc(member, itemId, quantity);
        run.busy(member.getId(), nowMs + ITEM_PAUSE_MS);
        if (dropped) {
            log.info("GPQ drop: {} put {} x{} at {} on {}", member.getName(), itemId, quantity, pointText(stand),
                    member.getMapId());
        }
        return dropped;
    }

    /** The ground {@code 30} px beside {@code target}, on the side the member comes from. */
    static Point beside(AgentGpqPorts ports, Character member, Point target) {
        Point at = member.getPosition();
        int side = at != null && at.x > target.x ? 1 : -1;
        Point candidate = new Point(target.x + side * 30, target.y);
        Point ground = ports.gateway().groundPoint(member.getMap(), candidate);
        return ground != null ? ground : candidate;
    }

    /** The live reactor named {@code name} on the member's map, or null. */
    static Reactor reactorNamed(AgentGpqPorts ports, Character member, String name) {
        for (Reactor reactor : ports.gateway().reactors(member)) {
            if (name.equals(reactor.getName())) {
                return reactor;
            }
        }
        return null;
    }

    /** The reactors of {@code reactorId} on the member's map that stand and take hits. */
    static List<Reactor> live(AgentGpqPorts ports, Character member, int reactorId) {
        List<Reactor> found = new ArrayList<>();
        for (Reactor reactor : ports.gateway().reactors(member)) {
            if (reactor.getId() == reactorId && reactor.isAlive() && reactor.isActive()) {
                found.add(reactor);
            }
        }
        return found;
    }

    /**
     * Records {@code member}'s job in {@code stage} and logs it when it changes, with where it stands, its level and
     * its speed and jump.
     */
    static void noteRole(AgentGpqRun run, Character member, String stage, String role) {
        if (run.role(member.getId(), role)) {
            log.info("GPQ {}: {} will {} (at {}, Lv{} speed {} jump {})", stage, member.getName(), role,
                    pointText(member.getPosition()), member.getLevel(), member.getTotalMoveSpeedStat(),
                    member.getTotalJumpStat());
        }
    }

    static String pointText(Point at) {
        return at == null ? "?" : "(" + at.x + "," + at.y + ")";
    }

    static boolean near(Point a, Point b, int reachPx) {
        return a != null && b != null && a.distanceSq(b) <= (long) reachPx * reachPx;
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}

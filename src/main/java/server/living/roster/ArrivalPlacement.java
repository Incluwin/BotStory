package server.living.roster;

import constants.id.MapId;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where a called companion comes into its owner's map (docs/BOT_MANAGEMENT_PLAN.md P1.5): at the portal
 * nearest the owner, as if it had walked over from the next map, never out of thin air beside them. A visible
 * portal that leads somewhere is preferred; a map without one uses its nearest start point. Hidden, scripted,
 * closed and Mystic Door portals are never used, nor is a portal so far from the owner that follow recovery would
 * teleport the companion beside them on its first ticks; a map with no usable portal returns null, and the caller
 * keeps the beside-owner spawn. A benched companion leaves the same way, walking to the portal nearest it.
 */
final class ArrivalPlacement {
    /** WZ portal type 0 ("sp"): where a character entering the map without a portal lands. */
    private static final int START_POINT = 0;
    private static final int VISIBLE = 0;
    private static final int FALLBACK = 1;
    private static final int UNUSABLE = -1;
    /** A portal must be nearer than this share of the follow-recovery teleport distance (agent-engine.yaml TELEPORT_DIST). */
    static final double REACH_SHARE = 0.8d;
    /** Decision 8a: companions restored at login walk in once the client has loaded the field, 2-8 s apart. */
    static final long LOGIN_ARRIVAL_MS = 2_000L;
    static final long STAGGER_MIN_MS = 2_000L;
    static final long STAGGER_MAX_MS = 8_000L;
    /** A benched companion walking out goes once it is this close to its portal, or when the walk times out. */
    static final int WALK_OUT_REACH_PX = 30;
    static final long WALK_OUT_MAX_MS = 8_000L;
    static final long WALK_OUT_CHECK_MS = 500L;
    /** A companion walking to its owner takes no in-map portal until this many follow distances from its place. */
    static final int ON_FOOT_REACH_FOLLOW_DISTANCES = 2;

    private ArrivalPlacement() {
    }

    /**
     * How near its place beside its owner a companion walks before it may take in-map portals again: past the follow
     * distance, inside which a follower counts as arrived and stops moving, so a walk always gets there.
     */
    static int onFootReachPx() {
        return ON_FOOT_REACH_FOLLOW_DISTANCES * AgentMovementPhysicsConfig.configuredFollowDist();
    }

    /** The arrival point on the map nearest {@code owner}, or null when the map has no usable portal in reach. */
    static Point nearestPortal(MapleMap map, Point owner) {
        return map == null ? null : nearestPortal(map.getPortals(), owner);
    }

    static Point nearestPortal(Collection<Portal> portals, Point owner) {
        if (portals == null || owner == null) {
            return null;
        }
        return best(portals, owner, REACH_SHARE * AgentMovementPhysicsConfig.configuredTeleportDist());
    }

    /**
     * Where a companion that does not follow anyone yet comes into {@code map} (its camp when it sets off, the map it
     * waits on outside a run): the usable portal nearest {@code near}, with no reach limit since no follow recovery
     * runs; the best-ranked one when {@code near} is null. Null when the map has no usable portal.
     */
    static Point portalNear(MapleMap map, Point near) {
        return map == null || map.getPortals() == null ? null
                : best(map.getPortals(), near == null ? new Point() : near, Double.POSITIVE_INFINITY);
    }

    private static Point best(Collection<Portal> portals, Point owner, double reach) {
        Portal best = null;
        int bestRank = UNUSABLE;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals) {
            int rank = rank(portal);
            if (rank == UNUSABLE) {
                continue;
            }
            double distance = portal.getPosition().distanceSq(owner);
            if (distance >= reach * reach) {
                continue; // follow recovery would teleport it beside the owner: a pop-in after all
            }
            if (best == null || rank < bestRank || rank == bestRank && distance < bestDistance) {
                best = portal;
                bestRank = rank;
                bestDistance = distance;
            }
        }
        return best == null ? null : new Point(best.getPosition());
    }

    /** VISIBLE for an open map portal to somewhere, FALLBACK for a plain start point, else UNUSABLE. */
    static int rank(Portal portal) {
        if (portal == null || portal.getPosition() == null || !portal.getPortalStatus()) {
            return UNUSABLE;
        }
        if (portal.getType() == Portal.MAP_PORTAL && portal.getTargetMapId() != MapId.NONE) {
            return VISIBLE;
        }
        if (portal.getType() == START_POINT && portal.getScriptName() == null) {
            return FALLBACK;
        }
        return UNUSABLE;
    }

    /** A random gap between two companions arriving together, so each walk-in is seen on its own. */
    static long staggerMs() {
        return ThreadLocalRandom.current().nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1L);
    }
}

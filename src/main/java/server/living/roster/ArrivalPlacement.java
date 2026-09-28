package server.living.roster;

import constants.id.MapId;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where a called companion comes into its owner's map (docs/BOT_MANAGEMENT_PLAN.md P1.5): at the portal
 * nearest the owner, as if it had walked over from the next map, never out of thin air beside them. A visible
 * portal that leads somewhere is preferred; a map without one uses its nearest start point. Hidden, scripted,
 * closed and Mystic Door portals are never used; a map with no usable portal returns null, and the caller keeps
 * the beside-owner spawn.
 */
final class ArrivalPlacement {
    /** WZ portal type 0 ("sp"): where a character entering the map without a portal lands. */
    private static final int START_POINT = 0;
    private static final int VISIBLE = 0;
    private static final int FALLBACK = 1;
    private static final int UNUSABLE = -1;
    static final long STAGGER_MIN_MS = 300L;
    static final long STAGGER_MAX_MS = 900L;

    private ArrivalPlacement() {
    }

    /** The arrival point on the map nearest {@code owner}, or null when the map has no usable portal. */
    static Point nearestPortal(MapleMap map, Point owner) {
        return map == null ? null : nearestPortal(map.getPortals(), owner);
    }

    static Point nearestPortal(Collection<Portal> portals, Point owner) {
        if (portals == null || owner == null) {
            return null;
        }
        Portal best = null;
        int bestRank = UNUSABLE;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals) {
            int rank = rank(portal);
            if (rank == UNUSABLE) {
                continue;
            }
            double distance = portal.getPosition().distanceSq(owner);
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

    /** A random gap between two companions arriving together, so they come in one after another. */
    static long staggerMs() {
        return ThreadLocalRandom.current().nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1L);
    }
}

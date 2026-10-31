package server.agents.physics;

import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationWalkRegionLookupService;
import server.maps.Foothold;
import server.maps.MapleMap;

/** Client-compatible zMass scoping for airborne foothold-wall collisions. */
public final class AgentWallCollisionPolicy {
    public static final int ALL_GROUPS = Integer.MIN_VALUE;
    public static final int UNKNOWN_GROUP = -1;

    private AgentWallCollisionPolicy() {
    }

    public static boolean collides(MapleMap map, Foothold wall, int moverZMass) {
        if (wall == null || !wall.isWall()) {
            return false;
        }
        int baseZMass = baseZMass(map);
        int wallZMass = wall.getZMass();
        if (moverZMass == ALL_GROUPS || wallZMass < 0 || baseZMass < 0) {
            return true;
        }
        return wallZMass == baseZMass || wallZMass == moverZMass;
    }

    public static int moverZMassForRegion(MapleMap map, int regionId) {
        if (map == null || regionId < 0) {
            return UNKNOWN_GROUP;
        }
        AgentNavigationWalkRegionLookupService.WalkRegionLookup lookup =
                AgentNavigationWalkRegionLookupService.resolveWalkRegionLookup(map);
        if (lookup == null) {
            return UNKNOWN_GROUP;
        }
        AgentNavigationGraph.Region region = lookup.regionsById().get(regionId);
        if (region == null || region.isRopeRegion || region.segments.isEmpty()) {
            return UNKNOWN_GROUP;
        }
        Foothold foothold = lookup.footholdsById().get(region.segments.getFirst().footholdId);
        return foothold == null ? UNKNOWN_GROUP : foothold.getZMass();
    }

    public static int moverZMassAt(MapleMap map, java.awt.Point position) {
        Foothold foothold = AgentGroundingService.findGroundFoothold(map, position);
        if (foothold == null) {
            return UNKNOWN_GROUP;
        }
        java.awt.Point ground = AgentGroundingService.findGroundPoint(map, position);
        if (ground == null || Math.abs(ground.y - position.y) > AgentMovementPhysicsConfig.configuredMaxSlopeUp()) {
            return UNKNOWN_GROUP;
        }
        return foothold.getZMass();
    }

    /**
     * The x direction of the ground a wall bounds: the side the floor capping its run of wall segments extends to
     * (a cliff's upper floor, a block's top). 0 when no single floor caps it.
     */
    public static int groundSide(MapleMap map, Foothold wall) {
        if (map == null || map.getFootholds() == null || wall == null || !wall.isWall()) {
            return 0;
        }
        int x = wall.getX1();
        int topY = Math.min(wall.getY1(), wall.getY2());
        java.util.List<Foothold> footholds = map.getFootholds().getAllFootholds();
        for (int hops = 0; hops < 64; hops++) {
            boolean west = false;
            boolean east = false;
            Foothold above = null;
            for (Foothold foothold : footholds) {
                if (foothold.isWall()) {
                    if (foothold.getX1() == x && foothold.getY1() != foothold.getY2()
                            && Math.max(foothold.getY1(), foothold.getY2()) == topY) {
                        above = foothold;
                    }
                } else if (foothold.getX1() < foothold.getX2()) {
                    west |= foothold.getX2() == x && foothold.getY2() == topY;
                    east |= foothold.getX1() == x && foothold.getY1() == topY;
                }
            }
            if (above == null) {
                return west == east ? 0 : west ? -1 : 1;
            }
            // A ledge branching off part-way up the wall (105090310 x -2, y -460) is not its cap.
            topY = Math.min(above.getY1(), above.getY2());
        }
        return 0;
    }

    private static int baseZMass(MapleMap map) {
        if (map == null || map.getFootholds() == null) {
            return UNKNOWN_GROUP;
        }
        return map.getFootholds().getMinimumZMass();
    }
}

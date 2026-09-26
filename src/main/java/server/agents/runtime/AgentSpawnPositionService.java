package server.agents.runtime;

import server.agents.capabilities.movement.AgentGroundingService;
import server.maps.MapleMap;

import java.awt.Point;

public final class AgentSpawnPositionService {
    private AgentSpawnPositionService() {
    }

    public static Point resolveSpawnPosition(MapleMap map, Point desiredPosition) {
        return resolveSpawnPosition(map, desiredPosition, AgentGroundingService::findGroundPoint);
    }

    /**
     * Spawn point beside a leader instead of on top of them, so the Agent's arrival and self-buffs
     * are visible next to the player rather than drawn over them. Tries the configured side offset
     * to the right, then the left, and keeps only ground that is on the leader's platform
     * (within SPAWN_SIDE_MAX_DROP px vertically); otherwise falls back to the leader's own spot.
     */
    public static Point resolveSpawnBesideLeader(MapleMap map, Point leaderPosition) {
        return resolveSpawnBesideLeader(map, leaderPosition,
                AgentRuntimeConfig.cfg.SPAWN_SIDE_OFFSET,
                AgentRuntimeConfig.cfg.SPAWN_SIDE_MAX_DROP,
                AgentGroundingService::findGroundPoint);
    }

    static Point resolveSpawnBesideLeader(MapleMap map, Point leaderPosition, int sideOffset, int maxDrop,
                                          GroundPointResolver groundPointResolver) {
        if (map == null || leaderPosition == null || sideOffset <= 0) {
            return resolveSpawnPosition(map, leaderPosition, groundPointResolver);
        }
        for (int dx : new int[]{sideOffset, -sideOffset}) {
            Point probe = new Point(leaderPosition.x + dx, leaderPosition.y - 1);
            Point ground = groundPointResolver.findGroundPoint(map, probe);
            if (ground != null && Math.abs(ground.y - leaderPosition.y) <= maxDrop) {
                return ground;
            }
        }
        return resolveSpawnPosition(map, leaderPosition, groundPointResolver);
    }

    static Point resolveSpawnPosition(MapleMap map, Point desiredPosition, GroundPointResolver groundPointResolver) {
        if (map == null || desiredPosition == null) {
            return desiredPosition;
        }

        Point groundPoint = groundPointResolver.findGroundPoint(map, new Point(desiredPosition.x, desiredPosition.y - 1));
        return groundPoint != null ? groundPoint : desiredPosition;
    }

    @FunctionalInterface
    interface GroundPointResolver {
        Point findGroundPoint(MapleMap map, Point point);
    }
}

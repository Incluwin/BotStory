package server.agents.capabilities.recovery;

import client.Character;
import server.agents.capabilities.combat.AgentDeathTickService;
import server.agents.capabilities.movement.AgentMovementBroadcastService;
import server.agents.capabilities.movement.AgentMovementPoseService;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

/**
 * Assembles map, movement, and dialogue operations for Agent respawn recovery.
 */
public final class AgentRespawnCoordinator {
    private AgentRespawnCoordinator() {
    }

    public static void respawnAtNearestTown(AgentRuntimeEntry entry, Character agent) {
        respawnAtNearestTown(
                entry,
                agent,
                AgentDeathConfig.cfg.RESPAWN_HP_PERCENT,
                liveHooks());
    }

    /**
     * P12 G6: the same respawn into {@code map} instead of the nearest town (a revive inside a boss event: the event's
     * own instance of the battle map's return map).
     */
    public static void respawnInto(AgentRuntimeEntry entry, Character agent, MapleMap map) {
        AgentDeathTickService.respawnAt(entry, agent, AgentDeathConfig.cfg.RESPAWN_HP_PERCENT, map, liveHooks());
    }

    private static AgentDeathTickService.RespawnHooks liveHooks() {
        return new AgentDeathTickService.RespawnHooks(
                AgentMapGatewayRuntime.map()::changeMapNear,
                (respawnEntry, respawnAgent, point) ->
                        AgentMovementPoseService.teleportTo(respawnEntry, respawnAgent, point),
                (respawnEntry, ignoredAgent) ->
                        AgentMovementStateResetService.resetEntryStateAfterTeleport(respawnEntry),
                (respawnEntry, ignoredAgent) ->
                        AgentMovementBroadcastService.broadcastMovement(respawnEntry));
    }

    static void respawnAtNearestTown(AgentRuntimeEntry entry,
                                     Character agent,
                                     int restoredHpPercent,
                                     AgentDeathTickService.RespawnHooks hooks) {
        AgentDeathTickService.respawnAtNearestTown(
                entry,
                agent,
                restoredHpPercent,
                hooks);
    }
}

package server.agents.capabilities.navigation;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent-owned map navigation warmup notification throttle.
 *
 * <p>The notice is operator diagnostics only: it goes to the server log, never to the
 * leader's chat, because players should not see engine internals such as cache warmup.
 */
public final class AgentNavigationWarmupService {
    private static final Logger log = LoggerFactory.getLogger(AgentNavigationWarmupService.class);
    private static final long WARMUP_RETRY_SUPPRESSION_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.navigation.AgentNavigationWarmupService.WARMUP_RETRY_SUPPRESSION_MS");
    private static final Map<Integer, Map<Integer, Long>> WARMUP_NOTIFIED = new ConcurrentHashMap<>();

    private AgentNavigationWarmupService() {
    }

    public static void clearLeaderRuntimeState(int leaderId) {
        WARMUP_NOTIFIED.remove(leaderId);
    }

    /**
     * Records that {@code agent} is moving with fallback steering while its map graph warms.
     *
     * @return whether a log line was written (false when throttled or not worth reporting)
     */
    public static boolean notifyWarmup(AgentRuntimeEntry entry, Character agent) {
        Character leader = AgentRelationshipRuntime.interactionTarget(entry);
        if (!AgentCharacterGatewayRuntime.characters().hasLiveClient(leader)
                || agent == null || agent.getMap() == null) {
            return false;
        }
        int leaderId = leader.getId();
        int mapId = agent.getMap().getId();
        long now = System.currentTimeMillis();
        Map<Integer, Long> byMap = WARMUP_NOTIFIED.get(leaderId);
        if (byMap != null) {
            Long last = byMap.get(mapId);
            if (last != null && (now - last) < WARMUP_RETRY_SUPPRESSION_MS) {
                return false;
            }
        }

        long walkable = agent.getMap().getFootholds().getAllFootholds().stream()
                .filter(fh -> !fh.isWall())
                .count();
        if (walkable < 100) {
            return false;
        }
        WARMUP_NOTIFIED.computeIfAbsent(leaderId, k -> new ConcurrentHashMap<>()).put(mapId, now);
        log.info("Agent {} is using fallback movement on map {} while its navigation graph warms "
                        + "(leader={}, walkableFootholds={})",
                agent.getName(), mapId, leader.getName(), walkable);
        return true;
    }
}

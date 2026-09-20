package server.agents.capabilities.looting;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.HashMap;
import java.util.Map;

/** Bounded physical attempts at objective drops, scoped to the current map. */
public final class AgentObjectiveLootApproachState {
    public static final AgentCapabilityStateKey<AgentObjectiveLootApproachState> STATE_KEY =
            new AgentCapabilityStateKey<>("looting.objective-approach",
                    AgentObjectiveLootApproachState.class, AgentObjectiveLootApproachState::new);
    private final Map<Integer, Long> suppressedUntil = new HashMap<>();
    private int mapId = -1;
    private int targetId;
    private long startedAtMs;
    private long progressAtMs;
    private double bestDistance;

    boolean suppressed(int currentMapId, int objectId, long nowMs) {
        if (mapId != currentMapId) {
            mapId = currentMapId;
            targetId = 0;
            suppressedUntil.clear();
        }
        suppressedUntil.values().removeIf(until -> nowMs >= until);
        return suppressedUntil.containsKey(objectId);
    }

    boolean approach(int currentMapId, int objectId, double distance, long nowMs) {
        if (suppressed(currentMapId, objectId, nowMs)) return false;
        if (targetId != objectId) {
            targetId = objectId;
            startedAtMs = progressAtMs = nowMs;
            bestDistance = distance;
        }
        if (distance <= bestDistance - 24) {
            bestDistance = distance;
            progressAtMs = nowMs;
        }
        if (nowMs - progressAtMs >= 30_000L || nowMs - startedAtMs >= 90_000L) {
            suppress(objectId, nowMs);
            return false;
        }
        return true;
    }

    void suppress(int objectId, long nowMs) {
        suppressedUntil.put(objectId, nowMs + 60_000L);
        if (targetId == objectId) targetId = 0;
    }
}

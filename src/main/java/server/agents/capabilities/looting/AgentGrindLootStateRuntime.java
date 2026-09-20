package server.agents.capabilities.looting;

import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

/**
 * Agent-owned adapter for temporary AgentRuntimeEntry-backed grind loot targeting state.
 */
public final class AgentGrindLootStateRuntime {
    private AgentGrindLootStateRuntime() {
    }

    public static MapItem grindLootTarget(AgentRuntimeEntry entry) {
        return entry.grindLootState().target();
    }

    public static boolean hasGrindLootTarget(AgentRuntimeEntry entry) {
        return entry.grindLootState().hasTarget();
    }

    public static void setGrindLootTarget(AgentRuntimeEntry entry, MapItem loot) {
        entry.grindLootState().setTarget(loot);
    }

    public static void setObjectiveLootTarget(AgentRuntimeEntry entry, MapItem loot) {
        entry.grindLootState().setObjectiveTarget(loot);
    }

    public static boolean hasObjectiveLootTarget(AgentRuntimeEntry entry) {
        return entry != null && entry.grindLootState().isObjectiveTarget();
    }

    public static void clearObjectiveLootTarget(AgentRuntimeEntry entry) {
        if (hasObjectiveLootTarget(entry)) {
            clearGrindLootTarget(entry);
        }
    }

    public static void clearGrindLootTarget(AgentRuntimeEntry entry) {
        entry.grindLootState().clearTarget();
    }

    public static void suppressRetry(AgentRuntimeEntry entry, MapItem loot, long untilMs) {
        if (loot == null) {
            clearRetrySuppression(entry);
            return;
        }
        entry.grindLootState().suppressRetry(loot.getObjectId(), untilMs);
    }

    public static boolean isRetrySuppressed(AgentRuntimeEntry entry, MapItem loot, long nowMs) {
        if (entry == null || loot == null) return false;
        if (entry.capabilityStates().find(AgentObjectiveLootApproachState.STATE_KEY)
                .filter(state -> state.suppressed(
                        server.agents.integration.AgentRuntimeIdentityRuntime.botMapId(entry),
                        loot.getObjectId(), nowMs)).isPresent()) return true;
        if (entry.grindLootState().ignoredObjectId() <= 0) {
            return false;
        }
        if (nowMs >= entry.grindLootState().ignoredUntilMs()) {
            clearRetrySuppression(entry);
            return false;
        }
        return entry.grindLootState().ignoredObjectId() == loot.getObjectId();
    }

    public static void clearRetrySuppression(AgentRuntimeEntry entry) {
        entry.grindLootState().clearRetrySuppression();
    }
}

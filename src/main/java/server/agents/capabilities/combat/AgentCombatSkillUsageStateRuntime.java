package server.agents.capabilities.combat;

import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;

/** Capability-owned adapter for {@link AgentCombatSkillUsageState}. */
public final class AgentCombatSkillUsageStateRuntime {
    private AgentCombatSkillUsageStateRuntime() {
    }

    public static void record(AgentRuntimeEntry entry, int skillId, long damage) {
        entry.capabilityStates().require(AgentCombatSkillUsageState.STATE_KEY).record(skillId, damage);
    }

    public static void addDamage(AgentRuntimeEntry entry, int skillId, long damage) {
        entry.capabilityStates().require(AgentCombatSkillUsageState.STATE_KEY).addDamage(skillId, damage);
    }

    public static Map<Integer, long[]> snapshot(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentCombatSkillUsageState.STATE_KEY).snapshot();
    }

    public static void reset(AgentRuntimeEntry entry) {
        entry.capabilityStates().require(AgentCombatSkillUsageState.STATE_KEY).reset();
    }
}

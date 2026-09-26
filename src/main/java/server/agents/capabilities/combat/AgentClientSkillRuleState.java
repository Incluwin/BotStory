package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.HashMap;
import java.util.Map;

/** Per-Agent clocks for {@link AgentClientSkillRules}: reuse locks after a landed hit, and invincibility. */
public final class AgentClientSkillRuleState {
    public static final AgentCapabilityStateKey<AgentClientSkillRuleState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.client-skill-rules",
                    AgentClientSkillRuleState.class, AgentClientSkillRuleState::new);

    private final Map<Integer, Long> reuseLockedUntilMs = new HashMap<>();
    private long invincibleUntilMs;

    synchronized boolean reuseLocked(int skillId, long nowMs) {
        Long until = reuseLockedUntilMs.get(skillId);
        return until != null && nowMs < until;
    }

    synchronized void lockReuse(int skillId, long untilMs) {
        reuseLockedUntilMs.put(skillId, untilMs);
    }

    synchronized boolean invincible(long nowMs) {
        return nowMs < invincibleUntilMs;
    }

    synchronized void invincibleUntil(long untilMs) {
        invincibleUntilMs = Math.max(invincibleUntilMs, untilMs);
    }

    synchronized void clear() {
        reuseLockedUntilMs.clear();
        invincibleUntilMs = 0;
    }
}

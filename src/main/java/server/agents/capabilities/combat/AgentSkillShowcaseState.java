package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.HashMap;
import java.util.Map;

/** Filming mode for one Agent; see {@link AgentSkillShowcase}. Off until {@code start()}. */
public final class AgentSkillShowcaseState {
    public static final AgentCapabilityStateKey<AgentSkillShowcaseState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.skill-showcase",
                    AgentSkillShowcaseState.class, AgentSkillShowcaseState::new);

    private boolean active;
    private int lastNamedSkillId;
    private final Map<Integer, Long> lastUsedAt = new HashMap<>();

    public synchronized boolean active() { return active; }

    public synchronized void start() {
        active = true;
        lastNamedSkillId = 0;
        lastUsedAt.clear();
    }

    public synchronized long lastUsedAt(int skillId) { return lastUsedAt.getOrDefault(skillId, 0L); }

    /** Records a use; true when the skill should be named aloud (it was not the last one named). */
    public synchronized boolean recordUse(int skillId, long nowMs) {
        lastUsedAt.put(skillId, nowMs);
        if (skillId == lastNamedSkillId) {
            return false;
        }
        lastNamedSkillId = skillId;
        return true;
    }
}

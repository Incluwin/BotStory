package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.HashSet;
import java.util.Set;

/** Temporary activity-owned restriction; zero means ordinary combat policy. */
public final class AgentCombatSkillConstraintState {
    public static final AgentCapabilityStateKey<AgentCombatSkillConstraintState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.skill-constraint",
                    AgentCombatSkillConstraintState.class, AgentCombatSkillConstraintState::new);

    private int requiredSkillId;
    private boolean attackSkillRequired;
    private final Set<Integer> bannedSkillIds = new HashSet<>();

    public synchronized int requiredSkillId() { return requiredSkillId; }
    public synchronized boolean attackSkillRequired() { return attackSkillRequired; }
    public synchronized void require(int skillId) { requiredSkillId = Math.max(0, skillId); }
    public synchronized void requireAttackSkill() { attackSkillRequired = true; }
    public synchronized boolean banned(int skillId) { return bannedSkillIds.contains(skillId); }
    public synchronized void ban(int skillId) { bannedSkillIds.add(skillId); }
    public synchronized void clear() {
        requiredSkillId = 0;
        attackSkillRequired = false;
        bannedSkillIds.clear();
    }
}

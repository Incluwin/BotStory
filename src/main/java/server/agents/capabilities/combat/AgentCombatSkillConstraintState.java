package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

/** Temporary activity-owned restriction; zero means ordinary combat policy. */
public final class AgentCombatSkillConstraintState {
    public static final AgentCapabilityStateKey<AgentCombatSkillConstraintState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.skill-constraint",
                    AgentCombatSkillConstraintState.class, AgentCombatSkillConstraintState::new);

    private int requiredSkillId;
    private boolean attackSkillRequired;

    public synchronized int requiredSkillId() { return requiredSkillId; }
    public synchronized boolean attackSkillRequired() { return attackSkillRequired; }
    public synchronized void require(int skillId) { requiredSkillId = Math.max(0, skillId); }
    public synchronized void requireAttackSkill() { attackSkillRequired = true; }
    public synchronized void clear() {
        requiredSkillId = 0;
        attackSkillRequired = false;
    }
}

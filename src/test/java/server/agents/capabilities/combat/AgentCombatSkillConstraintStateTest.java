package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatSkillConstraintStateTest {
    @Test
    void attackSkillRequirementPersistsUntilConstraintIsCleared() {
        AgentCombatSkillConstraintState state = new AgentCombatSkillConstraintState();

        assertFalse(state.attackSkillRequired());
        state.require(1234);
        state.requireAttackSkill();

        assertEquals(1234, state.requiredSkillId());
        assertTrue(state.attackSkillRequired());

        state.clear();

        assertEquals(0, state.requiredSkillId());
        assertFalse(state.attackSkillRequired());
    }
}

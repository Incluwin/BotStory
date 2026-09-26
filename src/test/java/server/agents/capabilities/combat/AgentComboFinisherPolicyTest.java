package server.agents.capabilities.combat;

import constants.skills.Crusader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentComboFinisherPolicyTest {
    @Test
    void finisherWaitsForAFullStack() {
        assertFalse(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_PANIC, null, 5));
        assertFalse(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_PANIC, 1, 5));
        assertFalse(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_PANIC, 3, 5));   // two orbs
        assertFalse(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_PANIC, 5, 5));   // four orbs
        assertTrue(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_PANIC, 6, 5));    // five orbs
    }

    @Test
    void requirementIsClampedToWhatComboAttackCanHold() {
        assertTrue(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_COMA, 4, 3));     // three orbs, low Combo Attack
        assertFalse(AgentComboFinisherPolicy.canPlan(Crusader.SWORD_COMA, 3, 3));
    }

    @Test
    void ordinarySkillsDoNotRequireComboBuff() {
        assertTrue(AgentComboFinisherPolicy.canPlan(1001005, null, 5));
    }
}

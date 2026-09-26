package server.agents.capabilities.combat.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCombatLearningContextTest {
    @Test
    void orbBucketsFollowTheFinisherSteps() {
        // The COMBO buff value is orbs + 1.
        assertEquals("0", AgentCombatLearningContext.orbBucket(1, 5));
        assertEquals("1-2", AgentCombatLearningContext.orbBucket(3, 5));
        assertEquals("3-4", AgentCombatLearningContext.orbBucket(5, 5));
        assertEquals("full", AgentCombatLearningContext.orbBucket(6, 5));
        // Advanced Combo: each count from five to nine stays apart, ten is full.
        assertEquals("6", AgentCombatLearningContext.orbBucket(7, 10));
        assertEquals("9", AgentCombatLearningContext.orbBucket(10, 10));
        assertEquals("full", AgentCombatLearningContext.orbBucket(11, 10));
    }

    @Test
    void keysLeaveOutWhatDoesNotApply() {
        assertEquals("1 target", AgentCombatLearningContext.key(1, false, null, false, false, null, false, false));
        assertEquals("4+ targets|boss|orbs full|energy full|transformed|no summon|target buffed|target debuffed",
                AgentCombatLearningContext.key(5, true, "full", true, true, false, true, true));
        assertEquals("2-3 targets|summon up", AgentCombatLearningContext.key(3, false, null, false, false, true, false, false));
    }
}

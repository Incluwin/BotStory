package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentBerserkWindowPolicyTest {
    @Test
    void withoutBerserkTheConfiguredThresholdIsUnchanged() {
        assertEquals(0.7f, AgentBerserkWindowPolicy.autopotHpThreshold(0.7f, 0), 1e-6);
    }

    @Test
    void berserkPullsTheTriggerJustUnderTheThreshold() {
        // Berserk at 40%: drink at 35% so the Agent holds the window instead of topping up out of it.
        assertEquals(0.35f, AgentBerserkWindowPolicy.autopotHpThreshold(0.7f, 40), 1e-6);
    }

    @Test
    void theSafetyFloorStopsALowThresholdLeavingTheAgentOneHitFromDeath() {
        assertEquals(0.25f, AgentBerserkWindowPolicy.autopotHpThreshold(0.7f, 20), 1e-6);
    }

    @Test
    void aThresholdAboveTheConfiguredTriggerNeverRaisesIt() {
        assertEquals(0.5f, AgentBerserkWindowPolicy.autopotHpThreshold(0.5f, 90), 1e-6);
    }

    @Test
    void withoutBerserkAPotionAimsToFillTheBar() {
        assertEquals(0.3f, AgentBerserkWindowPolicy.autopotHpDeficitFraction(0.7f, 0), 1e-6);
    }

    @Test
    void aBerserkAgentAsksForJustEnoughToStayInsideTheWindow() {
        // Berserk at 80%: drink at 70%, top up to 75%, so the doubling never switches off.
        assertEquals(0.08f, AgentBerserkWindowPolicy.autopotHpDeficitFraction(0.7f, 80), 1e-6);
    }
}

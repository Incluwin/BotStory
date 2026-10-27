package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRangedEngagementPolicyTest {
    /** 2026-10-27: a crowded shooter backs off while it keeps shooting; it no longer stays for one close swing. */
    @Test
    void retreatsAndKeepsShootingWhenATargetCrowdsIt() {
        AgentRangedEngagementPolicy.Decision decision = AgentRangedEngagementPolicy.decide(
                new AgentRangedEngagementPolicy.Input(false, true, false));

        assertTrue(decision.retreat());
        assertTrue(decision.attackGateOpen());
    }

    @Test
    void retreatsAndKeepsShootingAfterACloseRangeSkill() {
        AgentRangedEngagementPolicy.Decision decision = AgentRangedEngagementPolicy.decide(
                new AgentRangedEngagementPolicy.Input(true, false, false));

        assertTrue(decision.retreat());
        assertTrue(decision.attackGateOpen());
    }

    @Test
    void holdsAndShootsWhenNothingCrowdsIt() {
        AgentRangedEngagementPolicy.Decision decision = AgentRangedEngagementPolicy.decide(
                new AgentRangedEngagementPolicy.Input(false, false, false));

        assertFalse(decision.retreat());
        assertTrue(decision.attackGateOpen());
    }

    @Test
    void bossSpacingClosesTheAttackGateUntilTheAgentRetreats() {
        AgentRangedEngagementPolicy.Decision decision = AgentRangedEngagementPolicy.decide(
                new AgentRangedEngagementPolicy.Input(false, true, true));

        assertTrue(decision.retreat());
        assertFalse(decision.attackGateOpen());
    }
}

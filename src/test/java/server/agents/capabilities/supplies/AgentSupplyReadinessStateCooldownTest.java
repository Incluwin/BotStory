package server.agents.capabilities.supplies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A readiness trip refused for its price or its Use slots waits until it can go: its purse grew by what it lacked, a
 * slot freed, or it lacks fewer of what it went for, projectiles included (someone handed it some stars).
 */
class AgentSupplyReadinessStateCooldownTest {
    private static final int KERNING = 103_000_000;

    @Test
    void aSpendCooldownLiftsWhenItLacksFewerProjectiles() {
        AgentSupplyReadinessState state = new AgentSupplyReadinessState();
        state.cooldown(AgentSupplyReadinessState.Cooldown.CANNOT_AFFORD, Long.MAX_VALUE, 100, 1_000, 5,
                new AgentSupplyReadiness.Shortfall(0, 0, 500), KERNING);

        assertEquals(AgentSupplyReadinessState.Cooldown.CANNOT_AFFORD,
                state.cooldownInForce(1_000L, 100, 5, new AgentSupplyReadiness.Shortfall(0, 0, 500), KERNING),
                "nothing changed: it still waits");
        assertNull(state.cooldownInForce(2_000L, 100, 5, new AgentSupplyReadiness.Shortfall(0, 0, 400), KERNING),
                "a party member handed it 100 stars");

        state.cooldown(AgentSupplyReadinessState.Cooldown.NO_SPACE, Long.MAX_VALUE, 100, 0, 1,
                new AgentSupplyReadiness.Shortfall(0, 0, 500), KERNING);
        assertNull(state.cooldownInForce(3_000L, 100, 1, new AgentSupplyReadiness.Shortfall(0, 0, 499), KERNING));
    }
}

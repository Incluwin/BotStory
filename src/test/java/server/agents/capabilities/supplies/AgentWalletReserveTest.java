package server.agents.capabilities.supplies;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-Agent wallet-reserve seam: an Agent its host exempts (a companion carried along by its owner) keeps no
 * journey reserve, so it can spend its whole purse on supplies; every other Agent, and every Agent with no rule
 * installed, keeps the configured reserve.
 */
class AgentWalletReserveTest {
    private static final int COMPANION = 7;
    private static final int OTHER = 8;

    @AfterEach
    void uninstall() {
        AgentWalletReserve.install(null);
    }

    private static Character agent(int id, int level) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getLevel()).thenReturn(level);
        return agent;
    }

    @Test
    void withNoRuleEveryAgentKeepsTheConfiguredReserve() {
        int configured = AgentSupplyRecoveryPolicy.minimumWalletReserve(agent(COMPANION, 30));

        assertTrue(configured > 0, "the reserve is configured: " + configured);
        assertEquals(configured, AgentSupplyRecoveryPolicy.minimumWalletReserve(agent(OTHER, 30)));
    }

    @Test
    void anExemptAgentKeepsNoReserveAndTheOthersKeepTheirs() {
        int configured = AgentSupplyRecoveryPolicy.minimumWalletReserve(agent(OTHER, 30));
        AgentWalletReserve.install(id -> id == COMPANION);

        assertEquals(0, AgentSupplyRecoveryPolicy.minimumWalletReserve(agent(COMPANION, 30)));
        assertEquals(configured, AgentSupplyRecoveryPolicy.minimumWalletReserve(agent(OTHER, 30)));
    }
}

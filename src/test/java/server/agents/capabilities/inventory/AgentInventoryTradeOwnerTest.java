package server.agents.capabilities.inventory;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.trade.AgentTradeWindow;
import server.agents.integration.AgentTradeGatewayRuntime;
import server.agents.integration.TradeGateway;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 2026-10-15 (P20 run 3): a bench ally leads itself, so its interaction target is itself and it took no trade from its
 * owner. While it is a self-led member of a boss run, the human it stays with there is its trade owner whenever that
 * human is its trade partner (AgentBossRunRegistry.selfLedAnchor): the owner's "Let's trade" on its card can hand it
 * potions. Any other partner, no trade, or an Agent that is no self-led member keeps its interaction target.
 */
class AgentInventoryTradeOwnerTest {
    private static final int GUNNAR = 1_005;
    private static final int OWNER = 2;

    @Test
    void aBenchAllyTakesItsRunOwnersTradeAsItsOwnersAndNobodyElses() {
        Character gunnar = agent(GUNNAR);
        Character owner = agent(OWNER);
        Character stranger = agent(77);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(gunnar, gunnar, null); // it leads itself
        TradeGateway gateway = mock(TradeGateway.class);
        AgentTradeWindow window = mock(AgentTradeWindow.class);
        AgentTradeWindow partner = mock(AgentTradeWindow.class);
        when(window.partner()).thenReturn(partner);

        try (MockedStatic<AgentBossRunRegistry> registry = mockStatic(AgentBossRunRegistry.class);
             MockedStatic<AgentTradeGatewayRuntime> trades = mockStatic(AgentTradeGatewayRuntime.class)) {
            trades.when(AgentTradeGatewayRuntime::trade).thenReturn(gateway);
            registry.when(() -> AgentBossRunRegistry.selfLedAnchor(GUNNAR)).thenReturn(OWNER);
            when(gateway.currentWindow(gunnar)).thenReturn(window);
            when(partner.character()).thenReturn(owner);

            assertSame(owner, AgentInventoryTickRuntime.tradeOwner(entry, gunnar), "its run owner's trade");

            when(partner.character()).thenReturn(stranger);
            assertSame(gunnar, AgentInventoryTickRuntime.tradeOwner(entry, gunnar), "anyone else's: as before");
            when(gateway.currentWindow(gunnar)).thenReturn(null);
            assertSame(gunnar, AgentInventoryTickRuntime.tradeOwner(entry, gunnar), "no trade");

            when(gateway.currentWindow(gunnar)).thenReturn(window);
            when(partner.character()).thenReturn(owner);
            registry.when(() -> AgentBossRunRegistry.selfLedAnchor(GUNNAR)).thenReturn(0);
            assertSame(gunnar, AgentInventoryTickRuntime.tradeOwner(entry, gunnar), "no self-led member: as before");
        }
    }

    private static Character agent(int id) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        return agent;
    }
}

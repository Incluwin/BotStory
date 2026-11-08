package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A drop only its owner ever picks up (MapItem.permanentOwner: the casino's bets, payouts, dealt cards and gachapon
 * prizes) is moved by the casino itself, never by an Agent's own loot. Live 2026-11-08 (C8): a citizen dropped its bet
 * owner-only to itself, its loot took the chips straight back, and no citizen ever bet at a blackjack table.
 */
class AgentLootEligibilityCasinoTest {
    private static final int SNAIL_STAMP = 4002000;

    private final Inventory etcBag = mock(Inventory.class);
    private final Character bot = bot();
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);

    private Character bot() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(7101);
        when(agent.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        when(agent.getInventory(InventoryType.ETC)).thenReturn(etcBag);
        return agent;
    }

    private MapItem chips(boolean ownerOnly) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(SNAIL_STAMP);
        when(drop.getQuest()).thenReturn(-1);
        when(drop.getDropper()).thenReturn(bot);
        when(drop.canBePickedBy(any(Character.class))).thenReturn(true);
        when(drop.isPermanentOwner()).thenReturn(ownerOnly);
        return drop;
    }

    @Test
    void anAgentNeverLootsACasinoDropOnItsOwn() {
        assertFalse(AgentLootEligibility.canBotLoot(entry, bot, chips(true)), "its own bet, until the dealer takes it");
        assertFalse(AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, chips(true)));
    }

    @Test
    void anOrdinaryDropOfTheSameItemIsStillLoot() {
        assertTrue(AgentLootEligibility.canBotLoot(entry, bot, chips(false)));
    }
}

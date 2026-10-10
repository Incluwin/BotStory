package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentLootEligibility.leftForFullBag: a drop the Agent leaves only because its bag for that kind of item has no free
 * slot, that is one every other loot rule and its host's loot setting would let it pick up. Only such a drop may get a
 * companion's bag-full line (AgentBagFullSpeech); the loot rules themselves are unchanged.
 */
class AgentLootEligibilityBagFullTest {
    private static final int BOT = 5131;
    private static final int SNAIL_SHELL = 4000000;
    private static final int CAP = 1002000;

    private final Inventory etcBag = mock(Inventory.class);
    private final Inventory equipBag = mock(Inventory.class);
    private final Character bot = bot();
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);

    @AfterEach
    void uninstall() {
        AgentLootPolicy.install(null);
    }

    private Character bot() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(BOT);
        when(agent.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        when(agent.getInventory(InventoryType.ETC)).thenReturn(etcBag);
        when(agent.getInventory(InventoryType.EQUIP)).thenReturn(equipBag);
        return agent;
    }

    private static MapItem drop(int itemId, int meso) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getMeso()).thenReturn(meso);
        when(drop.getDropper()).thenReturn(mock(Monster.class));
        when(drop.getQuest()).thenReturn(-1);
        when(drop.getDropTime()).thenReturn(1_000L);
        when(drop.canBePickedBy(any(Character.class))).thenReturn(true);
        return drop;
    }

    @Test
    void aFullEquipBagLeavesAnEquipItWouldPickUp() {
        when(equipBag.isFull()).thenReturn(true);
        MapItem cap = drop(CAP, 0);

        assertFalse(AgentLootEligibility.canBotLoot(entry, bot, cap), "the loot rule itself is unchanged");
        assertTrue(AgentLootEligibility.leftForFullBag(entry, bot, cap));
    }

    @Test
    void aBagWithRoomLeavesNothingForLackOfSpace() {
        MapItem shell = drop(SNAIL_SHELL, 0);

        assertTrue(AgentLootEligibility.canBotLoot(entry, bot, shell));
        assertFalse(AgentLootEligibility.leftForFullBag(entry, bot, shell));
    }

    @Test
    void aDropItsLootSettingLeavesIsNotLeftForTheBag() {
        when(equipBag.isFull()).thenReturn(true);
        AgentLootPolicy.install(id -> new AgentLootPolicy.Policy(AgentLootPolicy.Mode.NONE, false, null));

        assertFalse(AgentLootEligibility.leftForFullBag(entry, bot, drop(CAP, 0)), "loot setting: nothing");
    }

    @Test
    void aDropTheOtherRulesRefuseIsNotLeftForTheBag() {
        when(equipBag.isFull()).thenReturn(true);
        MapItem notYet = drop(CAP, 0);
        when(notYet.canBePickedBy(bot)).thenReturn(false); // another party's drop still
        MapItem kpqPass = drop(AgentLootEligibility.KPQ_PASS, 0); // no KPQ session
        when(etcBag.isFull()).thenReturn(true);

        assertFalse(AgentLootEligibility.leftForFullBag(entry, bot, notYet));
        assertFalse(AgentLootEligibility.leftForFullBag(entry, bot, kpqPass));
    }

    @Test
    void mesoNeverFillsABag() {
        when(etcBag.isFull()).thenReturn(true);
        when(equipBag.isFull()).thenReturn(true);

        assertFalse(AgentLootEligibility.leftForFullBag(entry, bot, drop(0, 120)));
    }
}

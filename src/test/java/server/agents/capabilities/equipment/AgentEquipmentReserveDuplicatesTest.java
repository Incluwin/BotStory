package server.agents.capabilities.equipment;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Of identical copies of a kept piece (same id, same stats, same slots) the Agent keeps exactly one: the worn one, else
 * the one in the earliest bag slot. A copy that ties on the useful stats keeps the one with more upgrade slots left.
 */
class AgentEquipmentReserveDuplicatesTest {
    private static final int OVERALL = 1051014;

    @Test
    void threeIdenticalOwnClassOverallsKeepOnlyOne() {
        Character warrior = warrior();
        Equip first = overall(1, 3, 2);
        Equip second = overall(2, 3, 2);
        Equip third = overall(3, 3, 2);

        Set<Equip> keep = AgentEquipmentReservePolicy.selectOwnedItemsForSelfReserve(warrior, hooks(),
                List.of(first, second, third));

        assertEquals(1, keep.size(), "three identical overalls: exactly one is kept");
        assertTrue(keep.contains(first), "the first copy is the one kept");
    }

    @Test
    void threeIdenticalOverallsInTheBagKeepTheEarliestSlot() {
        Equip slot7 = overall(7, 3, 2);
        Equip slot2 = overall(2, 3, 2);
        Equip slot5 = overall(5, 3, 2);
        Character warrior = warriorHolding(List.of(), List.of(slot7, slot2, slot5));

        InventoryGateway inventory = inventoryGateway();
        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);

            Set<Item> keep = AgentEquipmentReservePolicy.collectPotentialSelfUpgradeItems(warrior);

            assertEquals(1, keep.size());
            assertSame(slot2, keep.iterator().next(), "the copy in the earliest bag slot is kept, whatever the bag order");
            assertTrue(AgentEquipmentReservePolicy.shouldReserveOwnedItem(warrior, (Item) slot2));
            assertFalse(AgentEquipmentReservePolicy.shouldReserveOwnedItem(warrior, (Item) slot5));
            assertFalse(AgentEquipmentReservePolicy.shouldReserveOwnedItem(warrior, (Item) slot7));
        }
    }

    @Test
    void aBagCopyIdenticalToTheWornPieceIsNotKept() {
        Equip worn = overall(-5, 3, 2);
        Equip bagCopy = overall(1, 3, 2);
        Character warrior = warriorHolding(List.of(worn), List.of(bagCopy));

        InventoryGateway inventory = inventoryGateway();
        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);

            assertTrue(AgentEquipmentReservePolicy.collectPotentialSelfUpgradeItems(warrior).isEmpty(),
                    "the worn piece is the kept copy; its identical spare in the bag is not");
        }
    }

    @Test
    void copiesThatTieOnUsefulStatsKeepTheOneWithMoreUpgradeSlots() {
        Character warrior = warrior();
        Equip failedScrolls = overall(1, 3, 2);
        Equip fresh = overall(2, 3, 2);
        when(failedScrolls.getUpgradeSlots()).thenReturn((byte) 3);
        when(fresh.getUpgradeSlots()).thenReturn((byte) 7);

        Set<Equip> keep = AgentEquipmentReservePolicy.selectOwnedItemsForSelfReserve(warrior, hooks(),
                List.of(failedScrolls, fresh));

        assertEquals(Set.of(fresh), Set.copyOf(keep), "a tie keeps the copy that can still take more scrolls");
    }

    @Test
    void copiesWithEqualMainStatsButAHigherUsefulSumStillKeepTheBetterOne() {
        Character warrior = warrior();
        Equip plain = overall(1, 3, 2);
        Equip moreDefense = overall(2, 3, 2);
        when(moreDefense.getWdef()).thenReturn((short) 40);

        Set<Equip> keep = AgentEquipmentReservePolicy.selectOwnedItemsForSelfReserve(warrior, hooks(),
                List.of(plain, moreDefense));

        assertEquals(Set.of(moreDefense), Set.copyOf(keep), "the old full-stat tie-break still picks the better copy");
    }

    @Test
    void differentItemsWithEqualStatsAreBothStillKept() {
        Character warrior = warrior();
        Equip overall = overall(1, 3, 2);
        Equip otherOverall = mockEquip(OVERALL + 1, 2, 3, 2);

        Set<Equip> keep = AgentEquipmentReservePolicy.selectOwnedItemsForSelfReserve(warrior, hooks(),
                List.of(overall, otherOverall));

        assertEquals(2, keep.size(), "only copies of one item id collapse; other items keep the old rule");
    }

    @Test
    void offerPathRecipientWantsABetterCopyButNotAnIdenticalOne() {
        Equip held = overall(1, 3, 2);
        Character recipient = warriorHolding(List.of(), List.of(held));
        Equip identical = overall(4, 3, 2);
        Equip better = overall(5, 5, 2);

        InventoryGateway inventory = inventoryGateway();
        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);

            assertTrue(AgentEquipmentReservePolicy.wouldReserveIncomingItem(recipient, better),
                    "a strictly better copy is still an upgrade for the recipient");
            assertFalse(AgentEquipmentReservePolicy.wouldReserveIncomingItem(recipient, identical),
                    "an identical copy of what the recipient holds is no upgrade");
            assertTrue(AgentEquipmentReservePolicy.shouldReserveOwnedItem(recipient, (Item) held),
                    "the recipient's own copy stays kept");
        }
    }

    private static Character warrior() {
        Character warrior = mock(Character.class);
        when(warrior.getJob()).thenReturn(Job.FIGHTER);
        when(warrior.getLevel()).thenReturn(30);
        return warrior;
    }

    private static Character warriorHolding(List<Equip> worn, List<Equip> bag) {
        Character warrior = warrior();
        Inventory equipped = mock(Inventory.class);
        Inventory equip = mock(Inventory.class);
        when(equipped.list()).thenReturn(new ArrayList<Item>(worn));
        when(equip.list()).thenReturn(new ArrayList<Item>(bag));
        when(warrior.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(warrior.getInventory(InventoryType.EQUIP)).thenReturn(equip);
        return warrior;
    }

    private static Equip overall(int position, int str, int dex) {
        return mockEquip(OVERALL, position, str, dex);
    }

    private static Equip mockEquip(int itemId, int position, int str, int dex) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(itemId);
        when(equip.getPosition()).thenReturn((short) position);
        when(equip.getInventoryType()).thenReturn(InventoryType.EQUIP);
        when(equip.getStr()).thenReturn((short) str);
        when(equip.getDex()).thenReturn((short) dex);
        when(equip.getWdef()).thenReturn((short) 30);
        when(equip.getUpgradeSlots()).thenReturn((byte) 7);
        return equip;
    }

    private static Map<String, Integer> overallStats() {
        return Map.of("reqJob", 1, "reqSTR", 0, "reqDEX", 0, "reqINT", 0, "reqLUK", 0, "reqPOP", 0,
                "STR", 3, "DEX", 2);
    }

    private static AgentEquipmentReservePolicy.SelfReserveHooks hooks() {
        AgentEquipmentReservePolicy.SelfReserveHooks hooks = mock(AgentEquipmentReservePolicy.SelfReserveHooks.class);
        when(hooks.isCash(anyInt())).thenReturn(false);
        when(hooks.getEquipmentSlot(anyInt())).thenReturn("MaPn");
        when(hooks.meetsReqs(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);
        when(hooks.getEquipLevelReq(anyInt())).thenReturn(30);
        when(hooks.getEquipStats(anyInt())).thenReturn(overallStats());
        return hooks;
    }

    private static InventoryGateway inventoryGateway() {
        InventoryGateway inventory = mock(InventoryGateway.class);
        when(inventory.isCashItem(anyInt())).thenReturn(false);
        when(inventory.isQuestItem(anyInt())).thenReturn(false);
        when(inventory.getEquipmentSlot(anyInt())).thenReturn("MaPn");
        when(inventory.meetsEquipRequirements(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt())).thenReturn(true);
        when(inventory.getEquipLevelRequirement(anyInt())).thenReturn(30);
        when(inventory.getEquipStats(anyInt())).thenReturn(overallStats());
        return inventory;
    }
}

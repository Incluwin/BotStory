package server.agents.capabilities.inventory;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.agents.integration.InventoryGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Lv30 warrior (or mage) whose Equip bag holds copies of one own-class overall (or of the mage shoes), for the keep
 * and sell rules.
 */
final class KeptCopyFixture {
    static final int OVERALL = 1051014;
    static final int MAGE_SHOES = 1072420;

    private KeptCopyFixture() {
    }

    static Character warriorWithBag(Equip... bag) {
        return holderWithBag(Job.FIGHTER, bag);
    }

    static Character mageWithBag(Equip... bag) {
        return holderWithBag(Job.MAGICIAN, bag);
    }

    private static Character holderWithBag(Job job, Equip... bag) {
        Character holder = mock(Character.class);
        when(holder.getJob()).thenReturn(job);
        when(holder.getLevel()).thenReturn(30);
        Inventory equipped = mock(Inventory.class);
        Inventory equip = mock(Inventory.class);
        when(equipped.list()).thenReturn(new ArrayList<>());
        List<Item> items = new ArrayList<>(List.of(bag));
        when(equip.list()).thenReturn(items);
        when(equip.getSlotLimit()).thenReturn((byte) 96);
        for (Equip item : bag) {
            short slot = item.getPosition();
            when(equip.getItem(slot)).thenReturn(item);
        }
        when(holder.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(holder.getInventory(InventoryType.EQUIP)).thenReturn(equip);
        return holder;
    }

    /** A plain (unscrolled) copy of the overall in bag {@code slot}: base STR 3 and DEX 2 roll as given. */
    static Equip overall(int slot, int str, int dex) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(OVERALL);
        when(equip.getPosition()).thenReturn((short) slot);
        when(equip.getInventoryType()).thenReturn(InventoryType.EQUIP);
        when(equip.getQuantity()).thenReturn((short) 1);
        when(equip.getStr()).thenReturn((short) str);
        when(equip.getDex()).thenReturn((short) dex);
        when(equip.getWdef()).thenReturn((short) 30);
        when(equip.getUpgradeSlots()).thenReturn((byte) 7);
        return equip;
    }

    /** A copy with one passed scroll: one slot used, scroll level 1, plus the scroll's extra weapon defense. */
    static Equip scrolledOverall(int slot, int str, int dex) {
        Equip equip = overall(slot, str, dex);
        when(equip.getLevel()).thenReturn((byte) 1);
        when(equip.getUpgradeSlots()).thenReturn((byte) 6);
        when(equip.getWdef()).thenReturn((short) 35);
        return equip;
    }

    /** A plain copy of the mage shoes in bag {@code slot}: base INT 4, SPD 2 and JMP 2 roll as given. */
    static Equip mageShoes(int slot, int int_, int speed, int jump) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(MAGE_SHOES);
        when(equip.getPosition()).thenReturn((short) slot);
        when(equip.getInventoryType()).thenReturn(InventoryType.EQUIP);
        when(equip.getQuantity()).thenReturn((short) 1);
        when(equip.getInt()).thenReturn((short) int_);
        when(equip.getSpeed()).thenReturn((short) speed);
        when(equip.getJump()).thenReturn((short) jump);
        when(equip.getUpgradeSlots()).thenReturn((byte) 8);
        return equip;
    }

    static InventoryGateway inventoryGateway() {
        InventoryGateway inventory = mock(InventoryGateway.class);
        when(inventory.isCashItem(anyInt())).thenReturn(false);
        when(inventory.isQuestItem(anyInt())).thenReturn(false);
        when(inventory.getEquipmentSlot(anyInt())).thenReturn("MaPn");
        when(inventory.meetsEquipRequirements(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt())).thenReturn(true);
        when(inventory.getEquipLevelRequirement(anyInt())).thenReturn(30);
        when(inventory.getEquipStats(anyInt())).thenReturn(Map.of(
                "reqJob", 1, "reqSTR", 0, "reqDEX", 0, "reqINT", 0, "reqLUK", 0, "reqPOP", 0,
                "STR", 3, "DEX", 2));
        when(inventory.getEquipmentSlot(MAGE_SHOES)).thenReturn("So");
        when(inventory.getEquipLevelRequirement(MAGE_SHOES)).thenReturn(15);
        when(inventory.getEquipStats(MAGE_SHOES)).thenReturn(Map.of(
                "reqJob", 2, "reqSTR", 0, "reqDEX", 0, "reqINT", 0, "reqLUK", 0, "reqPOP", 0,
                "INT", 4, "Speed", 2, "Jump", 2));
        return inventory;
    }
}

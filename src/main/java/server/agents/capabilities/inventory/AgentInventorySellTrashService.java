package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import server.ItemRestrictionPolicy;
import server.agents.capabilities.equipment.AgentEquipmentReservePolicy;
import server.agents.capabilities.trade.AgentOfferService;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AgentInventorySellTrashService {
    private AgentInventorySellTrashService() {
    }

    public static List<Item> collectSellTrashEquips(AgentRuntimeEntry entry, Character agent, InventoryGateway inventory) {
        List<Item> normalTradeEquips = collectNormalTradeEquips(entry, agent, inventory);
        List<Item> trash = collectSellTrashEquips(normalTradeEquips, inventory);
        Set<Item> forStall = AgentStallHolds.keep(agent, trash); // list or vendor (docs/ECONOMY_PLAN.md E3)
        if (forStall.isEmpty()) {
            return trash;
        }
        List<Item> sold = new ArrayList<>(trash);
        sold.removeAll(forStall);
        return sold;
    }

    static List<Item> collectSellTrashEquips(List<Item> normalTradeEquips, InventoryGateway inventory) {
        if (normalTradeEquips.isEmpty()) {
            return normalTradeEquips;
        }

        List<Item> result = new ArrayList<>(normalTradeEquips.size());
        for (Item item : normalTradeEquips) {
            if (item instanceof Equip equip && !AgentInventorySellTrashPolicy.shouldKeepForSellTrash(inventory, equip)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * The Use stacks of ammo for a weapon other than {@code weaponType} (arrows a Spearman loots, say), in slot order,
     * that a shop may take: no quest item, no untradeable one the world keeps, nothing reserved or held for an offer.
     */
    public static List<Item> collectUnusableAmmo(AgentRuntimeEntry entry, Character agent, WeaponType weaponType,
                                                 InventoryGateway inventory) {
        long nowMs = System.currentTimeMillis();
        return AgentInventoryItemPolicy.collectSafeItems(agent, InventoryType.USE,
                item -> AgentInventoryAmmoPolicy.isTradeAmmoItem(item.getItemId())
                        && (weaponType == null
                        || !AgentInventoryAmmoPolicy.isAmmoForWeapon(item.getItemId(), weaponType))
                        && AgentInventoryReservationRuntime.mayConsume(entry, item, nowMs)
                        && !AgentOfferService.isReservedForOtherRecipients(entry, agent, item),
                inventory::isQuestItem,
                itemId -> ItemRestrictionPolicy.allowsUntradeable(agent, itemId));
    }

    private static List<Item> collectNormalTradeEquips(AgentRuntimeEntry entry, Character agent, InventoryGateway inventory) {
        List<Item> all = AgentInventoryItemPolicy.collectSafeItems(agent, InventoryType.EQUIP, item -> true,
                inventory::isQuestItem,
                itemId -> ItemRestrictionPolicy.allowsUntradeable(agent, itemId));
        Set<Item> selfKeep = AgentEquipmentReservePolicy.collectPotentialSelfUpgradeItems(agent);
        long nowMs = System.currentTimeMillis();
        AgentInventoryReservationRuntime.refreshEquipmentReservations(entry, agent, nowMs);

        List<Item> normal = new ArrayList<>();
        for (Item item : all) {
            if (selfKeep.contains(item)) {
                continue;
            }
            if (!AgentInventoryReservationRuntime.mayConsume(entry, item, nowMs)) {
                continue;
            }
            if (AgentOfferService.isReservedForOtherRecipients(entry, agent, item)) {
                continue;
            }
            normal.add(item);
        }
        return AgentInventoryTradePolicy.sortEquipsByItemId(normal);
    }
}

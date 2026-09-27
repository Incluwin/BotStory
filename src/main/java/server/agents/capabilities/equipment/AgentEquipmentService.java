package server.agents.capabilities.equipment;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.events.AgentEventPriority;
import server.agents.resources.events.AgentEquipmentCandidateDetectedEvent;
import server.agents.resources.events.AgentResourceEventPublisher;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.Collection;
import java.util.List;

/**
 * Agent-owned equipment boundary over the remaining legacy optimizer.
 * Subsequent slices should move implementations here and delete the bot shell.
 */
public final class AgentEquipmentService {
    private AgentEquipmentService() {
    }

    public static void autoEquip(Character agent, Character leader, Item pendingOffer) {
        AgentEquipmentAutoEquipService.autoEquip(agent, leader, pendingOffer);
        refreshReservations(agent);
    }

    public static void autoEquip(Character agent, Character leader, Item pendingOffer, boolean force) {
        AgentEquipmentAutoEquipService.autoEquip(agent, leader, pendingOffer, force);
        refreshReservations(agent);
    }

    /** Selects a catalog-authored starter weapon after the general optimizer reconciles the loadout. */
    public static boolean equipPreferredWeapon(Character agent, int itemId) {
        boolean equipped = equipPreferredWeapon(agent, itemId, AgentInventoryGatewayRuntime.inventory());
        refreshReservations(agent);
        return equipped;
    }

    static boolean equipPreferredWeapon(Character agent, int itemId, InventoryGateway inventory) {
        return equipInSlot(agent, itemId, (short) -11, inventory);
    }

    /** Wears an owned equip in one slot (including the cosmetic cash slots, -1xx) when the agent can wear it there. */
    public static boolean equipInSlot(Character agent, int itemId, short slot) {
        boolean equipped = equipInSlot(agent, itemId, slot, AgentInventoryGatewayRuntime.inventory());
        refreshReservations(agent);
        return equipped;
    }

    static boolean equipInSlot(Character agent, int itemId, short slot, InventoryGateway inventory) {
        if (agent == null || itemId <= 0 || inventory == null) {
            return false;
        }
        Item equipped = agent.getInventory(InventoryType.EQUIPPED).getItem(slot);
        if (equipped != null && equipped.getItemId() == itemId) {
            return true;
        }
        Inventory equipInventory = agent.getInventory(InventoryType.EQUIP);
        for (Item item : equipInventory.list()) {
            if (item instanceof Equip equip && equip.getItemId() == itemId && equip.getPosition() > 0
                    && inventory.canWearEquipment(agent, equip, slot)) {
                inventory.moveItem(agent, InventoryType.EQUIP, equip.getPosition(), slot, (short) 1);
                Item selected = agent.getInventory(InventoryType.EQUIPPED).getItem(slot);
                return selected != null && selected.getItemId() == itemId;
            }
        }
        return false;
    }

    /** Moves whatever is worn in a slot back to the equip inventory; true when the slot ends up empty. */
    public static boolean clearSlot(Character agent, short slot) {
        if (agent == null || agent.getInventory(InventoryType.EQUIPPED).getItem(slot) == null) {
            return true;
        }
        short free = agent.getInventory(InventoryType.EQUIP).getNextFreeSlot();
        if (free <= 0) {
            return false;
        }
        AgentInventoryGatewayRuntime.inventory().moveItem(agent, InventoryType.EQUIPPED, slot, free, (short) 1);
        refreshReservations(agent);
        return agent.getInventory(InventoryType.EQUIPPED).getItem(slot) == null;
    }

    public static List<String> autoEquipDebug(Character agent) {
        return AgentEquipmentAutoEquipService.autoEquipDebug(agent);
    }

    public static String unequipSlot(Character agent, short[] slots) {
        return AgentEquipmentUnequipService.unequipSlot(agent, slots);
    }

    public static String unequipAll(Character agent) {
        return AgentEquipmentUnequipService.unequipAll(agent);
    }

    public static short[] slotsFromName(String slotName) {
        return AgentEquipmentSlotResolver.slotsFromName(slotName);
    }

    public static List<AgentEquipRecommendation> findRecommendedEquips(Character agent, Character leader) {
        return AgentEquipmentRecommendationService.findRecommendedEquips(agent, leader);
    }

    public static List<AgentEquipRecommendation> findRecommendedEquipsFromItems(Character agent,
                                                                                Collection<Equip> items) {
        return AgentEquipmentRecommendationService.findRecommendedEquipsFromItems(agent, items);
    }

    public static AgentEquipRecommendation findRecommendationForItem(Character agent,
                                                                     Character leader,
                                                                     Item item) {
        AgentEquipRecommendation recommendation =
                AgentEquipmentRecommendationService.findRecommendationForItem(agent, leader, item);
        if (recommendation != null && item != null) {
            AgentResourceEventPublisher.publishFor(agent,
                    objectiveId -> new AgentEquipmentCandidateDetectedEvent(
                            agent.getId(), System.currentTimeMillis(), item.getItemId(),
                            leader == null ? 0 : Math.max(0, leader.getId()), objectiveId),
                    AgentEventPriority.NORMAL);
        }
        return recommendation;
    }

    public static boolean shouldReserveOwnedItem(Character agent, Item item) {
        return AgentEquipmentReservePolicy.shouldReserveOwnedItem(agent, item);
    }

    public static boolean wouldReserveIncomingItem(Character agent, Equip equip) {
        return AgentEquipmentReservePolicy.wouldReserveIncomingItem(agent, equip);
    }

    public static boolean isWeaponCompatible(Character agent, WeaponType weaponType) {
        return AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, weaponType);
    }

    public static boolean isMageJob(Job job) {
        return AgentWeaponCompatibilityPolicy.isMageJob(job);
    }

    private static void refreshReservations(Character agent) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByCharacterInstance(agent);
        if (entry != null) {
            AgentInventoryReservationRuntime.refreshEquipmentReservations(
                    entry, agent, System.currentTimeMillis());
        }
    }
}

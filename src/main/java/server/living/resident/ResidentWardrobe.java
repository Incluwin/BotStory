package server.living.resident;

import client.Character;
import client.Job;
import client.creator.BotCreator;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.build.AgentStarterItemGrant;
import server.agents.capabilities.build.AgentStarterKitCatalog;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.equipment.AgentWeaponCompatibilityPolicy;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Dresses a Resident from its loadout's {@code outfit} pools, so Residents sharing a loadout still
 * look different. Per slot the Resident walks the pool in an order that is stable for its persona
 * id and wears the first entry it can: the right gender and the game's own job/level/stat check.
 * Cash items go into the cosmetic cash slots over the real gear; 0 means nothing in that slot
 * (bare head, no gloves, no cape); an overall as the top leaves the bottom empty. A weapon from the
 * pool must also suit the Resident's branch (a Fighter never takes a mace), and it replaces the other
 * weapons the living world handed out (loadouts, outfit pools, first-job starter kits): the Agent gear
 * optimizer would re-equip whichever hits harder (the starter sword over a mop). A weapon the Resident
 * earned as a companion is never removed, nor its own quest gear (ResidentKit), a scrolled, tagged or
 * untradeable piece, or what its contract lets it keep. Idempotent: a Resident already wearing its picks
 * is left alone. A slot its own quest gear takes keeps that piece: only a cash item still goes over it.
 */
final class ResidentWardrobe {
    private static final Logger log = LoggerFactory.getLogger(ResidentWardrobe.class);
    /** Outfit slot -> regular equip position; the cash position is 100 lower. Top comes before bottom. */
    private static final Map<String, Short> SLOTS = new LinkedHashMap<>();

    static {
        SLOTS.put("hat", (short) -1);
        SLOTS.put("top", (short) -5);
        SLOTS.put("bottom", (short) -6);
        SLOTS.put("shoes", (short) -7);
        SLOTS.put("gloves", (short) -8);
        SLOTS.put("cape", (short) -9);
        SLOTS.put("weapon", (short) -11);
    }

    private ResidentWardrobe() {
    }

    /** {@link #dress(Character, Persona, Loadout, Set, Set, Set)} with only this loadout's weapons and the starter kits as handed out. */
    static boolean dress(Character agent, Persona persona, Loadout loadout) {
        return dress(agent, persona, loadout, Set.of(), Set.of(), null);
    }

    /**
     * Returns true when anything was changed (the character is then saved); a hired citizen keeps its own gear.
     * {@code handedOut} ({@link #handedOut}) are the weapons an outfit weapon may replace; null means this loadout's.
     * The regular items of the {@code kitSlots} are left to ResidentKit, and the {@code kept} items (what its contract
     * lets it keep) are never swept away.
     */
    static boolean dress(Character agent, Persona persona, Loadout loadout, Set<Short> kitSlots, Set<Integer> kept,
                         Set<Integer> handedOut) {
        if (agent == null || loadout == null || loadout.outfit().isEmpty() || LivingContent.isHire(persona.id())) {
            return false;
        }
        Set<Integer> replaceable = handedOut != null ? handedOut : handedOut(List.of(loadout));
        boolean changed = false;
        boolean overall = false;
        for (Map.Entry<String, Short> slot : SLOTS.entrySet()) {
            List<Integer> pool = loadout.outfit().get(slot.getKey());
            if (pool == null || pool.isEmpty()) {
                continue;
            }
            short position = slot.getValue();
            if (slot.getKey().equals("bottom") && overall) {
                changed |= clear(agent, position) | clear(agent, (short) (position - 100));
                continue;
            }
            int pick = pick(agent, persona, slot.getKey(), pool);
            if (pick < 0 || kitSlots.contains(position) && (pick == 0 || !ItemInformationProvider.getInstance().isCash(pick))) {
                continue; // its own quest gear is worn there; a cash piece may still cover it
            }
            changed |= wear(agent, position, pick);
            if (slot.getKey().equals("weapon") && pick > 0 && isWorn(agent, position, pick)) {
                changed |= dropOtherWeapons(agent, pick, replaceable, kept);
            }
            if (slot.getKey().equals("top")) {
                overall = pick > 0 && ItemConstants.isOverall(pick);
            }
        }
        if (changed) {
            agent.saveCharToDB();
            log.info("Resident {} dressed from loadout {}", persona.id(), loadout.id());
        }
        return changed;
    }

    /** The first pool entry this character can wear, in the persona's own order; -1 when none fits. */
    static int pick(Character agent, Persona persona, String slot, List<Integer> pool) {
        List<Integer> order = new ArrayList<>(pool);
        Collections.shuffle(order, new Random((persona.id() + ':' + slot).hashCode()));
        for (int itemId : order) {
            if (itemId == 0 || wearable(agent, itemId, SLOTS.get(slot))) {
                return itemId;
            }
        }
        return -1;
    }

    static boolean wearable(Character agent, int itemId, short position) {
        int gender = (itemId / 1000) % 10; // 0 male only, 1 female only, anything else both
        if ((gender == 0 || gender == 1) && gender != agent.getGender()) {
            return false;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item item = ii.getEquipById(itemId);
        boolean cash = ii.isCash(itemId);
        short target = cash ? (short) (position - 100) : position;
        return item instanceof Equip equip && ii.canWearEquipment(agent, equip, target)
                && (position != SLOTS.get("weapon") || cash
                    || AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, ii.getWeaponType(itemId)));
    }

    /** The weapons the living world hands out: every loadout's equips and outfit weapons, and the first-job starter kits. */
    static Set<Integer> handedOut(Collection<Loadout> loadouts) {
        Set<Integer> ids = new HashSet<>();
        for (Loadout loadout : loadouts) {
            ids.addAll(loadout.equipIds());
            ids.addAll(loadout.outfit().getOrDefault("weapon", List.of()));
        }
        for (Job job : AgentStarterKitCatalog.kittedFirstJobs()) {
            for (AgentStarterItemGrant grant : AgentStarterKitCatalog.firstJobKitFor(job)) {
                ids.add(grant.itemId());
            }
        }
        ids.add(BotCreator.BASIC_POLEARM); // an Aran's, made with it
        ids.removeIf(id -> !ItemConstants.isWeapon(id));
        return ids;
    }

    /** An outfit weapon replaces a handed-out weapon, never one the Resident earned (looted, bought, traded). */
    static boolean replaces(int weaponId, int keep, Set<Integer> handedOut) {
        return weaponId != keep && handedOut.contains(weaponId);
    }

    /** Puts the item (0 = nothing) in the slot; a regular item also clears a cash item that would cover it. */
    private static boolean wear(Character agent, short position, int itemId) {
        short cashPosition = (short) (position - 100);
        if (itemId == 0) {
            return clear(agent, position) | clear(agent, cashPosition);
        }
        boolean cash = ItemInformationProvider.getInstance().isCash(itemId);
        short target = cash ? cashPosition : position;
        boolean changed = cash ? false : clear(agent, cashPosition);
        Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(target);
        if (worn != null && worn.getItemId() == itemId) {
            return changed;
        }
        if (agent.getInventory(InventoryType.EQUIP).countById(itemId) == 0) {
            if (agent.getInventory(InventoryType.EQUIP).isFull()
                    || !InventoryManipulator.addById(agent.getClient(), itemId, (short) 1)) {
                log.debug("Resident {}: no room for outfit item {}", agent.getName(), itemId);
                return changed;
            }
        }
        return AgentEquipmentService.equipInSlot(agent, itemId, target) || changed;
    }

    private static boolean isWorn(Character agent, short position, int itemId) {
        Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(position);
        return worn != null && worn.getItemId() == itemId;
    }

    /**
     * The other weapons the living world handed it leave its bag (the Agent gear optimizer would switch back to
     * whichever hits harder). A weapon it earned (looted, bought, traded), a piece with an owner tag (its own quest
     * gear, someone's gift), an untradeable or scrolled piece (handed-out weapons are plain) and what its contract lets
     * it keep all stay.
     */
    private static boolean dropOtherWeapons(Character agent, int keep, Set<Integer> handedOut, Set<Integer> kept) {
        boolean changed = false;
        for (Item item : new ArrayList<>(agent.getInventory(InventoryType.EQUIP).list())) {
            boolean tagged = item.getOwner() != null && !item.getOwner().isEmpty();
            boolean untradeable = (item.getFlag() & ItemConstants.UNTRADEABLE) != 0;
            if (replaces(item.getItemId(), keep, handedOut) && !kept.contains(item.getItemId()) && !tagged && !untradeable
                    && ResidentKit.scrolls(item) == 0) {
                InventoryManipulator.removeFromSlot(agent.getClient(), InventoryType.EQUIP, item.getPosition(), item.getQuantity(), false);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean clear(Character agent, short position) {
        if (agent.getInventory(InventoryType.EQUIPPED).getItem(position) == null) {
            return false;
        }
        return AgentEquipmentService.clearSlot(agent, position);
    }
}

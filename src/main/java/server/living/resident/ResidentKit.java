package server.living.resident;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.LivingWorldConfig;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.GearReward;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.LivingContent;
import server.living.quest.FriendForge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Resident's own quest gear (docs/LIVING_WORLD_GUIDE.md "Friendship"): the weapons and armour its friendship quests
 * give, worn once its level allows. Per equip slot it wears the highest-level piece from its quests that the game lets
 * it wear (the bot class rule, level and stats; a pool piece also its gender), scrolled the way the quest scrolls it: a
 * recipe in full, a friendship-scaled piece as at the quest's friendship gate.
 *
 * <p>Its copies are its own: each carries its character's name as the owner tag and the untradeable flag, so no Agent
 * trade, drop or sale (nor an equipped-slot trade) takes it, and each piece is made at most once per Resident
 * ({@link Ledger}); a copy that left anyway is never made again. The kit takes off only its own earlier quest pieces
 * and its loadout or outfit pieces (they go to its bag), and deletes nothing but its own superseded quest pieces: a
 * gift, loot or a contract's kept item it wears stays on, and what it carries stays carried.
 */
final class ResidentKit {
    private static final Logger log = LoggerFactory.getLogger(ResidentKit.class);
    /** Event kind recording each piece made for a persona (detail: the item id). */
    static final String ISSUED = "kit.issued";

    private ResidentKit() {
    }

    /** One piece to wear: where, and how its copy is scrolled. */
    record Piece(int itemId, short slot, int reqLevel, FriendForge.Plan plan) { }

    /** The pieces already made for each persona, so none is made twice. */
    interface Ledger {
        /** Reserves the piece for making; false when it was made before, is being made, or the ledger cannot be read. */
        boolean claim(String personaId, int itemId);

        /** The piece was made and handed to the Resident: recorded for good. */
        void record(String personaId, int itemId);

        /** Making the claimed piece failed: it may be made another time. */
        void release(String personaId, int itemId);
    }

    /** Per equip slot, the quest piece this character would wear now; empty for a hired citizen or no quest gear. */
    static Map<Short, Piece> picks(Character agent, Persona persona, LivingContent content, LivingWorldConfig config) {
        Map<Short, Piece> out = new LinkedHashMap<>();
        if (agent == null || persona == null || LivingContent.isHire(persona.id())) {
            return out;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int job = agent.getJob().getId();
        for (String questId : persona.questLines()) {
            Quest quest = content.quest(questId);
            if (quest == null) {
                continue;
            }
            for (GearReward gear : quest.rewards().gear()) {
                for (int itemId : gear.pool()) {
                    short slot = slotOf(itemId);
                    if (slot == 0 || !ResidentWardrobe.wearable(agent, itemId, slot)) {
                        continue;
                    }
                    int reqLevel = ii.getEquipLevelReq(itemId);
                    Piece held = out.get(slot);
                    if (held != null && held.reqLevel() > reqLevel) {
                        continue;
                    }
                    FriendForge.Plan plan = gear.scrolls().isEmpty()
                            ? FriendForge.plan(itemId, job, quest.requires().minFriendship(), config.friendshipScrollTakes60(),
                                    config.friendshipScrollTakes10(), FriendForge.wz())
                            : FriendForge.recipe(itemId, gear.scrolls(), FriendForge.classOf(itemId, job), FriendForge.wz());
                    out.put(slot, new Piece(itemId, slot, reqLevel, plan));
                }
            }
        }
        Piece top = out.get((short) -5);
        if (top != null && ItemConstants.isOverall(top.itemId())) {
            out.remove((short) -6); // an overall is the bottom too
        }
        Piece weapon = out.get((short) -11);
        if (weapon != null && ii.isTwoHanded(weapon.itemId())) {
            out.remove((short) -10); // no shield with a two-handed weapon
        }
        return out;
    }

    /** Wears the picks; true when anything changed (the character is then saved). */
    static boolean apply(Character agent, Persona persona, LivingContent content, LivingWorldConfig config, Loadout loadout,
                         Ledger ledger) {
        Map<Short, Piece> picks = picks(agent, persona, content, config);
        if (picks.isEmpty()) {
            return false;
        }
        Set<Integer> questIds = questIds(persona, content);
        Set<Integer> loadoutIds = loadoutIds(loadout);
        boolean changed = false;
        for (Piece piece : picks.values()) {
            changed |= wear(agent, persona, piece, questIds, loadoutIds, ledger);
        }
        if (changed) {
            agent.saveCharToDB();
            log.info("Resident {} wears its quest gear {}", persona.id(), picks.values().stream().map(Piece::itemId).toList());
        }
        return changed;
    }

    private static boolean wear(Character agent, Persona persona, Piece piece, Set<Integer> questIds, Set<Integer> loadoutIds,
                                Ledger ledger) {
        Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(piece.slot());
        if (ownCopy(agent, worn, piece.itemId())) {
            return false; // already on, as it was made
        }
        if (worn != null && !replaceable(agent.getName(), worn, questIds, loadoutIds)) {
            return false; // a gift, loot or a contract's kept piece: what it chose to wear stays
        }
        boolean changed = false;
        Item mine = carriedCopy(agent, piece.itemId());
        if (mine == null) {
            if (agent.getInventory(InventoryType.EQUIP).isFull() || !ledger.claim(persona.id(), piece.itemId())) {
                return false; // no room, or made once already (and gone: never made again)
            }
            Item made = FriendForge.forge(piece.plan());
            made.setOwner(agent.getName());
            made.setFlag((short) (made.getFlag() | ItemConstants.UNTRADEABLE));
            if (!InventoryManipulator.addFromDrop(agent.getClient(), made, false)) {
                ledger.release(persona.id(), piece.itemId());
                return false;
            }
            ledger.record(persona.id(), piece.itemId());
            changed = true;
            mine = carriedCopy(agent, piece.itemId());
            if (mine == null) {
                return true;
            }
        }
        if (!wearCarried(agent, mine, piece.slot())) {
            log.debug("Resident {}: could not wear quest gear {} in {}", persona.id(), piece.itemId(), piece.slot());
            return changed;
        }
        discardSuperseded(agent, piece, questIds);
        return true;
    }

    /** Puts this exact carried item on; what was worn there goes to the bag in its place. */
    private static boolean wearCarried(Character agent, Item item, short slot) {
        // by position, not by id (AgentEquipmentService.equipInSlot): a gift of the same id may sit in the bag too; equip
        // swaps the worn piece into the freed bag slot
        InventoryManipulator.equip(agent.getClient(), item.getPosition(), slot);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByCharacterInstance(agent);
        if (entry != null) { // as the Agent equip service does after a change
            AgentInventoryReservationRuntime.refreshEquipmentReservations(entry, agent, System.currentTimeMillis());
        }
        return agent.getInventory(InventoryType.EQUIPPED).getItem(slot) == item;
    }

    /**
     * After a quest piece goes on, the Resident's own earlier quest pieces for that slot (its tag on them) leave its bag,
     * so the Agent gear optimizer cannot switch back. Nothing else is deleted: an untagged piece may be a gift, loot or a
     * contract's kept item even when its id is a loadout's.
     */
    private static void discardSuperseded(Character agent, Piece piece, Set<Integer> questIds) {
        for (Item item : new ArrayList<>(agent.getInventory(InventoryType.EQUIP).list())) {
            int id = item.getItemId();
            if (id != piece.itemId() && questIds.contains(id) && slotOf(id) == piece.slot()
                    && agent.getName().equals(item.getOwner())) {
                InventoryManipulator.removeFromSlot(agent.getClient(), InventoryType.EQUIP, item.getPosition(), item.getQuantity(), false);
            }
        }
    }

    static boolean replaceable(String characterName, Item worn, Set<Integer> questIds, Set<Integer> loadoutIds) {
        return replaceable(characterName, worn.getItemId(), worn.getOwner(), scrolls(worn), questIds, loadoutIds);
    }

    /**
     * True for what the kit may take off (to the bag): its own quest pieces (its character's name as the tag), and plain
     * untagged pieces whose id is its loadout's or outfit's (those are made plain, so a scrolled one is a gift). Anyone
     * else's tagged piece, or any other piece, stays on.
     */
    static boolean replaceable(String characterName, int itemId, String owner, int scrolls, Set<Integer> questIds,
                               Set<Integer> loadoutIds) {
        if (characterName.equals(owner)) {
            return questIds.contains(itemId);
        }
        return (owner == null || owner.isEmpty()) && scrolls == 0 && loadoutIds.contains(itemId);
    }

    /** The successful scrolls on a piece; 0 for a plain one. */
    static int scrolls(Item item) {
        return item instanceof Equip equip ? equip.getLevel() : 0;
    }

    private static boolean ownCopy(Character agent, Item item, int itemId) {
        return item != null && item.getItemId() == itemId && agent.getName().equals(item.getOwner());
    }

    private static Item carriedCopy(Character agent, int itemId) {
        for (Item item : agent.getInventory(InventoryType.EQUIP).list()) {
            if (ownCopy(agent, item, itemId)) {
                return item;
            }
        }
        return null;
    }

    /** The slots the character now wears its own quest pieces in, with the bottom under an own overall. */
    static Set<Short> slotsHeld(Character agent, Map<Short, Piece> picks) {
        Set<Short> held = new HashSet<>();
        for (Piece piece : picks.values()) {
            Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(piece.slot());
            if (ownCopy(agent, worn, piece.itemId())) {
                held.add(piece.slot());
                if (ItemConstants.isOverall(piece.itemId())) {
                    held.add((short) -6);
                }
            }
        }
        return held;
    }

    /** Every piece the persona's quests can give, of any tier. */
    static Set<Integer> questIds(Persona persona, LivingContent content) {
        Set<Integer> ids = new HashSet<>();
        for (String questId : persona.questLines()) {
            Quest quest = content.quest(questId);
            if (quest != null) {
                for (GearReward gear : quest.rewards().gear()) {
                    ids.addAll(gear.pool());
                }
            }
        }
        return ids;
    }

    /** The loadout's own pieces: its equips and every outfit pool entry. */
    static Set<Integer> loadoutIds(Loadout loadout) {
        Set<Integer> ids = new HashSet<>();
        if (loadout != null) {
            ids.addAll(loadout.equipIds());
            for (List<Integer> pool : loadout.outfit().values()) {
                ids.addAll(pool);
            }
        }
        return ids;
    }

    /** The regular equip slot a piece is worn in (hat -1, earrings -4, top or overall -5, ... weapon -11); 0 for none. */
    static short slotOf(int itemId) {
        int category = itemId / 10000;
        if (category >= 130 && category < 150) {
            return -11;
        }
        return switch (category) {
            case 100 -> -1;
            case 103 -> -4;
            case 104, 105 -> -5;
            case 106 -> -6;
            case 107 -> -7;
            case 108 -> -8;
            case 109 -> -10;
            case 110 -> -9;
            default -> 0;
        };
    }
}

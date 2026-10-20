package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.maps.MapItem;

import java.util.Map;
import java.util.Set;

/**
 * What a companion may pick up in the Crimsonwood Armory (CWKPQ plan B8), a refuse-only clause for the shared loot
 * eligibility: it never makes a drop lootable that the stock rules refuse. The Armory's chests spray equips, books,
 * scrolls, Gelt Chocolate and the Tao materials (reactordrops 6102002-6102005). A companion takes what its own class
 * can use: an equip its archetype may wear, a book that teaches its job a skill, a weapon scroll for the weapon in its
 * hand. It leaves Gelt Chocolate (weapon attack) to physical attackers, so a magician does not take it; and it never
 * takes the Tao materials (Fiona's crafting) or anything else no class uses (storybooks, the chair), which are the
 * humans'. Any other potion it may take. Mesos are always fine. Outside the Armory nothing changes.
 */
public final class AgentCwkpqLootRule {
    public static final int ARMORY_MAP_ID = 610_030_800;
    static final int GELT_CHOCOLATE = 2_022_121;
    static final Set<Integer> TAO_MATERIALS = Set.of(4_032_015, 4_032_016, 4_032_017);
    /** Item.wz reqJob: one bit per archetype, 0 for every class. */
    static final Map<Archetype, Integer> REQ_JOB_BITS = Map.of(Archetype.WARRIOR, 1, Archetype.MAGICIAN, 2,
            Archetype.BOWMAN, 4, Archetype.THIEF, 8, Archetype.PIRATE, 16);
    private static final short WEAPON_SLOT = -11;

    /** One drop as one companion in the Armory sees it: whether its class can use it, worked out by {@link #facts}. */
    record Facts(int itemId, boolean meso, Archetype archetype, boolean forItsClass) {
    }

    private AgentCwkpqLootRule() {
    }

    /** True when {@code bot}, standing in the Armory, must leave {@code drop} to someone else. */
    public static boolean refuses(Character bot, MapItem drop) {
        return bot != null && drop != null && bot.getMapId() == ARMORY_MAP_ID && !allows(facts(bot, drop));
    }

    static boolean allows(Facts facts) {
        if (facts.meso()) {
            return true;
        }
        int itemId = facts.itemId();
        if (TAO_MATERIALS.contains(itemId)) {
            return false;
        }
        if (itemId == GELT_CHOCOLATE) {
            return facts.archetype() != null && facts.archetype() != Archetype.MAGICIAN;
        }
        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (type == InventoryType.EQUIP || isBook(itemId) || isWeaponScroll(itemId)) {
            return facts.forItsClass();
        }
        return type == InventoryType.USE;
    }

    static Facts facts(Character bot, MapItem drop) {
        int itemId = drop.getItemId();
        boolean meso = drop.getMeso() > 0;
        Archetype archetype = AgentCwkpqSigils.archetypeOf(bot.getJob().getId());
        return new Facts(itemId, meso, archetype, !meso && forItsClass(bot, archetype, itemId));
    }

    private static boolean forItsClass(Character bot, Archetype archetype, int itemId) {
        ItemInformationProvider items = ItemInformationProvider.getInstance();
        if (ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP) {
            Map<String, Integer> stats = items.getEquipStats(itemId);
            return stats != null && wearsReqJob(archetype, stats.getOrDefault("reqJob", 0));
        }
        if (isBook(itemId)) {
            Map<String, Integer> stats = items.getSkillStats(itemId, bot.getJob().getId());
            return stats != null && stats.getOrDefault("skillid", 0) != 0;
        }
        if (isWeaponScroll(itemId)) {
            Item weapon = bot.getInventory(InventoryType.EQUIPPED).getItem(WEAPON_SLOT);
            return weapon != null && scrollsWeapon(itemId, weapon.getItemId());
        }
        return false;
    }

    /** True when an equip with Item.wz {@code reqJob} fits {@code archetype}: 0 is every class, else its bit. */
    static boolean wearsReqJob(Archetype archetype, int reqJob) {
        if (reqJob == 0) {
            return true;
        }
        Integer bit = archetype == null ? null : REQ_JOB_BITS.get(archetype);
        return bit != null && reqJob > 0 && (reqJob & bit) != 0;
    }

    /** A skill book or mastery book. */
    static boolean isBook(int itemId) {
        int kind = itemId / 10_000;
        return kind == 228 || kind == 229;
    }

    /** A scroll for a weapon (2043xxxx-2044xxxx in v83: 2043 one-handed, 2044 two-handed and the rest). */
    static boolean isWeaponScroll(int itemId) {
        int kind = itemId / 10_000;
        return kind == 204 && (itemId / 1_000) % 10 >= 3;
    }

    /** True when weapon scroll {@code scrollId} is for weapon {@code weaponId}: 2044803 (knuckle 48) on 1482xxx. */
    static boolean scrollsWeapon(int scrollId, int weaponId) {
        return (scrollId / 100) % 100 == (weaponId / 10_000) % 100;
    }
}

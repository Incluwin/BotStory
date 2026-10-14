package server.living.quest;

import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.agents.capabilities.equipment.AgentEquipmentOwnerTags;
import server.knowledge.GearPicks;
import server.knowledge.ItemFact;
import server.knowledge.WorldKnowledge;
import server.living.config.Content.ScrollRecipe;
import server.living.quest.FriendForge.Catalog;
import server.living.quest.FriendForge.Plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * The {@code !bossgear} kit (docs/COMPANION_BOSSING_PLAN.md G13): for a character of a job and level, a piece of this
 * server's gear in each GearPicks slot ({@link GearPicks#pick}: named, non-cash, obtainable, within its job, gender and
 * main stats, stable for its seed), every upgrade slot holding a passed 10% scroll chosen for its job (60% where the piece takes no 10%)
 * ({@link FriendForge#recipe}). Each slot takes the highest band at or under the level that has a piece: v83 gear stops
 * near level 120, so a level 160 character gets level 120 pieces. One top: the overall or the coat, whichever is the
 * higher level (the overall on a tie), and pants only under a coat. The weapon keeps the worn one's type while the job
 * can use it, else the first type it can; a shield comes only beside a worn one, and only when the weapon keeps its type
 * (a new type may be two-handed). Pure: nothing is given or worn here.
 */
public final class BossGearPlanner {
    /**
     * The owner tag every boss-gear piece carries; with UNTRADEABLE it marks a GM test kit, never trade goods. The Agent
     * engine reads the same text, so its look-diversity filter never takes a worn kit piece off.
     */
    public static final String OWNER_TAG = AgentEquipmentOwnerTags.BOSS_GEAR;
    /**
     * Every upgrade slot takes a 10% scroll, the strongest standard grade (user 2026-10-13: scroll the bots' gear so
     * they deal damage faster; it was 60%), and every scroll passes; a piece no 10% scroll fits takes 60% instead.
     */
    static final List<ScrollRecipe> RECIPE = List.of(new ScrollRecipe(10, ScrollRecipe.REST),
            new ScrollRecipe(60, ScrollRecipe.REST));
    /** Bands are tried from the character's own level band down in these steps (GearPicks spans 20 levels a band). */
    private static final int BAND_STEP = 10;
    private static final int FIRST_WEAPON_TYPE = 130;
    private static final int LAST_WEAPON_TYPE = 149;
    private static final int WEAPON_TYPE = 10_000;
    private static final int SEED_PRIME = 31;
    private static final short CAP = -1;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short SHOES = -7;
    private static final short GLOVE = -8;
    private static final short CAPE = -9;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;

    private BossGearPlanner() {
    }

    /**
     * Who wears the kit: its job id and level, gender (0 male, 1 female), main stats (str, dex, int, luk), what it
     * wears (equipped position to item id), which weapon types ({@code id / 10000}, 130-149) its job can use, and the
     * seed its picks are stable for (its character id).
     */
    public record Wearer(int jobId, int level, int gender, int[] stats, Map<Short, Integer> worn,
                         IntPredicate weaponFits, long seed) {
    }

    /** One piece of the kit: the equipped position it goes on, its GearPicks category and how it is scrolled. */
    public record Piece(short position, String category, Plan plan) {
        public int itemId() {
            return plan.itemId();
        }
    }

    /** The kit for {@code wearer}, in equip order (the weapon before the shield); empty without knowledge. */
    public static List<Piece> plan(WorldKnowledge k, Catalog catalog, Wearer wearer) {
        List<Piece> kit = new ArrayList<>();
        if (k == null) {
            return kit;
        }
        add(kit, catalog, wearer, CAP, "cap", best(k, "cap", wearer, 0));
        int overall = best(k, "longcoat", wearer, 0);
        int coat = best(k, "coat", wearer, 0);
        boolean wearsOverall = overall != 0 && level(k, overall) >= level(k, coat);
        add(kit, catalog, wearer, TOP, wearsOverall ? "longcoat" : "coat", wearsOverall ? overall : coat);
        if (!wearsOverall) {
            add(kit, catalog, wearer, PANTS, "pants", best(k, "pants", wearer, 0));
        }
        add(kit, catalog, wearer, SHOES, "shoes", best(k, "shoes", wearer, 0));
        add(kit, catalog, wearer, GLOVE, "glove", best(k, "glove", wearer, 0));
        add(kit, catalog, wearer, CAPE, "cape", best(k, "cape", wearer, 0));
        int wornType = wearer.worn().getOrDefault(WEAPON, 0) / WEAPON_TYPE;
        List<Integer> types = new ArrayList<>();
        if (wornType != 0 && wearer.weaponFits().test(wornType)) {
            types.add(wornType);
        }
        for (int type = FIRST_WEAPON_TYPE; type <= LAST_WEAPON_TYPE; type++) {
            if (type != wornType && wearer.weaponFits().test(type)) {
                types.add(type);
            }
        }
        int weapon = 0;
        for (int i = 0; i < types.size() && weapon == 0; i++) {
            weapon = best(k, "weapon", wearer, types.get(i));
        }
        add(kit, catalog, wearer, WEAPON, "weapon", weapon);
        if (wearer.worn().containsKey(SHIELD) && (weapon == 0 || weapon / WEAPON_TYPE == wornType)) {
            add(kit, catalog, wearer, SHIELD, "shield", best(k, "shield", wearer, 0));
        }
        return kit;
    }

    /** The piece the kit describes, forged from the WZ with its scrolls passed, untradeable and tagged. */
    public static Item forge(Piece piece) {
        return tag(FriendForge.forge(piece.plan()));
    }

    /** Marks a boss-gear piece: untradeable, owner tag {@link #OWNER_TAG}. */
    static Item tag(Item item) {
        item.setOwner(OWNER_TAG);
        item.setFlag((short) (item.getFlag() | ItemConstants.UNTRADEABLE));
        return item;
    }

    /** The pick of the highest band at or under the wearer's level that has one; 0 when no band has any. */
    private static int best(WorldKnowledge k, String category, Wearer wearer, int weaponType) {
        int mask = FriendForge.jobMask(wearer.jobId());
        long seed = wearer.seed() * SEED_PRIME + category.hashCode();
        for (int band = wearer.level() / BAND_STEP * BAND_STEP; band >= 0; band -= BAND_STEP) {
            int pick = GearPicks.pick(k, category, band, mask, wearer.gender(), weaponType, wearer.stats(), seed);
            if (pick != 0) {
                return pick;
            }
        }
        return 0;
    }

    private static void add(List<Piece> kit, Catalog catalog, Wearer wearer, short position, String category, int itemId) {
        if (itemId != 0) {
            kit.add(new Piece(position, category, FriendForge.recipe(itemId, RECIPE, wearer.jobId(), catalog)));
        }
    }

    private static int level(WorldKnowledge k, int itemId) {
        return k.item(itemId).map(ItemFact::reqLevel).orElse(-1);
    }
}

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
import server.living.quest.FriendForge.Scroll;
import server.living.quest.FriendForge.Use;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final short FACE = -2;
    private static final short EYE = -3;
    private static final short EARRING = -4;
    private static final short PENDANT = -17;
    /** The accessory slots an accuracy goal may fill when they are empty, by their GearPicks category. */
    private static final Map<Short, String> ACCESSORIES = accessories();
    private static final String NO_NAME = "MISSING NAME";
    private static final int MAGICIAN = 2;

    private BossGearPlanner() {
    }

    /**
     * An accuracy goal for a kit: {@code need} is the accuracy at which every hit lands on the boss's parts at the
     * wearer's level (the v83 hit formula Agents roll); {@code base} is the wearer's accuracy without anything worn in
     * the kit's or the accessory slots; {@code worn} is what each piece it wears in those slots adds, by position.
     */
    public record Accuracy(int need, double base, Map<Short, Double> worn) {
        public Accuracy {
            worn = worn == null ? Map.of() : Map.copyOf(worn);
        }
    }

    /**
     * The kit for {@code wearer} with an accuracy goal (user 2026-10-15: warriors keep AP out of DEX and get the
     * accuracy a boss needs from their equipment, losing as little damage as they can). The plain kit comes first,
     * every slot scrolled for damage; while it falls short of {@code goal}, empty accessory slots take the accessory
     * that adds the most accuracy, then scrolls give way to accuracy scrolls of the same grade, the one that costs the
     * least damage for each point of accuracy first, until the goal is met or nothing is left to change
     * ({@link #accuracy} then tells how short it stays: AP into DEX is the last resort, outside the kit). A magician's
     * kit is unchanged: its magic accuracy comes from INT and LUK.
     */
    public static List<Piece> plan(WorldKnowledge k, Catalog catalog, Wearer wearer, Accuracy goal) {
        List<Piece> kit = plan(k, catalog, wearer);
        if (k == null || goal == null || goal.need() <= 0 || (wearer.jobId() / 100) % 10 == MAGICIAN) {
            return kit;
        }
        for (Map.Entry<Short, String> slot : ACCESSORIES.entrySet()) {
            if (accuracy(kit, catalog, goal) >= goal.need()) {
                return kit;
            }
            if (!wearer.worn().containsKey(slot.getKey())) {
                Plan accessory = accessory(k, catalog, wearer, slot.getValue());
                if (accessory != null) {
                    kit.add(new Piece(slot.getKey(), slot.getValue(), accessory));
                }
            }
        }
        if (accuracy(kit, catalog, goal) < goal.need()) {
            swapForAccuracy(kit, catalog, wearer.jobId(), goal);
        }
        return kit;
    }

    /** The accuracy the wearer reaches in {@code kit}: its base, what it keeps wearing elsewhere, and the kit. */
    public static int accuracy(List<Piece> kit, Catalog catalog, Accuracy goal) {
        double total = goal.base();
        Set<Short> covered = new HashSet<>();
        for (Piece piece : kit) {
            covered.add(piece.position());
            total += accuracyOf(piece.plan(), catalog);
        }
        for (Map.Entry<Short, Double> worn : goal.worn().entrySet()) {
            if (!covered.contains(worn.getKey())) {
                total += worn.getValue();
            }
        }
        return (int) Math.floor(total);
    }

    /** The accuracy a piece adds as planned: its own and its scrolls', DEX at 0.8 and LUK at 0.5 (Agents' formula). */
    static double accuracyOf(Plan plan, Catalog catalog) {
        Map<String, Integer> info = catalog.equipStats(plan.itemId());
        double accuracy = info == null ? 0 : accuracyOf(info);
        for (Use use : plan.uses()) {
            accuracy += use.takes() * accuracyOf(use.scroll().stats());
        }
        return accuracy;
    }

    static double accuracyOf(Map<String, Integer> stats) {
        return stats.getOrDefault("ACC", 0) + 0.8 * stats.getOrDefault("DEX", 0) + 0.5 * stats.getOrDefault("LUK", 0);
    }

    /**
     * The obtainable accessory of {@code category} the wearer can wear that adds the most accuracy once scrolled to the
     * kit's recipe (the lowest id on a tie); null when none adds any.
     */
    private static Plan accessory(WorldKnowledge k, Catalog catalog, Wearer wearer, String category) {
        int mask = FriendForge.jobMask(wearer.jobId());
        Plan best = null;
        double bestAccuracy = 0;
        for (ItemFact item : k.items()) {
            if (!wears(item, category, wearer, mask) || !k.obtainable(item.id())) {
                continue;
            }
            Plan plan = FriendForge.recipe(item.id(), RECIPE, wearer.jobId(), catalog);
            double accuracy = accuracyOf(plan, catalog);
            if (accuracy > bestAccuracy || accuracy == bestAccuracy && best != null && item.id() < best.itemId()) {
                best = plan;
                bestAccuracy = accuracy;
            }
        }
        return best;
    }

    private static boolean wears(ItemFact item, String category, Wearer wearer, int mask) {
        if (!item.equip() || !category.equals(item.category()) || item.cash() || item.unnamed() || item.name().isEmpty()
                || NO_NAME.equals(item.name()) || item.reqLevel() > wearer.level()) {
            return false;
        }
        if (item.reqJob() != 0 && (mask == 0 || (item.reqJob() & mask) == 0)) {
            return false;
        }
        int itemGender = item.id() / 1000 % 10;
        if (itemGender <= 1 && itemGender != wearer.gender()) {
            return false;
        }
        int[] stats = wearer.stats();
        return item.reqStr() <= stats[0] && item.reqDex() <= stats[1] && item.reqInt() <= stats[2]
                && item.reqLuk() <= stats[3] && item.reqPop() <= 0;
    }

    /** One slot of a planned scroll given over to an accuracy scroll: what it costs the job and the accuracy it adds. */
    private record Swap(int piece, int use, Scroll scroll, double cost, double gain) {
        double costPerPoint() {
            return cost / gain;
        }
    }

    /**
     * Turns the kit's scrolls into accuracy scrolls of the same grade, one slot at a time and cheapest damage per point
     * of accuracy first ({@link FriendForge#value} for the job), until the goal is met or no slot is left to turn.
     */
    private static void swapForAccuracy(List<Piece> kit, Catalog catalog, int jobId, Accuracy goal) {
        List<Swap> swaps = new ArrayList<>();
        for (int i = 0; i < kit.size(); i++) {
            Piece piece = kit.get(i);
            List<Scroll> scrolls = FriendForge.standard(catalog.scrollsFor(piece.itemId()));
            List<Use> uses = piece.plan().uses();
            for (int u = 0; u < uses.size(); u++) {
                Swap best = bestSwap(i, u, uses.get(u), scrolls, jobId);
                for (int slot = 0; best != null && slot < uses.get(u).takes(); slot++) {
                    swaps.add(best);
                }
            }
        }
        swaps.sort(Comparator.comparingDouble(Swap::costPerPoint).thenComparingInt(Swap::piece)
                .thenComparingInt(Swap::use));
        double total = accuracy(kit, catalog, goal);
        Map<Long, Integer> turned = new LinkedHashMap<>();
        Map<Long, Swap> chosen = new HashMap<>();
        for (Swap swap : swaps) {
            if (total >= goal.need()) {
                break;
            }
            long key = (long) swap.piece() << 32 | swap.use();
            turned.merge(key, 1, Integer::sum);
            chosen.put(key, swap);
            total += swap.gain();
        }
        for (int i = 0; i < kit.size(); i++) {
            Piece piece = kit.get(i);
            List<Use> kept = new ArrayList<>();
            List<Use> added = new ArrayList<>();
            List<Use> uses = piece.plan().uses();
            for (int u = 0; u < uses.size(); u++) {
                Use old = uses.get(u);
                long key = (long) i << 32 | u;
                int count = turned.getOrDefault(key, 0);
                if (old.count() > count) {
                    kept.add(count == 0 ? old : new Use(old.scroll(), old.count() - count, old.takes() - count));
                }
                if (count > 0) {
                    added.add(new Use(chosen.get(key).scroll(), count, count));
                }
            }
            if (!added.isEmpty()) {
                kept.addAll(added);
                kit.set(i, new Piece(piece.position(), piece.category(),
                        new Plan(piece.itemId(), piece.plan().slots(), List.copyOf(kept))));
            }
        }
    }

    /** The same-grade scroll that adds more accuracy than {@code use}'s for the least damage per point; null for none. */
    private static Swap bestSwap(int piece, int useIndex, Use use, List<Scroll> scrolls, int jobId) {
        Scroll now = use.scroll();
        double accuracyNow = accuracyOf(now.stats());
        double valueNow = FriendForge.value(now.stats(), jobId);
        Swap best = null;
        for (Scroll scroll : scrolls) {
            if (scroll.success() != now.success()) {
                continue;
            }
            double gain = accuracyOf(scroll.stats()) - accuracyNow;
            if (gain <= 0) {
                continue;
            }
            Swap swap = new Swap(piece, useIndex, scroll, valueNow - FriendForge.value(scroll.stats(), jobId), gain);
            if (best == null || swap.costPerPoint() < best.costPerPoint()
                    || swap.costPerPoint() == best.costPerPoint() && scroll.id() < best.scroll().id()) {
                best = swap;
            }
        }
        return best;
    }

    private static Map<Short, String> accessories() {
        Map<Short, String> slots = new LinkedHashMap<>();
        slots.put(PENDANT, "pendant");
        slots.put(EYE, "eye");
        slots.put(FACE, "face");
        slots.put(EARRING, "earring");
        return Collections.unmodifiableMap(slots);
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

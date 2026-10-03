package server.living.quest;

import client.inventory.Equip;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.living.config.Content.ScrollRecipe;
import tools.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The equipment a Resident scrolls for a friend ({@code rewards.gear}, docs/LIVING_WORLD_GUIDE.md "Friendship").
 *
 * <p>Which piece: the first in the pool made for the player's job (any piece with no job requirement fits everyone), a
 * weapon of the type they wield before any other. Which scroll: of the standard 100%, 60% and 10% scrolls for the piece,
 * each grade's scroll that does the most for the job (attack or magic attack first, then the job's main stat). How many
 * take: every slot is scrolled and the friendship level fixes how many succeed, {@code takes60}/{@code takes10} percent
 * of the slots (100% scrolls always take; a 0 means the friend does not use that grade yet). Failed slots are spent, as a
 * failed scroll's are, and nothing is ever destroyed. The friend uses the grade that makes the better piece, so a higher
 * level never makes a worse one.
 *
 * <p>A giver's own piece ({@link #recipe}) is instead scrolled to its authored recipe (say two 10% and five 60%) with
 * every scroll taking, each grade's scroll chosen for the class the piece is made for.
 *
 * <p>Event scrolls that sell 10% stats at 100% are not standard: a scroll whose stats match or beat those of a scroll
 * with a lower success rate for the same piece is left out.
 */
public final class FriendForge {
    private static final String[] STATS = {"STR", "DEX", "INT", "LUK", "PAD", "PDD", "MAD", "MDD", "ACC", "EVA",
            "Speed", "Jump", "MHP", "MMP"};
    private static final int[] GRADES = {10, 60, 100}; // strongest first: an equal piece goes to the stronger scrolls

    private FriendForge() {
    }

    /** A scroll's success rate and the stats one success adds. */
    public record Scroll(int id, int success, Map<String, Integer> stats) { }

    /** Where the forge reads equipment and scrolls: the WZ in the server ({@link #wz()}), a table in tests. */
    public interface Catalog {
        /** The equipment's info ({@code reqJob}, {@code tuc} = slots, ...), or null for no such equipment. */
        Map<String, Integer> equipStats(int itemId);

        /** Every uncursed 10%, 60% or 100% stat scroll that applies to the equipment. */
        List<Scroll> scrollsFor(int itemId);
    }

    /** {@code count} slots scrolled with {@code scroll}, {@code takes} of them succeeding. */
    public record Use(Scroll scroll, int count, int takes) { }

    /** The piece's {@code slots} and the scrolls put into them (none: nothing worth applying). */
    public record Plan(int itemId, int slots, List<Use> uses) { }

    // ---- choosing ------------------------------------------------------------------------------

    /**
     * The pool's piece for this player: the first one their job may wear and their gender may (a gendered id's
     * thousands digit, as the wardrobe reads it), a weapon of the type they wield ({@code wieldedId}, 0 for none) before
     * any other; 0 when none fits.
     */
    public static int pick(List<Integer> pool, int jobId, int gender, int wieldedId, Catalog catalog) {
        int mask = jobMask(jobId);
        int wielded = weaponType(wieldedId);
        int first = 0;
        for (int itemId : pool) {
            Map<String, Integer> stats = catalog.equipStats(itemId);
            if (stats == null || !fitsJob(stats.getOrDefault("reqJob", 0), mask) || !fitsGender(itemId, gender)) {
                continue;
            }
            if (wielded > 0 && weaponType(itemId) == wielded) {
                return itemId;
            }
            if (first == 0) {
                first = itemId;
            }
        }
        return first;
    }

    /** How a friend at {@code friendshipLevel} (0-10) scrolls {@code itemId} for a player of {@code jobId}. */
    public static Plan plan(int itemId, int jobId, int friendshipLevel, int[] takes60, int[] takes10, Catalog catalog) {
        Map<String, Integer> info = catalog.equipStats(itemId);
        int slots = info == null ? 0 : Math.max(0, info.getOrDefault("tuc", 0));
        Plan best = new Plan(itemId, slots, List.of());
        if (slots == 0) {
            return best;
        }
        List<Scroll> scrolls = standard(catalog.scrollsFor(itemId));
        double bestValue = 0;
        for (int grade : GRADES) {
            int percent = grade == 100 ? 100 : friendshipLevel <= 0 ? 0
                    : (grade == 60 ? takes60 : takes10)[Math.min(friendshipLevel, takes60.length) - 1];
            Scroll scroll = percent <= 0 ? null : bestOf(scrolls, grade, jobId);
            if (scroll == null) {
                continue;
            }
            int takes = (int) Math.round(slots * percent / 100.0);
            double value = takes * value(scroll.stats(), jobId);
            if (value > bestValue) {
                bestValue = value;
                best = new Plan(itemId, slots, List.of(new Use(scroll, slots, takes)));
            }
        }
        return best;
    }

    /**
     * A piece scrolled to a recipe: each line's grade with the scroll that does the most for {@code jobId} (the class
     * the piece is made for, {@link #classOf}), every scroll taking; a line with no count ({@link ScrollRecipe#REST})
     * fills the slots left, and lines past the piece's slots are cut short.
     */
    public static Plan recipe(int itemId, List<ScrollRecipe> recipe, int jobId, Catalog catalog) {
        Map<String, Integer> info = catalog.equipStats(itemId);
        int left = info == null ? 0 : Math.max(0, info.getOrDefault("tuc", 0));
        int slots = left;
        List<Scroll> scrolls = standard(catalog.scrollsFor(itemId));
        List<Use> uses = new ArrayList<>();
        for (ScrollRecipe line : recipe) {
            Scroll scroll = bestOf(scrolls, line.success(), jobId);
            int count = line.count() == ScrollRecipe.REST ? left : Math.min(line.count(), left);
            if (scroll != null && count > 0) {
                uses.add(new Use(scroll, count, count));
                left -= count;
            }
        }
        return new Plan(itemId, slots, uses);
    }

    /** The piece the plan describes, fresh from the WZ with its scrolls applied. */
    public static Item forge(Plan plan) {
        Equip equip = (Equip) ItemInformationProvider.getInstance().getEquipById(plan.itemId());
        for (Use use : plan.uses()) {
            for (int scroll = 0; scroll < use.count(); scroll++) {
                if (scroll < use.takes()) {
                    ItemInformationProvider.improveEquipStats(equip, use.scroll().stats());
                    equip.setLevel((byte) (equip.getLevel() + 1));
                }
                equip.setUpgradeSlots((byte) (equip.getUpgradeSlots() - 1));
            }
        }
        return equip;
    }

    // ---- the rules ------------------------------------------------------------------------------

    /** Without the scrolls that match or beat a lower-success scroll's every stat (event scrolls). */
    static List<Scroll> standard(List<Scroll> scrolls) {
        List<Scroll> out = new ArrayList<>();
        for (Scroll scroll : scrolls) {
            boolean special = false;
            for (Scroll other : scrolls) {
                if (other.success() < scroll.success() && covers(scroll.stats(), other.stats())) {
                    special = true;
                    break;
                }
            }
            if (!special) {
                out.add(scroll);
            }
        }
        return out;
    }

    private static boolean covers(Map<String, Integer> stats, Map<String, Integer> other) {
        if (other.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Integer> stat : other.entrySet()) {
            if (stats.getOrDefault(stat.getKey(), 0) < stat.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static Scroll bestOf(List<Scroll> scrolls, int grade, int jobId) {
        Scroll best = null;
        double bestValue = 0;
        for (Scroll scroll : scrolls) {
            double value = scroll.success() == grade ? value(scroll.stats(), jobId) : 0;
            if (value > bestValue || value > 0 && value == bestValue && scroll.id() < best.id()) {
                bestValue = value;
                best = scroll;
            }
        }
        return best;
    }

    /**
     * What one success is worth to the job: its attack (magic attack for magicians) 1, its main stat 0.4, its second
     * stat 0.15, accuracy 0.1 (magicians 0.02), and a little for avoid, speed, jump, defences, HP and MP.
     */
    static double value(Map<String, Integer> stats, int jobId) {
        int niche = (jobId / 100) % 10;
        String attack = niche == 2 ? "MAD" : "PAD";
        String main;
        String second;
        switch (niche) {
            case 2 -> { main = "INT"; second = "LUK"; }
            case 3 -> { main = "DEX"; second = "STR"; }
            case 4 -> { main = "LUK"; second = "DEX"; }
            case 5 -> {
                boolean gunslinger = (jobId / 10) % 10 == 2;
                main = gunslinger ? "DEX" : "STR";
                second = gunslinger ? "STR" : "DEX";
            }
            default -> { main = "STR"; second = "DEX"; }
        }
        double value = 0;
        for (Map.Entry<String, Integer> stat : stats.entrySet()) {
            String key = stat.getKey();
            double weight;
            if (key.equals(attack)) {
                weight = 1.0;
            } else if (key.equals(main)) {
                weight = 0.4;
            } else if (key.equals(second)) {
                weight = 0.15;
            } else {
                weight = switch (key) {
                    case "ACC" -> niche == 2 ? 0.02 : 0.1;
                    case "EVA", "Speed" -> 0.04;
                    case "Jump", "PDD", "MDD" -> 0.03;
                    case "MHP" -> 0.02;
                    case "MMP" -> niche == 2 ? 0.02 : 0.005;
                    default -> 0;
                };
            }
            value += weight * stat.getValue();
        }
        return value;
    }

    /**
     * The job a weapon is made for (a sword a warrior's, a dagger a bandit's, a gun a gunslinger's), whose stats its
     * scrolls are chosen for; {@code fallbackJobId} for anything that is not a weapon.
     */
    public static int classOf(int itemId, int fallbackJobId) {
        return switch (weaponType(itemId)) {
            case 130, 131, 132, 140, 141, 142, 143, 144 -> 100;
            case 133 -> 420;
            case 137, 138 -> 200;
            case 145 -> 300;
            case 146 -> 320;
            case 147 -> 410;
            case 148 -> 510;
            case 149 -> 520;
            default -> fallbackJobId;
        };
    }

    /** The reqJob bit of the job's branch (warrior 1, magician 2, bowman 4, thief 8, pirate 16), 0 for a beginner. */
    public static int jobMask(int jobId) {
        return switch ((jobId / 100) % 10) {
            case 1 -> 0x1;
            case 2 -> 0x2;
            case 3 -> 0x4;
            case 4 -> 0x8;
            case 5 -> 0x10;
            default -> 0;
        };
    }

    private static boolean fitsJob(int reqJob, int mask) {
        return reqJob == 0 || mask != 0 && (reqJob & mask) != 0;
    }

    private static boolean fitsGender(int itemId, int gender) {
        int digit = (itemId / 1000) % 10;
        return digit > 1 || digit == gender;
    }

    /** The weapon category (130-149, one per weapon type), or 0 for anything else. */
    static int weaponType(int itemId) {
        int category = itemId / 10000;
        return category >= 130 && category < 150 ? category : 0;
    }

    // ---- the WZ ----------------------------------------------------------------------------------

    private static final Catalog WZ = new WzCatalog();

    public static Catalog wz() {
        return WZ;
    }

    /** The server's own item data; scroll lists are built once per equipment id. */
    private static final class WzCatalog implements Catalog {
        private final Map<Integer, List<Scroll>> byEquip = new ConcurrentHashMap<>();
        private volatile List<Scroll> scrolls;

        @Override
        public Map<String, Integer> equipStats(int itemId) {
            return ItemInformationProvider.getInstance().getEquipStats(itemId);
        }

        @Override
        public List<Scroll> scrollsFor(int itemId) {
            return byEquip.computeIfAbsent(itemId, id -> {
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                List<Scroll> out = new ArrayList<>();
                for (Scroll scroll : allScrolls()) {
                    if (ii.canApplyScroll(scroll.id(), id)) {
                        out.add(scroll);
                    }
                }
                return List.copyOf(out);
            });
        }

        private List<Scroll> allScrolls() {
            List<Scroll> all = scrolls;
            if (all != null) {
                return all;
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            List<Scroll> out = new ArrayList<>();
            for (Pair<Integer, String> item : ii.getAllItems()) {
                int id = item.getLeft();
                if (id / 10000 != 204 || ItemConstants.isChaosScroll(id) || ItemConstants.isCleanSlate(id)
                        || ItemConstants.isModifierScroll(id)) {
                    continue;
                }
                Map<String, Integer> info = ii.getEquipStats(id);
                if (info == null || info.getOrDefault("cursed", 0) > 0) {
                    continue;
                }
                int success = info.getOrDefault("success", 0);
                if (success != 10 && success != 60 && success != 100) {
                    continue;
                }
                Map<String, Integer> stats = new LinkedHashMap<>();
                for (String stat : STATS) {
                    int amount = info.getOrDefault(stat, 0);
                    if (amount > 0) {
                        stats.put(stat, amount);
                    }
                }
                if (!stats.isEmpty()) {
                    out.add(new Scroll(id, success, Map.copyOf(stats)));
                }
            }
            scrolls = List.copyOf(out);
            return scrolls;
        }
    }
}

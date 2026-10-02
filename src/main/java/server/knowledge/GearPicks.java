package server.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The equip a character of a job and level would wear from this server's gear (docs/SERVER_KNOWLEDGE_PLAN.md 8.4): named,
 * non-cash and obtainable ({@link WorldKnowledge#obtainable}: sold, a global drop, or dropped outside a quest by a monster
 * of an open map), so boss drops count and event, PQ, script and quest gear does not.
 */
public final class GearPicks {
    /** Candidates need reqLevel in (band - WINDOW, band]. */
    private static final int WINDOW = 20;
    /** The picks within this many levels of a tier's level are equally good; the seed chooses among them. */
    private static final int GROUP = 5;
    /** The lower tier sits this many levels under the best, and this share of seeds wears it: a crowd is not one kit. */
    private static final int LOWER_TIER = 10;
    private static final int LOWER_TIER_PERCENT = 45;
    private static final int PERCENT = 100;
    private static final int WEAPON_TYPE = 10_000;
    private static final int MALE = 0;
    private static final int FEMALE = 1;
    /** String.wz's placeholder for an item it has no name for (91 equips). */
    private static final String NO_NAME = "MISSING NAME";

    private GearPicks() {
    }

    /**
     * Best pick for {@code category} ({@link ItemFact#category}: cap, coat, longcoat, pants, shoes, glove, cape, shield,
     * weapon) at {@code bandLevel}: reqJob 0 or sharing a bit of {@code jobMask} (job-specific first), the gender by the id
     * ({@code id / 1000 % 10}: 0 male, 1 female, else both), main-stat requirements within {@code stats} (str, dex, int,
     * luk) and no fame requirement, a weapon only of {@code weaponType} ({@code id / 10000}); one stable for {@code seed}
     * among the highest reqLevels, or for some seeds among those about ten levels under them. 0 when none fits.
     */
    public static int pick(WorldKnowledge k, String category, int bandLevel, int jobMask, int gender, int weaponType,
                           int[] stats, long seed) {
        List<ItemFact> fitting = new ArrayList<>();
        for (ItemFact item : k.items()) {
            if (fits(item, category, bandLevel, jobMask, gender, weaponType, stats) && k.obtainable(item.id())) {
                fitting.add(item);
            }
        }
        if (fitting.isEmpty()) {
            return 0;
        }
        Comparator<ItemFact> best = Comparator.comparing((ItemFact item) -> item.reqJob() == 0) // job-specific first
                .thenComparing(Comparator.comparingInt(ItemFact::reqLevel).reversed());
        fitting.sort(best.thenComparingInt(ItemFact::id));
        ItemFact top = fitting.get(0);
        boolean lower = new SplittableRandom(seed).nextInt(PERCENT) < LOWER_TIER_PERCENT;
        List<ItemFact> group = lower ? tier(fitting, top, top.reqLevel() - LOWER_TIER) : List.of();
        if (group.isEmpty()) {
            group = tier(fitting, top, top.reqLevel());
        }
        return group.get((int) Math.floorMod(seed, (long) group.size())).id();
    }

    /** The fitting picks as job-specific as {@code top} with reqLevel in [level - GROUP, level]. */
    private static List<ItemFact> tier(List<ItemFact> fitting, ItemFact top, int level) {
        List<ItemFact> group = new ArrayList<>();
        for (ItemFact item : fitting) {
            if ((item.reqJob() == 0) == (top.reqJob() == 0) && item.reqLevel() >= level - GROUP && item.reqLevel() <= level) {
                group.add(item);
            }
        }
        return group;
    }

    private static boolean fits(ItemFact item, String category, int bandLevel, int jobMask, int gender, int weaponType,
                                int[] stats) {
        if (!item.equip() || !category.equals(item.category()) || item.cash() || item.unnamed() || item.name().isEmpty()
                || NO_NAME.equals(item.name())) {
            return false;
        }
        if (item.reqLevel() <= bandLevel - WINDOW || item.reqLevel() > bandLevel) {
            return false;
        }
        if (item.reqJob() != 0 && (item.reqJob() & jobMask) == 0) {
            return false;
        }
        int itemGender = item.id() / 1000 % 10;
        if ((itemGender == MALE || itemGender == FEMALE) && itemGender != gender) {
            return false;
        }
        if (item.reqStr() > stats[0] || item.reqDex() > stats[1] || item.reqInt() > stats[2] || item.reqLuk() > stats[3]
                || item.reqPop() > 0) {
            return false;
        }
        return !"weapon".equals(category) || item.id() / WEAPON_TYPE == weaponType;
    }
}

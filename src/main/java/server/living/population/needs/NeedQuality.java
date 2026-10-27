package server.living.population.needs;

import client.inventory.Equip;
import client.inventory.Item;
import server.ItemInformationProvider;

import java.util.Map;
import java.util.TreeMap;

/**
 * "Base or better" for an equip a request takes (docs/BOT_CONCEPT_PLAN.md F1): no less weapon or magic attack than the
 * item's base, and a whole that weighs at least as much - attack four times a main stat, defense, HP and MP a tenth. A
 * player's random-stat drop slightly under base in one stat still counts when it makes up for it elsewhere. Stats are
 * maps with the shop listings' keys (CosmicInventorySnapshotReader), so a player's shop listing is judged the same way.
 */
public final class NeedQuality {
    private NeedQuality() {
    }

    /** Whether {@code offered} (an equip's stats) is at least the base of {@code itemId}; true when it has no base. */
    public static boolean baseOrBetter(int itemId, Map<String, Object> offered) {
        Item base = ItemInformationProvider.getInstance().getEquipById(itemId);
        return base == null || baseOrBetter(offered, stats(base));
    }

    public static boolean baseOrBetter(Map<String, Object> offered, Map<String, Object> base) {
        return stat(offered, "weaponAttack") >= stat(base, "weaponAttack")
                && stat(offered, "magicAttack") >= stat(base, "magicAttack")
                && weight(offered) >= weight(base);
    }

    /** An equip's stats as the shop listings carry them (CosmicInventorySnapshotReader's keys); empty for any other item. */
    public static Map<String, Object> stats(Item item) {
        Map<String, Object> out = new TreeMap<>();
        if (item instanceof Equip equip) {
            out.put("str", equip.getStr());
            out.put("dex", equip.getDex());
            out.put("int", equip.getInt());
            out.put("luk", equip.getLuk());
            out.put("hp", equip.getHp());
            out.put("mp", equip.getMp());
            out.put("weaponAttack", equip.getWatk());
            out.put("magicAttack", equip.getMatk());
            out.put("weaponDefense", equip.getWdef());
            out.put("magicDefense", equip.getMdef());
            out.put("accuracy", equip.getAcc());
            out.put("avoidability", equip.getAvoid());
            out.put("speed", equip.getSpeed());
            out.put("jump", equip.getJump());
        }
        return out;
    }

    static int weight(Map<String, Object> stats) {
        return stat(stats, "str") + stat(stats, "dex") + stat(stats, "int") + stat(stats, "luk")
                + 4 * (stat(stats, "weaponAttack") + stat(stats, "magicAttack"))
                + (stat(stats, "weaponDefense") + stat(stats, "magicDefense") + stat(stats, "hp") + stat(stats, "mp")) / 10
                + stat(stats, "accuracy") + stat(stats, "avoidability") + stat(stats, "speed") + stat(stats, "jump");
    }

    private static int stat(Map<String, Object> stats, String key) {
        Object value = stats.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
}

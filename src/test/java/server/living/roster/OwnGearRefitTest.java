package server.living.roster;

import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.knowledge.ItemFact;
import server.living.roster.OwnGearRefit.Move;
import server.living.store.LivingStore.WearStats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one rule both release paths re-dress a companion by once its !bossgear kit came off (OwnGearRefit): each slot a
 * boss piece left empty takes the best piece for it the companion carries and can wear, and nothing is ever made. Facts
 * are the server knowledge rows of these items.
 */
class OwnGearRefitTest {
    private static final int SWORD = 1302000;
    private static final int GLADIUS = 1302008;
    private static final int LIONHEART = 1402006;
    private static final int STEEL_SHIELD = 1092000;
    private static final int MYSTIC_SHIELD = 1092021;
    private static final int UNDERSHIRT = 1040002;
    private static final int JEAN_SHORTS = 1060002;
    private static final int BOSS_SHIELD = 1092017;
    private static final int BOSS_SWORD = 1302059;
    private static final int BOSS_TWO_HANDED = 1402011;
    private static final int BOSS_OVERALL = 1050000;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    /** Garrick: a Lv70 Fighter, male, STR 250. */
    private static final WearStats GARRICK = new WearStats(110, 70, 0, 250, 20, 4, 4, 0);
    private static final Map<Integer, ItemFact> FACTS = new HashMap<>();

    static {
        fact(SWORD, "Sword", "weapon", 0, 0, 0, 0);
        fact(GLADIUS, "Gladius", "weapon", 30, 1, 95, 0);
        fact(LIONHEART, "Lionheart", "weapon", 35, 1, 115, 0);
        fact(STEEL_SHIELD, "Steel Shield", "shield", 15, 1, 40, 0);
        fact(MYSTIC_SHIELD, "Mystic Shield", "shield", 22, 2, 0, 68);
        fact(UNDERSHIRT, "White Undershirt", "coat", 0, 0, 0, 0);
        fact(JEAN_SHORTS, "Blue Jean Shorts", "pants", 0, 0, 0, 0);
    }

    private static void fact(int id, String name, String category, int level, int job, int str, int int_) {
        FACTS.put(id, new ItemFact(id, name, category, level, job, str, 0, int_, 0, 0, 1, false, false, 0));
    }

    private static Item at(int itemId, int position) {
        return Equip.restored(itemId, (short) position);
    }

    private static Map<Short, Item> wears(Item... items) {
        Map<Short, Item> worn = new HashMap<>();
        for (Item item : items) {
            worn.put(item.getPosition(), item);
        }
        return worn;
    }

    private static List<Move> plan(WearStats wearer, Map<Short, Integer> taken, Map<Short, Item> worn,
                                   List<Item> carried) {
        return OwnGearRefit.plan(wearer, taken, worn, carried, FACTS::get);
    }

    private static List<String> moves(List<Move> moves) {
        return moves.stream().map(move -> move.piece().getItemId() + "@" + move.piece().getPosition() + " -> "
                + move.slot()).toList();
    }

    @Test
    void theShieldTheKitSwappedIntoTheBagGoesBackOnBesideAOneHandedWeapon() {
        Item steel = at(STEEL_SHIELD, 2);

        List<Move> moves = plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD), wears(at(GLADIUS, WEAPON)),
                List.of(at(SWORD, 1), steel));

        assertEquals(List.of(STEEL_SHIELD + "@2 -> -10"), moves(moves));
        assertEquals(steel, moves.get(0).piece(), "the carried piece itself moves");
    }

    @Test
    void besideATwoHandedWeaponTheShieldStaysInTheBag() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_TWO_HANDED), wears(),
                List.of(at(STEEL_SHIELD, 1), at(LIONHEART, 2)));

        assertEquals(List.of(LIONHEART + "@2 -> -11"), moves(moves),
                "its own two-handed sword goes back on; the shield the boss weapon may have pushed off stays");
    }

    @Test
    void withNoCarriedShieldTheSlotStaysEmpty() {
        List<Move> moves = plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD), wears(at(GLADIUS, WEAPON)),
                List.of(at(SWORD, 1), at(UNDERSHIRT, 2)));

        assertEquals(List.of(), moves(moves), "nothing is made, and no piece of another slot goes there");
    }

    @Test
    void aShieldItsJobOrLevelCannotWearStaysInTheBag() {
        WearStats rookie = new WearStats(100, 12, 0, 60, 4, 4, 4, 0);

        assertEquals(List.of(), moves(plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD), wears(at(GLADIUS, WEAPON)),
                List.of(at(MYSTIC_SHIELD, 1)))), "a magician's shield on a fighter");
        assertEquals(List.of(), moves(plan(rookie, Map.of(SHIELD, BOSS_SHIELD), wears(at(SWORD, WEAPON)),
                List.of(at(STEEL_SHIELD, 1)))), "a Lv15 shield on a Lv12 warrior");
    }

    @Test
    void theWeaponGoesOnFirstThenTheShieldATwoHandedBossWeaponPushedOff() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_TWO_HANDED), wears(),
                List.of(at(STEEL_SHIELD, 1), at(GLADIUS, 2)));

        assertEquals(List.of(GLADIUS + "@2 -> -11", STEEL_SHIELD + "@1 -> -10"), moves(moves));
    }

    @Test
    void theWeaponOfTheBossWeaponsTypeWinsOverAHigherOne() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_SWORD), wears(),
                List.of(at(LIONHEART, 1), at(SWORD, 2), at(GLADIUS, 3)));

        assertEquals(List.of(GLADIUS + "@3 -> -11"), moves(moves),
                "a one-handed sword like the boss sword (the kit kept its type), the highest of them");
    }

    @Test
    void besideAWornShieldOnlyAOneHandedWeaponGoesOn() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_TWO_HANDED), wears(at(STEEL_SHIELD, SHIELD)),
                List.of(at(LIONHEART, 1), at(GLADIUS, 2)));

        assertEquals(List.of(GLADIUS + "@2 -> -11"), moves(moves), "the two-handed one would push its shield off");
    }

    @Test
    void theCoatAndThePantsABossOverallPushedOffGoBackOn() {
        List<Move> moves = plan(GARRICK, Map.of(TOP, BOSS_OVERALL), wears(),
                List.of(at(JEAN_SHORTS, 1), at(UNDERSHIRT, 2)));

        assertEquals(List.of(UNDERSHIRT + "@2 -> -5", JEAN_SHORTS + "@1 -> -6"), moves(moves));
    }

    @Test
    void aSlotItStillWearsAndAnUnknownWearerOrPieceNeverMove() {
        assertEquals(List.of(), moves(plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD),
                wears(at(GLADIUS, WEAPON), at(STEEL_SHIELD, SHIELD)), List.of(at(STEEL_SHIELD, 1)))), "already worn");
        assertEquals(List.of(), moves(plan(null, Map.of(SHIELD, BOSS_SHIELD), wears(at(GLADIUS, WEAPON)),
                List.of(at(STEEL_SHIELD, 1)))), "no wearer read");
        assertEquals(List.of(), moves(OwnGearRefit.plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD),
                wears(at(GLADIUS, WEAPON)), List.of(at(STEEL_SHIELD, 1)), id -> null)), "no knowledge");
    }
}

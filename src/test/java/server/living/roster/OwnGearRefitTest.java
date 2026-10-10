package server.living.roster;

import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import server.knowledge.ItemFact;
import server.living.roster.BossGearRecord.Displaced;
import server.living.roster.OwnGearRefit.Move;
import server.living.store.LivingStore.WearStats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one rule both release paths re-dress a companion by once its !bossgear kit came off (OwnGearRefit): each slot a
 * boss piece left empty takes the best piece for it the companion carries and can wear, and nothing is ever made. Facts
 * are the server knowledge rows of these items.
 */
class OwnGearRefitTest {
    private static final int SWORD = 1302000;
    private static final int GLADIUS = 1302008;
    /** A Lv40 one-handed axe: a warrior piece, so the job mask lets a sword Fighter wear it. */
    private static final int BLUE_COUNTER = 1312007;
    private static final int LIONHEART = 1402006;
    private static final int STEEL_SHIELD = 1092000;
    private static final int MYSTIC_SHIELD = 1092021;
    private static final int UNDERSHIRT = 1040002;
    private static final int JEAN_SHORTS = 1060002;
    private static final int BOSS_SHIELD = 1092017;
    private static final int BOSS_SWORD = 1302059;
    private static final int BOSS_TWO_HANDED = 1402011;
    private static final int BOSS_OVERALL = 1050000;
    /** Tamsin's own Red Shark (a female coat) and the female overall her !bossgear kit put on (live, 2026-10-09). */
    private static final int RED_SHARK = 1041024;
    private static final int BLUE_ENGRIT = 1051012;
    /** A female pair of pants (the gender digit 1). */
    private static final int RED_MINISKIRT = 1061020;
    /** Tamsin's caps: the Steel Sharp Helm she wore before the kit, and two she carried (live, 2026-10-09). */
    private static final int STEEL_SHARP_HELM = 1002027;
    private static final int JOUSTING_HELMET = 1002023;
    private static final int RED_DUKE = 1002025;
    private static final int BOSS_CAP = 1002055;
    private static final short CAP = -1;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    /** Garrick: a Lv70 Fighter, male, STR 250. */
    private static final WearStats GARRICK = new WearStats(110, 70, 0, 250, 20, 4, 4, 0);
    /** Tamsin: a Lv38 Page, female, STR 150; made with the male beginner set (BotCreator), so she wore 1060002. */
    private static final WearStats TAMSIN = new WearStats(120, 38, 1, 150, 40, 4, 4, 0);
    private static final Map<Integer, ItemFact> FACTS = new HashMap<>();

    static {
        fact(SWORD, "Sword", "weapon", 0, 0, 0, 0);
        fact(GLADIUS, "Gladius", "weapon", 30, 1, 95, 0);
        fact(BLUE_COUNTER, "Blue Counter", "weapon", 40, 1, 125, 0);
        fact(LIONHEART, "Lionheart", "weapon", 35, 1, 115, 0);
        fact(STEEL_SHIELD, "Steel Shield", "shield", 15, 1, 40, 0);
        fact(MYSTIC_SHIELD, "Mystic Shield", "shield", 22, 2, 0, 68);
        fact(UNDERSHIRT, "White Undershirt", "coat", 0, 0, 0, 0);
        fact(JEAN_SHORTS, "Blue Jean Shorts", "pants", 0, 0, 0, 0);
        fact(RED_SHARK, "Red Shark", "coat", 25, 1, 80, 0);
        fact(RED_MINISKIRT, "Red Miniskirt", "pants", 20, 0, 0, 0);
        fact(STEEL_SHARP_HELM, "Steel Sharp Helm", "cap", 22, 1, 45, 0);
        fact(JOUSTING_HELMET, "Jousting Helmet", "cap", 30, 1, 65, 0);
        fact(RED_DUKE, "Red Duke", "cap", 40, 1, 90, 0);
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
        return plan(wearer, taken, Map.of(), worn, carried);
    }

    /** With the record of what the kit displaced (BossGearRecord): slot -> item id. */
    private static List<Move> plan(WearStats wearer, Map<Short, Integer> taken, Map<Short, Integer> displaced,
                                   Map<Short, Item> worn, List<Item> carried) {
        return OwnGearRefit.plan(wearer, taken, byId(displaced), worn, carried, FACTS::get, type -> true);
    }

    /** For a build that fights only with the weapon types {@code fights} names (AgentWeaponCompatibilityPolicy). */
    private static List<Move> plan(WearStats wearer, Map<Short, Integer> taken, Map<Short, Integer> displaced,
                                   List<Item> carried, Predicate<WeaponType> fights) {
        return OwnGearRefit.plan(wearer, taken, byId(displaced), wears(), carried, FACTS::get, fights);
    }

    /** A record that knows each piece by its id alone (as one written before the bag slot and print were kept). */
    private static Map<Short, Displaced> byId(Map<Short, Integer> displaced) {
        Map<Short, Displaced> out = new HashMap<>();
        displaced.forEach((slot, itemId) -> out.put(slot, Displaced.of(itemId)));
        return out;
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
    void tamsinsBlueJeanShortsTheBossOverallPushedOffGoBackOnThoughTheyAreAMansPair() {
        List<Move> moves = plan(TAMSIN, Map.of(TOP, BLUE_ENGRIT), wears(),
                List.of(at(UNDERSHIRT, 2), at(RED_SHARK, 9), at(JEAN_SHORTS, 10)));

        assertEquals(List.of(RED_SHARK + "@9 -> -5", JEAN_SHORTS + "@10 -> -6"), moves(moves),
                "she wore them under her coat before the kit; the game never holds an equip to the gender in its id");
    }

    @Test
    void itsOwnGendersPieceWinsOverAHigherOneOfTheOtherGender() {
        List<Move> moves = plan(GARRICK, Map.of(TOP, BOSS_OVERALL), wears(),
                List.of(at(RED_MINISKIRT, 1), at(JEAN_SHORTS, 2)));

        assertEquals(List.of(JEAN_SHORTS + "@2 -> -6"), moves(moves), "a man's own shorts over a Lv20 miniskirt");
    }

    @Test
    void aSlotItStillWearsAndAnUnknownWearerOrPieceNeverMove() {
        assertEquals(List.of(), moves(plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD),
                wears(at(GLADIUS, WEAPON), at(STEEL_SHIELD, SHIELD)), List.of(at(STEEL_SHIELD, 1)))), "already worn");
        assertEquals(List.of(), moves(plan(null, Map.of(SHIELD, BOSS_SHIELD), wears(at(GLADIUS, WEAPON)),
                List.of(at(STEEL_SHIELD, 1)))), "no wearer read");
        assertEquals(List.of(), moves(OwnGearRefit.plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD),
                Map.of(SHIELD, Displaced.of(STEEL_SHIELD)), wears(at(GLADIUS, WEAPON)), List.of(at(STEEL_SHIELD, 1)),
                id -> null,
                type -> true)), "no knowledge");
    }

    // ---- first what the kit displaced (BossGearRecord) ----------------------------------------------

    @Test
    void theCapTheKitDisplacedGoesBackOnOverAHigherOneItCarries() {
        Item helm = at(STEEL_SHARP_HELM, 8);

        List<Move> moves = plan(TAMSIN, Map.of(CAP, BOSS_CAP), Map.of(CAP, STEEL_SHARP_HELM), wears(),
                List.of(at(JOUSTING_HELMET, 1), at(RED_DUKE, 3), helm));

        assertEquals(List.of(STEEL_SHARP_HELM + "@8 -> -1"), moves(moves),
                "the cap she wore before the kit, not the Lv30 Jousting Helmet she also carries");
        assertEquals(helm, moves.get(0).piece());
    }

    @Test
    void whenTheDisplacedPieceIsGoneTheBestOneItCarriesGoesOn() {
        List<Move> moves = plan(TAMSIN, Map.of(CAP, BOSS_CAP), Map.of(CAP, STEEL_SHARP_HELM), wears(),
                List.of(at(JOUSTING_HELMET, 1), at(RED_DUKE, 3)));

        assertEquals(List.of(JOUSTING_HELMET + "@1 -> -1"), moves(moves),
                "sold or dropped: the highest cap she can wear (the Lv40 Red Duke is over her level)");
    }

    @Test
    void aDisplacedPieceItCannotWearNowGivesWayToTheBestOne() {
        List<Move> moves = plan(GARRICK, Map.of(SHIELD, BOSS_SHIELD), Map.of(SHIELD, MYSTIC_SHIELD),
                wears(at(GLADIUS, WEAPON)), List.of(at(MYSTIC_SHIELD, 1), at(STEEL_SHIELD, 2)));

        assertEquals(List.of(STEEL_SHIELD + "@2 -> -10"), moves(moves), "a magician's shield stays in the bag");
    }

    @Test
    void theRecordNeverDressesASlotTheStripDidNotEmpty() {
        Map<Short, Integer> displaced = Map.of(CAP, STEEL_SHARP_HELM, SHIELD, STEEL_SHIELD);

        List<Move> moves = plan(TAMSIN, Map.of(SHIELD, BOSS_SHIELD), displaced,
                wears(at(GLADIUS, WEAPON), at(JOUSTING_HELMET, CAP)), List.of(at(STEEL_SHARP_HELM, 1),
                        at(STEEL_SHIELD, 2)));

        assertEquals(List.of(STEEL_SHIELD + "@2 -> -10"), moves(moves), "the cap she wears now stays on");
    }

    // ---- a weapon its build fights with (AgentWeaponCompatibilityPolicy) ------------------------------

    /** Garrick's build: sword skills, so the Agent combat holds him to swords. */
    private static final Predicate<WeaponType> SWORDS = type -> type == WeaponType.SWORD1H
            || type == WeaponType.SWORD2H;

    @Test
    void aSwordFightersOneHandedSwordWinsOverAHigherAxeItsJobMaskAlsoAllows() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_TWO_HANDED), Map.of(),
                List.of(at(GLADIUS, 1), at(BLUE_COUNTER, 2)), SWORDS);

        assertEquals(List.of(GLADIUS + "@1 -> -11"), moves(moves),
                "its own two-handed sword was sold: the one-handed sword, not the Lv40 axe its warrior mask allows");
    }

    @Test
    void aDisplacedWeaponItsBuildCannotFightWithGivesWayToOneItCan() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_SWORD), Map.of(WEAPON, BLUE_COUNTER),
                List.of(at(BLUE_COUNTER, 1), at(GLADIUS, 2)), SWORDS);

        assertEquals(List.of(GLADIUS + "@2 -> -11"), moves(moves), "the kit gave a sword for the axe it could not use");
    }

    @Test
    void withNoWeaponItsBuildFightsWithOneItsJobMaskAllowsStillGoesOn() {
        List<Move> moves = plan(GARRICK, Map.of(WEAPON, BOSS_SWORD), Map.of(), List.of(at(BLUE_COUNTER, 1)), SWORDS);

        assertEquals(List.of(BLUE_COUNTER + "@1 -> -11"), moves(moves), "the optimizer has no sword for it either");
    }

    @Test
    void theWeaponTypeIsTheIdsCategoryAsTheGameReadsIt() {
        assertEquals(WeaponType.SWORD1H, OwnGearRefit.weaponType(GLADIUS));
        assertEquals(WeaponType.GENERAL1H_SWING, OwnGearRefit.weaponType(BLUE_COUNTER));
        assertEquals(WeaponType.SWORD2H, OwnGearRefit.weaponType(LIONHEART));
        assertEquals(WeaponType.BOW, OwnGearRefit.weaponType(1452002));
        assertEquals(WeaponType.CROSSBOW, OwnGearRefit.weaponType(1462001));
        assertEquals(WeaponType.GUN, OwnGearRefit.weaponType(1492013));
        assertEquals(WeaponType.NOT_A_WEAPON, OwnGearRefit.weaponType(STEEL_SHARP_HELM));
    }

    // ---- the very copy the kit displaced, of two carried with its id (BossGearRecord.Displaced) ----------

    /** Tamsin's own Steel Sharp Helm: three scrolls passed on it. */
    private static Item scrolledHelm(int position) {
        Equip helm = Equip.restored(STEEL_SHARP_HELM, (short) position);
        helm.setWdef((short) 33);
        helm.setLevel((byte) 3);
        helm.setUpgradeSlots((byte) 4);
        return helm;
    }

    private static List<Move> plan(Map<Short, Displaced> displaced, List<Item> carried) {
        return OwnGearRefit.plan(TAMSIN, Map.of(CAP, BOSS_CAP), displaced, wears(), carried, FACTS::get, type -> true);
    }

    @Test
    void ofTwoCarriedCopiesTheVeryHelmTheKitPutInHerBagGoesBackOn() {
        Item own = scrolledHelm(12); // the kit's cap went to the first free slot, 12, and her helm swapped in there

        List<Move> moves = plan(Map.of(CAP, Displaced.of(own)), List.of(at(STEEL_SHARP_HELM, 3), own));

        assertEquals(own, moves.get(0).piece(), "her scrolled helm, not the plain copy she looted into slot 3");
    }

    @Test
    void theVeryHelmIsKnownByItsStatsWhereItLeftTheBagSlotTheKitPutItIn() {
        Item own = scrolledHelm(12);
        Displaced record = Displaced.of(own);
        own.setPosition((short) 7);

        List<Move> moves = plan(Map.of(CAP, record), List.of(at(STEEL_SHARP_HELM, 3), own, at(STEEL_SHARP_HELM, 12)));

        assertEquals(own, moves.get(0).piece(), "its scrolls tell it from the plain copies, one now in slot 12");
    }

    @Test
    void ofTwoCopiesAlikeTheOneInTheBagSlotTheKitPutItInGoesBackOn() {
        Item own = at(STEEL_SHARP_HELM, 12);

        List<Move> moves = plan(Map.of(CAP, Displaced.of(own)), List.of(at(STEEL_SHARP_HELM, 3), own));

        assertEquals(own, moves.get(0).piece());
    }

    @Test
    void aRecordThatKnowsOnlyTheIdPutsTheFirstCopyOn() {
        Item first = at(STEEL_SHARP_HELM, 3);

        List<Move> moves = plan(Map.of(CAP, Displaced.of(STEEL_SHARP_HELM)), List.of(first, scrolledHelm(12)));

        assertEquals(first, moves.get(0).piece(), "a row written before the bag slot and print were kept");
    }
}

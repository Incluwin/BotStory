package server.living.quest;

import client.inventory.Equip;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.knowledge.ItemFact;
import server.knowledge.KnowledgeTestKit;
import server.knowledge.WorldKnowledge;
import server.living.quest.BossGearPlanner.Piece;
import server.living.quest.BossGearPlanner.Wearer;
import server.living.quest.FriendForge.Catalog;
import server.living.quest.FriendForge.Scroll;
import server.living.quest.FriendForge.Use;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code !bossgear} kit (docs/COMPANION_BOSSING_PLAN.md G13) over the seed knowledge snapshot and a fixture forge
 * catalog: one piece per GearPicks slot at the highest band that has one, every slot a passed 10% scroll for the job,
 * untradeable and tagged. The forge's input (the plan) is checked, not a forged item: ItemInformationProvider, which
 * FriendForge.forge and every new Equip read, cannot start without its database in a unit test.
 */
class BossGearPlannerTest {
    private static final int HERO = 112;
    private static final int CRUSADER = 111;
    private static final int OUTLAW = 521;
    private static final int MALE = 0;
    private static final int[] STRONG = {999, 999, 999, 999};
    private static final int GLADIUS = 1302008;        // a one-handed sword
    private static final int STEEL_SHIELD = 1092000;
    private static final int PISTOL = 1492000;
    private static final short CAP = -1;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short SHOES = -7;
    private static final short GLOVE = -8;
    private static final short CAPE = -9;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;

    private static final Scroll ALWAYS = new Scroll(2040900, 100, Map.of("PAD", 1));
    private static final Scroll WARRIOR_60 = new Scroll(2040901, 60, Map.of("PAD", 2, "STR", 1));
    private static final Scroll MAGE_60 = new Scroll(2040902, 60, Map.of("MAD", 2, "INT", 1));
    private static final Scroll WARRIOR_10 = new Scroll(2040903, 10, Map.of("PAD", 5, "STR", 3));
    private static final int SLOTS = 7;
    /** Every piece has SLOTS upgrade slots (its WZ tuc in the server) and takes these scrolls. */
    static final Catalog CATALOG = new Catalog() {
        @Override
        public Map<String, Integer> equipStats(int itemId) {
            return Map.of("tuc", SLOTS);
        }

        @Override
        public List<Scroll> scrollsFor(int itemId) {
            return List.of(ALWAYS, WARRIOR_60, MAGE_60, WARRIOR_10);
        }
    };

    private AutoCloseable seed;
    private WorldKnowledge k;

    @BeforeEach
    void publish() {
        seed = KnowledgeTestKit.publishSeed();
        k = WorldKnowledge.current();
    }

    @AfterEach
    void unpublish() throws Exception {
        seed.close();
    }

    private static Wearer wearer(int jobId, int level, Map<Short, Integer> worn) {
        return new Wearer(jobId, level, MALE, STRONG, worn, type -> type == 130 || type == 140, 4242L);
    }

    private static Set<Short> positions(List<Piece> kit) {
        return kit.stream().map(Piece::position).collect(Collectors.toCollection(TreeSet::new));
    }

    private Piece at(List<Piece> kit, short position) {
        return kit.stream().filter(piece -> piece.position() == position).findFirst().orElseThrow();
    }

    private int reqLevel(int itemId) {
        return k.item(itemId).map(ItemFact::reqLevel).orElse(-1);
    }

    @Test
    void aLevel160HeroGetsOnePiecePerSlotAtTheHighestBandThatHasOneAndKeepsTheSwordType() {
        List<Piece> kit = BossGearPlanner.plan(k, CATALOG, wearer(HERO, 160, Map.of(WEAPON, GLADIUS)));

        assertEquals(new TreeSet<>(Set.of(CAP, TOP, PANTS, SHOES, GLOVE, CAPE, WEAPON)), positions(kit),
                "one piece per slot, pants under the coat, no shield as none is worn: " + kit);
        assertEquals("coat", at(kit, TOP).category(),
                "the level 110-120 warrior overalls are not obtainable here, so the level 100 coat wins");
        assertEquals(130, at(kit, WEAPON).itemId() / 10_000, "the worn one-handed sword keeps its type");
        for (Piece piece : kit) {
            int level = reqLevel(piece.itemId());
            assertTrue(level >= 80 && level <= 160, piece + " is level " + level + ": v83 gear stops near 120");
            assertTrue(k.obtainable(piece.itemId()), piece.toString());
        }
        assertEquals(120, reqLevel(at(kit, WEAPON).itemId()), "no band from 160 down to 130 has a sword");
    }

    @Test
    void anOverallMeansNoPants() {
        List<Piece> kit = BossGearPlanner.plan(k, CATALOG,
                new Wearer(OUTLAW, 100, MALE, STRONG, Map.of(WEAPON, PISTOL), type -> type == 149, 4242L));

        assertEquals("longcoat", at(kit, TOP).category(), "pirates have overalls, their coats stop at level 11: " + kit);
        assertFalse(positions(kit).contains(PANTS), kit.toString());
        assertEquals(149, at(kit, WEAPON).itemId() / 10_000);
    }

    @Test
    void aWornShieldGetsOneBesideAKeptWeapon() {
        List<Piece> kit = BossGearPlanner.plan(k, CATALOG,
                wearer(CRUSADER, 100, Map.of(WEAPON, GLADIUS, SHIELD, STEEL_SHIELD)));

        assertEquals("shield", at(kit, SHIELD).category(), kit.toString());
        assertEquals(130, at(kit, WEAPON).itemId() / 10_000);
        assertEquals(WEAPON, kit.get(kit.size() - 2).position(), "the weapon goes on before the shield");
    }

    @Test
    void aNewWeaponTypeLeavesTheShieldOut() {
        List<Piece> kit = BossGearPlanner.plan(k, CATALOG,
                new Wearer(HERO, 160, MALE, STRONG, Map.of(WEAPON, GLADIUS, SHIELD, STEEL_SHIELD), type -> type == 140,
                        4242L));

        assertEquals(140, at(kit, WEAPON).itemId() / 10_000, "a sword the job cannot use is replaced by one it can");
        assertFalse(positions(kit).contains(SHIELD), "a new type may be two-handed: " + kit);
    }

    @Test
    void everySlotHoldsAPassedTenPercentScrollForTheJob() {
        List<Piece> kit = BossGearPlanner.plan(k, CATALOG, wearer(HERO, 160, Map.of(WEAPON, GLADIUS)));
        assertFalse(kit.isEmpty());

        for (Piece piece : kit) {
            // FriendForge.forge: each passed scroll adds its stats and a level, each scroll spends a slot, so this is
            // a piece at level tuc with 0 slots left and the base stats plus tuc times the warrior's 10% scroll
            assertEquals(SLOTS, piece.plan().slots(), piece.toString());
            assertEquals(List.of(new Use(WARRIOR_10, SLOTS, SLOTS)), piece.plan().uses(), piece.toString());
        }
    }

    @Test
    void aPieceNoTenPercentScrollFitsTakesSixtyPercentScrolls() {
        Catalog noTen = new Catalog() {
            @Override
            public Map<String, Integer> equipStats(int itemId) {
                return Map.of("tuc", SLOTS);
            }

            @Override
            public List<Scroll> scrollsFor(int itemId) {
                return List.of(ALWAYS, WARRIOR_60, MAGE_60);
            }
        };
        for (Piece piece : BossGearPlanner.plan(k, noTen, wearer(HERO, 160, Map.of(WEAPON, GLADIUS)))) {
            assertEquals(List.of(new Use(WARRIOR_60, SLOTS, SLOTS)), piece.plan().uses(), piece.toString());
        }
    }

    @Test
    void piecesAreUntradeableAndTaggedBossgear() {
        Item piece = BossGearPlanner.tag(Equip.restored(GLADIUS, (short) 0));

        assertEquals(GLADIUS, piece.getItemId());
        assertEquals("bossgear", piece.getOwner());
        assertTrue((piece.getFlag() & ItemConstants.UNTRADEABLE) != 0);
    }

    @Test
    void noKnowledgeGivesNoKit() {
        assertEquals(List.of(), BossGearPlanner.plan(null, CATALOG, wearer(HERO, 160, Map.of(WEAPON, GLADIUS))));
    }

    // ---- an accuracy goal (user 2026-10-15: accuracy from equipment, as little damage lost as possible) ----------

    /** The warrior's accuracy scroll: less attack than WARRIOR_10, 5 accuracy and 3 DEX (7.4 accuracy). */
    private static final Scroll ACCURACY_10 = new Scroll(2040904, 10, Map.of("PAD", 3, "ACC", 5, "DEX", 3));
    /** Gloves only: nearly WARRIOR_10's damage with 5 accuracy, the cheapest accuracy per point lost. */
    private static final Scroll GLOVE_ACCURACY_10 = new Scroll(2040905, 10, Map.of("PAD", 5, "STR", 1, "ACC", 5));
    private static final double SWAP_GAIN = 7.4;
    private static final int GLOVE_CATEGORY = 108;
    private static final Set<Integer> ACCESSORY_CATEGORIES = Set.of(101, 102, 103, 112);
    private static final short EYE = -3;
    private static final short[] ACCESSORY_POSITIONS = {-2, -3, -4, -17};
    /** Every accessory adds 10 accuracy unscrolled; armour adds none; gloves also take the cheaper glove scroll. */
    private static final Catalog ACCURACY = new Catalog() {
        @Override
        public Map<String, Integer> equipStats(int itemId) {
            return ACCESSORY_CATEGORIES.contains(itemId / 10_000) ? Map.of("tuc", SLOTS, "ACC", 10)
                    : Map.of("tuc", SLOTS);
        }

        @Override
        public List<Scroll> scrollsFor(int itemId) {
            return itemId / 10_000 == GLOVE_CATEGORY ? List.of(WARRIOR_10, ACCURACY_10, GLOVE_ACCURACY_10)
                    : List.of(WARRIOR_10, ACCURACY_10);
        }
    };

    private static long accuracyUses(List<Piece> kit) {
        return kit.stream().flatMap(piece -> piece.plan().uses().stream())
                .filter(use -> use.scroll() != WARRIOR_10).mapToInt(Use::takes).sum();
    }

    private static boolean accessory(Piece piece) {
        for (short position : ACCESSORY_POSITIONS) {
            if (piece.position() == position) {
                return true;
            }
        }
        return false;
    }

    @Test
    void withoutAGoalTheKitIsThePlainOne() {
        Wearer hero = wearer(HERO, 160, Map.of(WEAPON, GLADIUS));

        assertEquals(BossGearPlanner.plan(k, ACCURACY, hero), BossGearPlanner.plan(k, ACCURACY, hero, null));
        assertEquals(BossGearPlanner.plan(k, ACCURACY, hero),
                BossGearPlanner.plan(k, ACCURACY, hero, new BossGearPlanner.Accuracy(0, 100, Map.of())));
    }

    @Test
    void aGoalTheDamageKitMeetsChangesNothing() {
        Wearer hero = wearer(HERO, 160, Map.of(WEAPON, GLADIUS));
        BossGearPlanner.Accuracy goal = new BossGearPlanner.Accuracy(150, 150, Map.of());

        List<Piece> kit = BossGearPlanner.plan(k, ACCURACY, hero, goal);

        assertEquals(BossGearPlanner.plan(k, ACCURACY, hero), kit, "accuracy 150 already meets 150");
        assertEquals(150, BossGearPlanner.accuracy(kit, ACCURACY, goal));
    }

    @Test
    void aShortKitAddsAccessoriesThenTurnsJustEnoughSlotsToAccuracy() {
        Wearer hero = wearer(HERO, 160, Map.of(WEAPON, GLADIUS));
        BossGearPlanner.Accuracy goal = new BossGearPlanner.Accuracy(200, 100, Map.of());

        List<Piece> kit = BossGearPlanner.plan(k, ACCURACY, hero, goal);
        int reached = BossGearPlanner.accuracy(kit, ACCURACY, goal);

        assertTrue(reached >= 200, "the goal is met: " + reached);
        assertTrue(kit.stream().anyMatch(BossGearPlannerTest::accessory), "empty accessory slots are filled first");
        long accessories = kit.stream().filter(BossGearPlannerTest::accessory).count();
        double fromScrolls = 200 - 100 - 10.0 * accessories;
        assertTrue(fromScrolls > 0, "this test needs a goal the accessories alone do not meet: " + kit);
        // the cheapest turn is the gloves' (5 accuracy each), then the rest at 7.4; one turn fewer falls short
        long turned = accuracyUses(kit);
        assertTrue(reached - SWAP_GAIN < 200, "no more slots turned than the goal needs: " + reached + " " + kit);
        assertTrue(turned > 0, kit.toString());
        Piece glove = at(kit, GLOVE);
        long gloveTurns = glove.plan().uses().stream().filter(use -> use.scroll() == GLOVE_ACCURACY_10)
                .mapToInt(Use::takes).sum();
        assertEquals(Math.min(SLOTS, turned), gloveTurns, "the gloves' cheaper scroll goes first: " + glove);
        for (Piece piece : kit) {
            assertEquals(piece.plan().slots(), piece.plan().uses().stream().mapToInt(Use::count).sum(),
                    "every slot still holds one scroll: " + piece);
        }
    }

    @Test
    void aWornAccessoryStaysOnAndCounts() {
        Wearer hero = wearer(HERO, 160, Map.of(WEAPON, GLADIUS, EYE, 1022060));
        BossGearPlanner.Accuracy goal = new BossGearPlanner.Accuracy(400, 100, Map.of(EYE, 12.0));

        List<Piece> kit = BossGearPlanner.plan(k, ACCURACY, hero, goal);

        assertFalse(positions(kit).contains(EYE), "the worn eye accessory is not replaced: " + kit);
        double scrolls = kit.stream().mapToDouble(piece -> BossGearPlanner.accuracyOf(piece.plan(), ACCURACY)).sum();
        assertEquals((int) Math.floor(100 + 12.0 + scrolls), BossGearPlanner.accuracy(kit, ACCURACY, goal));
    }

    @Test
    void aGoalTooHighTurnsEverySlotAndStaysShort() {
        Wearer hero = wearer(HERO, 160, Map.of(WEAPON, GLADIUS));
        BossGearPlanner.Accuracy goal = new BossGearPlanner.Accuracy(5_000, 100, Map.of());

        List<Piece> kit = BossGearPlanner.plan(k, ACCURACY, hero, goal);

        for (Piece piece : kit) {
            assertTrue(piece.plan().uses().stream().noneMatch(use -> use.scroll() == WARRIOR_10),
                    "every damage scroll gave way: " + piece);
        }
        assertTrue(BossGearPlanner.accuracy(kit, ACCURACY, goal) < 5_000);
    }

    @Test
    void aMagiciansKitIsUnchanged() {
        Wearer bishop = new Wearer(232, 160, MALE, STRONG, Map.of(), type -> type == 137 || type == 138, 4242L);

        assertEquals(BossGearPlanner.plan(k, ACCURACY, bishop),
                BossGearPlanner.plan(k, ACCURACY, bishop, new BossGearPlanner.Accuracy(5_000, 0, Map.of())));
    }
}

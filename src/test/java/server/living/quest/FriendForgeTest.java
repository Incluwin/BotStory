package server.living.quest;

import org.junit.jupiter.api.Test;
import server.living.config.Content.ScrollRecipe;
import server.living.quest.FriendForge.Catalog;
import server.living.quest.FriendForge.Plan;
import server.living.quest.FriendForge.Scroll;
import server.living.quest.FriendForge.Use;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The friendship forge's rules against the v83 scroll stats (Item.wz/Consume/0204.img). */
class FriendForgeTest {
    private static final int[] TAKES_60 = {0, 0, 0, 70, 80, 95, 100, 100, 100, 100};
    private static final int[] TAKES_10 = {0, 0, 0, 0, 0, 0, 45, 60, 75, 90};

    private static final int FIGHTER = 110;
    private static final int CLERIC = 230;
    private static final int ASSASSIN = 410;
    private static final int GUNSLINGER = 520;
    private static final int BEGINNER = 0;

    private static final int GLADIUS = 1302008;       // one-handed sword, 7 slots, warriors
    private static final int AXE = 1312004;           // one-handed axe, warriors
    private static final int WAND = 1372005;          // magicians
    private static final int CLAW = 1472008;          // thieves
    private static final int THIEF_GLOVES = 1082074;  // thieves, 5 slots
    private static final int MAGE_GLOVES = 1082080;   // magicians, 5 slots
    private static final int MALE_OVERALL = 1050039;  // anyone, men only
    private static final int FEMALE_OVERALL = 1051039;

    private final FakeCatalog catalog = new FakeCatalog();

    FriendForgeTest() {
        catalog.equip(GLADIUS, 1, 7);
        catalog.equip(AXE, 1, 7);
        catalog.equip(WAND, 2, 7);
        catalog.equip(CLAW, 8, 7);
        catalog.equip(THIEF_GLOVES, 8, 5);
        catalog.equip(MAGE_GLOVES, 2, 5);
        catalog.equip(MALE_OVERALL, 0, 10);
        catalog.equip(FEMALE_OVERALL, 0, 10);
        List<Scroll> sword = List.of(
                new Scroll(2043000, 100, Map.of("PAD", 1)),
                new Scroll(2043001, 60, Map.of("PAD", 2, "STR", 1)),
                new Scroll(2043002, 10, Map.of("PAD", 5, "STR", 3, "PDD", 1)),
                new Scroll(2043003, 100, Map.of("PAD", 5, "STR", 3, "PDD", 1)), // event: 10% stats at 100%
                new Scroll(2043023, 100, Map.of("PAD", 2, "STR", 2)),           // event: beats the 60%
                new Scroll(2043008, 10, Map.of("MAD", 2, "INT", 2, "MDD", 1)),
                new Scroll(2043019, 10, Map.of("ACC", 5, "DEX", 3, "PAD", 3)));
        catalog.scrolls(GLADIUS, sword);
        catalog.scrolls(WAND, List.of(
                new Scroll(2043700, 100, Map.of("MAD", 1)),
                new Scroll(2043701, 60, Map.of("MAD", 2, "INT", 1)),
                new Scroll(2043702, 10, Map.of("MAD", 5, "INT", 3, "MDD", 1))));
        List<Scroll> gloves = List.of(
                new Scroll(2040803, 100, Map.of("PAD", 1)),
                new Scroll(2040804, 60, Map.of("PAD", 2)),
                new Scroll(2040805, 10, Map.of("PAD", 3)),
                new Scroll(2040807, 100, Map.of("PAD", 3)), // event
                new Scroll(2040802, 10, Map.of("ACC", 5, "DEX", 3, "EVA", 1)),
                new Scroll(2040818, 100, Map.of("MAD", 1)),
                new Scroll(2040817, 60, Map.of("MAD", 1, "INT", 1)),
                new Scroll(2040816, 10, Map.of("INT", 3, "MAD", 3, "MDD", 1)));
        catalog.scrolls(THIEF_GLOVES, gloves);
        catalog.scrolls(MAGE_GLOVES, gloves);
        catalog.scrolls(MALE_OVERALL, List.of(
                new Scroll(2040500, 100, Map.of("DEX", 1)),
                new Scroll(2040501, 60, Map.of("DEX", 2, "ACC", 1)),
                new Scroll(2040502, 10, Map.of("DEX", 5, "ACC", 3, "Speed", 1)),
                new Scroll(2040532, 60, Map.of("STR", 2, "PDD", 1)),
                new Scroll(2040534, 10, Map.of("STR", 5, "PDD", 3, "MHP", 5))));
    }

    @Test
    void eventScrollsThatBeatALowerGradeAreNotStandard() {
        List<Integer> kept = FriendForge.standard(catalog.scrollsFor(GLADIUS)).stream().map(Scroll::id).toList();
        assertTrue(kept.contains(2043000) && kept.contains(2043001) && kept.contains(2043002), kept.toString());
        assertTrue(!kept.contains(2043003) && !kept.contains(2043023), kept.toString());
    }

    @Test
    void friendshipDecidesTheGradeAndHowManyTake() {
        // Lv 1-3: only 100% scrolls, all seven take
        assertPlan(FriendForge.plan(GLADIUS, FIGHTER, 3, TAKES_60, TAKES_10, catalog), 2043000, 7);
        // Lv 4: 60% scrolls, 70% of 7 = 5 take (+10 att beats +7 att)
        assertPlan(FriendForge.plan(GLADIUS, FIGHTER, 4, TAKES_60, TAKES_10, catalog), 2043001, 5);
        // Lv 7: 10% scrolls, 45% of 7 = 3 take (+15 att beats seven 60% for +14)
        assertPlan(FriendForge.plan(GLADIUS, FIGHTER, 7, TAKES_60, TAKES_10, catalog), 2043002, 3);
        // Lv 10: 90% of 7 = 6 take
        assertPlan(FriendForge.plan(GLADIUS, FIGHTER, 10, TAKES_60, TAKES_10, catalog), 2043002, 6);
    }

    @Test
    void theFriendKeepsTheGradeThatMakesTheBetterPiece() {
        // five-slot gloves: at Lv 7 three 10% (+6) lose to five 60% (+10); at Lv 10 five 10% (+15) win
        assertPlan(FriendForge.plan(THIEF_GLOVES, ASSASSIN, 7, TAKES_60, TAKES_10, catalog), 2040804, 5);
        assertPlan(FriendForge.plan(THIEF_GLOVES, ASSASSIN, 10, TAKES_60, TAKES_10, catalog), 2040805, 5);
    }

    @Test
    void aHigherLevelNeverMakesAWorsePiece() {
        int[][] pieces = {{GLADIUS, FIGHTER}, {WAND, CLERIC}, {THIEF_GLOVES, ASSASSIN}, {MAGE_GLOVES, CLERIC},
                {MALE_OVERALL, GUNSLINGER}, {MALE_OVERALL, FIGHTER}};
        for (int[] piece : pieces) {
            double previous = -1;
            for (int level = 0; level <= 10; level++) {
                Plan plan = FriendForge.plan(piece[0], piece[1], level, TAKES_60, TAKES_10, catalog);
                double value = plan.uses().isEmpty() ? 0
                        : plan.uses().get(0).takes() * FriendForge.value(plan.uses().get(0).scroll().stats(), piece[1]);
                assertTrue(value >= previous, piece[0] + " for job " + piece[1] + " got worse at Lv " + level);
                previous = value;
            }
        }
    }

    @Test
    void scrollsAreChosenForTheJob() {
        assertEquals(2040816, FriendForge.plan(MAGE_GLOVES, CLERIC, 10, TAKES_60, TAKES_10, catalog).uses().get(0).scroll().id(),
                "magic attack gloves for a magician");
        assertEquals(2040502, FriendForge.plan(MALE_OVERALL, GUNSLINGER, 10, TAKES_60, TAKES_10, catalog).uses().get(0).scroll().id(),
                "DEX for a gunslinger");
        assertEquals(2040534, FriendForge.plan(MALE_OVERALL, FIGHTER, 10, TAKES_60, TAKES_10, catalog).uses().get(0).scroll().id(),
                "STR for a fighter");
    }

    @Test
    void aPieceWithoutSlotsOrScrollsIsGivenPlain() {
        catalog.equip(1032000, 0, 0);
        Plan plan = FriendForge.plan(1032000, FIGHTER, 10, TAKES_60, TAKES_10, catalog);
        assertTrue(plan.uses().isEmpty());
    }

    @Test
    void thePoolGivesTheWieldedWeaponTypeOrTheJobsFirstPiece() {
        List<Integer> pool = List.of(WAND, CLAW, GLADIUS, AXE);
        assertEquals(AXE, FriendForge.pick(pool, FIGHTER, 0, 1312000, catalog), "wields an axe");
        assertEquals(GLADIUS, FriendForge.pick(pool, FIGHTER, 0, 0, catalog), "bare-handed: the first warrior piece");
        assertEquals(GLADIUS, FriendForge.pick(pool, FIGHTER, 0, 1442000, catalog), "a polearm not in the pool");
        assertEquals(WAND, FriendForge.pick(pool, CLERIC, 1, 1382000, catalog), "a staff user takes the wand");
        assertEquals(CLAW, FriendForge.pick(pool, ASSASSIN, 0, 1472000, catalog));
        assertEquals(0, FriendForge.pick(pool, BEGINNER, 0, 1302000, catalog), "nothing for a beginner");
        assertEquals(0, FriendForge.pick(pool, GUNSLINGER, 0, 1492000, catalog), "nothing for a pirate");
    }

    @Test
    void gearedPiecesFollowTheWearersGender() {
        List<Integer> pool = List.of(MALE_OVERALL, FEMALE_OVERALL);
        assertEquals(MALE_OVERALL, FriendForge.pick(pool, FIGHTER, 0, 0, catalog));
        assertEquals(FEMALE_OVERALL, FriendForge.pick(pool, FIGHTER, 1, 0, catalog));
        assertEquals(MALE_OVERALL, FriendForge.pick(pool, BEGINNER, 0, 0, catalog), "no job requirement fits a beginner");
    }

    @Test
    void aGiversOwnPieceTakesItsWholeRecipe() {
        // the final tier: two 10% and five 60%, every one taking, chosen for the sword's own class (a warrior's)
        Plan plan = FriendForge.recipe(GLADIUS, List.of(new ScrollRecipe(10, 2), new ScrollRecipe(60, 5)),
                FriendForge.classOf(GLADIUS, CLERIC), catalog);
        assertEquals(7, plan.slots());
        assertEquals(List.of(new Use(catalog.scroll(GLADIUS, 2043002), 2, 2), new Use(catalog.scroll(GLADIUS, 2043001), 5, 5)),
                plan.uses(), "sword attack scrolls even for a magician holding the quest");
        // the lower and mid tiers
        assertEquals(List.of(new Use(catalog.scroll(GLADIUS, 2043000), 7, 7)),
                FriendForge.recipe(GLADIUS, List.of(new ScrollRecipe(100, 7)), 100, catalog).uses());
        assertEquals(List.of(new Use(catalog.scroll(WAND, 2043701), 7, 7)),
                FriendForge.recipe(WAND, List.of(new ScrollRecipe(60, 7)), FriendForge.classOf(WAND, FIGHTER), catalog).uses(),
                "magic attack for a wand");
    }

    @Test
    void aRecipeLongerThanThePieceIsCutAtItsSlots() {
        Plan plan = FriendForge.recipe(THIEF_GLOVES, List.of(new ScrollRecipe(10, 2), new ScrollRecipe(60, 5)), ASSASSIN, catalog);
        assertEquals(List.of(new Use(catalog.scroll(THIEF_GLOVES, 2040805), 2, 2), new Use(catalog.scroll(THIEF_GLOVES, 2040804), 3, 3)),
                plan.uses());
    }

    @Test
    void aRecipeLineWithoutACountFillsTheSlotsLeft() {
        // an armourer's final tier on five-slot gloves: two 10% and the rest (three) 60%, for the player's own class
        Plan gloves = FriendForge.recipe(THIEF_GLOVES, List.of(new ScrollRecipe(10, 2), new ScrollRecipe(60, ScrollRecipe.REST)),
                FriendForge.classOf(THIEF_GLOVES, ASSASSIN), catalog);
        assertEquals(List.of(new Use(catalog.scroll(THIEF_GLOVES, 2040805), 2, 2), new Use(catalog.scroll(THIEF_GLOVES, 2040804), 3, 3)),
                gloves.uses());
        // the lower tier on a ten-slot overall: all ten 100%, chosen for the wearer (STR for a fighter)
        Plan overall = FriendForge.recipe(MALE_OVERALL, List.of(new ScrollRecipe(100, ScrollRecipe.REST)), FIGHTER, catalog);
        assertEquals(1, overall.uses().size());
        assertEquals(10, overall.uses().get(0).count());
        assertEquals(10, overall.uses().get(0).takes());
        // magic attack gloves when the same recipe is made for a magician
        assertEquals(2040816, FriendForge.recipe(MAGE_GLOVES, List.of(new ScrollRecipe(10, 2), new ScrollRecipe(60, ScrollRecipe.REST)),
                FriendForge.classOf(MAGE_GLOVES, CLERIC), catalog).uses().get(0).scroll().id());
    }

    @Test
    void weaponsAreScrolledForTheClassTheyAreMadeFor() {
        assertEquals(100, FriendForge.classOf(GLADIUS, CLERIC));
        assertEquals(200, FriendForge.classOf(WAND, FIGHTER));
        assertEquals(410, FriendForge.classOf(CLAW, FIGHTER));
        assertEquals(420, FriendForge.classOf(1332009, FIGHTER), "a dagger is a bandit's");
        assertEquals(520, FriendForge.classOf(1492004, FIGHTER), "a gun is a gunslinger's");
        assertEquals(CLERIC, FriendForge.classOf(MAGE_GLOVES, CLERIC), "armour keeps the player's class");
    }

    private static void assertPlan(Plan plan, int scrollId, int takes) {
        assertEquals(scrollId, plan.uses().isEmpty() ? 0 : plan.uses().get(0).scroll().id(), "scroll");
        assertEquals(takes, plan.uses().isEmpty() ? 0 : plan.uses().get(0).takes(), "takes");
    }

    private static final class FakeCatalog implements Catalog {
        private final Map<Integer, Map<String, Integer>> equips = new HashMap<>();
        private final Map<Integer, List<Scroll>> scrolls = new HashMap<>();

        void equip(int itemId, int reqJob, int slots) {
            equips.put(itemId, Map.of("reqJob", reqJob, "tuc", slots));
        }

        void scrolls(int itemId, List<Scroll> list) {
            scrolls.put(itemId, new ArrayList<>(list));
        }

        @Override
        public Map<String, Integer> equipStats(int itemId) {
            return equips.get(itemId);
        }

        @Override
        public List<Scroll> scrollsFor(int itemId) {
            return scrolls.getOrDefault(itemId, List.of());
        }

        Scroll scroll(int itemId, int scrollId) {
            return scrollsFor(itemId).stream().filter(scroll -> scroll.id() == scrollId).findFirst().orElseThrow();
        }
    }
}

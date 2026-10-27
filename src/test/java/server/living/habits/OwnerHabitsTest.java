package server.living.habits;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OwnerHabits (docs/BOT_CONCEPT_PLAN.md F2.3): what is noticed of a human who owns companions, and the gates that let
 * only the best of it be learned. The potion point is the median of kept drinks, and a drink shortly before a death is
 * never kept; a category is skipped only when nearly all of it is left, its drops are cheap, it is not one never skipped,
 * and only so many; an item is vendored only when sold on separate visits; a map is learned for its EXP an hour against
 * the owner's best in the band, never one that keeps killing them; a skill for its share of the damage. The document
 * survives a write and a read, and "forget my habits" empties it.
 */
class OwnerHabitsTest {
    private static final OwnerHabits.Rules RULES = OwnerHabits.Rules.of(new LivingWorldConfig());
    private static final int WINDOW = RULES.deathWindowMs();
    private static final int ARROW = 2060000;
    private static final int CROSSBOW_ARROW = 2061000;
    private static final int ORE = 4010000;
    private static final int SNAIL_SHELL = 4000019;
    private static final int HENESYS_HUNTING_GROUND = 100010000;
    private static final int ANT_TUNNEL = 105050000;
    private static final int PIG_BEACH = 104010001;

    /** Drinks at each percent of HP, a minute apart, so each is kept by the next. */
    private static OwnerHabits drinking(int... hpPercents) {
        OwnerHabits habits = new OwnerHabits();
        long at = 0L;
        for (int hp : hpPercents) {
            habits.drank(at, hp, -1, WINDOW);
            at += 60_000L;
        }
        habits.keepDrinks(at + WINDOW + 1, WINDOW);
        return habits;
    }

    @Test
    void thePotionPointIsTheMedianOnceEnoughDrinksAreKept() {
        assertNull(drinking(50, 55, 60).hpPoint(RULES), "three drinks: too few to say");
        OwnerHabits habits = drinking(50, 55, 60, 52, 58, 90, 20, 55);
        assertEquals(8, RULES.minDrinks());
        assertEquals(55, habits.hpPoint(RULES), "the median: a one-off drink at 90% or 20% does not move it");
        assertNull(habits.mpPoint(RULES), "no MP potion drunk");
        assertEquals(55, OwnerHabits.median(List.of(50, 60)), "an even count: the two middle ones' mean");
    }

    @Test
    void aDrinkJustBeforeADeathCameTooLateAndIsNeverLearned() {
        OwnerHabits habits = drinking(60, 60, 60, 60, 60, 60, 60);
        long now = 1_000_000L;
        habits.drank(now, 15, -1, WINDOW); // a late drink...
        habits.died(); // ...and the owner died within the window
        habits.keepDrinks(now + WINDOW + 1, WINDOW);
        assertNull(habits.hpPoint(RULES), "the late drink was dropped: still seven kept");

        habits.drank(now + 120_000L, 62, -1, WINDOW);
        habits.keepDrinks(now + 120_000L + WINDOW + 1, WINDOW); // no death came
        assertEquals(60, habits.hpPoint(RULES));
    }

    @Test
    void loggingOffKeepsWhatWasDrunkSince() {
        OwnerHabits habits = new OwnerHabits();
        for (int i = 0; i < 8; i++) {
            habits.drank(i, 40, 30, WINDOW); // all within the window, none kept yet
        }
        assertNull(habits.hpPoint(RULES));
        habits.keepAllDrinks(); // a logout is no death
        assertEquals(40, habits.hpPoint(RULES));
        assertEquals(30, habits.mpPoint(RULES));
    }

    @Test
    void aCategoryIsSkippedOnlyWhenNearlyAllOfItIsLeftAndItIsCheap() {
        OwnerHabits habits = new OwnerHabits();
        assertEquals(206, OwnerHabits.category(ARROW));
        assertEquals(206, OwnerHabits.category(CROSSBOW_ARROW), "bow and crossbow arrows are one category");
        for (int i = 0; i < 12; i++) {
            habits.lootSeen(i % 2 == 0 ? ARROW : CROSSBOW_ARROW, false, 1);
        }
        habits.lootSeen(ARROW, true, 1); // one picked up of 13: 92% left
        for (int i = 0; i < 9; i++) {
            habits.lootSeen(SNAIL_SHELL, false, 1); // left every time, but seen too few times
        }
        for (int i = 0; i < 10; i++) {
            habits.lootSeen(ORE, i < 8, 10); // mostly picked up
        }
        assertEquals(List.of(206), habits.skippedCategories(RULES, category -> false));

        OwnerHabits dear = new OwnerHabits();
        for (int i = 0; i < 12; i++) {
            dear.lootSeen(ORE, false, 5_000); // left every time, but worth more than the cap
        }
        assertEquals(List.of(), dear.skippedCategories(RULES, category -> false), "never learned to waste value");
        assertEquals(List.of(), drinking().skippedCategories(RULES, category -> false));
    }

    @Test
    void aCategoryNeverSkippedStaysPickedUpAndOnlySoManyAreSkipped() {
        OwnerHabits habits = new OwnerHabits();
        for (int category = 1; category <= 6; category++) {
            for (int i = 0; i < 10 + category; i++) {
                habits.lootSeen(4000000 + category * 10000, false, 1); // categories 401..406, each always left
            }
        }
        List<Integer> skipped = habits.skippedCategories(RULES, category -> category == 406);
        assertEquals(RULES.lootMaxSkips(), skipped.size(), "never more than LOOT_MAX_SKIPS: LIKE_OWNER never becomes NONE");
        assertEquals(List.of(405, 404, 403, 402), skipped, "the most seen first; the never-skipped one passed over");
    }

    @Test
    void anItemDroppedFromTheBagCountsAsLeft() {
        OwnerHabits habits = new OwnerHabits();
        for (int i = 0; i < 10; i++) {
            habits.lootSeen(SNAIL_SHELL, true, 1); // picked up...
            habits.dropped(SNAIL_SHELL, 1); // ...and thrown away
        }
        assertEquals(List.of(400), habits.skippedCategories(RULES, category -> false));
        assertEquals(SNAIL_SHELL, habits.exampleLeft(400));
    }

    @Test
    void anItemIsVendoredOnlyWhenSoldOnSeparateVisits() {
        OwnerHabits habits = new OwnerHabits();
        habits.sold(SNAIL_SHELL, 0L);
        habits.sold(SNAIL_SHELL, 60_000L); // the same visit
        habits.sold(ORE, 0L);
        assertEquals(List.of(), habits.soldItems(RULES), "one visit is no habit");
        habits.sold(SNAIL_SHELL, OwnerHabits.SELL_VISIT_GAP_MS + 60_000L);
        assertEquals(List.of(SNAIL_SHELL), habits.soldItems(RULES));
    }

    @Test
    void aMapIsLearnedForItsExpAnHourAgainstTheBestAndNeverOneThatKills() {
        OwnerHabits habits = new OwnerHabits();
        long hour = 3_600_000L;
        habits.trained(35, ANT_TUNNEL, hour, 100_000L, 300, 0); // the best: 100k an hour
        habits.trained(31, PIG_BEACH, hour, 85_000L, 250, 0); // within 80% of it
        habits.trained(38, HENESYS_HUNTING_GROUND, hour, 60_000L, 200, 0); // too far below
        habits.trained(30, 104040000, hour, 150_000L, 400, 3); // the most EXP, but 3 deaths an hour
        habits.trained(34, 105050100, hour / 2, 80_000L, 20, 0); // 160k an hour on 20 kills: too few to say
        habits.trained(25, 101010000, hour, 200_000L, 300, 0); // another band
        assertEquals(List.of(ANT_TUNNEL, PIG_BEACH), habits.trainingMaps(33, RULES));
        assertEquals(List.of(101010000), habits.trainingMaps(29, RULES));
        assertEquals(List.of(), habits.trainingMaps(45, RULES));
    }

    @Test
    void aSkillIsLearnedForTheShareOfDamageItLandsNotForBeingPressed() {
        OwnerHabits habits = new OwnerHabits();
        for (int i = 0; i < 100; i++) {
            habits.skillUsed(110, 1101006, 0L); // Rage, a buff: pressed a lot, lands nothing
            habits.skillUsed(110, 1001004, 1_000L); // Power Strike: most of the damage
        }
        for (int i = 0; i < 40; i++) {
            habits.skillUsed(110, 1001005, 500L); // Slash Blast: 20k of 150k, under a quarter
        }
        for (int i = 0; i < 10; i++) {
            habits.skillUsed(110, 1111008, 3_000L); // 30k, 20%, and used too few times anyway
        }
        assertEquals(List.of(1001004), habits.favoriteSkills(110, RULES));
        assertEquals(List.of(), habits.favoriteSkills(120, RULES), "another job: nothing");
    }

    @Test
    void theDocumentSurvivesAWriteAndARead() {
        OwnerHabits habits = drinking(50, 55, 60, 52, 58, 90, 20, 55);
        for (int i = 0; i < 12; i++) {
            habits.lootSeen(ARROW, false, 1);
        }
        habits.sold(SNAIL_SHELL, 0L);
        habits.sold(SNAIL_SHELL, OwnerHabits.SELL_VISIT_GAP_MS);
        habits.trained(35, ANT_TUNNEL, 3_600_000L, 100_000L, 300, 0);
        for (int i = 0; i < 30; i++) {
            habits.skillUsed(110, 1001004, 1_000L);
        }
        assertTrue(habits.say("potion"));
        assertFalse(habits.say("potion"), "said once");

        OwnerHabits read = OwnerHabits.parse(habits.toJson());
        assertEquals(55, read.hpPoint(RULES));
        assertEquals(List.of(206), read.skippedCategories(RULES, category -> false));
        assertEquals(List.of(SNAIL_SHELL), read.soldItems(RULES));
        assertEquals(List.of(ANT_TUNNEL), read.trainingMaps(35, RULES));
        assertEquals(List.of(1001004), read.favoriteSkills(110, RULES));
        assertTrue(read.said("potion"));
        assertEquals(habits.toJson(), read.toJson());
        assertEquals(new OwnerHabits().toJson(), OwnerHabits.parse("not json").toJson(), "unreadable: nothing noticed");
        assertEquals(new OwnerHabits().toJson(), OwnerHabits.parse(null).toJson());
    }

    @Test
    void forgetMyHabitsLeavesNothing() {
        OwnerHabits habits = drinking(50, 55, 60, 52, 58, 90, 20, 55);
        habits.say("potion");
        habits.forget();
        assertNull(habits.hpPoint(RULES));
        assertFalse(habits.said("potion"), "it may mention a habit again once learned again");
        assertEquals(new OwnerHabits().toJson(), habits.toJson());
        assertTrue(habits.dirty());
    }
}

package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.Assignment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;

/**
 * The live bench harvest (docs/BOT_MANAGEMENT_PLAN.md P5, "Harvest counts kill loot and meso only"; P3b giveCoin): the
 * haul takes only what the companion picked up on its slice, meso and item stacks, never more than it still holds.
 * Coin its owner gives it, potions it buys, gear it puts on (and the gear that comes off) and stall stock moved into
 * its bag were never picked up, so they stay its own. Nothing is duplicated or lost: what goes into the haul leaves
 * its purse or bag.
 */
class HarvestDiffTest {
    private static final int BRIN = 902;
    private static final int RED_POTION = 2000000;
    private static final int OLD_SWORD = 1302000;
    private static final int NEW_SWORD = 1302001;
    private static final int SNAIL_SHELL = 4000019;
    private static final int STOLEN_FENCE = 4000021;

    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aTakeIsWhatWasPickedUpNeverMoreThanItHolds() {
        HarvestDiff loot = new HarvestDiff();
        loot.meso(300);
        loot.item(SNAIL_SHELL, 7);
        loot.item(RED_POTION, 2);
        loot.item(NEW_SWORD, 1);

        assertEquals(200L, loot.takeMeso(200L), "it spent some: only what its purse holds");
        assertEquals(0L, loot.takeMeso(5_000L), "taken once; the rest was spent, not farmed");
        Map<Integer, Integer> held = Map.of(SNAIL_SHELL, 9, RED_POTION, 1, STOLEN_FENCE, 40);
        assertEquals(Map.of(SNAIL_SHELL, 7, RED_POTION, 1), loot.takeItems(itemId -> held.getOrDefault(itemId, 0)),
                "the shells it picked up; one potion (it drank the other); the sword it wears; never the fences it never picked up");
        assertEquals(Map.of(), loot.takeItems(itemId -> 99));
        assertEquals(10L, loot.itemsTotal());
        assertEquals(300L, loot.mesoTotal());
    }

    @Test
    void aCoinItsOwnerGaveStaysInItsPurseWhileTheMesoItPickedUpGoesToTheHaul() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick(); // live; its purse is 1,000

        game.picksUpMeso(BRIN, 300);
        game.purses.merge(BRIN, 500, Integer::sum); // Ann's coin for supplies (CompanionManager.giveCoin)
        game.duty.tick();

        assertEquals(300L, game.row(ANN, "brin").haulMeso(), "only what it picked up");
        assertEquals(1_500, game.purses.get(BRIN), "the coin and its own purse are still its own");
        assertTrue(game.store.events("bench.harvest").contains(ANN + " brin bench.harvest +300 meso, 0 stacks"));
    }

    @Test
    void potionsItBuysAndGearItWearsAreNeverTaken() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();

        game.bags.get(BRIN).merge(RED_POTION, 100, Integer::sum); // bought at a shop
        game.picksUp(BRIN, RED_POTION, 2); // two dropped
        game.picksUp(BRIN, NEW_SWORD, 1); // it picked up a sword...
        game.bags.get(BRIN).remove(NEW_SWORD); // ...and put it on
        game.bags.get(BRIN).merge(OLD_SWORD, 1, Integer::sum); // the one it wore goes back in its bag
        game.picksUp(BRIN, SNAIL_SHELL, 7);
        game.duty.tick();

        assertEquals(Map.of(RED_POTION, 2, SNAIL_SHELL, 7), game.haul(ANN, "brin"));
        assertEquals(100, game.bags.get(BRIN).get(RED_POTION), "its bought potions stay");
        assertEquals(1, game.bags.get(BRIN).get(OLD_SWORD), "the gear that came off stays");
        assertEquals(0, game.bags.get(BRIN).get(SNAIL_SHELL), "what went to the haul left its bag");
    }

    @Test
    void stallStockMovedIntoItsBagIsNeverTakenBack() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_ITEM);
        game.store.store.addHaul(ANN, "brin", STOLEN_FENCE, 5);
        game.duty.tick();

        game.store.store.removeHaul(ANN, "brin", STOLEN_FENCE, 5); // stock for a stall, from its haul
        game.bags.get(BRIN).merge(STOLEN_FENCE, 5, Integer::sum);
        game.duty.tick();

        assertEquals(0, game.haul(ANN, "brin").getOrDefault(STOLEN_FENCE, 0));
        assertEquals(5, game.bags.get(BRIN).get(STOLEN_FENCE));
    }

    @Test
    void whatItPickedUpSinceTheLastLookIsTakenWhenTheSliceEnds() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();

        game.picksUpMeso(BRIN, 120);
        game.picksUp(BRIN, SNAIL_SHELL, 3);
        game.duty.endFor("brin", "called by owner");

        assertEquals(120L, game.row(ANN, "brin").haulMeso());
        assertEquals(Map.of(SNAIL_SHELL, 3), game.haul(ANN, "brin"));
        assertEquals(1_000, game.purses.get(BRIN));
    }

    @Test
    void aTraineeKeepsWhatItPicksUp() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();

        game.picksUpMeso(901, 80);
        game.picksUp(901, SNAIL_SHELL, 2);
        game.duty.tick();
        game.duty.endFor("olwen", "called by owner");

        assertEquals(0L, game.row(ANN, "olwen").haulMeso());
        assertEquals(Map.of(), game.haul(ANN, "olwen"));
        assertEquals(1_080, game.purses.get(901));
    }

    @Test
    void aFullBagLeavesANewStackWithTheCompanion() {
        game.config.HAUL_MAX_STACKS = 1;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.store.store.addHaul(ANN, "brin", STOLEN_FENCE, 1);
        game.duty.tick();

        game.picksUp(BRIN, SNAIL_SHELL, 4);
        game.picksUp(BRIN, STOLEN_FENCE, 2);
        game.duty.tick();

        assertEquals(Map.of(STOLEN_FENCE, 3), game.haul(ANN, "brin"), "a stack it already carries still fills");
        assertEquals(4, game.bags.get(BRIN).get(SNAIL_SHELL), "the companion keeps it");
    }
}

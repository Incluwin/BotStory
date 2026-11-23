package server.living.population;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One learned price lever (docs/WORLD_SYSTEMS_PLAN.md I2, PriceArms): arms 0.85..1.30, the item classes, an arm's
 * estimate shrunk toward the prior, rewards, and rows that go to the store and come back.
 */
class PriceArmsTest {
    private static final PriceArms.Tuning TUNING = new PriceArms.Tuning(0.2d, 0.03d, 3.0d);

    private static PriceArms asks() {
        return new PriceArms("ASK", PriceArms.steps(0.85d, 1.30d, 0.05d), m -> m >= 0.9d && m <= 1.2d ? 0.3d * m : 0.0d);
    }

    @Test
    void theArmsAreTheStepsOfThePlan() {
        assertArrayEquals(new double[]{0.85, 0.90, 0.95, 1.00, 1.05, 1.10, 1.15, 1.20, 1.25, 1.30},
                PriceArms.steps(0.85d, 1.30d, 0.05d), 0.0d);
        assertArrayEquals(new double[]{1.00, 1.05, 1.10, 1.15, 1.20, 1.25, 1.30}, PriceArms.steps(1.0d, 1.3d, 0.05d), 0.0d);
    }

    @Test
    void itemClassesComeFromTheItemIdBlocks() {
        assertEquals(PriceArms.ItemClass.EQUIP, PriceArms.ItemClass.of(1302000));
        assertEquals(PriceArms.ItemClass.POTION, PriceArms.ItemClass.of(2000002));
        assertEquals(PriceArms.ItemClass.POTION, PriceArms.ItemClass.of(2020013));
        assertEquals(PriceArms.ItemClass.SCROLL, PriceArms.ItemClass.of(2040804));
        assertEquals(PriceArms.ItemClass.ORE, PriceArms.ItemClass.of(4010000)); // bronze ore
        assertEquals(PriceArms.ItemClass.ORE, PriceArms.ItemClass.of(4020007)); // diamond ore
        assertEquals(PriceArms.ItemClass.ORE, PriceArms.ItemClass.of(4004000)); // power crystal ore
        assertEquals(PriceArms.ItemClass.OTHER, PriceArms.ItemClass.of(2070000)); // stars
        assertEquals(PriceArms.ItemClass.OTHER, PriceArms.ItemClass.of(4000019)); // snail shell
    }

    @Test
    void anUntriedArmIsItsPriorAndRewardsMoveItTowardWhatTheyMeasured() {
        PriceArms arms = asks();
        assertEquals(0.3d * 1.0d, arms.estimate("EQUIP", 3, 3.0d), 1e-12, "1.00 starts where today's rule is believed");
        assertEquals(0.0d, arms.estimate("EQUIP", 9, 3.0d), 1e-12, "1.30 is outside today's range: no prior credit");
        arms.reward("EQUIP", 3, 1.0d, 3.0d); // three bundles sold at the index
        assertEquals((3 * 1.0d + 3 * 0.3d) / 6.0d, arms.estimate("EQUIP", 3, 3.0d), 1e-12);
        arms.reward("EQUIP", 3, 0.0d, 6.0d);
        assertEquals((3 * 1.0d + 3 * 0.3d) / 12.0d, arms.estimate("EQUIP", 3, 3.0d), 1e-12);
        assertEquals(0.3d, arms.estimate("ORE", 3, 3.0d), 1e-12, "classes learn apart");
    }

    @Test
    void scoresAddTheSharedUcbBonus() {
        PriceArms arms = asks();
        arms.reward("ORE", 0, 0.85d, 4.0d);
        double[] scores = arms.scores("ORE", TUNING);
        assertEquals(arms.estimate("ORE", 0, 3.0d) + Ucb.bonus(0.2d, 4.0d, 4.0d), scores[0], 1e-12);
        assertEquals(arms.estimate("ORE", 5, 3.0d) + Ucb.bonus(0.2d, 4.0d, 0.0d), scores[5], 1e-12);
    }

    @Test
    void aPickAtTemperatureZeroIsTheBestScoreAndTheDrawIsSpreadOtherwise() {
        PriceArms arms = asks();
        for (int i = 0; i < 50; i++) {
            arms.reward("SCROLL", 4, 1.05d, 1.0d); // 1.05 always sells
            arms.reward("SCROLL", 5, 1.03d, 1.0d); // and 1.10 nearly as well: a near tie
        }
        assertEquals(4, arms.pick("SCROLL", new PriceArms.Tuning(0.0d, 0.0d, 3.0d), new Random(1L)));
        assertEquals(4, arms.best("SCROLL", 3.0d));
        int fours = 0;
        Random random = new Random(2L);
        for (int i = 0; i < 1_000; i++) {
            fours += arms.pick("SCROLL", TUNING, random) == 4 ? 1 : 0;
        }
        assertTrue(fours > 500 && fours < 900, "mostly the best, not all of them: " + fours);
    }

    @Test
    void rowsGoToTheStoreAndComeBack() {
        PriceArms arms = asks();
        arms.reward("EQUIP", 2, 0.95d, 2.0d);
        arms.reward("EQUIP", 2, 0.0d, 2.0d);
        arms.reward("ORE", 7, 1.2d, 1.0d);
        List<PriceArms.Row> rows = arms.rows();
        assertEquals(List.of(new PriceArms.Row("EQUIP", 2, 0.95d, 4.0d, 0.475d), new PriceArms.Row("ORE", 7, 1.2d, 1.0d, 1.2d)),
                rows);
        assertEquals("ASK_EQUIP", arms.storedKey("EQUIP"));
        PriceArms back = asks();
        for (PriceArms.Row row : rows) {
            back.load(row.key(), row.arm(), row.n(), row.mean());
        }
        back.load("ORE", 42, 1.0d, 1.0d); // an arm this lever does not have: skipped
        assertEquals(rows, back.rows());
        assertEquals(arms.estimate("EQUIP", 2, 3.0d), back.estimate("EQUIP", 2, 3.0d), 1e-12);
    }

    @Test
    void theJsonShowsCountsAndMeans() {
        PriceArms arms = asks();
        arms.reward("ORE", 0, 0.85d, 2.0d);
        String json = arms.json(3.0d);
        assertTrue(json.startsWith("{\"ORE\":{\"best\":"), json);
        assertTrue(json.contains("{\"m\":0.850,\"n\":2.000,\"mean\":0.850}"), json);
        assertEquals("{}", asks().json(3.0d));
    }
}

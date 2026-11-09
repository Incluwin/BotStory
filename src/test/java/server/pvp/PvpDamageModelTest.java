package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpDamageModelTest {
    private final PvpConfig config = new PvpConfig();

    private static PvpReference reference() {
        return PvpReference.parse(List.of(
                "# test table",
                "h_ref:",
                "  70: 4191   # n=180",
                "gear_base:",
                "  \"11-7\": 1219",
                "d_job:",
                "  \"11-7\": 6000",
                "dps_per_base:",
                "  physical: 5.0",
                "  magic: 90"));
    }

    @Test
    void referenceReadsItsTableAndFallsBack() {
        PvpReference reference = reference();

        assertEquals(4191, reference.hRef(75));
        assertEquals(PvpReference.fittedHp(105), reference.hRef(105));   // no 100s row: the V0.5 fit
        assertEquals(1219, reference.gearMedian(112, 79));
        assertEquals(-1, reference.gearMedian(312, 79));
        assertEquals(6000, reference.measuredRate(111, 70));
        assertEquals(90d, reference.ratePerBase(true));
        // the fit is within 5% of the database table from Lv 30 to 129
        assertTrue(Math.abs(PvpReference.fittedHp(75) - 4191) < 4191 * 0.05);
        assertTrue(Math.abs(PvpReference.fittedHp(35) - 1745) < 1745 * 0.05);
    }

    @Test
    void aMedianAttackerKnocksOutAMedianVictimInTtkSeconds() {
        PvpReference reference = reference();
        PvpDamageModel.Attacker median = new PvpDamageModel.Attacker(75, 112, 1219, false);

        double c = PvpDamageModel.coefficient(median, reference, config);
        double pvpDps = 6000 * c;               // its raw sustained rate is the job's measured rate

        assertEquals(4191d / 12d, pvpDps, 0.01d);
        assertEquals(12d, 4191d / pvpDps, 1e-9);
    }

    @Test
    void gearCountsButCompressed() {
        PvpReference reference = reference();
        double median = PvpDamageModel.coefficient(new PvpDamageModel.Attacker(75, 112, 1219, false), reference, config);
        double doubled = PvpDamageModel.coefficient(new PvpDamageModel.Attacker(75, 112, 2438, false), reference, config);

        // twice the gear rolls twice the raw damage; after the coefficient it deals 2^0.7 = 1.62x
        assertEquals(Math.pow(2d, 0.7d), 2d * doubled / median, 1e-9);
    }

    @Test
    void anUnmeasuredJobUsesItsGearAndTheRatePerBase() {
        PvpReference reference = reference();
        // a bowman path with no table rows: gear median = its own base, rate = base x 5.0
        double c = PvpDamageModel.coefficient(new PvpDamageModel.Attacker(75, 312, 1000, false), reference, config);

        assertEquals(4191d / (12d * 5000d), c, 1e-12);
    }

    @Test
    void areaLevelDefenseAndHitFactorsFollowThePlan() {
        assertEquals(1d, PvpDamageModel.areaShare(1, config), 1e-9);
        assertEquals(0.7d, PvpDamageModel.areaShare(3, config), 1e-9);
        assertEquals(0.6d, PvpDamageModel.areaShare(9, config), 1e-9);

        // 1% a level, capped at 5% (2026-11-09, practice run seven: 2% / 10% let a 3-5 level edge win 79%)
        assertEquals(1.05d, PvpDamageModel.levelFactor(85, 80, config), 1e-9);
        assertEquals(0.95d, PvpDamageModel.levelFactor(70, 90, config), 1e-9);   // capped at 5%

        assertEquals(1d - 276d / (276d + 40d * 79d), PvpDamageModel.defenseFactor(276, 79, config), 1e-9);
        assertEquals(0.75d, PvpDamageModel.defenseFactor(100_000, 10, config), 1e-9);   // capped at 25%

        assertEquals(0.75d, PvpDamageModel.hitChance(false, 150, 100, 100, 265, config), 1e-9);   // luck thief: capped
        assertEquals(0.90d, PvpDamageModel.hitChance(true, 10, 100, 100, 265, config), 1e-9);
        assertEquals(1d, PvpDamageModel.hitChance(false, 500, 100, 100, 0, config), 1e-9);
    }

    @Test
    void noAttackTakesMoreThanTheCapAndNoKnockOutComesInUnderFourAttacks() {
        Random random = new Random(7);
        for (int trial = 0; trial < 2000; trial++) {
            int lineCount = 1 + random.nextInt(8);
            List<Integer> raw = new java.util.ArrayList<>();
            for (int i = 0; i < lineCount; i++) {
                raw.add(random.nextInt(200_000));
            }
            int maxHp = 500 + random.nextInt(30_000);
            List<Integer> lines = PvpDamageModel.scaleLines(raw, 0.01d + random.nextDouble() * 5d, maxHp, config);

            long total = lines.stream().mapToLong(Integer::longValue).sum();
            assertTrue(total <= maxHp * 25L / 100L, () -> "over the cap: " + total + " of " + maxHp);
            assertTrue(4 * total >= 0 && 3 * total < maxHp, () -> "three attacks can knock out: " + total);
            assertEquals(lineCount, lines.size());
        }
    }

    /** Step 2b: a hit is scaled to the victim level's reference HP, so a level edge counts once (step 4). */
    @Test
    void aHitIsScaledToTheVictimsLevel() {
        PvpReference reference = PvpReference.parse(List.of("dps_per_base:", "  physical: 5.0"));   // the fit only

        double down = PvpDamageModel.levelHpShare(85, 80, reference);
        double up = PvpDamageModel.levelHpShare(80, 85, reference);

        assertEquals((double) reference.hRef(80) / reference.hRef(85), down, 1e-9);
        assertTrue(down < 1d && up > 1d, () -> "share down " + down + ", up " + up);
        assertEquals(1d, down * up, 1e-9);
        assertEquals(1d, PvpDamageModel.levelHpShare(80, 80, reference), 1e-9);
    }
}

package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learned matchups (2026-11-13): a class pair's pace gap, split between its two sides, bounded and persisted. */
class PvpMatchupsTest {
    private static final String TITLE = "# matchups";

    @TempDir
    Path dir;

    private PvpRateLearner learner() {
        return new PvpRateLearner(dir.resolve("learned-matchups.txt"), TITLE);
    }

    /** A fighter who really fought: 12 s engaged, 20 attack packets, every think found a skill. */
    private static DuelLog.Row fighter(int jobId, int side, boolean left, double pace) {
        DuelStats stats = new DuelStats();
        stats.packets.set(20);
        stats.thinks.set(40);
        return new DuelLog.Row(0L, 1L, 100000000, 15L, "knock-out", 1, "f" + side, side, jobId, 100, false, side, 100L,
                false, left, stats, 0L, 0L, 12d, 0d, 0d, 0d, 0d, pace);
    }

    /** A warrior (Crusader) on side 1 against a magician (F/P Mage) on side 2. */
    private static List<DuelLog.Row> warriorAgainstMagician(double warriorPace, double magicianPace) {
        return List.of(fighter(111, 1, false, warriorPace), fighter(211, 2, false, magicianPace));
    }

    @Test
    void theFasterSideOfAPairIsSlowedAndTheSlowerSpedUpByTheSameFactor() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        // check run 24: magicians took 8.5% of a warrior's HP a second, warriors 7.1% of a magician's
        for (int i = 0; i < 60; i++) {
            assertTrue(PvpMatchups.observeAll(learner, warriorAgainstMagician(0.071, 0.085), config));
        }
        double warriorHits = PvpMatchups.multiplier(learner, 1, 2);
        double magicianHits = PvpMatchups.multiplier(learner, 2, 1);
        assertTrue(warriorHits > 1.05 && warriorHits < 1.12, "warrior hits x" + warriorHits);
        assertEquals(1d, warriorHits * magicianHits, 1e-9, "the pair's shared pace stays");
        // corrected, the two paces meet: 0.071 x warrior hits against 0.085 x magician hits
        assertEquals(0.071 * warriorHits, 0.085 * magicianHits, 0.004);
    }

    @Test
    void aPairCountsOnlyAfterFiveDuelsAndOtherPairsStayUntouched() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < PvpMatchups.MIN_DUELS - 1; i++) {
            PvpMatchups.observeAll(learner, warriorAgainstMagician(0.05, 0.10), config);
        }
        assertEquals(1d, PvpMatchups.multiplier(learner, 1, 2), 1e-9);
        PvpMatchups.observeAll(learner, warriorAgainstMagician(0.05, 0.10), config);
        assertTrue(PvpMatchups.multiplier(learner, 1, 2) > 1d);
        assertEquals(1d, PvpMatchups.multiplier(learner, 1, 3), 1e-9, "warriors against bowmen");
        assertEquals(1d, PvpMatchups.multiplier(learner, 2, 2), 1e-9, "a class against itself");
    }

    @Test
    void theOrderOfTheRowsDoesNotTurnTheGapAround() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 30; i++) {
            // the magician's row first this time
            PvpMatchups.observeAll(learner, List.of(fighter(211, 2, false, 0.085), fighter(111, 1, false, 0.071)),
                    config);
        }
        assertTrue(PvpMatchups.multiplier(learner, 1, 2) > 1d, "the slower warrior's hits still go up");
        assertTrue(PvpMatchups.multiplier(learner, 2, 1) < 1d);
    }

    @Test
    void onlyOneAgainstOneDuelsOfTwoClassesWhereBothFoughtTeach() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        assertFalse(PvpMatchups.observeAll(learner, List.of(fighter(111, 1, false, 0.07),
                fighter(121, 2, false, 0.09)), config), "two warriors");
        assertFalse(PvpMatchups.observeAll(learner, List.of(fighter(111, 1, false, 0.07),
                fighter(211, 2, true, 0.09)), config), "one side left");
        assertFalse(PvpMatchups.observeAll(learner, List.of(fighter(111, 1, false, 0.07),
                fighter(211, 2, false, 0.09), fighter(311, 2, false, 0.09)), config), "two against one");
        assertFalse(PvpMatchups.observeAll(learner, List.of(fighter(0, 1, false, 0.07),
                fighter(211, 2, false, 0.09)), config), "a beginner");
        DuelLog.Row starved = fighter(411, 1, false, 0.0);
        starved.stats().noSkillThinks.set(30);   // an Assassin without stars
        assertFalse(PvpMatchups.observeAll(learner, List.of(starved, fighter(211, 2, false, 0.09)), config),
                "a side that found no usable attack");
        assertTrue(learner.means().isEmpty());
    }

    @Test
    void aSideThatNeverLandedIsClippedNotInfinite() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        assertTrue(PvpMatchups.observeAll(learner, warriorAgainstMagician(0.0, 0.10), config));
        double mean = learner.mean("p12").logMean();
        assertTrue(Double.isFinite(mean) && mean < 0d, "mean " + mean);
        assertEquals(Double.NaN, PvpMatchups.observed(0d, 0d));
    }

    @Test
    void theMeansArePersistedAndReadBack() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 10; i++) {
            PvpMatchups.observeAll(learner, warriorAgainstMagician(0.071, 0.085), config);
        }
        PvpRateLearner reloaded = learner();
        reloaded.load();
        assertEquals(learner.mean("p12").logMean(), reloaded.mean("p12").logMean(), 1e-6);
        assertEquals(10, reloaded.mean("p12").fighters());
        assertEquals(PvpMatchups.multiplier(learner, 1, 2), PvpMatchups.multiplier(reloaded, 1, 2), 1e-6);
    }

    @Test
    void theSwitchNeedsEvenClasses() {
        PvpConfig config = new PvpConfig();
        config.PVP_EVEN_CLASSES = true;
        config.PVP_LEARN_MATCHUPS = true;
        assertTrue(PvpMatchups.on(config));
        config.PVP_EVEN_CLASSES = false;
        assertFalse(PvpMatchups.on(config));
    }

    @Test
    void theGearEdgeIsTheGearRatioToTheExponent() {
        PvpConfig config = new PvpConfig();
        config.PVP_GEAR_EXPONENT = 0.7d;
        // a Crusader at Lv 100 whose band's median gear base is 1,000: 1,220 is warriors' 1.22x of check run 24
        PvpReference reference = PvpReference.parse(List.of("gear_base:", "  \"11-10\": 1000"));
        PvpDamageModel.Attacker attacker = new PvpDamageModel.Attacker(100, 111, 1220L, false);
        assertEquals(1.22d, PvpDamageModel.gearRatio(attacker, reference), 1e-12);
        assertEquals(Math.pow(1.22d, 0.7d), PvpDamageModel.gearEdge(attacker, reference, config), 1e-12);
        PvpDamageModel.Attacker unknown = new PvpDamageModel.Attacker(100, 511, 1220L, false);
        assertEquals(1d, PvpDamageModel.gearEdge(unknown, reference, config), 1e-12, "no median: no edge");
    }
}

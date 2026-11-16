package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learned matchups (2026-11-13): a class pair's pace gap, split between its two sides, bounded and persisted. */
class PvpMatchupsTest {
    private static final String TITLE = "# matchups";
    /** Every test fighter is a new character, so no two test duels share a pairing unless a test says so. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    @TempDir
    Path dir;

    private final PvpPairings counted = new PvpPairings();
    private long now = 1_000_000L;

    private PvpRateLearner learner() {
        return new PvpRateLearner(dir.resolve("learned-matchups.txt"), TITLE);
    }

    /** One duel, a second after the last. */
    private boolean observe(PvpRateLearner learner, List<DuelLog.Row> rows, PvpConfig config) {
        now += 1_000L;
        return PvpMatchups.observeAll(learner, rows, config, counted, now);
    }

    @Test
    void aPairingTeachesOnceAnHourHoweverOftenItIsFought() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        List<DuelLog.Row> sameTwo = warriorAgainstMagician(0.071, 0.085);
        assertTrue(observe(learner, sameTwo, config));
        for (int i = 0; i < 9; i++) {
            assertFalse(observe(learner, sameTwo, config), "repeat " + (i + 1) + " within the hour");
        }
        assertEquals(1, learner.mean("p12").fighters());
        now += config.PVP_PAIRING_REPEAT_MINUTES * 60_000L;
        assertTrue(observe(learner, sameTwo, config), "an hour on, it counts again");
        assertTrue(observe(learner, warriorAgainstMagician(0.071, 0.085), config), "two other citizens count at once");
        assertEquals(3, learner.mean("p12").fighters());
    }

    @Test
    void practiceSkipsAPartnerFoughtWithinTheWindowAndOtherwiseTakesTheNearestLevel() {
        record Bot(int id, int level) { }
        PvpPairings played = new PvpPairings();
        Bot a = new Bot(1, 80);
        Bot near = new Bot(2, 81);
        Bot farther = new Bot(3, 83);
        Bot outOfReach = new Bot(4, 90);
        List<Bot> free = List.of(near, farther, outOfReach);
        long hour = 60 * 60_000L;
        assertEquals(near, played.partner(a, free, Bot::level, Bot::id, 5, 0L, hour));
        played.noteDuel(List.of(fighterWithId(1, 1), fighterWithId(2, 2)), 0L);
        assertEquals(farther, played.partner(a, free, Bot::level, Bot::id, 5, 10 * 60_000L, hour), "kept apart");
        assertEquals(near, played.partner(a, free, Bot::level, Bot::id, 5, hour + 1, hour), "an hour on");
        assertEquals(near, played.partner(a, free, Bot::level, Bot::id, 5, 10 * 60_000L, 0L), "0: no limit");
        assertEquals(null, played.partner(a, List.of(near, outOfReach), Bot::level, Bot::id, 5, 10 * 60_000L, hour));
    }

    private static DuelLog.Row fighterWithId(int charId, int side) {
        return new DuelLog.Row(0L, 1L, 100000000, 15L, "knock-out", 1, "f" + charId, charId, 111, 80, false, side,
                100L, false, false, new DuelStats(), 0L, 0L, 12d, 0d, 0d, 0d, 0d, 0d);
    }

    /** A fighter who really fought: 12 s engaged, 20 attack packets, every think found a skill. */
    private static DuelLog.Row fighter(int jobId, int side, boolean left, double pace) {
        DuelStats stats = new DuelStats();
        stats.packets.set(20);
        stats.thinks.set(40);
        return new DuelLog.Row(0L, 1L, 100000000, 15L, "knock-out", 1, "f" + side, NEXT_ID.incrementAndGet(), jobId,
                100, false, side, 100L,
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
            assertTrue(observe(learner, warriorAgainstMagician(0.071, 0.085), config));
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
            observe(learner, warriorAgainstMagician(0.05, 0.10), config);
        }
        assertEquals(1d, PvpMatchups.multiplier(learner, 1, 2), 1e-9);
        observe(learner, warriorAgainstMagician(0.05, 0.10), config);
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
            observe(learner, List.of(fighter(211, 2, false, 0.085), fighter(111, 1, false, 0.071)),
                    config);
        }
        assertTrue(PvpMatchups.multiplier(learner, 1, 2) > 1d, "the slower warrior's hits still go up");
        assertTrue(PvpMatchups.multiplier(learner, 2, 1) < 1d);
    }

    @Test
    void onlyOneAgainstOneDuelsOfTwoClassesWhereBothFoughtTeach() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        assertFalse(observe(learner, List.of(fighter(111, 1, false, 0.07),
                fighter(121, 2, false, 0.09)), config), "two warriors");
        assertFalse(observe(learner, List.of(fighter(111, 1, false, 0.07),
                fighter(211, 2, true, 0.09)), config), "one side left");
        assertFalse(observe(learner, List.of(fighter(111, 1, false, 0.07),
                fighter(211, 2, false, 0.09), fighter(311, 2, false, 0.09)), config), "two against one");
        assertFalse(observe(learner, List.of(fighter(0, 1, false, 0.07),
                fighter(211, 2, false, 0.09)), config), "a beginner");
        DuelLog.Row starved = fighter(411, 1, false, 0.0);
        starved.stats().noSkillThinks.set(30);   // an Assassin without stars
        assertFalse(observe(learner, List.of(starved, fighter(211, 2, false, 0.09)), config),
                "a side that found no usable attack");
        assertTrue(learner.means().isEmpty());
    }

    @Test
    void aSideThatNeverLandedIsClippedNotInfinite() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        assertTrue(observe(learner, warriorAgainstMagician(0.0, 0.10), config));
        double mean = learner.mean("p12").logMean();
        assertTrue(Double.isFinite(mean) && mean < 0d, "mean " + mean);
        assertEquals(Double.NaN, PvpMatchups.observed(0d, 0d));
    }

    @Test
    void theMeansArePersistedAndReadBack() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 10; i++) {
            observe(learner, warriorAgainstMagician(0.071, 0.085), config);
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
        assertEquals(1.22d, PvpDamageModel.gearRatio(attacker, reference.gearMedian(111, 100)), 1e-12);
        assertEquals(Math.pow(1.22d, 0.7d), PvpDamageModel.gearEdge(attacker, reference, config), 1e-12);
        PvpDamageModel.Attacker unknown = new PvpDamageModel.Attacker(100, 511, 1220L, false);
        assertEquals(1d, PvpDamageModel.gearEdge(unknown, reference, config), 1e-12, "no median: no edge");
    }
}

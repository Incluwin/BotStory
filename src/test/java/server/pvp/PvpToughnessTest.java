package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learned toughness (2026-11-11): what each class takes against a reference victim, bounded and persisted. */
class PvpToughnessTest {
    private static final String TITLE = "# toughness";

    @TempDir
    Path dir;

    private PvpRateLearner learner() {
        return new PvpRateLearner(dir.resolve("learned-toughness.txt"), TITLE);
    }

    private static DuelLog.Row victim(int jobId, int level, boolean left, double neutral, double bare) {
        return new DuelLog.Row(0L, 1L, 100000000, 15L, "knock-out", 1, "v", 7, jobId, level, false, 2, 100L, true, left,
                new DuelStats(), 0L, 0L, 12d, neutral, bare);
    }

    @Test
    void aHitCountsWhatItWouldTakeOfAReferenceVictimOfTheVictimsLevel() {
        // 1,000 raw at a 0.01 factor, 2% level edge, the victim's reference HP 5,100 over the attacker's 5,000
        assertEquals(1000 * 0.01 * 1.02 * 1.02 / 5_100d,
                PvpToughness.neutralShare(1000, 0.01, 1.02, 1.02, 5_100d), 1e-12);
        assertEquals(0d, PvpToughness.neutralShare(1000, 0.01, 1d, 1d, 0d), 1e-12);   // no reference HP: nothing
    }

    @Test
    void aVictimReportsWhatAReferenceVictimWouldHaveLostOverWhatItLost() {
        assertEquals(Math.log(1.5), PvpToughness.observed(1.2, 0.8), 1e-12);
        assertEquals(Math.log(PvpRateLearner.MAX_EFFICIENCY), PvpToughness.observed(2.0, 0.1), 1e-12);   // clipped
        assertTrue(Double.isNaN(PvpToughness.observed(PvpToughness.MIN_NEUTRAL_SHARE - 0.01, 0.3)));      // too few hits
        assertTrue(Double.isNaN(PvpToughness.observed(1.2, 0d)));                                         // all dodged
    }

    @Test
    void aClassThatTakesLessThanAReferenceVictimLearnsToTakeMore() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        List<DuelLog.Row> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(victim(230, 85, false, 1.5, 1.0));   // a Priest that dodges and defends a third beyond the tables
        }
        assertEquals(60, PvpToughness.observeAll(learner, rows, config));

        double magicians = learner.multiplier(210, 85, config);   // a band it never heard: its class's
        assertTrue(magicians > 1.40 && magicians < 1.55, "class toughness " + magicians);
        assertEquals(1d, learner.multiplier(112, 85, config), 1e-9);   // warriors heard nothing
    }

    @Test
    void aVictimWhoLeftOrMetTooFewHitsTeachesNothing() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        List<DuelLog.Row> rows = List.of(victim(112, 85, true, 1.5, 1.0), victim(112, 85, false, 0.2, 0.1),
                victim(0, 85, false, 1.5, 1.0));   // a beginner has no class
        assertEquals(0, PvpToughness.observeAll(learner, rows, config));
        assertTrue(learner.means().isEmpty());
        assertFalse(dir.resolve("learned-toughness.txt").toFile().exists());
    }

    @Test
    void learnedToughnessSurvivesARestart() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        List<DuelLog.Row> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(victim(112, 85, false, 0.9, 1.2));   // warriors that go down faster than a reference victim
        }
        PvpToughness.observeAll(learner, rows, config);
        PvpRateLearner reloaded = learner();
        reloaded.load();

        double before = learner.multiplier(112, 85, config);
        assertTrue(before < 1d, "warrior toughness " + before);
        assertEquals(before, reloaded.multiplier(112, 85, config), 1e-6);
    }
}

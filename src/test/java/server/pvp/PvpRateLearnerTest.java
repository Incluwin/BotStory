package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PD16: learned rates follow the fighters' measured raw damage, bounded and persisted. */
class PvpRateLearnerTest {
    /** A Lv 9x Fighter path (11-9) and Spearman path (13-9) at gear median 2,000; warriors deal 3 per point of base. */
    private static final PvpReference REFERENCE = PvpReference.parse(List.of(
            "gear_base:", "  \"11-9\": 2000", "  \"13-9\": 2000",
            "class_dps_per_base:", "  1: 3"));
    private static final double PRIOR = 2000 * 3d;

    @TempDir
    Path dir;

    private PvpRateLearner learner() {
        return new PvpRateLearner(dir.resolve("learned-rates.txt"));
    }

    @Test
    void aClassThatDealsLessThanItsRateLearnsALowerRate() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 60; i++) {
            assertTrue(learner.observe(112, 95, 2000, PRIOR * 0.7, REFERENCE, config));
        }
        double multiplier = learner.multiplier(130, 95, config);   // a band it never heard: the class's rate
        assertTrue(multiplier > 0.69 && multiplier < 0.75, "class multiplier " + multiplier);
    }

    @Test
    void theTableStandsUntilAClassHasHeardEnoughFighters() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        learner.observe(112, 95, 2000, PRIOR * 0.5, REFERENCE, config);
        learner.observe(112, 95, 2000, PRIOR * 0.5, REFERENCE, config);

        assertEquals(1d, learner.multiplier(112, 95, config), 1e-9);
    }

    @Test
    void aBandUsesItsOwnRateOnceItHasEnoughFightersAndItsClassesUntilThen() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < config.PVP_LEARN_JOB_MIN_FIGHTERS + 4; i++) {
            learner.observe(112, 95, 2000, PRIOR * 0.6, REFERENCE, config);   // 11-9
        }
        learner.observe(132, 95, 2000, PRIOR * 1.4, REFERENCE, config);       // 13-9, one fighter
        double fighters = learner.multiplier(112, 95, config);
        double spearmen = learner.multiplier(132, 95, config);
        PvpRateLearner.Mean warriors = learner.means().get("c1");

        assertTrue(fighters < warriors.multiplier() - 0.05, "11-9 has its own, lower rate: " + fighters);
        assertEquals(warriors.multiplier(), spearmen, 1e-9, "13-9 has one fighter: the class's rate stands in");
    }

    /** A few broken bots pull a plain mean below the typical fighter; the capped step stays near the median. */
    @Test
    void aLowTailBarelyMovesTheLearnedRate() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 100; i++) {
            double efficiency = i % 5 == 0 ? 0.34 : 0.9;   // one in five never got its skills
            learner.observe(112, 95, 2000, PRIOR * efficiency, REFERENCE, config);
        }
        double multiplier = learner.multiplier(130, 95, config);
        double plainLogMean = Math.exp(0.2 * Math.log(0.34) + 0.8 * Math.log(0.9));   // about 0.73
        assertTrue(multiplier > plainLogMean + 0.05 && multiplier < 0.92,
                "near the typical 0.9, not the plain mean " + plainLogMean + ": " + multiplier);
    }

    @Test
    void ownGearIsTakenOutBeforeTheEfficiency() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 60; i++) {
            learner.observe(112, 95, 4000, PRIOR * 2, REFERENCE, config);   // twice the gear, twice the damage
        }
        assertEquals(1d, learner.multiplier(112, 95, config), 0.01);
    }

    @Test
    void oneFighterIsClippedAndTheLearnedRateIsBounded() {
        PvpRateLearner learner = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 200; i++) {
            learner.observe(112, 95, 2000, PRIOR * 0.001, REFERENCE, config);   // a bot that never got its skills
        }
        // each fighter counts as a third at worst (the clip), and the mean stops at the bound, 0.4
        assertEquals(PvpRateLearner.MIN_MULTIPLIER, learner.multiplier(112, 95, config), 1e-3);
        assertTrue(Math.log(PvpRateLearner.MIN_EFFICIENCY) - 1e-3 <= learner.means().get("11-9").logMean(),
                "clipped to a third, not a thousandth");
        assertFalse(learner.observe(0, 95, 2000, PRIOR, REFERENCE, config), "a Beginner has no class");
        assertFalse(learner.observe(112, 95, 0, PRIOR, REFERENCE, config), "no gear, no rate");
        assertFalse(learner.observe(112, 95, 2000, 0, REFERENCE, config), "no damage, nothing to learn");
    }

    @Test
    void whatWasLearnedSurvivesARestart() {
        PvpRateLearner first = learner();
        PvpConfig config = new PvpConfig();
        for (int i = 0; i < 20; i++) {
            first.observe(112, 95, 2000, PRIOR * 0.8, REFERENCE, config);
        }
        first.save();
        PvpRateLearner second = learner();
        second.load();

        assertEquals(first.multiplier(112, 95, config), second.multiplier(112, 95, config), 1e-5);
        assertEquals(first.means().get("11-9").fighters(), second.means().get("11-9").fighters());
        second.reset();
        assertEquals(1d, second.multiplier(112, 95, config), 1e-9);
    }

    @Test
    void aReplayedCsvCountsFightersWhoFoughtAndSkipsTheRest() {
        PvpRateLearner learner = learner();
        String header = DuelLog.HEADER;
        // endedAt,duel,map,seconds,reason,winner,name,charId,jobId,level,kind,side,gearBase,ko,left,thinks,buffs,sent,
        // deferred,refused,noSkill,packets,whiffs,dodged,hits,raw,dealt,taken,engaged,topRefusal
        String fought = "1,1,0,30,x,0,A,1,112,95,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,126000,0,0,30.0,-";
        String human = "1,1,0,30,x,0,B,2,112,95,H,1,2000,0,0,1,0,1,0,0,0,20,0,0,20,126000,0,0,30.0,-";
        String left = "1,2,0,30,x,0,C,3,112,95,B,0,2000,0,1,1,0,1,0,0,0,20,0,0,20,126000,0,0,30.0,-";
        String brief = "1,3,0,5,x,0,D,4,112,95,B,0,2000,0,0,1,0,1,0,0,0,20,0,0,20,126000,0,0,5.0,-";

        int counted = learner.replay(List.of(header, fought, human, left, brief), REFERENCE, new PvpConfig());

        assertEquals(2, counted, "the bot and the player who fought; not the one who left nor the brief one");
        assertEquals(2, learner.means().get("c1").fighters());
    }
}

package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpHitBalanceTest {
    private static final int WARRIOR = 112;
    private static final int THIEF = 412;
    private static final int BOWMAN = 312;

    /** Three of a class at Lv 75, all with the same accuracy and avoid. */
    private static void add(List<PvpHitBalance.Sample> samples, int jobClass, int accuracy, int avoid) {
        for (int i = 0; i < 3; i++) {
            samples.add(new PvpHitBalance.Sample(75, jobClass, accuracy, avoid));
        }
    }

    /** Round eight's shape: a warrior's accuracy and avoid far under a thief's, a bowman's in between. */
    private static PvpHitBalance roundEightShape() {
        List<PvpHitBalance.Sample> samples = new ArrayList<>();
        add(samples, 1, 100, 40);
        add(samples, 4, 200, 160);
        add(samples, 3, 180, 100);
        return PvpHitBalance.build(samples);
    }

    @Test
    void medianFighterOfEveryClassGetsTheBandMedian() {
        PvpHitBalance balance = roundEightShape();
        double accuracy = (100 + 200 + 180) / 3d;
        double avoid = (40 + 160 + 100) / 3d;
        assertEquals(accuracy, 100 * balance.accuracyFactor(WARRIOR, 75), 1e-9);
        assertEquals(accuracy, 200 * balance.accuracyFactor(THIEF, 75), 1e-9);
        assertEquals(accuracy, 180 * balance.accuracyFactor(BOWMAN, 75), 1e-9);
        assertEquals(avoid, 40 * balance.avoidFactor(WARRIOR, 75), 1e-9);
        assertEquals(avoid, 160 * balance.avoidFactor(THIEF, 75), 1e-9);
    }

    @Test
    void medianWarriorAndMedianThiefHitEachOtherAlike() {
        PvpHitBalance balance = roundEightShape();
        PvpConfig config = new PvpConfig();
        double warriorOnThief = PvpDamageModel.hitChance(false,
                (int) Math.round(100 * balance.accuracyFactor(WARRIOR, 75)), 75, 75,
                (int) Math.round(160 * balance.avoidFactor(THIEF, 75)), config);
        double thiefOnWarrior = PvpDamageModel.hitChance(false,
                (int) Math.round(200 * balance.accuracyFactor(THIEF, 75)), 75, 75,
                (int) Math.round(40 * balance.avoidFactor(WARRIOR, 75)), config);
        double rawWarriorOnThief = PvpDamageModel.hitChance(false, 100, 75, 75, 160, config);
        assertEquals(warriorOnThief, thiefOnWarrior, 1e-9);
        assertTrue(rawWarriorOnThief < warriorOnThief, "evening lifts the warrior off the miss cap");
    }

    @Test
    void avoidAboveTheClassMedianStillDodgesMore() {
        PvpHitBalance balance = roundEightShape();
        double median = 160 * balance.avoidFactor(THIEF, 75);
        double geared = 240 * balance.avoidFactor(THIEF, 75);
        assertEquals(1.5d, geared / median, 1e-9);
    }

    @Test
    void thinClassAndBandUsesItsNearestBandRatio() {
        List<PvpHitBalance.Sample> samples = new ArrayList<>();
        add(samples, 1, 100, 40);
        add(samples, 4, 200, 160);
        // Lv 95: two warriors only, under MIN_SAMPLES, so the band has no ratios of its own
        samples.add(new PvpHitBalance.Sample(95, 1, 300, 300));
        samples.add(new PvpHitBalance.Sample(95, 1, 300, 300));
        PvpHitBalance balance = PvpHitBalance.build(samples);
        assertEquals(balance.accuracyFactor(WARRIOR, 75), balance.accuracyFactor(WARRIOR, 95), 1e-9);
        assertEquals(balance.avoidFactor(THIEF, 75), balance.avoidFactor(THIEF, 35), 1e-9);
    }

    @Test
    void beginnersUnknownClassesAndEmptyDataStayAtOne() {
        PvpHitBalance empty = PvpHitBalance.build(List.of());
        assertEquals(1d, empty.accuracyFactor(WARRIOR, 75));
        assertEquals(1d, empty.avoidFactor(THIEF, 75));
        PvpHitBalance balance = roundEightShape();
        assertEquals(1d, balance.accuracyFactor(0, 75));
        assertEquals(1d, balance.avoidFactor(500 + 12, 75), "pirates absent from the data");
    }

    private static final int MAGICIAN = 212;

    /** Three of a class at Lv 75 with this WDEF and MDEF (the same accuracy and avoid for all). */
    private static void addDefense(List<PvpHitBalance.Sample> samples, int jobClass, int wdef, int mdef) {
        for (int i = 0; i < 3; i++) {
            samples.add(new PvpHitBalance.Sample(75, jobClass, 150, 100, wdef, mdef));
        }
    }

    /** Check run 14's shape: WDEF far above MDEF on most gear, a warrior's WDEF the most, a magician's MDEF. */
    private static PvpHitBalance defenseShape() {
        List<PvpHitBalance.Sample> samples = new ArrayList<>();
        addDefense(samples, 1, 900, 150);
        addDefense(samples, 2, 250, 500);
        addDefense(samples, 4, 400, 200);
        return PvpHitBalance.build(samples);
    }

    /** Check run 14 (2026-11-10): spells met far less MDEF than weapons met WDEF, and magicians won 68%. */
    @Test
    void aMedianVictimMeetsSpellsAndWeaponHitsWithOneDefense() {
        PvpHitBalance balance = defenseShape();
        double common = ((900 + 250 + 400) / 3d + (150 + 500 + 200) / 3d) / 2d;   // 400
        assertEquals(common, 900 * balance.wdefFactor(WARRIOR, 75), 1e-9);
        assertEquals(common, 150 * balance.mdefFactor(WARRIOR, 75), 1e-9);
        assertEquals(common, 250 * balance.wdefFactor(MAGICIAN, 75), 1e-9);
        assertEquals(common, 500 * balance.mdefFactor(MAGICIAN, 75), 1e-9);
        assertEquals(common, 400 * balance.wdefFactor(THIEF, 75), 1e-9);
        assertEquals(common, 200 * balance.mdefFactor(THIEF, 75), 1e-9);
    }

    @Test
    void aSpellAndAWeaponHitTakeTheSameCutFromAMedianVictim() {
        PvpHitBalance balance = defenseShape();
        PvpConfig config = new PvpConfig();
        double weapon = PvpDamageModel.defenseFactor(
                (int) Math.round(900 * balance.wdefFactor(WARRIOR, 75)), 75, config);
        double spell = PvpDamageModel.defenseFactor(
                (int) Math.round(150 * balance.mdefFactor(WARRIOR, 75)), 75, config);
        assertEquals(weapon, spell, 1e-9);
        assertTrue(PvpDamageModel.defenseFactor(900, 75, config) < PvpDamageModel.defenseFactor(150, 75, config),
                "unevened, the spell kept more");
    }

    @Test
    void defenseAboveTheClassMedianStillCutsMore() {
        PvpHitBalance balance = defenseShape();
        assertEquals(1200d / 900d, 1200 * balance.wdefFactor(WARRIOR, 75) / (900 * balance.wdefFactor(WARRIOR, 75)),
                1e-9);
    }

    @Test
    void samplesWithoutDefenseLeaveDefenseAlone() {
        PvpHitBalance balance = roundEightShape();
        assertEquals(1d, balance.wdefFactor(WARRIOR, 75));
        assertEquals(1d, balance.mdefFactor(THIEF, 75));
    }

    @Test
    void factorsAreClamped() {
        List<PvpHitBalance.Sample> samples = new ArrayList<>();
        add(samples, 1, 10, 1);
        add(samples, 4, 1000, 500);
        PvpHitBalance balance = PvpHitBalance.build(samples);
        assertEquals(PvpHitBalance.MAX_FACTOR, balance.accuracyFactor(WARRIOR, 75), 1e-9);
        assertEquals(PvpHitBalance.MAX_FACTOR, balance.avoidFactor(WARRIOR, 75), 1e-9);
        assertTrue(balance.accuracyFactor(THIEF, 75) >= PvpHitBalance.MIN_FACTOR);
    }
}

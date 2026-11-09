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

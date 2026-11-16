package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import server.pvp.probe.PvpCalibration;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live gear medians (2026-11-13): typical gear from the characters online, and the coefficient set for it. */
class PvpGearMediansTest {
    /** Crusaders (11x) at Lv 100s with a reference median of 1,000, F/P mages (21x) of 30, and a measured rate. */
    private static final PvpReference REFERENCE = PvpReference.parse(List.of(
            "h_ref:", "  100: 5000",
            "gear_base:", "  \"11-10\": 1000", "  \"12-10\": 1000", "  \"21-10\": 30",
            "d_job:", "  \"11-10\": 3000"));

    private static PvpCalibration.Sample crusader(long gearBase) {
        return new PvpCalibration.Sample(105, 111, 9000, gearBase);
    }

    @Test
    void aJobAndBandWithThreeCharactersOnlineUsesItsOwnMedian() {
        PvpGearMedians gear = PvpGearMedians.build(List.of(crusader(1100), crusader(1300), crusader(1200)), REFERENCE);
        assertEquals(1200L, gear.median(111, 105, REFERENCE));
    }

    @Test
    void aThinJobAndBandUsesTheReferenceTimesItsClasssLiveRatio() {
        List<PvpCalibration.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            samples.add(crusader(1200));                       // Crusaders carry 1.2x their reference median
        }
        samples.add(new PvpCalibration.Sample(104, 121, 9000, 1500));   // one White Knight: too few for its own
        PvpGearMedians gear = PvpGearMedians.build(samples, REFERENCE);
        // the warrior class's live ratio (median 1.2x over the six) applied to the White Knights' reference median
        assertEquals(1200L, gear.median(121, 104, REFERENCE));
        // a class nobody online plays keeps the reference median
        assertEquals(30L, gear.median(211, 105, REFERENCE));
        // no reference and no characters: none
        assertEquals(-1L, gear.median(511, 105, REFERENCE));
    }

    @Test
    void aTypicalAttackerAsGearedTodayHitsAtTheReferencePace() {
        PvpConfig config = new PvpConfig();
        config.PVP_LEARN_RATES = false;
        config.PVP_GEAR_EXPONENT = 0.7d;
        PvpDamageModel.Attacker typical = new PvpDamageModel.Attacker(105, 111, 1200L, false);
        double rate = PvpDamageModel.rate(typical, REFERENCE, config);   // 3,000 a second at the reference median
        double expectedRaw = rate * 1200d / 1000d;                       // it rolls what its gear gives
        double target = REFERENCE.hRef(105) / config.PVP_TTK_SECONDS;

        double live = PvpDamageModel.coefficient(typical, REFERENCE, config, 1200L) * expectedRaw;
        assertEquals(target, live, target * 1e-9, "measured against today's median: the reference pace");

        double stale = PvpDamageModel.coefficient(typical, REFERENCE, config, 1000L) * expectedRaw;
        assertEquals(target * Math.pow(1.2d, 0.7d), stale, target * 1e-9, "against the old median: 1.2^0.7 more");
    }

    @Test
    void gearAboveTodaysMedianStillHelps() {
        PvpConfig config = new PvpConfig();
        config.PVP_LEARN_RATES = false;
        config.PVP_GEAR_EXPONENT = 0.7d;
        PvpDamageModel.Attacker geared = new PvpDamageModel.Attacker(105, 111, 1500L, false);
        double rate = PvpDamageModel.rate(geared, REFERENCE, config);
        double damage = PvpDamageModel.coefficient(geared, REFERENCE, config, 1200L) * rate * 1500d / 1000d;
        double target = REFERENCE.hRef(105) / config.PVP_TTK_SECONDS;
        assertEquals(target * Math.pow(1.25d, 0.7d), damage, target * 1e-9);
        assertTrue(PvpDamageModel.gearRatio(geared, 1200L) > 1d);
    }

    @Test
    void offTheReferenceMedianStands() {
        PvpConfig config = new PvpConfig();
        assertEquals(false, config.PVP_LIVE_GEAR_MEDIANS);
        PvpDamageModel.Attacker attacker = new PvpDamageModel.Attacker(105, 111, 1200L, false);
        assertEquals(1000L, PvpDamageModel.typicalGear(attacker, REFERENCE, config));
        assertEquals(1d, PvpDamageModel.typicalShare(attacker, REFERENCE, 1000L), 1e-12);
    }
}

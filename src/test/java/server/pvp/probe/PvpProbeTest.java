package server.pvp.probe;

import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpProbeTest {
    @Test
    void gapStatsReadMovementCadence() {
        GapStats stats = GapStats.of(List.of(0L, 100L, 600L, 1100L, 3100L));

        assertEquals(4, stats.gaps());
        assertEquals(500, stats.medianMs());
        assertEquals(2000, stats.p90Ms());
        assertEquals(2000, stats.maxMs());
        assertEquals(775, stats.meanMs());
        assertEquals(1, stats.overOneSecond());
        assertEquals(0, GapStats.of(List.of(5L)).gaps());
    }

    @Test
    void meterCreditsEachAttackOverTheTimeToTheNextAndSkipsWalkingGaps() {
        DamageMeter meter = new DamageMeter();
        meter.record(1, 112, 125, false, 9999, 0); // not running: ignored
        meter.start();
        long t = 0;
        for (int i = 0; i < 100; i++) {          // 100 attacks of 1,000 every 800 ms: 99 engaged gaps
            meter.record(1, 112, 125, false, 1000, t);
            t += 800;
        }
        t += 10_000;                               // walking to the next mob: neither the gap nor the last hit count
        meter.record(1, 112, 125, false, 50_000, t);
        meter.record(2, 112, 125, false, 1000, 0); // too little engaged time for the report

        List<DamageMeter.Row> rows = meter.report();

        assertEquals(1, rows.size());
        DamageMeter.Row row = rows.get(0);
        assertEquals(11, row.jobPath());
        assertEquals(12, row.band());
        assertEquals(1250, row.medianDps());       // 99 x 1,000 over 99 x 0.8 s
        assertEquals(1, row.characters());
    }

    @Test
    void bestTargetCountsOneTargetOfAnAoe() {
        AttackInfo attack = new AttackInfo();
        Map<Integer, AttackTarget> targets = new HashMap<>();
        targets.put(1, new AttackTarget((short) 0, List.of(100, 200)));
        targets.put(2, new AttackTarget((short) 0, List.of(250, 0, -5)));
        attack.targets = targets;

        assertEquals(300, PvpProbe.bestTarget(attack));
        attack.targets = new HashMap<>();
        assertEquals(0, PvpProbe.bestTarget(attack));
    }

    @Test
    void referenceHpIsClassBalanced() {
        List<PvpCalibration.Sample> samples = List.of(
                new PvpCalibration.Sample(75, 112, 7000, 1000),  // warriors: median 7,000
                new PvpCalibration.Sample(76, 111, 7000, 1100),
                new PvpCalibration.Sample(77, 110, 9000, 1200),
                new PvpCalibration.Sample(78, 212, 2000, 20),    // one mage
                new PvpCalibration.Sample(79, 0, 50_000, 10),    // a beginner: left out
                new PvpCalibration.Sample(81, 312, 4000, 900));  // another band

        List<PvpCalibration.Band> bands = PvpCalibration.referenceHp(samples);

        assertEquals(2, bands.size());
        assertEquals(7, bands.get(0).band());
        assertEquals(4500, bands.get(0).hRef());          // (7,000 + 2,000) / 2, not the warrior-heavy 7,000
        assertEquals(4, bands.get(0).samples());
        Map<String, Long> gear = PvpCalibration.gearMedians(samples);
        assertEquals(1100L, gear.get("11-7"));
        assertTrue(gear.containsKey("21-7"));
    }
}

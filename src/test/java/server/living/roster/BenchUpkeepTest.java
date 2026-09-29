package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.bond.BondService;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;

/**
 * Decision 19 (b), retuned in P5: bench upkeep (30 meso per level an hour, a hired hand's hourly wage) never takes more
 * than BENCH_UPKEEP_MAX_SHARE (15%) of the meso an hour the companion is predicted to pick up at its camp. At the
 * ledger's prior that share is never reached; a camp measured to pay less lowers the upkeep with it; the ledger charges
 * exactly that, and the picker's rates show it.
 */
class BenchUpkeepTest {
    private static final int CAMPS = 104040000;

    private final BenchHarness game = new BenchHarness();
    private final BenchLedger ledger;

    BenchUpkeepTest() {
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        ledger = new BenchLedger(game.world, mapId -> "Ant Tunnel I", itemId -> "item " + itemId);
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void upkeepStaysUnderFifteenPercentOfWhatTypicalCampsArePredictedToPay() {
        // {level, job id, meso an hour its camp measured (-1: never hunted), hunts measured}: a fresh Lv10 at a new
        // camp, poor Lv15-20 camps (Pig Beach, Ant Tunnel for a class that drops little), a fair Lv30 one, rich Lv50-70 ones
        int[][] camps = {{10, 0, -1, 0}, {15, 100, 1_600, 8}, {20, 300, 2_000, 12}, {30, 410, 6_000, 8}, {50, 212, 25_000, 20},
                {70, 312, 60_000, 5}};
        long[] expected = {300L, 362L, 414L, 900L, 1_500L, 2_100L};
        for (int i = 0; i < camps.length; i++) {
            int level = camps[i][0];
            int jobId = camps[i][1];
            int camp = CAMPS + level;
            BenchYieldModel.Key key = BenchYieldModel.Key.of(jobId, level, camp);
            for (int hunt = 0; hunt < camps[i][3]; hunt++) {
                game.yields.record(key, new BenchYieldModel.Sample(0.25d, 0L, camps[i][2] / 4, 0L, 0));
            }
            double predicted = game.yields.mesoPerHour(key, ledger.mesoPrior(level));

            double upkeep = ledger.upkeepPerHour(level, jobId, camp);

            assertTrue(upkeep <= 0.15d * predicted + 1e-6, "Lv" + level + ": " + upkeep + " of " + predicted + " meso/h");
            assertTrue(upkeep <= BenchLedger.upkeepPerHour(level), "never more than 30 a level");
            assertEquals(expected[i], Math.round(upkeep), "Lv" + level + " at " + Math.round(predicted) + " meso/h");
        }
    }

    @Test
    void atThePriorThirtyALevelIsAboutFivePercent() {
        for (int level : new int[] {1, 10, 30, 70, 120}) {
            double share = ledger.upkeepPerHour(level, 0, 0) / ledger.mesoPrior(level);
            assertEquals(BenchLedger.upkeepPerHour(level), ledger.upkeepPerHour(level, 0, 0), 1e-9, "Lv" + level);
            assertEquals(30.0d / 594.0d, share, 1e-9, "Lv" + level);
        }
    }

    @Test
    void theLedgerChargesTheCappedUpkeepAndTheReportTellsWhatTheHourFarmed() {
        game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO); // Lv10, no job yet (-1), camping at FIELD
        BenchYieldModel.Key key = BenchYieldModel.Key.of(-1, 10, FIELD);
        for (int hunt = 0; hunt < 20; hunt++) {
            game.yields.record(key, new BenchYieldModel.Sample(0.25d, 0L, 250L, 0L, 0)); // 1,000 meso/h measured
        }
        double predicted = game.yields.mesoPerHour(key, ledger.mesoPrior(10));
        RosterEntry row = game.row(ANN, "brin");
        assertEquals(Math.round(0.15d * predicted), Math.round(ledger.upkeepPerHour(row)), "the row's own upkeep, at its camp");

        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        game.store.seed(new RosterEntry(ANN, "brin", RosterState.BENCHED, Assignment.FARM_MESO, 0, 0, FIELD, 0L, 0L, 0L,
                row.recruitedAtMs(), hourAgo, hourAgo, 0, null));
        ledger.tick();

        long net = game.row(ANN, "brin").haulMeso(); // the hour's meso less its upkeep
        assertEquals(0.85d * predicted, net, 3.0d, "farmed " + predicted + ", 15% of it went to upkeep (not 300)");
        assertEquals("farmed " + BenchReport.meso(Math.round(predicted)) + " meso at ant tunnel i.",
                ledger.takeReport(ANN, "brin", 10));
    }

    @Test
    void thePickerRatesAreTheCampsMeasuredRatesWithTheirHunts() {
        game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO);
        server.living.config.Content.Persona brin = game.content.persona("brin");
        BenchYieldModel.Key key = BenchYieldModel.Key.of(300, 20, FIELD);
        double priorExp = ledger.expPrior(20);
        for (int hunt = 0; hunt < 7; hunt++) {
            game.yields.record(key, new BenchYieldModel.Sample(0.25d, Math.round(priorExp * 0.25d * 1.5d), 1_000L, 0L, 0));
        }

        BenchLedger.Rates train = ledger.rates(brin, null, 20, 300, 25, Assignment.TRAIN, FIELD);
        BenchLedger.Rates farm = ledger.rates(brin, null, 20, 300, 25, Assignment.FARM_MESO, FIELD);
        BenchLedger.Rates fresh = ledger.rates(brin, null, 20, 300, 25, Assignment.TRAIN, FIELD + 1);

        assertEquals(7.0d, train.hunts(), 1e-9);
        assertEquals(fresh.expPercent() * (7 * 1.5d + 1) / 8, train.expPercent(), 0.05d, "7 hunts at 1.5x the prior, shrunk");
        assertEquals(Math.round((7 * 4_000.0d + ledger.mesoPrior(20)) / 8), farm.meso());
        assertEquals(Math.round(Math.min(600.0d, 0.15d * farm.meso())), farm.upkeep(), 1.0d);
        assertEquals(0.0d, fresh.hunts(), 1e-9, "a camp nobody hunted: the priors");
        assertEquals(ledger.rates(brin, null, 20, 25, Assignment.TRAIN).expPercent(), fresh.expPercent(), 1e-9);
    }
}

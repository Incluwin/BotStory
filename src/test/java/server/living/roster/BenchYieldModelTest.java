package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.bond.BondService;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.living.store.LivingStore.YieldRow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;

/**
 * BenchYieldModel (docs/BOT_MANAGEMENT_PLAN.md P5, "BenchYieldModel / BenchDutyChooser", changeset 42): live slices
 * are recorded per job branch, level band and camp, each weighted by the share of a full slice it ran, and a rate is
 * predicted as the measured one shrunk toward the ledger's 0.6-efficiency prior, closer to the measurement the more
 * slices ran; the rows survive a restart; the ledger credits the prediction.
 */
class BenchYieldModelTest {
    private static final BenchYieldModel.Key CAMP = BenchYieldModel.Key.of(110, 24, FIELD);

    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aKeyIsTheClassLineTheLevelBandAndTheCamp() {
        assertEquals(new BenchYieldModel.Key("WARRIOR", 2, FIELD), CAMP);
        assertEquals("BEGINNER", BenchYieldModel.branch(0));
        assertEquals("MAGICIAN", BenchYieldModel.branch(232));
        assertEquals("BOWMAN", BenchYieldModel.branch(1300)); // Wind Archer
        assertEquals("THIEF", BenchYieldModel.branch(412));
        assertEquals("PIRATE", BenchYieldModel.branch(522));
        assertEquals("WARRIOR", BenchYieldModel.branch(2112)); // Aran
        assertEquals("OTHER", BenchYieldModel.branch(900));
        assertEquals("OTHER", BenchYieldModel.branch(-1));
        assertEquals(0, BenchYieldModel.band(9));
        assertEquals(1, BenchYieldModel.band(10));
    }

    @Test
    void thePredictionMovesFromThePriorToTheMeasurementAsSlicesRun() {
        double prior = 1_000.0d;
        double measured = 2_000.0d;
        assertEquals(prior, BenchYieldModel.shrink(0.0d, measured, prior, 1.0d), 1e-9, "nothing measured: the prior");
        double previous = prior;
        for (int slices = 1; slices <= 20; slices++) {
            double predicted = BenchYieldModel.shrink(slices, measured, prior, 1.0d);
            assertTrue(predicted > previous && predicted < measured, slices + " slices: " + predicted);
            previous = predicted;
        }
        assertEquals(1_833.33d, BenchYieldModel.shrink(5.0d, measured, prior, 1.0d), 0.01d, "within 25% after 5 slices");
        assertEquals(measured, BenchYieldModel.shrink(1.0d, measured, prior, 0.0d), 1e-9, "no prior weight: the measurement");
    }

    @Test
    void slicesAreWeightedByTheShareOfAFullSliceTheyRan() {
        BenchYieldModel model = game.yields;
        assertNull(model.measured(CAMP));
        assertEquals(700.0d, model.expPerHour(CAMP, 700.0d), 1e-9, "the prior where nothing ran");

        model.record(CAMP, new BenchYieldModel.Sample(0.25d, 1_000L, 500L, 12L, 0)); // one full slice (15 min)
        model.record(CAMP, new BenchYieldModel.Sample(1.0d / 12.0d, 200L, 100L, 4L, 1)); // five minutes, then it died
        model.record(CAMP, new BenchYieldModel.Sample(0.0d, 9_999L, 9_999L, 9L, 0)); // ran no time: no sample

        BenchYieldModel.Rates rates = model.measured(CAMP);
        double hours = 0.25d + 1.0d / 12.0d;
        assertEquals(4.0d / 3.0d, rates.samples(), 1e-9);
        assertEquals(1_200.0d / hours, rates.expPerHour(), 1e-6, "the total over the hours measured");
        assertEquals(600.0d / hours, rates.mesoPerHour(), 1e-6);
        assertEquals(16.0d / hours, rates.itemsPerHour(), 1e-6);
        assertEquals(1.0d / hours, rates.deathsPerHour(), 1e-6);
        double prior = 1_000.0d;
        assertEquals(BenchYieldModel.shrink(4.0d / 3.0d, 1_200.0d / hours, prior, game.config.BENCH_YIELD_PRIOR_SLICES),
                model.expPerHour(CAMP, prior), 1e-6);
    }

    @Test
    void theRatesSurviveARestart() {
        game.yields.record(CAMP, new BenchYieldModel.Sample(0.25d, 1_000L, 500L, 12L, 0));
        game.yields.record(BenchYieldModel.Key.of(0, 5, 104010002), new BenchYieldModel.Sample(0.5d, 300L, 60L, 2L, 1));
        assertEquals(new YieldRow("WARRIOR", 2, FIELD, 1.0d, 4_000.0d, 2_000.0d, 48.0d, 0.0d),
                game.store.yields.get("WARRIOR:2:" + FIELD));

        BenchYieldModel restarted = new BenchYieldModel(game.world);
        restarted.load();

        assertEquals(game.yields.measured(CAMP), restarted.measured(CAMP));
        assertEquals(new BenchYieldModel.Rates(2.0d, 600.0d, 120.0d, 4.0d, 2.0d),
                restarted.measured(BenchYieldModel.Key.of(0, 5, 104010002)));
        assertEquals(game.yields.mesoPerHour(CAMP, 100.0d), restarted.mesoPerHour(CAMP, 100.0d), 1e-9);
    }

    @Test
    void aModelAskedBeforeItsLoadReadsTheRowsOnce() {
        game.store.yields.put("WARRIOR:2:" + FIELD, new YieldRow("WARRIOR", 2, FIELD, 3.0d, 900.0d, 0.0d, 0.0d, 0.0d));

        BenchYieldModel model = new BenchYieldModel(game.world);

        assertEquals(900.0d, model.measured(CAMP).expPerHour(), 1e-9);
    }

    @Test
    void theLedgerCreditsThePredictionForTheCompanionsCamp() {
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN); // level 10, no job (-1), camping at FIELD
        BenchLedger ledger = new BenchLedger(game.world);

        benchedAnHourAgo();
        ledger.tick(); // an hour on the priors (well short of its pace target)
        long onThePrior = game.row(ANN, "olwen").accruedExp();
        double prior = BenchLedger.expPerHour(10) * game.config.BENCH_FARM_EFFICIENCY;
        BenchYieldModel.Key key = BenchYieldModel.Key.of(-1, 10, FIELD);
        for (int slice = 0; slice < 5; slice++) {
            game.yields.record(key, new BenchYieldModel.Sample(0.25d, Math.round(prior * 0.25d * 2.0d), 0L, 0L, 0)); // twice the prior's EXP/h
        }
        benchedAnHourAgo();
        ledger.tick();
        long measured = game.row(ANN, "olwen").accruedExp();

        double predicted = BenchYieldModel.shrink(5.0d, game.yields.measured(key).expPerHour(), prior,
                game.config.BENCH_YIELD_PRIOR_SLICES);
        assertTrue(onThePrior > 0L);
        assertEquals(predicted / prior, (double) measured / onThePrior, 1e-3, "credited at " + predicted + " EXP/h, not " + prior);
        assertEquals(RosterState.BENCHED, game.row(ANN, "olwen").state());
    }

    /** Olwen's row as benched an hour ago with nothing banked (the ledger's clock reads the real time). */
    private void benchedAnHourAgo() {
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        RosterEntry row = game.row(ANN, "olwen");
        game.store.seed(new RosterEntry(ANN, "olwen", RosterState.BENCHED, Assignment.TRAIN, 0, 0, row.campMapId(), 0L, 0L, 0L,
                row.recruitedAtMs(), hourAgo, hourAgo, 0, null));
    }
}

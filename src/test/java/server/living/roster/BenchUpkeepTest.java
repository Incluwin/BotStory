package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.living.bond.BondService;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

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
        assertEquals("farmed " + BenchReport.meso(Math.round(predicted)) + " meso at Ant Tunnel I.",
                ledger.takeReport(ANN, "brin", 10));
    }

    @Test
    void itsContractsCutOfAFarmedHourGoesToItsPurseNotTheHaul() {
        // COMPANION_LENDING_PLAN 4.3: an OWN_DROPS 10 contract keeps 10% of the meso it farms at camp
        LootTerms terms = mock(LootTerms.class);
        when(terms.benchShare(org.mockito.ArgumentMatchers.eq(ANN), org.mockito.ArgumentMatchers.eq("brin"),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> Math.round(0.9d * (long) inv.getArgument(2)));
        when(game.world.lootTerms()).thenReturn(terms);
        game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO);
        BenchYieldModel.Key key = BenchYieldModel.Key.of(-1, 10, FIELD);
        for (int hunt = 0; hunt < 20; hunt++) {
            game.yields.record(key, new BenchYieldModel.Sample(0.25d, 0L, 250L, 0L, 0)); // 1,000 meso/h measured
        }
        double predicted = game.yields.mesoPerHour(key, ledger.mesoPrior(10));
        RosterEntry row = game.row(ANN, "brin");
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        game.store.seed(new RosterEntry(ANN, "brin", RosterState.BENCHED, Assignment.FARM_MESO, 0, 0, FIELD, 0L, 0L, 0L,
                row.recruitedAtMs(), hourAgo, hourAgo, 0, null));

        ledger.tick();

        assertEquals(0.75d * predicted, game.row(ANN, "brin").haulMeso(), 3.0d, "90% farmed, less 15% upkeep");
        org.mockito.ArgumentCaptor<Long> cut = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(terms).payCut(org.mockito.ArgumentMatchers.eq("brin"), cut.capture());
        assertEquals(0.1d * predicted, cut.getValue(), 2.0d, "its cut, to its own purse");
    }

    @Test
    void aCompanionOutOnLoanFarmsNothingAtCamp() {
        CompanionLoans loans = mock(CompanionLoans.class);
        when(loans.any()).thenReturn(true);
        when(loans.lent("brin")).thenReturn(true);
        when(game.world.loans()).thenReturn(loans);
        game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO);
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        RosterEntry lent = new RosterEntry(ANN, "brin", RosterState.BENCHED, Assignment.FARM_MESO, 0, 0, FIELD, 0L, 0L, 0L,
                game.row(ANN, "brin").recruitedAtMs(), hourAgo, hourAgo, 0, null);
        game.store.seed(lent);

        ledger.tick();

        assertEquals(lent, game.row(ANN, "brin"), "no credit, no upkeep: it is out with its owner's friend");
    }

    @Test
    void aGatherItCanGetNothingForChargesNoUpkeep() throws Exception {
        game.companion(ANN, "brin", "Brin", 902, Assignment.GATHER);
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            annNeeds(4003003); // Fairy Wings: only Happyville's script-spawned snowmen drop them (plan 8.3)
            game.store.seed(new RosterEntry(ANN, "brin", RosterState.BENCHED, Assignment.GATHER, 0, 0, FIELD, 0L, 0L, 0L,
                    1_000L, hourAgo, hourAgo, 0, null));
            ledger.tick();
            assertEquals(0L, game.row(ANN, "brin").haulMeso(), "it can do nothing, so it costs nothing");

            annNeeds(4000019); // Snail Shells: it can, so the hour costs its upkeep
            game.store.seed(new RosterEntry(ANN, "brin", RosterState.BENCHED, Assignment.GATHER, 0, 0, FIELD, 0L, 0L, 0L,
                    1_000L, hourAgo, hourAgo, 0, null));
            ledger.tick();
            assertTrue(game.row(ANN, "brin").haulMeso() < 0, "upkeep: " + game.row(ANN, "brin").haulMeso());
        }
    }

    /** Ann's active quest (another persona's) needs ten of the item. */
    private void annNeeds(int itemId) {
        Step step = mock(Step.class);
        when(step.type()).thenReturn(StepType.COLLECT_ITEM);
        when(step.itemId()).thenReturn(itemId);
        when(step.count()).thenReturn(10);
        Quest quest = mock(Quest.class);
        when(quest.persona()).thenReturn("wren");
        when(quest.step(0)).thenReturn(step);
        when(game.content.quest("errand")).thenReturn(quest);
        when(game.store.store.questStates(ANN, QuestStatus.ACTIVE)).thenReturn(List.of(
                new QuestState(ANN, "errand", 0, 0, QuestStatus.ACTIVE, 0L, 0L, 0)));
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

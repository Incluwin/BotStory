package server.living.roster;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * The call-back report (docs/BOT_MANAGEMENT_PLAN.md flow 1 step 5, P5 "call one back and read its report"): what a
 * companion did on the bench, as the ledger credited it and its live slices harvested and ended, is whispered once in
 * one short line when it is called back, after its join line; a second call has nothing new to tell, and a seat taken
 * without a word (a login's restore) spends it too.
 */
class BenchReportTest {
    private static final int FIELD = 105040300;
    private static final int STOLEN_FENCE = 4000021;
    private static final int SNAIL_SHELL = 4000019;
    private static final Map<Integer, String> PLACES = Map.of(FIELD, "Ant Tunnel I");
    private static final Map<Integer, String> ITEMS = Map.of(STOLEN_FENCE, "Stolen Fence", SNAIL_SHELL, "Snail Shell");

    private final BenchReport report = new BenchReport();

    // ---- the line -------------------------------------------------------------------------------

    @Test
    void aFarmerTellsWhatReachedItsHaulWhereAndHowOftenItDied() {
        report.haul(ANN, "mira", FIELD, 0L, STOLEN_FENCE, 100);
        report.haul(ANN, "mira", FIELD, 0L, STOLEN_FENCE, 43);
        report.haul(ANN, "mira", FIELD, 12_000L, 0, 0);
        report.deaths(ANN, "mira", 1, FIELD);

        assertEquals("farmed 143 stolen fences and 12k meso at Ant Tunnel I. died once.", take());
    }

    @Test
    void aTraineeTellsTheLevelsOrTheShareOfALevelItGained() {
        report.exp(ANN, "tobi", 5_000L, 10, FIELD); // Lv10 needs 1,242 EXP a level in the stock table: more than a level
        assertEquals("trained at Ant Tunnel I, went up 2 levels.", report.take(ANN, "tobi", 12, PLACES::get, ITEMS::get));

        report.exp(ANN, "tobi", 100L, 10, FIELD);
        assertEquals("trained at Ant Tunnel I, +8% exp.", report.take(ANN, "tobi", 10, PLACES::get, ITEMS::get));

        report.exp(ANN, "tobi", 5L, 10, FIELD);
        assertNull(report.take(ANN, "tobi", 10, PLACES::get, ITEMS::get), "under a percent is nothing worth a whisper");
    }

    @Test
    void severalItemsSalesAndARoughHuntReadAsOneShortLine() {
        report.haul(ANN, "mira", FIELD, 0L, STOLEN_FENCE, 30);
        report.haul(ANN, "mira", FIELD, 0L, SNAIL_SHELL, 12);
        report.sold(ANN, "mira", 3_100L);
        assertEquals("farmed 30 stolen fences and 12 other items at Ant Tunnel I. sold the haul for 3.1k meso.", take());

        report.deaths(ANN, "mira", 2, FIELD);
        assertEquals("rough hunt at Ant Tunnel I. died twice.", take());
    }

    @Test
    void theReportIsToldOnceAndNothingIsNotTold() {
        assertNull(take(), "nothing happened");
        report.haul(ANN, "mira", FIELD, 850L, 0, 0);
        report.haul(ANN, "ben's", FIELD, 99L, 0, 0);

        assertEquals("farmed 850 meso at Ant Tunnel I.", take());
        assertNull(take(), "told once");
        assertEquals("farmed 99 meso at Ant Tunnel I.", report.take(ANN, "ben's", 0, PLACES::get, ITEMS::get),
                "each companion has its own");
    }

    @Test
    void namesArePluralLikeAPlayerWouldSayThem() {
        assertEquals("stolen fences", BenchReport.plural("stolen fence", 2));
        assertEquals("snail shell", BenchReport.plural("snail shell", 1));
        assertEquals("fairies", BenchReport.plural("fairy", 3));
        assertEquals("keys", BenchReport.plural("key", 3));
        assertEquals("horns", BenchReport.plural("horns", 3));
        assertEquals("tree branches", BenchReport.plural("tree branch", 5), "-ch takes -es");
        assertEquals("bushes", BenchReport.plural("bush", 2));
        assertEquals("boxes", BenchReport.plural("box", 2));
        assertEquals("wild boar teeth", BenchReport.plural("wild boar tooth", 7), "irregular, by its last word");
        assertEquals("leaves", BenchReport.plural("leaf", 4));
        assertEquals("firewood", BenchReport.plural("firewood", 30), "counted without a plural");
        assertEquals("iron ore", BenchReport.plural("iron ore", 12));
        assertEquals("850", BenchReport.meso(850L));
        assertEquals("3.1k", BenchReport.meso(3_140L));
        assertEquals("12k", BenchReport.meso(12_400L));
    }

    // ---- what feeds it ---------------------------------------------------------------------------------

    @Test
    void aLiveSliceReportsWhatReachedTheHaulAndTheDeathItTook() {
        try (BenchHarness game = new BenchHarness()) {
            game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO); // Lv10, camping at BenchHarness.FIELD
            game.duty.tick();
            game.picksUpMeso(902, 100);
            game.picksUp(902, STOLEN_FENCE, 3);
            game.duty.tick(); // the harvest

            game.duty.died(902);
            game.botMaps.put(902, game.map(BenchHarness.TOWN)); // revived in its camp's return town
            game.duty.tick();

            verify(game.ledger).reportHaul(ANN, "brin", BenchHarness.FIELD, 100L, 0, 0);
            verify(game.ledger).reportHaul(ANN, "brin", BenchHarness.FIELD, 0L, STOLEN_FENCE, 3);
            verify(game.ledger).reportSlice(ANN, "brin", BenchHarness.FIELD, 0L, 10, 1);
        }
    }

    @Test
    void aTraineesOwnPickupsAreNotReportedAsFarmed() {
        try (BenchHarness game = new BenchHarness()) {
            game.companion(ANN, "tobi", "Tobi", 903, Assignment.TRAIN);
            game.duty.tick();
            game.picksUpMeso(903, 100); // a trainee keeps what it picks up
            game.duty.endFor("tobi", "called by owner");

            verify(game.ledger, never()).reportHaul(eq(ANN), eq("tobi"), anyInt(), anyLong(), anyInt(), anyInt());
            verify(game.ledger).reportSlice(ANN, "tobi", BenchHarness.FIELD, 0L, 10, 0);
        }
    }

    // ---- once per recall, through the roster ---------------------------------------------------------

    @Test
    void aCompanionCalledFromAFarCampWhispersItsReportOnceWhenItArrives() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
            BenchLedger ledger = new BenchLedger(game.world, id -> id == LITH ? "Lith Harbor" : PLACES.get(id), ITEMS::get);
            when(game.world.bench()).thenReturn(ledger);
            ledger.reportHaul(ANN, "olwen", FIELD, 12_000L, STOLEN_FENCE, 143);
            ledger.reportSlice(ANN, "olwen", FIELD, 0L, 10, 1);
            String line = "farmed 143 stolen fences and 12k meso at Ant Tunnel I. died once.";

            game.roster().call(game.ann, olwen); // join-then-travel: it is on its way
            verify(game.residents, never()).whisper(olwen, game.ann, line);
            game.runTimers(); // it arrives

            verify(game.residents).reply(olwen, game.ann, "here.");
            verify(game.residents, times(1)).whisper(olwen, game.ann, line);

            benchAgain(game, olwen);
            game.roster().call(game.ann, olwen);
            game.runTimers();
            verify(game.residents, times(2)).reply(olwen, game.ann, "here.");
            ArgumentCaptor<String> whispers = ArgumentCaptor.forClass(String.class);
            verify(game.residents, atLeastOnce()).whisper(eq(olwen), eq(game.ann), whispers.capture());
            assertEquals(1, whispers.getAllValues().stream().filter(said -> said.startsWith("farmed")).count(),
                    "the ETA whispers aside, the report was told once: " + whispers.getAllValues());
        }
    }

    @Test
    void oneSeatedAtOnceWhispersItAfterItsJoinLineAndALoginRestoreSpendsIt() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, HENESYS_FIELD); // camps where Ann is
            BenchLedger ledger = new BenchLedger(game.world, PLACES::get, ITEMS::get);
            when(game.world.bench()).thenReturn(ledger);
            ledger.reportHaul(ANN, "olwen", FIELD, 4_000L, 0, 0);

            game.roster().call(game.ann, olwen);

            verify(game.residents).reply(olwen, game.ann, "here.");
            verify(game.residents).whisper(olwen, game.ann, "farmed 4.0k meso at Ant Tunnel I.");
            assertNull(ledger.takeReport(ANN, "olwen", 0), "spent");
        }
    }

    @Test
    void afterALogoutTheSummaryIsTheOnlyReport() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
            BenchLedger ledger = new BenchLedger(game.world, id -> id == LITH ? "Lith Harbor" : PLACES.get(id), ITEMS::get);
            when(game.world.bench()).thenReturn(ledger);
            game.roster().onOwnerLogout(game.ann, null); // the baseline the login whisper compares with
            game.store.lineup.put(FakeLivingStore.key(ANN, "olwen"), 1); // Olwen is in Ann's usual lineup
            RosterEntry row = game.row(olwen); // what its hunts farmed meanwhile
            game.store.seed(new RosterEntry(ANN, "olwen", RosterState.BENCHED, row.assignment(), 0, 0, LITH, 0L, 3_120L, 0L,
                    row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(), 0, null));
            ledger.reportHaul(ANN, "olwen", FIELD, 3_120L, STOLEN_FENCE, 40);

            game.roster().onOwnerLogin(game.ann); // one whisper, then the usual lineup is called with travel
            game.runTimers(); // Olwen arrives

            verify(game.residents).whisper(olwen, game.ann, "while you were away: me +3,120 meso.");
            verify(game.residents).reply(olwen, game.ann, "here.");
            ArgumentCaptor<String> whispers = ArgumentCaptor.forClass(String.class);
            verify(game.residents, atLeastOnce()).whisper(eq(olwen), eq(game.ann), whispers.capture());
            assertEquals(0, whispers.getAllValues().stream().filter(said -> said.startsWith("farmed")).count(),
                    "the same numbers are not told twice in a second format: " + whispers.getAllValues());
        }
    }

    @Test
    void oneSwappedInOnItsOwnersMapTellsItsReportOnce() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            Persona tobi = game.companion("tobi", "Tobi", 901, RosterState.ACTIVE, LITH);
            game.outWithAnn(tobi);
            Persona mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, LITH);
            game.roster();
            game.moveAnn(LITH, 1000, 200); // Mira camps where Ann is: she walks in at once
            game.placeWithAnn(tobi);
            BenchLedger ledger = new BenchLedger(game.world, PLACES::get, ITEMS::get);
            when(game.world.bench()).thenReturn(ledger);
            ledger.reportHaul(ANN, "mira", FIELD, 4_000L, 0, 0);

            assertTrue(game.roster().swap(game.ann, tobi, mira).ok());

            assertEquals(RosterState.ACTIVE, game.row(mira).state());
            verify(game.residents).reply(mira, game.ann, "here.");
            verify(game.residents).whisper(mira, game.ann, "farmed 4.0k meso at Ant Tunnel I.");
            assertNull(ledger.takeReport(ANN, "mira", 0), "spent: never mixed into a later arrival's report");
        }
    }

    private String take() {
        return report.take(ANN, "mira", 0, PLACES::get, ITEMS::get);
    }

    /** The companion goes back to its camp (a bench order), as its row and its unload say. */
    private static void benchAgain(LifecycleHarness game, Persona persona) {
        game.roster().bench(game.ann, persona);
        RosterEntry row = game.row(persona);
        assertEquals(RosterState.BENCHED, row.state(), row.toString());
    }
}

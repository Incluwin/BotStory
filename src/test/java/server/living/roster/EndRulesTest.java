package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.TOWN;

/**
 * Duty-aware end rules (docs/BOT_MANAGEMENT_PLAN.md P5): a field slice ends on its time (unless watched), back in town,
 * at its quota or when its owner calls it; a town (REST) or stall slice runs longer and survives the field rules,
 * while the rules every slice keeps (a call, a changed duty, a row no longer on the bench) end it too.
 */
class EndRulesTest {
    private final BenchHarness game = new BenchHarness();
    private final BenchHarness.FakeForm town = game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN);

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void eachPoolHasItsOwnSliceLengthAndAWatchedSliceRunsOn() {
        LivingWorldConfig config = new LivingWorldConfig();
        long field = config.BENCH_LIVE_SLICE_MS;
        assertNull(BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_LIVE, field, false, config));
        assertEquals("slice elapsed", BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_LIVE, field + 1, false, config));
        assertNull(BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_LIVE, field * 10, true, config), "a player watching");
        assertNull(BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_TOWN, field + 1, false, config), "town runs longer");
        assertEquals("slice elapsed", BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_TOWN,
                config.BENCH_TOWN_SLICE_MS + 1L, false, config));
        assertNull(BenchDutyDispatcher.timeUp(CompanionBudget.Kind.COMMERCE, config.BENCH_TOWN_SLICE_MS + 1L, false, config),
                "a stall runs longer still");
        assertEquals("slice elapsed", BenchDutyDispatcher.timeUp(CompanionBudget.Kind.COMMERCE,
                config.COMMERCE_SLICE_MS + 1L, false, config));
        assertTrue(config.BENCH_TOWN_SLICE_MS > config.BENCH_LIVE_SLICE_MS && config.COMMERCE_SLICE_MS > config.BENCH_TOWN_SLICE_MS);
        // towns and the Free Market nearly always have a player: watched, a town or stall slice runs twice its length
        // at most, so their small pools still rotate
        assertNull(BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_TOWN, 2L * config.BENCH_TOWN_SLICE_MS, true, config));
        assertEquals("slice elapsed", BenchDutyDispatcher.timeUp(CompanionBudget.Kind.BENCH_TOWN,
                2L * config.BENCH_TOWN_SLICE_MS + 1L, true, config));
        assertNull(BenchDutyDispatcher.timeUp(CompanionBudget.Kind.COMMERCE, 2L * config.COMMERCE_SLICE_MS, true, config));
        assertEquals("slice elapsed", BenchDutyDispatcher.timeUp(CompanionBudget.Kind.COMMERCE,
                2L * config.COMMERCE_SLICE_MS + 1L, true, config));
    }

    @Test
    void aTownSliceOutlastsAFieldSliceAndIsNeverSentBackFromTown() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.register(Assignment.REST, town);
        game.duty.tick();
        assertTrue(game.duty.live("olwen") && game.duty.live("fenn"));

        game.now += game.config.BENCH_LIVE_SLICE_MS + 1;
        game.duty.tick();

        // the field slice's time is up (alone on the bench, it goes again at once); the town slice runs on
        assertEquals(List.of(ANN + " olwen bench.live-end slice elapsed"), game.store.events("bench.live-end"));
        assertTrue(game.duty.live("fenn"), "a town slice survives the field slice length");

        game.now += game.config.BENCH_TOWN_SLICE_MS;
        game.duty.tick();

        assertTrue(game.calls.contains("finish fenn: slice elapsed"), game.calls.toString());
        assertTrue(game.store.events("bench.live-end").contains(ANN + " fenn bench.live-end slice elapsed"));
    }

    @Test
    void aFieldSliceBackInTownEndsWhileATownSliceStays() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.register(Assignment.REST, town);
        game.duty.tick();

        game.botMaps.put(901, game.map(TOWN)); // it wandered off the field (or died and respawned)
        game.duty.tick();

        assertEquals(List.of(ANN + " olwen bench.live-end back in town"), game.store.events("bench.live-end"));
        assertTrue(game.duty.live("fenn"), "resting in town is its duty");
        assertEquals(BenchHarness.FIELD, game.botMaps.get(901).getId(), "it goes again from its camp");
    }

    @Test
    void aFieldSliceEndsAtItsQuota() {
        game.companion(ANN, "cato", "Cato", 903, Assignment.FARM_ITEM);
        RosterEntry row = game.row(ANN, "cato");
        game.store.seed(new RosterEntry(ANN, "cato", RosterState.BENCHED, Assignment.FARM_ITEM, 4000019, 3, row.campMapId(),
                0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
        game.duty.tick();
        assertTrue(game.duty.live("cato"));

        game.picksUp(903, 4000019, 3);
        game.duty.tick();

        assertFalse(game.duty.live("cato"));
        assertEquals(3, game.haul(ANN, "cato").get(4000019));
        assertEquals(List.of(ANN + " cato bench.live-end collected what was asked"), game.store.events("bench.live-end"));
    }

    @Test
    void theRulesEverySliceKeepsEndATownSliceToo() {
        game.config.BENCH_TOWN_PER_OWNER = 3; // all three of Ann's rest in town at once here
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.companion(ANN, "hale", "Hale", 908, Assignment.REST);
        game.companion(ANN, "ivo", "Ivo", 909, Assignment.REST);
        game.duty.register(Assignment.REST, town);
        game.duty.tick();
        assertEquals(3, game.duty.liveCount(CompanionBudget.Kind.BENCH_TOWN));

        game.duty.endFor("fenn", "called by owner");
        RosterEntry hale = game.row(ANN, "hale");
        game.store.seed(new RosterEntry(ANN, "hale", hale.state(), Assignment.TRAIN, 0, 0, hale.campMapId(), 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null)); // its owner gave it another duty
        game.store.seed(game.row(ANN, "ivo").with(RosterState.ACTIVE, game.now)); // called out of band
        game.duty.tick();

        assertEquals(java.util.Set.of(ANN + " fenn bench.live-end called by owner", ANN + " hale bench.live-end assignment changed",
                ANN + " ivo bench.live-end assignment changed"), java.util.Set.copyOf(game.store.events("bench.live-end")));
        assertNull(game.leases.holder(909), "out with its owner now: no bench seat");
        assertEquals(CompanionLeases.Kind.BENCH_LIVE, game.leases.holder(908).kind(), "Hale trains at its field now");
    }

    @Test
    void aNewCampEndsTheSliceAndTheNextOneGoesThere() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();

        RosterEntry row = game.row(ANN, "olwen");
        game.store.seed(new RosterEntry(ANN, "olwen", row.state(), row.assignment(), 0, 0, BenchHarness.OTHER_FIELD, 0L, 0L,
                0L, 1_000L, 1_000L, 1_000L, 0, null)); // "Camp somewhere else..."
        game.duty.tick();

        assertEquals(List.of(ANN + " olwen bench.live-end camp changed"), game.store.events("bench.live-end"));
        assertEquals("load Olwen at " + BenchHarness.OTHER_FIELD + " ch1",
                game.calls.stream().filter(call -> call.startsWith("load")).reduce((first, last) -> last).orElse(""));
    }

    @Test
    void aFormsOwnRuleEndsItsSlice() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.register(Assignment.REST, town);
        game.duty.tick();

        town.endsWith = "the stall sold out";
        game.duty.tick();

        assertTrue(game.calls.contains("finish fenn: the stall sold out"), game.calls.toString());
        assertEquals(List.of(ANN + " fenn bench.live-end the stall sold out"), game.store.events("bench.live-end"));
    }
}

package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.BEN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.TOWN;

/**
 * BenchDutyDispatcher (docs/BOT_MANAGEMENT_PLAN.md P5, "Living bench", flow 2, decision 12 a): the one place a benched
 * companion goes live. Each duty goes to its form (the field form for TRAIN and the farms, a registered form for
 * the others, the ledger alone without one) under its pool and lease; the per-owner and server-wide quotas hold,
 * owners online go first, each owner's bench takes turns, every load is on the living channel, and the default camp
 * of a field duty is a field, never a town.
 */
class BenchDutyDispatcherTest {
    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void everyDutyGoesToItsFormUnderItsPoolAndOneWithoutAFormStaysInTheLedger() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO);
        game.companion(BEN, "cato", "Cato", 903, Assignment.FARM_ITEM);
        game.companion(BEN, "dara", "Dara", 904, Assignment.GATHER);
        game.companion(BEN, "edda", "Edda", 905, Assignment.SELL);
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.companion(ANN, "gwen", "Gwen", 907, Assignment.STALL);
        game.duty.register(Assignment.REST, game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN));
        game.duty.register(Assignment.STALL, game.new FakeForm(CompanionBudget.Kind.COMMERCE, 910000001));

        game.duty.tick();

        // the field form grinds at the camp; the town and stall forms start their own activity where they load
        assertTrue(game.calls.containsAll(List.of("load Olwen at " + FIELD + " ch1", "grind Olwen", "load Brin at " + FIELD + " ch1",
                "grind Brin", "load Cato at " + FIELD + " ch1", "grind Cato", "load Fenn at " + TOWN + " ch1", "begin Fenn",
                "load Gwen at 910000001 ch1", "begin Gwen")), game.calls.toString());
        assertFalse(game.duty.live("dara") || game.duty.live("edda"), "no form yet: the ledger alone keeps them");
        assertEquals(3, game.duty.liveCount(CompanionBudget.Kind.BENCH_LIVE));
        assertEquals(1, game.duty.liveCount(CompanionBudget.Kind.BENCH_TOWN));
        assertEquals(1, game.duty.liveCount(CompanionBudget.Kind.COMMERCE));
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_LIVE, ANN), game.leases.holder(901));
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_LIVE, BEN), game.leases.holder(903));
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_TOWN, ANN), game.leases.holder(906));
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMMERCE, ANN), game.leases.holder(907));
        assertEquals(Set.of(901, 902, 903, 906, 907), game.duty.liveCharacterIds());
        assertTrue(game.store.events("bench.live").contains(ANN + " fenn bench.live rest live at map " + TOWN));
        // the population and PQ sweeps never take one: its lease refuses every other load
        assertFalse(game.leases.tryAcquire(901, CompanionLeases.Kind.POPULATION, 0));
        assertFalse(game.leases.tryAcquire(906, CompanionLeases.Kind.PQ_BACKGROUND, 0));
    }

    @Test
    void anUnregisteredFormFallsBackToTheLedger() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        BenchHarness.FakeForm town = game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN);
        game.duty.register(Assignment.REST, town);
        assertEquals(town, game.duty.form(Assignment.REST));

        game.duty.register(Assignment.REST, null);
        game.duty.tick();

        assertNull(game.duty.form(Assignment.REST));
        assertFalse(game.duty.live("fenn"));
        assertEquals(List.of(), game.calls);
    }

    @Test
    void theOwnersQuotaAndTheServerPoolHoldTheFieldSlices() {
        game.config.BENCH_LIVE_MAX = 3; // the server-wide field pool: min(BENCH_LIVE_MAX, BENCH_LIVE_GLOBAL)
        game.online.addAll(Set.of(ANN, BEN));
        for (int i = 0; i < 3; i++) {
            game.companion(ANN, "ann" + i, "Ann" + i, 911 + i, Assignment.TRAIN);
            game.companion(BEN, "ben" + i, "Ben" + i, 921 + i, Assignment.TRAIN);
        }

        game.duty.tick();

        assertEquals(3, game.duty.liveCount(CompanionBudget.Kind.BENCH_LIVE));
        assertTrue(game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE) <= 2);
        assertTrue(game.duty.liveCountFor(BEN, CompanionBudget.Kind.BENCH_LIVE) <= 2);
        assertEquals(List.of(), game.store.events("companion.budget_denied"), "the quota is checked before the budget is asked");

        game.config.BENCH_LIVE_MAX = 8;
        game.duty.tick();

        assertEquals(2, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE), "two live field grinders per owner");
        assertEquals(2, game.duty.liveCountFor(BEN, CompanionBudget.Kind.BENCH_LIVE));
    }

    @Test
    void townAndStallPoolsHaveTheirOwnCaps() {
        game.config.BENCH_TOWN_MAX = 1;
        game.config.COMMERCE_LIVE_MAX = 0; // no stalls
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.companion(BEN, "hale", "Hale", 908, Assignment.REST);
        game.companion(ANN, "gwen", "Gwen", 907, Assignment.STALL);
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.companion(ANN, "brin", "Brin", 902, Assignment.TRAIN);
        game.duty.register(Assignment.REST, game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN));
        game.duty.register(Assignment.STALL, game.new FakeForm(CompanionBudget.Kind.COMMERCE, 910000001));

        game.duty.tick();

        assertEquals(1, game.duty.liveCount(CompanionBudget.Kind.BENCH_TOWN));
        assertEquals(0, game.duty.liveCount(CompanionBudget.Kind.COMMERCE));
        // Ann's town presence and her stall do not use her two field seats
        assertEquals(2, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE));
    }

    @Test
    void eachOwnerHasOneTownSlotAndOneStallSoNoOwnerHoldsThemAll() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.companion(ANN, "hale", "Hale", 908, Assignment.REST);
        game.companion(BEN, "ivo", "Ivo", 909, Assignment.REST);
        game.companion(ANN, "gwen", "Gwen", 907, Assignment.STALL);
        game.companion(ANN, "jory", "Jory", 910, Assignment.STALL);
        game.companion(BEN, "kell", "Kell", 911, Assignment.STALL);
        game.duty.register(Assignment.REST, game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN));
        game.duty.register(Assignment.STALL, game.new FakeForm(CompanionBudget.Kind.COMMERCE, 910000001));

        game.duty.tick();

        assertEquals(1, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_TOWN));
        assertEquals(1, game.duty.liveCountFor(BEN, CompanionBudget.Kind.BENCH_TOWN), "Ben's rests beside Ann's");
        assertEquals(1, game.duty.liveCountFor(ANN, CompanionBudget.Kind.COMMERCE));
        assertEquals(1, game.duty.liveCountFor(BEN, CompanionBudget.Kind.COMMERCE), "Ben's stall opens though Ann has two");
        assertTrue(game.duty.live("ivo") && game.duty.live("kell"));
    }

    @Test
    void slicesNeverStackOnOneSpotAndWalkInFromAPortalWhereAPlayerIs() {
        game.statics(server.living.resident.ResidentSpots.class)
                .when(() -> server.living.resident.ResidentSpots.fallback(any(), anyInt()))
                .thenAnswer(inv -> new Point((int) inv.getArgument(1) % 1000, 0)); // one of the map's spots, by ordinal
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.companion(ANN, "brin", "Brin", 902, Assignment.TRAIN);

        game.duty.tick();

        assertNotEquals(game.loadedAt.get(901), game.loadedAt.get(902), "two companions never on one pixel");
        game.duty.endAll("test");
        server.maps.Portal portal = org.mockito.Mockito.mock(server.maps.Portal.class);
        org.mockito.Mockito.when(portal.getPosition()).thenReturn(new Point(700, 90));
        org.mockito.Mockito.when(portal.getPortalStatus()).thenReturn(true);
        org.mockito.Mockito.when(portal.getType()).thenReturn(server.maps.Portal.MAP_PORTAL);
        org.mockito.Mockito.when(portal.getTargetMapId()).thenReturn(TOWN);
        org.mockito.Mockito.when(game.map(FIELD).getPortals()).thenReturn(List.of(portal));
        org.mockito.Mockito.when(game.map(FIELD).getAllPlayers()).thenReturn(List.of(org.mockito.Mockito.mock(client.Character.class)));
        game.now += game.config.BENCH_LIVE_SLICE_MS;

        game.duty.tick();

        assertEquals(new Point(700, 90), game.loadedAt.get(901), "a player is there: it walks in, never pops in");
    }

    @Test
    void ownersOnlineGoFirstWhenThePoolIsContended() {
        game.config.BENCH_LIVE_MAX = 2;
        game.online.add(BEN);
        game.companion(ANN, "anna", "Anna", 911, Assignment.TRAIN); // Ann is offline
        game.companion(ANN, "anya", "Anya", 912, Assignment.TRAIN);
        game.companion(BEN, "bert", "Bert", 921, Assignment.TRAIN);
        game.companion(BEN, "bram", "Bram", 922, Assignment.TRAIN);

        game.duty.tick();

        assertTrue(game.duty.live("bert") && game.duty.live("bram"), game.calls.toString());
        assertFalse(game.duty.live("anna") || game.duty.live("anya"));
    }

    @Test
    void theOrderIsOnlineOwnersFirstOneOfEachOwnersPerRoundByTurn() {
        List<RosterEntry> rows = List.of(row(ANN, "a1"), row(ANN, "a2"), row(ANN, "a3"), row(BEN, "b1"), row(BEN, "b2"),
                row(30, "c1"));
        Map<String, Long> lastLive = Map.of("a1", 50L, "a2", 10L, "b1", 20L, "c1", 5L); // a3, b2: never live

        List<RosterEntry> order = BenchDutyDispatcher.order(rows, owner -> owner != 30, persona -> lastLive.getOrDefault(persona, 0L));

        // online Ann and Ben take turns (Ann's a3 waited longest), each one's bench by turn; offline 30 comes last
        assertEquals(List.of("a3", "b2", "a2", "b1", "a1", "c1"), order.stream().map(RosterEntry::personaId).toList());
    }

    @Test
    void eachOwnersBenchTakesTurnsSliceBySlice() {
        game.config.BENCH_LIVE_PER_OWNER = 1;
        game.companion(ANN, "anna", "Anna", 911, Assignment.TRAIN);
        game.companion(ANN, "anya", "Anya", 912, Assignment.TRAIN);
        game.companion(ANN, "anke", "Anke", 913, Assignment.TRAIN);

        List<String> turns = new java.util.ArrayList<>();
        for (int slice = 0; slice < 4; slice++) {
            game.duty.tick();
            turns.add(List.of("anna", "anya", "anke").stream().filter(game.duty::live).findFirst().orElse("-"));
            game.now += game.config.BENCH_LIVE_SLICE_MS + 1; // the slice elapses (nobody watches)
        }

        assertEquals(List.of("anke", "anna", "anya", "anke"), turns);
        assertTrue(game.store.events("bench.live-end").contains(ANN + " anke bench.live-end slice elapsed"));
    }

    @Test
    void slicesLoadOnlyOnTheLivingChannelAndEndOffIt() {
        game.config.CHANNEL = 3;
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);

        game.duty.tick();
        assertEquals("load Olwen at " + FIELD + " ch3", game.calls.get(0));

        game.channels.put(901, 2); // somehow off the living channel
        game.duty.tick();

        assertEquals(List.of(ANN + " olwen bench.live-end off the living channel"), game.store.events("bench.live-end"));
        assertEquals(List.of("load Olwen at " + FIELD + " ch3", "load Olwen at " + FIELD + " ch3"),
                game.calls.stream().filter(call -> call.startsWith("load")).toList(), "back on the living channel");
    }

    @Test
    void theDefaultCampOfAFieldDutyIsAFieldNeverATown() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN, TOWN); // a row from before P5, camping in town
        game.companion(ANN, "brin", "Brin", 902, Assignment.FARM_MESO, 0); // no camp: its home town's field

        game.duty.tick();

        assertTrue(game.calls.containsAll(List.of("load Olwen at " + FIELD + " ch1", "load Brin at " + FIELD + " ch1")), game.calls.toString());
        assertEquals(TOWN, game.row(ANN, "olwen").campMapId(), "the row keeps its camp; it keeps working");
        assertEquals(FIELD, BenchDutyDispatcher.fieldCamp(game.world, game.row(ANN, "olwen"), game.content.persona("olwen")));
    }

    @Test
    void withNoFittingFieldAFieldDutyStaysInTheLedger() {
        org.mockito.Mockito.when(game.roster.fieldCampFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(0);
        Persona olwen = game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN, TOWN);

        game.duty.tick();

        assertFalse(game.duty.live(olwen.id()));
        assertEquals(List.of(), game.calls);
    }

    @Test
    void aCallEndsTheSliceAndFreesItsSeat() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();

        game.duty.endFor("olwen", "called by owner");

        assertFalse(game.duty.live("olwen"));
        assertNull(game.leases.holder(901));
        assertTrue(game.calls.containsAll(List.of("hand back Olwen", "unload Olwen")), game.calls.toString());
        assertTrue(game.calls.indexOf("hand back Olwen") < game.calls.indexOf("unload Olwen"), "handed back before it unloads");
        assertEquals(List.of(ANN + " olwen bench.live-end called by owner"), game.store.events("bench.live-end"));
    }

    @Test
    void aCompanionOutWithItsOwnerOrCalledMeanwhileIsNeverTaken() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        RosterEntry row = game.row(ANN, "olwen");
        game.store.seed(row.with(RosterState.ACTIVE, game.now));

        game.duty.tick();

        assertFalse(game.duty.live("olwen"));
        assertEquals(List.of(), game.calls);
    }

    private static RosterEntry row(int ownerId, String personaId) {
        return new RosterEntry(ownerId, personaId, RosterState.BENCHED, Assignment.TRAIN, 0, 0, FIELD, 0L, 0L, 0L, 1_000L,
                1_000L, 1_000L, 0, null);
    }
}

package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementOutcome;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy;
import server.living.store.LivingStore.Assignment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.OTHER_FIELD;

/**
 * A field slice whose own walk to a shop within walking reach does not get there (found live 2026-10-09: Jinx, camped
 * at 105040304, 10 maps below Henesys, stood 5 minutes on 105040302 every slice). The no-progress watchdog blocks the
 * hop it stood on for the Agent's next trips, but only in its session, and each slice's unload ends the session: every
 * next slice walked into the same hold, every 8 minutes, with no EXP and no potions. Now its next slice is a shop slice
 * at that shop, as for a shop too far to walk to (BenchShopSliceTest), at most one per BENCH_LIVE_SLICE_MS. That holds
 * when the watchdog gave the walk up, and when BENCH_RESUPPLY_MAX_MS ran out first while the trip still walked toward
 * the shop (a hold reached late on the way: the watchdog's TRAVEL_NO_PROGRESS_MS only starts there). The slice ends
 * "stuck on the way to a shop" when the walk stood on its map since the dispatcher's last look, else "resupply took too
 * long". A trip given up for any other reason, or one that bought and walks back, ends the slice as before. The
 * dispatcher looks every TICK_MS, as LivingWorld's timer runs it, and the walk crosses a map about every 50 s.
 */
class BenchStalledShopTripTest {
    private static final int BRIN = 902;
    /** The shop stands in a town: no "back in town" end while it shops there. */
    private static final int SHOP = BenchHarness.TOWN;
    private static final int SHOP_NPC = 1011100;
    /** The maps its walk crosses toward the shop, in order. The first is the early hold, the fifth the late one. */
    private static final int[] WALK = {OTHER_FIELD, 104010003, 104010004, 104010005, 104010006, 104010007, 104010008,
            104010009, 104010010};
    /** About how long its walk takes to cross one map. */
    private static final long MAP_MS = 50_000L;
    private static final String TOO_LONG = "resupply took too long";

    private final BenchHarness game = new BenchHarness();
    /** When the dispatcher looks next. */
    private long nextLookAt;
    /** When its walk crossed onto the map it stands on. */
    private long onMapSince;
    /** Where its walk stands. */
    private int onMap;

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aWalkTheWatchdogGaveUpOnEndsTheSliceAndTheNextSliceLoadsAtThatShop() {
        liveAtItsCamp();
        setsOff(30_000L, WALK[0]); // its first map off its camp is the hold

        stallsOnTheWay(); // 330 s: given up, it is walking back to its camp
        nextLook(); // 360 s: 240 s into its resupply time, which look 1 (120 s) started

        assertEquals(List.of(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED), ends(),
                "its slice ends once the watchdog gave the walk up: the next one would walk into the same hold");
        assertTrue(game.duty.live("brin"), "its next slice starts at once");
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "...at the shop it set off for");
        assertTrue(game.duty.slice("brin").resupplying(), "its card says it is restocking there");
        assertEquals(List.of("load Brin at " + FIELD + " ch" + game.config.CHANNEL, "unload Brin",
                "load Brin at " + SHOP + " ch" + game.config.CHANNEL),
                game.calls.stream().filter(call -> call.startsWith("load") || call.startsWith("unload")).toList());
    }

    @Test
    void aGivenUpWalkAlreadyBackAtItsCampStillSendsTheNextSliceToTheShopItSetOffFor() {
        liveAtItsCamp();
        setsOff(30_000L, WALK[0]);

        stallsOnTheWay();
        supplies().clear(); // it walked back and asked its party: the trip is over, its supplier no longer on it
        nextLook();

        assertEquals(List.of(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED), ends());
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "the shop its trip had set off for");
    }

    @Test
    void aHoldReachedLateOnTheWayEndsTheSliceWhenItsResupplyTimeRunsOutAndTheNextSliceLoadsAtThatShop() {
        // Its resupply time starts at the dispatcher's first look at the trip, up to TICK_MS after it set off, and
        // ends at the first look BENCH_RESUPPLY_MAX_MS after that: here at look 4 (480 s). A hold reached after about
        // 180 s gives the watchdog no time to give the walk up before then.
        liveAtItsCamp();
        long setOff = setsOff(60_000L, FIELD);
        walksOn(setOff, 5); // on its fifth map, the hold, 250 s after it set off (310 s)
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "within walking reach: the same slice walks there");
        assertTrue(game.duty.slice("brin").resupplying(), "look 1 (120 s) saw it set off: its hunt is over");

        nextLook(); // 360 s
        assertTrue(ends().isEmpty(), "240 s into its resupply time: it walks on");

        nextLook(); // 480 s: out of time, 170 s on the hold (the watchdog would give the walk up at 610 s)
        assertEquals(List.of(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED), ends(),
                "it has not crossed a map since the last look: stuck on the way");
        assertTrue(game.duty.live("brin"), "its next slice starts at once");
        assertEquals(SHOP, game.duty.slice("brin").mapId(),
                "...at the shop it walked toward: the hop block that would get it past the hold ended with its session");
        assertTrue(game.duty.slice("brin").resupplying());
        assertEquals(1, restockSlices());
    }

    @Test
    void aWalkStillCrossingMapsWhenItsResupplyTimeRunsOutEndsAsTooLongAndTheNextSliceLoadsAtThatShop() {
        liveAtItsCamp();
        long setOff = setsOff(60_000L, FIELD);
        walksOn(setOff, 8); // a map every 50 s: on its eighth at 460 s, and the shop still ahead

        nextLook(); // 480 s: out of time, 20 s on its map

        assertEquals(List.of(ANN + " brin bench.live-end " + TOO_LONG), ends(), "it still gets somewhere: not stuck");
        assertEquals(SHOP, game.duty.slice("brin").mapId(),
                "the same walk would run out of time on its next slice too: that slice loads at the shop");
        assertTrue(game.duty.slice("brin").resupplying());
        assertEquals(1, restockSlices());
    }

    @Test
    void aTripThatBoughtAndWalksBackWhenItsResupplyTimeRunsOutEndsAsBeforeWithNoShopSlice() {
        liveAtItsCamp();
        long setOff = setsOff(60_000L, FIELD);
        walksOn(setOff, 3);
        runTo(setOff + 4 * MAP_MS); // 260 s: at the shop, it buys and walks back
        crossesOnto(SHOP);
        boughtAndWalksBack();
        runTo(setOff + 5 * MAP_MS); // 310 s: on its way back, a map where its walk back stands
        crossesOnto(WALK[2]);

        nextLook(); // 360 s
        nextLook(); // 480 s: out of time, 170 s on one map

        assertEquals(List.of(ANN + " brin bench.live-end " + TOO_LONG), ends());
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "it bought what it went for: its next slice is at its camp");
        assertFalse(game.duty.slice("brin").resupplying());
        assertEquals(0, restockSlices());
    }

    @Test
    void aWalkWithNoRouteToTheShopEndsTheSliceAsBefore() {
        givenUpFor(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED, "no portal route reaches the selected supplier");
    }

    @Test
    void aShopItCannotAffordEndsTheSliceAsBefore() {
        givenUpFor(AgentSupplyProcurementOutcome.Status.INSUFFICIENT_MESO,
                "safe liquidation produced insufficient mesos for the required supply");
    }

    @Test
    void aSecondStallWithinASliceOfTheShopSliceIsNoSecondShopSlice() {
        liveAtItsCamp();
        setsOff(30_000L, WALK[0]);
        stallsOnTheWay();
        nextLook();
        long shopSliceAt = game.now;
        assertEquals(SHOP, game.duty.slice("brin").mapId());

        restocksAtTheShop();
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "stocked up, it is back at its camp");

        setsOff(30_000L, WALK[0]); // out of potions again, and the same hold
        stallsOnTheWay();
        supplies().clear();
        nextLook();
        assertTrue(game.now - shopSliceAt < game.config.BENCH_LIVE_SLICE_MS);

        assertEquals(ANN + " brin bench.live-end resupplied", lastEnd(), "within a slice of its shop slice the trip ends as before");
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "no second shop slice within BENCH_LIVE_SLICE_MS");
        assertEquals(1, restockSlices(), "one shop slice");

        setsOff(30_000L, WALK[0]);
        stallsOnTheWay();
        nextLook();
        assertTrue(game.now - shopSliceAt >= game.config.BENCH_LIVE_SLICE_MS);
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "a slice later, a stall sends it to the shop again");
        assertEquals(2, restockSlices());
    }

    @Test
    void aSecondLateHoldWithinASliceOfTheShopSliceIsNoSecondShopSlice() {
        liveAtItsCamp();
        walksIntoALateHold();
        long shopSliceAt = game.now;
        assertEquals(SHOP, game.duty.slice("brin").mapId());

        restocksAtTheShop();
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "stocked up, it is back at its camp");

        walksIntoALateHold(); // out of potions again, and the same hold
        assertTrue(game.now - shopSliceAt < game.config.BENCH_LIVE_SLICE_MS);
        assertEquals(ANN + " brin bench.live-end " + TOO_LONG, lastEnd(), "within a slice of its shop slice it ends as before");
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "no second shop slice within BENCH_LIVE_SLICE_MS");
        assertEquals(1, restockSlices(), "one shop slice");

        walksIntoALateHold();
        assertTrue(game.now - shopSliceAt >= game.config.BENCH_LIVE_SLICE_MS);
        assertEquals(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED, lastEnd());
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "a slice later, a late hold sends it to the shop again");
        assertEquals(2, restockSlices());
    }

    /** Brin's trip of this slice ended with {@code status} for {@code reason}: the slice ends as before, no shop slice. */
    private void givenUpFor(AgentSupplyProcurementOutcome.Status status, String reason) {
        liveAtItsCamp();
        setsOff(30_000L, WALK[0]);
        nextLook(); // 120 s: its hunt is over

        runTo(game.now + 60_000L);
        AgentSupplyProcurementState supplies = supplies();
        supplies.complete(new AgentSupplyProcurementOutcome(status, AgentResourceCategory.HP_POTION, 0, 0, 1_000, 1_000,
                game.now, reason));
        supplies.markAbandoned(reason);
        supplies.clear();
        nextLook();

        assertEquals(List.of(ANN + " brin bench.live-end resupplied"), ends());
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "its next slice is at its camp");
        assertFalse(game.duty.slice("brin").resupplying());
        assertEquals(0, restockSlices());
    }

    /** Look 0: Brin is live at its camp, its shop BENCH_RESUPPLY_MAX_HOPS maps away (within walking reach). */
    private void liveAtItsCamp() {
        game.duty.shopHops = (from, to) -> from == FIELD && to == SHOP ? game.config.BENCH_RESUPPLY_MAX_HOPS : -1;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        nextLookAt = game.now;
        nextLook();
        assertEquals(FIELD, game.duty.slice("brin").mapId());
    }

    /**
     * {@code sinceLastLookMs} after the dispatcher's last look (where this slice began), out of potions, Brin's own
     * supply procurement sets off on foot for the shop, its walk on {@code firstMap}. Returns when it set off.
     */
    private long setsOff(long sinceLastLookMs, int firstMap) {
        runTo(nextLookAt - BenchDutyDispatcher.TICK_MS + sinceLastLookMs);
        supplies().start("resupply-1", "", AgentResourceCategory.HP_POTION, SHOP, SHOP_NPC, FIELD,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        crossesOnto(firstMap);
        return game.now;
    }

    /** From its camp, its walk crosses onto the first {@code maps} maps of WALK, one every MAP_MS after {@code setOff}. */
    private void walksOn(long setOff, int maps) {
        for (int i = 0; i < maps; i++) {
            runTo(setOff + (i + 1) * MAP_MS);
            crossesOnto(WALK[i]);
        }
    }

    /** Out of potions 60 s into its slice, its walk reaches the hold on its fifth map: its resupply time runs out there. */
    private void walksIntoALateHold() {
        long setOff = setsOff(60_000L, FIELD);
        walksOn(setOff, 5);
        nextLook();
        nextLook();
    }

    /** Its walk is on {@code mapId} now: the Agent's own tick looks at the map it stands on, which starts that map's clock. */
    private void crossesOnto(int mapId) {
        game.botMaps.put(BRIN, game.map(mapId));
        supplies().travelStalledMs(mapId, game.now);
        onMap = mapId;
        onMapSince = game.now;
    }

    /**
     * Its walk stood on its map for TRAVEL_NO_PROGRESS_MS: the watchdog's outcome and abandon, as
     * AgentSupplyProcurementRuntime.travelToSupplier records them (it then walks back to its camp).
     */
    private void stallsOnTheWay() {
        long limit = AgentSupplyRecoveryPolicy.travelNoProgressMs();
        runTo(onMapSince + limit);
        String stuck = AgentSupplyProcurementOutcome.NO_PROGRESS_TOWARD_SUPPLIER + ": " + limit + " ms on map " + onMap
                + " without reaching its portal to map " + WALK[1];
        AgentSupplyProcurementState supplies = supplies();
        supplies.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                AgentResourceCategory.HP_POTION, 0, 0, 1_000, 1_000, game.now, stuck));
        supplies.markAbandoned(stuck);
    }

    /** At the shop, its own shop visit restocks it, and its walk back sets off. */
    private void boughtAndWalksBack() {
        AgentSupplyProcurementState supplies = supplies();
        supplies.markShopRequested();
        game.bags.get(BRIN).merge(2000002, 100, Integer::sum);
        supplies.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, 100, 1_000, 500, game.now, "bought"));
        supplies.markReturning();
    }

    /** On its shop slice, its own shop visit restocks it there; that slice ends and the next is at its camp. */
    private void restocksAtTheShop() {
        runTo(game.now + 30_000L);
        AgentSupplyProcurementState supplies = supplies();
        supplies.start("resupply-2", "", AgentResourceCategory.HP_POTION, SHOP, SHOP_NPC, SHOP,
                AgentSupplyProcurementState.Phase.SHOPPING);
        nextLook();
        runTo(game.now + 60_000L);
        game.bags.get(BRIN).merge(2000002, 100, Integer::sum);
        supplies.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, 100, 1_000, 500, game.now, "bought"));
        supplies.clear();
        nextLook();
    }

    /** The clock runs to {@code atMs}, the dispatcher looking every TICK_MS on the way (LivingWorld's timer). */
    private void runTo(long atMs) {
        assertTrue(atMs >= game.now, "time runs forward");
        while (nextLookAt <= atMs) {
            game.now = nextLookAt;
            game.duty.tick();
            nextLookAt += BenchDutyDispatcher.TICK_MS;
        }
        game.now = atMs;
    }

    /** The dispatcher's next look. */
    private void nextLook() {
        runTo(nextLookAt);
    }

    private List<String> ends() {
        return game.store.events("bench.live-end");
    }

    private String lastEnd() {
        List<String> ends = ends();
        return ends.get(ends.size() - 1);
    }

    private long restockSlices() {
        return game.store.events("bench.live").stream().filter(event -> event.contains("restock live at map")).count();
    }

    private AgentSupplyProcurementState supplies() {
        return game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }
}

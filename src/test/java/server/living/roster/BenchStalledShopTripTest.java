package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementOutcome;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.living.store.LivingStore.Assignment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.OTHER_FIELD;

/**
 * A field slice whose own walk to a shop within walking reach was given up by the Agent's no-progress watchdog (found
 * live 2026-10-09: Jinx, camped at 105040304, 10 maps below Henesys, stood 5 minutes on 105040302 every slice). The
 * watchdog blocks that hop for the Agent's next trips, but only in its session, and each slice's unload ends the
 * session: every next slice walked into the same hold, every 8 minutes, with no EXP and no potions. Now its next slice
 * is a shop slice at that shop, as for a shop too far to walk to (BenchShopSliceTest), at most one per
 * BENCH_LIVE_SLICE_MS. A trip given up for any other reason ends the slice as before.
 */
class BenchStalledShopTripTest {
    private static final int BRIN = 902;
    /** The shop stands in a town: no "back in town" end while it shops there. */
    private static final int SHOP = BenchHarness.TOWN;
    private static final int SHOP_NPC = 1011100;
    /** The map past the stall map that the walk never reached a portal to. */
    private static final int NEXT_MAP = 104010003;

    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aWalkTheWatchdogGaveUpOnEndsTheSliceAndTheNextSliceLoadsAtThatShop() {
        walkingToAShopWithinReach();

        stallsOnTheWay(); // given up, it is walking back to its camp
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED),
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
        walkingToAShopWithinReach();

        stallsOnTheWay();
        supplies().clear(); // it walked back and asked its party: the trip is over, its supplier no longer on it
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED));
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "the shop its trip had set off for");
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
        walkingToAShopWithinReach();
        stallsOnTheWay();
        game.now += 60_000L;
        game.duty.tick();
        long shopSliceAt = game.now;
        assertEquals(SHOP, game.duty.slice("brin").mapId());

        restocksAtTheShop();
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "stocked up, it is back at its camp");

        setsOffFor(SHOP); // out of potions again, and the same hold
        game.now += 60_000L;
        game.duty.tick();
        stallsOnTheWay();
        supplies().clear();
        game.now += 60_000L;
        game.duty.tick();
        assertTrue(game.now - shopSliceAt < game.config.BENCH_LIVE_SLICE_MS);

        List<String> ends = game.store.events("bench.live-end");
        assertEquals(ANN + " brin bench.live-end resupplied", ends.get(ends.size() - 1),
                "within a slice of its shop slice the trip ends as before");
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "no second shop slice within BENCH_LIVE_SLICE_MS");
        assertEquals(1, restockSlices(), "one shop slice");

        game.now = shopSliceAt + game.config.BENCH_LIVE_SLICE_MS;
        setsOffFor(SHOP);
        game.duty.tick();
        stallsOnTheWay();
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "a slice later, a stall sends it to the shop again");
        assertEquals(2, restockSlices());
    }

    /** Brin's trip of this slice ended with {@code status} for {@code reason}: the slice ends as before, no shop slice. */
    private void givenUpFor(AgentSupplyProcurementOutcome.Status status, String reason) {
        walkingToAShopWithinReach();

        AgentSupplyProcurementState supplies = supplies();
        supplies.complete(new AgentSupplyProcurementOutcome(status, AgentResourceCategory.HP_POTION, 0, 0, 1_000, 1_000,
                game.now, reason));
        supplies.markAbandoned(reason);
        supplies.clear();
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end resupplied"));
        assertFalse(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_STALLED));
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "its next slice is at its camp");
        assertFalse(game.duty.slice("brin").resupplying());
        assertEquals(0, restockSlices());
    }

    /** Brin, live at its camp, set off on foot for a shop 10 maps away (within BENCH_RESUPPLY_MAX_HOPS): it walks. */
    private void walkingToAShopWithinReach() {
        game.duty.shopHops = (from, to) -> from == FIELD && to == SHOP ? game.config.BENCH_RESUPPLY_MAX_HOPS : -1;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        setsOffFor(SHOP);
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "within walking reach: the same slice walks there");
        assertTrue(game.duty.slice("brin").resupplying());
    }

    /** Brin's own supply procurement set off on foot for the shop on {@code shopMapId}, and it walked off its camp. */
    private void setsOffFor(int shopMapId) {
        supplies().start("resupply-1", "", AgentResourceCategory.HP_POTION, shopMapId, SHOP_NPC, FIELD,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        game.botMaps.put(BRIN, game.map(OTHER_FIELD));
    }

    /**
     * Its walk stood on OTHER_FIELD for TRAVEL_NO_PROGRESS_MS: the watchdog's outcome and abandon, as
     * AgentSupplyProcurementRuntime.travelToSupplier records them (it then walks back to its camp).
     */
    private void stallsOnTheWay() {
        game.now += 240_000L;
        String stuck = "no progress toward the supplier: 300000 ms on map " + OTHER_FIELD
                + " without reaching its portal to map " + NEXT_MAP;
        AgentSupplyProcurementState supplies = supplies();
        supplies.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                AgentResourceCategory.HP_POTION, 0, 0, 1_000, 1_000, game.now, stuck));
        supplies.markAbandoned(stuck);
    }

    /** On its shop slice, its own shop visit restocks it there; that slice ends and the next is at its camp. */
    private void restocksAtTheShop() {
        AgentSupplyProcurementState supplies = supplies();
        supplies.start("resupply-2", "", AgentResourceCategory.HP_POTION, SHOP, SHOP_NPC, SHOP,
                AgentSupplyProcurementState.Phase.SHOPPING);
        game.now += 60_000L;
        game.duty.tick();
        game.bags.get(BRIN).merge(2000002, 100, Integer::sum);
        supplies.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, 100, 1_000, 500, game.now, "bought"));
        supplies.clear();
        game.now += 60_000L;
        game.duty.tick();
    }

    private long restockSlices() {
        return game.store.events("bench.live").stream().filter(event -> event.contains("restock live at map")).count();
    }

    private AgentSupplyProcurementState supplies() {
        return game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }
}

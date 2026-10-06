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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;

/**
 * A field slice whose own shop trip is too long to walk within a slice (found live 2026-10-02..06: Jinx, camped 22 maps
 * below the nearest potion shop, set off for Henesys, was unloaded after BENCH_RESUPPLY_MAX_MS still on the way and
 * reloaded at its camp to set off again, 530 times): its slice ends once it has set off, its next slice loads it at that
 * shop, where its own shop visit runs, and the slice after that is at its camp again.
 */
class BenchShopSliceTest {
    private static final int BRIN = 902;
    /** The shop stands in a town: no "back in town" end while it shops there. */
    private static final int SHOP = BenchHarness.TOWN;
    private static final int SHOP_NPC = 1011100;

    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aShopTooFarToWalkToEndsTheSliceAndTheNextSliceLoadsAtThatShop() {
        game.duty.shopHops = (from, to) -> from == FIELD && to == SHOP ? 22 : -1;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        assertTrue(game.duty.live("brin"));

        setsOffFor(SHOP);
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_TOO_FAR),
                "its slice ends once it has set off: walking there would outlast the slice");
        assertTrue(game.duty.live("brin"), "its next slice starts at once");
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "...at the shop it set off for");
        assertTrue(game.duty.slice("brin").resupplying(), "its card says it is restocking there");
        assertEquals(List.of("load Brin at " + FIELD + " ch" + game.config.CHANNEL, "unload Brin",
                "load Brin at " + SHOP + " ch" + game.config.CHANNEL),
                game.calls.stream().filter(call -> call.startsWith("load") || call.startsWith("unload")).toList());
    }

    @Test
    void itWalksOffItsCampFirstSoNobodyWatchingThereSeesItVanish() {
        game.duty.shopHops = (from, to) -> 22;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();

        supplies().start("resupply-1", "", AgentResourceCategory.HP_POTION, SHOP, SHOP_NPC, FIELD,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER); // still walking to its camp's portal
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "still on its camp: it walks on");
        assertTrue(game.store.events("bench.live-end").isEmpty());

        game.botMaps.put(BRIN, game.map(BenchHarness.OTHER_FIELD)); // through the portal, out of sight
        game.now += 60_000L;
        game.duty.tick();
        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end " + BenchDutyDispatcher.SHOP_TOO_FAR));
        assertEquals(SHOP, game.duty.slice("brin").mapId());
    }

    @Test
    void theShopSliceEndsOnceItsOwnShopVisitIsDoneAndTheNextSliceIsAtItsCamp() {
        atTheShop();
        game.now += 60_000L;
        game.duty.tick();
        assertTrue(game.duty.live("brin"), "no trip yet (it asks its party first): it stays");
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "no \"back in town\" end at a shop in a town");

        AgentSupplyProcurementState supplies = supplies();
        supplies.start("resupply-2", "", AgentResourceCategory.HP_POTION, SHOP, SHOP_NPC, SHOP,
                AgentSupplyProcurementState.Phase.SHOPPING);
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(SHOP, game.duty.slice("brin").mapId(), "shopping: it stays");

        game.bags.get(BRIN).merge(2000002, 100, Integer::sum);
        supplies.complete(new AgentSupplyProcurementOutcome(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentResourceCategory.HP_POTION, 0, 100, 1_000_000, 970_000, game.now, "bought"));
        supplies.clear(); // the visit ended
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end resupplied"));
        assertTrue(game.duty.live("brin"));
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "stocked up, it is back at its camp");
        assertFalse(game.duty.slice("brin").resupplying());
        assertNull(game.yields.measured(BenchYieldModel.Key.of(-1, 10, SHOP)), "a shop is no hunting ground: no yield there");
    }

    @Test
    void aShopWithinWalkingReachKeepsTheTripOnFoot() {
        game.duty.shopHops = (from, to) -> 5;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();

        setsOffFor(SHOP);
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.duty.live("brin"));
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "the same slice: it walks there and back");
        assertTrue(game.duty.slice("brin").resupplying());
        assertTrue(game.store.events("bench.live-end").isEmpty());
    }

    @Test
    void aShopSliceThatNeverRestocksEndsAtTheCapAndTheNextFarTripIsWalkedUntilASliceHasPassed() {
        atTheShop();

        game.now += game.config.BENCH_RESUPPLY_MAX_MS;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end resupply took too long"));
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "back at its camp");

        setsOffFor(SHOP);
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(FIELD, game.duty.slice("brin").mapId(),
                "a shop slice just failed: no second one within a slice, so it never alternates every tick");
        assertTrue(game.duty.slice("brin").resupplying());
    }

    @Test
    void calledBackBeforeItsShopSliceItForgetsTheShop() {
        game.config.BENCH_LIVE_PER_OWNER = 1;
        game.duty.shopHops = (from, to) -> 22;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        game.companion(ANN, "cato", "Cato", 903, Assignment.TRAIN);

        setsOffFor(SHOP);
        game.now += 60_000L;
        game.duty.tick();
        assertTrue(game.duty.live("cato"), "the seat went to the companion that waited longest");
        assertFalse(game.duty.live("brin"));

        game.duty.endFor("brin", "called by owner"); // its owner called it: a companion in the party restocks its own way
        game.duty.endFor("cato", "called by owner");
        game.now += 60_000L;
        game.duty.tick();

        assertTrue(game.duty.live("brin"));
        assertEquals(FIELD, game.duty.slice("brin").mapId(), "no shop trip left over from before the call");
    }

    /** Brin, live at its camp, set off for a shop 22 maps away; its next slice is live at that shop. */
    private void atTheShop() {
        game.duty.shopHops = (from, to) -> 22;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.TRAIN);
        game.duty.tick();
        setsOffFor(SHOP);
        game.now += 60_000L;
        game.duty.tick();
        assertEquals(SHOP, game.duty.slice("brin").mapId());
    }

    /** Brin's own supply procurement set off on foot for the shop on {@code shopMapId}, and it walked off its camp. */
    private void setsOffFor(int shopMapId) {
        supplies().start("resupply-1", "", AgentResourceCategory.HP_POTION, shopMapId, SHOP_NPC, FIELD,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        game.botMaps.put(BRIN, game.map(BenchHarness.OTHER_FIELD));
    }

    private AgentSupplyProcurementState supplies() {
        return game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }
}

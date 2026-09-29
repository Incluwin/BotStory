package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.living.store.LivingStore.Assignment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.TOWN;

/**
 * Flow 12, a bench-live death or empty supplies (docs/BOT_MANAGEMENT_PLAN.md P5): a companion that dies on a live slice
 * revives by stock rules in its camp's return town, then its slice ends, the ledger resumes and its card reads "resting
 * after a rough hunt" for a slice; one out of supplies ends its hunt, keeps its seat while the Agent's own shop
 * resupply runs, and only then unloads, so the next slice starts after it.
 */
class BenchDeathResupplyTest {
    private static final int BRIN = 902;

    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aDeathEndsTheSliceOnceItRevivedAndItRestsOneSlice() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();
        game.picksUpMeso(BRIN, 100);

        game.duty.died(BRIN); // LivingWorldHooks.playerDied
        game.hp.put(BRIN, 0);
        game.duty.tick();
        assertTrue(game.duty.live("brin"), "still dead: the stock revive comes first");

        game.hp.put(BRIN, 50);
        game.botMaps.put(BRIN, game.map(TOWN)); // revived in its camp's return town
        game.duty.tick();

        assertFalse(game.duty.live("brin"));
        assertEquals(List.of(ANN + " brin bench.live-end " + BenchDutyDispatcher.ROUGH_HUNT), game.store.events("bench.live-end"));
        assertEquals(100L, game.row(ANN, "brin").haulMeso(), "what it picked up before it died is in the haul");
        assertNull(game.leases.holder(BRIN));
        assertEquals("resting after a rough hunt", game.duty.note("brin"));
        assertEquals("farm_meso (resting after a rough hunt)", CompanionView.of(game.world, game.row(ANN, "brin"), id -> "").duty(),
                "the card shows it");

        game.now += game.config.BENCH_LIVE_SLICE_MS - 1;
        game.duty.tick();
        assertFalse(game.duty.live("brin"), "it rests a slice; the ledger credits it meanwhile");

        game.now += 2;
        game.duty.tick();
        assertTrue(game.duty.live("brin"));
        assertEquals("", game.duty.note("brin"));
        assertEquals("farm_meso", CompanionView.of(game.world, game.row(ANN, "brin"), id -> "").duty());
    }

    @Test
    void aDeathIsRecordedInTheYields() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();
        game.now += 600_000L; // ten minutes of hunting
        game.picksUpMeso(BRIN, 500);
        game.duty.died(BRIN);
        game.duty.tick();

        BenchYieldModel.Rates rates = game.yields.measured(BenchYieldModel.Key.of(-1, 10, BenchHarness.FIELD));
        assertEquals(3_000.0d, rates.mesoPerHour(), 1e-6);
        assertEquals(6.0d, rates.deathsPerHour(), 1e-6);
    }

    @Test
    void outOfSuppliesItsHuntEndsItRestocksAndOnlyThenItsSeatIsFree() {
        game.config.BENCH_LIVE_PER_OWNER = 1;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.companion(ANN, "cato", "Cato", 903, Assignment.FARM_MESO);
        game.duty.tick();
        assertTrue(game.duty.live("brin"));
        assertFalse(game.duty.live("cato"));
        AgentSupplyProcurementState supplies = game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);

        game.now += game.config.BENCH_LIVE_SLICE_MS + 1; // a full slice: its time would be up...
        supplies.start("resupply-1", "", AgentResourceCategory.HP_POTION); // ...but it is out of potions: its own shop visit begins
        game.duty.tick();

        assertTrue(game.duty.live("brin"), "loaded for its shop visit");
        assertTrue(game.duty.slice("brin").resupplying());
        assertTrue(game.store.events("bench.resupply").contains(ANN + " brin bench.resupply out of supplies at map " + BenchHarness.FIELD));

        game.botMaps.put(BRIN, game.map(TOWN)); // the shop is in town: no "back in town" while it restocks
        game.now += 60_000L;
        game.duty.tick();
        assertTrue(game.duty.live("brin"));
        assertFalse(game.duty.live("cato"), "the seat is Brin's until the visit is done");
        game.bags.get(BRIN).merge(2000000, 100, Integer::sum); // what it bought is its own

        supplies.clear(); // the visit ended
        game.duty.tick();

        assertFalse(game.duty.live("brin"));
        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end resupplied"));
        assertEquals(100, game.bags.get(BRIN).get(2000000));
        assertTrue(game.duty.live("cato"), "the next slice starts only after it");
    }

    @Test
    void aShopVisitThatTakesTooLongUnloadsAnyway() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();
        game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY)
                .start("resupply-1", "", AgentResourceCategory.HP_POTION);
        game.duty.tick();

        game.now += game.config.BENCH_RESUPPLY_MAX_MS;
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end resupply took too long"));
    }

    @Test
    void theYieldOfAResupplyingSliceCountsItsHuntOnly() {
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();
        game.now += 900_000L; // a full slice of hunting
        game.picksUpMeso(BRIN, 900);
        AgentSupplyProcurementState supplies = game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        supplies.start("resupply-1", "", AgentResourceCategory.HP_POTION);
        game.duty.tick();
        game.now += 240_000L; // four minutes at the shop
        supplies.clear();
        game.duty.tick();

        BenchYieldModel.Rates rates = game.yields.measured(BenchYieldModel.Key.of(-1, 10, BenchHarness.FIELD));
        assertEquals(1.0d, rates.samples(), 1e-9, "one full slice");
        assertEquals(3_600.0d, rates.mesoPerHour(), 1e-6, "900 meso in the 15 minutes it hunted");
    }
}

package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.living.habits.OwnerHabitBook;
import server.living.store.LivingStore.Assignment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.TOWN;

/**
 * Selling like its owner (docs/BOT_CONCEPT_PLAN.md F2.4): what a bench farmer picks up of an item its owner sells at
 * shops stays in its bag while it hunts, and is sold at its resupply (its NPC price into the haul) instead of hauled;
 * a slice that ends anywhere else hauls it as before, and everything else is hauled as before.
 */
class OwnerHabitVendorTest {
    private static final int BRIN = 902;
    private static final int SNAIL_SHELL = 4000019;
    private static final int STOLEN_FENCE = 4000021;

    private final BenchHarness game = new BenchHarness();
    private final OwnerHabitBook habits = mock(OwnerHabitBook.class);

    @BeforeEach
    void wire() {
        when(game.world.ownerHabits()).thenReturn(habits);
        when(habits.sells(anyInt(), anyInt())).thenReturn(false);
        when(habits.sells(eq(ANN), eq(SNAIL_SHELL))).thenReturn(true); // Ann always sells her snail shells
        game.field.vendorPrice = (itemId, quantity) -> 3 * quantity;
        game.companion(ANN, "brin", "Brin", BRIN, Assignment.FARM_MESO);
        game.duty.tick();
        game.picksUp(BRIN, SNAIL_SHELL, 30);
        game.picksUp(BRIN, STOLEN_FENCE, 3);
        game.duty.tick(); // the harvest while it hunts
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void itHoldsWhatItsOwnerSellsWhileItHuntsAndSellsItAtItsResupply() {
        assertEquals(3, game.haul(ANN, "brin").get(STOLEN_FENCE), "everything else is hauled as before");
        assertNull(game.haul(ANN, "brin").get(SNAIL_SHELL), "not hauled");
        assertEquals(30, game.bags.get(BRIN).get(SNAIL_SHELL), "still in its bag");

        AgentSupplyProcurementState supplies = game.entry(BRIN).capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        supplies.start("resupply-1", "", AgentResourceCategory.HP_POTION); // out of potions: its own shop visit
        game.duty.tick();
        game.botMaps.put(BRIN, game.map(TOWN));
        supplies.clear(); // the visit ended: it stands at the shop
        game.duty.tick(); // its slice ends there (the only one on Ann's bench, it is then back on its turn)

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end resupplied"));
        assertEquals(0, game.bags.get(BRIN).get(SNAIL_SHELL), "sold");
        assertNull(game.haul(ANN, "brin").get(SNAIL_SHELL));
        assertEquals(90L, game.row(ANN, "brin").haulMeso(), "30 shells at 3 meso each, into the haul");
        assertEquals(List.of(ANN + " brin bench.vendor 30 x " + SNAIL_SHELL + " for 90 meso"), game.store.events("bench.vendor"));
    }

    @Test
    void aSliceThatEndsAwayFromAShopHaulsItAsBefore() {
        game.now += game.config.BENCH_LIVE_SLICE_MS + 1; // its time is up on the field
        game.duty.tick();

        assertTrue(game.store.events("bench.live-end").contains(ANN + " brin bench.live-end slice elapsed"));
        assertEquals(30, game.haul(ANN, "brin").get(SNAIL_SHELL), "hauled: there was no shop to sell it at");
        assertEquals(0L, game.row(ANN, "brin").haulMeso());
        assertTrue(game.store.events("bench.vendor").isEmpty());
    }
}

package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No player price goes under what an NPC shop pays (docs/ECONOMY_PLAN.md E3): a non-equip seeds from its NPC price,
 * the index never moves below it, a row left under it takes a fresh seed at start, and stall asks are floored by
 * {@link Market#npcPays}.
 */
class MarketPriceFloorTest {
    private static final int SHELL = 4000019;  // a row sales moved, well over its NPC price
    private static final int FLOORED = 4000021; // a row the old non-equip seed left at 10, with old stall-close "sales"
    private static final int FRESH = 4000022;   // no row yet
    private static final int NO_PRICE = 4000023;
    private static final Map<Integer, Long> SHOP = Map.of(SHELL, 2_000L, FLOORED, 30L, FRESH, 25L, NO_PRICE, -1L);
    private static final int SELLER = 10;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class);
    private final Market market;

    MarketPriceFloorTest() {
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        List<int[]> rows = new ArrayList<>();
        rows.add(new int[] {SHELL, 10_000, 5});
        rows.add(new int[] {FLOORED, 10, 3});
        when(store.prices()).thenReturn(rows);
        PopulationContent content = mock(PopulationContent.class);
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        living.when(LivingWorld::get).thenReturn(null);
        for (int itemId : SHOP.keySet()) {
            assertEquals(0L, PriceSeeds.of(itemId), itemId + " must not be in the player price tables for this test");
        }
        market = new Market(population, itemId -> SHOP.getOrDefault(itemId, -1L));
    }

    @AfterEach
    void close() {
        living.close();
    }

    @Test
    void aNonEquipSeedsFromItsNpcPrice() {
        assertEquals(100L, market.price(FRESH), "4 x the 25 an NPC pays, not the 10 a missing unit price gave");
        assertEquals(10L, market.price(NO_PRICE), "an item no shop prices keeps the old minimum");
    }

    @Test
    void aRowUnderItsNpcPriceTakesAFreshSeedAtStartEvenWithSales() {
        assertEquals(1, market.reseedUnmoved(), "the floored row only: the shell row is a real price");
        assertEquals(120L, market.price(FLOORED));
        assertEquals(10_000L, market.price(SHELL));
        verify(store).savePrice(eq(FLOORED), eq(120L), eq(3));
        assertEquals(0, market.reseedUnmoved(), "a second start writes nothing");
    }

    @Test
    void unsoldLinesSettleAtTheNpcPriceNotUnderIt() {
        for (int close = 0; close < 400; close++) {
            market.unsold(SHELL, market.price(SHELL));
        }
        assertEquals(2_000L, market.price(SHELL));
    }

    @Test
    void cheapSalesStopAtTheNpcPrice() {
        for (int sale = 0; sale < 200; sale++) {
            market.sold(SELLER, SHELL, 1);
        }
        market.learnSales();
        assertEquals(2_000L, market.price(SHELL));
        verify(store).savePrice(eq(SHELL), eq(2_000L), eq(205));
    }

    @Test
    void npcPaysIsWhatAShopPaysForTheBundle() {
        assertEquals(200_000L, market.npcPays(SHELL, 100));
        assertEquals(0L, market.npcPays(NO_PRICE, 10), "no shop price: no floor");
    }
}

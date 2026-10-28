package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The price index learns from executed shop sales (docs/ECONOMY_PLAN.md E2, rule 5): a sale is queued from the buyer's
 * thread and learned on the market's tick; the median of recent sales feeds the moving average, within a band of the
 * index; a player's alts teach it nothing; and an unmoved seed row takes the player price tables' seed.
 */
class MarketSaleLearningTest {
    private static final int SHELL = 4000019;
    private static final int CLAW_ATT_60 = 2044701; // SoloMapling scrolls.yaml: { 1: 1500000 }
    private static final int SELLER = 10;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationConfig config = new PopulationConfig();
    private final MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class);
    private final Market market;

    MarketSaleLearningTest() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        List<int[]> rows = new ArrayList<>();
        rows.add(new int[] {SHELL, 100, 5});
        rows.add(new int[] {CLAW_ATT_60, 900, 0});
        when(store.prices()).thenReturn(rows);
        PopulationContent content = mock(PopulationContent.class);
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        living.when(LivingWorld::get).thenReturn(null); // no bench: every seller is a plain one
        market = new Market(population);
    }

    @AfterEach
    void close() {
        living.close();
    }

    @Test
    void aSaleMovesTheIndexOnTheTickNotOnTheBuyersThread() {
        market.sold(SELLER, SHELL, 50);
        assertEquals(100L, market.price(SHELL), "queued only");
        verify(store, never()).savePrice(anyInt(), anyLong(), anyInt());

        market.learnSales();
        assertEquals(96L, market.price(SHELL), "100 + (50 - 100) x 0.08");
        verify(store).savePrice(SHELL, 96L, 6);
    }

    @Test
    void oneOddSaleAmongUsualOnesBarelyMovesTheIndex() {
        market.sold(SELLER, SHELL, 100);
        market.sold(SELLER, SHELL, 100);
        market.sold(SELLER, SHELL, 1_000_000); // a fat-finger listing someone bought
        market.learnSales();
        assertEquals(100L, market.price(SHELL), "the median of 100, 100, 1m is 100");
    }

    @Test
    void aFirstWildSaleMovesAStepTowardTheBandNotTowardItsPrice() {
        market.sold(SELLER, SHELL, 1_000_000);
        market.learnSales();
        assertEquals(116L, market.price(SHELL), "100 + (300 - 100) x 0.08: the sale counts as 3x the index at most");
    }

    @Test
    void theMarketSwitchOffQueuesNothing() {
        config.MARKET_ENABLED = false;
        market.sold(SELLER, SHELL, 50);
        config.MARKET_ENABLED = true;
        market.learnSales();
        assertEquals(100L, market.price(SHELL));
    }

    @Test
    void aSaleBetweenOneAccountsCharactersTeachesNothing() {
        Character buyer = mock(Character.class);
        when(buyer.getAccountID()).thenReturn(77);
        when(population.market()).thenReturn(market);
        try (MockedStatic<Population> running = mockStatic(Population.class)) {
            running.when(Population::get).thenReturn(population);
            PopulationHooks.shopSale(SELLER, 77, buyer, SHELL, 10, 1); // an alt buying from its own main
            PopulationHooks.shopSale(SELLER, 78, buyer, SHELL, 500, 10); // someone else's shop: 50 a unit
        }
        market.learnSales();
        assertEquals(96L, market.price(SHELL), "only the second sale, at 50 a unit, counts");
    }

    @Test
    void anUnmovedSeedRowTakesThePlayerPriceTablesSeed() {
        assertEquals(1, market.reseedUnmoved(), "the claw scroll only: the shell row was moved by sales");
        assertEquals(1_500_000L, market.price(CLAW_ATT_60));
        assertEquals(100L, market.price(SHELL));
        verify(store).savePrice(eq(CLAW_ATT_60), eq(1_500_000L), eq(0));
        assertEquals(0, market.reseedUnmoved(), "a second start writes nothing");
    }

    @Test
    void thePlayerPriceTablesAreReadAtThisServersVersion() {
        Map<Integer, Long> seeds = PriceSeeds.load(PriceSeeds.DIR);
        assertTrue(seeds.size() > 100, "the SoloMapling tables list " + seeds.size() + " items");
        assertEquals(1_200_000L, seeds.get(2040302), "Earring Int 10%: { 1: 1300000, 39: 1200000 } at v83");
        assertEquals(600_000L, seeds.get(1302009), "a variant id written 01302009");
        assertEquals(0L, PriceSeeds.priceAt(Map.of("84", "5"), PriceSeeds.VERSION), "a later version is not ours");
    }
}

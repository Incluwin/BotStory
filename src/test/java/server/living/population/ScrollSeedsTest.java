package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A scroll no player price table lists starts from what it is expected to add, at the median mesos per expected point
 * of the scrolls the tables price (docs/ECONOMY_PLAN.md E3), instead of 4x the 1 meso a shop pays for it.
 */
class ScrollSeedsTest {
    private static final int ATT_10 = 2043002;   // a table-less 10% attack scroll
    private static final int CLAW_ATT_60 = 2044701; // SoloMapling scrolls.yaml: { 1: 1500000 }
    private static final int CLEAN_SLATE = 2049000;
    private static final Map<Integer, Map<String, Integer>> STATS = Map.of(
            ATT_10, Map.of("success", 10, "PAD", 5),
            CLAW_ATT_60, Map.of("success", 60, "PAD", 2),
            CLEAN_SLATE, Map.of("success", 1, "tuc", 1));

    @Test
    void aScrollIsWorthItsExpectedGainAtTheMedianRateOfThePricedOnes() {
        ScrollSeeds seeds = new ScrollSeeds(id -> Map.of(
                1, Map.of("success", 10, "PAD", 5),       // 0.5 expected points at 1m: 2m a point
                2, Map.of("success", 60, "PAD", 2),       // 1.2 at 600k: 500k a point
                3, Map.of("success", 60, "STR", 2),       // 0.72 at 1.2m: 1.667m a point
                4, Map.of("success", 100, "PDD", 1)).get(id - 2_040_000),
                () -> Map.of(2_040_001, 1_000_000L, 2_040_002, 600_000L, 2_040_003, 1_200_000L, 1_302_000, 9L));
        assertEquals(833_333L, seeds.seed(2_040_001), "0.5 points x the median 1.667m a point");
        assertEquals(83_333L, seeds.seed(2_040_004), "a 100% DEF +1: 0.05 points");
        assertEquals(0L, seeds.seed(1_302_000), "no scroll");
        assertEquals(0L, seeds.seed(2_040_005), "no WZ info");
    }

    @Test
    void theMarketSeedsAScrollNoTableListsAgainstTheTablesAndRepairsARowLeftAtTheOldMinimum() {
        for (int itemId : new int[] {ATT_10, CLEAN_SLATE}) {
            assertEquals(0L, PriceSeeds.of(itemId), itemId + " must not be in the player price tables for this test");
        }
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        List<int[]> rows = new ArrayList<>();
        rows.add(new int[] {ATT_10, 10, 9}); // its "sales" were stall closes and buys off a 10-meso listing
        when(store.prices()).thenReturn(rows);
        PopulationContent content = mock(PopulationContent.class);
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        Market market = new Market(population, itemId -> 1L, itemId -> STATS.getOrDefault(itemId, Map.of()));

        assertEquals(1, market.reseedUnmoved());
        assertEquals(625_000L, market.price(ATT_10), "0.5 points x the claw scroll's 1.5m / 1.2 points");
        verify(store).savePrice(eq(ATT_10), eq(625_000L), eq(9));
        assertEquals(0, market.reseedUnmoved(), "a second start writes nothing");
        assertEquals(10L, market.price(CLEAN_SLATE), "no bonus to price: the NPC multiple, at its minimum");
    }
}

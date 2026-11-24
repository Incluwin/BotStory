package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.needs.NeedOffers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The price index's one-time reseed (docs/ECONOMY_PLAN.md section 10.9): only rows under the threshold move, once, with
 * the guard on and every old price kept. The store is an in-memory stand-in; the SQL itself was run on a check stack.
 */
class PriceIndexReseedTest {
    private static final int FALLEN = 4010000;
    private static final int ABOVE = 4010001;
    private static final int AT_THRESHOLD = 4010002;
    private static final int FLOORED = 4010003;
    private static final int NO_SEED = 4999999;
    private static final int FALLEN_SCROLL = 2040804;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationContent content = mock(PopulationContent.class);
    private final PopulationConfig config = new PopulationConfig();
    private final List<PriceIndexReseed.Move> written = new ArrayList<>();
    private boolean ran;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        when(content.basket()).thenReturn(List.of(new PopulationContent.Good(FALLEN, 1_000L),
                new PopulationContent.Good(ABOVE, 1_000L), new PopulationContent.Good(AT_THRESHOLD, 1_000L),
                new PopulationContent.Good(FLOORED, 1_000L), new PopulationContent.Good(FALLEN_SCROLL, 900_000L)));
        // reseedUnmoved's rows aside: each has samples
        when(store.prices()).thenReturn(List.of(new int[]{FALLEN, 80, 9}, new int[]{ABOVE, 700, 3},
                new int[]{AT_THRESHOLD, 500, 3}, new int[]{FLOORED, 450, 4}, new int[]{NO_SEED, 3, 2},
                new int[]{FALLEN_SCROLL, 76_028, 12}));
        when(store.priceReseedRan()).thenAnswer(call -> ran);
        when(store.reseedPrices(any())).thenAnswer(call -> {
            written.addAll(call.getArgument(0));
            ran = true;
            return true;
        });
        config.PRICE_INDEX_RESEED_ENABLED = true;
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = true;
    }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    private Market market() {
        return new Market(population, itemId -> itemId == FLOORED ? 900L : itemId == NO_SEED ? 0L : 1L);
    }

    @Test
    void onlyRowsUnderTheThresholdMoveNeverUnderTheNpcPriceAndTheirOldPricesAreKept() {
        Market market = market();
        assertEquals(3, PriceIndexReseed.runOnce(market, store, config));
        assertEquals(800L, market.price(FALLEN));
        assertEquals(720_000L, market.price(FALLEN_SCROLL));
        assertEquals(900L, market.price(FLOORED), "0.8 x seed is under what an NPC pays");
        assertEquals(700L, market.price(ABOVE));
        assertEquals(500L, market.price(AT_THRESHOLD), "under, not at, the threshold");
        assertEquals(3L, market.price(NO_SEED), "no seed of its own: left alone");
        assertEquals(List.of(new PriceIndexReseed.Move(FALLEN, 1_000L, 80L, 800L, 9),
                new PriceIndexReseed.Move(FLOORED, 1_000L, 450L, 900L, 4),
                new PriceIndexReseed.Move(FALLEN_SCROLL, 900_000L, 76_028L, 720_000L, 12)), written);
        assertEquals(9L, market.rows().get(FALLEN)[1], "its samples stay");
        assertEquals(1, PriceIndexReseed.plan(market, config).noSeed());
    }

    @Test
    void itRunsExactlyOnceAcrossTwoStarts() {
        assertEquals(3, PriceIndexReseed.runOnce(market(), store, config));
        Market second = market(); // the stored rows again, as if they had slid back
        assertEquals(0, PriceIndexReseed.runOnce(second, store, config));
        assertEquals(80L, second.price(FALLEN));
        assertEquals(3, written.size());
    }

    @Test
    void itRefusesWithoutTheGuardAndDoesNothingWithItsSwitchOff() {
        Market market = market();
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = false;
        assertEquals(0, PriceIndexReseed.runOnce(market, store, config));
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = true;
        config.PRICE_INDEX_RESEED_ENABLED = false;
        assertEquals(0, PriceIndexReseed.runOnce(market, store, config));
        assertEquals(80L, market.price(FALLEN));
        verify(store, never()).reseedPrices(any());
    }

    @Test
    void aFailedWriteLeavesTheIndexAsItWas() {
        doReturn(false).when(store).reseedPrices(any());
        Market market = market();
        assertEquals(0, PriceIndexReseed.runOnce(market, store, config));
        assertEquals(80L, market.price(FALLEN));
    }

    @Test
    void theDryRunChangesNothing() {
        config.PRICE_INDEX_RESEED_ENABLED = false;
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = false;
        Market market = market();
        String json = market.reseedDryRunJson();
        assertTrue(json.contains("\"would_change\":3,\"no_seed\":1,\"classes\":{\"ORE\":2,\"SCROLL\":1}"), json);
        assertTrue(json.contains("\"largest\":[{\"item\":2040804,\"seed\":900000,\"old\":76028,\"new\":720000"), json);
        assertEquals(80L, market.price(FALLEN));
        assertEquals(6, market.rows().size());
        verify(store, never()).reseedPrices(any());
        verify(store, never()).savePrice(anyInt(), anyLong(), anyInt());
    }

    @Test
    void theChangesetCreatesAndDropsTheTable() throws Exception {
        assertTrue(Files.readString(Path.of("src/main/resources/db/tables/061-price-reseed.sql"))
                .contains("CREATE TABLE IF NOT EXISTS living_price_reseed ("));
        assertTrue(Files.readString(Path.of("src/main/resources/db/tables/061-price-reseed-down.sql"))
                .contains("DROP TABLE IF EXISTS living_price_reseed;"));
        assertTrue(Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"))
                .contains("<changeSet id=\"70-price-reseed\""));
    }
}

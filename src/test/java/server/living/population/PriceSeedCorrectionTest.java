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
import java.util.Map;
import java.util.Set;

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
 * The one-time correction to the scroll formula's new seeds (docs/ECONOMY_PLAN.md section 10.15): only formula rows, those
 * with sales only over the threshold and only down, once by its own marker, with the guard on and every old price kept.
 * The store is an in-memory stand-in; the SQL itself was run on a check stack.
 */
class PriceSeedCorrectionTest {
    private static final int SUSPECT = 2044312;
    private static final int NEAR = 2044412;
    private static final int UNSOLD_DEAR = 2043017;
    private static final int UNSOLD_CHEAP = 2044012;
    private static final int BELOW = 2043112;
    private static final int TABLE = 1402012;
    private static final int NPC_MULTIPLE = 1332025;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationContent content = mock(PopulationContent.class);
    private final PopulationConfig config = new PopulationConfig();
    private final List<PriceIndexReseed.Move> written = new ArrayList<>();
    private boolean ran;
    private long seed;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        when(content.basket()).thenReturn(List.of());
        when(store.prices()).thenReturn(List.of());
        seed = market(1L).anchorPrice(SUSPECT);
        when(store.prices()).thenReturn(List.of(new int[]{SUSPECT, (int) (seed * 10), 8},
                new int[]{NEAR, (int) (seed * 1.5), 4}, new int[]{UNSOLD_DEAR, (int) (seed * 14), 0},
                new int[]{UNSOLD_CHEAP, (int) (seed / 2), 0}, new int[]{BELOW, (int) (seed / 4), 3},
                new int[]{TABLE, 40_000_000, 3}, new int[]{NPC_MULTIPLE, 90_000, 2}));
        when(store.priceReseedRan()).thenReturn(true); // the first reseed ran: none of this one's business
        when(store.priceCorrectionRan()).thenAnswer(call -> ran);
        when(store.correctPrices(any())).thenAnswer(call -> {
            written.addAll(call.getArgument(0));
            ran = true;
            return true;
        });
        config.PRICE_SEED_CORRECTION_ENABLED = true;
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = true;
    }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    private Market market(long npc) {
        return new Market(population, itemId -> itemId == NPC_MULTIPLE ? 1_000L : npc,
                itemId -> itemId / 10_000 == 204 ? Map.of("success", 60, "ACC", 3, "DEX", 2, "PAD", 1) : Map.of());
    }

    @Test
    void soldRowsOverTheThresholdAndEveryUnsoldFormulaRowTakeTheSeedAndTheirOldPricesAreKept() {
        Market market = market(1L);
        assertEquals(Market.SeedSource.SCROLL_FORMULA, market.seedSource(SUSPECT));
        assertEquals(3, PriceSeedCorrection.runOnce(market, store, config));
        assertEquals(seed, market.price(SUSPECT));
        assertEquals(seed, market.price(UNSOLD_DEAR));
        assertEquals(seed, market.price(UNSOLD_CHEAP), "up too, as any start sets it");
        assertEquals((int) (seed * 1.5), market.price(NEAR), "at, not over, the threshold");
        assertEquals((int) (seed / 4), market.price(BELOW), "a row with sales is never raised");
        assertEquals(40_000_000L, market.price(TABLE));
        assertEquals(90_000L, market.price(NPC_MULTIPLE));
        assertEquals(Set.of(move(SUSPECT, seed * 10, 8), move(UNSOLD_DEAR, seed * 14, 0), move(UNSOLD_CHEAP, seed / 2, 0)),
                Set.copyOf(written));
        assertEquals(8L, market.rows().get(SUSPECT)[1], "its samples stay");
        market.reseedUnmoved();
        verify(store, never()).savePrice(org.mockito.ArgumentMatchers.eq(UNSOLD_DEAR), anyLong(), anyInt());
        PriceSeedCorrection.Plan again = PriceSeedCorrection.plan(market, config);
        assertEquals(List.of(), again.all());
        verify(store, never()).reseedPrices(any());
    }

    private PriceIndexReseed.Move move(int itemId, long old, int samples) {
        return new PriceIndexReseed.Move(itemId, seed, (int) old, seed, samples, Market.SeedSource.SCROLL_FORMULA, 1.0d);
    }

    @Test
    void neverUnderWhatAnNpcPays() {
        Market market = market(seed * 4);
        PriceSeedCorrection.runOnce(market, store, config);
        assertEquals(market.npcUnit(SUSPECT), market.price(SUSPECT));
        assertTrue(market.price(SUSPECT) > seed);
    }

    @Test
    void itRunsExactlyOnceAcrossTwoStartsByItsOwnMarker() {
        assertEquals(3, PriceSeedCorrection.runOnce(market(1L), store, config));
        Market second = market(1L);
        assertEquals(0, PriceSeedCorrection.runOnce(second, store, config));
        assertEquals(seed * 10, second.price(SUSPECT));
        assertEquals(3, written.size());
    }

    @Test
    void itRefusesWithoutTheGuardAndDoesNothingWithItsSwitchOff() {
        Market market = market(1L);
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = false;
        assertEquals(0, PriceSeedCorrection.runOnce(market, store, config));
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = true;
        config.PRICE_SEED_CORRECTION_ENABLED = false;
        assertEquals(0, PriceSeedCorrection.runOnce(market, store, config));
        assertEquals(seed * 10, market.price(SUSPECT));
        assertEquals(seed * 14, market.price(UNSOLD_DEAR));
        verify(store, never()).correctPrices(any());
    }

    @Test
    void aFailedWriteLeavesTheIndexAsItWas() {
        doReturn(false).when(store).correctPrices(any());
        Market market = market(1L);
        assertEquals(0, PriceSeedCorrection.runOnce(market, store, config));
        assertEquals(seed * 10, market.price(SUSPECT));
    }

    @Test
    void theDryRunChangesNothingAndKeepsTheUnsoldRowsAStartIsAboutToSet() {
        config.PRICE_SEED_CORRECTION_ENABLED = false;
        Market market = market(1L);
        PriceSeedCorrection.runOnce(market, store, config);
        market.reseedUnmoved(); // sets the two without a sale, as today
        String json = market.correctionDryRunJson();
        assertTrue(json.contains("\"would_change\":1,\"would_reset_unsold\":0,\"unsold_at_start_count\":2"), json);
        assertTrue(json.contains("\"moves\":[{\"item\":2044312,\"source\":\"SCROLL_FORMULA\""), json);
        assertEquals(seed * 10, market.price(SUSPECT));
        verify(store, never()).correctPrices(any());
    }

    @Test
    void theChangesetCreatesAndDropsTheTable() throws Exception {
        assertTrue(Files.readString(Path.of("src/main/resources/db/tables/063-price-correction.sql"))
                .contains("CREATE TABLE IF NOT EXISTS living_price_correction ("));
        assertTrue(Files.readString(Path.of("src/main/resources/db/tables/063-price-correction-down.sql"))
                .contains("DROP TABLE IF EXISTS living_price_correction;"));
        assertTrue(Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"))
                .contains("<changeSet id=\"72-price-correction\""));
    }
}

package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.PriceQuote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read-only price lookup content PRICE answers use (docs/SERVER_KNOWLEDGE_PLAN.md 5.4 review): a quote only for
 * an item sales have nudged or a basket good; an item the index never priced, or only seeded (samples 0), has no
 * quote; and looking up never seeds a row or writes the DB (Market.price would do both, on the chat thread).
 */
class MarketKnownPriceTest {
    private static final int SOLD = 2070006;
    private static final int SEEDED = 4000021;
    private static final int BASKET = 1002357;
    private static final int UNSEEN = 2000006;

    @Test
    void theOldKumbiSeedOnSteeliesIsNoLongerAPrice() throws Exception {
        List<PopulationContent.Good> basket = new ArrayList<>();
        Matcher row = Pattern.compile("itemId:\\s*(\\d+),\\s*price:\\s*(\\d+)").matcher(
                Files.readString(Path.of("scripts/config/population/market.yaml"), StandardCharsets.US_ASCII));
        while (row.find()) {
            basket.add(new PopulationContent.Good(Integer.parseInt(row.group(1)), Long.parseLong(row.group(2))));
        }
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        PopulationContent content = mock(PopulationContent.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        // the live rows the mislabelled basket seeded: steelies at Kumbi's 25k, ilbis at Tobi's 90k, nothing sold since
        when(store.prices()).thenReturn(List.of(new int[]{2070005, 25_000, 0}, new int[]{2070006, 90_000, 0}));
        when(content.basket()).thenReturn(basket);
        Market market = new Market(population);

        assertNull(market.known(2070005), "steelies: no sale, no basket entry, so no price (was the Kumbi seed)");
        assertNull(market.known(2070006), "ilbis likewise (was the Tobi seed)");
        assertEquals(25_000L, market.known(2070003).price(), "the Kumbi seed now sits on Kumbis");
        assertEquals(90_000L, market.known(2070004).price());
    }

    @Test
    void aStaleSeedOnASwappedBasketIdGivesWayToTheBasketPrice() throws Exception {
        List<PopulationContent.Good> basket = new ArrayList<>();
        Matcher row = Pattern.compile("itemId:\\s*(\\d+),\\s*price:\\s*(\\d+)").matcher(
                Files.readString(Path.of("scripts/config/population/market.yaml"), StandardCharsets.US_ASCII));
        while (row.find()) {
            basket.add(new PopulationContent.Good(Integer.parseInt(row.group(1)), Long.parseLong(row.group(2))));
        }
        long bowAtt = basket.stream().filter(g -> g.itemId() == 2044501).findFirst().orElseThrow().price();
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        PopulationContent content = mock(PopulationContent.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        // live rows (2026-10-01): the old Claw seed on 2044501 (now Bow ATT) never sold; 2044701 (now Claw ATT) sold 3 times
        when(store.prices()).thenReturn(List.of(new int[]{2044501, 2_500_000, 0}, new int[]{2044701, 1_499_366, 3}));
        when(content.basket()).thenReturn(basket);
        Market market = new Market(population);

        assertEquals(bowAtt, market.known(2044501).price(), "the 0-sample seed is the old label's, not a price");
        assertEquals(1_499_366L, market.known(2044701).price(), "a row sales moved is kept");
        assertEquals(3, market.known(2044701).observations());
        verify(store, never()).savePrice(anyInt(), anyLong(), anyInt());
    }

    @Test
    void knowsOnlyWhatSalesOrTheBasketSay() {
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        PopulationContent content = mock(PopulationContent.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        when(store.prices()).thenReturn(List.of(new int[]{SOLD, 4_480_000, 12}, new int[]{SEEDED, 999, 0}));
        when(content.basket()).thenReturn(List.of(new PopulationContent.Good(BASKET, 25_000_000L)));
        Market market = new Market(population);

        PriceQuote sold = market.known(SOLD);
        assertEquals(4_480_000, sold.price());
        assertEquals(12, sold.observations());
        assertNull(market.known(SEEDED), "a seeded row nobody bought from is not a price");
        assertNull(market.known(UNSEEN));
        PriceQuote basket = market.known(BASKET);
        assertTrue(basket.basket());
        assertEquals(25_000_000L, basket.price());
        verify(store, never()).savePrice(anyInt(), anyLong(), anyInt());
        assertNull(market.known(UNSEEN), "still unknown: the lookup created no row");
    }
}

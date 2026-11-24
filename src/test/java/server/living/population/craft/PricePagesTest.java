package server.living.population.craft;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.living.population.Population;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The prices page (docs/WORLD_SYSTEMS_PLAN.md N8.4) against rows as economy_cpi_daily keeps them. */
class PricePagesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private final List<PricePages.Row> rows = new ArrayList<>();
    private final Population population = mock(Population.class);
    private LocalDate askedFrom;

    private PricePages pages() {
        PopulationConfig config = new PopulationConfig();
        config.BUY_ORDERS_ENABLED = true;
        when(population.config()).thenReturn(config);
        return new PricePages(population, new PricePages.Source() {
            @Override
            public List<PricePages.Row> since(LocalDate from) {
                askedFrom = from;
                return rows.stream().filter(row -> !row.day().isBefore(from)).toList();
            }

            @Override
            public boolean toldLately() {
                return false;
            }
        }, id -> "item " + id);
    }

    private void row(int daysAgo, int itemId, long price) {
        rows.add(new PricePages.Row(TODAY.minusDays(daysAgo), itemId, price));
    }

    @Test
    void eachBasketItemTodayAWeekAndAMonthAgo() {
        row(1, 2044701, 1_200_000L);
        row(8, 2044701, 1_000_000L);
        row(31, 2044701, 2_000_000L);
        row(1, 2000004, 900L);
        row(1, 2000005, 4_000L);
        row(8, 2000005, 4_010L);
        row(2, 9999999, 5L); // not in the basket
        assertEquals(List.of(new PricePages.Line(2000005, 4_000L, 4_010L, 0L), new PricePages.Line(2044701, 1_200_000L,
                        1_000_000L, 2_000_000L), new PricePages.Line(2000004, 900L, 0L, 0L)),
                pages().lines(List.of(2000005, 2044701, 2000004, 2000006), TODAY));
        assertTrue(!askedFrom.isAfter(TODAY.minusDays(34)));
    }

    @Test
    void aDayWithNoCloseTakesTheOneBeforeItAndAnOldPriceIsNotToday() {
        row(3, 2044701, 500L);
        row(12, 2044701, 400L);
        row(10, 2000004, 900L); // ten days without a price
        assertEquals(List.of(new PricePages.Line(2044701, 500L, 400L, 0L)),
                pages().lines(List.of(2044701, 2000004), TODAY));
    }

    @Test
    void inWordsAndNumbers() {
        assertEquals("about the same", PricePages.words(1_010L, 1_000L));
        assertEquals("up a little", PricePages.words(1_050L, 1_000L));
        assertEquals("down", PricePages.words(850L, 1_000L));
        assertEquals("up a lot", PricePages.words(1_500L, 1_000L));
        assertEquals("up wildly", PricePages.words(2_000L, 1_000L));
        assertEquals("new here", PricePages.words(5L, 0L));
        assertEquals("#i2044701# #e#t2044701##n\r\nToday: #b1.2m#k.\r\nA week ago: 1m, so up.\r\nA month ago: 2m, so down a lot.",
                PricePages.text(new PricePages.Line(2044701, 1_200_000L, 1_000_000L, 2_000_000L)));
        assertTrue(PricePages.text(new PricePages.Line(1, 10L, 0L, 0L)).contains("nobody wrote it down"));
    }
}

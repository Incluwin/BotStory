package server.economy.ledger;

import config.EconomyConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The price basket's arithmetic (docs/ECONOMY_PLAN.md E0): medians, the summary fields a player-shop sale is journaled
 * with, the shipped basket, and the index: 100 when the basket costs what it cost on its first day, the geometric mean
 * of each item's change otherwise, so one item doubling and one halving leaves it at 100.
 */
class PriceBasketTest {
    @Test
    void theMedianOfOddAndEvenLists() {
        assertEquals(20L, PriceBasket.median(List.of(30L, 10L, 20L)));
        assertEquals(25L, PriceBasket.median(List.of(40L, 10L, 20L, 30L)));
    }

    @Test
    void aSaleSummaryGivesItsNumbersAndSkipsTheRest() {
        Map<String, Long> fields = PriceBasket.fields(
                "shop=123 item=2040804 quantity=1 bundles=1 gross=950000 buyerTax=0 sellerTax=7600 fee=7600 escrow=ab-cd");
        assertEquals(2_040_804L, fields.get("item"));
        assertEquals(950_000L, fields.get("gross"));
        assertNull(fields.get("escrow"));
    }

    @Test
    void theShippedBasketIsFortyThreeGoods() {
        List<Integer> basket = PriceBasket.basket(new EconomyConfig().CPI_BASKET);
        assertEquals(43, basket.size());
        assertEquals(43, basket.stream().distinct().count(), "no item twice");
    }

    @Test
    void theIndexIsTheGeometricMeanOfEachItemsChange() {
        Map<Integer, Long> first = Map.of(1, 100L, 2, 400L, 3, 50L);
        assertEquals(100d, PriceBasket.index(List.of(price(1, 100), price(2, 400)), first), 1e-9);
        assertEquals(100d, PriceBasket.index(List.of(price(1, 200), price(2, 200)), first), 1e-9,
                "one doubled, one halved");
        assertEquals(200d, PriceBasket.index(List.of(price(1, 200), price(2, 800), price(3, 100)), first), 1e-9);
        assertNull(PriceBasket.index(List.of(price(9, 100)), first), "nothing priced twice yet");
    }

    private static PriceBasket.Price price(int itemId, long price) {
        return new PriceBasket.Price(itemId, price, PriceBasket.Source.LISTINGS, 1);
    }
}

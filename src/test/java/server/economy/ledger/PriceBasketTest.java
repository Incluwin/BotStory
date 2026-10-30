package server.economy.ledger;

import config.EconomyConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

    /**
     * Live 10-29: the 10-28 close priced 8 basket items at the index's old 10-meso seed and a sword under what an NPC
     * pays for it, all repaired by the price floor that night, and the 10-29 close read 941 (controller: basket
     * +841 %, buy budgets x0.75) for a basket that moved about -1 %. A seed or a price under the NPC's is no base:
     * the item's next recorded price is.
     */
    @Test
    void aSeedOrAPriceUnderTheNpcsIsNoBase() {
        LocalDate seeded = LocalDate.of(2026, 10, 28);
        LocalDate repaired = seeded.plusDays(1);
        int scroll = 2_043_002;
        int sword = 1_302_020;
        int potion = 2_000_004;
        List<PriceBasket.Recorded> history = List.of(
                new PriceBasket.Recorded(scroll, seeded, 10L),
                new PriceBasket.Recorded(sword, seeded, 20_333L),
                new PriceBasket.Recorded(potion, seeded, 5_000L),
                new PriceBasket.Recorded(scroll, repaired, 1_216_281L),
                new PriceBasket.Recorded(sword, repaired, 292_857L),
                new PriceBasket.Recorded(potion, repaired, 4_900L));
        Map<Integer, Integer> paid = Map.of(scroll, 1, sword, 117_143, potion, 1_200);
        java.util.function.IntToLongFunction npcPays = itemId -> paid.getOrDefault(itemId, 0);

        Map<Integer, Long> bases = PriceBasket.bases(history, npcPays);

        assertEquals(Map.of(scroll, 1_216_281L, sword, 292_857L, potion, 5_000L), bases);
        Double before = PriceBasket.index(PriceBasket.on(history, seeded, npcPays), bases);
        Double after = PriceBasket.index(PriceBasket.on(history, repaired, npcPays), bases);
        assertEquals(100d, before, 1e-9, "the seeded day counts its one market price, the potion's");
        assertEquals(100d * Math.pow(0.98, 1d / 3d), after, 1e-9, "the potion's -2 % over three items");
        assertEquals(-0.0067, after / before - 1d, 1e-4, "the day's change the controller reads, not +841 %");
    }

    private static PriceBasket.Price price(int itemId, long price) {
        return new PriceBasket.Price(itemId, price, PriceBasket.Source.LISTINGS, 1);
    }
}

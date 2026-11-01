package server.living.population.needs;

import org.junit.jupiter.api.Test;
import server.living.population.Market;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request's price the way players write it (2026-11-01 live: Somix's 1,500 meso offer for a hat read "1k ea", a third
 * less than it paid, and "ea" for a single hat): one decimal under 10k, never rounded up past what is paid; "ea" only
 * when it wants several.
 */
class NeedPriceWordsTest {
    @Test
    void underTenThousandKeepsOneDecimalCutNotRounded() {
        assertEquals("950", Market.formatPrice(950L));
        assertEquals("1k", Market.formatPrice(1_000L));
        assertEquals("1.5k", Market.formatPrice(1_500L));
        assertEquals("1.9k", Market.formatPrice(1_999L));
        assertEquals("2k", Market.formatPrice(2_040L));
        assertEquals("12k", Market.formatPrice(12_572L));
        assertEquals("1.2m", Market.formatPrice(1_200_000L));
    }

    @Test
    void eaOnlyWhenItWantsSeveral() {
        assertEquals("1.5k", NeedScreens.price(Need.open(1, NeedKind.GEAR, 1002017, 1, 1_500L, "cap", 0, 0L, 0L)));
        assertEquals("57 ea", NeedScreens.price(Need.open(1, NeedKind.SUPPLY, 2000002, 120, 57L, "easy_balrog", 0, 0L, 0L)));
    }
}

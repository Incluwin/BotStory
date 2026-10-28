package server.agents.economy.market;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The agent engine's valuation prior (docs/ECONOMY_PLAN.md E2): what the host installs, and 0 when it has nothing. */
class MarketPricePriorTest {
    @AfterEach
    void uninstall() {
        MarketPricePrior.install(null);
    }

    @Test
    void readsWhatTheHostInstalls() {
        assertEquals(0L, MarketPricePrior.of(2044701), "no host, no prior");
        MarketPricePrior.install(itemId -> itemId == 2044701 ? 1_500_000L : 0L);
        assertEquals(1_500_000L, MarketPricePrior.of(2044701));
        assertEquals(0L, MarketPricePrior.of(4000019));
    }

    @Test
    void aFailingOrNegativePriorIsNoPrior() {
        MarketPricePrior.install(itemId -> {
            throw new IllegalStateException("index not ready");
        });
        assertEquals(0L, MarketPricePrior.of(2044701));
        MarketPricePrior.install(itemId -> -5L);
        assertEquals(0L, MarketPricePrior.of(2044701));
    }
}

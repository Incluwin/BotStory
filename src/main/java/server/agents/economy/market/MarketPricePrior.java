package server.agents.economy.market;

import java.util.function.IntToLongFunction;

/**
 * The server's market price for an item, as a valuation prior (docs/ECONOMY_PLAN.md E2): the host that keeps a price
 * index installs it (the population's, learned from executed sales); 0 when none is installed or it has no price.
 */
public final class MarketPricePrior {
    private static final IntToLongFunction NONE = itemId -> 0L;
    private static volatile IntToLongFunction prior = NONE;

    private MarketPricePrior() {
    }

    /** null puts back "no prior". */
    public static void install(IntToLongFunction installed) {
        prior = installed == null ? NONE : installed;
    }

    public static long of(int itemId) {
        try {
            return Math.max(0L, prior.applyAsLong(itemId));
        } catch (RuntimeException failure) {
            return 0L;
        }
    }
}

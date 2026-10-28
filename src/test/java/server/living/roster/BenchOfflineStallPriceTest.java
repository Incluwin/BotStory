package server.living.roster;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An offline bench STALL has no buyer (docs/ECONOMY_PLAN.md E1): it sold its haul at 1.8x the NPC price, meso nobody
 * paid. It now sells at the NPC price as SELL does; economy.BENCH_OFFLINE_STALL_AT_NPC_PRICE off restores the markup.
 */
class BenchOfflineStallPriceTest {
    private final boolean shipped = YamlConfig.config.economy.BENCH_OFFLINE_STALL_AT_NPC_PRICE;

    @AfterEach
    void restore() {
        YamlConfig.config.economy.BENCH_OFFLINE_STALL_AT_NPC_PRICE = shipped;
    }

    @Test
    void anOfflineStallSellsAtTheNpcPrice() {
        YamlConfig.config.economy.BENCH_OFFLINE_STALL_AT_NPC_PRICE = true;
        assertEquals(1.0d, BenchLedger.offlineStallMarkup());
        YamlConfig.config.economy.BENCH_OFFLINE_STALL_AT_NPC_PRICE = false;
        assertEquals(1.8d, BenchLedger.offlineStallMarkup());
    }
}

package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.population.needs.NeedOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The price index under learned asks (docs/WORLD_SYSTEMS_PLAN.md I2.1): sales feed the index (8 % toward each sale)
 * and only the buy side is fixed (D16), so learned asks and the index could chase each other down. Through the real
 * Market paths (ask, sold + learnSales or unsold, settleAsks), from one seed index and the same buyer stream, with
 * browsing citizens (up to 0.9 x the current index) a share b of the buyers and the rest paying a fixed 1100: the index
 * never goes under what an NPC pays (the hard floor, independent of the index), and where it settles with the learner
 * on is within 10 % of where the fixed rule settles it. Measured 11-23 over 5 seeds: off/on means 960/1030 (b=0),
 * 946/951 (0.1), 742/725 (0.3), 405/458 (0.5), 401/415 (0.7). The fall to the floor at b >= 0.5 is today's rule's own
 * (the unsold step-down), not the learner's; the bound leaves room for seed noise around a worst gap of -2 %.
 */
class PriceIndexTrajectoryTest {
    static final int ORE = 4010000;
    static final long SEED_PRICE = 1_000L;
    static final long NPC = 400L;

    final Population population = mock(Population.class);
    final CitizenStore store = mock(CitizenStore.class);
    final PopulationConfig config = new PopulationConfig();

    PriceIndexTrajectoryTest() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(store.prices()).thenReturn(List.of(new int[]{ORE, (int) SEED_PRICE, 5}));
        config.MARKET_ENABLED = true;
    }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    /** Index after each round: browsers pay up to 0.9 x index (share {@code browse}), the rest up to {@code value}. */
    List<Long> run(boolean on, int rounds, double browse, long value, long seed) {
        config.PRICE_LEARNING_ENABLED = on;
        Market market = new Market(population, itemId -> NPC);
        Random buyers = new Random(seed);
        List<Long> out = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            long index = market.price(ORE);
            Map<Integer, double[]> asks = market.newAsks();
            long ask = market.ask(ORE, index, (short) 1, (short) 1, asks);
            double reservation = buyers.nextDouble() < browse ? 0.9d * index : value;
            boolean sells = ask <= reservation;
            if (sells) {
                market.sold(9, ORE, ask);
                market.learnSales();
            } else {
                market.unsold(ORE, ask);
            }
            if (asks != null) {
                market.settleAsks(asks, sells ? Map.of() : Map.of(ORE, 1));
            }
            out.add(market.price(ORE));
        }
        return out;
    }

    @Test
    void theIndexSettlesWithTheLearnerOnAboutWhereTheFixedRuleSettlesItAndNeverUnderTheNpcFloor() {
        for (double browse : new double[]{0.0, 0.1, 0.3, 0.5, 0.7}) {
            double[] settled = new double[2];
            for (int side = 0; side < 2; side++) {
                for (long seed = 1; seed <= 5; seed++) {
                    List<Long> trajectory = run(side == 1, 4_000, browse, 1_100L, seed);
                    for (int round = 0; round < trajectory.size(); round++) {
                        long index = trajectory.get(round);
                        assertTrue(index >= NPC, "b=" + browse + " seed " + seed + " round " + round + ": " + index);
                        if (round >= 3_000) {
                            settled[side] += index / 5_000.0d;
                        }
                    }
                }
            }
            assertTrue(settled[1] >= 0.9d * settled[0],
                    "b=" + browse + ": settled at " + settled[1] + " with the learner, " + settled[0] + " without");
        }
    }

}

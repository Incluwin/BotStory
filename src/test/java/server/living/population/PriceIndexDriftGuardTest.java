package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.population.needs.NeedOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The price index drift guard (docs/ECONOMY_PLAN.md section 10), through the real Market paths (ask, sold + learnSales or
 * unsold, settleAsks), 4000 stall rounds x 5 seeds from a seed price of 1000 with an NPC price of 400. Buyers are
 * browsers (up to 0.9 x the current index), errands (up to 1.3 x the current index) and absolute buyers (up to 1100).
 *
 * <p>The band. Absolute buyers support 1100: an index over it has no absolute buyer, and the asks' top (1.2 x, learned
 * 1.3 x) sits over the index, so it settles a little under. The guard settles between 0.65 and 1.0 x 1100 for every
 * share of browsers measured (lowest 750, b=0.7 with learned asks, whose asks go down to 0.85; highest 1031): 0.65
 * leaves room for seed noise and is 1.8 x the NPC floor's share (0.36). Relative buyers alone support no price at all,
 * so there the band is the seed's: 0.65 to 1.6 x 1000 (highest 1475, errands only with learned asks at up to 1.3).
 */
class PriceIndexDriftGuardTest {
    private static final int ORE = 4010000;
    private static final int SCROLL = 2040804;
    private static final long SEED_PRICE = 1_000L;
    private static final long NPC = 400L;
    private static final long ABSOLUTE = 1_100L;
    private static final double LOW = 0.65d;
    private static final double RELATIVE_HIGH = 1.6d;
    private static final double[] BROWSERS = {0.0d, 0.1d, 0.3d, 0.5d, 0.7d};
    private static final int ROUNDS = 4_000;

    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final CitizenStore store = mock(CitizenStore.class, withSettings().stubOnly());
    private final PopulationContent content = mock(PopulationContent.class, withSettings().stubOnly());
    private final PopulationConfig config = new PopulationConfig();

    PriceIndexDriftGuardTest() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.content()).thenReturn(content);
        when(content.basket()).thenReturn(List.of(new PopulationContent.Good(ORE, SEED_PRICE),
                new PopulationContent.Good(SCROLL, SEED_PRICE)));
        config.MARKET_ENABLED = true;
    }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    private Market market(int itemId, long start, long npc) {
        when(store.prices()).thenReturn(List.of(new int[]{itemId, (int) start, 5}));
        return new Market(population, id -> npc);
    }

    /** One seed's index after each round: {@code browse} + {@code errand} of the buyers are relative, the rest absolute. */
    private List<Long> run(boolean guard, boolean learned, int itemId, double browse, double errand, long start, long npc,
                           int rounds, long seed) {
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = guard;
        config.PRICE_LEARNING_ENABLED = learned;
        Market market = market(itemId, start, npc);
        Random buyers = new Random(seed);
        List<Long> out = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            long index = market.price(itemId);
            Map<Integer, double[]> asks = market.newAsks();
            long ask = market.ask(itemId, index, (short) 1, (short) 1, asks);
            double draw = buyers.nextDouble();
            double pays = draw < browse ? 0.9d * index : draw < browse + errand ? 1.3d * index : ABSOLUTE;
            boolean sells = ask <= pays;
            if (sells) {
                market.sold(9, itemId, ask);
                market.learnSales();
            } else {
                market.unsold(itemId, ask);
            }
            if (asks != null) {
                market.settleAsks(asks, sells ? Map.of() : Map.of(itemId, 1));
            }
            out.add(market.price(itemId));
        }
        return out;
    }

    private double settled(boolean guard, boolean learned, int itemId, double browse, double errand, long start, long npc) {
        double sum = 0.0d;
        for (long seed = 1; seed <= 5; seed++) {
            List<Long> all = run(guard, learned, itemId, browse, errand, start, npc, ROUNDS, seed);
            sum += all.subList(ROUNDS - 1_000, ROUNDS).stream().mapToDouble(Long::doubleValue).average().orElse(0.0d);
        }
        return sum / 5.0d;
    }

    private static void assertBetween(double low, double high, double value, String what) {
        assertTrue(value >= low && value <= high, what + ": " + value + " is outside " + low + ".." + high);
    }

    @Test
    void theIndexSettlesInsideTheBandAbsoluteDemandSupports() {
        for (boolean learned : new boolean[]{false, true}) {
            for (double browse : BROWSERS) {
                assertBetween(LOW * ABSOLUTE, ABSOLUTE, settled(true, learned, ORE, browse, 0.0d, SEED_PRICE, NPC),
                        "b=" + browse + " learned=" + learned);
            }
        }
    }

    @Test
    void theIndexStaysBoundedBothWaysWithNoAbsoluteBuyer() {
        for (boolean learned : new boolean[]{false, true}) {
            for (double[] mix : new double[][]{{0.5d, 0.5d}, {0.0d, 1.0d}, {1.0d, 0.0d}, {0.97d, 0.03d}}) {
                for (long seed = 1; seed <= 5; seed++) {
                    for (long index : run(true, learned, ORE, mix[0], mix[1], SEED_PRICE, NPC, ROUNDS, seed)) {
                        assertBetween(LOW * SEED_PRICE, RELATIVE_HIGH * SEED_PRICE, index,
                                "browsers " + mix[0] + " errands " + mix[1] + " learned=" + learned + " seed " + seed);
                    }
                }
            }
        }
    }

    /** What the guard is for: on today's rule the same mixes end on the NPC floor, or run away upward. */
    @Test
    void withoutTheGuardTheIndexDriftsBothWays() {
        assertTrue(settled(false, false, ORE, 0.5d, 0.0d, SEED_PRICE, NPC) < LOW * ABSOLUTE);
        assertTrue(settled(false, false, ORE, 0.5d, 0.5d, SEED_PRICE, NPC) < LOW * SEED_PRICE);
        assertTrue(settled(false, true, ORE, 0.5d, 0.5d, SEED_PRICE, NPC) < LOW * SEED_PRICE);
        assertTrue(settled(false, false, ORE, 0.0d, 1.0d, SEED_PRICE, NPC) > 100.0d * SEED_PRICE);
        assertTrue(settled(false, true, ORE, 0.0d, 1.0d, SEED_PRICE, NPC) > 100.0d * SEED_PRICE);
    }

    /** A scroll's NPC price is 1, no floor at all: the anchor is what holds a scroll that never sells. */
    @Test
    void aScrollNobodyBuysHoldsNearItsSeedAndAFallenOneComesBack() {
        assertTrue(settled(false, false, SCROLL, 1.0d, 0.0d, SEED_PRICE, 1L) < 0.1d * SEED_PRICE);
        for (boolean learned : new boolean[]{false, true}) {
            assertBetween(LOW * SEED_PRICE, SEED_PRICE, settled(true, learned, SCROLL, 1.0d, 0.0d, SEED_PRICE, 1L),
                    "held, learned=" + learned);
            assertBetween(LOW * SEED_PRICE, SEED_PRICE, settled(true, learned, SCROLL, 1.0d, 0.0d, 80L, 1L),
                    "recovered, learned=" + learned);
        }
    }

    /** The NPC floor is still the hard floor, whatever the anchor says. */
    @Test
    void theIndexNeverGoesUnderTheNpcPrice() {
        for (long index : run(true, false, ORE, 1.0d, 0.0d, 950L, 950L, 500, 1L)) {
            assertTrue(index >= 950L, "index " + index);
        }
    }

    /**
     * Off, every ask and index update is today's: each round's index and ask are compared with a copy of the rule as it
     * was before the guard, so a change to the off path fails here.
     */
    @Test
    void offPricesAndUpdatesAreTodaysRule() {
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = false;
        config.PRICE_INDEX_ANCHOR_PULL = 1.0d; // ignored while off
        Market market = market(ORE, SEED_PRICE, NPC);
        Random asks = new Random(config.SEED ^ 0x3a44e7L);
        Random buyers = new Random(7L);
        List<Long> recent = new ArrayList<>();
        long expected = SEED_PRICE;
        for (int round = 0; round < ROUNDS; round++) {
            long ask = market.ask(ORE, market.price(ORE), (short) 1, (short) 1, market.newAsks());
            assertEquals(Math.max(NPC, Math.round(expected * (0.9d + asks.nextDouble() * 0.3d))), ask, "ask, round " + round);
            long observed;
            if (buyers.nextDouble() < 0.6d) {
                market.sold(9, ORE, ask);
                market.learnSales();
                recent.add(ask);
                long[] last = recent.subList(Math.max(0, recent.size() - 7), recent.size()).stream()
                        .mapToLong(Long::longValue).sorted().toArray();
                long median = last[last.length / 2];
                observed = Math.max(Math.round(expected / 3.0d), Math.min(Math.round(expected * 3.0d), median));
            } else {
                market.unsold(ORE, ask);
                observed = Math.round(Math.min(ask, expected) * 0.9d);
            }
            expected = Math.max(NPC, Math.round(expected + (observed - expected) * 0.08d));
            assertEquals(expected, market.price(ORE), "index, round " + round);
        }
    }
}

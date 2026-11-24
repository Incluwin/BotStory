package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.population.needs.NeedOffers;
import server.living.store.LivingStore;
import server.living.roster.CompanionExecutor;
import server.living.roster.DirectExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Learned stall asks (docs/WORLD_SYSTEMS_PLAN.md I2.1) against a synthetic buyer population whose reservation prices
 * are known: the learner settles on the arm that earns most per bundle listed and sells more than the fixed rule
 * (0.9 + rand x 0.3) at an equal or better take; with the switch off a stall's prices are today's, draw for draw on
 * the same seed.
 */
class PriceLearningSimulationTest {
    private static final int ORE = 4010000;
    private static final int HAT = 1002094;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationConfig config = new PopulationConfig();

    PriceLearningSimulationTest() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(store.prices()).thenReturn(List.of(new int[]{ORE, 1_000, 5}, new int[]{HAT, 100_000, 5}));
    }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    /**
     * Who comes by a stall: for ore, six in ten buyers pay up to the index and four in ten up to 1.25 x it (the best
     * take per bundle listed is 1.00, which every buyer takes); for a hat, nine in ten gear errands pay up to 1.30 x the
     * index, the rest up to 0.90 (the best is 1.30).
     */
    private static double reservation(int itemId, Random buyers) {
        double roll = buyers.nextDouble();
        if (itemId == ORE) {
            return roll < 0.6d ? 1.0d : 1.25d;
        }
        return roll < 0.9d ? 1.30d : 0.90d;
    }

    /** {listed, sold, take over index} over the last {@code tail} of {@code rounds} one-bundle stalls of the item. */
    private static double[] run(Market market, int itemId, int rounds, int tail, Random buyers) {
        long unit = market.price(itemId);
        double listed = 0;
        double sold = 0;
        double take = 0;
        for (int round = 0; round < rounds; round++) {
            Map<Integer, double[]> asks = market.newAsks();
            long bundle = market.ask(itemId, unit, (short) 1, (short) 1, asks);
            double share = bundle / (double) unit;
            boolean sells = share <= reservation(itemId, buyers) + 1e-9;
            if (asks != null) {
                market.settleAsks(asks, sells ? Map.of() : Map.of(itemId, 1));
            }
            if (round >= rounds - tail) {
                listed++;
                sold += sells ? 1 : 0;
                take += sells ? share : 0;
            }
        }
        return new double[]{listed, sold, take};
    }

    private Market market(boolean on) {
        config.PRICE_LEARNING_ENABLED = on;
        return new Market(population, itemId -> itemId == ORE ? 10L : 20_000L);
    }

    @Test
    void theLearnerFindsTheBestArmAndSellsMoreThanTheFixedRule() {
        double[] fixedOre = run(market(false), ORE, 3_000, 1_000, new Random(5L));
        Market learning = market(true);
        double[] learnedOre = run(learning, ORE, 3_000, 1_000, new Random(5L));

        PriceArms asks = learning.prices().asks();
        assertEquals(1.00d, asks.multiplier(asks.best("ORE", config.PRICE_PRIOR_SAMPLES)), 1e-9, "ore: the index sells to all");
        double fixedSellThrough = fixedOre[1] / fixedOre[0];
        double learnedSellThrough = learnedOre[1] / learnedOre[0];
        assertTrue(learnedSellThrough > fixedSellThrough + 0.2d,
                "sell-through " + learnedSellThrough + " against the fixed rule's " + fixedSellThrough);
        assertTrue(learnedOre[2] / learnedOre[0] >= fixedOre[2] / fixedOre[0],
                "at an equal or better take per bundle listed: " + learnedOre[2] / learnedOre[0] + " vs " + fixedOre[2] / fixedOre[0]);

        double[] learnedHat = run(learning, HAT, 3_000, 1_000, new Random(6L));
        assertEquals(1.30d, asks.multiplier(asks.best("EQUIP", config.PRICE_PRIOR_SAMPLES)), 1e-9,
                "a hat gear errands pay 1.3x for: each class learns its own price");
        double[] fixedHat = run(market(false), HAT, 3_000, 1_000, new Random(6L));
        assertTrue(learnedHat[2] / learnedHat[0] > fixedHat[2] / fixedHat[0], "the hat takes more than the fixed rule");
        assertTrue(learnedHat[1] / learnedHat[0] >= fixedHat[1] / fixedHat[0] - 0.05d, "and sells about as often");
    }

    @Test
    void offTheStallPricesAreTodaysDrawForDrawOnTheSameSeed() {
        config.SEED = 1234L;
        Market off = market(false);
        assertNull(off.newAsks(), "off: no learned asks kept");
        Random today = new Random(config.SEED ^ 0x3a44e7L); // Market's own random, as before I2
        long[][] lines = {{ORE, 1_000, 100, 3}, {HAT, 100_000, 1, 1}, {ORE, 7, 100, 1}, {HAT, 1, 1, 1}};
        for (int round = 0; round < 200; round++) {
            long[] line = lines[round % lines.length];
            long npc = off.npcPays((int) line[0], (int) line[2]);
            long expected = Math.max(Math.max(1L, npc), Math.round(line[1] * line[2] * (0.9d + today.nextDouble() * 0.3d)));
            assertEquals(expected, off.ask((int) line[0], line[1], (short) line[2], (short) line[3], off.newAsks()),
                    "round " + round);
        }
        off.settleAsks(null, Map.of()); // nothing booked
        assertEquals("{\"on\":false,\"loaded\":false,\"ask\":{},\"offer\":{},\"bench\":{},\"buy\":{}}", off.prices().json());
    }

    @Test
    void onAStallKeepsOneArmPerItemAndItsBundlesAddUp() {
        Market on = market(true);
        Map<Integer, double[]> asks = on.newAsks();
        long first = on.ask(ORE, 1_000, (short) 100, (short) 2, asks);
        long second = on.ask(ORE, 1_000, (short) 100, (short) 3, asks);
        assertEquals(first, second, "the second stack of the same ore asks the same");
        double[] line = asks.get(ORE);
        assertEquals(5.0d, line[2], 0.0d);
        assertEquals(on.prices().asks().multiplier((int) line[0]), line[1], 1e-9);
        on.settleAsks(asks, Map.of(ORE, 2));
        PriceArms.Row row = on.prices().asks().rows().get(0);
        assertEquals(5.0d, row.n(), 0.0d);
        assertEquals(3 * line[1] / 5.0d, row.mean(), 1e-9, "three sold at the ask, two left at 0");
    }

    @Test
    void armStatisticsSurviveARestartThroughTheStore() {
        LivingWorld world = mock(LivingWorld.class);
        LivingStore living = mock(LivingStore.class);
        Map<String, LivingStore.YieldRow> saved = new HashMap<>();
        when(population.world()).thenReturn(world);
        CompanionExecutor inline = DirectExecutor.inline(); // stubs inside: not within another when()
        when(world.store()).thenReturn(living);
        when(world.executor()).thenReturn(inline);
        when(living.savePriceArm(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            LivingStore.YieldRow row = inv.getArgument(0);
            saved.put(row.jobBranch() + ":" + row.levelBand(), row);
            return true;
        });
        when(living.priceArms()).thenReturn(List.of());

        Market before = market(true);
        run(before, ORE, 400, 1, new Random(9L));
        assertTrue(!saved.isEmpty() && saved.keySet().stream().allMatch(key -> key.startsWith("ASK_ORE:")), saved.keySet()::toString);
        assertTrue(saved.values().stream().allMatch(row -> row.campMapId() == 0), "camp_map_id 0 for a price row");

        when(living.priceArms()).thenReturn(List.copyOf(saved.values()));
        Market after = market(true);
        assertTrue(after.prices().json().contains("\"loaded\":false"), "read on first use, not at boot");
        after.newAsks(); // first use reads the stored arms
        List<PriceArms.Row> was = before.prices().asks().rows();
        List<PriceArms.Row> now = after.prices().asks().rows();
        assertEquals(was.size(), now.size());
        for (int i = 0; i < was.size(); i++) {
            assertEquals(was.get(i).arm(), now.get(i).arm());
            assertEquals(was.get(i).n(), now.get(i).n(), 1e-9, "the same counts");
            assertEquals(was.get(i).mean(), now.get(i).mean(), 1e-9, "and means");
        }
    }

    @Test
    void anUnreadableStoreKeepsTheFixedRule() {
        LivingWorld world = mock(LivingWorld.class);
        LivingStore living = mock(LivingStore.class);
        when(population.world()).thenReturn(world);
        when(world.store()).thenReturn(living);
        when(living.priceArms()).thenThrow(new LivingStore.ReadFailed("companion_yield price", new RuntimeException()));
        Market on = market(true);
        assertNull(on.newAsks(), "no learned asks until the arms can be read");
    }
}

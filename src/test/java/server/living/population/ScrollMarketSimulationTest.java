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
import static org.mockito.Mockito.withSettings;

/**
 * The scroll market with errands and the stall cap (docs/ECONOMY_PLAN.md 10.14), through the real ask, sold, learnSales
 * and unsold paths with the drift guard on: 60 scroll ids from their seed prices, {@code lines} stall lines a day (live
 * 11-09 to 11-24: 1,320 a day, 597 with the cap at two) of which every unsold one steps its price down, and
 * {@code errands} a day, each for one of the kinds on sale at up to SCROLL_BUY_OVER_INDEX x the index. Forty days.
 * 2,000 errands a day (a buyer for every line) is past what SCROLL_ERRANDS_MAX lets out: the saturated end, bounded at
 * twice the seed (1.8 measured with the learner on, 1.24 off); under it the guard's stated 1.6 (1.49 measured: learner on, 300).
 * A closed model: what it shows is that the index holds its band at any number of errands, and the order of the
 * lines-a-sale ratio, not a forecast of sales.
 */
class ScrollMarketSimulationTest {
    private static final int KINDS = 60;
    private static final int FIRST = 2040000;
    private static final long SHOP = 10_000L;

    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final CitizenStore store = mock(CitizenStore.class, withSettings().stubOnly());
    private final PopulationConfig config = new PopulationConfig();

    private record Run(double index, double low, double high, double linesASale) { }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    private Run run(int lines, int errands, long seed, boolean learning) {
        config.PRICE_LEARNING_ENABLED = learning;
        config.PRICE_INDEX_DRIFT_GUARD_ENABLED = true;
        config.MARKET_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        PopulationContent content = mock(PopulationContent.class, withSettings().stubOnly());
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        when(store.prices()).thenReturn(List.of());
        Market seeds = new Market(population, itemId -> SHOP); // a shop price far under the band, so never the floor
        long[] anchor = new long[KINDS];
        List<int[]> rows = new ArrayList<>();
        for (int kind = 0; kind < KINDS; kind++) {
            anchor[kind] = seeds.anchorPrice(FIRST + kind);
            rows.add(new int[]{FIRST + kind, (int) anchor[kind], 5});
        }
        when(store.prices()).thenReturn(rows);
        Market market = new Market(population, itemId -> SHOP);
        Random random = new Random(seed);
        double low = Double.MAX_VALUE, high = 0, settled = 0;
        long sales = 0, listed = 0;
        for (int day = 0; day < 40; day++) {
            int[] buyers = new int[KINDS];
            for (int errand = 0; errand < errands; errand++) {
                buyers[random.nextInt(KINDS)]++;
            }
            for (int line = 0; line < lines; line++) {
                int kind = random.nextInt(KINDS);
                int item = FIRST + kind;
                long index = market.price(item);
                Map<Integer, double[]> asks = market.newAsks();
                long ask = market.ask(item, index, (short) 1, (short) 1, asks);
                boolean sells = buyers[kind] > 0 && ask <= Math.round(index * config.SCROLL_BUY_OVER_INDEX);
                if (sells) {
                    buyers[kind]--;
                    sales++;
                    market.sold(9, item, ask);
                    market.learnSales();
                } else {
                    market.unsold(item, ask);
                }
                if (asks != null) {
                    market.settleAsks(asks, sells ? Map.of() : Map.of(item, 1));
                }
                listed++;
            }
            double mean = 0;
            for (int kind = 0; kind < KINDS; kind++) {
                double ratio = market.price(FIRST + kind) / (double) anchor[kind];
                low = Math.min(low, ratio);
                high = Math.max(high, ratio);
                mean += ratio / KINDS;
            }
            if (day >= 20) {
                settled += mean / 20;
            }
        }
        return new Run(settled, low, high, listed / (double) Math.max(1L, sales));
    }

    @Test
    void theIndexHoldsItsBandAtAnyNumberOfErrandsAndLinesASaleFall() {
        for (boolean learning : new boolean[]{false, true}) {
            for (int lines : new int[]{1320, 597}) {
                double before = Double.MAX_VALUE;
                for (int errands : new int[]{5, 60, 300, 2000}) {
                    Run run = run(lines, errands, 3L, learning);
                    System.out.printf("learning=%s lines=%d errands=%d index=%.2f low=%.2f high=%.2f lines/sale=%.1f%n",
                            learning, lines, errands, run.index(), run.low(), run.high(), run.linesASale());
                    assertTrue(run.low() >= 0.6d && run.high() <= (errands == 2000 ? 2.0d : 1.6d), "lines " + lines + " errands " + errands + ": " + run);
                    assertTrue(run.linesASale() <= before, run.toString());
                    before = run.linesASale();
                }
            }
            assertTrue(run(597, 60, 3L, learning).linesASale() < 0.6d * run(1320, 60, 3L, learning).linesASale());
        }
    }
}

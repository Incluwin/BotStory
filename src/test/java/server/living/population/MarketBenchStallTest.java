package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.roster.BenchDutyDispatcher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The population market and the living bench (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): a
 * companion's bench stall prices from the price index (SELL a little under it), so its sales never move the index,
 * which would otherwise fall a little with every sale and every stall price with it; and browsing citizens buy from a
 * hired hand's stall (a player's companion now, though still a citizen), never from another citizen's.
 */
class MarketBenchStallTest {
    private static final int SHELL = 4000019;
    private static final int HUMAN = 10;
    private static final int BENCH_COMPANION = 907;
    private static final int CITIZEN = 5001;
    private static final int HIRED = 5002;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final LivingWorld world = mock(LivingWorld.class);
    private final BenchDutyDispatcher bench = mock(BenchDutyDispatcher.class);
    private final MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class);
    private final Market market;

    MarketBenchStallTest() {
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(store.prices()).thenReturn(List.<int[]>of(new int[] {SHELL, 100, 5}));
        living.when(LivingWorld::get).thenReturn(world);
        when(world.benchDuty()).thenReturn(bench);
        when(bench.sliceOf(BENCH_COMPANION)).thenReturn(mock(BenchDutyDispatcher.Slice.class));
        when(population.isCitizen(CITIZEN)).thenReturn(true);
        when(population.isCitizen(HIRED)).thenReturn(true);
        when(population.citizen(CITIZEN)).thenReturn(citizen(CITIZEN, Set.of()));
        when(population.citizen(HIRED)).thenReturn(citizen(HIRED, Set.of(Citizen.FLAG_HIRED)));
        market = new Market(population);
    }

    @AfterEach
    void close() {
        living.close();
    }

    @Test
    void aBenchStallsSalesNeverPullTheIndexDown() {
        for (int sale = 0; sale < 20; sale++) {
            market.sold(BENCH_COMPANION, SHELL, 85); // SELL: 0.85 x the index, sale after sale
        }
        market.learnSales();
        assertEquals(100L, market.price(SHELL), "the index its SELL price comes from stays where the others put it");

        market.sold(HUMAN, SHELL, 85);
        market.learnSales();
        assertTrue(market.price(SHELL) < 100L, "a human's sale under the index still moves it");
    }

    @Test
    void citizensBuyFromHumansAndHiredHandsButNotFromOtherCitizens() {
        assertTrue(market.buysFrom(HUMAN));
        assertTrue(market.buysFrom(BENCH_COMPANION), "a companion is no citizen");
        assertTrue(market.buysFrom(HIRED), "a hired hand is a player's companion now");
        assertFalse(market.buysFrom(CITIZEN));
    }

    private static Citizen citizen(int characterId, Set<String> flags) {
        return new Citizen(characterId, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}

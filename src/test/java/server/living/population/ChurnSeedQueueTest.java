package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import server.living.config.ContentLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Churn's queue of region-targeted seeds (docs/POPULATION_GUIDE.md "Targeted seeding"): requests are validated, each sweep
 * births at most SEED_BATCH of them in request order before the usual churn, even at TARGET_CITIZENS, a seed that cannot
 * be born is skipped and counted, fast seeding gets what is left of the batch and draws exactly the backdates it drew
 * before, and trickle births are unchanged.
 */
class ChurnSeedQueueTest {
    private static final PopulationContent CONTENT = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenGenerator generator = mock(CitizenGenerator.class);
    private int active;

    @BeforeEach
    void wire() {
        config.TARGET_CITIZENS = 600;
        config.SEED_BACKDATE_DAYS = 180;
        config.LIFE_PACE = 0.8d;
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(CONTENT);
        when(population.generator()).thenReturn(generator);
        when(population.activeCount()).thenAnswer(inv -> active);
        when(generator.birth(any(), anyInt())).thenAnswer(inv -> born());
        when(generator.seed(anyString())).thenAnswer(inv -> born());
    }

    @Test
    void requestsNeedAKnownRegionACountOf1To200AndABandInReach() {
        Churn churn = new Churn(population);

        assertThrows(IllegalArgumentException.class, () -> churn.requestSeeds("atlantis", 5));
        assertThrows(IllegalArgumentException.class, () -> churn.requestSeeds("orbis", 0));
        assertThrows(IllegalArgumentException.class, () -> churn.requestSeeds("orbis", -3));
        assertThrows(IllegalArgumentException.class, () -> churn.requestSeeds("orbis", Churn.SEED_REQUEST_MAX + 1));
        config.SEED_BACKDATE_DAYS = 10; // ten days of play cannot make anyone lv70
        assertThrows(IllegalArgumentException.class, () -> churn.requestSeeds("leafre", 5));
        assertTrue(churn.seedQueue().isEmpty());

        config.SEED_BACKDATE_DAYS = 180;
        assertEquals("leafre", churn.requestSeeds("leafre", Churn.SEED_REQUEST_MAX).id());
        churn.requestSeeds("orbis", 1);
        assertEquals(Map.of("leafre", Churn.SEED_REQUEST_MAX, "orbis", 1), churn.seedQueue());
    }

    @Test
    void eachSweepBirthsAtMostABatchInRequestOrderEvenAtTarget() {
        active = config.TARGET_CITIZENS; // births are paused; the target does not limit queued seeds
        Churn churn = new Churn(population);
        churn.requestSeeds("leafre", 5);
        churn.requestSeeds("orbis", 10);

        churn.step(System.currentTimeMillis());

        InOrder order = inOrder(generator);
        order.verify(generator, times(5)).seed("leafre");
        order.verify(generator, times(7)).seed("orbis");
        assertEquals(Map.of("orbis", 3), churn.seedQueue());
        assertEquals(config.TARGET_CITIZENS + Churn.SEED_BATCH, active);

        churn.step(System.currentTimeMillis());

        verify(generator, times(10)).seed("orbis");
        assertTrue(churn.seedQueue().isEmpty());
        verify(generator, never()).birth(any(), anyInt());
    }

    @Test
    void aSeedThatCannotBeBornIsSkippedAndCountedNotRetried() {
        active = config.TARGET_CITIZENS;
        when(generator.seed("leafre")).thenReturn(null);
        Churn churn = new Churn(population);
        churn.requestSeeds("leafre", 2);
        churn.requestSeeds("orbis", 1);

        churn.step(System.currentTimeMillis());
        churn.step(System.currentTimeMillis());

        verify(generator, times(2)).seed("leafre");
        verify(generator, times(1)).seed("orbis");
        assertEquals(2, churn.seedsSkipped());
        assertTrue(churn.seedQueue().isEmpty());
    }

    @Test
    void fastSeedingGetsWhatIsLeftOfTheBatchWithTheBackdatesItAlwaysDrew() {
        Churn churn = new Churn(population);
        churn.requestSeeds("victoria", 3);

        churn.step(System.currentTimeMillis());

        verify(generator, times(3)).seed("victoria");
        verify(generator, times(Churn.SEED_BATCH - 3)).birth(isNull(), anyInt());

        churn.step(System.currentTimeMillis()); // queue empty: a full fast-seed batch

        ArgumentCaptor<Integer> backdates = ArgumentCaptor.forClass(Integer.class);
        verify(generator, times(2 * Churn.SEED_BATCH - 3)).birth(isNull(), backdates.capture());
        Random random = new Random(config.SEED ^ 0xc0ffeeL); // Churn's own draws: the queue takes none of them
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 2 * Churn.SEED_BATCH - 3; i++) {
            double skew = random.nextDouble();
            expected.add((int) Math.round(skew * skew * config.SEED_BACKDATE_DAYS));
        }
        assertEquals(expected, backdates.getAllValues());
    }

    @Test
    void trickleBirthsAreUnchangedWithAnEmptyQueue() {
        active = config.TARGET_CITIZENS - 2; // inside the last tenth: BIRTHS_PER_DAY, not fast seeding
        Churn churn = new Churn(population);
        long now = System.currentTimeMillis();

        churn.step(now);
        churn.step(now + 1_000L); // inside the spacing

        verify(generator, times(1)).birth(isNull(), eq(0));
        verify(generator, never()).seed(anyString());
        assertTrue(churn.seedQueue().isEmpty());
    }

    private Citizen born() {
        active++;
        return new Citizen(1000 + active, "Seed" + active, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}

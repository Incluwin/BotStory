package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.living.population.Hearsay.Teller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-minute cost of word of mouth (docs/WORLD_SYSTEMS_PLAN.md N2) at 240 online citizens, the live target: the tie
 * pass over 40 parties of three and 40 circles of three, and a hearsay minute with every citizen in one of eight towns
 * (30 a town: 3,480 pairs, cut at PAIR_CHECKS_MAX) and a story to tell. Printed, and bounded loosely so a slow machine
 * never fails it; the store and the memories are stubs, so this is the in-memory work alone.
 */
class WordOfMouthCostTest {
    @Test
    void aMinuteAt240CitizensIsAFewMilliseconds() {
        PopulationConfig config = new PopulationConfig();
        config.CITIZEN_TIES_ENABLED = true;
        config.HEARSAY_ENABLED = true;
        Population population = mock(Population.class);
        when(population.config()).thenReturn(config);
        TieStore store = mock(TieStore.class);
        when(store.all()).thenReturn(new ArrayList<>());
        CitizenTies ties = new CitizenTies(population, store);
        when(population.ties()).thenReturn(ties);
        PersonMemories memories = mock(PersonMemories.class);
        when(population.memories()).thenReturn(memories);
        long now = System.currentTimeMillis();
        Map<Integer, PersonMemory> mind = new HashMap<>();
        mind.put(99, PersonMemory.none().with(PersonMemory.Kind.FAMED, 0, "", 1, now, config));
        when(memories.known(anyInt())).thenReturn(mind);

        List<List<Integer>> parties = new ArrayList<>();
        List<List<Integer>> circles = new ArrayList<>();
        for (int g = 0; g < 40; g++) {
            parties.add(List.of(g * 3 + 1, g * 3 + 2, g * 3 + 3));
            circles.add(List.of(121 + g * 3, 122 + g * 3, 123 + g * 3));
        }
        List<Teller> online = new ArrayList<>();
        for (int id = 1; id <= 240; id++) {
            online.add(new Teller(id, "C" + id, 0, id / 4, 0, 1, 100_000_000 + id % 8, true));
        }
        Hearsay hearsay = new Hearsay(population, new Random(1));
        for (int warm = 0; warm < 50; warm++) { // the JIT
            ties.together(parties, circles, now + warm * 60_000L);
            hearsay.tell(new ArrayList<>(online), now);
        }
        int minutes = 200;
        long tiesNs = 0L;
        long hearsayNs = 0L;
        for (int m = 0; m < minutes; m++) {
            long start = System.nanoTime();
            ties.together(parties, circles, now + (50 + m) * 60_000L);
            ties.flush(now + (50 + m) * 60_000L);
            long mid = System.nanoTime();
            hearsay.tell(new ArrayList<>(online), now);
            tiesNs += mid - start;
            hearsayNs += System.nanoTime() - mid;
        }
        double tiesMs = tiesNs / 1e6 / minutes;
        double hearsayMs = hearsayNs / 1e6 / minutes;
        System.out.printf("word of mouth at 240 citizens: ties %.3f ms/min, hearsay %.3f ms/min%n", tiesMs, hearsayMs);
        assertTrue(tiesMs < 50 && hearsayMs < 200, "ties " + tiesMs + " ms, hearsay " + hearsayMs + " ms a minute");
    }
}

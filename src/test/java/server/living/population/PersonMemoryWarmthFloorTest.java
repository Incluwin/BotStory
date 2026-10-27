package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.population.CitizenStore.MemoryRow;
import server.living.population.CitizenStore.MemoryWrite;
import server.living.population.PersonMemory.Kind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hire let go keeps the warmth of its friendship (docs/BOT_CONCEPT_PLAN.md C1.3): {@link PersonMemory#warmedTo} raises
 * a cooler memory to the floor and leaves a warmer one alone, and {@link PersonMemories#warmAtLeast} reaches a citizen
 * that is not online (a hire has no session) through the next flush, like any event.
 */
class PersonMemoryWarmthFloorTest {
    private static final long DAY = 86_400_000L;
    private static final int MIRA = 950;
    private static final int ANN = 10;

    private final PopulationConfig config = new PopulationConfig();

    @Test
    void aCoolerMemoryIsRaisedToTheFloorWithItsEpisodesKept() {
        long now = 100 * DAY;
        PersonMemory memory = PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - DAY, config);

        PersonMemory warmed = memory.warmedTo(40, now, config);

        assertEquals(40, warmed.feltWarmth(now, config));
        assertEquals(memory.episodes(), warmed.episodes());
        assertEquals(memory.firstMetAtMs(), warmed.firstMetAtMs());
    }

    @Test
    void aWarmerMemoryIsLeftAsItIs() {
        long now = 100 * DAY;
        PersonMemory warm = new PersonMemory(70, now, now - 5 * DAY, List.of());

        assertSame(warm, warm.warmedTo(40, now, config));
    }

    @Test
    void anEmptyMemoryMeetsThemNow() {
        long now = 100 * DAY;

        PersonMemory warmed = PersonMemory.none().warmedTo(15, now, config);

        assertEquals(15, warmed.feltWarmth(now, config));
        assertEquals(now, warmed.firstMetAtMs());
    }

    @Test
    void anOfflineCitizenTakesTheFloorAtTheNextFlush() {
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(mock(PresenceScheduler.class)); // no session: hired
        when(store.memory(anyInt(), anyInt())).thenReturn(new MemoryRow(ANN, PersonMemory.none(), 0L));
        when(store.memories(anyInt())).thenReturn(List.of());
        when(store.saveMemories(any())).thenReturn(true);
        PersonMemories memories = new PersonMemories(population);

        memories.warmAtLeast(MIRA, ANN, 40);
        verify(store, never()).saveMemories(any()); // memory only until the social tick
        memories.flush(System.currentTimeMillis());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MemoryWrite>> writes = ArgumentCaptor.forClass(List.class);
        verify(store).saveMemories(writes.capture());
        MemoryWrite write = writes.getValue().get(0);
        assertEquals(MIRA, write.characterId());
        assertEquals(ANN, write.playerId());
        assertTrue(write.memory().warmth() >= 40, write.memory().toString());
    }
}

package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.living.population.PersonMemory.Kind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The needs board's episodes (docs/BOT_CONCEPT_PLAN.md F1): bringing a citizen what it asked for in person warms it 8
 * (more than an answered question), leaving it at a board 4, each once a day whatever was brought; it is greeted with
 * the item later, told in the model's grounding, and stored like any other episode.
 */
class PersonMemoryFulfilledTest {
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_790_000_040_000L;
    private static final int HENESYS = 100_000_000;

    private final PopulationConfig config = new PopulationConfig();

    @Test
    void inPersonWarmsEightOnceADayWhateverWasBrought() {
        PersonMemory memory = PersonMemory.none().with(Kind.FULFILLED, HENESYS, "white potion", 40, T0, config);
        assertEquals(8, memory.feltWarmth(T0, config));
        assertEquals(Kind.HELPED.warmth + 6, Kind.FULFILLED.warmth, "more than an answered question");

        memory = memory.with(Kind.FULFILLED, HENESYS, "claw att 60%", 1, T0 + HOUR, config);
        assertEquals(8, memory.feltWarmth(T0 + HOUR, config), "the same day: no more warmth");
        assertEquals(1, memory.episodes().size(), "one episode, the latest thing brought");
        assertEquals("claw att 60%", memory.episodes().get(0).detail());

        memory = memory.with(Kind.FULFILLED, HENESYS, "white potion", 10, T0 + 25 * HOUR, config);
        assertEquals(16, memory.feltWarmth(T0 + 25 * HOUR, config), "the next day it counts again (8 faded a little, + 8)");
    }

    @Test
    void aBoardHandInWarmsLessAndApart() {
        PersonMemory memory = PersonMemory.none().with(Kind.FULFILLED_BOARD, 0, "work gloves", 1, T0, config);
        assertEquals(4, memory.feltWarmth(T0, config));
        memory = memory.with(Kind.FULFILLED, HENESYS, "white potion", 5, T0 + HOUR, config);
        assertEquals(12, memory.feltWarmth(T0 + HOUR, config), "in person the same day still counts");
    }

    @Test
    void itIsGreetedToldAndStored() {
        PersonMemory memory = PersonMemory.none().with(Kind.FULFILLED, HENESYS, "white potion", 40, T0, config);
        PersonMemory.Greeting greeting = memory.greeting(T0 + HOUR, config);
        assertEquals("memory.greet.fulfilled", greeting.trigger());
        assertEquals("white potion", greeting.episode().detail());
        assertEquals("they brought me white potion", PersonMemory.phrase(memory.episodes().get(0)));
        assertEquals("they left work gloves for me at the board", PersonMemory.phrase(
                new PersonMemory.Episode(Kind.FULFILLED_BOARD, T0, 0, 1, "work gloves")));

        List<PersonMemory.Episode> decoded = PersonMemory.decode(PersonMemory.encode(memory.episodes()));
        assertEquals(memory.episodes(), decoded);
    }
}

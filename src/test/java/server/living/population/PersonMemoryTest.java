package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.living.population.PersonMemory.Episode;
import server.living.population.PersonMemory.Kind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a citizen remembers of one player (docs/CITIZEN_PERSON_MEMORY_PLAN.md): episodes weigh by kind and fade with
 * age, repeats fold, warmth follows what happened and fades back to nothing, at most six episodes stay (the heaviest),
 * the words follow age, and the stored form round-trips.
 */
class PersonMemoryTest {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    /** On a whole minute, as the stored form keeps time. */
    private static final long T0 = 1_790_000_040_000L;
    private static final int KERNING = 103_000_000;

    private final PopulationConfig config = new PopulationConfig();

    @Test
    void aClearedRunWarmsAndIsRememberedForAboutAMonth() {
        PersonMemory memory = PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, T0, config);

        assertEquals(12, memory.warmth());
        assertEquals(T0, memory.firstMetAtMs());
        assertEquals(List.of(new Episode(Kind.PQ_CLEAR, T0, 0, 1, "kpq")), memory.episodes());
        assertEquals(1, memory.faded(T0 + 33 * DAY, config).episodes().size(), "still there after 33 days");
        assertTrue(memory.faded(T0 + 34 * DAY, config).episodes().isEmpty(), "weight 10 halves every 10 days: gone by 34");
    }

    @Test
    void aChatIsForgottenInAboutTenDaysAndThenTheWholeMemoryGoesOnceWarmthFades() {
        PersonMemory memory = PersonMemory.none().with(Kind.TALK, KERNING, "", 1, T0, config);

        assertEquals(1, memory.faded(T0 + 9 * DAY, config).episodes().size());
        PersonMemory later = memory.faded(T0 + 11 * DAY, config);
        assertTrue(later.episodes().isEmpty(), "weight 2 halves every 10 days: under 1 after 10");
        assertFalse(later.isEmpty(), "the warmth of 2 has not faded under 1 yet");
        assertTrue(memory.faded(T0 + 15 * DAY, config).isEmpty(), "2 halves every 14 days: under 1 after 14");
    }

    @Test
    void warmthFadesTowardNothingByItsHalfLife() {
        PersonMemory memory = PersonMemory.none().with(Kind.FAMED, KERNING, "", 1, T0, config);

        assertEquals(8, memory.feltWarmth(T0, config));
        assertEquals(4, memory.feltWarmth(T0 + 14 * DAY, config));
        assertEquals(2, memory.feltWarmth(T0 + 28 * DAY, config));
    }

    @Test
    void aChatFoldsIntoOneEpisodeAndOnlyItsFirstLinesWarm() {
        PersonMemory memory = PersonMemory.none();
        for (int line = 0; line < 10; line++) {
            memory = memory.with(Kind.TALK, KERNING, "", 1, T0 + line * 60_000L, config);
        }

        assertEquals(1, memory.episodes().size());
        assertEquals(10, memory.episodes().getFirst().count());
        assertEquals(2 + 1 + 1, memory.feltWarmth(T0 + 9 * 60_000L, config), "+2, then +1 for the next two lines");
        PersonMemory nextDay = memory.with(Kind.TALK, KERNING, "", 1, T0 + DAY, config);
        assertEquals(2, nextDay.episodes().size(), "the next day is another chat");
    }

    @Test
    void aPartyStintWarmsAPointEveryTenMinutesUpToEightAndWeighsMoreTheLongerItWas() {
        PersonMemory memory = PersonMemory.none();
        for (int minute = 1; minute <= 120; minute++) {
            memory = memory.with(Kind.PARTY, KERNING, "", 1, T0 + minute * 60_000L, config);
        }

        assertEquals(1, memory.episodes().size());
        Episode stint = memory.episodes().getFirst();
        assertEquals(120, stint.count());
        assertEquals(8, memory.warmth(), "a point every 10 minutes, at most 8");
        assertEquals(8.0d, stint.baseWeight(), 1e-9, "2 + at most 6");
        PersonMemory elsewhere = memory.with(Kind.PARTY, KERNING + 1, "", 1, T0 + 121 * 60_000L, config);
        assertEquals(2, elsewhere.episodes().size(), "a stint on another map is another episode");
    }

    @Test
    void oneRunToldTwiceIsOneEpisodeAndWarmsOnce() {
        PersonMemory memory = PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, T0, config)
                .with(Kind.PQ_CLEAR, 0, "kpq", 1, T0 + 60_000L, config);

        assertEquals(1, memory.episodes().size());
        assertEquals(12, memory.warmth());
        PersonMemory secondRun = memory.with(Kind.PQ_CLEAR, 0, "kpq", 1, T0 + HOUR, config);
        assertEquals(2, secondRun.episodes().size(), "an hour later it is the next run");
        assertEquals(24, secondRun.feltWarmth(T0 + HOUR, config));
    }

    @Test
    void aLetDownIsAGrudgeForTwoWeeksAndGreetsCoolNamingIt() {
        PersonMemory memory = PersonMemory.none().with(Kind.TALK, KERNING, "", 1, T0, config)
                .with(Kind.PQ_LEFT, 0, "kpq", 1, T0 + HOUR, config);

        assertEquals(-58, memory.warmth());
        assertTrue(memory.feltWarmth(T0 + 13 * DAY, config) <= PersonMemory.COLD, "a grudge for about two weeks");
        assertTrue(memory.feltWarmth(T0 + 15 * DAY, config) > PersonMemory.COLD);
        PersonMemory.Greeting greeting = memory.greeting(T0 + 5 * HOUR, config);
        assertEquals("memory.greet.cool", greeting.trigger());
        assertEquals(Kind.PQ_LEFT, greeting.episode().kind());
        assertEquals("memory.hello.cool", memory.hello(T0 + 5 * HOUR, config));
    }

    @Test
    void aFriendWhoLetsItDownOnceIsOnlyCool() {
        PersonMemory friend = new PersonMemory(45, T0, T0 - 30 * DAY, List.of())
                .with(Kind.PQ_LEFT, 0, "kpq", 1, T0, config);

        assertEquals(-15, friend.warmth());
        assertTrue(friend.warmth() > PersonMemory.COLD && friend.warmth() <= PersonMemory.COOL);
        assertEquals("memory.greet.cool", friend.greeting(T0 + 5 * HOUR, config).trigger());
    }

    @Test
    void theGreetingBringsUpTheStrongestRecentEpisodeElseItIsWarmOrVague() {
        PersonMemory memory = PersonMemory.none().with(Kind.TALK, KERNING, "", 1, T0, config)
                .with(Kind.BOUGHT, 910_000_001, "maple shield", 1, T0 + HOUR, config);

        PersonMemory.Greeting greeting = memory.greeting(T0 + DAY, config);
        assertEquals("memory.greet.bought", greeting.trigger());
        assertEquals("maple shield", greeting.episode().detail());

        PersonMemory withRun = memory.with(Kind.PQ_CLEAR, 0, "kpq", 1, T0 + 2 * HOUR, config);
        assertEquals("memory.greet.pq", withRun.greeting(T0 + DAY, config).trigger());
        PersonMemory fond = new PersonMemory(40, T0 + 2 * HOUR, T0, withRun.episodes());
        assertEquals("memory.greet.warm", fond.greeting(T0 + 15 * DAY, config).trigger(),
                "after two weeks it names nothing, but it still likes them");
        assertEquals("memory.greet.vague", memory.greeting(T0 + 15 * DAY, config).trigger());
        assertEquals("memory.hello", withRun.hello(T0 + DAY, config));
    }

    @Test
    void aSingleHiIsNotWorthAReunionGreeting() {
        PersonMemory once = PersonMemory.none().with(Kind.TALK, KERNING, "", 1, T0, config);
        PersonMemory twice = once.with(Kind.TALK, KERNING, "", 1, T0 + DAY, config);

        assertTrue(once.salience(T0 + 4 * HOUR, config) < PersonMemory.GREET_SALIENCE);
        assertTrue(twice.salience(T0 + DAY + 4 * HOUR, config) >= PersonMemory.GREET_SALIENCE);
        assertNull(PersonMemory.none().hello(T0, config));
    }

    @Test
    void atMostSixEpisodesStayTheHeaviest() {
        PersonMemory memory = PersonMemory.none();
        for (int day = 0; day < 6; day++) {
            memory = memory.with(Kind.TALK, KERNING, "", 1, T0 + day * DAY, config);
        }
        memory = memory.with(Kind.PQ_CLEAR, 0, "kpq", 1, T0 + 6 * DAY, config);

        assertEquals(6, memory.episodes().size());
        assertTrue(memory.episodes().stream().anyMatch(episode -> episode.kind() == Kind.PQ_CLEAR));
        assertTrue(memory.episodes().stream().noneMatch(episode -> episode.atMs() == T0), "the oldest chat went");
    }

    @Test
    void theRecallLineSaysWhenWhatAndWhereNewestFirstAndHowItFeels() {
        PersonMemory memory = PersonMemory.none().with(Kind.PARTY, KERNING, "", 40, T0, config)
                .with(Kind.PQ_CLEAR, 0, "kpq", 1, T0 + HOUR, config)
                .with(Kind.FAMED, 0, "", 1, T0 + HOUR, config);

        String recall = memory.recall(T0 + 2 * DAY, config, mapId -> mapId == KERNING ? "kerning city" : "", 256);

        assertEquals("met the other day; cleared KPQ together the other day; they famed me the other day; partied"
                + " together 40 min at kerning city the other day; i like them", recall);
        assertTrue(memory.recall(T0 + 2 * DAY, config, mapId -> "kerning city", 60).length() <= 60);
    }

    @Test
    void recallWordsFollowAge() {
        assertEquals("earlier", PersonMemory.when(5 * HOUR));
        assertEquals("yesterday", PersonMemory.when(20 * HOUR));
        assertEquals("the other day", PersonMemory.when(3 * DAY));
        assertEquals("last week", PersonMemory.when(8 * DAY));
        assertEquals("a while back", PersonMemory.when(20 * DAY));
    }

    @Test
    void theStoredFormRoundTripsAndADamagedLineIsForgotten() {
        PersonMemory memory = PersonMemory.none().with(Kind.SOLD, 910_000_001, "steely | throwing{knives}", 3, T0, config)
                .with(Kind.HELPED, KERNING, "a very long name that goes past thirty-two characters", 1, T0 + HOUR, config);

        String stored = PersonMemory.encode(memory.episodes());
        List<Episode> back = PersonMemory.decode(stored + "\nNOPE|1|2|3|x\nTALK|x|2|3|y");

        assertEquals(List.of(new Episode(Kind.SOLD, T0, 910_000_001, 3, "steely  throwingknives"),
                new Episode(Kind.HELPED, T0 + HOUR, KERNING, 1, "a very long name that goes past")), back);
        assertTrue(stored.length() < 600);
        assertTrue(PersonMemory.decode("").isEmpty() && PersonMemory.decode(null).isEmpty());
    }

    @Test
    void eightOfTheLongestEpisodesFitTheColumn() {
        String detail = "x".repeat(PersonMemory.DETAIL_CHARS);
        List<Episode> episodes = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            episodes.add(new Episode(Kind.PQ_CLEAR, 99_999_999L * 60_000L, 999_999_999, 99_999, detail));
        }
        assertTrue(PersonMemory.encode(episodes).length() <= 600, String.valueOf(PersonMemory.encode(episodes).length()));
    }
}

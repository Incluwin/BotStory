package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.Hearsay.Teller;
import server.living.population.PersonMemory.Episode;
import server.living.population.PersonMemory.Kind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Word of mouth (docs/WORLD_SYSTEMS_PLAN.md N2.3): decision D3 (hearsay alone keeps warmth within +/-10 and travels at
 * most two tellings from the witness), the server-wide cap on tellings and reads a minute, who talks to whom, and that
 * what a citizen only heard of is forgotten first.
 */
class HearsayTest {
    private static final long HOUR = 3_600_000L;
    private static final int HUMAN = 10;
    private static final int TOWN = 100_000_000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final Map<Integer, Map<Integer, PersonMemory>> minds = new HashMap<>();

    @BeforeEach
    void on() {
        config.HEARSAY_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.memories()).thenReturn(memories);
        when(memories.known(anyInt())).thenAnswer(call -> minds.getOrDefault((int) call.getArgument(0), Map.of()));
        // a telling lands in the listener's mind as PersonMemories.hear would put it there
        doAnswer(call -> {
            int listener = call.getArgument(0);
            int player = call.getArgument(1);
            Map<Integer, PersonMemory> mind = minds.computeIfAbsent(listener, id -> new HashMap<>());
            mind.put(player, mind.getOrDefault(player, PersonMemory.none()).heard(call.getArgument(2), call.getArgument(3),
                    call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7),
                    System.currentTimeMillis(), config, config.HEARSAY_WARMTH_MAX));
            return null;
        }).when(memories).hear(anyInt(), anyInt(), anyBoolean(), anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    // ---- decision D3: the warmth bound ---------------------------------------------------------

    @Test
    void hearsayAloneNeverWarmsPastTenNorCoolsPastMinusTen() {
        long now = System.currentTimeMillis();
        PersonMemory memory = PersonMemory.none();
        for (int i = 0; i < 12; i++) {
            memory = memory.heard(true, "W" + i, "ful", "W" + i, 1, TOWN, now, config, 10);
        }
        assertEquals(10, memory.feltWarmth(now, config));
        assertTrue(memory.feltWarmth(now, config) < PersonMemory.WARM, "friendlier, never someone it likes");
        memory = memory.heard(false, "X", "ks", "X", 1, TOWN, now, config, 10);
        assertEquals(6, memory.feltWarmth(now, config), "bad news brings it down from the bound");
        PersonMemory bad = PersonMemory.none();
        for (int i = 0; i < 12; i++) {
            bad = bad.heard(false, "W" + i, "dfm", "W" + i, 1, TOWN, now, config, 10);
        }
        assertEquals(-10, bad.feltWarmth(now, config));
        assertTrue(bad.feltWarmth(now, config) > PersonMemory.COLD, "never a grudge: invites are not refused for it");
        assertTrue(bad.hearsayOnly());
        assertEquals("memory.greet.heardbad", bad.greeting(now, config).trigger());
    }

    @Test
    void itsOwnExperienceWinsAndTheSameDeedHeardTwiceIsNothing() {
        long now = System.currentTimeMillis();
        PersonMemory known = PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - HOUR, config);
        assertSame(known, known.heard(false, "Ann", "dfm", "Ann", 1, TOWN, now, config, 10));
        PersonMemory once = PersonMemory.none().heard(true, "Ann", "ful", "Bo", 2, TOWN, now, config, 10);
        assertSame(once, once.heard(true, "Ann", "ful", "Cy", 2, TOWN, now, config, 10), "another teller, the same deed");
        Episode heard = once.episodes().get(0);
        assertEquals(Kind.HEARD_GOOD, heard.kind());
        assertEquals("Ann:ful:Bo", heard.detail(), "who it was done to, what, who told it");
        assertEquals(2, heard.count(), "how many tellings from the witness");
        assertEquals("memory.greet.heard", once.greeting(now, config).trigger());
        assertTrue(once.recall(now, config, id -> null, 256).contains("heard from Bo they helped Ann out"));
    }

    @Test
    void theEpisodeSurvivesTheColumnCodec() {
        long now = System.currentTimeMillis();
        PersonMemory memory = PersonMemory.none().heard(true, "Annabellerose", "clr-kpq", "Christophers", 2, TOWN, now,
                config, 10);
        List<Episode> back = PersonMemory.decode(PersonMemory.encode(memory.episodes()));
        assertEquals(Kind.HEARD_GOOD, back.get(0).kind());
        assertEquals(2, back.get(0).count());
        assertEquals("Annabellerose", PersonMemory.Heard.parse(back.get(0).detail()).witness());
        String longest = PersonMemory.encode(java.util.Collections.nCopies(8, new Episode(Kind.HEARD_GOOD,
                Long.MAX_VALUE / 2, 999_999_999, 2, "Abcdefghijkl:left-abcdef:Abcdefghijkl")));
        assertTrue(longest.length() <= 600, "eight of the longest fit the VARCHAR(600): " + longest.length());
    }

    // ---- decision D3: two tellings -------------------------------------------------------------

    @Test
    void aStoryTravelsTwoTellingsFromTheWitnessAndStops() {
        long now = System.currentTimeMillis();
        minds.put(1, new HashMap<>(Map.of(HUMAN, PersonMemory.none().with(Kind.FULFILLED, TOWN, "Red Potion", 1, now, config))));
        List<Teller> chain = new ArrayList<>();
        for (int id = 1; id <= 4; id++) {
            chain.add(new Teller(id, "C" + id, 0, 7, 0, 1, TOWN, true));
        }
        Hearsay hearsay = new Hearsay(population, new Random(3));
        for (int step = 0; step < 3; step++) { // 1 tells 2, 2 tells 3, 3 has nothing to tell 4
            Teller teller = chain.get(step);
            Teller listener = chain.get(step + 1);
            for (int tries = 0; tries < 40 && !minds.containsKey(listener.id()); tries++) {
                new Hearsay(population, new Random(tries)).tell(List.of(teller, listener), now);
            }
        }
        assertEquals(1, minds.get(2).get(HUMAN).episodes().get(0).count());
        assertEquals("C1:ful", minds.get(2).get(HUMAN).episodes().get(0).detail());
        assertEquals(2, minds.get(3).get(HUMAN).episodes().get(0).count());
        assertEquals("C1:ful:C2", minds.get(3).get(HUMAN).episodes().get(0).detail());
        assertFalse(minds.containsKey(4), "a second-hand story is not told again");
        assertNull(Hearsay.story("C3", HUMAN, minds.get(3).get(HUMAN).episodes().get(0), 4d, now, config));
        assertTrue(hearsay.report().startsWith("hearsay: on"));
    }

    @Test
    void onlyStrongDeedsThatAgreeWithTheFeelingAreTold() {
        long now = System.currentTimeMillis();
        Episode chat = new Episode(Kind.TALK, now, TOWN, 9, "");
        Episode left = new Episode(Kind.PQ_LEFT, now, 0, 1, "kpq");
        Episode oldFame = new Episode(Kind.FAMED, now - 40 * 24 * HOUR, 0, 1, "");
        assertNull(Hearsay.story("Ann", HUMAN, chat, 0, now, config), "a chat is not news");
        assertEquals("left-kpq", Hearsay.story("Ann", HUMAN, left, -30, now, config).deed());
        assertNull(Hearsay.story("Ann", HUMAN, left, 20, now, config), "made up since: not told");
        assertNull(Hearsay.story("Ann", HUMAN, oldFame, 5, now, config), "faded under STRONG");
    }

    // ---- the cap and who talks -----------------------------------------------------------------

    @Test
    void atMostTheCapAMinuteServerWideAndAsManyReads() {
        long now = System.currentTimeMillis();
        config.HEARSAY_TELLINGS_PER_MINUTE = 20;
        List<Teller> crowd = new ArrayList<>();
        for (int id = 1; id <= 60; id++) {
            crowd.add(new Teller(id, "C" + id, 0, 0, 3, 1, TOWN, true)); // one guild on one town map
            minds.put(id, new HashMap<>(Map.of(HUMAN + id, PersonMemory.none().with(Kind.FAMED, TOWN, "", 1, now, config))));
        }
        int told = new Hearsay(population, new Random(1)).tell(crowd, now);
        assertEquals(20, told);
        verify(memories, times(20)).hear(anyInt(), anyInt(), anyBoolean(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(memories, atMost(20)).known(anyInt());
    }

    @Test
    void strangersOnAFieldOrInAnotherTownDoNotTalk() {
        Hearsay hearsay = new Hearsay(population, new Random(1));
        Teller a = new Teller(1, "A", 0, 5, 0, 1, TOWN, true);
        assertTrue(hearsay.close(a, new Teller(2, "B", 0, 5, 0, 1, TOWN, true)), "circle mates in town");
        assertFalse(hearsay.close(a, new Teller(2, "B", 0, 6, 0, 1, TOWN, true)), "strangers in town");
        assertFalse(hearsay.close(new Teller(1, "A", 0, 5, 0, 1, 104_000_100, false),
                new Teller(2, "B", 0, 5, 0, 1, 104_000_100, false)), "circle mates on a field");
        assertTrue(hearsay.close(new Teller(1, "A", 9, 0, 0, 1, 104_000_100, false),
                new Teller(2, "B", 9, 0, 0, 1, 104_000_200, false)), "one party, anywhere");
        assertFalse(hearsay.close(a, new Teller(2, "B", 0, 5, 0, 2, TOWN, true)), "another channel");
        CitizenTies ties = mock(CitizenTies.class);
        when(population.ties()).thenReturn(ties);
        when(ties.between(1, 2)).thenReturn(CitizenTies.Kind.RIVAL);
        assertTrue(hearsay.close(a, new Teller(2, "B", 0, 6, 0, 1, TOWN, true)), "tied, even rivals gossip");
    }

    @Test
    void offNothingIsTold() {
        config.HEARSAY_ENABLED = false;
        PresenceScheduler presence = mock(PresenceScheduler.class);
        when(population.presence()).thenReturn(presence);
        new Hearsay(population, new Random(1)).minute(System.currentTimeMillis());
        verify(presence, never()).sessions();
        verify(memories, never()).hear(anyInt(), anyInt(), anyBoolean(), anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    // ---- eviction order ------------------------------------------------------------------------

    @Test
    void whatItOnlyHeardOfIsForgottenFirstEvenWhenMoreMemorable() {
        long now = System.currentTimeMillis();
        CitizenStore store = mock(CitizenStore.class);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(mock(PresenceScheduler.class));
        config.MEMORY_PEOPLE_MAX = 2;
        PersonMemory heard = PersonMemory.none().heard(true, "Ann", "ful", "Ann", 1, TOWN, now, config, 10);
        PersonMemory weak = PersonMemory.none().with(Kind.TALK, TOWN, "", 1, now - 5 * 24 * HOUR, config);
        assertTrue(heard.salience(now, config) > weak.salience(now, config));
        when(store.memory(anyInt(), anyInt())).thenAnswer(call -> new CitizenStore.MemoryRow(call.getArgument(1),
                PersonMemory.none(), 0L));
        when(store.saveMemories(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(store.memories(963)).thenReturn(List.of(new CitizenStore.MemoryRow(11, heard, 0L),
                new CitizenStore.MemoryRow(12, weak, 0L)));
        PersonMemories real = new PersonMemories(population);
        real.record(963, 13, Kind.FAMED, TOWN, "", 1);
        real.flush(now);
        verify(store).forgetMemories(963, List.of(11));
    }

    @Test
    void thePruneForgetsHearsayOnlyRowsFirst() {
        assertTrue(CitizenStore.HEARSAY_ONLY.contains("'HEARD_'"));
        // the SQL counts lines and HEARD_ markers; the same arithmetic in Java over encoded columns
        long now = System.currentTimeMillis();
        String onlyHeard = PersonMemory.encode(PersonMemory.none().heard(true, "A", "ful", "A", 1, 0, now, config, 10)
                .heard(false, "B", "ks", "B", 1, 0, now, config, 10).episodes());
        String mixed = PersonMemory.encode(List.of(new Episode(Kind.TALK, now, 0, 1, ""),
                new Episode(Kind.HEARD_GOOD, now, 0, 1, "A:ful")));
        assertTrue(hearsayOnly(onlyHeard));
        assertFalse(hearsayOnly(mixed));
        assertFalse(hearsayOnly(""));
    }

    private static boolean hearsayOnly(String episodes) {
        int lines = episodes.length() - episodes.replace("\n", "").length() + 1;
        int heard = (episodes.length() - episodes.replace("HEARD_", "").length()) / 6;
        return !episodes.isEmpty() && lines == heard;
    }
}

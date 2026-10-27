package server.living.population;

import client.Character;
import config.LivingWorldConfig;
import config.PopulationConfig;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import server.living.population.CitizenStore.MemoryRow;
import server.living.population.CitizenStore.MemoryWrite;
import server.living.population.PersonMemory.Episode;
import server.living.population.PersonMemory.Kind;
import server.living.roster.CompanionLetters;
import server.living.roster.RosterService;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The memories in use (docs/CITIZEN_PERSON_MEMORY_PLAN.md): an event before the pair's row is read waits for it and
 * goes out in the tick's one batch; a player back after three hours apart is greeted by name with what it remembers,
 * once, from within earshot, one citizen per player per gap; a citizen past its people cap forgets its least memorable;
 * logging off writes and lets go; the hourly prune; and the kill switch touches nothing.
 */
class PersonMemoriesTest {
    private static final long HOUR = 3_600_000L;
    private static final int KERNING = 103_000_000;
    private static final int ANN = 10;
    private static final int BOB = 11;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Map<Integer, MemoryRow> rows = new HashMap<>();
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private PersonMemories memories;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        when(population.fields()).thenReturn(fields);
        when(fields.name(anyInt())).thenReturn("Kerning City");
        when(store.memory(anyInt(), anyInt())).thenAnswer(call -> rows.getOrDefault(key(call.getArgument(0), call.getArgument(1)),
                new MemoryRow(call.getArgument(1), PersonMemory.none(), 0L)));
        when(store.saveMemories(any())).thenReturn(true);
        when(store.memories(anyInt())).thenReturn(List.of());
        when(chatter.speakTo(any())).thenReturn(true);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        memories = new PersonMemories(population);
    }

    @AfterEach
    void tearDown() {
        identity.close();
    }

    private static int key(int citizenId, int playerId) {
        return citizenId * 1000 + playerId;
    }

    @Test
    void eventsBeforeTheRowIsReadWaitForItAndGoOutInTheTicksOneBatch() {
        long now = System.currentTimeMillis();
        PersonMemory before = PersonMemory.none().with(Kind.TALK, KERNING, "", 1, now - 2 * 24 * HOUR, config);
        rows.put(key(963, ANN), new MemoryRow(ANN, before, now - 2 * 24 * HOUR));

        memories.record(963, ANN, Kind.PQ_CLEAR, 0, "kpq", 1);
        memories.record(963, ANN, Kind.BOUGHT, 910_000_001, "maple shield", 1);
        memories.record(964, ANN, Kind.FAMED, KERNING, "", 1);
        assertNull(memories.peek(963, ANN), "nothing read on the event's thread");
        verifyNoInteractions(store);

        memories.flush(now);

        verify(store).memory(963, ANN);
        ArgumentCaptor<List<MemoryWrite>> batch = writes();
        verify(store, times(1)).saveMemories(batch.capture());
        assertEquals(2, batch.getValue().size());
        MemoryWrite pip = batch.getValue().stream().filter(write -> write.characterId() == 963).findFirst().orElseThrow();
        assertEquals(List.of(Kind.TALK, Kind.PQ_CLEAR, Kind.BOUGHT), pip.memory().episodes().stream().map(Episode::kind).toList());
        assertNull(memories.peek(963, ANN), "written, and they are not together: the row has it all");

        memories.flush(now + 60_000L);
        verify(store, times(1)).saveMemories(any()); // nothing new: no second write
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<MemoryWrite>> writes() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void aPlayerBackAfterThreeHoursIsGreetedByNameWithTheRunOnce() {
        long now = System.currentTimeMillis();
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - 20 * HOUR, config),
                now - 4 * HOUR));
        CitizenSession pip = session(963, "Pip", new Point(0, 0));
        Character ann = human(ANN, "Ann", new Point(200, 0));

        memories.seen(pip, ann, now);
        memories.seen(pip, ann, now + 60_000L);

        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, times(1)).speakTo(said.capture());
        ChatterEngine.Event event = said.getValue();
        assertEquals("memory.greet.pq", event.trigger);
        assertEquals("Ann", event.subjectName);
        assertEquals("KPQ", event.slots.get("pq"));
        assertEquals("yesterday", event.slots.get("when"));
        assertTrue(pip.journal.summary(200).contains("ran into Ann again"));
    }

    @Test
    void aHiddenGmIsNotGreetedUntilTheyShowThemselves() {
        long now = System.currentTimeMillis();
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - 20 * HOUR, config),
                now - 4 * HOUR));
        CitizenSession pip = session(963, "Pip", new Point(0, 0));
        Character ann = human(ANN, "Ann", new Point(100, 0));
        when(ann.isHidden()).thenReturn(true);

        memories.seen(pip, ann, now);
        verify(chatter, never()).speakTo(any());

        when(ann.isHidden()).thenReturn(false);
        memories.seen(pip, ann, now + 60_000L);
        verify(chatter).speakTo(any());
    }

    @Test
    void togetherAnHourAgoIsNoReunion() {
        long now = System.currentTimeMillis();
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - 2 * HOUR, config),
                now - HOUR));

        memories.seen(session(963, "Pip", new Point(0, 0)), human(ANN, "Ann", new Point(100, 0)), now);

        verify(chatter, never()).speakTo(any());
    }

    @Test
    void outOfEarshotItWaitsForALaterMinuteOfTheSameVisit() {
        long now = System.currentTimeMillis();
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.SOLD, 910_000_001, "work gloves", 1,
                now - 30 * HOUR, config).with(Kind.TALK, KERNING, "", 2, now - 30 * HOUR, config), now - 5 * HOUR));
        CitizenSession pip = session(963, "Pip", new Point(0, 0));
        Character ann = human(ANN, "Ann", new Point(1200, 0));

        memories.seen(pip, ann, now);
        verify(chatter, never()).speakTo(any());

        when(ann.getPosition()).thenReturn(new Point(300, 0));
        memories.seen(pip, ann, now + 60_000L);
        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter).speakTo(said.capture());
        assertEquals("memory.greet.sold", said.getValue().trigger);
        assertEquals("work gloves", said.getValue().item);

        memories.seen(pip, ann, now + 15 * 60_000L);
        verify(chatter, times(1)).speakTo(any());
    }

    @Test
    void oneCitizenGreetsAPlayerPerGapAndAStrangerIsNotGreeted() {
        long now = System.currentTimeMillis();
        PersonMemory friends = PersonMemory.none().with(Kind.PARTY, KERNING, "", 60, now - 26 * HOUR, config);
        rows.put(key(963, ANN), new MemoryRow(ANN, friends, now - 26 * HOUR));
        rows.put(key(964, ANN), new MemoryRow(ANN, friends, now - 26 * HOUR));
        Character ann = human(ANN, "Ann", new Point(100, 0));
        Character bob = human(BOB, "Bob", new Point(100, 0));
        CitizenSession pip = session(963, "Pip", new Point(0, 0));
        CitizenSession mia = session(964, "Mia", new Point(0, 0));

        memories.seen(pip, ann, now);
        memories.seen(mia, ann, now);
        memories.seen(pip, bob, now);

        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter, times(1)).speakTo(said.capture());
        assertEquals("memory.greet.party", said.getValue().trigger);
        assertEquals("kerning city", said.getValue().slots.get("place"));

        memories.seen(mia, ann, now + config.MEMORY_GREET_GAP_MS + 60_000L);
        verify(chatter, times(2)).speakTo(any()); // her reunion is still waiting within the visit, and the gap is over
    }

    @Test
    void aRememberedPlayersHiGetsTheRememberedHelloAndAStrangersDoesNot() {
        long now = System.currentTimeMillis();
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - 2 * HOUR, config),
                now - 60_000L));
        CitizenSession pip = session(963, "Pip", new Point(0, 0));
        Character ann = human(ANN, "Ann", new Point(50, 0));
        Character bob = human(BOB, "Bob", new Point(50, 0));
        memories.seen(pip, ann, now);
        memories.seen(pip, bob, now);
        verify(chatter, never()).speakTo(any());

        assertTrue(memories.hello(pip, ann, now));
        assertFalse(memories.hello(pip, bob, now), "a stranger gets the old coin flip");

        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter).speakTo(said.capture());
        assertEquals("memory.hello", said.getValue().trigger);
        assertEquals("Ann", said.getValue().subjectName);
    }

    @Test
    void aCitizenPastItsPeopleCapForgetsItsLeastMemorable() {
        long now = System.currentTimeMillis();
        config.MEMORY_PEOPLE_MAX = 2;
        PersonMemory strong = PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - HOUR, config);
        PersonMemory weak = PersonMemory.none().with(Kind.TALK, KERNING, "", 1, now - 5 * 24 * HOUR, config);
        when(store.memories(963)).thenReturn(List.of(new MemoryRow(ANN, strong, now - HOUR), new MemoryRow(BOB, weak, 0L)));

        memories.record(963, 12, Kind.FAMED, KERNING, "", 1);
        memories.flush(now);

        verify(store).forgetMemories(963, List.of(BOB));
        verify(store).saveMemories(any());
    }

    @Test
    void someoneItAlreadyRemembersTakesNoRoom() {
        long now = System.currentTimeMillis();
        config.MEMORY_PEOPLE_MAX = 1;
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.TALK, KERNING, "", 1, now - HOUR, config), now - HOUR));

        memories.record(963, ANN, Kind.TALK, KERNING, "", 1);
        memories.flush(now);

        verify(store, never()).memories(anyInt());
        verify(store, never()).forgetMemories(anyInt(), any());
    }

    @Test
    void loggingOffWritesItsMemoriesAndLetsThemGo() {
        memories.record(963, ANN, Kind.TALK, KERNING, "", 1);
        memories.record(964, ANN, Kind.TALK, KERNING, "", 1);
        when(presence.session(964)).thenReturn(mock(CitizenSession.class));

        memories.release(963);

        ArgumentCaptor<List<MemoryWrite>> batch = writes();
        verify(store).saveMemories(batch.capture());
        assertEquals(List.of(963), batch.getValue().stream().map(MemoryWrite::characterId).toList());
        assertNull(memories.peek(963, ANN), "gone from the cache");
    }

    @Test
    void aFailedBatchIsTriedAgainNextTick() {
        long now = System.currentTimeMillis();
        when(store.saveMemories(any())).thenReturn(false, true);
        when(presence.session(963)).thenReturn(mock(CitizenSession.class));
        memories.record(963, ANN, Kind.TALK, KERNING, "", 1);

        memories.flush(now);
        memories.flush(now + 60_000L);
        memories.flush(now + 120_000L);

        verify(store, times(2)).saveMemories(any());
    }

    @Test
    void thePruneRunsOnceAnHour() {
        long now = System.currentTimeMillis();

        memories.flush(now);
        verify(store, never()).pruneMemories(anyLong(), anyInt());
        memories.flush(now + HOUR + 1);
        memories.flush(now + HOUR + 60_000L);

        verify(store, times(1)).pruneMemories(eq(now + HOUR + 1 - 60 * 24 * HOUR), eq(20000));
    }

    @Test
    void theKillSwitchReadsAndWritesNothing() {
        long now = System.currentTimeMillis();
        config.MEMORY_ENABLED = false;
        rows.put(key(963, ANN), new MemoryRow(ANN, PersonMemory.none().with(Kind.PQ_CLEAR, 0, "kpq", 1, now - 20 * HOUR, config),
                now - 4 * HOUR));

        memories.record(963, ANN, Kind.TALK, KERNING, "", 1);
        memories.seen(session(963, "Pip", new Point(0, 0)), human(ANN, "Ann", new Point(0, 0)), now);
        memories.flush(now + 2 * HOUR);
        memories.release(963);

        verifyNoInteractions(store, chatter);
        assertNull(memories.peek(963, ANN));
        assertEquals(0, memories.warmth(963, ANN));
    }

    @Test
    void aCitizenAsksAfterAWarmFriendWhoIsOfflineAndTheLettersHearOfIt() {
        long now = System.currentTimeMillis();
        LivingWorldConfig living = new LivingWorldConfig();
        living.LETTERS_ENABLED = true;
        living.LETTERS_ASK_CHANCE = 1.0d;
        LivingWorld world = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        CompanionLetters letters = mock(CompanionLetters.class);
        when(population.world()).thenReturn(world);
        when(world.config()).thenReturn(living);
        when(world.roster()).thenReturn(roster);
        when(roster.letters()).thenReturn(letters);
        when(letters.on()).thenReturn(true);
        when(letters.mayAsk(anyInt(), anyLong())).thenReturn(true);
        World game = mock(World.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(population.gameWorld()).thenReturn(game);
        when(game.getPlayerStorage().getCharacterById(anyInt())).thenReturn(null); // offline
        when(game.getPlayerStorage().getCharacterById(BOB)).thenReturn(mock(Character.class)); // Bob is on
        PersonMemory warm = new PersonMemory(60, now, now - 24 * HOUR, List.of());
        PersonMemory cool = new PersonMemory(20, now, now - 24 * HOUR, List.of());
        when(store.memories(963)).thenReturn(List.of(new MemoryRow(BOB, warm, 0L), new MemoryRow(12, cool, 0L),
                new MemoryRow(ANN, warm, 0L)));
        CitizenSession pip = session(963, "Pip", new Point(0, 0));
        try (MockedStatic<Character> names = mockStatic(Character.class)) {
            names.when(() -> Character.getNameById(ANN)).thenReturn("Ann");

            assertTrue(memories.askAfter(pip, now));

            ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
            verify(chatter).speakTo(said.capture());
            assertEquals("memory.asked", said.getValue().trigger);
            assertEquals("Ann", said.getValue().subjectName, "the warm friend who is away, not Bob who is on");
            verify(letters).asked(ANN, "Pip", now);

            living.LETTERS_ENABLED = false;
            when(letters.on()).thenReturn(false);
            assertFalse(memories.askAfter(pip, now), "the kill switch");
        }
    }

    private CitizenSession session(int id, String name, Point at) {
        Citizen citizen = new Citizen(id, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = new CitizenSession(citizen, 0L, 24 * HOUR, false);
        session.entry = mock(AgentRuntimeEntry.class);
        session.mapId = KERNING;
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(at);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(agent);
        when(presence.session(id)).thenReturn(session);
        return session;
    }

    private Character human(int id, String name, Point at) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getLevel()).thenReturn(30);
        when(chr.getJob()).thenReturn(client.Job.HUNTER);
        when(chr.getPosition()).thenReturn(at);
        return chr;
    }
}

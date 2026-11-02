package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.TownConversations.Line;
import server.living.population.TownConversations.Script;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Citizens standing together in town talk among themselves (SoloMapling's ConversationManager, docs/SOLOMAPLING_PORT.md):
 * the ported scripts load, only citizens standing close form a group, recent scripts are not repeated, and a script
 * plays line by line in its speakers' voices, only with CONVERSATIONS_ENABLED on and a player there to hear it.
 */
class TownConversationsTest {
    private static final int HENESYS = 100_000_000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final VoicePacks voices = mock(VoicePacks.class);
    private final MapleMap henesys = mock(MapleMap.class);
    private final Deque<Runnable> steps = new ArrayDeque<>();
    private final List<CitizenSession> sessions = new ArrayList<>();
    private final List<Character> onMap = new ArrayList<>();
    private final Set<Character> agents = new HashSet<>();
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        when(population.voices()).thenReturn(voices);
        when(presence.sessions()).thenReturn(sessions);
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(call -> call.getArgument(1));
        when(henesys.getAllPlayers()).thenReturn(onMap);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        AgentPresence.install(agents::contains);
    }

    @AfterEach
    void close() {
        identity.close();
        AgentPresence.install(null);
    }

    private CitizenSession townie(int id, int x, int y) {
        Citizen citizen = new Citizen(id, "C" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        session.activity = Activity.TOWN;
        session.mapId = HENESYS;
        session.channel = 1;
        session.entry = mock(AgentRuntimeEntry.class);
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(x, y));
        when(agent.getMap()).thenReturn(henesys);
        when(agent.getMapId()).thenReturn(HENESYS);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(agent);
        when(presence.session(id)).thenReturn(session);
        sessions.add(session);
        onMap.add(agent);
        agents.add(agent);
        return session;
    }

    private static Script script(String id, int participants, String... speakersAndTexts) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < speakersAndTexts.length; i += 2) {
            lines.add(new Line(speakersAndTexts[i], speakersAndTexts[i + 1], 0));
        }
        return new Script(id, participants, lines);
    }

    @Test
    void soloMaplingsScriptsLoadWithSpeakersTheyHave() {
        List<Script> scripts = TownConversations.load(Path.of("scripts/config/population/conversations.yaml"));
        assertEquals(182, scripts.size());
        Set<String> ids = new HashSet<>();
        for (Script script : scripts) {
            assertTrue(ids.add(script.id()));
            assertTrue(script.participants() >= 2 && script.participants() <= 4, script.id());
            assertFalse(script.lines().isEmpty(), script.id());
            for (Line line : script.lines()) {
                int role = line.speaker().charAt(0) - 'A';
                assertTrue(role >= 0 && role < script.participants(), script.id() + ": speaker " + line.speaker());
                assertFalse(line.text().isBlank(), script.id());
                assertTrue(line.emote() >= 0 && line.emote() <= 7, script.id());
            }
        }
    }

    @Test
    void onlyCitizensStandingCloseFormAGroup() {
        CitizenSession a = townie(1, 0, 100);
        CitizenSession b = townie(2, 200, 110);
        townie(3, 900, 100); // across the map
        townie(4, 100, 300); // on another platform
        List<CitizenSession> group = TownConversations.cluster(sessions, new Random(1));
        assertEquals(2, group.size());
        assertTrue(group.containsAll(List.of(a, b)));
        sessions.removeIf(s -> s == a || s == b);
        assertTrue(TownConversations.cluster(sessions, new Random(1)).isEmpty());
    }

    @Test
    void theLastTwentyScriptsAreNotRepeatedWhileOthersFit() {
        TownConversations talks = new TownConversations(population, (delay, step) -> { }, List.of());
        List<Script> from = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            from.add(script("s" + i, 2, "A", "hi"));
        }
        from.add(script("big", 4, "A", "hi"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            Script picked = talks.pick(from, 2);
            assertTrue(seen.add(picked.id()), "repeated " + picked.id());
            assertFalse(picked.id().equals("big"), "a four-person script needs four citizens");
        }
        assertNull(talks.pick(List.of(script("big", 4, "A", "hi")), 3));
    }

    @Test
    void aScriptPlaysLineByLineInItsSpeakersVoicesAndThenTheyAreFree() {
        config.CONVERSATIONS_ENABLED = true;
        CitizenSession a = townie(1, 0, 100);
        CitizenSession b = townie(2, 15, 100);
        onMap.add(mock(Character.class)); // a player watching
        TownConversations talks = new TownConversations(population, (delay, step) -> steps.add(step),
                List.of(script("hello", 2, "A", "hey you", "B", "oh hi", "A", "nice day")));
        talks.tick();
        assertTrue(talks.talking(1) && talks.talking(2));
        List<String> spoken = new ArrayList<>();
        doAnswer(call -> {
            spoken.add(((CitizenSession) call.getArgument(0)).name() + ": " + call.getArgument(1));
            return null;
        }).when(chatter).answer(any(), anyString(), anyLong(), any());
        while (!steps.isEmpty()) {
            steps.poll().run();
        }
        assertEquals(3, spoken.size());
        String first = spoken.get(0).substring(0, spoken.get(0).indexOf(':'));
        String second = spoken.get(1).substring(0, spoken.get(1).indexOf(':'));
        assertFalse(first.equals(second), "A and B are two citizens: " + spoken);
        assertEquals(first, spoken.get(2).substring(0, spoken.get(2).indexOf(':')), "A speaks again: " + spoken);
        assertTrue(spoken.get(0).endsWith("hey you") && spoken.get(1).endsWith("oh hi"));
        assertFalse(talks.talking(1) || talks.talking(2), "the group breaks up after its last line");
        assertTrue(List.of(a.name(), b.name()).containsAll(List.of(first, second)));
    }

    @Test
    void nothingIsSaidWithTheSwitchOffOrNobodyToHear() {
        townie(1, 0, 100);
        townie(2, 15, 100);
        TownConversations talks = new TownConversations(population, (delay, step) -> steps.add(step),
                List.of(script("hello", 2, "A", "hey", "B", "hi")));
        talks.tick();
        assertTrue(steps.isEmpty(), "CONVERSATIONS_ENABLED is off");
        config.CONVERSATIONS_ENABLED = true;
        talks.tick();
        assertTrue(steps.isEmpty(), "only citizens on the map: nobody to hear it");
        verify(chatter, never()).answer(any(), anyString(), anyLong(), any());
        assertFalse(talks.talking(1));
    }

    @Test
    void aSpeakerWhoGoesOnToSomethingElseEndsTheTalk() {
        config.CONVERSATIONS_ENABLED = true;
        CitizenSession a = townie(1, 0, 100);
        townie(2, 15, 100);
        onMap.add(mock(Character.class));
        TownConversations talks = new TownConversations(population, (delay, step) -> steps.add(step),
                List.of(script("hello", 2, "A", "one", "B", "two", "A", "three")));
        talks.tick();
        steps.poll().run(); // the first line
        a.activity = Activity.TRAIN;
        while (!steps.isEmpty()) {
            steps.poll().run();
        }
        verify(chatter, times(1)).answer(any(), anyString(), anyLong(), any());
        assertFalse(talks.talking(1) || talks.talking(2));
    }
}

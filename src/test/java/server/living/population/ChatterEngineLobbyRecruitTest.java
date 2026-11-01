package server.living.population;

import client.Character;
import config.LivingWorldConfig;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A lobby town's idle recruiting ("KPQ anyone? need 1 more") has no party behind it: while players' whispers reach
 * citizens (F5c) a whisper back would find none, so it is not said; the lobby's other talk goes on.
 */
class ChatterEngineLobbyRecruitTest {
    private static final int KERNING = 103_000_000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final PopulationContent content = mock(PopulationContent.class, withSettings().stubOnly());
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private ChatterEngine chatter;
    private CitizenSession lia;

    @BeforeEach
    void setUp() {
        config.CHATTER_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(content);
        VoicePacks voices = mock(VoicePacks.class, withSettings().stubOnly());
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(call -> call.getArgument(1));
        when(population.voices()).thenReturn(voices);
        LivingWorld world = mock(LivingWorld.class, withSettings().stubOnly());
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(population.world()).thenReturn(world);
        FieldCatalog fields = mock(FieldCatalog.class, withSettings().stubOnly());
        when(fields.name(anyInt())).thenReturn("Kerning City");
        when(fields.kind(anyInt())).thenReturn("lobby");
        when(population.fields()).thenReturn(fields);
        when(population.presence()).thenReturn(mock(PresenceScheduler.class, withSettings().stubOnly()));
        when(population.conversation()).thenReturn(mock(Conversation.class, withSettings().stubOnly()));

        Character agent = mock(Character.class, withSettings().stubOnly());
        when(agent.getName()).thenReturn("Lia");
        when(agent.getLevel()).thenReturn(25);
        Citizen citizen = new Citizen(1, "Lia", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        lia = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        lia.entry = mock(AgentRuntimeEntry.class, withSettings().stubOnly());
        open(AgentRuntimeIdentityRuntime.class).when(() -> AgentRuntimeIdentityRuntime.bot(lia.entry)).thenReturn(agent);
        lia.mapId = KERNING;
        open(TimerManager.class).when(TimerManager::getInstance).thenReturn(mock(TimerManager.class, withSettings().stubOnly()));

        chatter = new ChatterEngine(population);
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private static PopulationContent.Intent intent(String id, String line) {
        return new PopulationContent.Intent(id, "dwell", "lobby", List.of(), "map", 10, 0L, List.of(line), Map.of(),
                "social.yaml", 0);
    }

    /** Whether Lia says something on a fresh dwell, with only {@code intent} to say. */
    private boolean says(PopulationContent.Intent intent) {
        when(content.intents("dwell")).thenReturn(List.of(intent));
        lia.lastSpokeAtMs.clear();
        lia.recentIntents.clear();
        return chatter.speakTo(ChatterEngine.Event.of("dwell", KERNING, 1).self(lia));
    }

    @Test
    void idleRecruitingGoesWhileWhispersReachCitizens() {
        PopulationContent.Intent recruit = intent("lobby.recruit", "KPQ anyone? need 1 more");

        config.CITIZEN_WHISPERS_ENABLED = false;
        assertTrue(says(recruit), "whispers off: as before");
        config.CITIZEN_WHISPERS_ENABLED = true;
        assertFalse(says(recruit), "a whisper back would find no party");
        assertTrue(says(intent("lobby.join", "inv pls KPQ")), "the lobby's other talk goes on");
    }
}

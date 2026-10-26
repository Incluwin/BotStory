package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The model's grounding for a citizen's reply (docs/CITIZEN_PERSON_MEMORY_PLAN.md): what it remembers of the player
 * talking to it, by their name, beside its card; nothing for a stranger, nothing without a speaker.
 */
class ConversationMemoryTest {
    private static final int CITIZEN = 963;
    private static final int ANN = 10;

    private final Population population = mock(Population.class);
    private final PersonMemories memories = mock(PersonMemories.class);

    @BeforeEach
    void pip() {
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.presence()).thenReturn(mock(PresenceScheduler.class));
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.memories()).thenReturn(memories);
        when(population.citizen(CITIZEN)).thenReturn(new Citizen(CITIZEN, "Pip", "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""));
    }

    @Test
    void theModelIsToldWhatTheCitizenRemembersOfTheSpeakerByName() {
        when(memories.recall(eq(CITIZEN), eq(ANN), anyLong())).thenReturn("met yesterday; cleared KPQ together yesterday; i like them");

        Map<String, String> facts = new Conversation(population).facts(CITIZEN, ANN, "Ann");

        assertEquals("met yesterday; cleared KPQ together yesterday; i like them", facts.get("what i remember about Ann"));
        assertEquals("Pip, a player, from victoria, plays evening", facts.get("who i am"));
    }

    @Test
    void aStrangerOrNoSpeakerGetsNoMemoryLine() {
        when(memories.recall(anyInt(), anyInt(), anyLong())).thenReturn(null);
        Conversation conversation = new Conversation(population);

        assertFalse(conversation.facts(CITIZEN, ANN, "Ann").keySet().stream().anyMatch(key -> key.startsWith("what i remember")));
        when(memories.recall(anyInt(), anyInt(), anyLong())).thenReturn("met earlier; i like them");
        assertFalse(conversation.facts(CITIZEN, 0, null).keySet().stream().anyMatch(key -> key.startsWith("what i remember")));
        assertEquals("met earlier; i like them", conversation.facts(CITIZEN, ANN, null).get("what i remember about them"));
    }
}

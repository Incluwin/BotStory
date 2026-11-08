package server.living.population;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.resident.ResidentDialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Map chat to a casino host comes before a companion's answer (live 2026-11-08, C8): Dorran answered "drink?" and
 * "yes" ("hmm not sure tbh"), so the bar host never heard them and never offered a drink.
 */
class LivingWorldHooksCasinoChatTest {
    private final LivingWorld world = mock(LivingWorld.class);
    private final ResidentDialogue dialogue = mock(ResidentDialogue.class);
    private final Population population = mock(Population.class);
    private final CitizenCasino casino = mock(CitizenCasino.class);
    private final Conversation conversation = mock(Conversation.class);
    private final Character player = mock(Character.class);
    private MockedStatic<LivingWorld> living;
    private MockedStatic<Population> populations;
    private MockedStatic<AgentPresence> presence;

    @BeforeEach
    void setUp() {
        when(world.dialogue()).thenReturn(dialogue);
        when(dialogue.onMapChat(any(), anyString())).thenReturn(true); // a companion would answer anything
        when(population.casino()).thenReturn(casino);
        when(population.conversation()).thenReturn(conversation);
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        presence = mockStatic(AgentPresence.class);
        presence.when(() -> AgentPresence.isAgent(any())).thenReturn(false);
    }

    @AfterEach
    void close() {
        presence.close();
        populations.close();
        living.close();
    }

    @Test
    void aWordToACasinoHostNeverReachesACompanion() {
        when(casino.heard(player, "drink?")).thenReturn(true);

        assertTrue(LivingWorldHooks.chat(player, "drink?"));

        verify(dialogue, never()).onMapChat(any(), anyString());
    }

    @Test
    void otherChatStillGoesToTheCompanionsFirst() {
        when(casino.heard(player, "nice weather")).thenReturn(false);

        assertTrue(LivingWorldHooks.chat(player, "nice weather"));

        verify(dialogue).onMapChat(player, "nice weather");
        verify(conversation, never()).onChat(any(), anyString());
    }

    @Test
    void withNoCasinoTheCompanionsAndConversationsHearIt() {
        when(population.casino()).thenReturn(null);
        when(dialogue.onMapChat(any(), anyString())).thenReturn(false);

        assertFalse(LivingWorldHooks.chat(player, "hello"));

        verify(dialogue).onMapChat(player, "hello");
        verify(conversation).onChat(player, "hello");
    }
}

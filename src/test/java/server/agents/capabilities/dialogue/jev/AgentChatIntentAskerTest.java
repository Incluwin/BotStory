package server.agents.capabilities.dialogue.jev;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentChatRuntime;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.TypeSafeSettings;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.social.conversation.AgentSocialDialogueRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Third deploy review 2026-10-10: since whispers reach the intent judge, an administrator's whisper can leave the
 * companion asking "u want me to go hunt?", and the ask was the Agent's, not the asker's: the owner's "ok" to a party
 * mate confirmed it, and the companion went hunting; an administrator's whispered "yes" confirmed the owner's ask. An
 * ask now waits for the one it asked, and anyone else's line leaves it.
 */
class AgentChatIntentAskerTest {
    private final Character admin = character(1, "Kiwi", mock(Client.class));
    private final Character owner = character(3, "Ohboy", mock(Client.class));
    private final Character target = character(2, "Tamsin", new BotClient(0, 0));
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(target, owner, null);
    private String oldAdministrators;
    private String oldOperators;
    private JevClient oldJev;

    @BeforeEach
    void setUp() {
        oldJev = JevClient.runtime();
        // No API key: a line the judge would read stays on this thread, so no SystemOne call outlives the test.
        JevClient.installRuntime(new JevClient(new TypeSafeSettings("", TypeSafeSettings.DEFAULT_ENDPOINT,
                TypeSafeSettings.DEFAULT_MODEL, 1000), (request, settings) -> {
            throw new AssertionError("no SystemOne call in this test");
        }));
        oldAdministrators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "Kiwi";
        oldOperators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = "Ohboy";
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(owner.getId(), entry);
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return actor.getId() == admin.getId() || actor.getId() == owner.getId(); // an administrator, its owner
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return agentCharacterId == target.getId(); // Ohboy's companion
            }
        });
    }

    @AfterEach
    void tearDown() {
        JevClient.installRuntime(oldJev);
        AgentClaimGuard.install(null);
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = oldAdministrators;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
        AgentRuntimeRegistry.clear();
    }

    @Test
    void anAdministratorsAskIsNotConfirmedByTheOwnersPartyLine() {
        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            askedByTheJudge(admin, "can u go hunt", "grind"); // the administrator's whisper, judged ASK

            partyLine("ok"); // the owner, to a party mate
            chat.verify(() -> AgentChatRuntime.handleChat(eq("grind"), any()), never());

            whisper(admin, "yes"); // the administrator's answer still confirms it
            chat.verify(() -> AgentChatRuntime.handleChat(eq("grind"), any()), times(1));
        }
    }

    @Test
    void theOwnersAskIsNotConfirmedByAnAdministratorsWhisper() {
        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            askedByTheJudge(owner, "can u go hunt", "grind"); // the owner's party line, judged ASK

            whisper(admin, "yes");
            chat.verify(() -> AgentChatRuntime.handleChat(eq("grind"), any()), never());

            whisper(owner, "yes"); // the owner's answer, whispered, still confirms it
            chat.verify(() -> AgentChatRuntime.handleChat(eq("grind"), any()), times(1));
        }
    }

    @Test
    void anAskWaitsForItsAskerAndOnlyItsAskerDropsIt() {
        AgentChatPendingAsks asks = new AgentChatPendingAsks(30_000L);
        asks.remember(entry, admin.getId(), "can u go hunt", "grind", 0L);

        assertEquals(AgentChatPendingAsks.Reply.OTHER, asks.resolve(entry, owner.getId(), "ok", 1L).reply());
        assertEquals(AgentChatPendingAsks.Reply.OTHER, asks.resolve(entry, owner.getId(), "go grind", 2L).reply());
        assertEquals(AgentChatPendingAsks.Reply.CONFIRMED, asks.resolve(entry, admin.getId(), "yes", 3L).reply());

        asks.remember(entry, admin.getId(), "can u go hunt", "grind", 0L);
        assertEquals(AgentChatPendingAsks.Reply.OTHER, asks.resolve(entry, admin.getId(), "brb", 1L).reply());
        assertEquals(AgentChatPendingAsks.Reply.OTHER, asks.resolve(entry, admin.getId(), "yes", 2L).reply(),
                "its asker's other line dropped it");
    }

    /** The judge asks {@code speaker} "did you mean ..." about {@code line}, as apply does for an ASK. */
    private void askedByTheJudge(Character speaker, String line, String canonical) {
        List<String> asked = new ArrayList<>();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        AgentChatIntentRuntime.apply(entry, speaker.getId(),
                speaker == admin ? AgentReplyChannel.WHISPER : AgentReplyChannel.PARTY, line,
                new AgentChatIntentJudge.Decision(AgentChatIntentJudge.Outcome.ASK, null, canonical,
                        0.62d, 0.6d, 0L, 0L, ""),
                null, (e, command, channel) -> CompletableFuture.completedFuture(true),
                (e, reply) -> asked.add(reply), result);
        assertTrue(result.join());
        assertEquals(List.of(AgentChatIntentRuntime.askText(canonical)), asked);
    }

    private void partyLine(String line) {
        AgentChatRouteCoordinator.handleChat(owner, line, AgentReplyChannel.PARTY, Map.of(owner.getId(), List.of(entry)),
                (leaderId, commandLeader, agentName) -> null,
                (leaderId, commandLeader, agentName, targetName) -> null,
                (leaderId, agentName) -> false,
                new AgentFormationService.FormationState(AgentFormationService.FormationType.STAGGER, 60, 0),
                60, 120);
        drainAll();
    }

    private void whisper(Character speaker, String line) {
        CosmicAgentWhisperCommandBridge.handleWhisperToAgent(speaker, target, line);
        drainAll();
    }

    private void drainAll() {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // each pass may queue the next step (the reply channel, the chat, a re-dispatched canonical command)
        }
    }

    private static Character character(int id, String name, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}

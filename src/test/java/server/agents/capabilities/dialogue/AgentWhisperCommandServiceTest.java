package server.agents.capabilities.dialogue;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentReplyChannelStateRuntime;
import server.agents.commands.AgentReplyRoute;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeHandle;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWhisperCommandServiceTest {
    private String oldAdministrators;

    @BeforeEach
    void leaderMayOperate() {
        oldAdministrators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "Kiwi";
    }

    @AfterEach
    void restoreAdministrators() {
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = oldAdministrators;
    }

    @Test
    void ignoresNonAgentTargets() {
        List<String> calls = new ArrayList<>();
        Character leader = character(1, mock(Client.class));
        Character target = character(2, mock(Client.class));

        AgentWhisperCommandService.handleWhisperToAgent(
                leader,
                target,
                "follow me",
                hooks(new TestHandle(), calls));

        assertEquals(List.of(), calls);
    }

    @Test
    void serviceRoutesAgentWhisperThroughHandleHooks() {
        List<String> calls = new ArrayList<>();
        Character leader = character(1, mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        TestHandle handle = new TestHandle();

        AgentWhisperCommandService.handleWhisperToAgent(
                leader,
                target,
                "follow me",
                hooks(handle, calls));

        assertEquals(List.of("resolve:1:2", "chat:WHISPER:follow me"), calls);
    }

    @Test
    void runtimeRoutesWhisperToTargetedAgentAndMarksWhisperReplyChannel() {
        Character leader = character(1, mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(target, leader, null);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(leader.getId(), entry);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class)) {
            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(leader, target, "follow me");
            drainAll(entry);

            assertEquals(AgentReplyChannel.WHISPER, AgentReplyChannelStateRuntime.replyChannel(entry));
            chat.verify(() -> AgentChatRuntime.handleChat(eq("follow me"), any()));
        } finally {
            AgentRuntimeRegistry.clear();
        }
    }

    /**
     * Live 2026-10-10: an owner's whisper no fixed pattern matched ("sell my junk please") was dropped with no reply,
     * while the same line named in party chat fell back. It now takes that chain: the phrase packs (here the prejudge
     * they install) turn it into "sell trash", the Agent's own sell flow runs, and the reply channel stays the whisper.
     */
    @Test
    void anUnmatchedWhisperFallsBackToThePhrasePacksAndAnswersByWhisper() {
        Character leader = character(1, mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(target, leader, null);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(leader.getId(), entry);
        List<String> utility = new ArrayList<>();
        AgentChatIntentRuntime.installPrejudge(message -> "sell my junk please".equals(message) ? "sell trash" : null);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class)) {
            chat.when(() -> AgentChatRuntime.handleChat(any(), any()))
                    .thenAnswer(call -> AgentChatUtilityFlow.handle(call.getArgument(0), utilityCalls(utility)));

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(leader, target, "sell my junk please");
            drainAll(entry);

            chat.verify(() -> AgentChatRuntime.handleChat(eq("sell my junk please"), any()));
            assertEquals(List.of("sellTrash"), utility);
            assertEquals(AgentReplyChannel.WHISPER, AgentReplyChannelStateRuntime.replyChannel(entry));
        } finally {
            AgentChatIntentRuntime.installPrejudge(null);
            AgentRuntimeRegistry.clear();
        }
    }

    /**
     * A whispered line nothing takes gets the social reply a named party line gets; a command the Agent carried out
     * but reports as unhandled ("follow me", the crew window's orders) gets no small talk on top of its own reply.
     */
    @Test
    void aWhisperedMissGetsTheSocialReplyButACarriedOutCommandDoesNot() {
        Character leader = character(1, mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(target, leader, null);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(leader.getId(), entry);
        // the host answers this line from its own knowledge: no intent judgment, the social reply keeps its turn
        AgentChatIntentRuntime.installContentGate((agent, speaker, message) -> true);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            social.when(AgentSocialDialogueRuntime::enabled).thenReturn(true);

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(leader, target, "follow me");
            drainAll(entry);
            social.verify(() -> AgentSocialDialogueRuntime.maybeRespond(any(), any(), any(), any()), never());

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(leader, target, "how was ur day");
            drainAll(entry);
            // the reply's route is fixed now, so the small talk that comes back later still goes by whisper to Kiwi
            social.verify(() -> AgentSocialDialogueRuntime.maybeRespond(entry, leader, "how was ur day",
                    new AgentReplyRoute(AgentReplyChannel.WHISPER, leader)));
            assertEquals(AgentReplyChannel.WHISPER, AgentReplyChannelStateRuntime.replyChannel(entry));
        } finally {
            AgentChatIntentRuntime.installContentGate(null);
            AgentRuntimeRegistry.clear();
        }
    }

    /**
     * Deploy review 2026-10-10: an answer to a whispered line that waits (here the intent judge's "did you mean",
     * sent when the judgment comes back) read the reply channel when it was sent, so a party line from the owner in
     * between sent the private answer to the party. It now keeps the whisper it answers.
     */
    @Test
    void aWhisperedAnswerThatWaitsStillGoesByWhisperAfterAPartyLineFromTheOwner() {
        Character leader = character(1, mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(target.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(target, leader, null);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(leader.getId(), entry);
        boolean transport = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
        List<AgentChatIntentRuntime.ReplyQueue> judging = new ArrayList<>();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentChatIntentRuntime> intent = mockStatic(AgentChatIntentRuntime.class,
                     org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            // the whispered line waits on its judgment; the owner's party line is taken at once
            intent.when(() -> AgentChatIntentRuntime.judgeUnmatched(any(), any(), any(), any(), any())).thenAnswer(call -> {
                if (!"u got pots".equals(call.getArgument(1))) {
                    return CompletableFuture.completedFuture(true);
                }
                judging.add(call.getArgument(4));
                return new CompletableFuture<Boolean>();
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(leader, target, "u got pots");
            drainAll(entry);
            AgentChatRouteCoordinator.handleChat(leader, "brb", AgentReplyChannel.PARTY, Map.of(leader.getId(), List.of(entry)),
                    (leaderId, commandLeader, agentName) -> null,
                    (leaderId, commandLeader, agentName, targetName) -> null,
                    (leaderId, agentName) -> false,
                    new AgentFormationService.FormationState(AgentFormationService.FormationType.STAGGER, 60, 0),
                    60,
                    120);
            drainAll(entry);
            assertEquals(AgentReplyChannel.PARTY, AgentReplyChannelStateRuntime.replyChannel(entry));

            // the judgment comes back: its answer is for the whisper
            assertEquals(1, judging.size());
            judging.get(0).queue(entry, "did you mean 'pots'?");
            drainAll(entry);

            verify(leader).sendPacket(any(Packet.class));
            verify(map, never()).broadcastMessage(any(Packet.class));
        } finally {
            config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = transport;
            AgentRuntimeRegistry.clear();
        }
    }

    private static void drainAll(AgentRuntimeEntry entry) {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // each pass may queue the next step (the reply channel, the chat, a re-dispatched canonical command)
        }
    }

    private static AgentChatUtilityFlow.UtilityCallbacks utilityCalls(List<String> calls) {
        return new AgentChatUtilityFlow.UtilityCallbacks() {
            @Override
            public void tradeInvite() {
                calls.add("tradeInvite");
            }

            @Override
            public void sellTrash() {
                calls.add("sellTrash");
            }

            @Override
            public void makeCrystals() {
                calls.add("makeCrystals");
            }

            @Override
            public void disassembleTrash() {
                calls.add("disassembleTrash");
            }
        };
    }

    private static AgentWhisperCommandService.Hooks<TestHandle> hooks(TestHandle resolved, List<String> calls) {
        return new AgentWhisperCommandService.Hooks<>(
                (leader, target) -> {
                    calls.add("resolve:" + leader.getId() + ":" + target.getId());
                    return resolved;
                },
                (entry, message, channel) -> calls.add("chat:" + channel + ":" + message));
    }

    private static Character character(int id, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(id == 1 ? "Kiwi" : "Agent" + id);
        when(character.getClient()).thenReturn(client);
        return character;
    }

    private static final class TestHandle implements AgentRuntimeHandle {
    }
}

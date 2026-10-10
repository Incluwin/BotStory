package server.agents.auth;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentChatIngressService;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentChatRuntime;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.commands.AgentFollowTargetCommandCoordinator;
import server.agents.commands.AgentLeaderTransferCoordinator;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentScheduledTaskRuntime;
import server.agents.runtime.AgentSchedulerRuntime;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner-free claim seam and every route that asks it (docs/BOT_MANAGEMENT_PLAN.md P1,
 * "Owner-scoped authority"): the whisper bridge, chat transfer, dismiss and "follow &lt;name&gt;",
 * and the leader transfer itself. {@code AgentControlService.ensureCanControl} is covered by
 * AgentControlServiceCharacterizationTest.
 */
class AgentClaimGuardTest {
    private static final int KIWI = 1;
    private static final int OLWEN = 900;
    private static final int PROBE = 960;

    private final Character kiwi = character(KIWI, "Kiwi", mock(Client.class));
    private final Character bob = character(3, "Bob", mock(Client.class));
    private final Character olwen = character(OLWEN, "Olwen", new BotClient(0, 0));
    private final Character probe = character(PROBE, "Probe", new BotClient(0, 0));
    private String oldOperators;

    @BeforeEach
    void kiwiIsAnOperator() {
        oldOperators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = "Kiwi";
        AgentRuntimeRegistry.clear();
    }

    @AfterEach
    void restore() {
        AgentClaimGuard.install(null);
        AgentRuntimeRegistry.clear();
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
    }

    @Test
    void withNothingInstalledEveryRouteBehavesAsBefore() {
        assertTrue(AgentClaimGuard.mayControl(kiwi, OLWEN));
        assertTrue(AgentClaimGuard.mayControl(null, OLWEN));
        assertTrue(AgentClaimGuard.mayLoad(kiwi, OLWEN));
        assertFalse(AgentClaimGuard.isClaimed(OLWEN));
    }

    @Test
    void anInstalledRuleDecides() {
        install(Set.of(PROBE), Set.of(OLWEN));

        assertFalse(AgentClaimGuard.mayControl(kiwi, OLWEN));
        assertTrue(AgentClaimGuard.mayControl(kiwi, PROBE));
        assertTrue(AgentClaimGuard.isClaimed(OLWEN));
        assertFalse(AgentClaimGuard.isClaimed(PROBE));
    }

    @Test
    void loadingFollowsControlUnlessTheRuleSaysOtherwise() {
        install(Set.of(PROBE, OLWEN), Set.of(OLWEN));
        assertTrue(AgentClaimGuard.mayLoad(kiwi, OLWEN)); // the default: whoever may control it

        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return true; // an administrator commands anything loaded
            }

            @Override
            public boolean mayLoad(Character actor, int agentCharacterId) {
                return agentCharacterId != OLWEN; // but never loads a claimed character
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return agentCharacterId == OLWEN;
            }
        });

        assertTrue(AgentClaimGuard.mayControl(kiwi, OLWEN));
        assertFalse(AgentClaimGuard.mayLoad(kiwi, OLWEN));
        assertTrue(AgentClaimGuard.mayLoad(kiwi, PROBE));
    }

    // ---- whisper bridge ------------------------------------------------------------------

    @Test
    void aWhisperFromSomeoneWhoMayNotControlTheAgentIsIgnoredLikeAStrangers() {
        AgentRuntimeEntry entry = register(olwen);
        install(Set.of(), Set.of(OLWEN));

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class)) {
            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(kiwi, olwen, "follow me");
            entry.actionMailbox().drain(entry, 1);

            chat.verify(() -> AgentChatRuntime.handleChat(any(), any()), never());
        }
    }

    @Test
    void aWhisperFromTheControllerStillReachesTheAgent() {
        AgentRuntimeEntry entry = register(olwen);
        install(Set.of(OLWEN), Set.of(OLWEN));

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class)) {
            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(kiwi, olwen, "follow me");
            AgentMailboxRuntime.drain(entry); // its reply channel, then the line

            chat.verify(() -> AgentChatRuntime.handleChat(eq("follow me"), any()));
        }
    }

    // ---- leader transfer -----------------------------------------------------------------

    @Test
    void aClaimedAgentIsNeverTransferred() {
        AgentRuntimeEntry entry = register(olwen);
        standingNextTo(bob);
        install(Set.of(OLWEN), Set.of(OLWEN));
        List<String> calls = new ArrayList<>();

        String result = AgentLeaderTransferCoordinator.transferAgent(KIWI, kiwi, "Olwen", "Bob",
                stopped -> calls.add("stop"), (leaderId, leader, agent) -> {
                    calls.add("register " + leaderId);
                    return null;
                });

        assertEquals("Bot 'Olwen' cannot be transferred.", result);
        assertEquals(List.of(), calls);
        assertSame(entry, AgentRuntimeRegistry.findByName(KIWI, "Olwen"));
    }

    @Test
    void aPlainAgentIsStillTransferred() {
        AgentRuntimeEntry entry = register(probe);
        standingNextTo(bob);
        install(Set.of(PROBE), Set.of());
        List<String> calls = new ArrayList<>();

        String result;
        try (MockedStatic<AgentScheduledTaskRuntime> tasks = mockStatic(AgentScheduledTaskRuntime.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            result = AgentLeaderTransferCoordinator.transferAgent(KIWI, kiwi, "Probe", "Bob",
                    stopped -> calls.add("stop"), (leaderId, leader, agent) -> {
                        calls.add("register " + leaderId);
                        return entry;
                    });
        }

        assertNull(result);
        assertEquals(List.of("stop", "register 3"), calls);
    }

    // ---- chat routes ---------------------------------------------------------------------

    @Test
    void chatTransferAndDismissOfAnAgentTheLeaderMayNotControlAreRefusedByTheAgentItself() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(olwen, kiwi, null);
        install(Set.of(), Set.of(OLWEN));
        List<String> calls = new ArrayList<>();
        AgentChatIngressService.Hooks<AgentRuntimeEntry> hooks = chatHooks(Map.of(KIWI, List.of(entry)), calls);

        try (MockedStatic<AgentDialogueTransportRuntime> transport = mockStatic(AgentDialogueTransportRuntime.class)) {
            assertTrue(hooks.transferRoute().handle(kiwi, "transfer Olwen to Bob"));
            assertTrue(hooks.dismissRoute().handle(kiwi, "dismiss Olwen"));
            AgentMailboxRuntime.drain(entry);

            transport.verify(() -> AgentDialogueTransportRuntime.queueReply(entry, "i'm sticking with you."), times(2));
        }
        assertEquals(List.of(), calls);
        verify(kiwi, never()).yellowMessage(any());
    }

    @Test
    void chatTransferOfYourOwnCompanionIsRefusedByTheCompanion() {
        // Kiwi controls Olwen, but a claimed Agent is never handed to another leader
        AgentRuntimeEntry entry = new AgentRuntimeEntry(olwen, kiwi, null);
        install(Set.of(OLWEN), Set.of(OLWEN));
        List<String> calls = new ArrayList<>();
        AgentChatIngressService.Hooks<AgentRuntimeEntry> hooks = chatHooks(Map.of(KIWI, List.of(entry)), calls);

        try (MockedStatic<AgentDialogueTransportRuntime> transport = mockStatic(AgentDialogueTransportRuntime.class)) {
            assertTrue(hooks.transferRoute().handle(kiwi, "transfer Olwen to Bob"));
            AgentMailboxRuntime.drain(entry);

            transport.verify(() -> AgentDialogueTransportRuntime.queueReply(entry, "i'm sticking with you."));
        }
        assertEquals(List.of(), calls);
        verify(kiwi, never()).yellowMessage(any());
    }

    @Test
    void chatTransferAndDismissOfAnAgentTheLeaderControlsGoThrough() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(probe, kiwi, null);
        install(Set.of(PROBE), Set.of());
        List<String> calls = new ArrayList<>();
        AgentChatIngressService.Hooks<AgentRuntimeEntry> hooks = chatHooks(Map.of(KIWI, List.of(entry)), calls);

        hooks.transferRoute().handle(kiwi, "transfer Probe to Bob");
        hooks.dismissRoute().handle(kiwi, "dismiss Probe");

        assertEquals(List.of("transfer Probe->Bob", "dismiss Probe"), calls);
    }

    @Test
    void followAnotherPlayerLeavesClaimedAgentsWithTheirLeader() {
        AgentRuntimeEntry companion = new AgentRuntimeEntry(olwen, kiwi, null);
        AgentRuntimeEntry plain = new AgentRuntimeEntry(probe, kiwi, null);
        install(Set.of(OLWEN, PROBE), Set.of(OLWEN));
        AgentChatIngressService.Hooks<AgentRuntimeEntry> hooks =
                chatHooks(Map.of(KIWI, List.of(companion, plain)), new ArrayList<>());

        try (MockedStatic<AgentFollowTargetCommandCoordinator> follow = mockStatic(AgentFollowTargetCommandCoordinator.class);
             MockedStatic<AgentDialogueTransportRuntime> transport = mockStatic(AgentDialogueTransportRuntime.class)) {
            follow.when(() -> AgentFollowTargetCommandCoordinator.resolveFollowTarget(kiwi, "Bob")).thenReturn(bob);

            hooks.untargetedRoute().handle(kiwi, List.of(companion, plain), "follow Bob", AgentReplyChannel.MAP);
            AgentMailboxRuntime.drain(companion);
            AgentMailboxRuntime.drain(plain);

            follow.verify(() -> AgentFollowTargetCommandCoordinator.applyResolvedFollowTargetCommand(plain, bob));
            follow.verify(() -> AgentFollowTargetCommandCoordinator.applyResolvedFollowTargetCommand(companion, bob), never());
            transport.verify(() -> AgentDialogueTransportRuntime.queueReply(companion, "i'll stay with you."));
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private static void install(Set<Integer> controlled, Set<Integer> claimed) {
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return controlled.contains(agentCharacterId);
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return claimed.contains(agentCharacterId);
            }
        });
    }

    private AgentRuntimeEntry register(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, kiwi, null);
        AgentRuntimeRegistry.registerEntry(KIWI, entry);
        return entry;
    }

    private void standingNextTo(Character other) {
        MapleMap map = mock(MapleMap.class);
        when(kiwi.getMap()).thenReturn(map);
        when(map.getCharacterByName(other.getName())).thenReturn(other);
    }

    private AgentChatIngressService.Hooks<AgentRuntimeEntry> chatHooks(
            Map<Integer, List<AgentRuntimeEntry>> entries, List<String> calls) {
        AtomicReference<AgentChatIngressService.Hooks<AgentRuntimeEntry>> captured = new AtomicReference<>();
        try (MockedStatic<AgentChatIngressService> ingress = mockStatic(AgentChatIngressService.class)) {
            ingress.when(() -> AgentChatIngressService.handleChat(any(), any(), any(), any())).thenAnswer(inv -> {
                captured.set(inv.getArgument(3));
                return null;
            });
            AgentChatRouteCoordinator.handleChat(kiwi, "hi", AgentReplyChannel.MAP, entries,
                    (leaderId, leader, agentName) -> null,
                    (leaderId, leader, agentName, targetName) -> {
                        calls.add("transfer " + agentName + "->" + targetName);
                        return null;
                    },
                    (leaderId, agentName) -> calls.add("dismiss " + agentName),
                    new AgentFormationService.FormationState(AgentFormationService.FormationType.STAGGER, 60, 0),
                    60,
                    120);
        }
        return captured.get();
    }

    private static Character character(int id, String name, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}

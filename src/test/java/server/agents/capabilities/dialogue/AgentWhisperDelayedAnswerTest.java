package server.agents.capabilities.dialogue;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.build.AgentBuildService;
import server.agents.capabilities.build.AgentMakerService;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentMovementRuntime;
import server.agents.capabilities.movement.fidget.AgentFidgetService;
import server.agents.capabilities.social.AgentSocialRuntime;
import server.agents.capabilities.supplies.AgentPotionRuntime;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyRuntime;
import server.agents.capabilities.trade.AgentInventoryTransferService;
import server.agents.capabilities.trade.AgentOfferService;
import server.agents.capabilities.trade.AgentSupplyShareTradeService;
import server.agents.capabilities.trade.AgentTradeSequenceService;
import server.agents.capabilities.trade.AgentTransferRuntime;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.MakerGateway;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.async.AgentAsyncCompletion;
import server.agents.runtime.async.AgentAsyncTaskGateway;
import server.agents.runtime.scheduler.AgentSessionId;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Third deploy review 2026-10-10: the answers a whispered line's flows send late, and the one each answers. The
 * whisper bridge gives an administrator's companion back to its owner once the line is handled, and the owner's party
 * line moves the reply channel, so whatever an answer reads when it is sent is no longer the line's: an item query's
 * answer comes back from the async gateway, a crafting batch's last line comes steps later, "fame me" and a supply
 * request act on "me" 500-900 ms later. Each keeps the line's route and asker. An owner's decision a line only raises
 * (the job advance prompt) stays the owner's.
 */
class AgentWhisperDelayedAnswerTest {
    private String oldAdministrators;
    private String oldOperators;
    private boolean oldTransport;
    private final List<Runnable> delayed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        oldAdministrators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "Kiwi";
        oldOperators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = "Ohboy";
        oldTransport = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
        AgentRuntimeRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        AgentClaimGuard.install(null);
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = oldAdministrators;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = oldTransport;
        AgentRuntimeRegistry.clear();
    }

    /**
     * "do you have pots" whispered by an administrator to Ohboy's companion: the count comes back from the async
     * gateway as a new mailbox action, out of the line's scope, after its companion went back to Ohboy. The answer
     * was whispered to Ohboy, and the administrator got nothing.
     */
    @Test
    void anAdministratorsItemQueryIsAnsweredToTheAdministrator() {
        Lent lent = lent();
        AsyncProbe async = new AsyncProbe();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentAsyncTaskGateway> gateway = mockStatic(AgentAsyncTaskGateway.class);
             MockedStatic<AgentInventoryTransferService> transfer = mockStatic(AgentInventoryTransferService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            gateway.when(AgentAsyncTaskGateway::runtime).thenReturn(async.gateway);
            transfer.when(() -> AgentInventoryTransferService.countTransferableItems(any(), any(), any())).thenReturn(0);
            collectDelayed(scheduler);
            chat.when(() -> AgentChatRuntime.handleChat(eq("do you have pots"), any())).thenAnswer(call -> {
                AgentTransferRuntime.itemQueryCallbacks(lent.entry).queryItem("pots");
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "do you have pots");
            drainAll(lent.entry);
            assertSame(lent.owner, AgentRelationshipRuntime.interactionTarget(lent.entry), "its owner's again");

            async.complete(lent.entry);
            runDelayed();

            verify(lent.admin).sendPacket(any(Packet.class));
            verify(lent.owner, never()).sendPacket(any(Packet.class));
        }
    }

    /** The owner's whispered "do you have pots", then a party line before the answer: it went to the party. */
    @Test
    void aWhisperedItemQueryIsStillWhisperedAfterAPartyLine() {
        Character leader = character(1, mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(target.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(target, leader, null);
        AgentRuntimeRegistry.registerEntry(leader.getId(), entry);
        AsyncProbe async = new AsyncProbe();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime> intent =
                     mockStatic(server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime.class, CALLS_REAL_METHODS);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class);
             MockedStatic<AgentAsyncTaskGateway> gateway = mockStatic(AgentAsyncTaskGateway.class);
             MockedStatic<AgentInventoryTransferService> transfer = mockStatic(AgentInventoryTransferService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            gateway.when(AgentAsyncTaskGateway::runtime).thenReturn(async.gateway);
            transfer.when(() -> AgentInventoryTransferService.countTransferableItems(any(), any(), any())).thenReturn(0);
            collectDelayed(scheduler);
            intent.when(() -> server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime.judgeUnmatched(
                            any(), anyInt(), any(), any(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(false));
            chat.when(() -> AgentChatRuntime.handleChat(eq("do you have pots"), any())).thenAnswer(call -> {
                AgentTransferRuntime.itemQueryCallbacks(entry).queryItem("pots");
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(leader, target, "do you have pots");
            drainAll(entry);
            partyLine(leader, entry, "brb");
            drainAll(entry);

            async.complete(entry);
            runDelayed();

            verify(leader).sendPacket(any(Packet.class));
            verify(map, never()).broadcastMessage(any(Packet.class));
        }
    }

    /**
     * "give me your pots" whispered by an administrator: the check comes back from the async gateway too, and the
     * trade it opens was the owner's.
     */
    @Test
    void anAdministratorsTransferRequestTradesWithTheAdministrator() {
        Lent lent = lent();
        AsyncProbe async = new AsyncProbe();
        List<Character> tradingWith = new ArrayList<>();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentAsyncTaskGateway> gateway = mockStatic(AgentAsyncTaskGateway.class);
             MockedStatic<AgentInventoryTransferService> transfer = mockStatic(AgentInventoryTransferService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            gateway.when(AgentAsyncTaskGateway::runtime).thenReturn(async.gateway);
            transfer.when(() -> AgentInventoryTransferService.hasTransferableItems(any(), any(), any())).thenReturn(true);
            // whom the trade is with: the one it reads as the asker when it starts
            transfer.when(() -> AgentInventoryTransferService.startTradeTransfer(anyString(), any(AgentRuntimeEntry.class),
                    any(Character.class))).thenAnswer(call -> tradingWith.add(AgentRelationshipRuntime.requester(lent.entry)));
            collectDelayed(scheduler);
            chat.when(() -> AgentChatRuntime.handleChat(eq("give me your pots"), any())).thenAnswer(call -> {
                AgentTransferRuntime.handleTransferCommand(lent.entry, new AgentChatTransferFlow.TransferCommand(
                        AgentChatTransferFlow.TransferMode.TRADE, "pots"), "give me your pots");
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "give me your pots");
            drainAll(lent.entry);
            async.complete(lent.entry);
            runDelayed();
        }

        assertEquals(List.of(lent.admin), tradingWith);
    }

    /** "fame me" whispered by an administrator: the fame, 500-900 ms later, went to the owner. */
    @Test
    void anAdministratorsFameMeFamesTheAdministrator() {
        Lent lent = lent();
        when(lent.target.getLevel()).thenReturn(50);
        when(lent.target.canGiveFame(any())).thenReturn(Character.FameStatus.OK);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            collectDelayed(scheduler);
            chat.when(() -> AgentChatRuntime.handleChat(eq("fame me"), any())).thenAnswer(call -> {
                AgentSocialRuntime.socialCallbacks(lent.entry).fame("me");
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "fame me");
            drainAll(lent.entry);
            runDelayed();

            verify(lent.admin).gainFame(1, lent.target, 1);
            verify(lent.owner, never()).gainFame(anyInt(), any(), anyInt());
        }
    }

    /** "i need hp pots" whispered by an administrator: the pots were offered to the owner. */
    @Test
    void anAdministratorsPotionRequestIsOfferedToTheAdministrator() {
        Lent lent = lent();
        when(lent.target.getMapId()).thenReturn(100000000);
        when(lent.admin.getMapId()).thenReturn(100000000);
        when(lent.owner.getMapId()).thenReturn(100000000);
        Item pots = mock(Item.class);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class, CALLS_REAL_METHODS);
             MockedStatic<AgentPotionRuntime> potionRuntime = mockStatic(AgentPotionRuntime.class, CALLS_REAL_METHODS);
             MockedStatic<AgentSupplyShareTradeService> share = mockStatic(AgentSupplyShareTradeService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            collectDelayed(scheduler);
            potions.when(() -> AgentPotionService.countPotions(lent.target)).thenReturn(new int[]{500, 500});
            potions.when(() -> AgentPotionService.collectPotShareItems(eq(lent.target), anyBoolean(), anyInt()))
                    .thenReturn(List.of(pots));
            potionRuntime.when(() -> AgentPotionRuntime.sayMapNow(any(), any())).thenAnswer(call -> null);
            chat.when(() -> AgentChatRuntime.handleChat(eq("i need hp pots"), any())).thenAnswer(call -> {
                AgentSupplyRuntime.supplyRequestCallbacks(lent.entry).requestPotion(true);
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "i need hp pots");
            drainAll(lent.entry);
            runDelayed();

            share.verify(() -> AgentSupplyShareTradeService.startPotShareTransfer(
                    eq(List.of(pots)), eq(lent.admin), eq(lent.entry), eq(lent.target), anyInt()));
            share.verify(() -> AgentSupplyShareTradeService.startPotShareTransfer(
                    any(), eq(lent.owner), any(), any(), anyInt()), never());
        }
    }

    /** "give me mesos" whispered by an administrator: the trade, 500-700 ms later, was opened with the owner. */
    @Test
    void anAdministratorsMesoRequestIsTradedToTheAdministrator() {
        Lent lent = lent();
        when(lent.target.getMeso()).thenReturn(5000);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentTradeSequenceService> sequence = mockStatic(AgentTradeSequenceService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            collectDelayed(scheduler);
            chat.when(() -> AgentChatRuntime.handleChat(eq("give me mesos"), any())).thenAnswer(call -> {
                AgentTransferRuntime.handleTransferCommand(lent.entry, new AgentChatTransferFlow.TransferCommand(
                        AgentChatTransferFlow.TransferMode.TRADE, "mesos"), "give me mesos");
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "give me mesos");
            drainAll(lent.entry);
            runDelayed();

            sequence.verify(() -> AgentTradeSequenceService.startSequence(
                    eq("mesos"), eq(lent.admin), any(), anyInt(), anyBoolean(), eq(lent.entry), any()));
            sequence.verify(() -> AgentTradeSequenceService.startSequence(
                    any(), eq(lent.owner), any(), anyInt(), anyBoolean(), any(), any()), never());
        }
    }

    /**
     * "make crystals" whispered by an administrator: only the batch's first step kept the line's route, so "ok making
     * 1 crystal" went to the administrator and "done - 1 crystal" to the owner.
     */
    @Test
    void anAdministratorsCraftingBatchReportsToTheAdministrator() {
        Lent lent = lent();
        int leftover = 4000000;
        Inventory etc = mock(Inventory.class);
        Item stack = mock(Item.class);
        when(stack.getItemId()).thenReturn(leftover);
        when(etc.list()).thenReturn(List.of(stack));
        when(etc.countById(leftover)).thenReturn(
                config.AgentTuning.intValue("server.agents.capabilities.build.AgentMakerService.LEFTOVERS_PER_CRYSTAL"));
        when(lent.target.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(lent.target.isLoggedin()).thenReturn(true);
        InventoryGateway inventory = mock(InventoryGateway.class);
        when(inventory.getMakerCrystalFromLeftover(leftover)).thenReturn(4260000);
        MakerGateway maker = mock(MakerGateway.class);
        when(maker.getMakerSkillLevel(any())).thenReturn(1);
        when(maker.makeLeftoverCrystal(any(), eq(leftover))).thenReturn(0);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            collectDelayed(scheduler);
            // AgentUtilityRuntime.makeCrystals, with test gateways
            chat.when(() -> AgentChatRuntime.handleChat(eq("make crystals"), any())).thenAnswer(call -> {
                AgentSchedulerRuntime.afterRandomDelay(lent.entry, 500, 700,
                        () -> AgentMakerService.handleMakeCrystals(lent.entry, inventory, maker));
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "make crystals");
            drainAll(lent.entry);
            runDelayed(); // the start line, the craft, then the next step: nothing left, "done"

            verify(lent.admin, times(2)).sendPacket(any(Packet.class)); // "ok making 1 crystal", "done - 1 crystal"
            verify(lent.owner, never()).sendPacket(any(Packet.class));
        } finally {
            AgentMakerService.clearAgentRuntimeState(lent.target.getId());
        }
    }

    /**
     * "hi" whispered by an administrator: the greeting is the administrator's, but the job advance prompt the status
     * check raises after it is the owner's decision, which the owner's next line answers: it goes to the owner, as it
     * did before answers kept their line's route.
     */
    @Test
    void theJobPromptAnAdministratorsGreetingRaisesGoesToTheOwner() {
        Lent lent = lent();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentFidgetService> fidget = mockStatic(AgentFidgetService.class);
             MockedStatic<AgentBuildService> build = mockStatic(AgentBuildService.class);
             MockedStatic<AgentOfferService> offers = mockStatic(AgentOfferService.class);
             MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class, CALLS_REAL_METHODS)) {
            build.when(() -> AgentBuildService.buildJobPrompt(any(), any()))
                    .thenReturn("i can job advance! fighter, page or spearman?");
            collectDelayed(scheduler);
            chat.when(() -> AgentChatRuntime.handleChat(eq("hi"), any())).thenAnswer(call -> {
                AgentMovementRuntime.movementCallbacks(lent.entry).greeting();
                return true;
            });

            CosmicAgentWhisperCommandBridge.handleWhisperToAgent(lent.admin, lent.target, "hi");
            drainAll(lent.entry);
            runDelayed(); // the greeting and the status check, then the reply queue's next line

            verify(lent.admin, times(1)).sendPacket(any(Packet.class)); // the greeting
            verify(lent.owner, times(1)).sendPacket(any(Packet.class)); // the job prompt
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Ohboy's companion, which the administrator Kiwi may command too. */
    private record Lent(Character admin, Character owner, Character target, AgentRuntimeEntry entry) {
    }

    private static Lent lent() {
        Character admin = character(1, mock(Client.class));
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(3);
        when(owner.getName()).thenReturn("Ohboy");
        when(owner.getClient()).thenReturn(mock(Client.class));
        Character target = character(2, new BotClient(0, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(target, owner, null);
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
        return new Lent(admin, owner, target, entry);
    }

    /** The async gateway: the work runs when the test says, and its completion comes as the gateway delivers it. */
    private static final class AsyncProbe {
        private final AgentAsyncTaskGateway gateway = mock(AgentAsyncTaskGateway.class);
        private final AtomicReference<Supplier<?>> task = new AtomicReference<>();
        private final AtomicReference<AgentAsyncTaskGateway.CompletionHandler<Object>> handler = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        AsyncProbe() {
            when(gateway.submit(any(), any(), anyString(), any(Supplier.class),
                    any(AgentAsyncTaskGateway.CompletionHandler.class))).thenAnswer(call -> {
                        task.set(call.getArgument(3));
                        handler.set(call.getArgument(4));
                        return new AgentAsyncTaskGateway.Submission(1L, AgentAsyncTaskGateway.SubmissionStatus.ACCEPTED);
                    });
            when(gateway.isLatest(any(), any(), anyString(), anyLong())).thenReturn(true);
        }

        /** The worker's result, delivered on the Agent's mailbox out of any line's scope. */
        void complete(AgentRuntimeEntry entry) {
            Object result = task.get().get();
            handler.get().handle(entry, new AgentAsyncCompletion<>(AgentSessionId.from(entry), 1L, null, "k",
                    AgentAsyncCompletion.Status.SUCCEEDED, result, null, 0L));
        }
    }

    private void collectDelayed(MockedStatic<AgentSchedulerRuntime> scheduler) {
        scheduler.when(() -> AgentSchedulerRuntime.afterDelay(any(AgentRuntimeEntry.class), anyLong(), any(Runnable.class)))
                .thenAnswer(call -> delayed.add(call.getArgument(2)));
    }

    /** Runs what came due, in order, and what that schedules in turn. */
    private void runDelayed() {
        for (int step = 0; step < 20 && !delayed.isEmpty(); step++) {
            delayed.remove(0).run();
        }
        assertEquals(List.of(), delayed, "everything scheduled ran");
    }

    private static void partyLine(Character leader, AgentRuntimeEntry entry, String line) {
        AgentChatRouteCoordinator.handleChat(leader, line, AgentReplyChannel.PARTY, Map.of(leader.getId(), List.of(entry)),
                (leaderId, commandLeader, agentName) -> null,
                (leaderId, commandLeader, agentName, targetName) -> null,
                (leaderId, agentName) -> false,
                new AgentFormationService.FormationState(AgentFormationService.FormationType.STAGGER, 60, 0),
                60, 120);
    }

    private static void drainAll(AgentRuntimeEntry entry) {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // each pass may queue the next step (the reply channel, the chat)
        }
    }

    private static Character character(int id, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(id == 1 ? "Kiwi" : "Agent" + id);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}

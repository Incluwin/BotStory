package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.Trade;
import server.agents.capabilities.dialogue.AgentChatCommandClassifier;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentEquipmentDialogueClassifier;
import server.agents.capabilities.equipment.AgentEquipmentAutoEquipService;
import server.agents.capabilities.equipment.AgentEquipmentOptimizerResult;
import server.agents.capabilities.movement.AgentFormationCommandService;
import server.agents.capabilities.movement.AgentPartyGatherService;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.quest.QuestLedger;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * CompanionManager (docs/BOT_MANAGEMENT_PLAN.md P3): it acts only on the owner's own companions (a friend's is NotOwned
 * and nothing is touched), its orders reach exactly the owner's companions out with them through the Agent chat paths
 * typing uses (a whisper for one, the owner's chat line over their own companions for the crew) with phrases the Agent
 * classifiers take, committed companions refuse bench, swap and errands "after the run", and the trade, gear, haul and
 * release pages do what their stock windows and roster verbs do. The roster is the real one over LifecycleHarness.
 */
class CompanionManagerTest {
    private static final int BEN = 20;

    private LifecycleHarness h;
    private Persona hadrik;
    private Persona tobi;
    private Persona mira;
    private Persona brannock;
    private CompanionManager manager;
    private MockedStatic<CosmicAgentWhisperCommandBridge> whispers;
    private MockedStatic<AgentChatRouteCoordinator> chat;

    @BeforeEach
    void wire() {
        h = new LifecycleHarness();
        hadrik = h.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        tobi = h.companion("tobi", "Tobi", 902, RosterState.ACTIVE, LITH);
        mira = h.companion("mira", "Mira", 903, RosterState.BENCHED, HENESYS);
        brannock = h.companionOf(BEN, "brannock", "Brannock", 904, RosterState.ACTIVE, LITH); // Ben's
        h.roster();
        when(h.world.directory().mayControl(any(), anyInt())).thenReturn(true);
        manager = new CompanionManager(h.world);
        whispers = mockStatic(CosmicAgentWhisperCommandBridge.class);
        chat = mockStatic(AgentChatRouteCoordinator.class);
    }

    @AfterEach
    void close() {
        chat.close();
        whispers.close();
        h.close();
    }

    // ---- ownership -----------------------------------------------------------------------------

    @Test
    void aFriendsCompanionIsNotOwnedAndNothingIsTouched() {
        h.outWithAnn(hadrik);
        h.outWithAnn(brannock); // standing right here in the same party
        Map<String, RosterEntry> before = Map.copyOf(h.store.rows);
        ContractService contracts = mock(ContractService.class);
        when(h.world.contracts()).thenReturn(contracts);

        for (RosterResult result : List.of(manager.call(h.ann, brannock), manager.bench(h.ann, brannock),
                manager.swap(h.ann, hadrik, brannock), manager.swap(h.ann, brannock, mira),
                manager.assign(h.ann, brannock, Assignment.REST, 0, 0), manager.setCamp(h.ann, brannock, HENESYS),
                manager.followMe(h.ann, brannock), manager.waitHere(h.ann, brannock), manager.huntHere(h.ann, brannock),
                manager.showGear(h.ann, brannock), manager.openTrade(h.ann, brannock),
                manager.wearBestFromHaul(h.ann, brannock), manager.release(h.ann, brannock))) {
            assertInstanceOf(RosterResult.NotOwned.class, result);
        }

        assertEquals(before, h.store.rows);
        whispers.verifyNoInteractions();
        verify(contracts, never()).dismiss(any(), any());
        verify(h.bot(brannock), never()).exportExcludedItems(any());
    }

    @Test
    void aCrewOrderNeverReachesAFriendsCompanionABenchedOneOrOneOnALiveBenchSlice() {
        h.outWithAnn(hadrik);
        h.outWithAnn(tobi);
        h.outWithAnn(brannock);
        when(h.world.directory().mayControl(h.ann, 902)).thenReturn(false); // Tobi's live bench slice owns him

        RosterResult result = manager.followAll(h.ann);

        assertEquals("You tell Hadrik: follow me.", ((RosterResult.Ok) result).line());
        assertEquals(List.of(901), crewOf(sentChat("follow me")));
    }

    @Test
    void anOrderToACompanionNotOutWithTheOwnerSaysSoAndIsNotSent() {
        RosterResult result = manager.followMe(h.ann, mira); // benched

        assertEquals("Mira is not out with you right now.", RosterVoice.line(mira, result));
        whispers.verifyNoInteractions();
        assertInstanceOf(RosterResult.Failed.class, manager.followAll(h.ann)); // nobody is loaded yet
        chat.verifyNoInteractions();
    }

    // ---- orders reach the existing paths ---------------------------------------------------------

    @Test
    void oneCompanionsOrdersAreTheOwnersWhispersToIt() {
        h.outWithAnn(hadrik);
        Character bot = h.bot(hadrik);

        assertEquals("Hadrik sticks with you.", ((RosterResult.Ok) manager.followMe(h.ann, hadrik)).line());
        manager.waitHere(h.ann, hadrik);
        manager.huntHere(h.ann, hadrik);

        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(h.ann, bot, "follow me"));
        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(h.ann, bot, "wait here"));
        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(h.ann, bot, "grind"));
        // the phrases are the ones the Agent's own chat classifiers take for those intents
        assertTrue(AgentChatCommandClassifier.isFollowCommand(CompanionManager.FOLLOW_ME));
        assertTrue(AgentChatCommandClassifier.isStopCommand(CompanionManager.WAIT_HERE));
        assertTrue(AgentChatCommandClassifier.isGrindCommand(CompanionManager.HUNT_HERE));
        assertFalse(AgentChatCommandClassifier.isFarmHereCommand(CompanionManager.HUNT_HERE));
        assertEquals(null, AgentChatCommandClassifier.matchFollowTarget(CompanionManager.FOLLOW_ME));
    }

    @Test
    void crewWideOrdersAreTheOwnersChatLineOverTheirOwnCompanions() {
        h.outWithAnn(hadrik);
        h.outWithAnn(tobi);

        manager.followAll(h.ann);
        manager.waitAll(h.ann);
        assertEquals("You tell Hadrik and Tobi: formation spread.", ((RosterResult.Ok) manager.setFormation(h.ann, "spread")).line());

        assertEquals(List.of(901, 902), crewOf(sentChat("follow me")));
        assertEquals(List.of(901, 902), crewOf(sentChat("wait here")));
        assertEquals(List.of(901, 902), crewOf(sentChat("formation spread")));
        assertTrue(AgentChatCommandClassifier.isStopCommand(CompanionManager.WAIT_HERE));
        assertTrue(AgentFormationCommandService.matchesCommand("formation spread"));
    }

    @Test
    void anUnknownFormationIsRefusedAndNothingIsSaid() {
        h.outWithAnn(hadrik);

        assertInstanceOf(RosterResult.Failed.class, manager.setFormation(h.ann, "zigzag"));

        chat.verifyNoInteractions();
    }

    @Test
    void gatherAndRebuffReachOnlyTheCompanionsOnTheOwnersMap() {
        h.outWithAnn(hadrik);
        h.outWithAnn(tobi);
        h.moveAnn(HENESYS, 500, 200); // Ann walked on; only Hadrik came along so far
        h.placeWithAnn(hadrik);

        manager.gatherAll(h.ann);
        manager.rebuffAll(h.ann);

        assertEquals(List.of(901), crewOf(sentChat("gather up")));
        assertEquals(List.of(901), crewOf(sentChat("gather up for buffs")));
        assertTrue(AgentPartyGatherService.matchesAssemble(CompanionManager.GATHER_UP));
        assertTrue(AgentPartyGatherService.matchesAssemble(CompanionManager.GATHER_FOR_BUFFS));
        assertFalse(AgentPartyGatherService.mentionsBuffs(CompanionManager.GATHER_UP));
        assertTrue(AgentPartyGatherService.mentionsBuffs(CompanionManager.GATHER_FOR_BUFFS));

        h.moveAnn(LITH, 500, 200); // nobody here
        assertEquals("Nobody of yours is here with you.", ((RosterResult.Failed) manager.gatherAll(h.ann)).reason());
    }

    // ---- committed -------------------------------------------------------------------------------

    @Test
    void aCommittedCompanionRefusesBenchSwapAndErrandsAfterTheRun() {
        h.outWithAnn(hadrik);
        h.commitments.commit("kpq-1", ANN, 901);

        RosterResult bench = manager.bench(h.ann, hadrik);
        RosterResult swap = manager.swap(h.ann, hadrik, mira);
        RosterResult errand = manager.assign(h.ann, hadrik, Assignment.REST, 0, 0); // out with Ann: a delegate

        for (RosterResult result : List.of(bench, swap, errand)) {
            assertInstanceOf(RosterResult.InEventInstance.class, result);
            assertEquals("Hadrik: after the run.", RosterVoice.line(hadrik, result));
        }
        verify(h.residents, Mockito.times(3)).whisper(hadrik, h.ann, RosterService.AFTER_THE_RUN_LINE);
        assertEquals(RosterState.ACTIVE, h.row(hadrik).state());
        assertEquals(Assignment.TRAIN, h.row(hadrik).assignment());
        assertEquals(RosterState.BENCHED, h.row(mira).state());
        assertTrue(manager.bench(h.ann, tobi).ok(), "one not in the run still goes");
    }

    // ---- roster verbs ----------------------------------------------------------------------------

    @Test
    void aDutyForOneOutWithYouSendsItAwayKeepingItsSeatAndSetsABenchedOnesDuty() {
        h.outWithAnn(hadrik);
        when(h.world.quests()).thenReturn(mock(QuestLedger.class));

        assertTrue(manager.assign(h.ann, hadrik, Assignment.REST, 0, 0).ok());
        assertTrue(manager.assign(h.ann, mira, Assignment.FARM_MESO, 0, 0).ok());

        assertEquals(RosterState.AWAY, h.row(hadrik).state()); // plan flow 2: delegate, the seat kept
        assertEquals(Assignment.REST, h.row(hadrik).assignment());
        assertEquals(CompanionSlots.Kind.AWAY, h.slots.reservation("hadrik").kind());
        assertEquals(RosterState.BENCHED, h.row(mira).state());
        assertEquals(Assignment.FARM_MESO, h.row(mira).assignment());
    }

    @Test
    void everyoneOnTheBenchGetsTheDutyAndNobodyOutWithYou() {
        h.outWithAnn(hadrik);
        Persona olwen = h.companion("olwen", "Olwen", 905, RosterState.BENCHED, LITH);

        RosterResult result = manager.assignAll(h.ann, Assignment.REST, 0, 0);

        assertTrue(result.ok());
        assertEquals(Assignment.REST, h.row(mira).assignment());
        assertEquals(Assignment.REST, h.row(olwen).assignment());
        assertEquals(Assignment.TRAIN, h.row(hadrik).assignment());
        assertEquals(Assignment.TRAIN, h.row(tobi).assignment());
        assertEquals(Assignment.TRAIN, h.store.row(BEN, "brannock").assignment());
    }

    @Test
    void partingWaysHandsTheHaulOverFirst() {
        RosterEntry row = h.row(mira);
        h.store.seed(new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, HENESYS, 0L, 500L, 0L,
                row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(), 0, null));
        ContractService contracts = mock(ContractService.class);
        when(h.world.contracts()).thenReturn(contracts);
        when(contracts.dismiss(h.ann, mira)).thenReturn("Mira heads home to lith harbor.");

        RosterResult result = manager.release(h.ann, mira);

        InOrder order = inOrder(h.ann, contracts);
        order.verify(h.ann).gainMeso(500, true);
        order.verify(contracts).dismiss(h.ann, mira);
        String line = ((RosterResult.Ok) result).line();
        assertTrue(line.startsWith("Mira hands over 0 item stack(s) and 500 meso."), line);
        assertTrue(line.endsWith("Mira heads home to lith harbor."), line);
    }

    @Test
    void theUsualLineupIsRememberedFromThePartyAndCalledBack() {
        assertEquals("You have no usual lineup yet.", ((RosterResult.Failed) manager.callUsual(h.ann)).reason());
        h.outWithAnn(hadrik);
        h.outWithAnn(tobi);

        assertEquals("Your usual lineup: Hadrik, Tobi.", ((RosterResult.Ok) manager.saveLineup(h.ann)).line());
        assertEquals(Map.of("hadrik", 1, "tobi", 2), h.store.store.lineupSlots(ANN));
        assertTrue(manager.callUsual(h.ann).ok());
    }

    // ---- trade, gear and the haul -----------------------------------------------------------------

    @Test
    void letsTradeOpensTheStockTradeWindowOnTheOwnersPacketThread() {
        h.outWithAnn(hadrik);
        Client client = h.ann.getClient();
        List<Runnable> io = new ArrayList<>();
        doAnswer(inv -> io.add(inv.getArgument(0))).when(client).runOnIoThread(any());
        when(h.ann.isAlive()).thenReturn(true);

        try (MockedStatic<Trade> trade = mockStatic(Trade.class);
             MockedStatic<AgentRelationshipRuntime> relationship = mockStatic(AgentRelationshipRuntime.class)) {
            assertTrue(manager.openTrade(h.ann, hadrik).ok());
            trade.verifyNoInteractions(); // not on companion-ops
            assertEquals(1, io.size());
            io.get(0).run();

            trade.verify(() -> Trade.startTrade(h.ann));
            trade.verify(() -> Trade.inviteTrade(h.ann, h.bot(hadrik)));
            relationship.verify(() -> AgentRelationshipRuntime.setInteractionTarget(any(), eq(h.ann)));
        }
    }

    @Test
    void letsTradeWithACompanionOnAnotherMapIsRefused() {
        h.outWithAnn(hadrik);
        h.moveAnn(HENESYS, 500, 200);

        try (MockedStatic<Trade> trade = mockStatic(Trade.class)) {
            assertEquals("Hadrik is too far away to trade.", ((RosterResult.Failed) manager.openTrade(h.ann, hadrik)).reason());
            trade.verifyNoInteractions();
        }
    }

    @Test
    void letMeSeeYourGearResendsTheStockCharacterInfo() {
        h.outWithAnn(hadrik);
        Packet info = mock(Packet.class);

        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class, Mockito.CALLS_REAL_METHODS)) {
            packets.when(() -> PacketCreator.charInfo(h.bot(hadrik))).thenReturn(info);
            assertTrue(manager.showGear(h.ann, hadrik).ok());
        }

        verify(h.bot(hadrik)).exportExcludedItems(h.ann.getClient());
        verify(h.ann).sendPacket(info);
        assertEquals("Mira is not out with you right now.", RosterVoice.line(mira, manager.showGear(h.ann, mira)));
    }

    @Test
    void wearYourBestPutsOnWhatTheEquipRuleWouldAndLeavesTheRestInTheHaul() {
        h.outWithAnn(hadrik);
        Character bot = h.bot(hadrik);
        Inventory equips = mock(Inventory.class);
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        h.store.haul.put(FakeLivingStore.key(ANN, "hadrik"), new TreeMap<>(Map.of(1302000, 1, 1402000, 1, 4000000, 7)));
        Equip sword = equip(1302000);
        Equip claymore = equip(1402000);
        CompanionManager manager = new CompanionManager(h.world, id -> id == 1302000 ? sword : id == 1402000 ? claymore : null,
                id -> id == 1302000 ? "Sword" : "Claymore");
        List<AgentMailboxAction<?>> mailbox = new ArrayList<>();

        try (MockedStatic<AgentEquipmentAutoEquipService> optimizer = mockStatic(AgentEquipmentAutoEquipService.class);
             MockedStatic<AgentMailboxRuntime> mailboxes = mockStatic(AgentMailboxRuntime.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            optimizer.when(() -> AgentEquipmentAutoEquipService.runOptimizerWithExtras(eq(bot), any()))
                    .thenReturn(new AgentEquipmentOptimizerResult(sword, Map.of())); // the rule picks the sword only
            mailboxes.when(() -> AgentMailboxRuntime.dispatch(any(AgentRuntimeEntry.class), any(AgentMailboxAction.class)))
                    .thenAnswer(inv -> {
                        mailbox.add(inv.getArgument(1));
                        return null;
                    });
            inventory.when(() -> InventoryManipulator.addFromDrop(any(), any(), anyBoolean())).thenReturn(true);

            RosterResult result = manager.wearBestFromHaul(h.ann, hadrik);

            assertEquals("Hadrik puts on Sword from the haul.", ((RosterResult.Ok) result).line());
            assertEquals(Map.of(1302000, 0, 1402000, 1, 4000000, 7), h.store.haul.get(FakeLivingStore.key(ANN, "hadrik")));
            inventory.verifyNoInteractions(); // the bag changes on the Agent's own mailbox, not here
            assertEquals(1, mailbox.size());
            mailbox.get(0).execute(null);
            inventory.verify(() -> InventoryManipulator.addFromDrop(bot.getClient(), sword, false));
            inventory.verify(() -> InventoryManipulator.addFromDrop(any(), eq(claymore), anyBoolean()), never());
        }
        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(h.ann, bot, "auto equip"));
        assertTrue(AgentEquipmentDialogueClassifier.isAutoEquipCommand(CompanionManager.AUTO_EQUIP));
    }

    @Test
    void aPieceThatNoLongerFitsTheBagGoesBackToTheHaul() {
        h.outWithAnn(hadrik);
        Character bot = h.bot(hadrik);
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(mock(Inventory.class));
        h.store.haul.put(FakeLivingStore.key(ANN, "hadrik"), new TreeMap<>(Map.of(1302000, 1)));
        Equip sword = equip(1302000);
        CompanionManager manager = new CompanionManager(h.world, id -> sword, id -> "Sword");
        List<AgentMailboxAction<?>> mailbox = new ArrayList<>();

        try (MockedStatic<AgentEquipmentAutoEquipService> optimizer = mockStatic(AgentEquipmentAutoEquipService.class);
             MockedStatic<AgentMailboxRuntime> mailboxes = mockStatic(AgentMailboxRuntime.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            optimizer.when(() -> AgentEquipmentAutoEquipService.runOptimizerWithExtras(eq(bot), any()))
                    .thenReturn(new AgentEquipmentOptimizerResult(sword, Map.of()));
            mailboxes.when(() -> AgentMailboxRuntime.dispatch(any(AgentRuntimeEntry.class), any(AgentMailboxAction.class)))
                    .thenAnswer(inv -> {
                        mailbox.add(inv.getArgument(1));
                        return null;
                    });
            inventory.when(() -> InventoryManipulator.addFromDrop(any(), any(), anyBoolean())).thenReturn(false); // looted full

            manager.wearBestFromHaul(h.ann, hadrik);
            mailbox.get(0).execute(null);
        }

        assertEquals(Map.of(1302000, 1), h.store.haul.get(FakeLivingStore.key(ANN, "hadrik")));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A piece of gear (a mock: a real Equip reads the item data, which tests do not have). */
    private static Equip equip(int itemId) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(itemId);
        return equip;
    }

    /** The crew map of the one chat line {@code phrase} the owner said to their companions (party channel). */
    @SuppressWarnings("unchecked")
    private Map<Integer, List<AgentRuntimeEntry>> sentChat(String phrase) {
        ArgumentCaptor<Map<Integer, List<AgentRuntimeEntry>>> crew = ArgumentCaptor.forClass(Map.class);
        chat.verify(() -> AgentChatRouteCoordinator.handleChat(eq(h.ann), eq(phrase), eq(AgentReplyChannel.PARTY),
                crew.capture(), any(), any(), any(), any(), anyInt(), anyInt()));
        return crew.getValue();
    }

    /** The character ids of the Agents a crew map holds for Ann (and nobody else). */
    private static List<Integer> crewOf(Map<Integer, List<AgentRuntimeEntry>> crew) {
        assertEquals(List.of(ANN), List.copyOf(crew.keySet()));
        return crew.get(ANN).stream().map(entry -> entry.bot().getId()).toList();
    }
}

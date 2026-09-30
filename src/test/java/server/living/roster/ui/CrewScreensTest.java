package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import net.packet.Packet;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchDutyDispatcher;
import server.living.roster.BenchLedger;
import server.living.roster.Commitments;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.CrewRotation;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The P3 Crew window (docs/BOT_MANAGEMENT_PLAN.md, "Player experience > Crew window" and the Crew window speaker rule):
 * the party block per seat state (and guests), the bench block with its bench-seat count out of the roster cap, paging,
 * a row opening that companion's card, each crew-wide action reaching CompanionManager (and, through it, only the
 * owner's own companions) and answering in pink text with the Crew window drawn again as the answer to the click, the
 * usual lineup, the bench-wide duty with the priors' rates, a stale click drawing the window again, and who speaks.
 */
class CrewScreensTest {
    private static final int ANN = 10;
    private static final int HADRIK_PORTRAIT = 9970001;
    private static final int STOLEN_FENCE = 4000021;
    private static final int ANT_TUNNEL = 105040300;
    private static final byte MENU = 4;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final CompanionPartySync sync = mock(CompanionPartySync.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final CompanionManager manager = mock(CompanionManager.class);
    private final BenchLedger ledger = mock(BenchLedger.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Character hadrikBot = mock(Character.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final Map<String, Persona> personas = new HashMap<>();

    private MockedStatic<LivingWorld> livingWorld;
    private CompanionViews views;
    private CompanionMenus menus;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.directory()).thenReturn(directory);
        when(world.config()).thenReturn(new LivingWorldConfig());
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.partySync()).thenReturn(sync);
        when(world.slots()).thenReturn(slots);
        when(world.manager()).thenReturn(manager);
        when(world.bench()).thenReturn(ledger);
        views = new CompanionViews(world, mapId -> "Ant Tunnel", itemId -> "Stolen Fence");
        when(world.views()).thenReturn(views);
        when(store.roster(ANN)).thenAnswer(inv -> List.copyOf(rows));
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenAnswer(inv -> ((RosterEntry) inv.getArgument(0)).assignment() == Assignment.TRAIN
                ? "train" : "farm Stolen Fence x200");
        when(slots.occupied(owner)).thenReturn(3);
        when(content.portraitNpcId("hadrik")).thenReturn(HADRIK_PORTRAIT);
        MapleMap perion = mock(MapleMap.class);
        when(perion.getMapName()).thenReturn("Perion");
        when(hadrikBot.getId()).thenReturn(900);
        when(hadrikBot.getName()).thenReturn("Hadrik");
        when(hadrikBot.getLevel()).thenReturn(31);
        when(hadrikBot.getJob()).thenReturn(Job.FIGHTER);
        when(hadrikBot.getMap()).thenReturn(perion);
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        for (String name : List.of("followAll", "waitAll", "gatherAll", "rebuffAll", "saveLineup", "callUsual")) {
            stubLine(name);
        }
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> "Ant Tunnel");
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        livingWorld.close();
    }

    // ---- the blocks --------------------------------------------------------------------------

    @Test
    void thePartyBlockShowsEachSeatStateAndGuestsAndTheBenchBlockEachDuty() {
        hadrik();
        add(new RosterEntry(ANN, "tobi", RosterState.TRAVELLING, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L, 0L, 0L, 0L, 0L, 0,
                null, System.currentTimeMillis() + 40_000L, 0, 0L), "Tobi", 902, 30, Job.CLERIC);
        add(row("vex", RosterState.HOLD), "Vex", 903, 22, Job.MAGICIAN);
        add(row("dorran", RosterState.PARKED), "Dorran", 904, 35, Job.SPEARMAN);
        add(new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.FARM_ITEM, STOLEN_FENCE, 200, ANT_TUNNEL, 4_872L,
                3_120L, 0L, 0L, 0L, 0L, 0, null), "Mira", 901, 28, Job.HUNTER);
        when(store.haul(ANN, "mira")).thenReturn(List.of(new HaulItem(STOLEN_FENCE, 143), new HaulItem(4000019, 5)));
        add(row("olwen", RosterState.AWAY), "Olwen", 905, 26, Job.BANDIT);
        guestInParty("Kira", 555, 25, Job.ASSASSIN);

        menus.crew(owner);

        Talk crew = lastTalk();
        assertEquals(CompanionMenus.BOARD_NPC, crew.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, crew.speaker());
        assertTrue(crew.text().startsWith("#eParty 3/6#n (1 seat kept)\r\n#L"), crew.text()); // Olwen, away, keeps hers
        // a guest is a row like the others (it opens its hire terms): the v83 client draws a plain line after a #L
        // row half a line up, over that row
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%",
                "Tobi, Lv30 Cleric - on the way (0:40)",
                "Vex, Lv22 Magician - waiting outside",
                "Dorran, Lv35 Spearman - offline",
                "Kira, Lv25 Assassin - Henesys - HP 100% (guest)",
                "Mira, Lv28 Hunter - farm @ Ant Tunnel - 143/200 Stolen Fence - 10% EXP banked - 3,120 meso, 2 stacks",
                "Olwen, Lv26 Bandit - away: train @ Ant Tunnel"), crew.labels().subList(0, 7));
        assertFalse(crew.text().contains("\r\nKira"), crew.text());
        // four of the ten roster places are in the party block
        assertTrue(crew.text().contains("\r\n#eBench (2 of 6 bench seats)#n\r\n#L"), crew.text());
    }

    @Test
    void aBenchRowSaysWhyItIsNotOutRestingOrOutOfSupplies() {
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        add(row("tobi", RosterState.BENCHED), "Tobi", 902, 30, Job.CLERIC);
        BenchDutyDispatcher bench = mock(BenchDutyDispatcher.class);
        when(bench.note("mira")).thenReturn(BenchDutyDispatcher.ROUGH_HUNT);
        when(bench.note("tobi")).thenReturn(BenchDutyDispatcher.NO_SUPPLIES);
        when(world.benchDuty()).thenReturn(bench);

        menus.crew(owner);

        List<String> labels = lastTalk().labels();
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("Mira") && label.endsWith(" - resting")), labels.toString());
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("Tobi") && label.endsWith(" - out of supplies")),
                labels.toString());
    }

    @Test
    void theBenchSeatsAreTheRosterCapLessThoseOut() {
        for (int index = 0; index < 5; index++) {
            add(row("out" + index, RosterState.ACTIVE), "Out" + index, 910 + index, 20, Job.FIGHTER);
        }
        for (int index = 0; index < 3; index++) {
            add(row("camp" + index, RosterState.BENCHED), "Camp" + index, 920 + index, 20, Job.HUNTER);
        }

        menus.crew(owner);

        assertTrue(lastTalk().text().contains("#eBench (3 of 5 bench seats)#n"), lastTalk().text());
    }

    @Test
    void anEmptyPartyAndAnEmptyBenchSaySo() {
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("mira", 1));

        menus.rosterBook(owner);
        assertTrue(lastTalk().text().contains("(nobody out with me)\r\n\r\n#eBench (1 of 10 bench seats)#n"), lastTalk().text());
        assertEquals(List.of("Mira, Lv28 Hunter - train @ Ant Tunnel", "Call my usual lineup", "Everyone on the bench..."),
                lastTalk().labels(), "nobody is out: no orders, and nobody to remember");

        LivingTalk.forget(owner);
        rows.clear();
        hadrik();
        views.invalidate(ANN);
        menus.crew(owner);
        assertTrue(lastTalk().text().contains("#eBench (0 of 9 bench seats)#n\r\n(nobody at camp)"), lastTalk().text());
        assertFalse(lastTalk().labels().contains("Everyone on the bench..."));
        assertFalse(lastTalk().labels().contains("Call my usual lineup"));
    }

    @Test
    void aLongCrewPagesWithMoreAndTheActionsFollowTheBlocks() {
        for (int index = 0; index < 5; index++) {
            add(row("out" + index, RosterState.ACTIVE), "Out" + index, 910 + index, 20, Job.FIGHTER);
            add(row("camp" + index, RosterState.BENCHED), "Camp" + index, 920 + index, 20, Job.HUNTER);
        }
        when(store.lineupSlots(ANN)).thenReturn(Map.of("camp0", 1));

        menus.crew(owner);
        List<String> first = lastTalk().labels();
        assertEquals(11, first.size()); // the ten rows, then "More..."
        assertEquals("Out0, Lv20 Fighter - on the way", first.get(0));
        assertEquals("Camp4, Lv20 Hunter - train @ Ant Tunnel", first.get(9));
        assertEquals("More...", first.get(10));

        pick("More...");

        assertEquals(List.of("Call my usual lineup", "Remember this lineup", "Everyone: follow me", "Everyone: wait here",
                "Everyone: gather up", "Everyone: rebuff", "Formation...", "Everyone on the bench...", "Back to the top..."),
                lastTalk().labels());
    }

    // ---- rows --------------------------------------------------------------------------------

    @Test
    void aPartyRowOpensThatCompanionsCardAndABenchRowItsBenchCard() {
        RosterEntry hadrikRow = hadrik();
        RosterEntry miraRow = add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new Relation.OwnCompanion(hadrikRow));
        when(directory.resolveName(owner, "Mira")).thenReturn(new Relation.OwnCompanion(miraRow));

        menus.crew(owner);
        pick("Hadrik, Lv31 Fighter - Perion - HP 90%");

        Talk card = lastTalk();
        assertEquals(HADRIK_PORTRAIT, card.npcId());
        assertEquals(0, card.speaker());
        assertTrue(card.text().startsWith("#eHadrik#n"), card.text());

        LivingTalk.forget(owner);
        menus.crew(owner);
        pick("Mira, Lv28 Hunter - train @ Ant Tunnel");

        assertTrue(lastTalk().text().startsWith("#eMira#n"), lastTalk().text());
        verify(directory).resolveName(owner, "Mira"); // ownership read again at the click
    }

    @Test
    void aCardOpenedFromACrewRowHasBackToTheCrewWindowEvenAfterItsPages() {
        RosterEntry hadrikRow = hadrik();
        RosterEntry miraRow = add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        add(row("olwen", RosterState.BENCHED), "Olwen", 905, 26, Job.BANDIT);
        when(directory.resolveName(owner, "Mira")).thenReturn(new Relation.OwnCompanion(miraRow));
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new Relation.OwnCompanion(hadrikRow));
        menus.rosterBook(owner);

        pick("Mira, Lv28 Hunter - train @ Ant Tunnel");
        assertTrue(lastTalk().text().startsWith("#eMira#n"), lastTalk().text());
        assertEquals("Back", lastTalk().labels().get(lastTalk().labels().size() - 1), "the bench card is no dead end");
        pick("What you do at camp...");
        pick("Back"); // the card again, still with its way back to the Crew window
        pick("Back");

        Talk crew = lastTalk();
        assertTrue(crew.text().startsWith("#eParty 3/6#n"), crew.text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, crew.speaker(), "the Crew window it came from: the player's notes");
        pick("Olwen, Lv26 Bandit - train @ Ant Tunnel"); // a second bench companion, all by mouse
        verify(directory).resolveName(owner, "Olwen");

        LivingTalk.forget(owner);
        menus.card(owner, hadrikRow); // a double-click: no Crew window behind it
        assertFalse(lastTalk().labels().contains("Back"), lastTalk().labels().toString());
    }

    @Test
    void aRowThatIsNoLongerYoursOpensNothing() {
        hadrik();
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new Relation.OthersCompanion(20, "hadrik"));

        menus.crew(owner);
        pick("Hadrik, Lv31 Fighter - Perion - HP 90%");

        assertEquals("Hadrik is not in your roster.", lastTalk().text());
        verifyNoInteractions(manager);
    }

    // ---- crew-wide actions -------------------------------------------------------------------------

    @Test
    void eachCrewWideOrderReachesTheManagerAndTheCrewWindowIsDrawnAgain() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        menus.crew(owner);

        order("Everyone: follow me", "followAll", m -> m.followAll(owner));
        order("Everyone: wait here", "waitAll", m -> m.waitAll(owner));
        order("Everyone: gather up", "gatherAll", m -> m.gatherAll(owner));
        order("Everyone: rebuff", "rebuffAll", m -> m.rebuffAll(owner));
    }

    @Test
    void anOrderWithNothingToSayStillDrawsTheCrewWindowAgainWithNoPinkText() {
        hadrik();
        when(manager.followAll(owner)).thenReturn(new RosterResult.Ok(""));
        menus.crew(owner);
        int selection = lastTalk().selection("Everyone: follow me");
        clearInvocations(client);

        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));

        verify(manager).followAll(owner);
        verify(owner, never()).dropMessage(anyInt(), anyString());
        assertTrue(lastTalk().text().startsWith("#eParty 3/6#n"), lastTalk().text()); // only packets since the click
    }

    @Test
    void formationOffersDistinctFormationsInPlainWordsForEveryoneOut() {
        hadrik();
        when(manager.setFormation(owner, "tight")).thenReturn(new RosterResult.Ok("You tell Hadrik: formation tight."));
        menus.crew(owner);

        pick("Formation...");
        Talk picker = lastTalk();
        assertEquals(CompanionMenus.NOTES_SPEAKER, picker.speaker());
        assertEquals(List.of("Stay close", "Give me room", "Spread out", "Keep to my left", "Keep to my right", "Back"),
                picker.labels());
        pick("Stay close");

        verify(manager).setFormation(owner, "tight");
        verify(owner, never()).dropMessage(anyInt(), anyString()); // Hadrik answers in his own words
        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
    }

    @Test
    void everyoneOnTheBenchShowsThePriorsRatesAndSetsEveryBenchDuty() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        add(row("olwen", RosterState.AWAY), "Olwen", 905, 26, Job.BANDIT);
        when(ledger.rates(eq(personas.get("mira")), any(), eq(28), anyInt(), eq(Assignment.TRAIN)))
                .thenReturn(new BenchLedger.Rates(12.4d, 0L, 840L));
        when(ledger.rates(eq(personas.get("olwen")), any(), eq(26), anyInt(), eq(Assignment.TRAIN)))
                .thenReturn(new BenchLedger.Rates(31.6d, 0L, 780L));
        when(ledger.rates(eq(personas.get("mira")), any(), eq(28), anyInt(), eq(Assignment.FARM_MESO)))
                .thenReturn(new BenchLedger.Rates(0.0d, 4_940L, 840L));
        when(ledger.rates(eq(personas.get("olwen")), any(), eq(26), anyInt(), eq(Assignment.FARM_MESO)))
                .thenReturn(new BenchLedger.Rates(0.0d, 4_680L, 780L));
        when(manager.assignAll(owner, Assignment.REST, 0, 0)).thenReturn(new RosterResult.Ok("Mira and Olwen will rest."));
        menus.crew(owner);

        pick("Everyone on the bench...");
        assertEquals(List.of("Train (~12-32% EXP/h each)", "Farm meso (~8.0k meso/h together after upkeep)",
                "Gather for my quests", "Sell the haul", "Run a stall", "Rest (no upkeep)", "Back"), lastTalk().labels());
        pick("Rest (no upkeep)");

        verify(manager).assignAll(owner, Assignment.REST, 0, 0);
        verify(owner).dropMessage(5, "Mira and Olwen will rest.");
        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
    }

    @Test
    void theBenchWideTrainRateLeavesOutOneKeepingPaceAsItsCardSays() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        add(row("olwen", RosterState.BENCHED), "Olwen", 905, 26, Job.BANDIT);
        when(ledger.rates(eq(personas.get("mira")), any(), eq(28), anyInt(), eq(Assignment.TRAIN)))
                .thenReturn(new BenchLedger.Rates(0.0d, 0L, 840L)); // at pace with Ann: it earns nothing
        when(ledger.rates(eq(personas.get("olwen")), any(), eq(26), anyInt(), eq(Assignment.TRAIN)))
                .thenReturn(new BenchLedger.Rates(31.6d, 0L, 780L));
        menus.crew(owner);

        pick("Everyone on the bench...");

        assertEquals("Train (~32% EXP/h)", lastTalk().labels().get(0));
    }

    @Test
    void makeRoomWaitsForTheRunForOneTiedToIt() {
        hadrik();
        Commitments commitments = mock(Commitments.class);
        when(world.commitments()).thenReturn(commitments);
        when(commitments.isCommitted(900)).thenReturn(true); // Hadrik is in the run
        Party full = mock(Party.class);
        List<PartyCharacter> six = new ArrayList<>();
        for (int index = 0; index < RosterService.PARTY_CAP; index++) {
            six.add(mock(PartyCharacter.class));
        }
        when(full.getMembers()).thenReturn(six);
        when(owner.getParty()).thenReturn(full);
        menus.crew(owner);

        pick("Make room for a friend...");

        assertTrue(lastTalk().text().contains("\r\nHadrik, Lv31 Fighter - Perion - HP 90% (after the run)\r\n"),
                lastTalk().text());
        assertEquals(List.of("Never mind"), lastTalk().labels());
        verifyNoInteractions(sync);
    }

    @Test
    void backFromACrewPageIsTheCrewWindowAgain() {
        hadrik();
        menus.crew(owner);
        pick("Formation...");

        pick("Back");

        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
        verifyNoInteractions(manager);
    }

    @Test
    void aCrewOrderReachesOnlyTheOwnersCompanionsOut() {
        when(world.manager()).thenReturn(null); // the real facade over this world
        hadrik();
        when(roster.entry(ANN, "hadrik")).thenReturn(rows.get(0));
        Character brannockBot = mock(Character.class); // Ben's, standing in Ann's party
        when(brannockBot.getId()).thenReturn(904);
        when(directory.mayControl(any(), anyInt())).thenReturn(true);
        AgentRuntimeEntry hadrikRuntime = new AgentRuntimeEntry(hadrikBot, owner, null);
        AgentRuntimeEntry brannockRuntime = new AgentRuntimeEntry(brannockBot, owner, null);
        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentChatRouteCoordinator> chat = mockStatic(AgentChatRouteCoordinator.class)) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(900)).thenReturn(hadrikRuntime);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(904)).thenReturn(brannockRuntime);
            menus.crew(owner);

            pick("Everyone: follow me");

            ArgumentCaptor<Map<Integer, List<AgentRuntimeEntry>>> crew = ArgumentCaptor.forClass(Map.class);
            chat.verify(() -> AgentChatRouteCoordinator.handleChat(eq(owner), eq("follow me"), eq(AgentReplyChannel.PARTY),
                    crew.capture(), any(), any(), any(), any(), anyInt(), anyInt()));
            assertEquals(Map.of(ANN, List.of(hadrikRuntime)), crew.getValue());
        }
        verify(owner, never()).dropMessage(anyInt(), anyString()); // an order that went out: the companions answer
    }

    // ---- the usual lineup --------------------------------------------------------------------------

    @Test
    void rememberThisLineupSavesItAndCallMyUsualLineupCallsIt() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("hadrik", 1, "mira", 2));
        menus.crew(owner);

        pick("Remember this lineup");
        verify(owner).dropMessage(5, "saveLineup done.");
        pick("Call my usual lineup"); // from the Crew window drawn again

        InOrder order = inOrder(manager);
        order.verify(manager).saveLineup(owner);
        order.verify(manager).callUsual(owner);
        verify(owner).dropMessage(5, "callUsual done.");
        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
    }

    @Test
    void callMyUsualLineupIsOfferedOnlyWithSomeoneBenchedAndRememberOnlyWithSomeoneOut() {
        hadrik();
        add(row("olwen", RosterState.AWAY), "Olwen", 905, 26, Job.BANDIT); // away keeps its seat: the usual call skips it
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("hadrik", 1, "olwen", 2)); // Mira has no place in it

        menus.crew(owner);

        assertFalse(lastTalk().labels().contains("Call my usual lineup"), "nobody of the lineup is benched");
        assertTrue(lastTalk().labels().contains("Remember this lineup"));

        LivingTalk.forget(owner);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("hadrik", 1, "mira", 2));
        views.invalidate(ANN); // a lineup saved: the cache reads it again
        menus.crew(owner);
        assertTrue(lastTalk().labels().contains("Call my usual lineup"));
    }

    // ---- keep my crew fresh (P5, decision 15 b) ----------------------------------------------------

    @Test
    void keepMyCrewFreshIsOffUntilTurnedOnAndEachClickSetsWhatItsLabelOffers() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        boolean[] on = {false};
        CrewRotation rotation = mock(CrewRotation.class);
        when(world.crewRotation()).thenReturn(rotation);
        when(rotation.offered()).thenReturn(true);
        when(rotation.keepsFresh(ANN)).thenAnswer(inv -> on[0]);
        when(manager.setKeepCrewFresh(eq(owner), anyBoolean()))
                .thenAnswer(inv -> {
                    on[0] = inv.getArgument(1);
                    return new RosterResult.Ok("Keep my crew fresh is " + (on[0] ? "on." : "off."));
                });
        menus.crew(owner);

        List<String> labels = lastTalk().labels();
        assertEquals(labels.indexOf("Everyone on the bench...") + 1, labels.indexOf("Keep my crew fresh (off)"),
                "right after the bench-wide duty, as the plan lists it");
        pick("Keep my crew fresh (off)");
        verify(manager).setKeepCrewFresh(owner, true);
        verify(owner).dropMessage(5, "Keep my crew fresh is on.");
        pick("Keep my crew fresh (on)"); // from the Crew window drawn again
        verify(manager).setKeepCrewFresh(owner, false);
        assertTrue(lastTalk().labels().contains("Keep my crew fresh (off)"), lastTalk().labels().toString());
    }

    @Test
    void keepMyCrewFreshIsNotOfferedWhenAutoRotationIsOff() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        CrewRotation rotation = mock(CrewRotation.class); // AUTO_ROTATE_MODE: OFF
        when(world.crewRotation()).thenReturn(rotation);

        menus.crew(owner);

        assertTrue(lastTalk().labels().stream().noneMatch(label -> label.startsWith("Keep my crew fresh")));
    }

    // ---- stale clicks ------------------------------------------------------------------------------

    @Test
    void aClickAfterTheRowsChangedDrawsTheCrewWindowAgainInsteadOfActing() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        menus.crew(owner);

        views.invalidate(ANN); // a roster write or a party change touched Ann's rows meanwhile
        pick("Remember this lineup");

        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n\r\n#eParty 3/6#n"), lastTalk().text());
        verify(manager, never()).saveLineup(any());

        pick("Remember this lineup"); // the window drawn afresh acts
        verify(manager).saveLineup(owner);
    }

    @Test
    void aRowOnlyOpensACardReadAfreshSoAChangeElsewhereNeverGetsInTheWay() {
        RosterEntry hadrikRow = hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new Relation.OwnCompanion(hadrikRow));
        menus.crew(owner);

        views.invalidate(ANN); // say, Mira's bench slice wrote her haul
        pick("Hadrik, Lv31 Fighter - Perion - HP 90%");

        assertTrue(lastTalk().text().startsWith("#eHadrik#n"), lastTalk().text()); // no "Things changed meanwhile."
        verify(directory).resolveName(owner, "Hadrik"); // ownership read again at the click
    }

    @Test
    void aBenchDutyPickedAfterTheRowsChangedDrawsTheCrewWindowAgain() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        menus.crew(owner);
        pick("Everyone on the bench...");

        views.invalidate(ANN);
        pick("Rest (no upkeep)");

        verify(manager, never()).assignAll(any(), any(), anyInt(), anyInt());
        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED), lastTalk().text());
    }

    // ---- who speaks and when -----------------------------------------------------------------------

    @Test
    void openedFromACardThatCompanionSpeaksAndBackReturnsToItsCard() {
        RosterEntry hadrikRow = hadrik();
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new Relation.OwnCompanion(hadrikRow));

        menus.crews().show(owner, personas.get("hadrik"));

        Talk crew = lastTalk();
        assertEquals(HADRIK_PORTRAIT, crew.npcId());
        assertEquals(0, crew.speaker());
        List<String> labels = crew.labels();
        assertEquals("Back", labels.get(labels.size() - 1));

        pick("Formation...");
        assertEquals(HADRIK_PORTRAIT, lastTalk().npcId());
        assertTrue(lastTalk().text().startsWith("how should we stand?"), lastTalk().text());
        pick("Back");
        pick("Back"); // the Crew window's own Back: Hadrik's card

        assertTrue(lastTalk().text().startsWith("#eHadrik#n"), lastTalk().text());
    }

    @Test
    void theRosterItemOpensThePlayersNotesWithNoBack() {
        hadrik();

        menus.rosterBook(owner);

        Talk crew = lastTalk();
        assertEquals(CompanionMenus.BOARD_NPC, crew.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, crew.speaker());
        assertFalse(crew.labels().contains("Back"));
    }

    @Test
    void anOrderDoneWhileAnotherWindowWaitsIsOnlyPinkTextAndNothingIsSentOverTheWindow() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        hadrik();
        when(manager.followAll(owner)).thenReturn(new RosterResult.Failed("Nobody of yours is out with you."));
        menus.crew(owner);
        pick("Everyone: follow me");
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened meanwhile").show();
        clearInvocations(client);

        queue.get(0).run();

        verify(manager).followAll(owner);
        verify(owner).dropMessage(5, "Nobody of yours is out with you.");
        verify(client, never()).sendPacket(any()); // a second NPC_TALK over a waiting window disconnects the client
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /**
     * Picks {@code label} on the Crew window on screen, and checks the manager, no pink echo of an order that went out
     * (each companion answers for itself) and the window drawn again.
     */
    private void order(String label, String method, Consumer<CompanionManager> call) {
        pick(label);
        call.accept(verify(manager));
        verify(owner, never()).dropMessage(5, method + " done.");
        Talk again = lastTalk();
        assertEquals(CompanionMenus.NOTES_SPEAKER, again.speaker());
        assertTrue(again.text().startsWith("#eParty 3/6#n"), again.text());
    }

    private void stubLine(String method) {
        RosterResult.Ok done = new RosterResult.Ok(method + " done.");
        switch (method) {
            case "followAll" -> when(manager.followAll(owner)).thenReturn(done);
            case "waitAll" -> when(manager.waitAll(owner)).thenReturn(done);
            case "gatherAll" -> when(manager.gatherAll(owner)).thenReturn(done);
            case "rebuffAll" -> when(manager.rebuffAll(owner)).thenReturn(done);
            case "saveLineup" -> when(manager.saveLineup(owner)).thenReturn(done);
            case "callUsual" -> when(manager.callUsual(owner)).thenReturn(done);
            default -> throw new IllegalArgumentException(method);
        }
    }

    /** Hadrik, out with Ann and loaded: Lv31 Fighter in Perion at 812/900 HP. */
    private RosterEntry hadrik() {
        RosterEntry entry = add(row("hadrik", RosterState.ACTIVE), "Hadrik", 900, 31, Job.FIGHTER);
        when(roster.companion(entry)).thenReturn(hadrikBot);
        return entry;
    }

    private RosterEntry add(RosterEntry entry, String name, int characterId, int level, Job job) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(entry.personaId());
        when(persona.name()).thenReturn(name);
        when(content.persona(entry.personaId())).thenReturn(persona);
        personas.put(entry.personaId(), persona);
        when(residents.characterIdOf(entry.personaId())).thenReturn(characterId);
        when(store.characterLevelAndJob(characterId)).thenReturn(new int[] {level, job.getId()});
        rows.add(entry);
        return entry;
    }

    /** A citizen guest in Ann's party, on the GUEST seat charged to her. */
    private void guestInParty(String name, int characterId, int level, Job job) {
        Character citizen = mock(Character.class);
        when(citizen.getName()).thenReturn(name);
        when(citizen.getLevel()).thenReturn(level);
        when(citizen.getJob()).thenReturn(job);
        MapleMap henesys = mock(MapleMap.class);
        when(henesys.getMapName()).thenReturn("Henesys");
        when(citizen.getMap()).thenReturn(henesys);
        when(citizen.getHp()).thenReturn(500);
        when(citizen.getCurrentMaxHp()).thenReturn(500);
        PartyCharacter annRow = mock(PartyCharacter.class);
        when(annRow.getId()).thenReturn(ANN);
        PartyCharacter guestRow = mock(PartyCharacter.class);
        when(guestRow.getId()).thenReturn(characterId);
        when(guestRow.getPlayer()).thenReturn(citizen);
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(annRow, guestRow));
        when(owner.getParty()).thenReturn(party);
        when(slots.reservations(ANN)).thenReturn(List.of(new CompanionSlots.Reservation(ANN,
                CompanionSlots.characterKey(characterId), CompanionSlots.Kind.GUEST, Long.MAX_VALUE, 1L)));
    }

    private void pick(String label) {
        int selection = lastTalk().selection(label);
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L, 0L, 0L, 0L, 0L, 0, null);
    }
}

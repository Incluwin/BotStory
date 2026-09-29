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
import server.living.roster.BenchLedger;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
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
        assertTrue(crew.text().startsWith("#eParty 3/6#n\r\n#L"), crew.text());
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%",
                "Tobi, Lv30 Cleric - on the way (0:40)",
                "Vex, Lv22 Magician - waiting outside",
                "Dorran, Lv35 Spearman - offline",
                "Mira, Lv28 Hunter - farm @ Ant Tunnel - 143/200 Stolen Fence - 10% EXP banked - 3,120 meso, 2 stacks",
                "Olwen, Lv26 Bandit - away: train @ Ant Tunnel"), crew.labels().subList(0, 6));
        // a guest is listed, as plain text: not yours to manage
        assertTrue(crew.text().contains("\r\nKira, Lv25 Assassin - Henesys - HP 100% (guest)\r\n"), crew.text());
        assertFalse(crew.labels().stream().anyMatch(label -> label.startsWith("Kira")));
        // four of the ten roster places are in the party block
        assertTrue(crew.text().contains("\r\n#eBench (2 of 6 bench seats)#n\r\n#L"), crew.text());
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

        menus.crew(owner);
        List<String> first = lastTalk().labels();
        assertEquals(11, first.size()); // the ten rows, then "More..."
        assertEquals("Out0, Lv20 Fighter - on the way", first.get(0));
        assertEquals("Camp4, Lv20 Hunter - train @ Ant Tunnel", first.get(9));
        assertEquals("More...", first.get(10));

        pick("More...");

        assertEquals(List.of("Call my usual lineup", "Remember this lineup", "Everyone: follow me", "Everyone: wait here",
                "Everyone: gather up", "Everyone: rebuff", "Formation...", "Everyone on the bench...", "More..."),
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
    void formationOffersTheChatCommandsFormationsForEveryoneOut() {
        hadrik();
        when(manager.setFormation(owner, "tight")).thenReturn(new RosterResult.Ok("You tell Hadrik: formation tight."));
        menus.crew(owner);

        pick("Formation...");
        Talk picker = lastTalk();
        assertEquals(CompanionMenus.NOTES_SPEAKER, picker.speaker());
        assertEquals(List.of("Stagger", "Split", "Random", "Spread", "Left", "Right", "Stack", "Tight", "Loose", "Back"),
                picker.labels());
        pick("Tight");

        verify(manager).setFormation(owner, "tight");
        verify(owner).dropMessage(5, "You tell Hadrik: formation tight.");
        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
    }

    @Test
    void everyoneOnTheBenchShowsThePriorsRatesAndSetsEveryBenchDuty() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        add(row("olwen", RosterState.AWAY), "Olwen", 905, 26, Job.BANDIT);
        when(ledger.projectedPerHour(Assignment.TRAIN, 28)).thenReturn(12.4d);
        when(ledger.projectedPerHour(Assignment.TRAIN, 26)).thenReturn(31.6d);
        when(ledger.projectedPerHour(Assignment.FARM_MESO, 28)).thenReturn(4_100.0d);
        when(ledger.projectedPerHour(Assignment.FARM_MESO, 26)).thenReturn(3_900.0d);
        when(manager.assignAll(owner, Assignment.REST, 0, 0)).thenReturn(new RosterResult.Ok("Mira will rest. Olwen will rest."));
        menus.crew(owner);
        pick("More..."); // three rows and eight actions: the last one is on the second page

        pick("Everyone on the bench...");
        assertEquals(List.of("Train (~12-32% EXP/h each)", "Farm meso (~8.0k meso/h together after upkeep)",
                "Gather for my quests", "Sell the haul", "Run a stall", "Rest (no upkeep)", "Back"), lastTalk().labels());
        pick("Rest (no upkeep)");

        verify(manager).assignAll(owner, Assignment.REST, 0, 0);
        verify(owner).dropMessage(5, "Mira will rest. Olwen will rest.");
        assertTrue(lastTalk().text().startsWith("#eParty "), lastTalk().text());
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
        verify(owner).dropMessage(5, "You tell Hadrik: follow me.");
    }

    // ---- the usual lineup --------------------------------------------------------------------------

    @Test
    void rememberThisLineupSavesItAndCallMyUsualLineupCallsIt() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
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

        menus.crew(owner);

        assertFalse(lastTalk().labels().contains("Call my usual lineup"));
        assertTrue(lastTalk().labels().contains("Remember this lineup"));
    }

    // ---- stale clicks ------------------------------------------------------------------------------

    @Test
    void aClickAfterTheRowsChangedDrawsTheCrewWindowAgainInsteadOfActing() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        menus.crew(owner);

        views.invalidate(ANN); // a roster write or a party change touched Ann's rows meanwhile
        pick("Hadrik, Lv31 Fighter - Perion - HP 90%");

        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n\r\n#eParty 3/6#n"), lastTalk().text());
        verify(directory, never()).resolveName(any(), anyString());

        views.invalidate(ANN);
        pick("Remember this lineup");
        verify(manager, never()).saveLineup(any());

        pick("Remember this lineup"); // the window drawn afresh acts
        verify(manager).saveLineup(owner);
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
        menus.crew(owner);
        pick("Everyone: follow me");
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened meanwhile").show();
        clearInvocations(client);

        queue.get(0).run();

        verify(manager).followAll(owner);
        verify(owner).dropMessage(5, "followAll done.");
        verify(client, never()).sendPacket(any()); // a second NPC_TALK over a waiting window disconnects the client
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Picks {@code label} on the Crew window on screen, and checks the manager, the pink line and the window drawn again. */
    private void order(String label, String method, Consumer<CompanionManager> call) {
        pick(label);
        call.accept(verify(manager));
        verify(owner).dropMessage(5, method + " done.");
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

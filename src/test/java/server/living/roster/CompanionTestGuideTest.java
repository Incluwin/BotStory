package server.living.roster;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import config.LivingWorldConfig;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.resident.ResidentDialogue;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * docs/TEST_GUIDE.md section 4, CO-02..CO-09, against a fake LivingStore ({@link FakeLivingStore}): the roster, bench,
 * call, assign and haul semantics each row expects, reached the way the player reaches them (a chat line, then the
 * roster verb). companion-ops runs inline here so the mocked Agent runtime statics apply; CompanionChatVerbsTest
 * covers the thread. What needs a live server (the Agent's own commands, the chat judge, a live bench slice, the
 * meso ledger's world rates) is skipped or left to the live row, with the reason.
 */
class CompanionTestGuideTest {
    private static final int ALICE = 10;
    private static final int BRANNOCK_CHARACTER = 900;
    private static final int LITH_HARBOR = 104000000;
    private static final int PIG_BEACH = 104010001;
    private static final int SNAIL_SHELL = 4000019;
    private static final Persona BRANNOCK = CompanionChatVerbsTest.persona("brannock", "Brannock");
    private static final Persona OLWEN = CompanionChatVerbsTest.persona("olwen", "Olwen");

    private final FakeLivingStore rows = new FakeLivingStore();
    private final LivingWorld world = mock(LivingWorld.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final LivingContent content = mock(LivingContent.class);
    private final Character alice = character(ALICE, "Alice");
    private final Character brannockBot = character(BRANNOCK_CHARACTER, "Brannock");
    private RosterService roster;
    private ResidentDialogue dialogue;

    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentPartyLifecycleService> party;
    private MockedStatic<Party> parties;
    private MockedStatic<ResidentSpots> spots;

    @BeforeEach
    void wireWorld() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.ROSTER_BOOK_ITEM_ID = 0;
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(rows.store);
        when(world.residents()).thenReturn(residents);
        when(world.content()).thenReturn(content);
        when(world.slots()).thenReturn(new CompanionSlots(world));
        when(world.leases()).thenReturn(new CompanionLeases());
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(world.bench()).thenReturn(new BenchLedger(world));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.bonds()).thenReturn(mock(BondService.class));
        when(world.contracts()).thenReturn(mock(ContractService.class));
        when(world.quests()).thenReturn(new QuestLedger(world));
        when(world.routineDate()).thenReturn(LocalDate.of(2026, 9, 28));
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.budget()).thenReturn(budget);
        CompanionExecutor inline = mock(CompanionExecutor.class); // the mocked statics are visible on this thread only
        when(inline.submit(anyString(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return CompletableFuture.completedFuture(null);
        });
        when(world.executor()).thenReturn(inline);
        when(world.chatVerbs()).thenReturn(new CompanionChatVerbs(world));

        for (Persona persona : List.of(BRANNOCK, OLWEN)) {
            when(content.persona(persona.id())).thenReturn(persona);
            when(content.personaByName(persona.id())).thenReturn(persona);
        }
        when(residents.ensureCharacter(BRANNOCK)).thenReturn(BRANNOCK_CHARACTER);
        when(residents.characterIdOf("brannock")).thenReturn(BRANNOCK_CHARACTER);
        rows.levels.put(ALICE, 30);
        rows.levels.put(BRANNOCK_CHARACTER, 5);

        registry = mockStatic(AgentRuntimeRegistry.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        interaction = mockStatic(AgentInteractionRuntime.class);
        party = mockStatic(AgentPartyLifecycleService.class);
        parties = mockStatic(Party.class);
        spots = mockStatic(ResidentSpots.class);
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Brannock"))
                .thenReturn(AgentSpawnResult.ok(brannockBot, true));
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, brannockBot)).thenReturn(JoinOutcome.JOINED);
        spots.when(() -> ResidentSpots.townMapId("lith-harbor")).thenReturn(LITH_HARBOR);

        roster = new RosterService(world);
        when(world.roster()).thenReturn(roster);
        dialogue = new ResidentDialogue(world);
    }

    @AfterEach
    void closeStatics() {
        spots.close();
        parties.close();
        party.close();
        interaction.close();
        cleanup.close();
        registry.close();
    }

    @Test
    @DisplayName("CO-02: accepting the contract seats Brannock, and @roster lists him ACTIVE")
    void co02AcceptingTheContractSeatsBrannockActive() {
        // ContractService.execute ends in RosterService.recruit (the terms window is CO-01). Brannock answering
        // "follow me", "grind" and "stop" is the Agent runtime, and "no Jev request" is !typesafe: both live.
        assertEquals(new RosterResult.Ok("Brannock joins your party."), roster.recruit(alice, BRANNOCK));

        assertEquals(RosterState.ACTIVE, rows.row(ALICE, "brannock").state());
        party.verify(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, brannockBot));
        say("roster");
        verify(alice).dropMessage(6, "  Brannock lv5 active");
    }

    @Test
    @Disabled("CO-03 needs a live server: the [phrase] log line and the Jev request count come from the running chat"
            + " pipeline; the phrase pack itself is covered by the PhraseMatcher unit tests")
    @DisplayName("CO-03: phrase pack")
    void co03PhrasePack() {
    }

    @Test
    @Disabled("CO-04 needs a live server and the TypeSafe key: the [typesafe-chat] judgement and its redispatch run"
            + " against the Jev API")
    @DisplayName("CO-04: free-form command with the key")
    void co04FreeFormCommand() {
    }

    @Test
    @DisplayName("CO-05: 'bench Brannock' takes him out of the party, and @roster lists him BENCHED with his assignment")
    void co05BenchTakesBrannockOutOfThePartyToCamp() {
        rows.seed(row(RosterState.ACTIVE, Assignment.TRAIN, LITH_HARBOR, 0L));
        loaded();

        say("bench brannock");

        party.verify(() -> AgentPartyLifecycleService.leaveAgentParty(brannockBot));
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(BRANNOCK_CHARACTER));
        RosterEntry benched = rows.row(ALICE, "brannock");
        assertEquals(RosterState.BENCHED, benched.state());
        assertEquals(Assignment.TRAIN, benched.assignment());
        verify(alice).dropMessage(5, "Brannock heads back to camp to train.");
        say("roster");
        verify(alice).dropMessage(6, "  Brannock lv5 benched - train at map " + LITH_HARBOR);
    }

    @Test
    @DisplayName("CO-06: 'Brannock train' on the bench banks EXP toward the owner's pace, and 'call Brannock' applies it")
    void co06TrainingOnTheBenchBanksExpAndACallAppliesIt() {
        long tenMinutesAgo = System.currentTimeMillis() - 10 * 60_000L;
        rows.seed(new RosterEntry(ALICE, "brannock", RosterState.BENCHED, Assignment.REST, 0, 0, LITH_HARBOR, 0L, 0L, 0L,
                1L, tenMinutesAgo, 0L, 0, null));

        say("brannock train");
        assertEquals(Assignment.TRAIN, rows.row(ALICE, "brannock").assignment());
        world.bench().tick();

        long banked = rows.row(ALICE, "brannock").accruedExp();
        assertTrue(banked > 0, "EXP banked toward ownerLevel - paceGap");
        assertEquals(RosterState.BENCHED, rows.row(ALICE, "brannock").state(), "the ledger never writes state");
        assertEquals(1, rows.events("bench.ledger").size());

        say("call brannock");
        verify(brannockBot).gainExp((int) banked, false, false);
        assertEquals(RosterState.ACTIVE, rows.row(ALICE, "brannock").state());
        assertEquals(0L, rows.row(ALICE, "brannock").accruedExp(), "granted once");
    }

    @Test
    @DisplayName("CO-07: 'Brannock camp here' on a field sets his camp, and 'bench Brannock' benches him there")
    void co07CampHereThenBenchCampsAtTheField() {
        // the live bench slice at the camp (BenchSessions, "bench session: Brannock train live at ...") needs a live server
        rows.seed(row(RosterState.ACTIVE, Assignment.TRAIN, LITH_HARBOR, 0L));
        when(alice.getMapId()).thenReturn(PIG_BEACH);

        say("brannock camp here");
        say("bench brannock");

        RosterEntry benched = rows.row(ALICE, "brannock");
        assertEquals(PIG_BEACH, benched.campMapId());
        assertEquals(RosterState.BENCHED, benched.state());
        verify(alice).dropMessage(5, "Brannock will camp at map " + PIG_BEACH + ".");
    }

    @Test
    @DisplayName("CO-08: 'Brannock farm meso' on the bench, 'what did you get' shows the haul, 'hand it over' transfers it")
    void co08FarmMesoThenShowAndHandOverTheHaul() {
        // the farming itself (a live slice, or the ledger at the world's meso rate) and bench.harvest need a live
        // server, and so do item names in the haul listing (WZ): the listing is checked with the meso only
        rows.seed(row(RosterState.BENCHED, Assignment.TRAIN, PIG_BEACH, 1_234L));

        say("brannock farm meso");
        assertEquals(Assignment.FARM_MESO, rows.row(ALICE, "brannock").assignment());
        verify(alice).dropMessage(5, "Brannock will farm meso.");

        say("brannock what did you get");
        verify(alice).dropMessage(6, "Brannock: 1234 meso");

        rows.store.addHaul(ALICE, "brannock", SNAIL_SHELL, 7);
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            inventory.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), anyString())).thenReturn(true);
            inventory.when(() -> InventoryManipulator.addById(any(), anyInt(), anyShort())).thenReturn(true);

            say("brannock hand it over");

            inventory.verify(() -> InventoryManipulator.addById(any(), eq(SNAIL_SHELL), eq((short) 7)));
        }
        verify(alice).dropMessage(5, "Brannock hands you 1,234 meso and 1 stack.");
        verify(alice).gainMeso(1_234, true);
        assertEquals(0L, rows.row(ALICE, "brannock").haulMeso());
        assertEquals(List.of(), rows.store.haul(ALICE, "brannock"));
        assertEquals(1, rows.events("haul.handover").size());
    }

    @Test
    @DisplayName("CO-09: farming a quest item is refused only when Brannock gave the quest that wants it")
    void co09FarmingAQuestItemIsRefusedOnlyForTheGiversOwnErrand() {
        // the item is named by id: "snail shell" and the confirmation line resolve names from WZ, which unit tests
        // do not load
        rows.seed(row(RosterState.BENCHED, Assignment.TRAIN, PIG_BEACH, 0L));
        activeQuest("brannock-manifest", "brannock");

        say("brannock farm " + SNAIL_SHELL);
        verify(alice).dropMessage(5, "Brannock: that one's your errand, not mine.");
        assertEquals(Assignment.TRAIN, rows.row(ALICE, "brannock").assignment(), "refused: nothing changes");

        activeQuest("olwen-manifest", "olwen"); // Olwen's manifest wants the same shells: allowed
        assertFalse(world.quests().isOwnQuestTarget(alice, BRANNOCK, SNAIL_SHELL));
        assertTrue(world.quests().isOwnQuestTarget(alice, OLWEN, SNAIL_SHELL));
    }

    // ---- helpers -------------------------------------------------------------------------

    private void say(String line) {
        assertTrue(dialogue.onMapChat(alice, line), "the roster takes '" + line + "'");
    }

    /** Brannock is loaded and follows Alice (nowhere near a portal: he leaves at once when benched). */
    private void loaded() {
        AgentRuntimeEntry runtime = new AgentRuntimeEntry(brannockBot, alice, null);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(BRANNOCK_CHARACTER)).thenReturn(runtime);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(BRANNOCK_CHARACTER)).thenReturn(ALICE);
    }

    /** Alice has an active quest from {@code giver} whose step collects snail shells (replacing any other). */
    private void activeQuest(String questId, String giver) {
        Step collect = mock(Step.class);
        when(collect.type()).thenReturn(StepType.COLLECT_ITEM);
        when(collect.itemId()).thenReturn(SNAIL_SHELL);
        Quest quest = mock(Quest.class);
        when(quest.persona()).thenReturn(giver);
        when(quest.steps()).thenReturn(List.of(collect));
        when(content.quest(questId)).thenReturn(quest);
        when(rows.store.questStates(ALICE, QuestStatus.ACTIVE)).thenReturn(List.of(
                new QuestState(ALICE, questId, 0, 0, QuestStatus.ACTIVE, 1L, 0L, 0)));
    }

    private static RosterEntry row(RosterState state, Assignment assignment, int campMapId, long haulMeso) {
        return new RosterEntry(ALICE, "brannock", state, assignment, 0, 0, campMapId, 0L, haulMeso, 0L, 1L, 1L, 1L, 0, null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.isLoggedin()).thenReturn(true);
        when(character.getMapId()).thenReturn(LITH_HARBOR);
        return character;
    }
}

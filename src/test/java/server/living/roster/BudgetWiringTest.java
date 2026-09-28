package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompanionBudget v1 wired into the loads (docs/BOT_MANAGEMENT_PLAN.md P1): a call asks the COMPANION pool
 * before anything is ended or loaded, a call inside the owner's load spacing is queued rather than refused,
 * and a bench slice asks the BENCH_LIVE pool (per owner and server-wide) before it loads.
 */
class BudgetWiringTest {
    private static final int ALICE = 10;
    private static final int OLWEN_CHARACTER = 900;
    private static final int BRIN_CHARACTER = 901;
    private static final Persona OLWEN = persona("olwen", "Olwen");
    private static final Persona BRIN = persona("brin", "Brin");

    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final BenchSessions benchSessions = mock(BenchSessions.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final Character alice = character(ALICE);
    private final Character olwenBot = character(OLWEN_CHARACTER);
    private final Character brinBot = character(BRIN_CHARACTER);
    private final java.util.Set<String> seatedRows = new java.util.HashSet<>();

    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentPartyLifecycleService> party;
    private MockedStatic<Party> parties;

    @BeforeEach
    void wireWorld() {
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(inv.getArgument(0) + ":" + inv.getArgument(1)));
        when(store.allBenched()).thenAnswer(inv -> List.copyOf(rows.values()));
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(entry.characterId() + ":" + entry.personaId(), entry);
            return null;
        }).when(store).saveRoster(any());
        when(residents.ensureCharacter(OLWEN)).thenReturn(OLWEN_CHARACTER);
        when(residents.characterIdOf(OLWEN.id())).thenReturn(OLWEN_CHARACTER);
        when(residents.ensureCharacter(BRIN)).thenReturn(BRIN_CHARACTER);
        when(residents.characterIdOf(BRIN.id())).thenReturn(BRIN_CHARACTER);
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.benchSessions()).thenReturn(benchSessions);
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.bench()).thenReturn(mock(BenchLedger.class));
        when(world.leases()).thenReturn(leases);

        registry = mockStatic(AgentRuntimeRegistry.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        interaction = mockStatic(AgentInteractionRuntime.class);
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen")).thenReturn(AgentSpawnResult.ok(olwenBot, true));
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Brin")).thenReturn(AgentSpawnResult.ok(brinBot, true));
        party = mockStatic(AgentPartyLifecycleService.class);
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(eq(alice), any())).thenReturn(JoinOutcome.JOINED);
        parties = mockStatic(Party.class);
    }

    @AfterEach
    void closeStatics() {
        parties.close();
        party.close();
        interaction.close();
        cleanup.close();
        registry.close();
    }

    // ---- COMPANION: RosterService.call -----------------------------------------------------

    @Test
    void aFullCompanionPoolRefusesTheCallBeforeAnythingIsEndedOrLoaded() {
        seed(row("olwen", RosterState.BENCHED));
        when(world.budget()).thenReturn(new CompanionBudget(world));
        registry.when(() -> AgentRuntimeRegistry.activeAgentCountForLeader(ALICE)).thenReturn(5);

        String reply = new RosterService(world).call(alice, OLWEN);

        assertEquals("Olwen could not join right now.", reply);
        verify(store).event(ALICE, "olwen", "roster.call_refused", "5 companions already out");
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString()), never());
        verify(benchSessions, never()).endFor(anyString(), anyString());
        assertNull(leases.holder(OLWEN_CHARACTER));
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":olwen").state());
        verify(residents).whisper(OLWEN, alice, "give me a moment. too many of us are out right now.");
    }

    @Test
    void theAgentCeilingRefusesTheCall() {
        seed(row("olwen", RosterState.BENCHED));
        when(world.budget()).thenReturn(new CompanionBudget(world));
        registry.when(AgentRuntimeRegistry::activeAgentCount).thenReturn(300);

        assertEquals("Olwen could not join right now.", new RosterService(world).call(alice, OLWEN));
        verify(store).event(ALICE, "olwen", "roster.call_refused", "Agent ceiling 300 reached");
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString()), never());
    }

    @Test
    void aCallInsideTheLoadSpacingIsQueuedAndSpawnsWhenAdmitted() {
        seed(row("olwen", RosterState.BENCHED));
        seed(row("brin", RosterState.BENCHED));
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(budget.loadDelayMs(eq(alice), anyLong())).thenReturn(0L, 1_500L, 0L);
        when(world.budget()).thenReturn(budget);
        seatedOnceActive(BRIN_CHARACTER, brinBot, "brin");
        RosterService roster = new RosterService(world);
        TimerManager timers = mock(TimerManager.class);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);

        String brinReply;
        try (MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<Server> server = online(alice)) {
            timer.when(TimerManager::getInstance).thenReturn(timers);
            assertEquals("Olwen joins your party.", roster.call(alice, OLWEN));
            brinReply = roster.call(alice, BRIN);
            interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Brin"), never());

            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), retry.capture(), eq(1_500L));
            retry.getValue().run();
        }

        assertEquals("Brin will be right there.", brinReply);
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Brin"));
        // the stock "has joined the party" notice announces Brin; no pink line on top of it
        verify(alice, never()).dropMessage(anyInt(), anyString());
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":brin").state());
    }

    @Test
    void aQueuedCallWaitsQuietlyWhileAnotherLoadHoldsTheSlot() {
        // five companions restored at login: every queued one fires together, one takes the slot, the rest wait
        seed(row("brin", RosterState.ACTIVE));
        when(store.roster(ALICE)).thenAnswer(inv -> List.copyOf(rows.values()));
        LivingContent content = mock(LivingContent.class);
        when(content.persona("brin")).thenReturn(BRIN);
        when(world.content()).thenReturn(content);
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(budget.loadDelayMs(eq(alice), anyLong())).thenReturn(1_500L, 1_200L, 0L);
        when(world.budget()).thenReturn(budget);
        seatedOnceActive(BRIN_CHARACTER, brinBot, "brin");
        RosterService roster = new RosterService(world);
        TimerManager timers = mock(TimerManager.class);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<Server> server = online(alice)) {
            timer.when(TimerManager::getInstance).thenReturn(timers);
            roster.onOwnerLogin(alice);
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), retry.capture(), eq(ArrivalPlacement.LOGIN_ARRIVAL_MS));
            retry.getValue().run(); // the restore fires once the field has loaded: another load holds the slot
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), retry.capture(), eq(1_500L));
            retry.getValue().run(); // the slot is still taken: wait again, say nothing
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), retry.capture(), eq(1_200L));
            interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString()), never());
            retry.getValue().run();
        }

        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Brin"));
        verify(alice, never()).dropMessage(anyInt(), anyString());
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":brin").state());
    }

    @Test
    void aQueuedCallDoesNotLoadIntoAPartyThatFilledMeanwhile() {
        seed(row("brin", RosterState.BENCHED));
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.loadDelayMs(eq(alice), anyLong())).thenReturn(1_500L, 0L);
        when(world.budget()).thenReturn(budget);
        RosterService roster = new RosterService(world);
        TimerManager timers = mock(TimerManager.class);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<Server> server = online(alice)) {
            timer.when(TimerManager::getInstance).thenReturn(timers);
            assertEquals("Brin will be right there.", roster.call(alice, BRIN));
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), retry.capture(), eq(1_500L));
            Party full = mock(Party.class);
            List<PartyCharacter> six = Collections.nCopies(6, mock(PartyCharacter.class));
            when(full.getMembers()).thenReturn(six);
            when(alice.getParty()).thenReturn(full);
            retry.getValue().run();
        }

        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString()), never());
        verify(alice).dropMessage(5, "Brin could not join right now.");
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":brin").state());
    }

    // ---- BENCH_LIVE: BenchSessions.start ---------------------------------------------------

    @Test
    void aBenchSliceIsNotLoadedOnceTheOwnersLiveShareIsUsed() throws Exception {
        when(benchSessions.liveCountFor(ALICE)).thenReturn(2);

        assertBenchLoadDenied("BENCH_LIVE: 2 live bench slots already used");
    }

    @Test
    void aBenchSliceIsNotLoadedOnceTheServerPoolIsFull() throws Exception {
        when(benchSessions.liveCount()).thenReturn(8);

        assertBenchLoadDenied("BENCH_LIVE: server bench pool 8 full");
    }

    /** A real BenchSessions asks the real budget, which counts the live slices through world.benchSessions(). */
    private void assertBenchLoadDenied(String logged) throws Exception {
        seed(row("olwen", RosterState.BENCHED));
        when(world.budget()).thenReturn(new CompanionBudget(world));
        LivingContent content = mock(LivingContent.class);
        when(content.persona("olwen")).thenReturn(OLWEN);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(store.characterLevel(ALICE)).thenReturn(30);
        when(store.characterLevel(OLWEN_CHARACTER)).thenReturn(10);
        when(residents.map(104000000)).thenReturn(mock(MapleMap.class));

        try (MockedStatic<CosmicAgentOfflineLoader> loader = mockStatic(CosmicAgentOfflineLoader.class);
             MockedStatic<ResidentSpots> spots = mockStatic(ResidentSpots.class)) {
            new BenchSessions(world).tick();
            loader.verifyNoInteractions();
        }
        assertNull(leases.holder(OLWEN_CHARACTER));
        verify(store).event(eq(ALICE), isNull(), eq("companion.budget_denied"), eq(logged));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static MockedStatic<Server> online(Character owner) {
        Server instance = mock(Server.class, RETURNS_DEEP_STUBS);
        when(instance.getWorld(0).getPlayerStorage().getCharacterById(owner.getId())).thenReturn(owner);
        MockedStatic<Server> server = mockStatic(Server.class);
        server.when(Server::getInstance).thenReturn(instance);
        return server;
    }

    /** The registry holds the companion once its row is ACTIVE with it loaded, as after a real call. */
    private void seatedOnceActive(int characterId, Character bot, String personaId) {
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(characterId)).thenAnswer(inv ->
                seatedRows.contains(personaId) ? new AgentRuntimeEntry(bot, alice, null) : null);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId)).thenReturn(ALICE);
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(eq(alice), eq(bot == brinBot ? "Brin" : "Olwen")))
                .thenAnswer(inv -> {
                    seatedRows.add(personaId);
                    return AgentSpawnResult.ok(bot, true);
                });
    }

    private void seed(RosterEntry entry) {
        rows.put(entry.characterId() + ":" + entry.personaId(), entry);
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ALICE, personaId, state, Assignment.TRAIN, 0, 0, 104000000, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }

    private static Persona persona(String id, String name) {
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
    }
}

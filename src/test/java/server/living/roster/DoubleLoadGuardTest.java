package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import net.server.world.Party;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeCleanupService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One materialization lease per character (docs/BOT_MANAGEMENT_PLAN.md P1, "JoinOutcome and spawn fixes"): a
 * call for a character that is leased or loaded for something else is refused, never removed and respawned
 * beside the owner (a blink); a bench slice loads only a free character and holds its lease while live.
 */
class DoubleLoadGuardTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int OLWEN_CHARACTER = 900;
    private static final Persona OLWEN = persona("olwen", "Olwen");

    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final BenchSessions benchSessions = mock(BenchSessions.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final Character alice = character(ALICE);
    private final Character olwenBot = character(OLWEN_CHARACTER);
    private final List<String> calls = new ArrayList<>();

    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentPartyLifecycleService> party;
    private MockedStatic<Party> parties;
    private MockedStatic<CosmicAgentOfflineLoader> loader;
    private MockedStatic<ResidentSpots> spots;

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
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(budget.admit(any(), anyInt(), anyLong())).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.benchSessions()).thenReturn(benchSessions);
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.bench()).thenReturn(mock(BenchLedger.class));
        when(world.leases()).thenReturn(leases);
        when(world.budget()).thenReturn(budget);

        registry = mockStatic(AgentRuntimeRegistry.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()))
                .thenAnswer(inv -> calls.add("remove " + inv.getArgument(0)));
        interaction = mockStatic(AgentInteractionRuntime.class);
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen")).thenAnswer(inv -> {
            calls.add("spawn Olwen");
            return AgentSpawnResult.ok(olwenBot, true);
        });
        party = mockStatic(AgentPartyLifecycleService.class);
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot)).thenReturn(JoinOutcome.JOINED);
        parties = mockStatic(Party.class);
    }

    @AfterEach
    void closeStatics() {
        if (loader != null) {
            loader.close();
            spots.close();
        }
        parties.close();
        party.close();
        interaction.close();
        cleanup.close();
        registry.close();
    }

    // ---- RosterService.spawnCompanion ----------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = CompanionLeases.Kind.class, names = {"RESIDENT", "POPULATION", "PQ_BACKGROUND", "EXPEDITION"})
    void aCharacterLeasedForSomethingElseIsRefusedNotBlinked(CompanionLeases.Kind holder) {
        seed(row(RosterState.BENCHED));
        leases.tryAcquire(OLWEN_CHARACTER, holder, 0);

        String reply = new RosterService(world).call(alice, OLWEN);

        assertEquals("Olwen could not join right now.", reply);
        verify(store).event(ALICE, "olwen", "roster.call_refused", "busy");
        assertEquals(List.of(), calls);
        assertEquals(holder, leases.holder(OLWEN_CHARACTER).kind());
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":olwen").state());
        verify(residents).whisper(OLWEN, alice, "give me a moment. i'm in the middle of something.");
    }

    @Test
    void anotherOwnersCompanionLeaseIsRefused() {
        seed(row(RosterState.BENCHED));
        leases.tryAcquire(OLWEN_CHARACTER, CompanionLeases.Kind.COMPANION, BOB);

        assertEquals("Olwen could not join right now.", new RosterService(world).call(alice, OLWEN));
        verify(store).event(ALICE, "olwen", "roster.call_refused", "busy");
        assertEquals(List.of(), calls);
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, BOB), leases.holder(OLWEN_CHARACTER));
    }

    @Test
    void aLiveBenchSliceIsEndedProperlyBeforeTheCall() {
        seed(row(RosterState.BENCHED));
        leases.tryAcquire(OLWEN_CHARACTER, CompanionLeases.Kind.BENCH_LIVE, ALICE);
        doAnswer(inv -> {
            calls.add("end slice");
            leases.release(OLWEN_CHARACTER, CompanionLeases.Kind.BENCH_LIVE);
            return null;
        }).when(benchSessions).endFor(eq("olwen"), anyString());

        assertEquals("Olwen joins your party.", new RosterService(world).call(alice, OLWEN));

        // BenchSessions.end saves, removes and releases; the roster never removes the runtime itself
        assertEquals(List.of("end slice", "spawn Olwen"), calls);
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ALICE), leases.holder(OLWEN_CHARACTER));
    }

    @Test
    void theCompanionLeaseIsHeldWhileActiveAndFreedOnBench() {
        seed(row(RosterState.BENCHED));
        RosterService roster = new RosterService(world);

        roster.call(alice, OLWEN);
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ALICE), leases.holder(OLWEN_CHARACTER));
        roster.bench(alice, OLWEN);

        assertNull(leases.holder(OLWEN_CHARACTER));
        assertEquals(List.of("spawn Olwen", "remove " + OLWEN_CHARACTER), calls);
    }

    @Test
    void aFailedSpawnFreesTheLeaseAndKeepsTheRowBenched() {
        seed(row(RosterState.BENCHED));
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen"))
                .thenReturn(AgentSpawnResult.fail("No character named 'Olwen' exists."));

        String reply = new RosterService(world).call(alice, OLWEN);

        assertEquals("Olwen could not join right now.", reply);
        verify(store).event(ALICE, "olwen", "roster.call_refused", "No character named 'Olwen' exists.");
        assertNull(leases.holder(OLWEN_CHARACTER));
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":olwen").state());
    }

    @Test
    void anOwnerBelowLevelTenLeadsAStarterPartyOfTheirOwnCompanions() {
        // decision 9 (a): every call path, not only the party window, forms the starter party, and before the
        // spawn, whose registration would otherwise make an ordinary party and show a lv8 owner the beginner notice
        seed(row(RosterState.BENCHED));
        when(alice.getLevel()).thenReturn(8);
        parties.when(() -> Party.createParty(alice, true, true)).thenAnswer(inv -> calls.add("starter party"));

        assertEquals("Olwen joins your party.", new RosterService(world).call(alice, OLWEN));

        assertEquals(List.of("starter party", "spawn Olwen"), calls);
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":olwen").state());
    }

    @Test
    void aSecondCallWhileTheFirstIsLoadingIsNotASecondLoad() {
        // a party-window call and a queued retry for the same companion, one inside the other's load
        seed(row(RosterState.BENCHED));
        RosterService roster = new RosterService(world);
        String[] second = new String[1];
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen")).thenAnswer(inv -> {
            calls.add("spawn Olwen");
            second[0] = roster.call(alice, OLWEN);
            return AgentSpawnResult.ok(olwenBot, true);
        });
        calls.clear(); // re-stubbing ran the setup's spawn answer once

        assertEquals("Olwen joins your party.", roster.call(alice, OLWEN));

        assertEquals("Olwen is on the way.", second[0]);
        assertEquals(List.of("spawn Olwen"), calls);
        verify(world.bench()).applyAccrued(any(), eq(olwenBot)); // once: the banked EXP is granted a single time
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":olwen").state());
    }

    @Test
    void aRefusalNeverBenchesACompanionAnotherCallSeatedMeanwhile() {
        // a login restore of an ACTIVE row is refused its load slot because another call just seated Olwen
        seed(row(RosterState.ACTIVE));
        AtomicBoolean seated = new AtomicBoolean();
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER))
                .thenAnswer(inv -> seated.get() ? new AgentRuntimeEntry(olwenBot, alice, null) : null);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(OLWEN_CHARACTER)).thenReturn(ALICE);
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenAnswer(inv -> {
            seated.set(true);
            return CompanionBudget.Admission.deny("one load every 2000 ms");
        });
        when(world.budget()).thenReturn(budget);

        assertEquals("Olwen could not join right now.", new RosterService(world).call(alice, OLWEN));

        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":olwen").state());
        assertEquals(List.of(), calls);
    }

    @Test
    void anOwnerWhoCannotFormAPartyHearsWhy() {
        seed(row(RosterState.BENCHED));
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot)).thenReturn(JoinOutcome.NO_PARTY);

        String reply = new RosterService(world).call(alice, OLWEN);

        assertEquals("Olwen could not join right now.", reply);
        verify(store).event(ALICE, "olwen", "roster.call_refused", "no party");
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":olwen").state());
        assertEquals(List.of("spawn Olwen", "remove " + OLWEN_CHARACTER), calls);
        assertNull(leases.holder(OLWEN_CHARACTER));
        verify(residents).whisper(OLWEN, alice, "i couldn't join your party. try again in a bit.");
    }

    @Test
    void anActiveRowThatCannotJoinGoesBackToTheBench() {
        // a login restore of an ACTIVE row whose party join fails
        seed(row(RosterState.ACTIVE));
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot)).thenReturn(JoinOutcome.FAILED);

        assertEquals("Olwen could not join right now.", new RosterService(world).call(alice, OLWEN));
        verify(store).event(ALICE, "olwen", "roster.call_refused", "failed");
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":olwen").state());
        verify(residents).whisper(OLWEN, alice, "i couldn't join your party. try again in a bit.");
    }

    // ---- BenchSessions load site ---------------------------------------------------------

    @Test
    void aBenchSliceSkipsACharacterThatIsAlreadyLoaded() throws Exception {
        registry.when(() -> AgentRuntimeRegistry.hasActiveAgentCharacterId(OLWEN_CHARACTER)).thenReturn(true);

        benchFixture(olwenBot);
        new BenchSessions(world).tick();

        loader.verifyNoInteractions();
        assertNull(leases.holder(OLWEN_CHARACTER));
    }

    @Test
    void aBenchSliceSkipsACharacterLeasedElsewhere() throws Exception {
        leases.tryAcquire(OLWEN_CHARACTER, CompanionLeases.Kind.COMPANION, ALICE);

        benchFixture(olwenBot);
        new BenchSessions(world).tick();

        loader.verifyNoInteractions();
        assertEquals(CompanionLeases.Kind.COMPANION, leases.holder(OLWEN_CHARACTER).kind());
    }

    @Test
    void aBenchSliceHoldsTheBenchLeaseUntilItEnds() throws Exception {
        benchFixture(olwenBot);
        BenchSessions bench = new BenchSessions(world);

        bench.tick();
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_LIVE, ALICE), leases.holder(OLWEN_CHARACTER));

        bench.endFor("olwen", "called by owner");
        assertNull(leases.holder(OLWEN_CHARACTER));
        assertEquals(List.of("remove " + OLWEN_CHARACTER), calls);
    }

    @Test
    void aFailedBenchLoadFreesTheLease() throws Exception {
        benchFixture(null);
        new BenchSessions(world).tick();

        loader.verify(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), any(), any()));
        assertNull(leases.holder(OLWEN_CHARACTER));
    }

    /** A benched TRAIN companion at a field camp, far enough below its owner to go live; the loader returns {@code loaded}. */
    private void benchFixture(Character loaded) throws Exception {
        seed(row(RosterState.BENCHED));
        LivingContent content = mock(LivingContent.class);
        when(content.persona("olwen")).thenReturn(OLWEN);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(store.characterLevel(ALICE)).thenReturn(30);
        when(store.characterLevel(OLWEN_CHARACTER)).thenReturn(10);
        MapleMap camp = mock(MapleMap.class);
        when(residents.map(104000000)).thenReturn(camp);
        Inventory empty = mock(Inventory.class);
        when(empty.list()).thenReturn(List.of());
        when(olwenBot.getInventory(any())).thenReturn(empty);
        spots = mockStatic(ResidentSpots.class); // the camp's arrival point is not what is tested
        loader = mockStatic(CosmicAgentOfflineLoader.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), eq(camp), any()))
                .thenReturn(loaded);
    }

    // ---- helpers -------------------------------------------------------------------------

    private void seed(RosterEntry entry) {
        rows.put(entry.characterId() + ":" + entry.personaId(), entry);
    }

    private static RosterEntry row(RosterState state) {
        return new RosterEntry(ALICE, "olwen", state, Assignment.TRAIN, 0, 0, 104000000, 0L,
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

package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import constants.id.MapId;
import net.server.Server;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.auth.AgentAuthorizationResult;
import server.agents.auth.AgentControlAccess;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.registry.AgentResolvedCharacter;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
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
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.GenericPortal;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.5 walk-in arrivals (docs/BOT_MANAGEMENT_PLAN.md, "P1.5: Crew window v0"): a called companion is loaded
 * standing at the usable portal nearest its owner, joins the party under the P1 JoinOutcome rules, and only then
 * follows, so it walks over instead of popping in beside the owner. A map with no usable portal keeps the
 * beside-owner spawn. The P1 guards (budget, bench slice, lease, loaded check) run in the same order either way,
 * and companions restored together at login come in a moment apart.
 */
class ArrivalPlacementTest {
    private static final int ALICE = 10;
    private static final int CAMP = 104000000;
    private static final int FIELD = 104040000;
    private static final Point OWNER_AT = new Point(1000, 200);
    private static final Persona OLWEN = persona("olwen", "Olwen");
    private static final Persona BRIN = persona("brin", "Brin");
    private static final Persona CORA = persona("cora", "Cora");
    private static final List<Persona> PERSONAS = List.of(OLWEN, BRIN, CORA);

    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final Map<String, Character> bots = new LinkedHashMap<>();
    private final Set<Integer> loaded = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final BenchSessions benchSessions = mock(BenchSessions.class);
    private final BenchLedger ledger = mock(BenchLedger.class);
    private final CompanionBudget budget = mock(CompanionBudget.class);
    private final CompanionLeases leases = spy(new CompanionLeases());
    private final MapleMap map = mock(MapleMap.class);
    private final Character alice = character(ALICE);

    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentPartyLifecycleService> party;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<Party> parties;

    @BeforeEach
    void wireWorld() {
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(inv.getArgument(0) + ":" + inv.getArgument(1)));
        when(store.roster(ALICE)).thenAnswer(inv -> List.copyOf(rows.values()));
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(entry.characterId() + ":" + entry.personaId(), entry);
            return null;
        }).when(store).saveRoster(any());
        LivingContent content = mock(LivingContent.class);
        for (int i = 0; i < PERSONAS.size(); i++) {
            Persona persona = PERSONAS.get(i);
            int characterId = 900 + i;
            Character bot = character(characterId);
            when(bot.getName()).thenReturn(persona.name());
            bots.put(persona.name(), bot);
            when(residents.ensureCharacter(persona)).thenReturn(characterId);
            when(residents.characterIdOf(persona.id())).thenReturn(characterId);
            when(content.persona(persona.id())).thenReturn(persona);
        }
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(store);
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(residents);
        when(world.benchSessions()).thenReturn(benchSessions);
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.bench()).thenReturn(ledger);
        when(world.leases()).thenReturn(leases);
        when(world.budget()).thenReturn(budget);

        // Alice stands in a field next to the companions' camp map, so a call is instant unless a test moves her
        when(alice.getMap()).thenReturn(map);
        when(alice.getMapId()).thenReturn(CAMP);
        when(alice.getPosition()).thenReturn(OWNER_AT);
        when(map.getId()).thenReturn(CAMP);
        portals(portal(Portal.MAP_PORTAL, 800, 200, FIELD, null, true), portal(Portal.MAP_PORTAL, 2400, 200, FIELD, null, true));

        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int characterId = inv.getArgument(0);
            return loaded.contains(characterId) ? new AgentRuntimeEntry(bot(characterId), alice, null) : null;
        });
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(anyInt())).thenReturn(ALICE);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt())).thenAnswer(inv -> {
            loaded.remove((Integer) inv.getArgument(0));
            return calls.add("remove " + bot((int) inv.getArgument(0)).getName());
        });
        interaction = mockStatic(AgentInteractionRuntime.class);
        interaction.when(() -> AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(eq(alice), anyString(), eq(map), any()))
                .thenAnswer(inv -> {
                    Point at = inv.getArgument(3);
                    return load(inv.getArgument(1), "spawn at " + at.x + "," + at.y);
                });
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(eq(alice), anyString()))
                .thenAnswer(inv -> load(inv.getArgument(1), "spawn beside owner"));
        party = mockStatic(AgentPartyLifecycleService.class);
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(eq(alice), any())).thenAnswer(inv -> {
            calls.add("join " + ((Character) inv.getArgument(1)).getName());
            return JoinOutcome.JOINED;
        });
        movement = mockStatic(AgentMovementCommandRuntime.class);
        movement.when(() -> AgentMovementCommandRuntime.followConfiguredTarget(any())).thenAnswer(inv -> {
            AgentRuntimeEntry runtime = inv.getArgument(0);
            return calls.add("follow " + runtime.bot().getName());
        });
        parties = mockStatic(Party.class);
    }

    @AfterEach
    void closeStatics() {
        parties.close();
        movement.close();
        party.close();
        interaction.close();
        cleanup.close();
        registry.close();
    }

    // ---- choosing the portal -----------------------------------------------------------------

    @Test
    void theNearestVisiblePortalIsChosenAndHiddenScriptedClosedAndDoorPortalsAreSkipped() {
        List<Portal> portals = List.of(
                portal(10, 1000, 200, 100000000, null, true),            // hidden ("ph"), right under the owner
                portal(1, 1010, 200, MapId.NONE, null, true),            // invisible ("pi")
                portal(7, 990, 200, MapId.NONE, "enterDollHouse", true), // script-only ("ps")
                portal(Portal.DOOR_PORTAL, 1020, 200, MapId.NONE, null, true), // Mystic Door town point
                portal(Portal.MAP_PORTAL, 1030, 200, FIELD, null, false), // a gate closed by its event
                portal(Portal.MAP_PORTAL, 1050, 200, MapId.NONE, "scriptOnly", true), // visible but leads nowhere
                portal(0, 1060, 200, MapId.NONE, null, true),            // start point: only without a visible portal
                portal(Portal.MAP_PORTAL, 400, 200, FIELD, null, true),
                portal(Portal.MAP_PORTAL, 1300, 260, FIELD, "market00", true),
                portal(Portal.MAP_PORTAL, 2000, 200, FIELD, null, true));

        assertEquals(new Point(1300, 260), ArrivalPlacement.nearestPortal(portals, OWNER_AT));
    }

    @Test
    void aStartPointIsUsedOnlyWhenTheMapHasNoVisiblePortal() {
        List<Portal> portals = List.of(
                portal(10, 1000, 200, 100000000, null, true),
                portal(0, 200, 200, MapId.NONE, null, true),
                portal(0, 1200, 150, MapId.NONE, null, true),
                portal(0, 1010, 200, MapId.NONE, "scripted", true));

        assertEquals(new Point(1200, 150), ArrivalPlacement.nearestPortal(portals, OWNER_AT));
    }

    @Test
    void aMapWithNoUsablePortalHasNoArrivalPoint() {
        List<Portal> unusable = List.of(portal(10, 1000, 200, 100000000, null, true), portal(1, 900, 200, MapId.NONE, null, true),
                portal(8, 1100, 200, MapId.NONE, "psi", true), portal(Portal.MAP_PORTAL, 800, 200, FIELD, null, false));

        assertNull(ArrivalPlacement.nearestPortal(unusable, OWNER_AT));
        assertNull(ArrivalPlacement.nearestPortal(List.of(), OWNER_AT));
        assertNull(ArrivalPlacement.nearestPortal((MapleMap) null, OWNER_AT));
        assertNull(ArrivalPlacement.nearestPortal(List.of(portal(Portal.MAP_PORTAL, 800, 200, FIELD, null, true)), null));
    }

    @Test
    void theStaggerIsAShortRandomGap() {
        for (int i = 0; i < 200; i++) {
            long gap = ArrivalPlacement.staggerMs();
            assertTrue(gap >= 300L && gap <= 900L, "gap " + gap);
        }
    }

    // ---- RosterService: the walk-in ------------------------------------------------------------

    @Test
    void aCalledCompanionIsPlacedAtTheNearestPortalAndFollowsOnlyOnceItJoined() {
        seed("olwen", RosterState.BENCHED);

        assertEquals("Olwen joins your party.", new RosterService(world).call(alice, OLWEN));

        assertEquals(List.of("spawn at 800,200", "join Olwen", "follow Olwen"), calls);
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString()), never());
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":olwen").state());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ALICE), leases.holder(900));
    }

    @Test
    void aCompanionTravellingFromCampWalksInFromThePortalWhenItArrives() {
        seed("olwen", RosterState.BENCHED);
        when(alice.getMapId()).thenReturn(FIELD); // a field one road from the camp: a real ETA
        TimerManager timers = mock(TimerManager.class);
        ArgumentCaptor<Runnable> arrival = ArgumentCaptor.forClass(Runnable.class);
        RosterService roster = new RosterService(world);

        try (MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<Server> server = online(alice)) {
            timer.when(TimerManager::getInstance).thenReturn(timers);
            assertTrue(roster.call(alice, OLWEN).startsWith("Olwen is on the way"));
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), arrival.capture(), anyLong());
            assertEquals(List.of(), calls);
            arrival.getValue().run();
        }

        assertEquals(List.of("spawn at 800,200", "join Olwen", "follow Olwen"), calls);
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":olwen").state());
    }

    @Test
    void withoutAUsablePortalTheCompanionSpawnsBesideTheOwnerAsBefore() {
        seed("olwen", RosterState.BENCHED);
        portals(portal(10, 900, 200, 100000000, null, true), portal(7, 1100, 200, MapId.NONE, "enterDollHouse", true));

        assertEquals("Olwen joins your party.", new RosterService(world).call(alice, OLWEN));

        // the beside-owner registration joins and follows by itself: no second follow command
        assertEquals(List.of("spawn beside owner", "join Olwen"), calls);
        interaction.verify(() -> AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(any(), anyString(), any(), any()), never());
        movement.verifyNoInteractions();
        assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":olwen").state());
    }

    @Test
    void aCompanionThatCannotJoinLeavesAgainWithoutEverFollowing() {
        seed("olwen", RosterState.BENCHED);
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(eq(alice), any())).thenAnswer(inv -> {
            calls.add("join refused");
            return JoinOutcome.PARTY_FULL;
        });

        assertEquals("Olwen could not join right now.", new RosterService(world).call(alice, OLWEN));

        assertEquals(List.of("spawn at 800,200", "join refused", "remove Olwen"), calls);
        movement.verifyNoInteractions();
        assertEquals(RosterState.BENCHED, rows.get(ALICE + ":olwen").state());
        assertNull(leases.holder(900));
        verify(residents).whisper(OLWEN, alice, "your party's full. i'll wait at camp.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theP1GuardsRunInTheSameOrderWithOrWithoutAPortal(boolean mapHasPortal) {
        // a lv8 owner with no party: the starter party is formed before the load, as on every P1 call path
        seed("olwen", RosterState.BENCHED);
        if (!mapHasPortal) {
            portals();
        }
        when(budget.admit(any(), any(Character.class))).thenAnswer(inv -> {
            calls.add("budget");
            return CompanionBudget.Admission.ALLOW;
        });
        doAnswer(inv -> calls.add("end bench slice")).when(benchSessions).endFor(eq("olwen"), anyString());
        doAnswer(inv -> calls.add("resident released")).when(residents).releaseForRecruit("olwen");
        doAnswer(inv -> {
            calls.add("lease");
            return inv.callRealMethod();
        }).when(leases).tryAcquire(anyInt(), any(), anyInt());
        registry.when(() -> AgentRuntimeRegistry.hasActiveAgentCharacterId(900)).thenAnswer(inv -> !calls.add("loaded check"));
        parties.when(() -> Party.createParty(alice, true, true)).thenAnswer(inv -> calls.add("starter party"));
        doAnswer(inv -> calls.add("exp")).when(ledger).applyAccrued(any(), any());

        assertEquals("Olwen joins your party.", new RosterService(world).call(alice, OLWEN));

        List<String> expected = new ArrayList<>(List.of("budget", "end bench slice", "resident released", "lease",
                "loaded check", "starter party"));
        expected.addAll(mapHasPortal ? List.of("spawn at 800,200", "join Olwen", "follow Olwen") : List.of("spawn beside owner", "join Olwen"));
        expected.add("exp");
        assertEquals(expected, calls);
    }

    @Test
    void aRecruitIsSeatedBesideTheOwnerItWasTalkingTo() {
        when(store.personaState(anyString())).thenReturn(new PersonaState("olwen", 900, null, 0, 0));
        try (MockedStatic<ResidentSpots> spots = mockStatic(ResidentSpots.class)) {
            spots.when(() -> ResidentSpots.townMapId("lith-harbor")).thenReturn(CAMP);

            assertEquals("Olwen joins your party.", new RosterService(world).recruit(alice, OLWEN));
        }

        assertEquals(List.of("spawn beside owner", "join Olwen"), calls);
        movement.verifyNoInteractions();
    }

    // ---- login restore: a moment apart -----------------------------------------------------------

    @Test
    void aLoginRestoreBringsTheFirstAtOnceAndTheOthersAMomentApart() {
        seed("olwen", RosterState.ACTIVE);
        seed("brin", RosterState.ACTIVE);
        seed("cora", RosterState.ACTIVE);
        RosterService roster = new RosterService(world);
        TimerManager timers = mock(TimerManager.class);
        ArgumentCaptor<Runnable> later = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);

        try (MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<Server> server = online(alice)) {
            timer.when(TimerManager::getInstance).thenReturn(timers);
            roster.onOwnerLogin(alice);

            assertEquals(List.of("spawn at 800,200", "join Olwen", "follow Olwen"), calls);
            verify(timers, times(2)).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), later.capture(), delays.capture());
            long brinAt = delays.getAllValues().get(0);
            long coraAt = delays.getAllValues().get(1);
            assertTrue(brinAt >= 300L && brinAt <= 900L, "Brin " + brinAt + " ms after Olwen");
            assertTrue(coraAt - brinAt >= 300L && coraAt - brinAt <= 900L, "Cora " + (coraAt - brinAt) + " ms after Brin");

            later.getAllValues().forEach(Runnable::run);
        }

        assertEquals(List.of("spawn at 800,200", "join Olwen", "follow Olwen", "spawn at 800,200", "join Brin", "follow Brin",
                "spawn at 800,200", "join Cora", "follow Cora"), calls);
        for (String personaId : List.of("olwen", "brin", "cora")) {
            assertEquals(RosterState.ACTIVE, rows.get(ALICE + ":" + personaId).state());
        }
        verify(alice, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void aStaggeredArrivalStillWaitsForTheOwnersLoadSpacing() {
        seed("olwen", RosterState.ACTIVE);
        seed("brin", RosterState.ACTIVE);
        when(budget.loadDelayMs(eq(alice), anyLong())).thenReturn(0L, 1_500L, 0L);
        RosterService roster = new RosterService(world);
        TimerManager timers = mock(TimerManager.class);
        ArgumentCaptor<Runnable> later = ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<TimerManager> timer = mockStatic(TimerManager.class);
             MockedStatic<Server> server = online(alice)) {
            timer.when(TimerManager::getInstance).thenReturn(timers);
            roster.onOwnerLogin(alice);
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), later.capture(), anyLong());
            later.getValue().run(); // Olwen's load still holds the slot: Brin waits the budget's gap, quietly
            verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), later.capture(), eq(1_500L));
            assertFalse(calls.contains("join Brin"));
            later.getValue().run();
        }

        assertEquals(List.of("spawn at 800,200", "join Olwen", "follow Olwen", "spawn at 800,200", "join Brin", "follow Brin"), calls);
        verify(alice, never()).dropMessage(anyInt(), anyString());
    }

    // ---- the portal spawn is guarded like any other ------------------------------------------------

    @Test
    void thePortalSpawnAsksTheClaimGuardBeforeAnythingIsLoaded() throws Exception {
        AgentControlAccess ownership = mock(AgentControlAccess.class);
        AgentResolvedCharacter olwen = new AgentResolvedCharacter(900, "Olwen", 1, null);
        when(ownership.resolveCharacterByName("Olwen")).thenReturn(olwen);
        when(ownership.ensureCanControl(alice, olwen)).thenReturn(AgentAuthorizationResult.denied("'Olwen' does not take orders from you."));

        AgentSpawnResult result = AgentLifecycleService.spawnAgentForLeaderAt(alice, "Olwen", map, new Point(800, 200), ownership,
                new AgentLifecycleService.SpawnHooks(
                        (spawnMap, point) -> { throw new AssertionError("no placement before the guard"); },
                        (leaderId, leader, agent) -> { throw new AssertionError("no registration"); },
                        (characterId, worldId, channel, spawnMap, point) -> { throw new AssertionError("no load"); },
                        (entry, agent, spawnMap, point) -> { throw new AssertionError("no placement"); },
                        entry -> { throw new AssertionError("no follow"); },
                        (agent, spawnMap, point) -> { throw new AssertionError("no map change"); }));

        assertFalse(result.success());
        assertEquals("'Olwen' does not take orders from you.", result.errorMessage());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private AgentSpawnResult load(String name, String call) {
        Character bot = bots.get(name);
        calls.add(call);
        loaded.add(bot.getId());
        return AgentSpawnResult.ok(bot, true);
    }

    private Character bot(int characterId) {
        return bots.values().stream().filter(bot -> bot.getId() == characterId).findFirst().orElseThrow();
    }

    private void portals(Portal... portals) {
        when(map.getPortals()).thenReturn(List.of(portals));
    }

    private void seed(String personaId, RosterState state) {
        rows.put(ALICE + ":" + personaId, new RosterEntry(ALICE, personaId, state, Assignment.TRAIN, 0, 0, CAMP, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
    }

    private static Portal portal(int type, int x, int y, int targetMapId, String script, boolean open) {
        GenericPortal portal = new GenericPortal(type);
        portal.setPosition(new Point(x, y));
        portal.setTargetMapId(targetMapId);
        portal.setScriptName(script);
        portal.setPortalStatus(open);
        return portal;
    }

    private static MockedStatic<Server> online(Character owner) {
        Server instance = mock(Server.class, RETURNS_DEEP_STUBS);
        when(instance.getWorld(0).getPlayerStorage().getCharacterById(owner.getId())).thenReturn(owner);
        MockedStatic<Server> server = mockStatic(Server.class);
        server.when(Server::getInstance).thenReturn(instance);
        return server;
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

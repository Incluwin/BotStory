package server.living.population;

import client.BotClient;
import client.Character;
import client.Client;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.resident.ResidentSpots;
import server.maps.MapleMap;
import server.maps.Portal;
import testutil.LogLines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The presence re-plan never moves a citizen in the middle of its own supply trip. Live, iNeedFox shopped on Aqua Road
 * (230000002) at 22:52:28 and stood in Leafre (240020400) at 22:52:53, so its walk back found no route; AngryWarrior
 * shopped in Kerning (103000002) at 07:11:18 and was on Kerning's spawn point on a town stint at 07:22.
 * The re-plan waits for the next sweep instead; logging off at the session's end never waits.
 */
class PresenceSchedulerSupplyTripTest {
    private static final int HUNTING_FIELD = 103000101;
    private static final int TOWN = 103000000;
    private static final int CITIZEN = 961;
    private static final String TRIP = "supply:HP_POTION:961:1";

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final Market market = mock(Market.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final MapleMap here = mock(MapleMap.class);
    private final MapleMap town = mock(MapleMap.class);
    private final Portal townSpawn = mock(Portal.class);
    private final TimerManager timers = mock(TimerManager.class);
    private final List<AgentRuntimeEntry> registered = new ArrayList<>();
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<AgentTownLifeRuntime> townLife;
    private MockedStatic<ResidentSpots> spots;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(market);
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.fields()).thenReturn(fields);
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{30, 0});
        when(population.store()).thenReturn(store);
        when(fields.homeTown(any(), any())).thenReturn(TOWN);
        when(town.getId()).thenReturn(TOWN);
        when(town.getPortal(0)).thenReturn(townSpawn);
        when(here.getId()).thenReturn(HUNTING_FIELD);
        when(population.map(anyInt(), eq(TOWN))).thenReturn(town);
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        doAnswer(inv -> {
            Citizen citizen = inv.getArgument(0);
            registry.put(citizen.characterId(), citizen);
            return null;
        }).when(population).save(any());
        timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(timers);
        movement = mockStatic(AgentMovementCommandRuntime.class);
        townLife = mockStatic(AgentTownLifeRuntime.class);
        spots = mockStatic(ResidentSpots.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
    }

    @AfterEach
    void close() {
        registered.forEach(AgentRuntimeRegistry::unregisterEntry);
        cleanup.close();
        spots.close();
        townLife.close();
        movement.close();
        timerManager.close();
    }

    @Test
    void aCitizenOnItsSupplyTripIsNotMovedUntilTheTripEnds() {
        PresenceScheduler scheduler = scheduler();
        Character agent = loaded(scheduler);
        CitizenSession session = scheduler.session(CITIZEN);
        AgentSupplyProcurementState trip = trip(session);
        session.lastPlanAtMs = 0L; // its re-plan is due

        try (LogLines lines = new LogLines(PresenceScheduler.class)) {
            scheduler.sweep();
            scheduler.sweep();
            assertEquals(List.of(), lines.startingWith("citizen AngryWarrior"), "a waiting re-plan logs at debug only");
        }

        assertNothingMoved(session, agent);
        assertEquals(0L, session.lastPlanAtMs, "the re-plan stays due for the sweep after the trip");

        trip.clear(); // the walk back ended
        scheduler.sweep();

        Runnable move = scheduledMove();
        assertNotEquals(0L, session.lastPlanAtMs);
        move.run();
        verify(agent).changeMap(town, townSpawn);
        assertEquals(TOWN, session.mapId);
    }

    @Test
    void aCitizenWithNoTripIsRePlannedAsBefore() {
        PresenceScheduler scheduler = scheduler();
        Character agent = loaded(scheduler);
        CitizenSession session = scheduler.session(CITIZEN);
        session.entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY); // idle: no trip
        session.lastPlanAtMs = 0L;

        scheduler.sweep();

        movement.verify(() -> AgentMovementCommandRuntime.stop(session.entry));
        scheduledMove().run();
        verify(agent).changeMap(town, townSpawn);
        assertEquals(CitizenSession.Activity.TOWN, session.activity);
    }

    @Test
    void aCitizenWhoseAgentIsNotRunningIsRePlannedAsBefore() {
        PresenceScheduler scheduler = scheduler();
        Character agent = loaded(scheduler);
        CitizenSession session = scheduler.session(CITIZEN);
        trip(session);
        // no running Agent entry: the old trip state on its session's entry counts for nothing
        AgentRuntimeRegistry.unregisterEntry(session.entry);
        session.lastPlanAtMs = 0L;

        scheduler.sweep();

        scheduledMove().run();
        verify(agent).changeMap(town, townSpawn);
    }

    @Test
    void aTripThatStartsWhileTheMoveWaitsKeepsTheCitizenWhereItIs() {
        PresenceScheduler scheduler = scheduler();
        Character agent = loaded(scheduler);
        CitizenSession session = scheduler.session(CITIZEN);
        session.lastPlanAtMs = 0L;
        scheduler.sweep();
        Runnable move = scheduledMove();

        AgentSupplyProcurementState trip = trip(session); // it ran low before the move came
        move.run();

        verify(agent, never()).changeMap(any(MapleMap.class), any(Portal.class));
        assertEquals(HUNTING_FIELD, session.mapId);
        assertEquals(0L, session.lastPlanAtMs, "the re-plan comes again at the first sweep after the trip");

        trip.clear();
        scheduler.sweep();
        scheduledMove().run();
        verify(agent).changeMap(town, townSpawn);
    }

    @Test
    void anotherCallersMoveWaitsForTheTripToo() {
        PresenceScheduler scheduler = scheduler();
        Character agent = loaded(scheduler);
        CitizenSession session = scheduler.session(CITIZEN);
        trip(session);

        try (LogLines lines = new LogLines(PresenceScheduler.class)) {
            // the social fabric sends a circle party to train, the market walks a shopper into a room
            scheduler.travel(session, CitizenSession.Activity.TRAIN, TOWN, System.currentTimeMillis());

            assertEquals(List.of("citizen AngryWarrior stays on map " + HUNTING_FIELD
                            + " for its supply trip; its move to " + TOWN + " waits for the next re-plan"),
                    lines.startingWith("citizen AngryWarrior"));
        }
        assertNothingMoved(session, agent);
        assertEquals(CitizenSession.Activity.TOWN, session.activity, "its activity is left as it was");
        assertEquals(0L, session.lastPlanAtMs, "it re-plans at the first sweep after the trip");
    }

    @Test
    void aCitizenOnItsSupplyTripStillLogsOffAtTheEndOfItsSession() {
        PresenceScheduler scheduler = scheduler();
        Character agent = loaded(scheduler);
        CitizenSession session = scheduler.session(CITIZEN);
        trip(session);
        session.endsAtMs = 0L;

        scheduler.sweep();

        verify(agent.getClient()).forceDisconnect();
        assertNull(scheduler.session(CITIZEN));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Every draw is its bound's top: a due re-plan never goes AFK (15 in 100) and picks TOWN (TRAIN 60, TOWN 40). */
    private PresenceScheduler scheduler() {
        return new PresenceScheduler(population, new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        });
    }

    /** A citizen logged in on a hunting field, then made a loaded one whose Agent entry is running. */
    private Character loaded(PresenceScheduler scheduler) {
        Citizen citizen = new Citizen(CITIZEN, "AngryWarrior", "evening", 0, 0, "victoria", "hunter", "relaxed-v1",
                "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        registry.put(CITIZEN, citizen);
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("AngryWarrior");
        when(agent.getClient()).thenReturn(client);
        when(agent.getMapId()).thenReturn(HUNTING_FIELD);
        when(agent.getMap()).thenReturn(here);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        registered.add(entry);
        session.entry = entry;
        session.virtual = false;
        session.mapId = HUNTING_FIELD;
        session.activity = CitizenSession.Activity.TOWN;
        return agent;
    }

    /** Its Agent set off on a critical HP potion trip (any phase counts; this one is walking back). */
    private static AgentSupplyProcurementState trip(CitizenSession session) {
        AgentSupplyProcurementState trip =
                session.entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
        trip.start(TRIP, "maintenance:resupply:" + TRIP, AgentResourceCategory.HP_POTION, 103000002, 1052104,
                HUNTING_FIELD, AgentSupplyProcurementState.Phase.RETURNING);
        return trip;
    }

    private Runnable scheduledMove() {
        ArgumentCaptor<Runnable> move = ArgumentCaptor.forClass(Runnable.class);
        verify(timers, atLeastOnce()).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), move.capture(), anyLong());
        List<Runnable> moves = move.getAllValues();
        return moves.get(moves.size() - 1);
    }

    private void assertNothingMoved(CitizenSession session, Character agent) {
        verify(timers, never()).schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong());
        movement.verify(() -> AgentMovementCommandRuntime.stop(any()), never());
        verify(market, never()).onLeave(session);
        verify(agent, never()).changeMap(any(MapleMap.class), any(Portal.class));
        assertEquals(HUNTING_FIELD, session.mapId);
    }
}

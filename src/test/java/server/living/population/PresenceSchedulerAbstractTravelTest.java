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
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.capabilities.townlife.AgentTownLifeState;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.capabilities.townlife.LithHarborTownLifeCatalog;
import server.agents.integration.MapGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.scheduler.AgentScheduler;
import server.agents.runtime.simulation.AgentAbstractExecutionScope;
import server.agents.runtime.simulation.AgentAbstractRecoveryPolicy;
import server.agents.runtime.simulation.AgentBackgroundExecutionPolicy;
import server.agents.runtime.simulation.AgentBackgroundOutcomeLedger;
import server.agents.runtime.simulation.AgentBackgroundOutcomeReconciler;
import server.agents.runtime.simulation.AgentDefaultSimulationPolicy;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.agents.runtime.simulation.AgentSimulationPolicy;
import server.agents.runtime.simulation.AgentSimulationTransitionEvidence;
import server.agents.runtime.simulation.AgentSimulationTransitionService;
import server.integration.AgentPresence;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen that travels while its Agent runs abstract leaves BACKGROUND_ABSTRACT cleanly before it is moved. Its
 * TownLife used to exit gracefully: a committed DWELL held the abstract scope, the move landed 1 s later, and the
 * window could not reconcile (map and position changed), so every such travel logged "left BACKGROUND_ABSTRACT ...
 * without reconciling" through the bounded abandon (2026-10-14). The real TownLife, mode policy and outcome ledger
 * run here; the test plays the woken scheduler's next mode decision.
 */
class PresenceSchedulerAbstractTravelTest {
    private static final int TOWN = LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID;
    private static final int FIELD = 104010001;
    private static final int CITIZEN = 963;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final MapleMap town = mock(MapleMap.class);
    private final MapleMap field = mock(MapleMap.class);
    private final Portal fieldSpawn = mock(Portal.class);
    private final TimerManager timers = mock(TimerManager.class);
    private final MapGateway maps = mock(MapGateway.class);
    private final AgentSimulationPolicy modes = new AgentDefaultSimulationPolicy(
            true, true, maps, AgentBackgroundExecutionPolicy.allowlisted());
    private final AgentSimulationTransitionService transitions = new AgentSimulationTransitionService(
            runtime -> true,
            AgentBackgroundOutcomeReconciler.ledgerBacked(),
            new AgentAbstractRecoveryPolicy(maps::isObservedByPlayer, 30_000L));
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<AgentScheduler> scheduler;
    private Character agent;
    private AgentRuntimeEntry entry;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        doAnswer(inv -> {
            Citizen citizen = inv.getArgument(0);
            registry.put(citizen.characterId(), citizen);
            return null;
        }).when(population).save(any());
        when(town.getId()).thenReturn(TOWN);
        when(field.getId()).thenReturn(FIELD);
        when(field.getPortal(0)).thenReturn(fieldSpawn);
        when(population.map(anyInt(), eq(FIELD))).thenReturn(field);
        timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(timers);
        movement = mockStatic(AgentMovementCommandRuntime.class);
        scheduler = mockStatic(AgentScheduler.class);
    }

    @AfterEach
    void close() {
        if (entry != null && AgentTownLifeRuntime.active(entry)) {
            AgentTownLifeRuntime.forceStop(entry, agent, "test cleanup");
        }
        AgentPresence.install(null);
        scheduler.close();
        movement.close();
        timerManager.close();
    }

    @Test
    void anAbstractCitizenReconcilesBeforeItIsMoved() {
        PresenceScheduler presence = presence();
        CitizenSession session = dwellingInTown(presence);
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(2_000L));

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, 3_000L);

        assertFalse(AgentTownLifeRuntime.active(entry), "nobody can see it, so TownLife stops at once");
        assertEquals(AgentAbstractExecutionScope.NONE, entry.simulationState().abstractExecutionScope());
        scheduler.verify(() -> AgentScheduler.wake(entry));
        verify(agent, never()).changeMap(any(MapleMap.class), any(Portal.class));

        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(3_050L)); // the woken scheduler's next frame
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED, lastOutcome());
        assertEquals(1L, ledger().reconciliationCount());
        assertEquals(0L, ledger().abandonmentCount());

        scheduledMove().run();
        verify(agent).changeMap(field, fieldSpawn);
        standOn(field, new Point(40, 120));
        assertEquals(CitizenSession.Activity.TRAIN, session.activity);
        movement.verify(() -> AgentMovementCommandRuntime.grind(entry));
        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(5_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.ALREADY_IN_MODE, lastOutcome());
        assertEquals(0L, ledger().abandonmentCount());
    }

    @Test
    void theMoveWaitsWhileItsSchedulerHasNotDecidedYet() {
        PresenceScheduler presence = presence();
        CitizenSession session = dwellingInTown(presence);
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(2_000L));
        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, 3_000L);

        scheduledMove().run(); // a busy scheduler has not run its next frame within the 1 s pause

        verify(agent, never()).changeMap(any(MapleMap.class), any(Portal.class));
        scheduler.verify(() -> AgentScheduler.wake(entry), times(2));
        verify(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), any(Runnable.class), eq(500L));

        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(4_200L));
        scheduledMove().run();
        verify(agent).changeMap(field, fieldSpawn);
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED, lastOutcome());
        assertEquals(0L, ledger().abandonmentCount());
    }

    @Test
    void theMoveStopsWaitingAfterItsChecks() {
        PresenceScheduler presence = presence();
        CitizenSession session = dwellingInTown(presence);
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(2_000L));
        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, 3_000L);

        for (int check = 0; check < PresenceScheduler.ABSTRACT_EXIT_CHECKS; check++) {
            scheduledMove().run(); // its scheduler never decides (a stalled frame)
        }
        verify(agent, never()).changeMap(any(MapleMap.class), any(Portal.class));
        scheduledMove().run();

        verify(agent).changeMap(field, fieldSpawn);
        standOn(field, new Point(40, 120));
        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(12_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED_LEDGER_ABANDONED, lastOutcome());
        assertEquals("abstract scope ended", ledger().lastAbandonment().reason());
    }

    @Test
    void aCitizenSomeoneCanSeeStillFinishesItsDwell() {
        PresenceScheduler presence = presence();
        CitizenSession session = dwellingInTown(presence);
        when(maps.isObservedByPlayer(town)).thenReturn(true);
        assertEquals(AgentSimulationMode.PRESENTATION, decide(2_000L));

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, 3_000L);

        AgentTownLifeState state = entry.capabilityStates().require(AgentTownLifeState.STATE_KEY);
        assertTrue(AgentTownLifeRuntime.active(entry), "a graceful exit waits out the committed dwell");
        assertTrue(state.exitRequested());
        scheduler.verify(() -> AgentScheduler.wake(any()), never());
        scheduledMove().run();
        verify(agent).changeMap(field, fieldSpawn);
    }

    @Test
    void anAbstractCitizenWithAPlayerJustArrivedExitsGracefully() {
        PresenceScheduler presence = presence();
        CitizenSession session = dwellingInTown(presence);
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(2_000L));
        Character player = mock(Character.class);
        AgentPresence.install(chr -> chr != player);
        when(town.getAllPlayers()).thenReturn(List.of(agent, player)); // its scheduler has not seen the player yet

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, 3_000L);

        assertTrue(AgentTownLifeRuntime.active(entry), "the player sees it finish its dwell");
        scheduler.verify(() -> AgentScheduler.wake(entry));
        when(maps.isObservedByPlayer(town)).thenReturn(true);
        assertEquals(AgentSimulationMode.PRESENTATION, decide(3_050L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED, lastOutcome());
        assertEquals(0L, ledger().abandonmentCount());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private PresenceScheduler presence() {
        return new PresenceScheduler(population, new Random(7L));
    }

    /** A loaded citizen in Lith Harbor whose real TownLife is in a committed LINGER dwell. */
    private CitizenSession dwellingInTown(PresenceScheduler presence) {
        Citizen citizen = new Citizen(CITIZEN, "QuietBaker", "evening", 0, 0, "victoria", "hunter", "relaxed-v1",
                "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        registry.put(CITIZEN, citizen);
        CitizenSession session = presence.login(citizen, System.currentTimeMillis(), true);
        agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("QuietBaker");
        when(agent.getClient()).thenReturn(client);
        when(agent.getChair()).thenReturn(-1);
        standOn(town, new Point(300, 150));
        entry = new AgentRuntimeEntry(agent, null, null);
        session.entry = entry;
        session.virtual = false;
        session.mapId = TOWN;
        session.activity = CitizenSession.Activity.TOWN;

        AgentTownLifeSessionResult started = AgentTownLifeRuntime.requestLocal(
                entry, agent, AgentTownLifeVisitRequest.leisure(TOWN),
                AgentTownLifeAdmissionMode.MANUAL_ONLY, 1_000L, CITIZEN);
        assertTrue(started.started());
        AgentTownLifeState state = entry.capabilityStates().require(AgentTownLifeState.STATE_KEY);
        state.select(AgentTownLifeState.Activity.LINGER, new Point(300, 150), 0, 1_000L);
        state.beginDwell(60_000L);
        assertTrue(state.hasCommittedActivity());
        return session;
    }

    private void standOn(MapleMap map, Point position) {
        int mapId = map.getId();
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getPosition()).thenReturn(position);
    }

    private AgentSimulationMode decide(long nowMs) {
        return transitions.transition(entry, modes.selectMode(entry), nowMs);
    }

    private AgentSimulationTransitionEvidence.Outcome lastOutcome() {
        return entry.simulationState().lastTransitionEvidence().outcome();
    }

    private AgentBackgroundOutcomeLedger.Snapshot ledger() {
        return entry.simulationState().backgroundOutcomes().snapshot();
    }

    private Runnable scheduledMove() {
        ArgumentCaptor<Runnable> move = ArgumentCaptor.forClass(Runnable.class);
        verify(timers, atLeastOnce()).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), move.capture(), anyLong());
        List<Runnable> moves = move.getAllValues();
        return moves.get(moves.size() - 1);
    }
}

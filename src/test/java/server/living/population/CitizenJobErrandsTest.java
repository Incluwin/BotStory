package server.living.population;

import client.Character;
import client.Job;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Career;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen whose life reaches its third job takes it at its chief in El Nath (2026-10-06): its session starts on the
 * Chief's Residence, it walks up to Tylus, stands a moment, advances, says so and goes about its day. Moved away or
 * logged out on the way: nothing. Refused: no second try this session.
 */
class CitizenJobErrandsTest {
    private static final int ID = 800;
    private static final int CHIEFS_RESIDENCE = 211000001;
    private static final int TYLUS = 2020008;
    private static final Point TYLUS_SPOT = new Point(100, 50);

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final List<Long> delays = new java.util.ArrayList<>();
    private final long[] now = {1_000_000L};
    private final int[] advances = {0};
    private Citizen citizen;
    private CitizenSession session;
    private Character agent;
    private MapleMap map;
    private CitizenJobErrands errands;
    private MockedStatic<AgentMovementCommandRuntime> movement;

    @BeforeEach
    void wire() {
        PopulationContent content = mock(PopulationContent.class);
        when(content.career("warrior-fighter")).thenReturn(new Career("warrior-fighter", "FIGHTER", 1, 75, List.of()));
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(content);
        when(population.store()).thenReturn(store);
        when(population.chatter()).thenReturn(chatter);
        citizen = citizen(Set.of());
        when(population.citizen(ID)).thenAnswer(inv -> citizen);
        session = new CitizenSession(citizen, now[0], now[0] + 3_600_000L, false);
        agent = mock(Character.class);
        when(agent.getName()).thenReturn("Tobi");
        when(agent.getJob()).thenReturn(Job.FIGHTER);
        when(agent.getLevel()).thenReturn(72);
        when(agent.getPosition()).thenReturn(new Point(600, 50));
        session.entry = new AgentRuntimeEntry(agent, agent, null);
        session.mapId = CHIEFS_RESIDENCE;
        when(presence.session(ID)).thenAnswer(inv -> session);
        NPC tylus = mock(NPC.class);
        when(tylus.getPosition()).thenReturn(TYLUS_SPOT);
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(CHIEFS_RESIDENCE);
        when(map.getNPCById(TYLUS)).thenReturn(tylus);
        errands = new CitizenJobErrands(population, presence);
        errands.clock = () -> now[0];
        errands.later = (task, delayMs) -> {
            queue.add(task);
            delays.add(delayMs);
        };
        errands.advance = (entry, job) -> {
            advances[0]++;
            return CompletableFuture.completedFuture(true);
        };
        movement = mockStatic(AgentMovementCommandRuntime.class);
    }

    @AfterEach
    void close() {
        movement.close();
    }

    @Test
    void itIsPlannedForItsThirdJobOnceItsLifeReachesLevelSeventy() {
        session.virtual = true;
        when(store.characterProgress(ID)).thenReturn(new int[]{69, 0, 110, 0, 0});
        assertNull(errands.planned(session));
        when(store.characterProgress(ID)).thenReturn(new int[]{72, 0, 110, 0, 0});
        assertEquals(Job.CRUSADER, errands.planned(session));
        session.errandJob = Job.CRUSADER;
        assertEquals(CHIEFS_RESIDENCE, errands.mapFor(session), "Tylus in El Nath's Chief's Residence");
    }

    @Test
    void aBacklogCitizenAHireOrErrandsOffAreNeverPlanned() {
        session.virtual = true;
        when(store.characterProgress(ID)).thenReturn(new int[]{72, 0, 110, 0, 0});
        citizen = citizen(Set.of(Citizen.FLAG_JOB_BACKLOG));
        assertNull(errands.planned(session), "its overdue job comes off-screen");
        citizen = citizen(Set.of(Citizen.FLAG_HIRED));
        assertNull(errands.planned(session), "a hire is its owner's (the companion errands)");
        citizen = citizen(Set.of());
        config.JOB_ERRANDS_ENABLED = false;
        assertNull(errands.planned(session), "the kill switch");
    }

    @Test
    void itWalksUpToItsChiefStandsAMomentAdvancesSaysSoAndGoesAboutItsDay() {
        start();

        assertTrue(errands.begin(session, map));
        movement.verify(() -> AgentMovementCommandRuntime.moveTo(session.entry, TYLUS_SPOT, false));
        run(); // still walking
        assertEquals(0, advances[0]);
        when(agent.getPosition()).thenReturn(new Point(120, 50));
        run(); // there: it talks first
        assertEquals(0, advances[0]);
        assertEquals((long) config.JOB_ERRAND_TALK_MS, delays.get(delays.size() - 1));
        now[0] += config.JOB_ERRAND_TALK_MS;
        run(); // the advancement
        assertEquals(1, advances[0]);
        run(); // done

        ArgumentCaptor<ChatterEngine.Event> said = ArgumentCaptor.forClass(ChatterEngine.Event.class);
        verify(chatter).event(said.capture());
        assertEquals("jobadvance.self", said.getValue().trigger);
        assertEquals("crusader", said.getValue().job);
        verify(presence).replan(eq(session), anyLong());
        assertNull(session.errandJob);
    }

    @Test
    void itAdvancesWhereItStandsWhenTheWalkTakesTooLong() {
        start();
        assertTrue(errands.begin(session, map));

        now[0] += config.JOB_ERRAND_WALK_MAX_MS;
        run();
        now[0] += config.JOB_ERRAND_TALK_MS;
        run();

        assertEquals(1, advances[0]);
    }

    @Test
    void refusedItGoesAboutItsDayAndIsNotPlannedAgainThisSession() {
        errands.advance = (entry, job) -> CompletableFuture.completedFuture(false);
        start();
        when(agent.getPosition()).thenReturn(new Point(100, 50));
        assertTrue(errands.begin(session, map));

        run();
        now[0] += config.JOB_ERRAND_TALK_MS;
        run();
        run();

        verify(chatter, never()).event(any());
        verify(presence).replan(eq(session), anyLong());
        assertTrue(session.errandFailed);
        assertNull(errands.planned(session));
    }

    @Test
    void movedAwayOnTheWayItNeverAdvances() {
        start();
        assertTrue(errands.begin(session, map));

        session.activity = Activity.TRAIN; // a party leader took it training
        now[0] += config.JOB_ERRAND_WALK_MAX_MS + config.JOB_ERRAND_TALK_MS;
        while (!queue.isEmpty()) {
            run();
        }

        assertEquals(0, advances[0]);
        verify(presence, never()).replan(any(), anyLong());
    }

    @Test
    void itDoesNotSetOffWhenNothingIsDueAfterAll() {
        start();
        when(agent.getLevel()).thenReturn(69);

        assertFalse(errands.begin(session, map));
        assertTrue(queue.isEmpty());
    }

    @Test
    void theSchedulerPlansTheErrandAheadOfTheCohortsActivitiesAndSendsItToItsChief() {
        session.virtual = true;
        when(store.characterProgress(anyInt())).thenReturn(new int[]{72, 0, 110, 0, 0});
        PresenceScheduler scheduler = new PresenceScheduler(population);

        assertEquals(Activity.JOB, scheduler.plan(session, now[0]));
        assertEquals(Job.CRUSADER, session.errandJob);
        assertEquals(CHIEFS_RESIDENCE, scheduler.mapFor(session, Activity.JOB));
    }

    @Test
    void aLoginWritesOntoTheCurrentRecordSoAFlagTheCatchUpClearedStaysCleared() {
        Citizen snapshot = citizen(Set.of(Citizen.FLAG_JOB_BACKLOG)); // the record the sweep logged it in with
        java.util.Map<Integer, Citizen> registry = new java.util.HashMap<>(java.util.Map.of(ID, citizen(Set.of())));
        when(population.update(anyInt(), any())).thenAnswer(inv -> {
            Citizen next = inv.<java.util.function.UnaryOperator<Citizen>>getArgument(1).apply(registry.get(ID));
            registry.put(ID, next);
            return next;
        });
        when(population.citizen(ID)).thenAnswer(inv -> registry.get(ID));
        PresenceScheduler scheduler = new PresenceScheduler(population);

        scheduler.login(snapshot, now[0], true);

        assertFalse(registry.get(ID).has(Citizen.FLAG_JOB_BACKLOG), "the cleared backlog flag is not written back");
        assertEquals(now[0], registry.get(ID).lastOnlineAtMs());
    }

    private void start() {
        session.activity = Activity.JOB;
        session.errandJob = Job.CRUSADER;
    }

    private void run() {
        Runnable task = queue.poll();
        if (task != null) {
            task.run();
        }
    }

    private static Citizen citizen(Set<String> flags) {
        return new Citizen(ID, "Tobi", "evening", 0, 0, "victoria", "warrior-fighter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}

package server.living.population;

import client.BotClient;
import client.Character;
import client.Client;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Cohort;
import server.maps.MapleMap;

import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Presence and the PLAY activity (CitizenMiniGames): PLAY is planned only when minigames take the citizen (the kill
 * switch, a town of its region), and a citizen on a PLAY session is neither re-planned nor sent AFK.
 */
class PresenceSchedulerPlayTest {
    private static final int CITIZEN = 977;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenMiniGames miniGames = mock(CitizenMiniGames.class);
    private final TimerManager timers = mock(TimerManager.class);
    private Citizen citizen;
    private AgentRuntimeEntry entry;
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.citizens()).thenReturn(List.of());
        PopulationContent content = mock(PopulationContent.class);
        when(population.content()).thenReturn(content);
        when(content.cohort("evening")).thenReturn(new Cohort("evening", ZoneOffset.UTC, 1, 10, new int[24], new int[24],
                30, 60, Map.of("PLAY", 100, "TOWN", 1)));
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{25, 0, 0, 0});
        when(population.store()).thenReturn(store);
        when(population.fields()).thenReturn(mock(FieldCatalog.class));
        citizen = new Citizen(CITIZEN, "OmokOli", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        when(population.citizen(anyInt())).thenReturn(citizen);
        timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(timers);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
    }

    @AfterEach
    void close() {
        if (entry != null) {
            AgentRuntimeRegistry.unregisterEntry(entry);
        }
        cleanup.close();
        timerManager.close();
    }

    @Test
    void playIsPlannedOnlyWhenMinigamesTakeTheCitizen() {
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = new CitizenSession(citizen, 0L, 3_600_000L, true);

        assertEquals(Activity.TOWN, scheduler.plan(session, 1_000L)); // no minigames at all

        when(population.miniGames()).thenReturn(miniGames);
        assertEquals(Activity.TOWN, scheduler.plan(session, 1_000L)); // switched off, or no town of its region

        when(miniGames.plays(citizen, 1_000L)).thenReturn(true);
        assertEquals(Activity.PLAY, scheduler.plan(session, 1_000L));
    }

    @Test
    void aCitizenAtTheTableIsNotRePlannedOrSentAfk() {
        when(population.miniGames()).thenReturn(miniGames);
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = loaded(scheduler);
        session.lastPlanAtMs = 0L; // its re-plan is long due
        when(miniGames.holds(session)).thenReturn(true);

        scheduler.sweep();

        assertEquals(Activity.PLAY, session.activity);
        assertEquals(0L, session.afkUntilMs);
        verify(timers, never()).schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong());
    }

    /** A loaded citizen on a PLAY session in Henesys. */
    private CitizenSession loaded(PresenceScheduler scheduler) {
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("OmokOli");
        when(agent.getClient()).thenReturn(client);
        when(agent.getMapId()).thenReturn(100_000_000);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        entry = new AgentRuntimeEntry(agent, null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        session.entry = entry;
        session.virtual = false;
        session.mapId = 100_000_000;
        session.activity = Activity.PLAY;
        return session;
    }
}

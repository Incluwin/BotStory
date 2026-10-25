package server.living.population;

import client.Character;
import client.Client;
import config.PopulationConfig;
import client.BotClient;
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
import server.living.population.PopulationContent.Lobby;
import server.maps.MapleMap;

import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Presence leaves a citizen in its party's party quest alone (CitizenPartyQuests): its session running out logs it
 * off only after the run, no re-plan or AFK moves it meanwhile, and once the run is over it plans no party quest for
 * a while.
 */
class PresenceSchedulerPartyQuestTest {
    private static final int CITIZEN = 962;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenPartyQuests partyQuests = mock(CitizenPartyQuests.class);
    private final TimerManager timers = mock(TimerManager.class);
    private Citizen citizen;
    private AgentRuntimeEntry entry;
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.partyQuests()).thenReturn(partyQuests);
        when(population.citizens()).thenReturn(List.of());
        citizen = new Citizen(CITIZEN, "LobbyLia", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
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
    void aCitizenInItsPartyQuestOutlivesItsSessionUntilTheRunIsOver() {
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = loaded(scheduler);
        session.endsAtMs = 0L;
        when(partyQuests.holds(CITIZEN)).thenReturn(true);

        scheduler.sweep();
        assertNotNull(scheduler.session(CITIZEN));

        when(partyQuests.holds(CITIZEN)).thenReturn(false);
        scheduler.sweep();
        assertNull(scheduler.session(CITIZEN));
    }

    @Test
    void noRePlanOrAfkMovesItDuringTheRun() {
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = loaded(scheduler);
        session.lastPlanAtMs = 0L; // its re-plan is long due
        when(partyQuests.holds(CITIZEN)).thenReturn(true);

        scheduler.sweep();

        assertEquals(Activity.PQ, session.activity);
        assertEquals(0L, session.afkUntilMs);
        verify(timers, never()).schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong());
    }

    @Test
    void aCitizenJustBackFromARunPlansNoPartyQuest() {
        PopulationContent content = mock(PopulationContent.class);
        when(population.content()).thenReturn(content);
        when(content.cohort("evening")).thenReturn(new Cohort("evening", ZoneOffset.UTC, 1, 10, new int[24], new int[24],
                30, 60, Map.of("PQ", 100, "TOWN", 1)));
        FieldCatalog fields = mock(FieldCatalog.class);
        when(population.fields()).thenReturn(fields);
        when(fields.lobbyFor(any(), anyInt())).thenReturn(new Lobby("kpq", 103_000_000, 21, 30, "KPQ"));
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{25, 0, 0, 0});
        when(population.store()).thenReturn(store);
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = new CitizenSession(citizen, 0L, 3_600_000L, true);

        assertEquals(Activity.PQ, scheduler.plan(session, 1_000L));
        when(partyQuests.coolingDown(CITIZEN, 1_000L)).thenReturn(true);
        assertEquals(Activity.TOWN, scheduler.plan(session, 1_000L));
    }

    /** A loaded citizen out for KPQ at the Kerning lobby. */
    private CitizenSession loaded(PresenceScheduler scheduler) {
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("LobbyLia");
        when(agent.getClient()).thenReturn(client);
        when(agent.getMapId()).thenReturn(103_000_000);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        entry = new AgentRuntimeEntry(agent, null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        session.entry = entry;
        session.virtual = false;
        session.mapId = 103_000_000;
        session.activity = Activity.PQ;
        return session;
    }
}

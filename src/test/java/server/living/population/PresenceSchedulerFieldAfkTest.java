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

import java.awt.Point;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Decision D6 through the presence sweep (docs/BOT_CONCEPT_PLAN.md F8): a citizen whose due re-plan goes AFK on a
 * training field pauses at most 2 min ("brb"), while one in town keeps the 5-15 min AFK.
 */
class PresenceSchedulerFieldAfkTest {
    private static final int CITIZEN = 978;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private Citizen citizen;
    private AgentRuntimeEntry entry;
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    /** Every draw low (a due re-plan goes AFK), every double high (the longest pause the config allows). */
    private static final class Draws extends Random {
        @Override
        public int nextInt(int bound) {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0.999999d;
        }
    }

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.chatter()).thenReturn(chatter);
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.citizens()).thenReturn(List.of());
        when(population.fields()).thenReturn(fields);
        PopulationContent content = mock(PopulationContent.class);
        when(population.content()).thenReturn(content);
        when(content.cohort("evening")).thenReturn(new Cohort("evening", ZoneOffset.UTC, 1, 10, new int[24], new int[24],
                30, 60, Map.of("TOWN", 1)));
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{25, 0, 0, 0});
        when(population.store()).thenReturn(store);
        citizen = new Citizen(CITIZEN, "FieldFin", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        when(population.citizen(anyInt())).thenReturn(citizen);
        timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
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
    void aDueAfkOnAFieldIsAPauseOfAtMostTwoMinutes() {
        when(fields.kind(anyInt())).thenReturn("field");
        PresenceScheduler scheduler = new PresenceScheduler(population, new Draws());
        CitizenSession session = loaded(scheduler);
        long before = System.currentTimeMillis();
        scheduler.sweep();
        long planned = session.afkUntilMs - before;
        assertTrue(session.fieldPause);
        assertTrue(planned >= FieldPause.MIN_MS && planned <= config.FIELD_AFK_MAX_MS + 1_000L, "pause " + planned);
        assertTrue(planned <= 120_000L);
    }

    @Test
    void aDueAfkInTownKeepsTheLongAfk() {
        when(fields.kind(anyInt())).thenReturn("town");
        PresenceScheduler scheduler = new PresenceScheduler(population, new Draws());
        CitizenSession session = loaded(scheduler);
        long before = System.currentTimeMillis();
        scheduler.sweep();
        assertFalse(session.fieldPause);
        assertEquals(5 * 60_000L, session.afkUntilMs - before, 1_000L, "the draw of 0: 5 min");
    }

    /** A loaded citizen in good health on map 104000100 with no mob near, its re-plan long due. */
    private CitizenSession loaded(PresenceScheduler scheduler) {
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of());
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("FieldFin");
        when(agent.getClient()).thenReturn(client);
        when(agent.getMapId()).thenReturn(104_000_100);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        when(agent.getHp()).thenReturn(1_000);
        when(agent.getCurrentMaxHp()).thenReturn(1_000);
        when(agent.isAlive()).thenReturn(true);
        entry = new AgentRuntimeEntry(agent, null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        session.entry = entry;
        session.virtual = false;
        session.mapId = 104_000_100;
        session.activity = Activity.TOWN;
        session.lastPlanAtMs = 0L;
        return session;
    }
}

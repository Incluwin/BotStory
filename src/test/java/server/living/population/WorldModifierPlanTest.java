package server.living.population;

import client.Character;
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
import server.living.population.events.WorldEvents;
import server.living.population.events.WorldModifier;

import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The world modifier in the citizens' plans (docs/WORLD_SYSTEMS_PLAN.md N4.1): its weights, target maps and avoided
 * fields while an event sets them, and nothing at all once it clears them.
 */
class WorldModifierPlanTest {
    private static final int CITIZEN = 977;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final WorldEvents events = mock(WorldEvents.class);
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
                30, 60, Map.of("MARKET", 10, "TRAIN", 30)));
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

    private int planned(PresenceScheduler scheduler, Activity wanted) {
        CitizenSession session = new CitizenSession(citizen, 0L, 3_600_000L, true);
        int count = 0;
        for (int i = 0; i < 4_000; i++) {
            count += scheduler.plan(session, 1_000L) == wanted ? 1 : 0;
        }
        return count;
    }

    private void modifier(WorldModifier modifier) {
        when(population.worldEvents()).thenReturn(events);
        when(events.modifier()).thenReturn(modifier);
    }

    @Test
    void aMarketDayRaisesMarketPlansAndItsEndTakesThatAway() {
        citizen = citizen.withFlag(Citizen.FLAG_MERCHANT);
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        double before = planned(scheduler, Activity.MARKET) / 4_000.0d;
        WorldModifier day = WorldModifier.NONE.with(WorldModifier.Effect.marketDay("m", 2, 0.85d, 3.0d, Long.MAX_VALUE));
        modifier(day);
        double during = planned(scheduler, Activity.MARKET) / 4_000.0d;
        modifier(day.without("m"));
        double after = planned(scheduler, Activity.MARKET) / 4_000.0d;
        assertEquals(0.25d, before, 0.03d);
        assertEquals(0.5d, during, 0.03d, "30 of 60");
        assertEquals(0.25d, after, 0.03d);
    }

    @Test
    void anEffectIsForItsRegionOnly() {
        WorldModifier.Effect festival = WorldModifier.Effect.festival("f", "orbis", 200000000, 4.0d, Long.MAX_VALUE);
        modifier(WorldModifier.NONE.with(festival));
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = new CitizenSession(citizen, 0L, 3_600_000L, true);
        assertEquals(0, scheduler.mapFor(session, Activity.PLAY), "a victoria citizen plays where it likes");
        modifier(WorldModifier.NONE.with(WorldModifier.Effect.festival("f", "victoria", 100000000, 4.0d, Long.MAX_VALUE)));
        assertEquals(100000000, scheduler.mapFor(session, Activity.PLAY));
        modifier(WorldModifier.NONE);
        assertEquals(0, scheduler.mapFor(session, Activity.PLAY));
    }

    @Test
    void anAvoidedFieldIsAvoidedByThoseUnderItsLevelUntilCleared() {
        PresenceScheduler scheduler = new PresenceScheduler(population, new Random(3L));
        CitizenSession session = loaded(scheduler);
        assertEquals(false, scheduler.avoiding(session, 106010000, 0L));
        modifier(WorldModifier.NONE.with(WorldModifier.Effect.avoid("i", 106010000, 25, Long.MAX_VALUE)));
        assertEquals(true, scheduler.avoiding(session, 106010000, 0L), "level 20");
        when(session.agent().getLevel()).thenReturn(25);
        assertEquals(false, scheduler.avoiding(session, 106010000, 0L));
        modifier(WorldModifier.NONE.with(WorldModifier.Effect.avoid("i", 106010000, WorldModifier.EVERYONE, Long.MAX_VALUE)));
        assertEquals(true, scheduler.avoiding(session, 106010000, 0L), "overrun");
        assertEquals(false, scheduler.avoiding(session, 106010001, 0L));
        modifier(WorldModifier.NONE);
        assertEquals(false, scheduler.avoiding(session, 106010000, 0L));
    }

    @Test
    void eventIsNeverRolled() {
        when(population.content().cohort("evening")).thenReturn(new Cohort("evening", ZoneOffset.UTC, 1, 10, new int[24],
                new int[24], 30, 60, Map.of("TRAIN", 1)));
        assertEquals(0, planned(new PresenceScheduler(population, new Random(3L)), Activity.EVENT));
    }

    /** A loaded level-20 citizen. */
    private CitizenSession loaded(PresenceScheduler scheduler) {
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getLevel()).thenReturn(20);
        entry = new AgentRuntimeEntry(agent, null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        session.entry = entry;
        session.virtual = false;
        return session;
    }
}

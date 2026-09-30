package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime;
import server.agents.capabilities.partyquest.kpq.AgentKpqPopulationRuntime;
import server.living.LivingWorld;
import server.living.config.LivingContent;
import server.living.population.CitizenSession.Activity;
import server.living.roster.CompanionLeases;
import server.living.roster.RosterService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The background KPQ/HPQ directors only get citizens the population may send (P6 WP4): never one some owner has
 * hired, and never one the living world has loaded for a companion or a live bench slice. A citizen's own
 * POPULATION lease keeps it eligible, as does no lease at all.
 */
class SocialFabricPqCitizensTest {
    private static final int OWNED = 960;
    private static final int COMPANION = 961;
    private static final int BENCH = 962;
    private static final int CITIZEN = 963;
    private static final int UNLEASED = 964;

    @Test
    void ownedAndLeasedCitizensStayOutOfBackgroundPartyQuests() {
        CompanionLeases leases = new CompanionLeases();
        leases.tryAcquire(COMPANION, CompanionLeases.Kind.COMPANION, 7);
        leases.tryAcquire(BENCH, CompanionLeases.Kind.BENCH_LIVE, 7);
        leases.tryAcquire(CITIZEN, CompanionLeases.Kind.POPULATION, 0);
        RosterService roster = mock(RosterService.class);
        when(roster.cachedOwnerOf(anyString())).thenReturn(0);
        when(roster.cachedOwnerOf(LivingContent.HIRE_PREFIX + OWNED)).thenReturn(7);
        LivingWorld world = mock(LivingWorld.class);
        when(world.leases()).thenReturn(leases);
        when(world.roster()).thenReturn(roster);
        List<CitizenSession> sessions = new ArrayList<>();
        for (int id : new int[] {OWNED, COMPANION, BENCH, CITIZEN, UNLEASED}) {
            sessions.add(onPq(id));
        }
        Population population = population(world, sessions);

        ArgumentCaptor<Supplier<Set<Integer>>> kpqIds = supplier();
        ArgumentCaptor<Supplier<Set<Integer>>> hpqIds = supplier();
        try (MockedStatic<AgentKpqPopulationRuntime> kpq = mockStatic(AgentKpqPopulationRuntime.class);
             MockedStatic<AgentHpqPopulationRuntime> hpq = mockStatic(AgentHpqPopulationRuntime.class)) {
            new SocialFabric(population).installSeams();

            kpq.verify(() -> AgentKpqPopulationRuntime.installHostManaged(any(), kpqIds.capture()));
            hpq.verify(() -> AgentHpqPopulationRuntime.installHostManaged(any(), hpqIds.capture()));
        }

        assertEquals(Set.of(CITIZEN, UNLEASED), kpqIds.getValue().get());
        assertEquals(Set.of(CITIZEN, UNLEASED), hpqIds.getValue().get());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Supplier<Set<Integer>>> supplier() {
        return ArgumentCaptor.forClass((Class<Supplier<Set<Integer>>>) (Class<?>) Supplier.class);
    }

    private static Population population(LivingWorld world, List<CitizenSession> sessions) {
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        PresenceScheduler presence = mock(PresenceScheduler.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(store.guilds()).thenReturn(List.of());
        when(population.presence()).thenReturn(presence);
        when(presence.sessions()).thenReturn(sessions);
        when(population.world()).thenReturn(world);
        return population;
    }

    /** A citizen online (not virtual) whose session is out for a party quest. */
    private static CitizenSession onPq(int characterId) {
        Citizen citizen = new Citizen(characterId, "citizen" + characterId, "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0,
                "");
        CitizenSession session = new CitizenSession(citizen, 0L, 60_000L, false);
        session.activity = Activity.PQ;
        return session;
    }
}

package server.living.population;

import client.Character;
import config.LivingWorldConfig;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import server.living.resident.ResidentSpots;
import server.maps.MapleMap;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A citizen plans where it logs in before it loads, so that plan cannot read its equip bag (docs/ECONOMY_PLAN.md E3).
 * Live 10-29 every sell trip began at a citizen's first re-plan, 25-45 minutes after its login, and the 70 citizens on
 * gear errands whose bags were still full had sold nothing since the trips began: their sessions ended first. Once it
 * has loaded, a citizen whose bag sends it to sell plans again at the next sweep.
 */
class PresenceSellAtLoadTest {
    private static final int TOWN = 104000000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PopulationContent content = mock(PopulationContent.class);
    private final Map<Integer, Citizen> registry = new java.util.HashMap<>();
    private MockedStatic<CosmicAgentOfflineLoader> loader;
    private MockedStatic<AgentInteractionRuntime> runtime;
    private MockedStatic<ResidentSpots> spots;
    private MockedStatic<AgentMovementCommandRuntime> movement;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(content);
        LivingWorld world = mock(LivingWorld.class);
        when(population.world()).thenReturn(world);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.ledger()).thenReturn(mock(ProgressionLedger.class));
        FieldCatalog fields = mock(FieldCatalog.class);
        when(fields.homeTown(any(), any())).thenReturn(TOWN);
        when(population.fields()).thenReturn(fields);
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{30, 0, 0, 0, 0});
        when(population.store()).thenReturn(store);
        MapleMap town = mock(MapleMap.class);
        when(town.getId()).thenReturn(TOWN);
        when(population.map(anyInt(), anyInt())).thenReturn(town);
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        int[] flat = new int[24];
        Arrays.fill(flat, 10);
        when(content.cohort("all-day")).thenReturn(new PopulationContent.Cohort("all-day", ZoneId.of("UTC"), 100, 100,
                flat, flat, 60, 60, Map.of("TOWN", 1)));
        for (int id = 1; id <= 2; id++) {
            registry.put(id, new Citizen(id, "Citizen" + id, "all-day", 0, 0, "victoria", "hunter", "relaxed-v1",
                    "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""));
        }
        loader = mockStatic(CosmicAgentOfflineLoader.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(anyInt(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> mock(Character.class));
        runtime = mockStatic(AgentInteractionRuntime.class);
        runtime.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> new AgentRuntimeEntry(inv.getArgument(0), null, null));
        spots = mockStatic(ResidentSpots.class);
        movement = mockStatic(AgentMovementCommandRuntime.class);
    }

    @AfterEach
    void close() {
        movement.close();
        spots.close();
        runtime.close();
        loader.close();
    }

    @Test
    void aCitizenLoadedWithAFullEquipBagPlansAgainAtTheNextSweep() {
        long now = 100 * 3_600_000L;
        PresenceScheduler presence = new PresenceScheduler(population, new Random(3));
        int[] free = {0};
        presence.sellTrips().freeEquipSlots = who -> free[0];

        CitizenSession full = presence.login(registry.get(1), now, false);
        assertNotNull(full);
        assertEquals(0L, full.lastPlanAtMs, "its plan could not read its bag: it plans again to go and sell");
        assertTrue(presence.sellTrips().due(full, now));

        free[0] = 40;
        CitizenSession roomy = presence.login(registry.get(2), now, false);
        assertEquals(now, roomy.lastPlanAtMs, "room in its bag: the plan it logged in with stands");
        assertFalse(presence.sellTrips().due(roomy, now));
    }

    @Test
    void anErrandBuyerWithAFullBagGoesToSellBeforeItShops() {
        long now = 100 * 3_600_000L;
        PresenceScheduler presence = new PresenceScheduler(population, new Random(3));
        presence.sellTrips().freeEquipSlots = who -> 40;
        CitizenSession session = presence.login(registry.get(1), now, false);
        assertFalse(presence.sellSoon(session, now), "room enough: it shops");
        assertEquals(now, session.lastPlanAtMs);

        presence.sellTrips().freeEquipSlots = who -> 0;
        presence.sellTrips().shopFor = who -> new int[]{100000102, 1012003};
        assertTrue(presence.sellSoon(session, now + 60_000L));
        assertEquals(0L, session.lastPlanAtMs, "it plans again at the next sweep, and its bag sends it to sell");

        session.sellTrip = presence.sellTrips().planned(session, now + 120_000L);
        session.lastPlanAtMs = now + 120_000L;
        assertFalse(presence.sellSoon(session, now + 180_000L), "already on its way to sell");
    }
}

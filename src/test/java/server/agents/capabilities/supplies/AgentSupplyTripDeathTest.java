package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatDeathRuntime;
import server.agents.capabilities.combat.AgentDeathStateRuntime;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.MapGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.ShopGateway;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.SpawnPoint;
import server.maps.MapleMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A supply trip and a death on its way. Live 2026-11-01 (77666f9fc): the citizen SpearArcher, a Lv70 Marauder (job
 * 511) with no potions and about 60 of 5,111 HP, set off from Cold Cradle for Ant Tunnel Park's store, died in the Ant
 * Tunnel, respawned in Sleepywood at 50 HP and walked straight back in, 50+ minutes and dozens of deaths: the trip
 * outlived every death (each respawn picked its walk up again, and the walk's no-progress watchdog restarts on every
 * map), and nothing kept a walk at respawn HP with nothing to drink off the monsters' maps. Every map past Sleepywood
 * on the way down spawns monsters; Sleepywood, Henesys and the town shops spawn none.
 */
class AgentSupplyTripDeathTest {
    private static final int COLD_CRADLE = 105090311;
    private static final int SLEEPYWOOD = 105040300;
    private static final int ANT_TUNNEL_III = 105050200;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int HENESYS = 100000000;
    private static final int HENESYS_MARKET = 100000100;
    private static final int HENESYS_SHOP = 100000102;
    private static final int HAUNTED_HOUSE = 682000000;
    private static final int BENT_TREE = 610010000;
    private static final int DEAD_MANS_GORGE = 610010004;
    private static final int MAX_HP = 5_111;
    private static final int RESPAWN_HP = 50;

    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final int[] standsOn = {COLD_CRADLE};
    private final int[] hp = {60};
    private MockedStatic<AgentShopGatewayRuntime> shopData;
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gatewayRuntime;
    private MockedStatic<AgentMapGatewayRuntime> mapRuntime;
    private MockedStatic<AgentClientGatewayRuntime> clientRuntime;
    private PrimitiveCapabilityGateway gateway;

    @BeforeEach
    void world() {
        shopData = mockStatic(AgentShopGatewayRuntime.class);
        shopData.when(AgentShopGatewayRuntime::shop).thenReturn(mock(ShopGateway.class));
        gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong())).thenAnswer(call -> new AgentRouteOutcome(
                AgentRouteStatus.MOVING, standsOn[0], -1, call.getArgument(2), false));
        gatewayRuntime = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gatewayRuntime.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        MapGateway mapGateway = mock(MapGateway.class);
        when(mapGateway.resolveMap(anyInt(), anyInt(), anyInt())).thenAnswer(call -> map(call.getArgument(2)));
        mapRuntime = mockStatic(AgentMapGatewayRuntime.class);
        mapRuntime.when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        clientRuntime = mockStatic(AgentClientGatewayRuntime.class);
        clientRuntime.when(AgentClientGatewayRuntime::clients).thenReturn(mock(AgentClientGateway.class));
        for (int safe : new int[]{SLEEPYWOOD, HENESYS, HENESYS_MARKET, HENESYS_SHOP}) {
            maps.put(safe, map(safe, false));
        }
    }

    @AfterEach
    void close() {
        clientRuntime.close();
        mapRuntime.close();
        gatewayRuntime.close();
        shopData.close();
    }

    @Test
    void aDeathOnTheWayEndsTheTripWhereItRespawnedInsteadOfWalkingBackIn() {
        Character agent = marauder();
        AgentRuntimeEntry entry = entry(agent);
        AgentSupplyProcurementState execution = execution(entry);
        planShortage(entry, "supply:HP_POTION:532");

        // Out of potions on Cold Cradle: the nearest store is Ant Tunnel Park's.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));
        assertEquals(ANT_TUNNEL_PARK, execution.supplierMapId());
        verify(gateway).travelTo(entry, agent, ANT_TUNNEL_PARK, 1_000L);

        // It dies in the Ant Tunnel and respawns in Sleepywood at 50 HP, still with nothing to drink.
        standsOn[0] = ANT_TUNNEL_III;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 60_000L));
        dieAndRespawn(entry, agent, SLEEPYWOOD);
        clearInvocations(gateway);

        // The trip is over where it stands: no walk on to the store, nor back to Cold Cradle through the same maps.
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 66_000L));
        assertFalse(execution.isActive());
        assertNull(AgentObjectiveKernel.active(entry));
        assertTrue(execution.lastOutcome().reason().contains("died on map " + ANT_TUNNEL_III),
                execution.lastOutcome().reason());

        // Its planner raises the shortage again and again while it regenerates in town: no walk sets off at that HP.
        for (long at = 70_000L; at <= 670_000L; at += 30_000L) {
            hp[0] = RESPAWN_HP + (int) ((at - 66_000L) / 10_000L) * 10; // passive regen, 10 HP per 10 s
            planShortage(entry, "supply:HP_POTION:532:" + at);
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, at));
            assertFalse(execution.isActive());
        }
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
        assertEquals(SLEEPYWOOD, standsOn[0]);
    }

    @Test
    void aWalkThatComesIntoTownAtCriticalHpWithNothingToDrinkStopsThere() {
        Character agent = marauder();
        AgentRuntimeEntry entry = entry(agent);
        AgentSupplyProcurementState execution = execution(entry);
        planShortage(entry, "supply:HP_POTION:533");
        hp[0] = 4_000;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));
        assertEquals(ANT_TUNNEL_PARK, execution.supplierMapId());

        // It came through Sleepywood badly hurt: the Ant Tunnel lies ahead.
        standsOn[0] = SLEEPYWOOD;
        hp[0] = 300;
        clearInvocations(gateway);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 2_000L));

        assertFalse(execution.isActive());
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void theAssassinsWalkAcrossBentTreeWaitsInTheHauntedHouseUntilItsHpIsBack() {
        // Faschan and FireBunny, Lv36-38 Assassins of about 1,700 HP, 10-30 and 10-31: from the Haunted House's hall
        // (no monsters, the Phantom Forest's return map) to Mo's store in Dead Man's Gorge across Bent Tree's Phantom
        // Trees, one to two hours a trip, ended only by logging off.
        maps.put(HAUNTED_HOUSE, map(HAUNTED_HOUSE, false));
        maps.put(DEAD_MANS_GORGE, map(DEAD_MANS_GORGE, false));
        standsOn[0] = HAUNTED_HOUSE;
        hp[0] = 1_200;
        Character agent = citizen(901, "Faschan", Job.ASSASSIN, 38, 1_740);
        AgentRuntimeEntry entry = entry(agent, "thief-claw-standard-v1");
        AgentSupplyProcurementState execution = execution(entry);
        planShortage(entry, "supply:HP_POTION:901");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));
        assertEquals(DEAD_MANS_GORGE, execution.supplierMapId());

        standsOn[0] = BENT_TREE;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 30_000L));
        dieAndRespawn(entry, agent, HAUNTED_HOUSE);
        clearInvocations(gateway);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 36_000L));
        assertFalse(execution.isActive());

        // At 50 HP it stays in the hall.
        planShortage(entry, "supply:HP_POTION:901b");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 40_000L));
        assertFalse(execution.isActive());
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());

        // Back over the critical line, after the held start's quiet period, it crosses again.
        hp[0] = 900;
        long later = 40_000L + config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy.FAILED_PROCUREMENT_COOLDOWN_MS");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, later));
        assertEquals(DEAD_MANS_GORGE, execution.supplierMapId());
        verify(gateway).travelTo(entry, agent, DEAD_MANS_GORGE, later);
    }

    @Test
    void aTownWhoseShopIsNextDoorStillSendsItShoppingAtRespawnHp() {
        Character agent = marauder();
        standsOn[0] = HENESYS;
        hp[0] = RESPAWN_HP;
        AgentRuntimeEntry entry = entry(agent);
        AgentSupplyProcurementState execution = execution(entry);
        planShortage(entry, "supply:HP_POTION:534");

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));

        assertTrue(execution.isActive());
        assertEquals(HENESYS_SHOP, execution.supplierMapId());
        verify(gateway).travelTo(entry, agent, HENESYS_SHOP, 1_000L);
    }

    @Test
    void backOverTheCriticalLineItSetsOffFromSleepywoodAgain() {
        Character agent = marauder();
        standsOn[0] = SLEEPYWOOD;
        hp[0] = MAX_HP / 2;
        AgentRuntimeEntry entry = entry(agent);
        AgentSupplyProcurementState execution = execution(entry);
        planShortage(entry, "supply:HP_POTION:535");

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));

        // From Sleepywood the nearest store is Henesys's, through the forest's monster maps: at half its HP it goes.
        assertTrue(execution.isActive());
        assertEquals(HENESYS_SHOP, execution.supplierMapId());
        verify(gateway).travelTo(entry, agent, HENESYS_SHOP, 1_000L);
    }

    private void dieAndRespawn(AgentRuntimeEntry entry, Character agent, int returnMapId) {
        hp[0] = 0;
        AgentCombatDeathRuntime.enterDeadState(entry, agent, false);
        // AgentDeathTickService.respawnAtNearestTown: the return map of the map it died on, at the respawn HP
        AgentDeathStateRuntime.clear(entry);
        standsOn[0] = returnMapId;
        hp[0] = RESPAWN_HP;
    }

    private Character marauder() {
        return citizen(532, "SpearArcher", Job.MARAUDER, 70, MAX_HP);
    }

    private Character citizen(int id, String name, Job job, int level, int maxHp) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getMapId()).thenAnswer(call -> standsOn[0]);
        when(agent.getMap()).thenAnswer(call -> map(standsOn[0]));
        when(agent.getHp()).thenAnswer(call -> hp[0]);
        when(agent.isAlive()).thenAnswer(call -> hp[0] > 0);
        when(agent.getCurrentMaxHp()).thenReturn(maxHp);
        when(agent.getMp()).thenReturn(3_000);
        when(agent.getCurrentMaxMp()).thenReturn(3_000);
        when(agent.getJob()).thenReturn(job);
        when(agent.getLevel()).thenReturn(level);
        when(agent.getMeso()).thenReturn(1_000_000);
        when(agent.getChair()).thenReturn(-1);
        return agent;
    }

    /**
     * Any map the test did not name spawns monsters, as every map on the way from Sleepywood to the store does. The
     * Sleepywood Hotel is not named either, so the sauna's ride is never in reach here (AgentSleepywoodSaunaTripTest).
     */
    private MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> map(id, true));
    }

    private static MapleMap map(int mapId, boolean monsters) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getMonsterSpawn()).thenReturn(monsters ? List.of(mock(SpawnPoint.class)) : List.of());
        return map;
    }

    private static AgentRuntimeEntry entry(Character agent) {
        return entry(agent, "pirate-knuckle-standard-v1");
    }

    private static AgentRuntimeEntry entry(Character agent, String bundleId) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find(bundleId).orElseThrow());
        return entry;
    }

    private static void planShortage(AgentRuntimeEntry entry, String requestId) {
        entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY).update(
                new AgentSupplyNeed(AgentResourceCategory.HP_POTION, 0, 50, AgentSupplyUrgency.EMPTY, "", 1L),
                new AgentProcurementRequest(requestId, AgentResourceCategory.HP_POTION, 50, Long.MAX_VALUE,
                        List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE));
    }

    private static AgentSupplyProcurementState execution(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }
}

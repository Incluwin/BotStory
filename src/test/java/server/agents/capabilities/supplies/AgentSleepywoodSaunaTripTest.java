package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.shop.AgentShopWorkflowPhase;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.MapGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.ShopGateway;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.SpawnPoint;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sleepywood's sauna as a supply trip's potion stop. Live 2026-11-01 the citizen SpearArcher, a Lv70 Marauder, respawned
 * in Sleepywood (the return map of every Dungeon map) at 50 of 5,111 HP with no potions; every walk to a shop crosses
 * the Ant Tunnel or the forest, so the hostile-map gate held it there for the 38 minutes of 1 HP/s regen it took to get
 * over CRITICAL_HP_PERCENT. A player there walks into the Sleepywood Hotel next door, pays the Receptionist (1061100)
 * 499 mesos for the Regular Sauna (105040401) and buys potions from Mr. Sweatbottom (1061002). The walks and the ride
 * here run through the real router (AgentVictoriaRouteRuntime.travelStatus) on a gateway whose portals take the Agent
 * where they lead; Sleepywood, the hotel and the sauna spawn no monsters, every other map does.
 */
class AgentSleepywoodSaunaTripTest {
    private static final int SLEEPYWOOD = 105040300;
    private static final int HOTEL = 105040400;
    private static final int SAUNA = 105040401;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int COLD_CRADLE = 105090311;
    private static final int RECEPTIONIST = 1061100;
    private static final int SWEATBOTTOM = 1061002;
    private static final int FARE = 499;
    private static final int RED_POTION = 2000000;
    private static final int RED_PRICE = 50;
    private static final int MAX_HP = 5_111;
    private static final int RESPAWN_HP = 50;
    private static final int LV70_RESERVE = 500 + 70 * 50; // WALLET_RESERVE_BASE + level * WALLET_RESERVE_PER_LEVEL
    private static final Point HERE = new Point(100, 200);

    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final List<Integer> entered = new ArrayList<>();
    private final List<Long> rideBudgets = new ArrayList<>();
    private final List<Integer> shopVisits = new ArrayList<>();
    private int standsOn = SLEEPYWOOD;
    private int hp = RESPAWN_HP;
    private int purse = 20_000;
    private Character agent;
    private AgentRuntimeEntry entry;
    private PrimitiveCapabilityGateway gateway;
    private MockedStatic<AgentShopGatewayRuntime> shopData;
    private MockedStatic<AgentShopService> shops;
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gatewayRuntime;
    private MockedStatic<AgentMapGatewayRuntime> mapRuntime;
    private MockedStatic<AgentClientGatewayRuntime> clientRuntime;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;

    @BeforeEach
    void sleepywood() {
        for (int safe : new int[]{SLEEPYWOOD, HOTEL, SAUNA}) {
            maps.put(safe, map(safe, false));
        }
        agent = marauder();
        entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find("pirate-knuckle-standard-v1").orElseThrow());

        gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong())).thenAnswer(call -> outcome(
                AgentVictoriaRouteRuntime.travelStatus(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), gateway, call.getArgument(3))));
        when(gateway.rideTo(any(), any(), anyInt(), anyLong(), anyLong())).thenAnswer(call -> {
            rideBudgets.add(call.getArgument(4));
            return outcome(AgentVictoriaRouteRuntime.travelStatus(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), gateway, call.getArgument(3),
                    new AgentVictoriaRouteRuntime.RideBudget(call.getArgument(4))));
        });
        when(gateway.grounded(any())).thenReturn(true);
        when(gateway.npcPosition(any(), anyInt())).thenReturn(HERE); // every NPC stands beside it
        when(gateway.portalPosition(any(), anyInt())).thenReturn(HERE); // and every portal
        // a portal's id names the map it leads to, so going through it lands there
        when(gateway.directPortalIdTo(any(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(gateway.enterPortal(any(), anyInt())).thenAnswer(call -> {
            enter(call.getArgument(1));
            return true;
        });
        // the Receptionist's regular sauna, as 1061100.js runs it: next, regular, yes; 499 mesos and in
        when(gateway.runNpcScript(agent, RECEPTIONIST, 0, 0)).thenAnswer(call -> {
            if (purse < FARE) {
                return true; // "you don't have enough mesos": the conversation ends where it was
            }
            purse -= FARE;
            enter(SAUNA);
            return true;
        });
        gatewayRuntime = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gatewayRuntime.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);

        MapGateway mapGateway = mock(MapGateway.class);
        when(mapGateway.resolveMap(anyInt(), anyInt(), anyInt())).thenAnswer(call -> map(call.getArgument(2)));
        mapRuntime = mockStatic(AgentMapGatewayRuntime.class);
        mapRuntime.when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        clientRuntime = mockStatic(AgentClientGatewayRuntime.class);
        clientRuntime.when(AgentClientGatewayRuntime::clients).thenReturn(mock(AgentClientGateway.class));
        shopData = mockStatic(AgentShopGatewayRuntime.class);
        shopData.when(AgentShopGatewayRuntime::shop).thenReturn(mock(ShopGateway.class));

        shops = mockStatic(AgentShopService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        shops.when(() -> AgentShopService.ordinaryPotionPrice(any(), any(), anyBoolean())).thenReturn(RED_PRICE);
        // the counter: the visit buys a hundred Red Potions, keeping the wallet reserve, and ends
        shops.when(() -> AgentShopService.requestVisitAtNpc(any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
            int npcId = call.getArgument(2);
            int reserve = call.getArgument(3);
            if (call.getArgument(0) == null || npcId != SWEATBOTTOM || standsOn != SAUNA) {
                return false;
            }
            shopVisits.add(npcId);
            int bought = Math.min(100, Math.max(0, purse - reserve) / RED_PRICE);
            purse -= bought * RED_PRICE;
            carry(bought);
            AgentRuntimeEntry visitor = call.getArgument(0);
            AgentShopStateRuntime.startShopVisit(visitor, HERE, HERE, 0, 100L);
            AgentShopStateRuntime.workflow(visitor).transition(AgentShopWorkflowPhase.TRANSACTING, "buying", 120L);
            AgentShopStateRuntime.notePotionsRestoredAtCounter(visitor, bought > 0, bought > 0);
            AgentShopStateRuntime.completeWorkflow(visitor, "bought", 150L);
            AgentShopStateRuntime.clearShopState(visitor);
            return true;
        });
        classification = mockStatic(AgentUseItemClassificationPolicy.class);
        StatEffect red = mock(StatEffect.class);
        when(red.getHp()).thenReturn((short) 50);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(call -> call.getArgument(0) != null);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(call -> (int) call.getArgument(0) == RED_POTION ? red : null);
        carry(0);
    }

    @AfterEach
    void close() {
        classification.close();
        shops.close();
        shopData.close();
        clientRuntime.close();
        mapRuntime.close();
        gatewayRuntime.close();
    }

    @Test
    void atRespawnHpWithNoPotionsItRidesIntoTheSaunaBuysPotionsAndWalksBackOut() {
        AgentSupplyProcurementState execution = execution();
        planShortage("supply:HP_POTION:532");

        long now = 1_000L;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, now));
        assertEquals(SAUNA, execution.supplierMapId(), "the sauna, not Ant Tunnel Park's store down the tunnel");
        assertEquals(SWEATBOTTOM, execution.supplierNpcId());
        assertEquals(List.of(HOTEL), entered, "it walked into the hotel next door");

        boolean underway = true;
        for (int tick = 0; tick < 60 && underway; tick++) {
            now += 2_000L;
            underway = AgentSupplyProcurementRuntime.tick(entry, agent, now);
        }

        assertFalse(underway, "the trip is over");
        assertFalse(execution.isActive());
        assertNull(AgentObjectiveKernel.active(entry));
        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution.lastOutcome().status());
        assertEquals(List.of(HOTEL, SAUNA, HOTEL, SLEEPYWOOD), entered,
                "into the hotel, the Receptionist's ride into the sauna, and out the way it came");
        verify(gateway).runNpcScript(agent, RECEPTIONIST, 0, 0);
        assertEquals(List.of(SWEATBOTTOM), shopVisits);
        assertEquals(100, AgentSupplyInventorySnapshot.quantity(agent, AgentResourceCategory.HP_POTION));
        assertEquals(20_000 - FARE - 100 * RED_PRICE, purse);
    }

    @Test
    void theRideNeverSpendsTheWalletReserve() {
        planShortage("supply:HP_POTION:532");

        AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L);
        AgentSupplyProcurementRuntime.tick(entry, agent, 3_000L);
        AgentSupplyProcurementRuntime.tick(entry, agent, 5_000L);

        assertFalse(rideBudgets.isEmpty());
        assertEquals(20_000L - LV70_RESERVE, (long) rideBudgets.get(0), "the purse less the Lv70 reserve of 4,000");
    }

    @Test
    void withTooLittleOverItsWalletReserveForTheFareItWaitsInSleepywoodAsBefore() {
        purse = LV70_RESERVE + FARE; // the fare, but not the fare and one potion, over its reserve
        AgentSupplyProcurementState execution = execution();

        for (long at = 1_000L; at <= 1_201_000L; at += 30_000L) {
            hp = Math.min(MAX_HP * 44 / 100, RESPAWN_HP + (int) (at / 1_000L)); // 1 HP/s, still under 45%
            planShortage("supply:HP_POTION:532:" + at);
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, at));
            assertFalse(execution.isActive());
        }

        assertEquals(List.of(), entered, "it never leaves Sleepywood");
        verify(gateway, never()).rideTo(any(), any(), anyInt(), anyLong(), anyLong());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
        assertEquals(LV70_RESERVE + FARE, purse);
    }

    @Test
    void aWalkThatComesIntoSleepywoodAtCriticalHpTakesTheSaunaInsteadOfTheTunnel() {
        // SpearArcher's 11-01 walk from Cold Cradle toward Ant Tunnel Park's store, arriving in Sleepywood hurt
        AgentSupplyProcurementState execution = execution();
        String requestId = "supply:HP_POTION:532";
        planShortage(requestId);
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000, Long.MAX_VALUE, 2,
                AgentObjectiveSource.RECOVERY_POLICY, "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, 5L);
        execution.start(requestId, maintenance.objectiveId(), AgentResourceCategory.HP_POTION, ANT_TUNNEL_PARK,
                1061001, COLD_CRADLE, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, 0, purse);
        hp = 300;

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));

        assertTrue(execution.isActive());
        assertFalse(execution.abandoned());
        assertEquals(SAUNA, execution.supplierMapId());
        assertEquals(List.of(HOTEL), entered);
    }

    @Test
    void overTheCriticalLineItWalksToItsShopAsBeforeAndNeverRides() {
        hp = MAX_HP / 2;
        AgentSupplyProcurementState execution = execution();
        planShortage("supply:HP_POTION:532");

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));

        assertTrue(execution.isActive());
        assertFalse(execution.supplierMapId() == SAUNA);
        verify(gateway, never()).rideTo(any(), any(), anyInt(), anyLong(), anyLong());
        verify(gateway).travelTo(entry, agent, execution.supplierMapId(), 1_000L);
    }

    private void enter(int mapId) {
        standsOn = mapId;
        entered.add(mapId);
    }

    private Character marauder() {
        Character marauder = mock(Character.class);
        when(marauder.getId()).thenReturn(532);
        when(marauder.getName()).thenReturn("SpearArcher");
        when(marauder.getMapId()).thenAnswer(call -> standsOn);
        when(marauder.getMap()).thenAnswer(call -> map(standsOn));
        when(marauder.getHp()).thenAnswer(call -> hp);
        when(marauder.isAlive()).thenAnswer(call -> hp > 0);
        when(marauder.getCurrentMaxHp()).thenReturn(MAX_HP);
        when(marauder.getMp()).thenReturn(3_000);
        when(marauder.getCurrentMaxMp()).thenReturn(3_000);
        when(marauder.getJob()).thenReturn(Job.MARAUDER);
        when(marauder.getLevel()).thenReturn(70);
        when(marauder.getMeso()).thenAnswer(call -> purse);
        when(marauder.getChair()).thenReturn(-1);
        when(marauder.getPosition()).thenReturn(HERE);
        return marauder;
    }

    /** Its Use tab: {@code quantity} Red Potions, or nothing. */
    private void carry(int quantity) {
        Inventory use = new Inventory(agent, InventoryType.USE, (byte) 24);
        if (quantity > 0) {
            use.addItem(Items.itemWithQuantity(RED_POTION, quantity));
        }
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
    }

    private MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> map(id, true));
    }

    private static MapleMap map(int mapId, boolean monsters) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getMonsterSpawn()).thenReturn(monsters ? List.of(mock(SpawnPoint.class)) : List.of());
        return map;
    }

    private static AgentRouteOutcome outcome(AgentVictoriaRouteRuntime.TravelOutcome travel) {
        return new AgentRouteOutcome(AgentRouteStatus.valueOf(travel.status().name()), travel.sourceMapId(),
                travel.nextMapId(), travel.destinationMapId(), travel.edgeBlocked());
    }

    private void planShortage(String requestId) {
        entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY).update(
                new AgentSupplyNeed(AgentResourceCategory.HP_POTION, 0, 50, AgentSupplyUrgency.EMPTY, "", 1L),
                new AgentProcurementRequest(requestId, AgentResourceCategory.HP_POTION, 50, Long.MAX_VALUE,
                        List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE));
    }

    private AgentSupplyProcurementState execution() {
        return entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }
}

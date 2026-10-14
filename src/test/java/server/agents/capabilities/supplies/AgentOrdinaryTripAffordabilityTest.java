package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.ShopItem;
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
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.ShopGateway;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An ordinary potion trip (not a readiness duty's, which AgentSupplyReadinessRuntime prices first) whose purse less its
 * wallet reserve cannot pay for one potion. Live 2026-10-13, SilentSin (Lv36 Warrior, 1,600-1,675 mesos under a 2,300
 * reserve, on a bench "farm meso" slice at 101020004) walked to Ellinia's potion shop (NPC 1031100) every 8-10 minutes
 * for MP potions it could not buy ("MP_POTION 2 -> 2 (reserve 60/5556), potions hp 84 mp 2, mesos 1675 -> 1675"). Each
 * visit ended NO_PROGRESS ("shop completed without increasing the required supply"), only a five-minute cooldown held
 * the next one back, and each bench slice began with a fresh session and set off again, so it never hunted. Now it
 * does not set off while its purse less the reserve cannot pay for one potion as its visit buys them, and a visit that
 * could not is INSUFFICIENT_MESO. The shop's stock and the potions' strengths are stubs; the real shop service picks.
 */
class AgentOrdinaryTripAffordabilityTest {
    private static final int CAMP = 101_020_004;
    private static final int ELLINIA_POTION_SHOP = 101_000_002;
    private static final int SHOP_NPC = 1_031_100;
    private static final int SILENT_SIN = 543;
    private static final int RED = 2_000_000; // 50 mesos, 50 HP
    private static final int WHITE = 2_000_002; // 320 mesos, 300 HP
    private static final int BLUE = 2_000_003; // 192 mesos, 100 MP
    private static final int RED_PRICE = 50;
    private static final int WHITE_PRICE = 320;
    private static final int BLUE_PRICE = 192;

    private final Map<Integer, int[]> potions = new HashMap<>();
    private final Map<Integer, Integer> purses = new HashMap<>();
    private final Map<Integer, StatEffect> effects = new HashMap<>();
    /** The party asks and shop visits, in order. */
    private final List<String> calls = new ArrayList<>();
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final ShopGateway shops = mock(ShopGateway.class);
    private final Shop shop = mock(Shop.class);
    private MockedStatic<AgentPotionService> potionService;
    private MockedStatic<AgentShopService> shopService;
    private MockedStatic<AgentShopGatewayRuntime> shopGateway;
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;

    @BeforeEach
    void stubTheWorld() {
        effects.put(RED, effect(50, 0));
        effects.put(WHITE, effect(300, 0));
        effects.put(BLUE, effect(0, 100));
        classification = mockStatic(AgentUseItemClassificationPolicy.class, CALLS_REAL_METHODS);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> effects.get((int) inv.getArgument(0)));
        potionService = mockStatic(AgentPotionService.class);
        potionService.when(() -> AgentPotionService.countPotions(any(Character.class)))
                .thenAnswer(inv -> potions.get(((Character) inv.getArgument(0)).getId()).clone());
        potionService.when(() -> AgentPotionService.requestPotShare(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> calls.add("party ask " + ((Character) inv.getArgument(1)).getName()));
        // the real shop service picks and prices the visit's potions; the visit itself is a stub
        shopService = mockStatic(AgentShopService.class, CALLS_REAL_METHODS);
        shopService.when(() -> AgentShopService.onMapChange(any(), any(), any())).thenAnswer(inv -> null);
        shopService.when(() -> AgentShopService.requestVisitAtNpc(any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> calls.add("shop visit " + inv.getArgument(2)));
        shopService.when(() -> AgentShopService.tickShopVisit(any(), any(), any())).thenReturn(true);
        sells(new ShopItem((short) 1, WHITE, WHITE_PRICE, 0), new ShopItem((short) 1, BLUE, BLUE_PRICE, 0));
        when(shops.findForNpc(SHOP_NPC)).thenReturn(shop);
        shopGateway = mockStatic(AgentShopGatewayRuntime.class);
        shopGateway.when(AgentShopGatewayRuntime::shop).thenReturn(shops);
        primitives = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong())).thenAnswer(inv -> {
            Character agent = inv.getArgument(1);
            int destination = inv.getArgument(2);
            return new AgentRouteOutcome(agent.getMapId() == destination ? AgentRouteStatus.ARRIVED
                    : AgentRouteStatus.MOVING, agent.getMapId(), destination, destination, false);
        });
    }

    @AfterEach
    void unstub() {
        AgentWalletReserve.install(null);
        primitives.close();
        shopGateway.close();
        shopService.close();
        potionService.close();
        classification.close();
    }

    @Test
    void aPurseWithinItsWalletReserveDoesNotWalkToTheShopUntilItPaysForOnePotion() {
        int reserve = AgentSupplyRecoveryPolicy.minimumWalletReserve(36);
        Character silentSin = silentSin(1_675);
        AgentRuntimeEntry entry = entry(silentSin);
        planCritical(entry, AgentResourceCategory.MP_POTION, 2);

        for (long now : new long[]{1_000L, 1_250L, 60_000L, 400_000L}) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, now));
        }

        assertFalse(execution(entry).isActive(), "1,675 mesos under a " + reserve + " reserve buy no potion");
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
        verify(shops, never()).findForNpc(anyInt()); // within its reserve no price is looked up
        assertEquals(List.of("party ask SilentSin"), calls, "once, as each trip it gave up asked");

        purses.put(SILENT_SIN, reserve + BLUE_PRICE - 1); // over its reserve, one meso short of a Blue Potion
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 401_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 402_000L));
        assertFalse(execution(entry).isActive());
        verify(shops, times(1)).findForNpc(SHOP_NPC); // looked up once, then held to that price

        purses.put(SILENT_SIN, reserve + BLUE_PRICE);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silentSin, 403_000L));
        assertTrue(execution(entry).isActive(), "it can pay for one: it sets off as before");
        assertEquals(ELLINIA_POTION_SHOP, execution(entry).supplierMapId());
        assertEquals(SHOP_NPC, execution(entry).supplierNpcId());
        assertEquals(List.of("party ask SilentSin"), calls, "no second ask");
    }

    @Test
    void aPurseThatPaysForARedPotionButNotTheWhitePotionItsVisitBuysStaysPut() {
        sells(new ShopItem((short) 1, RED, RED_PRICE, 0), new ShopItem((short) 1, WHITE, WHITE_PRICE, 0),
                new ShopItem((short) 1, BLUE, BLUE_PRICE, 0));
        int reserve = AgentSupplyRecoveryPolicy.minimumWalletReserve(36);
        Character silentSin = silentSin(reserve + 100);
        potions.put(SILENT_SIN, new int[]{2, 84});
        AgentRuntimeEntry entry = entry(silentSin);
        planCritical(entry, AgentResourceCategory.HP_POTION, 2);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 1_000L));
        assertFalse(execution(entry).isActive(), "its visit's critical pass buys White Potions, 320 each");

        purses.put(SILENT_SIN, reserve + WHITE_PRICE);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silentSin, 2_000L));
        assertTrue(execution(entry).isActive());
    }

    @Test
    void aVisitThatCouldNotPayForOnePotionIsInsufficientMesoAndHoldsTheNextTripUntilThePurseCovers() {
        int reserve = AgentSupplyRecoveryPolicy.minimumWalletReserve(36);
        Character silentSin = silentSin(1_675);
        stand(silentSin, ELLINIA_POTION_SHOP);
        AgentRuntimeEntry entry = entry(silentSin);
        planCritical(entry, AgentResourceCategory.MP_POTION, 2);
        startTrip(entry, AgentResourceCategory.MP_POTION, 2, 1_675); // it set off before it could tell

        shopCompletes(entry); // MP_POTION 2 -> 2, mesos 1675 -> 1675
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silentSin, 200L));

        AgentSupplyProcurementOutcome outcome = execution(entry).lastOutcome();
        assertEquals(AgentSupplyProcurementOutcome.Status.INSUFFICIENT_MESO, outcome.status(), outcome.reason());
        assertTrue(execution(entry).abandoned());
        stand(silentSin, CAMP); // it walked back
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 300L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of("party ask SilentSin"), calls);

        long cooled = 300L + AgentSupplyRecoveryPolicy.failedProcurementCooldownMs();
        planCritical(entry, AgentResourceCategory.MP_POTION, 2); // the potion check plans it again
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, cooled));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, cooled + 60_000L));
        assertFalse(execution(entry).isActive(), "its cooldown is over, its want of mesos is not");

        assertEquals(List.of("party ask SilentSin"), calls, "the trip it gave up asked already");

        purses.put(SILENT_SIN, reserve + BLUE_PRICE);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silentSin, cooled + 61_000L));
        assertTrue(execution(entry).isActive());
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, execution(entry).phase());
    }

    @Test
    void aCompanionWithAnEmptyPurseAsksItsPartyOnceAndNeverWalksToTheShop() {
        AgentWalletReserve.install(id -> true); // out with its owner: no journey reserve
        Character silentSin = silentSin(0);
        AgentRuntimeEntry entry = entry(silentSin);
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        AgentModeStateRuntime.setFollowing(entry, true);
        planCritical(entry, AgentResourceCategory.MP_POTION, 2);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 1_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 1_000L + partyWaitMs()));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silentSin, 2_000L + partyWaitMs()));

        assertFalse(execution(entry).isActive(), "nobody shared, and its empty purse buys nothing at the shop");
        assertEquals(List.of("party ask SilentSin"), calls);
    }

    @Test
    void aSelfSustainingAgentStillSetsOffAndEarnsWhatItLacksAfterTheVisit() {
        int reserve = AgentSupplyRecoveryPolicy.minimumWalletReserve(36);
        Character silentSin = silentSin(1_675);
        AgentRuntimeEntry entry = entry(silentSin);
        entry.capabilityStates().require(AgentResourceAutonomyState.STATE_KEY).requireSelfSustaining();
        planCritical(entry, AgentResourceCategory.MP_POTION, 2);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silentSin, 1_000L));
        assertTrue(execution(entry).isActive(), "a journey participant earns its supplies through recovery");

        stand(silentSin, ELLINIA_POTION_SHOP);
        execution(entry).markShopRequested();
        shopCompletes(entry);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silentSin, 2_000L));

        assertEquals(AgentSupplyProcurementOutcome.Status.INSUFFICIENT_MESO,
                execution(entry).lastOutcome().status(), execution(entry).lastOutcome().reason());
        assertEquals(AgentSupplyProcurementState.Phase.RESTING, execution(entry).phase());
        assertTrue(execution(entry).recoveryTargetMeso() > reserve, "it hunts for more than its reserve");
    }

    // ---- fixture ---------------------------------------------------------------------------

    /** SilentSin as the log shows it: Lv36 Warrior on its camp with 84 HP potions and 2 MP potions. */
    private Character silentSin(int mesos) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(SILENT_SIN);
        when(agent.getName()).thenReturn("SilentSin");
        when(agent.getJob()).thenReturn(Job.WARRIOR);
        when(agent.getLevel()).thenReturn(36);
        when(agent.getHp()).thenReturn(2_736);
        when(agent.getMp()).thenReturn(384);
        when(agent.getCurrentMaxHp()).thenReturn(2_736);
        when(agent.getCurrentMaxMp()).thenReturn(384);
        when(agent.getChair()).thenReturn(-1);
        when(agent.getMeso()).thenAnswer(inv -> purses.get(SILENT_SIN));
        Inventory use = mock(Inventory.class);
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        stand(agent, CAMP);
        potions.put(SILENT_SIN, new int[]{84, 2});
        purses.put(SILENT_SIN, mesos);
        return agent;
    }

    private static void stand(Character agent, int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
    }

    private void sells(ShopItem... items) {
        when(shop.getItems()).thenReturn(List.of(items));
    }

    private static AgentRuntimeEntry entry(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find("warrior-standard-v1").orElseThrow());
        return entry;
    }

    /** A critical request of {@code category} as the potion check plans it at {@code count}. */
    private static AgentResourcePlanningState planCritical(AgentRuntimeEntry entry, AgentResourceCategory category,
                                                           int count) {
        AgentResourcePlanningState planning = entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY);
        int target = Math.max(AgentRuntimeConfig.cfg.POT_LOW_WARN * 2, count);
        planning.update(new AgentSupplyNeed(category, count, target, AgentSupplyUrgency.CRITICAL, "", 1L),
                new AgentProcurementRequest("supply:" + category + ":" + entry.bot().getId(), category,
                        target - count, Long.MAX_VALUE, List.of(AgentProcurementMethod.NPC_SHOP),
                        AgentSupplyUrgency.CRITICAL, "", Long.MAX_VALUE));
        return planning;
    }

    /** A trip from its camp, at the shop's counter. */
    private static void startTrip(AgentRuntimeEntry entry, AgentResourceCategory category, int before, int mesos) {
        String requestId = "supply:" + category + ":" + entry.bot().getId();
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000, Long.MAX_VALUE, 2,
                AgentObjectiveSource.RECOVERY_POLICY, "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, 5L);
        AgentSupplyProcurementState execution = execution(entry);
        execution.start(requestId, maintenance.objectiveId(), category, ELLINIA_POTION_SHOP, SHOP_NPC,
                CAMP, AgentSupplyProcurementState.Phase.SHOPPING, before, mesos);
        execution.markShopRequested();
    }

    /** The shop visit ran to its end without buying anything. */
    private static void shopCompletes(AgentRuntimeEntry entry) {
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 100L);
        AgentShopStateRuntime.workflow(entry).transition(AgentShopWorkflowPhase.TRANSACTING, "buying", 120L);
        AgentShopStateRuntime.completeWorkflow(entry, "bought", 150L);
        AgentShopStateRuntime.clearShopState(entry);
    }

    private static AgentSupplyProcurementState execution(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }

    private static long partyWaitMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy.PARTY_SUPPLY_WAIT_MS");
    }

    private static StatEffect effect(int hp, int mp) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        return effect;
    }
}

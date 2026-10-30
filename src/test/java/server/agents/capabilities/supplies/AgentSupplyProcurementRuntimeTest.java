package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.ShopFactory;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.progression.AgentCareerBuildBundle;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentSupplyProcurementRuntimeTest {
    @Test
    void automaticProcurementRequiresAnExplicitSelfSustainingSession() {
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);

        assertFalse(AgentSupplyProcurementRuntime.tickIfSelfSustaining(
                entry, agent, 100L));

        entry.capabilityStates().require(AgentResourceAutonomyState.STATE_KEY)
                .requireSelfSustaining();
        assertFalse(AgentSupplyProcurementRuntime.tickIfSelfSustaining(
                entry, agent, 200L));
    }

    @Test
    void activePotionMaintenanceReleasesAFullHealthBeginnerPlan() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(92);
        when(agent.getJob()).thenReturn(Job.BEGINNER);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentObjectiveDefinition plan = new AgentObjectiveDefinition(
                "maple:plan:92", "plan.maple-island", 80, Long.MAX_VALUE, 5,
                AgentObjectiveSource.QUEST_PLAN, "maple-v1", "maple:92");
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:HP_POTION:92", "maintenance.resupply", 1_000,
                10_000L, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", plan.objectiveId());
        AgentObjectiveKernel.start(entry, plan, 10L);
        AgentObjectiveKernel.suspendFor(entry, maintenance, "HP potions empty", 20L);
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start(
                "supply:HP_POTION:92", maintenance.objectiveId(),
                AgentResourceCategory.HP_POTION, 10000, 0, 10000,
                AgentSupplyProcurementState.Phase.SHOPPING);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 30L));
        assertEquals(plan, AgentObjectiveKernel.active(entry));
        assertFalse(entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY).isActive());
    }

    @Test
    void restoredPotionMaintenanceAlsoReleasesAFullHealthBeginnerPlan() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(93);
        when(agent.getJob()).thenReturn(Job.BEGINNER);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentObjectiveDefinition plan = new AgentObjectiveDefinition(
                "maple:plan:93", "plan.maple-island", 80, Long.MAX_VALUE, 5,
                AgentObjectiveSource.QUEST_PLAN, "maple-v1", "maple:93");
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:MP_POTION:93", "maintenance.resupply", 1_000,
                10_000L, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", plan.objectiveId());
        AgentObjectiveKernel.start(entry, plan, 10L);
        AgentObjectiveKernel.suspendFor(entry, maintenance, "MP potions empty", 20L);

        assertFalse(AgentSupplyProcurementRuntime.tickIfSelfSustaining(
                entry, agent, 30L));
        assertEquals(plan, AgentObjectiveKernel.active(entry));
    }

    @Test
    void combatIntentSurvivesLowPotionShopInterruptionAndReturn() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(90);
        when(agent.getMapId()).thenReturn(104000000);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentCareerBuildBundle bundle = AgentCareerBuildBundleRepository.defaultRepository()
                .find("warrior-standard-v1").orElseThrow();
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(bundle);
        AgentObjectiveDefinition combat = new AgentObjectiveDefinition(
                "combat:quest:90", "combat.quest", 80, Long.MAX_VALUE, 5,
                AgentObjectiveSource.QUEST_PLAN, "combat-v1", "quest:90");
        AgentObjectiveKernel.start(entry, combat, 10L);

        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentSupplyNeed need = new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                0, 20, AgentSupplyUrgency.EMPTY, combat.objectiveId(), 100L);
        AgentProcurementRequest request = new AgentProcurementRequest(
                "supply:HP_POTION:90", AgentResourceCategory.HP_POTION, 20, 10_000L,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                combat.objectiveId(), 10_000L);
        planning.update(need, request);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);

        assertTrue(AgentSupplyProcurementRuntime.begin(entry, agent, request, execution, 20L));
        assertEquals("maintenance:resupply:" + request.requestId(),
                AgentObjectiveKernel.active(entry).objectiveId());
        assertEquals(combat, entry.capabilityStates()
                .require(server.agents.objectives.AgentObjectiveState.STATE_KEY)
                .suspendedSnapshot().get(0).objective());

        execution.markShopRequested();
        execution.markReturning();
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertEquals(combat, AgentObjectiveKernel.active(entry));
        assertNull(planning.procurement(AgentResourceCategory.HP_POTION));
    }

    @Test
    void refreshedSameCategoryRequestDoesNotStarveActiveReturnTrip() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(91);
        when(agent.getMapId()).thenReturn(101010101);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentObjectiveDefinition training = new AgentObjectiveDefinition(
                "victoria:training:91:30", "progression.victoria-training", 80,
                Long.MAX_VALUE, 5, AgentObjectiveSource.PROGRESSION_POLICY,
                "victoria-v1", "91:level-30");
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:HP_POTION:100", "maintenance.resupply", 1_000,
                10_000L, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", training.objectiveId());
        AgentObjectiveKernel.start(entry, training, 10L);
        AgentObjectiveKernel.suspendFor(entry, maintenance, "HP potions critical", 20L);

        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentSupplyNeed need = new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                0, 20, AgentSupplyUrgency.EMPTY, training.objectiveId(), 100L);
        AgentProcurementRequest request = new AgentProcurementRequest(
                "supply:HP_POTION:refreshed", AgentResourceCategory.HP_POTION, 20, 10_000L,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                training.objectiveId(), 10_000L);
        planning.update(need, request);
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start(
                "supply:HP_POTION:100", maintenance.objectiveId(), request.category(),
                101000002, 1032102, 101010101, AgentSupplyProcurementState.Phase.RETURNING);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertEquals(training, AgentObjectiveKernel.active(entry));
        assertNull(planning.procurement(AgentResourceCategory.HP_POTION));
    }

    @Test
    void blockedShopVisitIsRetriedOnceThenAbandonedSoThePlanResumes() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(92);
        when(agent.getName()).thenReturn("ProbeHero");
        when(agent.getMapId()).thenReturn(103000002);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentObjectiveDefinition follow = new AgentObjectiveDefinition(
                "follow:leader:92", "companion.follow", 80, Long.MAX_VALUE, 5,
                AgentObjectiveSource.QUEST_PLAN, "follow-v1", "leader:92");
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:HP_POTION:92", "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", follow.objectiveId());
        AgentObjectiveKernel.start(entry, follow, 10L);
        AgentObjectiveKernel.suspendFor(entry, maintenance, "HP potions critical", 20L);

        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentSupplyNeed need = new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                0, 20, AgentSupplyUrgency.EMPTY, follow.objectiveId(), 100L);
        AgentProcurementRequest request = new AgentProcurementRequest(
                "supply:HP_POTION:92", AgentResourceCategory.HP_POTION, 20, Long.MAX_VALUE,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                follow.objectiveId(), Long.MAX_VALUE);
        planning.update(need, request);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);
        execution.start(request.requestId(), maintenance.objectiveId(), request.category(),
                103000002, 1051002, 103000002, AgentSupplyProcurementState.Phase.SHOPPING);
        execution.markShopRequested();
        // The companion was following its leader before the resupply interrupted it.
        Character leader = mock(Character.class);
        AgentRelationshipRuntime.setFollowTarget(entry, leader);
        execution.rememberForegroundMode(true, false);

        // The shop workflow ends BLOCKED (what abortShop produces) and its visit state clears.
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 100L);
        AgentShopStateRuntime.blockWorkflow(entry, "cannot reach the shopkeeper", 150L);
        AgentShopStateRuntime.clearShopState(entry);

        // First failure: bounded retry, foreground stays suspended.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));
        assertEquals(AgentSupplyProcurementState.Phase.SHOPPING, execution.phase());
        assertEquals(1, execution.shopFailures());
        assertFalse(execution.shopRequested());
        assertFalse(execution.abandoned());
        assertEquals(maintenance, AgentObjectiveKernel.active(entry));

        // The retry waits out its delay before asking for the shop again.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 300L));
        assertFalse(execution.shopRequested());

        // Retry fires; without a live map the supplier is unavailable, which exhausts the budget.
        long retryAt = 200L + AgentSupplyRecoveryPolicy.shopRetryDelayMs();
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, retryAt));
        assertTrue(execution.abandoned());
        assertEquals(AgentSupplyProcurementState.Phase.RETURNING, execution.phase());
        assertTrue(execution.suppressed(AgentResourceCategory.HP_POTION, retryAt));
        assertFalse(execution.suppressed(AgentResourceCategory.HP_POTION,
                retryAt + AgentSupplyRecoveryPolicy.failedProcurementCooldownMs()));
        assertEquals(maintenance, AgentObjectiveKernel.active(entry));

        // Giving up cleared the movement mode but kept the leader as the follow target.
        assertEquals(leader, AgentRelationshipRuntime.followTarget(entry));

        // Already on the plan map: the maintenance finishes FAILED and the plan resumes.
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, retryAt + 1));
        assertFalse(execution.isActive());
        assertEquals(follow, AgentObjectiveKernel.active(entry));
        assertNull(planning.procurement(AgentResourceCategory.HP_POTION));
        assertTrue(entry.capabilityStates()
                .require(server.agents.objectives.AgentObjectiveState.STATE_KEY)
                .suspendedSnapshot().isEmpty());

        // A fresh critical request for the same category is ignored during the cooldown.
        planning.update(need, request);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, retryAt + 2));
        assertFalse(execution.isActive());
        assertEquals(follow, AgentObjectiveKernel.active(entry));
    }

    /**
     * Arriving on a shop map, onMapChange opens a visit at the shopkeeper and begin() adopts it. Live (2026-09-21 to
     * 2026-10-06) the adopted trip carried supplier NPC 0, so after the visit ended CANCELLED or BLOCKED the retry
     * asked for NPC 0 and 109 trips gave up with "selected supplier NPC is unavailable".
     */
    @Test
    void adoptedShopVisitThatEndsBlockedIsRetriedAtItsShopkeeper() {
        int shopMap = 100000102;
        int shopkeeper = 1011100;
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(shopkeeper);
        when(npc.getPosition()).thenReturn(new Point(-297, -79));
        when(npc.hasShop()).thenReturn(true);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(96);
        when(agent.getName()).thenReturn("Shopper");
        when(agent.getMapId()).thenReturn(shopMap);
        when(agent.getMap()).thenReturn(map);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentObjectiveDefinition training = new AgentObjectiveDefinition(
                "victoria:training:96:30", "progression.victoria-training", 80,
                Long.MAX_VALUE, 5, AgentObjectiveSource.PROGRESSION_POLICY,
                "victoria-v1", "96:level-30");
        AgentObjectiveKernel.start(entry, training, 10L);
        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentProcurementRequest request = new AgentProcurementRequest(
                "supply:HP_POTION:96", AgentResourceCategory.HP_POTION, 20, Long.MAX_VALUE,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                training.objectiveId(), Long.MAX_VALUE);
        planning.update(new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                0, 20, AgentSupplyUrgency.EMPTY, training.objectiveId(), 15L), request);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);

        try (MockedStatic<AgentShopService> shops = mockStatic(AgentShopService.class, CALLS_REAL_METHODS);
             MockedStatic<ShopFactory> shopData = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shopData.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(shopkeeper)).thenReturn(mock(Shop.class));
            // The opportunistic visit onMapChange opens at the shopkeeper it needs; the walk to the counter is not
            // under test.
            shops.when(() -> AgentShopService.onMapChange(any(), any(), any()))
                    .thenAnswer(call -> AgentShopService.requestVisitAtNpc(entry, agent, shopkeeper));
            shops.when(() -> AgentShopService.tickShopVisit(any(), any(), any())).thenReturn(true);
            shops.when(() -> AgentShopService.pendingVisitStocks(any(), any(), any())).thenReturn(true); // HP potions

            assertTrue(AgentSupplyProcurementRuntime.begin(entry, agent, request, execution, 20L));
            assertTrue(execution.shopRequested());
            assertEquals(shopkeeper, execution.supplierNpcId());

            // The adopted visit ends BLOCKED and its visit state clears.
            AgentShopStateRuntime.blockWorkflow(entry, "couldn't get to the shopkeeper", 150L);
            AgentShopStateRuntime.clearShopState(entry);
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));
            assertEquals(1, execution.shopFailures());

            // The retry asks the same shopkeeper again instead of giving the trip up.
            long retryAt = 200L + AgentSupplyRecoveryPolicy.shopRetryDelayMs();
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, retryAt));
            assertFalse(execution.abandoned(), execution.abandonedReason());
            assertTrue(execution.shopRequested());
            assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        }
    }

    @Test
    void aVisitItsMapOpenedAtAShopThatSellsNoneIsNotTheTripsSupplier() {
        // live 10-26..29: Gikoel and HaaTheMage, training on 101010102, began MP potion trips there; the visit
        // onMapChange had opened at El Moth 1032103 (a scroll seller: the stars it carried wanted a refill, which any
        // shop does) was adopted, bought no potion, and the trip was given up and taken again every six minutes
        int field = 101010102;
        int elMoth = 1032103;
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(elMoth);
        when(npc.getPosition()).thenReturn(new Point(-179, 1844));
        when(npc.hasShop()).thenReturn(true);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(97);
        when(agent.getName()).thenReturn("Gikoel");
        when(agent.getMapId()).thenReturn(field);
        when(agent.getMap()).thenReturn(map);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find("warrior-standard-v1").orElseThrow());
        AgentObjectiveDefinition training = new AgentObjectiveDefinition(
                "victoria:training:97:30", "progression.victoria-training", 80,
                Long.MAX_VALUE, 5, AgentObjectiveSource.PROGRESSION_POLICY,
                "victoria-v1", "97:level-30");
        AgentObjectiveKernel.start(entry, training, 10L);
        AgentProcurementRequest request = new AgentProcurementRequest(
                "supply:MP_POTION:97", AgentResourceCategory.MP_POTION, 20, Long.MAX_VALUE,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                training.objectiveId(), Long.MAX_VALUE);
        entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY).update(new AgentSupplyNeed(
                AgentResourceCategory.MP_POTION, 0, 20, AgentSupplyUrgency.EMPTY, training.objectiveId(), 15L), request);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);
        Shop scrolls = mock(Shop.class);
        server.ShopItem scroll = mock(server.ShopItem.class);
        when(scroll.getItemId()).thenReturn(2040000);
        when(scroll.getPrice()).thenReturn(35000);
        when(scrolls.getItems()).thenReturn(List.of(scroll));

        try (MockedStatic<AgentShopService> shops = mockStatic(AgentShopService.class, CALLS_REAL_METHODS);
             MockedStatic<ShopFactory> shopData = mockStatic(ShopFactory.class);
             MockedStatic<server.agents.capabilities.inventory.AgentUseItemClassificationPolicy> items =
                     mockStatic(server.agents.capabilities.inventory.AgentUseItemClassificationPolicy.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shopData.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(elMoth)).thenReturn(scrolls); // a scroll is no potion (its effect: none)
            shops.when(() -> AgentShopService.onMapChange(any(), any(), any()))
                    .thenAnswer(call -> AgentShopService.requestVisitAtNpc(entry, agent, elMoth));

            assertTrue(AgentSupplyProcurementRuntime.begin(entry, agent, request, execution, 20L));

            assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "the scroll seller's visit was let go");
            assertTrue(execution.supplierNpcId() != elMoth && execution.supplierMapId() != field,
                    () -> "supplier " + execution.supplierNpcId() + " on " + execution.supplierMapId());
            assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, execution.phase());
            assertFalse(execution.shopRequested());
        }
    }

    @Test
    void selfSustainingSessionsKeepTheStickyStallForInspection() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(93);
        when(agent.getName()).thenReturn("JourneyProbe");
        when(agent.getMapId()).thenReturn(103000002);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentResourceAutonomyState.STATE_KEY).requireSelfSustaining();
        AgentObjectiveDefinition training = new AgentObjectiveDefinition(
                "victoria:training:93:30", "progression.victoria-training", 80,
                Long.MAX_VALUE, 5, AgentObjectiveSource.PROGRESSION_POLICY,
                "victoria-v1", "93:level-30");
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:HP_POTION:93", "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", training.objectiveId());
        AgentObjectiveKernel.start(entry, training, 10L);
        AgentObjectiveKernel.suspendFor(entry, maintenance, "HP potions critical", 20L);

        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentProcurementRequest request = new AgentProcurementRequest(
                "supply:HP_POTION:93", AgentResourceCategory.HP_POTION, 20, Long.MAX_VALUE,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                training.objectiveId(), Long.MAX_VALUE);
        planning.update(new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                0, 20, AgentSupplyUrgency.EMPTY, training.objectiveId(), 100L), request);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);
        execution.start(request.requestId(), maintenance.objectiveId(), request.category(),
                103000002, 1051002, 103000002, AgentSupplyProcurementState.Phase.SHOPPING);

        // No live map: the supplier is unavailable immediately.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));
        assertEquals(AgentSupplyProcurementState.Phase.STALLED, execution.phase());
        assertFalse(execution.abandoned());
        assertEquals("selected supplier NPC is unavailable", execution.stalledReason());
        assertEquals(maintenance, AgentObjectiveKernel.active(entry));

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 300L));
        assertEquals(maintenance, AgentObjectiveKernel.active(entry));
    }

    @Test
    void requestResolvedDuringTheTripHomeStillFinishesTheReturnAsSuccess() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(94);
        when(agent.getName()).thenReturn("Restocked");
        when(agent.getMapId()).thenReturn(101010101);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentObjectiveDefinition training = new AgentObjectiveDefinition(
                "victoria:training:94:30", "progression.victoria-training", 80,
                Long.MAX_VALUE, 5, AgentObjectiveSource.PROGRESSION_POLICY,
                "victoria-v1", "94:level-30");
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:HP_POTION:94", "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", training.objectiveId());
        AgentObjectiveKernel.start(entry, training, 10L);
        AgentObjectiveKernel.suspendFor(entry, maintenance, "HP potions critical", 20L);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);
        execution.start("supply:HP_POTION:94", maintenance.objectiveId(),
                AgentResourceCategory.HP_POTION, 101000002, 1032102, 101010101,
                AgentSupplyProcurementState.Phase.RETURNING);
        // The potion check already observed a healthy reserve, so no request is planned.

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertFalse(execution.isActive());
        assertFalse(execution.abandoned());
        assertEquals(training, AgentObjectiveKernel.active(entry));
        assertFalse(execution.suppressed(AgentResourceCategory.HP_POTION, 200L));
    }

    @Test
    void resupplyWhoseObjectiveWasEndedElsewhereStillFreesTheNextResupply() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(97);
        when(agent.getName()).thenReturn("Wanderer");
        when(agent.getMapId()).thenReturn(102000000);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository()
                        .find("warrior-standard-v1").orElseThrow());
        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentSupplyNeed need = new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                0, 200, AgentSupplyUrgency.EMPTY, "", 10L);
        AgentProcurementRequest first = new AgentProcurementRequest(
                "supply:HP_POTION:10", AgentResourceCategory.HP_POTION, 200, Long.MAX_VALUE,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE);
        planning.update(need, first);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);

        // The trip starts and is then abandoned.
        assertTrue(AgentSupplyProcurementRuntime.begin(entry, agent, first, execution, 20L));
        // Something else ends the maintenance objective first (the relog reconciler used to).
        AgentObjectiveKernel.finishAndResume(entry, "maintenance:resupply:" + first.requestId(),
                server.agents.objectives.AgentObjectiveStatus.FAILED, "ended elsewhere", 25L);
        execution.markAbandoned("no portal route reaches the selected supplier");
        execution.markReturning();
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 30L));
        assertFalse(execution.isActive());

        // The next shortage starts a fresh resupply instead of being refused on every tick.
        AgentProcurementRequest second = new AgentProcurementRequest(
                "supply:HP_POTION:40", AgentResourceCategory.HP_POTION, 200, Long.MAX_VALUE,
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE);
        planning.update(need, second);
        assertTrue(AgentSupplyProcurementRuntime.begin(entry, agent, second, execution, 40L));
        assertEquals("maintenance:resupply:" + second.requestId(),
                AgentObjectiveKernel.active(entry).objectiveId());
    }

    @Test
    void refusedStartIsSuppressedInsteadOfRetriedEveryTick() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(98);
        when(agent.getName()).thenReturn("Unplanned");
        when(agent.getMapId()).thenReturn(250000000);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        planning.update(new AgentSupplyNeed(AgentResourceCategory.HP_POTION,
                        0, 200, AgentSupplyUrgency.EMPTY, "", 10L),
                new AgentProcurementRequest("supply:HP_POTION:98", AgentResourceCategory.HP_POTION,
                        200, Long.MAX_VALUE, List.of(AgentProcurementMethod.NPC_SHOP),
                        AgentSupplyUrgency.EMPTY, "", Long.MAX_VALUE));
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);

        // No career bundle and no shop here: begin() is refused.
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 20L));
        assertFalse(execution.isActive());
        assertTrue(execution.suppressed(AgentResourceCategory.HP_POTION, 21L));
        assertFalse(execution.suppressed(AgentResourceCategory.HP_POTION,
                20L + AgentSupplyRecoveryPolicy.failedProcurementCooldownMs()));
    }

    @Test
    void companionWithoutAPlanObjectiveResumesFollowingAfterTheReturnTrip() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(95);
        when(agent.getName()).thenReturn("Companion");
        when(agent.getMapId()).thenReturn(103000000);
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Character leader = mock(Character.class);
        AgentRelationshipRuntime.setFollowTarget(entry, leader);
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:supply:HP_POTION:95", "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", "maintenance:resupply:supply:HP_POTION:95");
        AgentObjectiveKernel.start(entry, maintenance, 10L);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);
        execution.start("supply:HP_POTION:95", maintenance.objectiveId(),
                AgentResourceCategory.HP_POTION, 103000002, 1051002, 103000000,
                AgentSupplyProcurementState.Phase.RETURNING);
        execution.rememberForegroundMode(true, false);
        assertFalse(AgentModeStateRuntime.following(entry));

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertFalse(execution.isActive());
        assertNull(AgentObjectiveKernel.active(entry));
        assertTrue(AgentModeStateRuntime.following(entry));
        assertEquals(leader, AgentRelationshipRuntime.followTarget(entry));
    }
}

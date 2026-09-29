package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.shop.AgentShopWorkflowPhase;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Supplier trips only go where the map-level portal router can get to, and companions stay
 * with (or go back to) their owner. The mocked Agents have no live map, so the real gateway
 * reports every portal as unavailable, which is how a portal that keeps refusing entry gets
 * blocked by the router on the live server.
 */
class AgentSupplyProcurementReachabilityTest {
    private static final int KERNING = 103000000;
    private static final int KERNING_SHOP = 103000002;
    private static final int KERNING_SHOP_NPC = 1051002;
    // Kerning's west field: its top portal is the only way into town.
    private static final int KERNING_WEST_FIELD = 103010000;
    private static final int HENESYS_SHOP = 100000102;
    private static final int HENESYS_SHOP_NPC = 1011100;
    private static final int HENESYS = 100000000;
    // Henesys hunting grounds: 3 and 4 portal hops from the Henesys shop.
    private static final int HENESYS_GATE = 106010000;
    private static final int SLEEPYWOOD_ROAD = 106010100;
    private static final int EL_NATH_FIELD = 211040200;

    @Test
    void agentOffThePortalGraphDoesNotLeaveForAnUnreachableShop() {
        Character agent = agent(301, "ElNathCitizen", EL_NATH_FIELD);
        AgentRuntimeEntry entry = entryWithBundle(agent, "warrior-standard-v1");
        AgentObjectiveDefinition plan = new AgentObjectiveDefinition(
                "citizen:plan:301", "citizen.routine", 80, Long.MAX_VALUE, 5,
                AgentObjectiveSource.QUEST_PLAN, "citizen-v1", "citizen:301");
        AgentObjectiveKernel.start(entry, plan, 10L);
        AgentResourcePlanningState planning = planShortage(entry, "supply:HP_POTION:301", plan.objectiveId());
        AgentSupplyProcurementState execution = execution(entry);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 20L));

        // No trip toward a Victoria shop the router cannot reach: the plan keeps running.
        assertFalse(execution.isActive());
        assertEquals(plan, AgentObjectiveKernel.active(entry));
        assertTrue(execution.suppressed(AgentResourceCategory.HP_POTION, 21L));
        assertEquals(plan.objectiveId(), planning.procurement(AgentResourceCategory.HP_POTION).objectiveId());
    }

    @Test
    void companionWhoseShopRouteTurnsIntoADetourGivesUpAndFollowsItsOwnerAgain() {
        Character agent = agent(302, "Brannock", KERNING_WEST_FIELD);
        AgentRuntimeEntry entry = entryWithBundle(agent, "warrior-standard-v1");
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        planShortage(entry, "supply:MP_POTION:302", "");
        AgentSupplyProcurementState execution = execution(entry);
        startTrip(entry, execution, "supply:MP_POTION:302", AgentResourceCategory.MP_POTION,
                KERNING_WEST_FIELD);
        // Travel stops the Agent, which clears the follow target the trip started with.
        execution.rememberForegroundMode(true, false);
        assertNull(AgentRelationshipRuntime.followTarget(entry));

        // Two portal hops to the Kerning shop; the town portal refuses entry three times.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 10_000L));
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 12_100L));
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 14_200L));
        assertFalse(execution.abandoned());

        // The router would now walk 21 maps around the blocked portal (Henesys is 11 away).
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 14_300L));
        assertTrue(execution.abandoned());
        assertEquals(KERNING_SHOP, execution.supplierMapId());
        assertTrue(execution.suppressed(AgentResourceCategory.MP_POTION, 14_300L));

        // It does not walk back to the field it left: follow brings it to its owner.
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 14_400L));
        assertFalse(execution.isActive());
        assertNull(AgentObjectiveKernel.active(entry));
        assertTrue(AgentModeStateRuntime.following(entry));
        assertEquals(owner, AgentRelationshipRuntime.followTarget(entry));
    }

    @Test
    void strayStepWithinTheTripsOriginalLengthKeepsTheTrip() {
        Character agent = agent(307, "Zeus956", HENESYS_GATE);
        AgentRuntimeEntry entry = entryWithBundle(agent, "warrior-standard-v1");
        planShortage(entry, "supply:HP_POTION:307", "");
        AgentSupplyProcurementState execution = execution(entry);
        startTrip(entry, execution, "supply:HP_POTION:307", AgentResourceCategory.HP_POTION,
                HENESYS_SHOP, HENESYS_SHOP_NPC, HENESYS_GATE);

        // Three hops when it set out; it got one map closer, then stepped back through a portal.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));
        when(agent.getMapId()).thenReturn(HENESYS);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_100L));
        when(agent.getMapId()).thenReturn(HENESYS_GATE);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_200L));
        assertFalse(execution.abandoned());
        assertEquals(HENESYS_SHOP, execution.supplierMapId());

        // Four hops is longer than the trip it set out on.
        when(agent.getMapId()).thenReturn(SLEEPYWOOD_ROAD);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 1_300L));
        assertTrue(execution.abandoned());
    }

    @Test
    void selfSustainingTripSwitchesToTheNearestSupplierTheRouterStillReaches() {
        Character agent = agent(303, "JourneyThief", KERNING_WEST_FIELD);
        AgentRuntimeEntry entry = entryWithBundle(agent, "thief-claw-standard-v1");
        entry.capabilityStates().require(AgentResourceAutonomyState.STATE_KEY).requireSelfSustaining();
        planShortage(entry, "supply:HP_POTION:303", "");
        AgentSupplyProcurementState execution = execution(entry);
        startTrip(entry, execution, "supply:HP_POTION:303", AgentResourceCategory.HP_POTION,
                KERNING_WEST_FIELD);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 10_000L));
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 12_100L));
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 14_200L));
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 14_300L));

        // Henesys (11 hops) instead of the 21-map detour around Kerning's blocked portal.
        assertEquals(HENESYS_SHOP, execution.supplierMapId());
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, execution.phase());
        assertFalse(execution.abandoned());
    }

    @Test
    void followingCompanionAsksItsPartyBeforeLeavingToShop() {
        Character agent = agent(304, "Sadie", KERNING);
        AgentRuntimeEntry entry = followingCompanion(agent);
        planShortage(entry, "supply:HP_POTION:304", "");
        AgentSupplyProcurementState execution = execution(entry);

        // Just walked in with no potions: it asks and stays with its owner for the wait.
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));
        assertFalse(execution.isActive());
        assertNull(AgentObjectiveKernel.active(entry));
        assertTrue(AgentModeStateRuntime.following(entry));
        long waitEnds = 1_000L + partySupplyWaitMs();
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, waitEnds - 1));
        assertFalse(execution.isActive());

        // Nobody shared: now it goes to the nearest shop and remembers to follow afterwards.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, waitEnds));
        assertTrue(execution.isActive());
        assertEquals(KERNING_SHOP, execution.supplierMapId());
        assertTrue(execution.resumeFollow());
    }

    @Test
    void shareDuringTheWaitEndsTheShortageAndTheNextShortageAsksAgain() {
        Character agent = agent(305, "Vex", KERNING);
        AgentRuntimeEntry entry = followingCompanion(agent);
        AgentResourcePlanningState planning = planShortage(entry, "supply:MP_POTION:305", "");
        AgentSupplyProcurementState execution = execution(entry);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 1_000L));

        // The owner hands over potions: the next observation is healthy.
        planning.update(new AgentSupplyNeed(AgentResourceCategory.MP_POTION,
                50, 50, AgentSupplyUrgency.HEALTHY, "", 2_000L), null);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 2_000L));
        assertFalse(execution.isActive());

        // Much later it runs dry again: it asks again instead of leaving at once.
        long later = 2_000L + 10 * partySupplyWaitMs();
        planShortage(entry, "supply:MP_POTION:305b", "");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, later));
        assertFalse(execution.isActive());
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, later + partySupplyWaitMs()));
        assertTrue(execution.isActive());
    }

    @Test
    void companionThatRestockedRejoinsItsOwnerInsteadOfWalkingBack() {
        Character agent = agent(306, "Fenna", KERNING_SHOP);
        AgentRuntimeEntry entry = entryWithBundle(agent, "thief-claw-standard-v1");
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        planShortage(entry, "supply:HP_POTION:306", "");
        AgentSupplyProcurementState execution = execution(entry);
        startTrip(entry, execution, "supply:HP_POTION:306", AgentResourceCategory.HP_POTION,
                KERNING_WEST_FIELD);
        execution.rememberForegroundMode(true, false);
        execution.markShopRequested();
        execution.markReturning();

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertFalse(execution.isActive());
        assertFalse(execution.abandoned());
        assertTrue(AgentModeStateRuntime.following(entry));
        assertEquals(owner, AgentRelationshipRuntime.followTarget(entry));
    }

    @Test
    void companionWhoseShopVisitBoughtNothingRejoinsItsOwnerInsteadOfGrindingForMesos() {
        // Ilse on the live server: 448 mesos, below her wallet reserve, so the visit buys nothing.
        Character agent = agent(308, "Ilse", KERNING_SHOP);
        when(agent.getJob()).thenReturn(Job.ASSASSIN);
        when(agent.getLevel()).thenReturn(30);
        when(agent.getMeso()).thenReturn(448);
        AgentRuntimeEntry entry = entryWithBundle(agent, "thief-claw-standard-v1");
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        planShortage(entry, "supply:HP_POTION:308", "");
        AgentSupplyProcurementState execution = execution(entry);
        startTrip(entry, execution, "supply:HP_POTION:308", AgentResourceCategory.HP_POTION,
                KERNING_WEST_FIELD);
        execution.rememberForegroundMode(true, false);
        execution.markShopRequested();
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 100L);
        AgentShopStateRuntime.workflow(entry).transition(AgentShopWorkflowPhase.TRANSACTING, "buying", 120L);
        AgentShopStateRuntime.completeWorkflow(entry, "nothing affordable", 150L);
        AgentShopStateRuntime.clearShopState(entry);

        // No resting and grinding for mesos on some training map: it gives the trip up.
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));
        assertTrue(execution.abandoned());
        assertEquals(AgentSupplyProcurementState.Phase.RETURNING, execution.phase());
        assertTrue(execution.suppressed(AgentResourceCategory.HP_POTION, 200L));

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 300L));
        assertFalse(execution.isActive());
        assertTrue(AgentModeStateRuntime.following(entry));
        assertEquals(owner, AgentRelationshipRuntime.followTarget(entry));
    }

    private static Character agent(int id, String name, int mapId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getChair()).thenReturn(-1);
        return agent;
    }

    private static AgentRuntimeEntry followingCompanion(Character agent) {
        AgentRuntimeEntry entry = entryWithBundle(agent, "thief-claw-standard-v1");
        Character owner = mock(Character.class);
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        AgentModeStateRuntime.setFollowing(entry, true);
        return entry;
    }

    private static long partySupplyWaitMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy.PARTY_SUPPLY_WAIT_MS");
    }

    private static AgentRuntimeEntry entryWithBundle(Character agent, String bundleId) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find(bundleId).orElseThrow());
        return entry;
    }

    private static AgentResourcePlanningState planShortage(
            AgentRuntimeEntry entry, String requestId, String objectiveId) {
        AgentResourceCategory category = requestId.contains("MP_POTION")
                ? AgentResourceCategory.MP_POTION : AgentResourceCategory.HP_POTION;
        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        planning.update(new AgentSupplyNeed(category, 0, 50, AgentSupplyUrgency.EMPTY,
                        objectiveId, 1L),
                new AgentProcurementRequest(requestId, category, 50, Long.MAX_VALUE,
                        List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.EMPTY,
                        objectiveId, Long.MAX_VALUE));
        return planning;
    }

    private static AgentSupplyProcurementState execution(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }

    private static void startTrip(AgentRuntimeEntry entry,
                                  AgentSupplyProcurementState execution,
                                  String requestId,
                                  AgentResourceCategory category,
                                  int returnMapId) {
        startTrip(entry, execution, requestId, category, KERNING_SHOP, KERNING_SHOP_NPC, returnMapId);
    }

    private static void startTrip(AgentRuntimeEntry entry,
                                  AgentSupplyProcurementState execution,
                                  String requestId,
                                  AgentResourceCategory category,
                                  int supplierMapId,
                                  int supplierNpcId,
                                  int returnMapId) {
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000,
                Long.MAX_VALUE, 2, AgentObjectiveSource.RECOVERY_POLICY,
                "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, 5L);
        execution.start(requestId, maintenance.objectiveId(), category,
                supplierMapId, supplierNpcId, returnMapId,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
    }
}

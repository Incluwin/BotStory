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
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.shop.AgentShopWorkflowPhase;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import testutil.Items;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An ordinary potion trip (not a readiness duty's) is judged by the reserve the shop buys to
 * (AgentPotionPurchasePolicy target reserve bars), not by a count of 200 potions. The cases come from the live log of
 * 2026-10-05 ("finished shopping at NPC n on map m: HP_POTION a -> b (target t)"); the potions' strengths and the
 * maxima are chosen so that the logged count is exactly what the shop buys to.
 */
class AgentOrdinaryPotionTripTest {
    private static final int KERNING = 103_000_000;
    private static final int KERNING_SHOP = 103_000_002;
    private static final int KERNING_SHOP_NPC = 1_051_002;
    private static final int STRONG_HP = 2_022_003; // stubbed: 1,000 HP
    private static final int WEAK_HP = 2_000_000; // stubbed: 50 HP
    private static final int STRONG_MP = 2_022_000; // stubbed: 600 MP
    private static final int ELIXIR_MP = 2_000_006; // stubbed: 1,600 MP
    private static final int BLUE_MP = 2_000_003; // stubbed: 300 MP

    private final Map<Integer, StatEffect> effects = new HashMap<>();
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private int purse;

    @BeforeEach
    void stubPotions() {
        effects.put(STRONG_HP, effect(1_000, 0));
        effects.put(WEAK_HP, effect(50, 0));
        effects.put(STRONG_MP, effect(0, 600));
        effects.put(ELIXIR_MP, effect(0, 1_600));
        effects.put(BLUE_MP, effect(0, 300));
        classification = mockStatic(AgentUseItemClassificationPolicy.class);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(inv -> inv.getArgument(0) != null);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> effects.get((int) inv.getArgument(0)));
    }

    @AfterEach
    void unstub() {
        classification.close();
    }

    // ---- the shop visit's verdict -----------------------------------------------------------

    @Test
    void aVisitThatBuysToTheReserveIsRestoredThoughItCarriesFarFewerThanTwoHundred() {
        // LilTius at Orbis: HP 0 -> 29 (target 200) with 1.8M mesos left, given up and suppressed.
        Character agent = agent(601, "LilTius", 2_900, 1_000);
        AgentRuntimeEntry entry = entry(agent);
        carry(agent, STRONG_HP, 0);
        planCritical(entry, AgentResourceCategory.HP_POTION, 0);
        startTrip(entry, AgentResourceCategory.HP_POTION, 0);

        shopCompletes(entry);
        carry(agent, STRONG_HP, 29); // 29,000 HP: ten bars of 2,900
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        AgentSupplyProcurementState execution = execution(entry);
        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution.lastOutcome().status());
        assertFalse(execution.abandoned());
        assertEquals(AgentSupplyProcurementState.Phase.RETURNING, execution.phase());
        assertFalse(execution.suppressed(AgentResourceCategory.HP_POTION, 200L));
    }

    @Test
    void manaPotionsBoughtToTheReserveRestoreTheTrip() {
        // SirJona at Perion: MP 8 -> 22 (target 200) with 900k mesos, and again 9 -> 23 eighteen minutes later.
        Character agent = agent(602, "SirJona", 900, 1_100);
        AgentRuntimeEntry entry = entry(agent);
        carry(agent, STRONG_MP, 8);
        planCritical(entry, AgentResourceCategory.MP_POTION, 8);
        startTrip(entry, AgentResourceCategory.MP_POTION, 8);

        shopCompletes(entry);
        carry(agent, STRONG_MP, 22); // 13,200 MP: twelve bars of 1,100
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution(entry).lastOutcome().status());
        assertFalse(execution(entry).abandoned());
    }

    @Test
    void aVisitThatBoughtItsWholeAllowanceIsRestoredEvenBelowTheReserve() {
        // Weak potions for a big HP bar: ten bars would take 1,000 of them, so one visit buys its allowance and stops.
        Character agent = agent(603, "Nous", 5_000, 500);
        AgentRuntimeEntry entry = entry(agent);
        carry(agent, WEAK_HP, 4);
        planCritical(entry, AgentResourceCategory.HP_POTION, 4);
        startTrip(entry, AgentResourceCategory.HP_POTION, 4);

        shopCompletes(entry);
        carry(agent, WEAK_HP, 4 + perVisitAllowance());
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution(entry).lastOutcome().status());
    }

    @Test
    void aVisitThatRanOutOfMesosShortOfTheReserveIsStillGivenUp() {
        Character agent = agent(604, "Brokeish", 2_900, 1_000);
        purse = 3_000;
        AgentRuntimeEntry entry = entry(agent);
        carry(agent, STRONG_HP, 0);
        planCritical(entry, AgentResourceCategory.HP_POTION, 0);
        startTrip(entry, AgentResourceCategory.HP_POTION, 0);

        shopCompletes(entry);
        carry(agent, STRONG_HP, 10); // 10,000 of the 29,000 HP it buys to
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, agent, 200L));

        AgentSupplyProcurementState execution = execution(entry);
        assertEquals(AgentSupplyProcurementOutcome.Status.PARTIALLY_RESTORED, execution.lastOutcome().status());
        assertTrue(execution.abandoned());
        assertTrue(execution.suppressed(AgentResourceCategory.HP_POTION, 200L));
    }

    // ---- whether a trip sets off at all ----------------------------------------------------

    @Test
    void aCriticalRequestWhoseReserveIsAlreadyMetStartsNoTrip() {
        // EvilWiz at Mushroom Castle: MP 9 -> 9 every five minutes for an hour; nine potions held twelve bars.
        Character agent = agent(605, "EvilWiz", 700, 1_200);
        AgentRuntimeEntry entry = entry(agent);
        carry(agent, ELIXIR_MP, 9); // 14,400 MP: twelve bars of 1,200
        AgentResourcePlanningState planning = planCritical(entry, AgentResourceCategory.MP_POTION, 9);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 100L));

        assertFalse(execution(entry).isActive());
        assertNull(planning.procurement(AgentResourceCategory.MP_POTION));
        assertFalse(execution(entry).suppressed(AgentResourceCategory.MP_POTION, 100L));
    }

    @Test
    void aManaRequestPlannedBeforeTheHpVisitFilledManaTooStartsNoSecondTrip() {
        // Pirate555 at Ludibrium: its HP visit took MP from 1 to 43 too, then an MP trip on the request planned at 1
        // went out for 40 -> 49 (target 239); LilTius did the same at Orbis, 25 -> 25 (target 225).
        Character agent = agent(606, "Pirate555", 2_000, 1_200);
        AgentRuntimeEntry entry = entry(agent);
        carry(agent, BLUE_MP, 43); // 12,900 MP: short of twelve bars, but far from critical
        AgentResourcePlanningState planning = planCritical(entry, AgentResourceCategory.MP_POTION, 1);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, agent, 100L));

        assertFalse(execution(entry).isActive());
        assertNull(planning.procurement(AgentResourceCategory.MP_POTION));
    }

    @Test
    void aCriticalCountWithAShortReserveStillCallsForATrip() {
        Character agent = agent(607, "Isreus", 3_500, 800);
        carry(agent, STRONG_HP, 9); // 9,000 of the 35,000 HP it buys to

        assertTrue(AgentPotionReserve.tripNeeded(agent, AgentResourceCategory.HP_POTION, false));
    }

    @Test
    void aStandingDutyHolderKeepsTheCountRuleItsVisitBuysBy() {
        Character agent = agent(608, "DutyHolder", 700, 1_200);
        carry(agent, ELIXIR_MP, 9); // the reserve is met, the count critical

        assertFalse(AgentPotionReserve.tripNeeded(agent, AgentResourceCategory.MP_POTION, false));
        assertTrue(AgentPotionReserve.tripNeeded(agent, AgentResourceCategory.MP_POTION, true));
        carry(agent, ELIXIR_MP, AgentRuntimeConfig.cfg.POT_STOP);
        assertFalse(AgentPotionReserve.tripNeeded(agent, AgentResourceCategory.MP_POTION, true));
    }

    @Test
    void anAgentWhoseMaximumReadsZeroHasNoReserveToMeet() {
        Character agent = agent(609, "Unloaded", 0, 0);
        carry(agent, STRONG_HP, 5);

        assertFalse(AgentPotionReserve.of(agent, AgentResourceCategory.HP_POTION).met());
        assertTrue(AgentPotionReserve.tripNeeded(agent, AgentResourceCategory.HP_POTION, false));
    }

    // ---- fixture ---------------------------------------------------------------------------

    private Character agent(int id, String name, int maxHp, int maxMp) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getJob()).thenReturn(Job.ASSASSIN);
        when(agent.getLevel()).thenReturn(40);
        when(agent.getMapId()).thenReturn(KERNING_SHOP);
        when(agent.getChair()).thenReturn(-1);
        when(agent.getCurrentMaxHp()).thenReturn(maxHp);
        when(agent.getCurrentMaxMp()).thenReturn(maxMp);
        purse = 1_800_000;
        when(agent.getMeso()).thenAnswer(inv -> purse);
        return agent;
    }

    /** Its Use tab holds one stack: {@code quantity} of {@code itemId}. */
    private static void carry(Character agent, int itemId, int quantity) {
        Inventory use = new Inventory(agent, InventoryType.USE, (byte) 24);
        if (quantity > 0) {
            use.addItem(Items.itemWithQuantity(itemId, quantity));
        }
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
    }

    private static AgentRuntimeEntry entry(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find("thief-claw-standard-v1").orElseThrow());
        return entry;
    }

    /** A critical request as the potion check plans it at {@code count} (AgentPotionService.requestLowPotShare). */
    private static AgentResourcePlanningState planCritical(AgentRuntimeEntry entry, AgentResourceCategory category,
                                                           int count) {
        AgentResourcePlanningState planning = entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY);
        int target = Math.max(AgentRuntimeConfig.cfg.POT_LOW_WARN * 2, count);
        AgentSupplyUrgency urgency = count <= 0 ? AgentSupplyUrgency.EMPTY : AgentSupplyUrgency.CRITICAL;
        planning.update(new AgentSupplyNeed(category, count, target, urgency, "", 1L),
                new AgentProcurementRequest("supply:" + category + ":" + entry.bot().getId(), category,
                        target - count, Long.MAX_VALUE, List.of(AgentProcurementMethod.NPC_SHOP),
                        urgency, "", Long.MAX_VALUE));
        return planning;
    }

    private void startTrip(AgentRuntimeEntry entry, AgentResourceCategory category, int before) {
        String requestId = "supply:" + category + ":" + entry.bot().getId();
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000, Long.MAX_VALUE, 2,
                AgentObjectiveSource.RECOVERY_POLICY, "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, 5L);
        AgentSupplyProcurementState execution = execution(entry);
        execution.start(requestId, maintenance.objectiveId(), category, KERNING_SHOP, KERNING_SHOP_NPC, KERNING,
                AgentSupplyProcurementState.Phase.SHOPPING, before, purse);
        execution.markShopRequested();
    }

    /** The shop visit ran to its end; the test then sets what it left in the Use tab. */
    private static void shopCompletes(AgentRuntimeEntry entry) {
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 100L);
        AgentShopStateRuntime.workflow(entry).transition(AgentShopWorkflowPhase.TRANSACTING, "buying", 120L);
        AgentShopStateRuntime.completeWorkflow(entry, "bought", 150L);
        AgentShopStateRuntime.clearShopState(entry);
    }

    private static AgentSupplyProcurementState execution(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }

    private static int perVisitAllowance() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.shop.AgentPotionPurchasePolicy.MAX_PURCHASE_QUANTITY_PER_VISIT");
    }

    private static StatEffect effect(int hp, int mp) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        return effect;
    }
}

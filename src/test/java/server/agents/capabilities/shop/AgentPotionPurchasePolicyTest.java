package server.agents.capabilities.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPotionPurchasePolicyTest {
    @Test
    void shouldSizePurchaseFromRecoveryCapacityInsteadOfStackCount() {
        int quantity = AgentPotionPurchasePolicy.quantityToTarget(
                2_000,
                20,
                300,
                1_000,
                10.0,
                0,
                true);

        assertEquals(27, quantity);
    }

    @Test
    void shouldApplyMinimumAndVisitAndCarryBounds() {
        assertEquals(10, AgentPotionPurchasePolicy.quantityToTarget(
                9_900, 20, 100, 1_000, 10.0, 0, true));
        assertEquals(5, AgentPotionPurchasePolicy.quantityToTarget(
                0, 195, 100, 1_000, 10.0, 0, true));
        assertEquals(7, AgentPotionPurchasePolicy.quantityToTarget(
                0, 20, 100, 1_000, 10.0, 93, true));
    }

    @Test
    void aStandardAboveTheCarryAndVisitLimitsIsBoughtInOneVisitAndNeverToppedUpPastIt() {
        // epq=250/350: one visit buys the whole standard; the top-up toward 500/700 finds the carry room used
        assertEquals(250, AgentPotionPurchasePolicy.floorQuantity(250, 0, 0, 250));
        assertEquals(230, AgentPotionPurchasePolicy.floorQuantity(350, 120, 0, 350));
        assertEquals(0, AgentPotionPurchasePolicy.floorQuantity(500, 250, 250, 250));
        assertEquals(0, AgentPotionPurchasePolicy.floorQuantity(700, 350, 350, 350));
    }

    @Test
    void aStandardUnderTheLimitsKeepsTodaysLimits() {
        assertEquals(40, AgentPotionPurchasePolicy.floorQuantity(40, 0, 0, 20));
        assertEquals(100, AgentPotionPurchasePolicy.floorQuantity(250, 0, 0, 20), "the per-visit limit");
        assertEquals(50, AgentPotionPurchasePolicy.floorQuantity(250, 150, 0, 20), "the carry limit");
    }

    @Test
    void shouldSplitNormalBudgetWhenBothResourcesNeedStock() {
        assertEquals(500, AgentPotionPurchasePolicy.normalSpendBudget(1_000, true));
        assertEquals(1_000, AgentPotionPurchasePolicy.normalSpendBudget(1_000, false));
        assertTrue(AgentPotionPurchasePolicy.triggerReserveBars(false)
                > AgentPotionPurchasePolicy.triggerReserveBars(true));
    }
}

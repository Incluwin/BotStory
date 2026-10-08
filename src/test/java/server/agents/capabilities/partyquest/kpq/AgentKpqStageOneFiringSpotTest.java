package server.agents.capabilities.partyquest.kpq;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import testutil.Items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * KPQ stage 1: a ranged member holds a standoff firing spot and hits what comes into range. In a companion run one
 * with nothing to shoot would only hit what walks into it, so it grinds its territory like a melee member until it
 * has projectiles again; a stock run keeps the firing spot.
 */
class AgentKpqStageOneFiringSpotTest {
    @Test
    void aDryRangedMemberGetsNoFiringSpotInACompanionRun() {
        Character dry = claw(0);
        Character loaded = claw(800);
        try (MockedStatic<AgentAttackExecutionProvider> attacks = claws()) {
            assertFalse(AgentKpqCoordinator.stageOneFiresFromASpot(dry, true, true));
            assertTrue(AgentKpqCoordinator.stageOneFiresFromASpot(loaded, true, true));
            assertFalse(AgentKpqCoordinator.stageOneFiresFromASpot(loaded, false, true), "no ranged weapon");
        }
    }

    @Test
    void aStockRunKeepsTheFiringSpot() {
        Character dry = claw(0);
        try (MockedStatic<AgentAttackExecutionProvider> attacks = claws()) {
            assertTrue(AgentKpqCoordinator.stageOneFiresFromASpot(dry, true, false));
        }
    }

    private static Character claw(int stars) {
        Character agent = mock(Character.class);
        when(agent.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        Inventory use = new Inventory(agent, InventoryType.USE, (byte) 24);
        if (stars > 0) {
            use.addItem(Items.itemWithQuantity(2_070_000, stars));
        }
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        return agent;
    }

    private static MockedStatic<AgentAttackExecutionProvider> claws() {
        MockedStatic<AgentAttackExecutionProvider> attacks = mockStatic(AgentAttackExecutionProvider.class);
        attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(any())).thenReturn(WeaponType.CLAW);
        return attacks;
    }
}

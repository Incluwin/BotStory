package server.agents.capabilities.equipment;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Fighter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentEquipmentServiceTest {
    @Test
    void shouldExposeAgentOwnedWeaponCompatibilityBoundary() {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(Job.BOWMAN);

        assertTrue(AgentEquipmentService.isWeaponCompatible(agent, WeaponType.BOW));
        assertTrue(AgentEquipmentService.isWeaponCompatible(agent, WeaponType.CROSSBOW));
        assertFalse(AgentEquipmentService.isWeaponCompatible(agent, WeaponType.CLAW));
    }

    @Test
    void shouldExposeAgentOwnedMageJobBoundary() {
        assertTrue(AgentEquipmentService.isMageJob(Job.MAGICIAN));
        assertTrue(AgentEquipmentService.isMageJob(Job.BISHOP));
        assertFalse(AgentEquipmentService.isMageJob(Job.ASSASSIN));
    }

    @Test
    void aSwordBuiltCrusaderWearingAnAxeWearsNoUsableWeapon() {
        // Dorran 2026-10-31: Fighter sword skills, no Coma or Panic, a one-handed axe (1412004) in hand
        assertFalse(wearsUsableWeapon(1412004, WeaponType.GENERAL1H_SWING));
        assertTrue(wearsUsableWeapon(1302004, WeaponType.SWORD1H));
    }

    @Test
    void anAgentWithNoWeaponOrNoReadableEquipmentCountsAsArmed() {
        Character bare = mock(Character.class);
        when(bare.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        assertTrue(AgentEquipmentService.wearsUsableWeapon(bare));
        assertTrue(AgentEquipmentService.wearsUsableWeapon(mock(Character.class)));
    }

    private static boolean wearsUsableWeapon(int itemId, WeaponType weaponType) {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(Job.CRUSADER);
        when(agent.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(20);
        when(agent.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(20);
        when(agent.getSkillLevel(Fighter.FINAL_ATTACK_SWORD)).thenReturn(30);
        Inventory equipped = mock(Inventory.class);
        Item weapon = mock(Item.class);
        when(weapon.getItemId()).thenReturn(itemId);
        when(equipped.getItem((short) -11)).thenReturn(weapon);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        InventoryGateway gateway = mock(InventoryGateway.class);
        when(gateway.getWeaponType(itemId)).thenReturn(weaponType);
        try (MockedStatic<AgentInventoryGatewayRuntime> runtime = mockStatic(AgentInventoryGatewayRuntime.class)) {
            runtime.when(AgentInventoryGatewayRuntime::inventory).thenReturn(gateway);
            return AgentEquipmentService.wearsUsableWeapon(agent);
        }
    }

    @Test
    void shouldExposeAgentOwnedReserveBoundaryForNonEquipItems() {
        Character agent = mock(Character.class);
        Item item = mock(Item.class);

        assertFalse(AgentEquipmentService.shouldReserveOwnedItem(agent, item));
    }
}

package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import testutil.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCombatAmmoCounterTest {
    @Test
    void identifiesWeaponsThatRequireProjectileAmmo() {
        assertTrue(AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.BOW));
        assertTrue(AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.CROSSBOW));
        assertTrue(AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.CLAW));
        assertTrue(AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.GUN));

        assertFalse(AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.DAGGER_THIEVES));
        assertFalse(AgentCombatAmmoCounter.isRangedAmmoWeapon(WeaponType.WAND));
        assertFalse(AgentCombatAmmoCounter.isRangedAmmoWeapon(null));
    }

    @Test
    void nonAmmoWeaponsDoNotRequireInventoryInspection() {
        assertEquals(Integer.MAX_VALUE, AgentCombatAmmoCounter.countAmmo(null, null));
        assertEquals(Integer.MAX_VALUE, AgentCombatAmmoCounter.countAmmo(null, WeaponType.DAGGER_THIEVES));
        assertEquals(Integer.MAX_VALUE, AgentCombatAmmoCounter.countAmmo(null, WeaponType.WAND));
    }

    @Test
    void countCarriedIgnoresSoulArrowAndShadowClaw() {
        Character bowman = mock(Character.class);
        when(bowman.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(bowman.getBuffedValue(BuffStat.SOULARROW)).thenReturn(1);
        Inventory use = new Inventory(bowman, InventoryType.USE, (byte) 24);
        when(bowman.getInventory(InventoryType.USE)).thenReturn(use);

        assertEquals(Integer.MAX_VALUE, AgentCombatAmmoCounter.countAmmo(bowman, WeaponType.BOW),
                "the fight never runs out under Soul Arrow");
        assertEquals(0, AgentCombatAmmoCounter.countCarried(bowman, WeaponType.BOW), "but the bag is empty");

        use.addItem(Items.itemWithQuantity(2_060_000, 700));
        assertEquals(700, AgentCombatAmmoCounter.countCarried(bowman, WeaponType.BOW));
        assertEquals(0, AgentCombatAmmoCounter.countCarried(bowman, WeaponType.CLAW), "arrows are no stars");
        assertEquals(Integer.MAX_VALUE, AgentCombatAmmoCounter.countCarried(bowman, WeaponType.WAND));
        assertEquals(Integer.MAX_VALUE, AgentCombatAmmoCounter.countCarried(null, null));
    }
}

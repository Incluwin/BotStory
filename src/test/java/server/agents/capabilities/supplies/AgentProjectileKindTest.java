package server.agents.capabilities.supplies;

import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The word a companion uses for what its weapon shoots (AgentProjectileKind). */
class AgentProjectileKindTest {
    @Test
    void everyAmmoWeaponHasItsWordAndOthersHaveNone() {
        assertEquals(AgentProjectileKind.STARS, AgentProjectileKind.of(WeaponType.CLAW));
        assertEquals(AgentProjectileKind.ARROWS, AgentProjectileKind.of(WeaponType.BOW));
        assertEquals(AgentProjectileKind.BOLTS, AgentProjectileKind.of(WeaponType.CROSSBOW));
        assertEquals(AgentProjectileKind.BULLETS, AgentProjectileKind.of(WeaponType.GUN));
        assertEquals("stars", AgentProjectileKind.STARS.word());
        assertEquals("arrows", AgentProjectileKind.ARROWS.word());
        assertEquals("bolts", AgentProjectileKind.BOLTS.word());
        assertEquals("bullets", AgentProjectileKind.BULLETS.word());
        assertNull(AgentProjectileKind.of(WeaponType.WAND));
        assertNull(AgentProjectileKind.of(WeaponType.DAGGER_THIEVES));
        assertNull(AgentProjectileKind.of(null));
    }
}

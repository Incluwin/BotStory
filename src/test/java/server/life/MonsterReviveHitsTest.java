package server.life;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A revived form keeps who hit the form it revived from (Monster.killBy), and passes them on with its own hitters
 * down a chain of revives: Snack Bar's stand 8220008 revives as the boss 8220009, King Clang 5220001 as 5220000.
 */
class MonsterReviveHitsTest {
    private static final int ANN = 2;
    private static final int BEA = 3;

    @Test
    void aRevivedFormKeepsWhoHitTheFormsBeforeIt() {
        Monster stand = monster(8_220_008);
        stand.applyFakeDamage(player(ANN), 10, false);
        assertEquals(Set.of(ANN), stand.hittersSoFar());

        Monster snackBar = monster(8_220_009);
        assertFalse(snackBar.hitBeforeReviveBy(id -> true), "a fresh spawn revived from nothing");
        snackBar.revivedFrom(stand.hittersSoFar());
        assertTrue(snackBar.hitBeforeReviveBy(id -> id == ANN));
        assertFalse(snackBar.hitBeforeReviveBy(id -> id == BEA));

        snackBar.applyFakeDamage(player(BEA), 10, false);
        assertEquals(Set.of(ANN, BEA), snackBar.hittersSoFar(), "its own hitters and those before it");
    }

    private static Character player(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private static Monster monster(int id) {
        MonsterStats stats = new MonsterStats();
        stats.hp = 1000;
        stats.mp = 100;
        stats.name = Integer.toString(id);
        stats.boss = true;
        return new Monster(id, stats);
    }
}

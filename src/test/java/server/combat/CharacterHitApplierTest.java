package server.combat;

import client.BuffStat;
import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guard arithmetic shared by mob hits and PvP hits (docs/PVP_PLAN.md V1.1). */
class CharacterHitApplierTest {
    private static Character target(int hp, int mp, Integer magicGuard) {
        Character target = mock(Character.class);
        when(target.getHp()).thenReturn(hp);
        when(target.getMp()).thenReturn(mp);
        when(target.getBuffedValue(BuffStat.MAGIC_GUARD)).thenReturn(magicGuard);
        when(target.getBuffedValue(BuffStat.MESOGUARD)).thenReturn(null); // Mockito's Integer default is 0, not null
        return target;
    }

    @Test
    void magicGuardMovesItsFullShareAgainstMonsters() {
        Character mage = target(5000, 5000, 80);

        CharacterHitApplier.Applied applied =
                CharacterHitApplier.applyLoss(mage, 1000, 0, CharacterHitApplier.Guards.PVE, false);

        assertEquals(200, applied.hpLoss());
        assertEquals(800, applied.mpLoss());
        verify(mage).addMPHP(-200, -800);
    }

    @Test
    void magicGuardMovesHalfItsShareInPvp() {
        Character mage = target(5000, 5000, 80);

        CharacterHitApplier.Applied applied =
                CharacterHitApplier.applyLoss(mage, 1000, 0, new CharacterHitApplier.Guards(0.5d, 0.75d), false);

        assertEquals(600, applied.hpLoss());
        assertEquals(400, applied.mpLoss());
    }

    @Test
    void magicGuardSpillsBackToHpWhenMpRunsOut() {
        Character mage = target(5000, 100, 80);

        CharacterHitApplier.Applied applied =
                CharacterHitApplier.applyLoss(mage, 1000, 0, CharacterHitApplier.Guards.PVE, false);

        assertEquals(900, applied.hpLoss());
        assertEquals(100, applied.mpLoss());
    }

    @Test
    void noGuardsLeaveTheWholeHitOnHpAndSpendNoMesos() {
        Character mage = target(5000, 5000, 80);
        Character thief = target(5000, 100, null);
        when(thief.getBuffedValue(BuffStat.MESOGUARD)).thenReturn(80);

        CharacterHitApplier.Applied mageHit =
                CharacterHitApplier.applyLoss(mage, 1000, 0, CharacterHitApplier.Guards.NONE, true);
        CharacterHitApplier.Applied thiefHit =
                CharacterHitApplier.applyLoss(thief, 1000, 0, CharacterHitApplier.Guards.NONE, true);

        assertEquals(1000, mageHit.hpLoss());
        assertEquals(0, mageHit.mpLoss());
        assertEquals(1000, thiefHit.hpLoss());
        verify(thief, never()).gainMeso(anyInt(), anyBoolean());
    }

    @Test
    void aKnockOutStopsAtOneHp() {
        Character fighter = target(300, 100, null);

        CharacterHitApplier.Applied applied =
                CharacterHitApplier.applyLoss(fighter, 1000, 0, CharacterHitApplier.Guards.PVE, true);

        assertTrue(applied.floored());
        assertEquals(299, applied.hpLoss());
        verify(fighter).addMPHP(-299, 0);
    }

    @Test
    void aHitThatLeavesHpIsNotAKnockOut() {
        Character fighter = target(3000, 100, null);

        CharacterHitApplier.Applied applied =
                CharacterHitApplier.applyLoss(fighter, 1000, 0, CharacterHitApplier.Guards.PVE, true);

        assertFalse(applied.floored());
        assertEquals(1000, applied.hpLoss());
    }

    @Test
    void withoutTheFloorAHitCanTakeAllHp() {
        Character fighter = target(300, 100, null);

        CharacterHitApplier.Applied applied =
                CharacterHitApplier.applyLoss(fighter, 1000, 0, CharacterHitApplier.Guards.PVE, false);

        assertFalse(applied.floored());
        assertEquals(1000, applied.hpLoss());
    }
}

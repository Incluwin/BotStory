package server.agents.diagnostics.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaAnomalyChecksTest {
    @Test
    void aKeydownNukeCastEverySecondIsImpossible() {
        // Big Bang: 10 casts in 10s against an animation+keydown floor of 1680ms.
        assertTrue(ArenaAnomalyChecks.castRateImpossible(10, 10, 1680, 0.6));
    }

    @Test
    void boostersMayLegitimatelyBeatTheFloorALittle() {
        // 18 casts in 18s against a 1100ms floor is inside what attack speed can explain.
        assertFalse(ArenaAnomalyChecks.castRateImpossible(18, 18, 1100, 0.6));
    }

    @Test
    void damageThatEmptiesTheDummyPoolIsNotAMeasurementOfTheSkill() {
        assertTrue(ArenaAnomalyChecks.damageBoundedByDummy(5_000_000L, 5_000_000L, 1));
        assertTrue(ArenaAnomalyChecks.damageBoundedByDummy(9_500_000L, 5_000_000L, 2));
        assertFalse(ArenaAnomalyChecks.damageBoundedByDummy(1_800_000L, 5_000_000L, 1));
    }

    @Test
    void attacksThatMoveNoHpAreFlagged() {
        assertTrue(ArenaAnomalyChecks.attackedWithoutDamage(12, 0));
        assertFalse(ArenaAnomalyChecks.attackedWithoutDamage(0, 0));
        assertFalse(ArenaAnomalyChecks.attackedWithoutDamage(12, 500));
    }
}

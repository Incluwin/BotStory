package server.life;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalDropLevelEligibilityTest {
    @Test
    void acceptsOnlyMobLevelsInsideInclusiveRange() {
        MonsterGlobalDropEntry entry = new MonsterGlobalDropEntry(
                5220000, 1_000, -1, 1, 1, (short) 0, 50, 90);

        assertFalse(entry.isEligibleForMobLevel(49));
        assertTrue(entry.isEligibleForMobLevel(50));
        assertTrue(entry.isEligibleForMobLevel(90));
        assertFalse(entry.isEligibleForMobLevel(91));
    }

    @Test
    void stockConstructorRetainsAllLevelBehavior() {
        MonsterGlobalDropEntry entry = new MonsterGlobalDropEntry(
                5220000, 1_000, -1, 1, 1, (short) 0);

        assertTrue(entry.isEligibleForMobLevel(0));
        assertTrue(entry.isEligibleForMobLevel(255));
    }
}

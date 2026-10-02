package server.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gear a citizen of a job and level wears (docs/SERVER_KNOWLEDGE_PLAN.md 8.4), over the real file and the seed rows:
 * named, non-cash, obtainable equips of the slot and band that its job, gender and main stats allow, stable per seed.
 */
class GearPicksTest {
    private static final int WARRIOR = 1;
    private static final int MAGICIAN = 2;
    private static final int MALE = 0;
    private static final int FEMALE = 1;
    private static final int[] STRONG = {999, 999, 999, 999};

    private final WorldKnowledge k = SeedOverlay.knowledge();

    @Test
    void aWarriorsOneHandedSwordAtSeventy() {
        int pick = GearPicks.pick(k, "weapon", 70, WARRIOR, MALE, 130, STRONG, 7);
        ItemFact sword = k.item(pick).orElseThrow();

        assertEquals(130, pick / 10_000, "a one-handed sword keeps sword mastery");
        assertTrue(sword.reqLevel() >= 51 && sword.reqLevel() <= 70, sword.toString());
        assertFalse(sword.cash());
        assertTrue(k.obtainable(pick));
        assertTrue(sword.reqJob() == 0 || (sword.reqJob() & WARRIOR) != 0);
    }

    @Test
    void genderJobAndStatsAreRespected() {
        for (long seed = 0; seed <= 20; seed++) {
            int coat = GearPicks.pick(k, "longcoat", 50, MAGICIAN, FEMALE, 0, STRONG, seed);
            assertTrue(coat == 0 || coat / 1000 % 10 != 0, "a female citizen never gets a male overall: " + coat);
            int cap = GearPicks.pick(k, "cap", 40, WARRIOR, MALE, 0, STRONG, seed);
            ItemFact fact = k.item(cap).orElseThrow();
            assertTrue(fact.reqJob() == 0 || (fact.reqJob() & WARRIOR) != 0, fact.toString());
        }
        int weak = GearPicks.pick(k, "weapon", 70, WARRIOR, MALE, 130, new int[] {4, 4, 4, 4}, 3);
        if (weak != 0) {
            ItemFact fact = k.item(weak).orElseThrow();
            assertTrue(fact.reqStr() <= 4 && fact.reqDex() <= 4 && fact.reqInt() <= 4 && fact.reqLuk() <= 4, fact.toString());
        }
    }

    @Test
    void stablePerSeedAndVariedAcrossSeeds() {
        assertEquals(GearPicks.pick(k, "coat", 50, WARRIOR, MALE, 0, STRONG, 11), GearPicks.pick(k, "coat", 50, WARRIOR, MALE, 0, STRONG, 11));
        Set<Integer> coats = new HashSet<>();
        for (long seed = 0; seed <= 20; seed++) {
            coats.add(GearPicks.pick(k, "coat", 50, WARRIOR, MALE, 0, STRONG, seed));
        }
        assertTrue(coats.size() >= 2, coats.toString());
    }

    @Test
    void nothingFitsGivesZero() {
        assertEquals(0, GearPicks.pick(k, "weapon", 70, WARRIOR, MALE, 999, STRONG, 1), "no weapon of that type");
    }
}

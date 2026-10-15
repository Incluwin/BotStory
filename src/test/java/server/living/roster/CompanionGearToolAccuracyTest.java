package server.living.roster;

import org.junit.jupiter.api.Test;
import server.combat.CombatFormulaProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The accuracy a {@code !bossgear <name> <boss>} kit aims for, and the sentence its reply ends with. */
class CompanionGearToolAccuracyTest {
    private final CombatFormulaProvider formula = CombatFormulaProvider.getInstance();

    @Test
    void theNeedIsTheLeastAccuracyThatAlwaysHits() {
        // Showa's The Boss (level 175, avoid 120) for a level 200 warrior; Pink Bean's parts (180, 55); a level 150
        // warrior under The Boss pays 10 per level of difference
        int[][] cases = {{175, 120, 200}, {180, 55, 200}, {175, 120, 150}, {152, 40, 200}};
        for (int[] c : cases) {
            int need = CompanionGearTool.alwaysHits(c[0], c[1], c[2]);
            assertEquals(1.0, formula.calculatePhysicalMobHitChance(need, c[2], c[0], c[1]), 1e-9,
                    "need " + need + " always hits a level " + c[0] + " avoid " + c[1]);
            assertTrue(formula.calculatePhysicalMobHitChance(need - 1, c[2], c[0], c[1]) < 1.0,
                    "one less does not: " + (need - 1));
        }
        assertEquals(438, CompanionGearTool.alwaysHits(175, 120, 200));
        assertEquals(0, CompanionGearTool.alwaysHits(175, 0, 200));
    }

    @Test
    void theReplySaysWhatTheKitReachedAndHowMuchDexWouldCloseAGap() {
        assertEquals(" Accuracy for showa: 440 of the 438 that always hits.",
                CompanionGearTool.accuracyLine("showa", 440, 438));
        assertEquals(" Accuracy for showa: 398 of the 438 that always hits; 40 short (about 50 more DEX would close"
                + " it).", CompanionGearTool.accuracyLine("showa", 398, 438));
    }
}

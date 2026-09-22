package server.agents.diagnostics.arena;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaOptimizerTest {
    private static Trial trial(String scenario, int skillId, int targets, int seconds, long damage) {
        return new Trial(scenario, skillId, "s" + skillId, targets, true, seconds, damage, (double) damage / seconds, 1, 0, 0L);
    }

    @Test
    void ranksByDamagePerSecondNotTotalDamage() {
        Trial shortStrong = trial("single", 1, 1, 10, 1000);   // 100 dps
        Trial longWeak = trial("single", 2, 1, 40, 2000);      // 50 dps
        assertEquals(1, ArenaOptimizer.best(List.of(shortStrong, longWeak)).orElseThrow().skillId());
        assertEquals(List.of(1, 2), ArenaOptimizer.topSkills(List.of(longWeak, shortStrong), 3));
    }

    @Test
    void topSkillsDeduplicatesRepeatedRuns() {
        List<Trial> trials = List.of(trial("single", 5, 1, 10, 900), trial("single", 5, 1, 10, 950),
                trial("single", 6, 1, 10, 800), trial("single", 7, 1, 10, 100));
        assertEquals(List.of(5, 6), ArenaOptimizer.topSkills(trials, 2));
    }

    @Test
    void meanDpsAveragesRepeatedRunsAndPicksTheBestMean() {
        List<Trial> trials = List.of(trial("boss", 1, 1, 10, 1000), trial("boss", 1, 1, 10, 600),
                trial("boss", 2, 1, 10, 900), trial("boss", 2, 1, 10, 700));
        Map<Integer, Double> means = ArenaOptimizer.meanDpsBySkill(trials);
        assertEquals(80.0, means.get(1), 1e-9);
        assertEquals(80.0, means.get(2), 1e-9);
        assertEquals(1, ArenaOptimizer.bestSkillByMean(List.of(trial("boss", 1, 1, 10, 1000), trial("boss", 2, 1, 10, 999))));
    }

    @Test
    void aoeThresholdIsTheSmallestPackWhereTheAreaSkillWins() {
        Map<Integer, Double> single = Map.of(2, 100.0, 3, 100.0, 4, 100.0);
        Map<Integer, Double> multi = Map.of(2, 80.0, 3, 120.0, 4, 160.0);
        assertEquals(3, ArenaOptimizer.aoeThreshold(single, multi));
        assertEquals(0, ArenaOptimizer.aoeThreshold(single, Map.of(2, 50.0, 3, 60.0, 4, 70.0)));
        assertEquals(2, ArenaOptimizer.aoeThreshold(single, Map.of(2, 101.0)));
    }

    @Test
    void specialMovesKeptUnlessTheyClearlyCost() {
        assertTrue(ArenaOptimizer.specialMovesPayOff(100, 100));
        assertTrue(ArenaOptimizer.specialMovesPayOff(98, 100));
        assertFalse(ArenaOptimizer.specialMovesPayOff(90, 100));
    }
}

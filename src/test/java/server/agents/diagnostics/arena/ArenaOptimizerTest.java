package server.agents.diagnostics.arena;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaOptimizerTest {
    @Test
    void pairingSeesThroughConditionsThatDwarfTheDifference() {
        // Transformed pairs read ~100k, untransformed ~20k; the learned side wins every pair by ~2k.
        List<Double> learned = List.of(102_000.0, 22_000.0, 101_000.0, 21_500.0, 103_000.0);
        List<Double> prior = List.of(100_000.0, 20_000.0, 99_500.0, 19_000.0, 100_500.0);
        ArenaOptimizer.PairedComparison comparison = ArenaOptimizer.paired(learned, prior);
        assertEquals(2_100.0, comparison.meanDifference(), 1e-6);
        assertTrue(comparison.decisive(), "a steady per-pair gain is a result even when conditions swing 5x");
        // Same means, but the gain is all in one pair: not a result.
        ArenaOptimizer.PairedComparison lucky = ArenaOptimizer.paired(
                List.of(110_500.0, 20_000.0, 99_500.0, 19_000.0, 100_500.0), prior);
        assertFalse(lucky.decisive());
    }

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

    @Test
    void medianIgnoresTheOutlierRunThatWouldSwingAMean() {
        // One lucky run of skill 2 beats skill 1 on the mean; on the median skill 1 still wins.
        List<Trial> trials = List.of(
                trial("single", 1, 1, 10, 1000), trial("single", 1, 1, 10, 1000), trial("single", 1, 1, 10, 1000),
                trial("single", 2, 1, 10, 300), trial("single", 2, 1, 10, 900), trial("single", 2, 1, 10, 2000));
        assertEquals(100.0, ArenaOptimizer.medianDpsBySkill(trials).get(1), 1e-9);
        assertEquals(90.0, ArenaOptimizer.medianDpsBySkill(trials).get(2), 1e-9);
        assertEquals(1, ArenaOptimizer.bestSkillByMedian(trials));
        assertEquals(2, ArenaOptimizer.bestSkillByMean(trials));
    }

    @Test
    void spreadIsTheRangeOfTheRepeatedRuns() {
        List<Trial> trials = List.of(trial("boss", 4, 1, 10, 400), trial("boss", 4, 1, 10, 1000),
                trial("boss", 5, 1, 10, 700));
        assertEquals(60.0, ArenaOptimizer.spreadBySkill(trials).get(4), 1e-9);
        assertEquals(0.0, ArenaOptimizer.spreadBySkill(trials).get(5), 1e-9);
    }

    @Test
    void aWinInsideTheSpreadIsNotDecisive() {
        assertFalse(ArenaOptimizer.decisive(110, 100, 40, 5));   // gap 10, noise 40
        assertFalse(ArenaOptimizer.decisive(110, 100, 5, 40));   // noise belongs to either side
        assertTrue(ArenaOptimizer.decisive(200, 100, 40, 5));
    }

    @Test
    void skillsThatDealTheTargetsHpAreNotRankableByDps() {
        // Cosmic deals monster.getHp() - 1 for Heaven's Hammer, so on a dummy with an overridden
        // HP pool its "dps" is the pool divided by the trial length.
        assertFalse(ArenaOptimizer.rankableByDps(1221011));
        assertTrue(ArenaOptimizer.rankableByDps(1121008));
    }
}

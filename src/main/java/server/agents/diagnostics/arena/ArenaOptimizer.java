package server.agents.diagnostics.arena;

import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pure decision rules of the combat arena, kept apart from the world so they are testable:
 * which trial won, which candidates deserve a longer confirmation run, whether two rounds
 * agree, and from how many targets the area skill beats the single-target skill.
 */
public final class ArenaOptimizer {
    private ArenaOptimizer() {
    }

    /** Damage per second, the reward every comparison uses. */
    public static double dps(Trial trial) {
        return trial.seconds() <= 0 ? 0 : (double) trial.damage() / trial.seconds();
    }

    public static Optional<Trial> best(List<Trial> trials) {
        return trials.stream().max(Comparator.comparingDouble(ArenaOptimizer::dps));
    }

    /** Skill ids of the top {@code count} trials by DPS, best first, without duplicates. */
    public static List<Integer> topSkills(List<Trial> trials, int count) {
        List<Integer> top = new ArrayList<>();
        trials.stream().sorted(Comparator.comparingDouble(ArenaOptimizer::dps).reversed())
                .forEach(trial -> {
                    if (top.size() < count && !top.contains(trial.skillId())) {
                        top.add(trial.skillId());
                    }
                });
        return top;
    }

    /** Mean DPS per skill over the given trials (used when a skill was run more than once). */
    public static Map<Integer, Double> meanDpsBySkill(List<Trial> trials) {
        Map<Integer, double[]> sums = new LinkedHashMap<>();
        for (Trial trial : trials) {
            double[] acc = sums.computeIfAbsent(trial.skillId(), ignored -> new double[2]);
            acc[0] += dps(trial);
            acc[1] += 1;
        }
        Map<Integer, Double> means = new LinkedHashMap<>();
        sums.forEach((skillId, acc) -> means.put(skillId, acc[0] / acc[1]));
        return means;
    }

    public static int bestSkillByMean(List<Trial> trials) {
        return meanDpsBySkill(trials).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * The smallest target count at which the area skill out-damages the single-target skill,
     * given DPS measured per target count for both; 0 when the area skill never wins.
     */
    public static int aoeThreshold(Map<Integer, Double> singleDpsByTargets, Map<Integer, Double> multiDpsByTargets) {
        int threshold = 0;
        for (int targets : multiDpsByTargets.keySet().stream().sorted().toList()) {
            Double single = singleDpsByTargets.get(targets);
            Double multi = multiDpsByTargets.get(targets);
            if (single == null || multi == null) {
                continue;
            }
            if (multi > single) {
                return targets;
            }
        }
        return threshold;
    }

    /** Special moves pay off when the run with them out-damages the run without, allowing a small tolerance. */
    public static boolean specialMovesPayOff(double withDps, double withoutDps) {
        return withDps >= withoutDps * 0.97;
    }
}

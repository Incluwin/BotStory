package server.agents.capabilities.combat.learning;

import java.util.Map;
import java.util.TreeMap;

/**
 * What the arena learned about fighting as one job, per scenario ({@code single}, {@code multi},
 * {@code boss}). Stored as JSON under {@code scripts/config/agents/combat-policy/<jobId>.json}
 * so the learned values can be read, diffed and deleted. The live loop only exploits a scenario
 * whose learned policy beat the prior scorer in the arena ({@code adopted}).
 */
public record AgentCombatPolicy(int jobId,
                                String jobName,
                                int level,
                                String build,
                                String learnedAt,
                                double gammaPerSecond,
                                boolean priorsRelaxed,
                                Map<Integer, String> actionNames,
                                Map<String, Scenario> scenarios) {
    /** One scenario's table and the arena evidence it was adopted (or not) on. */
    public record Scenario(boolean adopted,
                           double learnedDps,
                           double priorDps,
                           long decisions,
                           Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> table) {
        public Scenario {
            table = table == null ? Map.of() : new TreeMap<>(table);
        }
    }

    public AgentCombatPolicy {
        actionNames = actionNames == null ? Map.of() : new TreeMap<>(actionNames);
        scenarios = scenarios == null ? Map.of() : new TreeMap<>(scenarios);
    }

    /** The scenario a live situation falls into, mirroring how the arena stages them. */
    public static String scenarioFor(boolean bossTarget, int targetsInReach) {
        if (bossTarget) {
            return "boss";
        }
        return targetsInReach >= 2 ? "multi" : "single";
    }
}

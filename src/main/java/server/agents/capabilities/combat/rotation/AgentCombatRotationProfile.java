package server.agents.capabilities.combat.rotation;

import java.util.List;

/**
 * What the combat arena measured to be the best way for one job to fight, per scenario. The
 * live combat loop exploits it (preferred attack skill per scenario, the mob count from which
 * the area skill wins, whether summons/debuffs pay off); the arena rewrites it. Stored as JSON
 * under {@code scripts/config/agents/combat-profiles/<jobId>.json} so operators can review,
 * pin or delete it.
 */
public record AgentCombatRotationProfile(int jobId,
                                         String jobName,
                                         int level,
                                         String build,
                                         String measuredAt,
                                         Choice single,
                                         Choice multi,
                                         Choice boss,
                                         List<Trial> trials) {
    /** Preferred attack for a scenario; {@code aoeFromTargets} only matters for {@code multi}. */
    public record Choice(int skillId,
                         String skillName,
                         double damagePerSecond,
                         boolean useSpecialMoves,
                         int aoeFromTargets) {
    }

    /** One measured run: scenario, forced skill (0 = scorer's own choice), targets, seconds, results. */
    public record Trial(String scenario,
                        int skillId,
                        String skillName,
                        int targets,
                        boolean specialMoves,
                        int seconds,
                        long damage,
                        double damagePerSecond,
                        int attacks,
                        int specialMovesCast,
                        long mpSpent) {
    }

    public AgentCombatRotationProfile {
        trials = trials == null ? List.of() : List.copyOf(trials);
    }

    public Choice choiceFor(boolean bossTarget, int targetsInReach) {
        if (bossTarget && boss != null) {
            return boss;
        }
        if (multi != null && multi.aoeFromTargets() > 0 && targetsInReach >= multi.aoeFromTargets()) {
            return multi;
        }
        return single;
    }
}

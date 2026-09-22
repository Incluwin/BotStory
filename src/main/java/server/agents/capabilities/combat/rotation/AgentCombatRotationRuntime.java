package server.agents.capabilities.combat.rotation;

import client.Character;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.life.Monster;

import java.util.List;
import java.util.Optional;

/**
 * Applies a measured {@link AgentCombatRotationProfile} to live decisions. It never invents a
 * plan: it only picks, among the plans the planner already built for this tick, the one the
 * arena found best for the situation (boss target, or the number of targets the area skill
 * would hit). When no profile exists or the preferred skill cannot be planned right now, the
 * caller falls back to the estimated-DPS scorer.
 */
public final class AgentCombatRotationRuntime {
    private static volatile boolean enabled = true;

    private AgentCombatRotationRuntime() {
    }

    /** Diagnostic switch so the arena can measure the scorer's own choice ("auto") as a baseline. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static Optional<AgentCombatRotationProfile> profileFor(Character bot) {
        if (!enabled || bot == null || bot.getJob() == null) {
            return Optional.empty();
        }
        return AgentCombatRotationProfileRepository.defaultRepository().find(bot.getJob().getId());
    }

    /** The candidate the profile prefers for this target set, or null to let the scorer decide. */
    public static AgentAttackPlan preferredPlan(Character bot, Monster target, List<AgentAttackPlan> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Optional<AgentCombatRotationProfile> profile = profileFor(bot);
        if (profile.isEmpty()) {
            return null;
        }
        boolean bossTarget = target != null && target.isBoss();
        int targetsInReach = 1;
        for (AgentAttackPlan candidate : candidates) {
            if (candidate.targets != null) {
                targetsInReach = Math.max(targetsInReach, candidate.targets.size());
            }
        }
        AgentCombatRotationProfile.Choice choice = profile.get().choiceFor(bossTarget, targetsInReach);
        if (choice == null || choice.skillId() <= 0) {
            return null;
        }
        for (AgentAttackPlan candidate : candidates) {
            if (candidate.skillId == choice.skillId()) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether the profile wants summons/debuffs in this situation; true without a profile. */
    public static boolean specialMovesWanted(Character bot, boolean bossTarget, int targetsInReach) {
        Optional<AgentCombatRotationProfile> profile = profileFor(bot);
        if (profile.isEmpty()) {
            return true;
        }
        AgentCombatRotationProfile.Choice choice = profile.get().choiceFor(bossTarget, targetsInReach);
        return choice == null || choice.useSpecialMoves();
    }
}

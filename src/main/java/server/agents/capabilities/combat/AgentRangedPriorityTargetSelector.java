package server.agents.capabilities.combat;

import client.Character;
import client.inventory.WeaponType;
import server.agents.capabilities.supplies.AgentAmmoStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.awt.Point;

/**
 * Agent-owned selector for replacing a ranged target the Agent cannot shoot now with one it can.
 */
public final class AgentRangedPriorityTargetSelector {
    private AgentRangedPriorityTargetSelector() {
    }

    public static Monster selectPriorityRangedAttackTarget(AgentRuntimeEntry entry,
                                                          Character agent,
                                                          Point agentPosition,
                                                          Monster preferredTarget) {
        if (entry == null || AgentAmmoStateRuntime.noAmmo(entry) || agent == null || agentPosition == null) {
            return null;
        }

        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(agent);
        if (!AgentCombatAmmoCounter.isRangedAmmoWeapon(weaponType)) {
            return null;
        }
        if (isRangedAttackTarget(entry, agent, weaponType, preferredTarget)) {
            return preferredTarget;
        }

        Monster best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Monster candidate : server.agents.perception.AgentMapPerception.monsters(agent.getMap())) {
            if (candidate == preferredTarget) {
                continue;
            }
            if (!isRangedAttackTarget(entry, agent, weaponType, candidate)) {
                continue;
            }
            double distanceSq = candidate.getPosition().distanceSq(agentPosition);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isRangedAttackTarget(AgentRuntimeEntry entry,
                                                Character agent,
                                                WeaponType weaponType,
                                                Monster target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (!AgentCombatObjectiveTargetStateRuntime.allows(entry, target.getId())) {
            return false;
        }
        AgentAttackPlan plan = AgentCombatPlanRuntime.planAttack(entry, agent, target, AgentCombatConfig.cfg);
        return plan != null
                && plan.route == AgentAttackRoute.RANGED
                && AgentCombatRangePolicy.isTargetInAttackRange(plan, agent, target)
                && AgentCombatRangePolicy.canUseAttackPlanNow(
                        AgentMovementStateRuntime.grounded(entry), weaponType, plan.route);
    }
}

package server.agents.capabilities.combat;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossTargetGate;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns live monster enumeration; target policies consume only its bounded candidates. In companion boss scope a monster
 * immune to the Agent's damage type or reflecting it is no candidate (AgentBossTargetGate, P12 G4), and neither is a
 * part the run never targets (AgentBossRunRegistry.neverTarget, P12 G3): the selector already skipped it as a
 * secondary or projectile target, and now as a primary too.
 */
final class AgentCombatCandidateProvider {
    private AgentCombatCandidateProvider() {
    }

    static List<Monster> local(
            Character agent, Point origin, double maximumDistanceSquared) {
        if (agent == null || agent.getMap() == null || origin == null) {
            return List.of();
        }
        return AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(AgentCombatTargetEligibilityPolicy::isHostileLivingMonster)
                .filter(monster -> {
                    Point aim = AgentCombatAimPointPolicy.aimPoint(agent, monster);
                    return aim != null && aim.distanceSq(origin) <= maximumDistanceSquared;
                })
                // the boss checks last: only monsters in reach pay for them
                .filter(monster -> !AgentBossTargetGate.refuses(agent, monster))
                .filter(monster -> !AgentBossRunRegistry.neverTarget(monster))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    static List<Monster> mapWidePreferred(AgentRuntimeEntry entry, Character agent) {
        return mapWide(entry, agent, true);
    }

    static List<Monster> mapWideIncidental(AgentRuntimeEntry entry, Character agent) {
        return mapWide(entry, agent, false);
    }

    static Map<Integer, Monster> byObjectId(Character agent) {
        if (agent == null || agent.getMap() == null) {
            return Map.of();
        }
        return AgentMapPerception.monsters(agent.getMap()).stream()
                .collect(Collectors.toMap(
                        Monster::getObjectId, Function.identity(), (left, right) -> left));
    }

    private static List<Monster> mapWide(
            AgentRuntimeEntry entry, Character agent, boolean preferred) {
        if (entry == null || agent == null || agent.getMap() == null) {
            return List.of();
        }
        return AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(AgentCombatTargetEligibilityPolicy::isHostileLivingMonster)
                .filter(monster -> AgentCombatObjectiveTargetStateRuntime.allows(
                        entry, monster.getId()))
                .filter(monster -> AgentCombatObjectiveTargetStateRuntime.prefers(
                        entry, monster.getId()) == preferred)
                .filter(monster -> !AgentBossTargetGate.refuses(agent, monster))
                .filter(monster -> !AgentBossRunRegistry.neverTarget(monster))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}

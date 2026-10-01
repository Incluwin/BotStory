package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import server.StatEffect;
import server.agents.capabilities.combat.data.AgentAttackDataProvider;
import server.combat.CombatFormulaProvider;
import server.life.Monster;

/**
 * What an Agent's attacks are worth against one monster, read-only, for a host that ranks Agents (the KPQ lineup at
 * Lakelis): the expected damage per second of the best of its basic swing and the attack skills its weapon allows (as
 * the skill cache takes them), each one attack's expected damage after hit chance, crits and the monster's defense
 * ({@link CombatFormulaProvider#estimateExpectedDamage}) over its animation. Live (2026-09-30, King Slime): Pell hit 74
 * of 74 for 6 a second, HappyWolf 19 of 90 for 48, Rurik 0 of 95; their levels and jobs did not tell them apart. An
 * action with variants is timed by its first, so an Agent ranks the same until its stats, gear, skills or buffs change.
 */
public final class AgentExpectedDamagePolicy {
    private AgentExpectedDamagePolicy() {
    }

    public static double damagePerSecond(Character agent, Monster monster) {
        WeaponType weapon = AgentAttackExecutionProvider.getEquippedWeaponType(agent);
        double best = damagePerSecond(agent, monster, weapon, null, 0);
        for (Skill skill : agent.getSkills().keySet()) {
            int level = agent.getSkillLevel(skill);
            StatEffect effect = level > 0 ? skill.getEffect(level) : null;
            if (effect != null && AgentCombatSkillClassifier.classifySkillCacheBucket(skill, effect)
                    == AgentCombatSkillClassifier.SkillCacheBucket.ACTIVE_ATTACK
                    && AgentCombatWeaponPolicy.canUseSkillWithWeapon(skill.getId(), weapon)
                    && !AgentCombatSkillClassifier.requiresPreAction(skill.getId())) {
                best = Math.max(best, damagePerSecond(agent, monster, weapon, skill, level));
            }
        }
        return best;
    }

    /** One attack's expected damage over its animation; {@code skill} null: the basic swing. */
    private static double damagePerSecond(Character agent, Monster monster, WeaponType weapon, Skill skill, int level) {
        int skillId = skill == null ? 0 : skill.getId();
        StatEffect effect = skill == null ? null : skill.getEffect(level);
        boolean magic = skill != null
                && AgentAttackExecutionProvider.determineSkillRoute(agent, skillId) == AgentAttackRoute.MAGIC;
        CombatFormulaProvider formulas = CombatFormulaProvider.getInstance();
        double damage = formulas.estimateExpectedDamage(agent, monster,
                effect == null ? 1 : AgentCombatHitCounter.effectiveHitCount(effect), skillId,
                formulas.resolveDamageProfile(agent, skillId, effect, magic));
        String action = skill == null ? null
                : skill.resolveAnimationAction(level, AgentAttackExecutionProvider.isTwoHandedWeapon(agent));
        int cooldownMs = AgentAttackExecutionProvider.resolveSkillAttackTiming(skill, action != null ? action
                : AgentAttackDataProvider.getInstance().getBasicAttackSpec(weapon).primaryAction(), agent, null)
                .cooldownMs();
        return damage * 1000.0d / Math.max(1, cooldownMs);
    }
}

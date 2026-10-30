package server.agents.capabilities.combat;

import client.Character;
import client.Disease;
import client.Skill;
import client.inventory.WeaponType;
import server.StatEffect;
import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.capabilities.combat.rotation.AgentCombatRotationRuntime;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.monitoring.AgentPerformanceMonitor;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AgentCombatPlanRuntime {
    private AgentCombatPlanRuntime() {
    }

    public static AgentAttackPlan planAttack(AgentRuntimeEntry entry, Character bot, Monster target,
                                             AgentCombatConfig.Config config) {
        long startedAt = System.nanoTime();
        try {
            List<AgentAttackPlan> candidates = new ArrayList<>(skillAttackCandidates(
                    entry, bot, target, config));
            boolean sealed = bot.hasDisease(Disease.SEAL);
            List<Integer> attackSkillIds = attackSkillIds(entry);
            WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(bot);

            // A wand swing is the emergency fallback for a magician, not a competing damage plan. Allowing
            // it into the score alongside a usable spell can select an invisible-looking basic hit at melee
            // range instead of broadcasting the magic-skill packet. A shooter's basic shot is the same kind of
            // fallback: only while out of MP for its attack skills.
            if (!attackSkillRequired(entry) && (sealed || (!hasUsableMagicSkill(candidates)
                    && !hasLearnedMagicSkill(bot, attackSkillIds)
                    && basicShotAllowed(bot, weaponType, attackSkillIds)))) {
                AgentAttackPlan basicAttack = AgentBasicAttackPlanRuntime.planBasicAttack(bot, target);
                basicAttack = AgentCombatObjectiveTargetStateRuntime.restrictAttackPlan(entry, basicAttack);
                if (basicAttack != null) {
                    candidates.add(basicAttack);
                }
            }
            List<AgentAttackPlan> eligible = skipImmuneElements(target,
                    preferRangedBossCandidates(target, weaponType, candidates));
            // Filming: rotate through the skills instead of repeating the best one.
            AgentAttackPlan shown = AgentSkillShowcase.pick(entry, eligible);
            if (shown != null) {
                return shown;
            }
            // What the Agent learned from its own casts comes first (arena learning, or an adopted
            // policy with enough evidence for this situation); null leaves the choice to what follows.
            AgentAttackPlan learned = AgentCombatLearner.choose(entry, bot, target, eligible);
            if (learned != null) {
                return learned;
            }
            // A measured rotation profile (combat arena) outranks the estimated-DPS scorer when it
            // names one of this tick's candidates; otherwise the scorer decides as before.
            AgentAttackPlan preferred = AgentCombatRotationRuntime.preferredPlan(bot, target, eligible);
            if (preferred != null) {
                return preferred;
            }
            return AgentAttackPlanScoringPolicy.selectBestAttackPlan(bot, eligible);
        } finally {
            AgentPerformanceMonitor.record("combat-plan", System.nanoTime() - startedAt);
        }
    }

    private static boolean attackSkillRequired(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(
                AgentCombatSkillConstraintState.STATE_KEY).attackSkillRequired();
    }

    /** Plans an attack skill without allowing the normal basic-attack fallback. */
    public static AgentAttackPlan planSkillAttackOnly(AgentRuntimeEntry entry, Character bot, Monster target,
                                                       AgentCombatConfig.Config config) {
        List<AgentAttackPlan> candidates = skillAttackCandidates(entry, bot, target, config);
        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        return AgentAttackPlanScoringPolicy.selectBestAttackPlan(
                bot, skipImmuneElements(target, preferRangedBossCandidates(target, weaponType, candidates)));
    }

    /**
     * 2026-10-29: the plans left once those whose skill element {@code target} is immune to (Mob.wz elemAttr 1) are
     * dropped, while another plan remains, before the learned policy, a rotation or the scorer chooses. The damage
     * estimate (CombatFormulaProvider) counts a weak and a strong element but not an immune one, and the learned
     * policies key on target count only, so a Bishop's pick between Angel Ray (holy) and Big Bang (no element) took
     * no account of CWKPQ's Margana, Rellik and Hsalf or Pink Bean's Ariel being holy-immune; a player casts Big Bang.
     */
    static List<AgentAttackPlan> skipImmuneElements(Monster target, List<AgentAttackPlan> candidates) {
        if (target == null || candidates.size() < 2) {
            return candidates;
        }
        List<AgentAttackPlan> hurting = new ArrayList<>(candidates.size());
        for (AgentAttackPlan candidate : candidates) {
            Skill skill = candidate.skillId > 0 ? AgentSkillGatewayRuntime.skills().getSkill(candidate.skillId) : null;
            Element element = skill == null ? null : skill.getElement();
            if (element == null || element == Element.NEUTRAL
                    || target.getElementalEffectiveness(element) != ElementalEffectiveness.IMMUNE) {
                hurting.add(candidate);
            }
        }
        return hurting.isEmpty() || hurting.size() == candidates.size() ? candidates : hurting;
    }

    private static List<AgentAttackPlan> skillAttackCandidates(
            AgentRuntimeEntry entry, Character bot, Monster target, AgentCombatConfig.Config config) {
        if (bot.hasDisease(Disease.SEAL)) return List.of();
        List<AgentAttackPlan> candidates = new ArrayList<>(3);
        for (int skillId : attackSkillIds(entry)) {
            if (AgentClientSkillRules.reuseLocked(entry, skillId)) {
                continue;   // the client would refuse the recast right after it landed
            }
            AgentAttackPlan skillAttack = AgentSkillAttackPlanRuntime.planSkillAttack(
                    bot, target, skillId, config);
            skillAttack = AgentCombatObjectiveTargetStateRuntime.restrictAttackPlan(entry, skillAttack);
            if (skillAttack != null) candidates.add(skillAttack);
        }
        return candidates;
    }

    static List<Integer> attackSkillIds(AgentRuntimeEntry entry) {
        List<Integer> attackSkillIds = AgentCombatSkillClassifier.cachedAttackSkillIds(
                AgentCombatSkillCacheStateRuntime.attackSkillIds(entry),
                AgentCombatSkillCacheStateRuntime.attackSkillId(entry),
                AgentCombatSkillCacheStateRuntime.aoeSkillId(entry));
        AgentCombatSkillConstraintState constraint = entry.capabilityStates()
                .require(AgentCombatSkillConstraintState.STATE_KEY);
        int requiredSkillId = constraint.requiredSkillId();
        if (requiredSkillId > 0) {
            return attackSkillIds.stream().filter(skillId -> skillId == requiredSkillId).toList();
        }
        return attackSkillIds.stream().filter(skillId -> !constraint.banned(skillId)).toList();
    }

    /**
     * Whether a bow, crossbow, claw or gun Agent may add its basic shot: 2026-10-27 (user) "ranged characters normal
     * attacks throw projectiles if out of mp only", so only while it cannot pay the MP of any attack skill it has
     * learned for the weapon (or has none). The cost check is the skill planner's own (StatEffect.canPaySkillCost).
     * Any other weapon may.
     */
    static boolean basicShotAllowed(Character bot, WeaponType weaponType, List<Integer> attackSkillIds) {
        if (!AgentCombatAmmoCounter.isRangedAmmoWeapon(weaponType)) {
            return true;
        }
        for (int skillId : attackSkillIds) {
            Skill skill = AgentSkillGatewayRuntime.skills().getSkill(skillId);
            int skillLevel = skill == null ? 0 : bot.getSkillLevel(skill);
            if (skillLevel <= 0 || !AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, weaponType)) {
                continue;
            }
            StatEffect effect = skill.getEffect(skillLevel);
            if (effect != null && effect.canPaySkillCost(bot)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasUsableMagicSkill(List<AgentAttackPlan> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.skillId > 0
                && candidate.route == AgentAttackRoute.MAGIC);
    }

    static List<AgentAttackPlan> preferRangedBossCandidates(Monster target,
                                                             WeaponType weaponType,
                                                             List<AgentAttackPlan> candidates) {
        if (target == null || !target.isBoss()
                || !AgentAttackExecutionProvider.isDegenerateCapableRangedWeapon(weaponType)) {
            return candidates;
        }
        List<AgentAttackPlan> rangedCandidates = candidates.stream()
                .filter(candidate -> candidate.route == AgentAttackRoute.RANGED)
                .toList();
        return rangedCandidates.isEmpty() ? candidates : rangedCandidates;
    }

    private static boolean hasLearnedMagicSkill(Character bot, List<Integer> skillIds) {
        for (int skillId : skillIds) {
            Skill skill = AgentSkillGatewayRuntime.skills().getSkill(skillId);
            if (skill != null
                    && bot.getSkillLevel(skill) > 0
                    && AgentAttackExecutionProvider.determineSkillRoute(bot, skillId)
                    == AgentAttackRoute.MAGIC) {
                return true;
            }
        }
        // The per-Agent attack cache is deliberately lazy and may briefly be stale after advancement,
        // skill allocation, or rematerialization. Inspect the character's authoritative learned-skill
        // snapshot as a fallback so those windows cannot turn a magician into a basic wand attacker.
        return hasLearnedOffensiveMagicSkill(bot, bot.getSkills());
    }

    static boolean hasLearnedOffensiveMagicSkill(Character bot,
                                                   Map<Skill, Character.SkillEntry> learnedSkills) {
        for (Map.Entry<Skill, Character.SkillEntry> learned : learnedSkills.entrySet()) {
            Skill skill = learned.getKey();
            Character.SkillEntry entry = learned.getValue();
            int skillLevel = entry != null ? Byte.toUnsignedInt(entry.skillevel) : 0;
            if (skill == null || skillLevel <= 0 || skillLevel > skill.getMaxLevel()) {
                continue;
            }
            StatEffect effect = skill.getEffect(skillLevel);
            if (AgentCombatSkillClassifier.isActiveAttackSkill(skill, effect)
                    && AgentAttackExecutionProvider.determineSkillRoute(bot, skill.getId())
                    == AgentAttackRoute.MAGIC) {
                return true;
            }
        }
        return false;
    }
}

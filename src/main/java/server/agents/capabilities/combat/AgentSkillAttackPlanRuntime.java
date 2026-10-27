package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import constants.skills.DragonKnight;
import server.StatEffect;
import server.agents.capabilities.combat.data.AgentAttackDataProvider;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.SkillGateway;
import server.life.Monster;

import java.awt.Rectangle;
import java.util.List;

public final class AgentSkillAttackPlanRuntime {
    private AgentSkillAttackPlanRuntime() {
    }

    /** Why the last {@link #planSkillAttack} on this thread returned null; diagnostics only. */
    private static final ThreadLocal<String> LAST_REJECTION = new ThreadLocal<>();

    public static String lastRejection() {
        return LAST_REJECTION.get();
    }

    /** Character.handleEnergyChargeGain parks the bar at 15000 while the charge is full. */
    private static final int ENERGY_BAR_FULL = config.AgentTuning.intValue("server.agents.capabilities.combat.AgentSkillAttackPlanRuntime.ENERGY_BAR_FULL");

    private static AgentAttackPlan reject(String gate) {
        LAST_REJECTION.set(gate);
        return null;
    }

    public static AgentAttackPlan planSkillAttack(Character bot, Monster primaryTarget, int skillId,
                                                  AgentCombatConfig.Config config) {
        return planSkillAttack(bot, primaryTarget, skillId, config,
                AgentSkillGatewayRuntime.skills(), null);
    }

    public static AgentAttackPlan planSkillAttack(Character bot, Monster primaryTarget, int skillId,
                                                  AgentCombatConfig.Config config, SkillGateway skills) {
        return planSkillAttack(bot, primaryTarget, skillId, config, skills, null);
    }

    /**
     * Plans a normal skill packet while admitting one authored objective point into
     * the hitbox. This is reserved for PQ trigger mobs whose map placement sits
     * outside the conservative general-combat vertical band.
     */
    public static AgentAttackPlan planSkillAttackForAuthoredObjective(
            Character bot, Monster primaryTarget, int skillId,
            AgentCombatConfig.Config config, Rectangle objectivePoint) {
        return planSkillAttack(bot, primaryTarget, skillId, config,
                AgentSkillGatewayRuntime.skills(), objectivePoint);
    }

    /**
     * Plans {@code skillId} swung at {@code aim} with no monster in it (CWKPQ plan B3: a sigil, which only a hit of one
     * of its skills turns): the same gates as a monster attack (combo, form, charge, energy, cooldown, level, cost,
     * weapon, ammo) and the same packet fields and timing, aimed at the point,
     * with no targets. Its hit box is the skill's own area from where the Agent stands, facing the point (a skill with
     * none: a 1x1 box at the point, which its caller then holds the Agent close enough for). Null when a gate refuses
     * ({@link #lastRejection} says which). Meso Explosion is never planned so: it needs mesos to blow up.
     */
    public static AgentAttackPlan planSkillAttackAt(Character bot, java.awt.Point aim, int skillId) {
        return planSkillAttackAt(bot, aim, skillId, AgentSkillGatewayRuntime.skills());
    }

    static AgentAttackPlan planSkillAttackAt(Character bot, java.awt.Point aim, int skillId, SkillGateway skills) {
        LAST_REJECTION.remove();
        if (bot == null || aim == null || skillId <= 0) {
            return reject("invalid");
        }
        if (skillId == constants.skills.ChiefBandit.MESO_EXPLOSION) {
            return reject("needs-mesos");
        }
        Skill skill = skills.getSkill(skillId);
        int skillLevel = skill == null ? 0 : bot.getSkillLevel(skill);
        StatEffect effect = skill == null || skillLevel <= 0 ? null : skill.getEffect(skillLevel);
        if (!AgentComboFinisherPolicy.canPlan(bot, skillId)) {
            return reject("combo-orbs");
        }
        if (!AgentAranCombo.canPlan(bot, skillId)) {
            return reject("aran-combo");
        }
        if (!AgentClientSkillRules.usableInCurrentForm(bot, skillId)) {
            return reject("needs-super-transformation");
        }
        if (!AgentClientSkillRules.chargeReady(bot, skillId)) {
            return reject("needs-charge");
        }
        if (AgentCombatSkillClassifier.isEnergyAttackSkill(skillId) && bot.getEnergyBar() < ENERGY_BAR_FULL) {
            return reject("energy-charge-not-full");
        }
        AgentSkillAttackPlanner.SkillAttackReadiness readiness = AgentSkillAttackPlanner.skillAttackReadiness(
                skillId,
                bot.skillIsCooling(skillId),
                skill != null,
                skillLevel,
                () -> effect.canPaySkillCost(bot),
                () -> AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(
                        skillId, AgentAttackExecutionProvider.getEquippedWeaponType(bot)));
        if (readiness != AgentSkillAttackPlanner.SkillAttackReadiness.READY) {
            return reject("readiness:" + readiness);
        }
        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        AgentAttackRoute route = AgentAttackExecutionProvider.determineSkillRoute(bot, skillId);
        if (AgentSkillAttackPlanner.skillAmmoReadiness(
                effect.getBulletCount(),
                effect.getBulletConsume(),
                AgentCombatHitCounter.shadowPartnerHitMultiplier(bot, route),
                route,
                () -> AgentCombatAmmoCounter.countAmmo(bot, weaponType))
                != AgentSkillAttackPlanner.SkillAmmoReadiness.READY) {
            return reject("ammo");
        }
        String action = AgentAttackExecutionProvider.resolveSkillAttackAction(bot, skill, skillLevel, weaponType);
        boolean facingLeft = aim.x < bot.getPosition().x;
        Rectangle hitBox = effect.hasBoundingBox() ? effect.calculateBoundingBox(bot.getPosition(), facingLeft)
                : new Rectangle(aim.x, aim.y, 1, 1);
        int attackCount = AgentCombatHitCounter.packetSafeHitCount(
                bot, route, AgentCombatHitCounter.effectiveHitCount(effect));
        AgentAttackExecutionProvider.BasicAttackData fallbackAttackData =
                AgentAttackExecutionProvider.buildMonsterBasicAttackData(bot, aim);
        String fallbackAction = AgentAttackDataProvider.getInstance().getBasicAttackSpec(weaponType).primaryAction();
        AgentSkillAttackPlanner.SkillAttackPacketFields packetFields =
                AgentSkillAttackPlanner.resolveSkillAttackPacketFields(
                        route, weaponType, bot.getPosition(), aim, action, fallbackAction);
        AgentAttackExecutionProvider.SkillAttackTiming skillTiming =
                AgentAttackExecutionProvider.resolveSkillAttackTiming(skill, action, bot, fallbackAttackData);
        return new AgentAttackPlan(skillId, skillLevel, attackCount, hitBox, List.of(),
                route, packetFields.display(), packetFields.direction(), packetFields.rangedDirection(),
                packetFields.stance(), fallbackAttackData.speed(), skillTiming.hitDelayMs(), skillTiming.cooldownMs(),
                AgentCombatWeaponPolicy.damageWeaponTypeForAction(skillId, weaponType, action));
    }

    private static AgentAttackPlan planSkillAttack(
            Character bot, Monster primaryTarget, int skillId,
            AgentCombatConfig.Config config, SkillGateway skills,
            Rectangle objectivePoint) {
        LAST_REJECTION.remove();
        Skill skill = skills.getSkill(skillId);
        int skillLevel = skill == null ? 0 : bot.getSkillLevel(skill);
        StatEffect effect = skill == null || skillLevel <= 0 ? null : skill.getEffect(skillLevel);
        if (!AgentComboFinisherPolicy.canPlan(bot, skillId)) {
            return reject("combo-orbs");
        }
        if (!AgentAranCombo.canPlan(bot, skillId)) {
            return reject("aran-combo");
        }
        if (!AgentClientSkillRules.usableInCurrentForm(bot, skillId)) {
            return reject("needs-super-transformation");
        }
        if (!AgentClientSkillRules.chargeReady(bot, skillId)) {
            return reject("needs-charge");
        }
        if (AgentCombatSkillClassifier.isEnergyAttackSkill(skillId) && bot.getEnergyBar() < ENERGY_BAR_FULL) {
            return reject("energy-charge-not-full");
        }
        AgentSkillAttackPlanner.SkillAttackReadiness readiness = AgentSkillAttackPlanner.skillAttackReadiness(
                skillId,
                skillId != 0 && bot.skillIsCooling(skillId),
                skill != null,
                skillLevel,
                () -> effect.canPaySkillCost(bot),
                () -> AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(
                        skillId, AgentAttackExecutionProvider.getEquippedWeaponType(bot)));
        if (readiness != AgentSkillAttackPlanner.SkillAttackReadiness.READY) {
            return reject("readiness:" + readiness);
        }

        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        AgentAttackRoute route = AgentAttackExecutionProvider.determineSkillRoute(bot, skillId);
        if (AgentSkillAttackPlanner.skillAmmoReadiness(
                effect.getBulletCount(),
                effect.getBulletConsume(),
                AgentCombatHitCounter.shadowPartnerHitMultiplier(bot, route),
                route,
                () -> AgentCombatAmmoCounter.countAmmo(bot, weaponType))
                != AgentSkillAttackPlanner.SkillAmmoReadiness.READY) {
            return reject("ammo");
        }

        String action = AgentAttackExecutionProvider.resolveSkillAttackAction(bot, skill, skillLevel, weaponType);
        if (AgentCombatSkillHitboxPolicy.isStrikePointAnchoredAoeSkill(skillId)) {
            Monster strikePointFallback = primaryTarget;
            primaryTarget = bot == null ? primaryTarget : AgentCombatTargetSelector.resolveStrikePointPrimaryByBasicWeapon(
                    bot.getPosition(),
                    strikePointFallback,
                    route,
                    facingLeft -> AgentCombatRangePolicy.basicWeaponReachRect(bot, facingLeft, route),
                    hitBox -> AgentCombatTargetSelector.resolveEffectivePrimary(
                            bot.getPosition(), strikePointFallback, hitBox, server.agents.perception.AgentMapPerception.monsters(bot.getMap())));
        }

        Rectangle hitBox = AgentCombatSkillHitboxPolicy.calculateSkillHitBox(
                effect, bot, primaryTarget, route, skillId, action);
        if (hitBox == null) {
            return reject("hitbox");
        }
        if (objectivePoint != null) {
            hitBox = hitBox.union(objectivePoint);
        }

        boolean strikePointAnchored = AgentCombatSkillHitboxPolicy.isStrikePointAnchoredAoeSkill(skillId);
        Monster preSelectionPrimaryTarget = primaryTarget;
        AgentSkillAttackPlanner.SkillPrimaryTargetSelection targetSelection =
                AgentSkillAttackPlanner.resolvePrimaryTargetAfterHitbox(
                        strikePointAnchored,
                        preSelectionPrimaryTarget,
                        hitBox,
                        () -> AgentCombatRangePolicy.isPrimaryReachableByBasicWeapon(
                                bot, preSelectionPrimaryTarget, route),
                        (candidate, candidateHitBox) -> AgentCombatTargetSelector.resolveEffectivePrimary(
                                bot.getPosition(), candidate, candidateHitBox, server.agents.perception.AgentMapPerception.monsters(bot.getMap())),
                        AgentCombatHitboxIntersection::intersectsMonster);
        if (targetSelection == null) {
            return reject("target-selection");
        }
        primaryTarget = targetSelection.target();

        int attackCount = AgentCombatHitCounter.packetSafeHitCount(
                bot, route, AgentCombatHitCounter.effectiveHitCount(effect));
        java.awt.Point aimPoint = AgentCombatAimPointPolicy.aimPoint(bot, primaryTarget);
        // 2026-10-27: a ranged skill fires at any distance. The v83 client swings instead within 50 px; an Agent
        // never whacks (AgentAttackExecutionProvider.buildMonsterBasicAttackData).
        AgentAttackExecutionProvider.BasicAttackData fallbackAttackData =
                AgentAttackExecutionProvider.buildMonsterBasicAttackData(bot, aimPoint);
        AgentAttackDataProvider.AttackAnimationSpec attackSpec =
                AgentAttackDataProvider.getInstance().getBasicAttackSpec(weaponType);
        String fallbackAction = attackSpec.primaryAction();
        AgentSkillAttackPlanner.SkillAttackPacketFields packetFields =
                AgentSkillAttackPlanner.resolveSkillAttackPacketFields(
                        route, weaponType, bot.getPosition(), aimPoint, action, fallbackAction);
        AgentAttackExecutionProvider.SkillAttackTiming skillTiming =
                AgentAttackExecutionProvider.resolveSkillAttackTiming(skill, action, bot, fallbackAttackData);
        List<Monster> targets = AgentCombatTargetSelector.collectTargetsInHitBox(
                primaryTarget, hitBox, Math.max(1, effect.getMobCount()), server.agents.perception.AgentMapPerception.monsters(bot.getMap()));
        if (skillId == DragonKnight.DRAGON_ROAR
                && !AgentCombatSupportPolicy.canUseDragonRoarPlan(
                bot, targets.size(),
                AgentCombatSupportPolicy.hasNearbyHealSkillAlly(
                        bot, config.SUPPORT_RANGE, config.SUPPORT_VERTICAL_RANGE))) {
            return reject("dragon-roar-ally-rule");
        }
        if (skillId == DragonKnight.DRAGON_ROAR
                && !AgentCombatSupportPolicy.roarLeavesHpAboveAlert(bot, -effect.getHpRate())) {
            return reject("dragon-roar-hp-alert");
        }

        return new AgentAttackPlan(skillId, skillLevel, attackCount, hitBox, targets,
                route, packetFields.display(),
                packetFields.direction(), packetFields.rangedDirection(),
                packetFields.stance(),
                fallbackAttackData.speed(), skillTiming.hitDelayMs(), skillTiming.cooldownMs(),
                AgentCombatWeaponPolicy.damageWeaponTypeForAction(skillId, weaponType, action));
    }
}

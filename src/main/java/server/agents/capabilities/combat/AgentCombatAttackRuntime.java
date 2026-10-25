package server.agents.capabilities.combat;

import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.capabilities.expedition.boss.AgentBossClientLoad;
import server.agents.capabilities.expedition.boss.AgentBossTargetGate;
import server.agents.capabilities.supplies.AgentAmmoStateRuntime;

import client.Character;
import client.Disease;
import net.server.channel.handlers.AbstractDealDamageHandler;
import java.util.ArrayList;
import java.awt.Rectangle;
import server.maps.MapItem;
import server.agents.perception.AgentMapPerception;
import constants.skills.ChiefBandit;
import constants.skills.DragonKnight;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.operations.events.AgentAttackResolvedEvent;
import server.agents.integration.CombatAttackApplicationResult;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSessionEventRuntime;
import server.combat.CombatFormulaProvider;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentCombatAttackRuntime {
    private AgentCombatAttackRuntime() {
    }

    public static AgentAttackTransactionResult attackMonster(AgentRuntimeEntry entry,
                                                              Character bot,
                                                              AgentAttackPlan attackPlan) {
        if (entry == null || bot == null || attackPlan == null || attackPlan.targets == null
                || attackPlan.targets.isEmpty() || attackPlan.route == null) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.INVALID_REQUEST, mapId(bot), skillId(attackPlan));
        }
        Monster primaryTarget = attackPlan.targets.get(0);
        if (primaryTarget == null || !primaryTarget.isAlive()) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.TARGET_UNAVAILABLE, mapId(bot), attackPlan.skillId);
        }
        AgentCombatAttackExecutionPolicy.AttackExecutionReadiness readiness =
                AgentCombatAttackExecutionPolicy.attackExecutionReadiness(
                        AgentCombatCooldownStateRuntime.hasAttackCooldown(entry),
                        AgentAmmoStateRuntime.noAmmo(entry) && attackPlan.route == AgentAttackRoute.RANGED,
                        attackPlan.skillId,
                        () -> AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(
                                        attackPlan.skillId,
                                        AgentAttackExecutionProvider.getEquippedWeaponType(bot))
                                && (attackPlan.skillId <= 0 || !bot.hasDisease(Disease.SEAL))
                                && AgentCombatSkillUsePolicy.canPaySkillCost(
                                        bot, attackPlan.skillId, attackPlan.skillLevel)
                                && !AgentClientSkillRules.reuseLocked(entry, attackPlan.skillId)
                                && AgentClientSkillRules.usableInCurrentForm(bot, attackPlan.skillId),
                        () -> entry != null && attackPlan != null && AgentCombatRangePolicy.canUseAttackPlanNow(
                                AgentMovementStateRuntime.grounded(entry),
                                AgentAttackExecutionProvider.getEquippedWeaponType(bot),
                                attackPlan.route));
        if (readiness != AgentCombatAttackExecutionPolicy.AttackExecutionReadiness.READY) {
            return AgentAttackTransactionResult.deferred(
                    reason(readiness), bot.getMapId(), attackPlan.skillId);
        }

        MapleMap attackMap = bot.getMap();
        if (primaryTarget.getMap() != attackMap) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.TARGET_NOT_IN_AGENT_MAP,
                    bot.getMapId(), attackPlan.skillId);
        }
        if (!AgentCombatRangePolicy.isTargetInAttackRange(attackPlan, bot, primaryTarget)) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.TARGET_OUT_OF_RANGE,
                    bot.getMapId(), attackPlan.skillId);
        }

        int targetLimit = AgentAttackPacketPolicy.targetCount(attackPlan.targets.size());
        List<Monster> authoritativeTargets = authoritativeTargets(bot, attackPlan.targets, targetLimit, attackMap);
        if (authoritativeTargets.isEmpty() || authoritativeTargets.getFirst() != primaryTarget) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.TARGET_UNAVAILABLE, bot.getMapId(), attackPlan.skillId);
        }
        int numAttacked = authoritativeTargets.size();
        int numDamage = AgentAttackPacketPolicy.damageLineCount(attackPlan.numDamage);
        List<Integer> explodedMesos = List.of();
        if (attackPlan.skillId == ChiefBandit.MESO_EXPLOSION) {
            // The server removes the listed meso drops and NPEs on a null list; one damage line per meso.
            explodedMesos = explodableMesos(attackMap, attackPlan.hitBox);
            if (explodedMesos.isEmpty()) {
                return AgentAttackTransactionResult.rejected(
                        AgentAttackTransactionResult.Reason.CANNOT_USE_SKILL, bot.getMapId(), attackPlan.skillId);
            }
            numDamage = Math.max(1, Math.min(numDamage, explodedMesos.size()));
        }
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        if (!explodedMesos.isEmpty()) {
            attack.explodedMesos = new ArrayList<>(explodedMesos);
            attack.attackDelay = (short) Math.max(0, Math.min(Short.MAX_VALUE, attackPlan.hitDelayMs));
        }
        attack.skill = attackPlan.skillId;
        attack.skilllevel = attackPlan.skillLevel;
        attack.numDamage = numDamage;
        attack.numAttacked = numAttacked;
        attack.numAttackedAndDamage = AgentAttackPacketPolicy.packCounts(numAttacked, numDamage);
        attack.speed = attackPlan.speed;
        attack.stance = attackPlan.stance;
        attack.display = attackPlan.display;
        attack.direction = attackPlan.direction;
        attack.rangedirection = attackPlan.rangedDirection;
        attack.ranged = attackPlan.route == AgentAttackRoute.RANGED;
        CombatFormulaProvider.DamageProfile damageProfile = AgentAttackDamageProfileService.resolve(bot, attackPlan);
        attack.magic = damageProfile.magicAttack();
        attack.targets = new HashMap<>();

        for (Monster target : authoritativeTargets) {
            attack.targets.put(target.getObjectId(), scaled(
                    CombatFormulaProvider.getInstance().makeTarget(bot, target, numDamage,
                            attackPlan.skillId, damageProfile, attackPlan.hitDelayMs),
                    linePercent(attackPlan.skillId, AgentBossClientLoad.damagePercent(bot, target.getId()))));
        }

        if (bot.getMap() != attackMap) {
            return AgentAttackTransactionResult.deferred(
                    AgentAttackTransactionResult.Reason.MAP_CHANGED_DURING_ATTACK,
                    bot.getMapId(), attackPlan.skillId);
        }
        List<Monster> committedTargets = authoritativeTargets(bot, authoritativeTargets, numAttacked, attackMap);
        if (committedTargets.isEmpty() || committedTargets.getFirst() != primaryTarget) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.TARGET_UNAVAILABLE, bot.getMapId(), attackPlan.skillId);
        }
        if (committedTargets.size() != authoritativeTargets.size()) {
            Set<Integer> committedTargetIds = committedTargets.stream()
                    .map(Monster::getObjectId)
                    .collect(Collectors.toUnmodifiableSet());
            attack.targets.keySet().retainAll(committedTargetIds);
            attack.numAttacked = committedTargets.size();
            attack.numAttackedAndDamage = AgentAttackPacketPolicy.packCounts(committedTargets.size(), numDamage);
        }
        // What the formula expects this exact cast to deal, taken before the attack moves the orb
        // stack; the learner credits casts with it (calibrated) instead of the noisy roll.
        double expected = AgentCombatLearner.learning(entry)
                ? AgentAttackPlanScoringPolicy.scoreAttackPlan(bot, attackPlan).rawDamage() : 0.0d;
        CombatAttackApplicationResult application =
                AgentAttackExecutionProvider.applyAttackRoute(attackPlan.route, attack, bot);
        if (application == null || !application.applied()) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.HANDLER_REJECTED,
                    bot.getMapId(), attackPlan.skillId);
        }
        int hitLines = attack.targets.values().stream().flatMap(target -> target.damageLines().stream())
                .mapToInt(damage -> damage > 0 ? 1 : 0).sum();
        int totalLines = attack.targets.values().stream().mapToInt(target -> target.damageLines().size()).sum();
        long committedAtMs = System.currentTimeMillis();
        AgentSessionEventRuntime.bus(entry).publish(new AgentAttackResolvedEvent(
                bot.getId(), committedAtMs, bot.getMapId(), attack.targets.size(),
                hitLines, totalLines - hitLines));
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, attackPlan.cooldownMs);
        // on a boss run's battle map: counted for the mirror summary and spaced by the client-load floor (0 is off)
        AgentBossClientLoad.attackCommitted(bot);
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, AgentBossClientLoad.attackFloorMs(bot));
        entry.capabilityStates().require(AgentCombatSpecialMoveState.STATE_KEY).countAttack();
        // Which skill actually swung, and for how much: the measured rotation is this histogram,
        // and the same number is the learner's reward for the decision.
        long dealt = attack.targets.values().stream()
                .flatMap(target -> target.damageLines().stream())
                .mapToLong(damage -> damage)
                .sum();
        AgentCombatSkillUsageStateRuntime.record(entry, attackPlan.skillId, dealt);
        AgentBossClientLoad.memberAttack(bot, dealt);
        if (degenerateBasicAttack(bot, attackPlan)) {
            AgentCombatSkillUsageStateRuntime.recordDegenerate(entry);
        }
        AgentCombatLearner.onAttackCommitted(entry, attackPlan.skillId, dealt, expected);
        AgentSkillShowcase.used(entry, bot, attackPlan.skillId);
        AgentCombatFacingRuntime.rememberAttackFacing(entry, attackPlan.stance);
        AgentCombatDamageRuntime.dashAfterAttack(entry, bot, attackPlan.skillId, attackPlan.skillLevel, attackPlan.stance);
        if (hitLines > 0) {
            AgentClientSkillRules.afterLandedHit(entry, attackPlan.skillId, committedAtMs, attackPlan.cooldownMs);
        }
        // an Aran's client counts each monster the attack hit on its combo counter
        AgentAranCombo.countHits(bot, attackPlan.skillId, (int) attack.targets.values().stream()
                .filter(target -> target.damageLines().stream().anyMatch(damage -> damage > 0)).count());
        AgentCombatAlertRuntime.markAlerted(entry);
        return AgentAttackTransactionResult.committed(
                bot.getMapId(),
                attackPlan.skillId,
                committedTargets.stream().map(Monster::getObjectId).toList(),
                hitLines,
                totalLines - hitLines,
                committedAtMs);
    }

    /**
     * Swings {@code attackPlan} (AgentSkillAttackPlanRuntime.planSkillAttackAt: no targets) at nothing, as a player's
     * attack that hits no monster (CWKPQ plan B3: a sigil, which the caller then hits with the skill's id). The same
     * readiness as {@link #attackMonster} (the Agent's attack lock, ammo, weapon, seal, cost, reuse lock, form, ground)
     * and the same handler: it is broadcast to the map, and its MP, ammo and cooldown are paid. It deals no damage, so
     * neither the skill usage histogram nor the learner hears of it; the Agent's attack lock and facing are kept.
     */
    public static AgentAttackTransactionResult attackAtNothing(AgentRuntimeEntry entry, Character bot,
                                                                AgentAttackPlan attackPlan) {
        if (entry == null || bot == null || attackPlan == null || attackPlan.route == null
                || attackPlan.targets == null || !attackPlan.targets.isEmpty() || attackPlan.skillId <= 0
                || attackPlan.skillId == ChiefBandit.MESO_EXPLOSION) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.INVALID_REQUEST, mapId(bot), skillId(attackPlan));
        }
        AgentCombatAttackExecutionPolicy.AttackExecutionReadiness readiness =
                AgentCombatAttackExecutionPolicy.attackExecutionReadiness(
                        AgentCombatCooldownStateRuntime.hasAttackCooldown(entry),
                        AgentAmmoStateRuntime.noAmmo(entry) && attackPlan.route == AgentAttackRoute.RANGED,
                        attackPlan.skillId,
                        () -> AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(
                                        attackPlan.skillId,
                                        AgentAttackExecutionProvider.getEquippedWeaponType(bot))
                                && !bot.hasDisease(Disease.SEAL)
                                && !bot.skillIsCooling(attackPlan.skillId)
                                && AgentCombatSkillUsePolicy.canPaySkillCost(
                                        bot, attackPlan.skillId, attackPlan.skillLevel)
                                && !AgentClientSkillRules.reuseLocked(entry, attackPlan.skillId)
                                && AgentClientSkillRules.usableInCurrentForm(bot, attackPlan.skillId),
                        () -> AgentCombatRangePolicy.canUseAttackPlanNow(
                                AgentMovementStateRuntime.grounded(entry),
                                AgentAttackExecutionProvider.getEquippedWeaponType(bot),
                                attackPlan.route));
        if (readiness != AgentCombatAttackExecutionPolicy.AttackExecutionReadiness.READY) {
            return AgentAttackTransactionResult.deferred(reason(readiness), bot.getMapId(), attackPlan.skillId);
        }
        int numDamage = AgentAttackPacketPolicy.damageLineCount(attackPlan.numDamage);
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = attackPlan.skillId;
        attack.skilllevel = attackPlan.skillLevel;
        attack.numDamage = numDamage;
        attack.numAttacked = 0;
        attack.numAttackedAndDamage = AgentAttackPacketPolicy.packCounts(0, numDamage);
        attack.speed = attackPlan.speed;
        attack.stance = attackPlan.stance;
        attack.display = attackPlan.display;
        attack.direction = attackPlan.direction;
        attack.rangedirection = attackPlan.rangedDirection;
        attack.ranged = attackPlan.route == AgentAttackRoute.RANGED;
        attack.magic = attackPlan.route == AgentAttackRoute.MAGIC;
        attack.targets = new HashMap<>();
        CombatAttackApplicationResult application =
                AgentAttackExecutionProvider.applyAttackRoute(attackPlan.route, attack, bot);
        if (application == null || !application.applied()) {
            return AgentAttackTransactionResult.rejected(
                    AgentAttackTransactionResult.Reason.HANDLER_REJECTED, bot.getMapId(), attackPlan.skillId);
        }
        long committedAtMs = System.currentTimeMillis();
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, attackPlan.cooldownMs);
        AgentCombatFacingRuntime.rememberAttackFacing(entry, attackPlan.stance);
        return AgentAttackTransactionResult.committed(bot.getMapId(), attackPlan.skillId, List.of(), 0, 0,
                committedAtMs);
    }

    /** Object ids of unclaimed meso drops inside the blast, as the client would list them. */
    static List<Integer> explodableMesos(MapleMap map, Rectangle hitBox) {
        if (map == null || hitBox == null) {
            return List.of();
        }
        List<Integer> oids = new ArrayList<>();
        for (MapItem item : AgentMapPerception.items(map)) {
            if (item.getMeso() > 0 && !item.isPickedUp() && item.getPosition() != null
                    && hitBox.contains(item.getPosition())) {
                oids.add(item.getObjectId());
            }
        }
        return oids;
    }

    /**
     * The plan's live targets on the attack map, in order, up to {@code limit}. In companion boss scope a target immune
     * to the bot's damage type or reflecting it is dropped (AgentBossTargetGate, P12 G4): reflect damage lands for each
     * listed target, secondaries too, and a dropped primary refuses the attack.
     */
    private static List<Monster> authoritativeTargets(Character bot, List<Monster> candidates, int limit,
                                                      MapleMap attackMap) {
        LinkedHashMap<Integer, Monster> targets = new LinkedHashMap<>();
        for (Monster candidate : candidates) {
            if (targets.size() >= limit) {
                break;
            }
            if (candidate != null && candidate.isAlive() && candidate.getMap() == attackMap
                    && !AgentBossTargetGate.refuses(bot, candidate)) {
                targets.putIfAbsent(candidate.getObjectId(), candidate);
            }
        }
        return List.copyOf(targets.values());
    }

    private static AgentAttackTransactionResult.Reason reason(
            AgentCombatAttackExecutionPolicy.AttackExecutionReadiness readiness) {
        return switch (readiness) {
            case ATTACK_COOLDOWN -> AgentAttackTransactionResult.Reason.ATTACK_COOLDOWN;
            case NO_AMMO -> AgentAttackTransactionResult.Reason.NO_AMMO;
            case CANNOT_USE_SKILL -> AgentAttackTransactionResult.Reason.CANNOT_USE_SKILL;
            case CANNOT_USE_ATTACK_PLAN -> AgentAttackTransactionResult.Reason.CANNOT_USE_ATTACK_PLAN;
            case READY -> AgentAttackTransactionResult.Reason.NONE;
        };
    }

    private static int mapId(Character bot) {
        return bot == null ? 0 : bot.getMapId();
    }

    private static int skillId(AgentAttackPlan attackPlan) {
        return attackPlan == null ? 0 : attackPlan.skillId;
    }

    /** A basic attack at close range with a weapon made to shoot: the whack an archer or thief swings point-blank. */
    static boolean degenerateBasicAttack(Character bot, AgentAttackPlan attackPlan) {
        return attackPlan != null && attackPlan.skillId == 0 && attackPlan.isCloseRangeRoute()
                && AgentAttackExecutionProvider.isDegenerateCapableRangedWeapon(
                AgentAttackExecutionProvider.getEquippedWeaponType(bot));
    }

    /**
     * 2026-10-15 (P20 run 7): the percent a boss's companionDamagePercent ({@code bossPercent}) scales the lines of
     * {@code skillId} by. Sacrifice costs its user x% of its first line in HP (CloseRangeDamageHandler), so a scaled
     * line made a Dark Knight pay 30-50k HP a cast, left at 1 HP by safeAddHP for the next hit: its lines stay as made.
     */
    static int linePercent(int skillId, int bossPercent) {
        return skillId == DragonKnight.SACRIFICE ? 100 : bossPercent;
    }

    /** The target's lines scaled to {@code percent} (a boss's companionDamagePercent); the target itself at 100. */
    static AbstractDealDamageHandler.AttackTarget scaled(AbstractDealDamageHandler.AttackTarget made, int percent) {
        if (made == null || percent == 100) {
            return made;
        }
        List<Integer> lines = new ArrayList<>(made.damageLines().size());
        for (Integer line : made.damageLines()) {
            long value = line == null ? 0L : (long) line * percent / 100L;
            lines.add((int) Math.max(0L, Math.min(Integer.MAX_VALUE, value)));
        }
        return new AbstractDealDamageHandler.AttackTarget(made.delay(), lines, made.critLineIndices());
    }

}

package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.DawnWarrior;
import constants.skills.WindArcher;
import server.StatEffect;
import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * 2026-10-30: a warrior's Final Attack, the follow-up a v83 client sends on its own. Which attacks set it off is data:
 * an attack skill's Skill.wz {@code finalAttack} lists Final Attacks and the weapon types each needs (Power Strike and
 * Slash Blast list 1100002-1300003), and the client follows such a skill with the one the attacker has for the weapon
 * it holds, at that Final Attack's prop. A real client (Lv255 Fighter, one-handed axe, Final Attack: Axe 30 = prop 60,
 * Power Strike; monitored packets) sent no Final Attack after 30 basic attacks, and one after 15 of 19 Power Strikes,
 * hit or not: a CLOSE_RANGE_ATTACK of its own with the Final Attack's id, display 0, the weapon's final swing
 * (swingOF 10 times, stabOF 5), the Power Strike's facing and speed, one line on one monster or none. It started
 * 480 ms after a swingO Power Strike and 420 ms after a stabO1 one (attack speed 3): 40% of the way from the trigger's
 * hit to the end of its animation (478 and 430 there). Bows and crossbows draw no final swing, so archers' Final
 * Attacks are not sent; Dawn Warrior's needs its buff on, as the client checks.
 */
public final class AgentFinalAttackProc {
    /** Where in the trigger's recovery (its hit to the end of its animation) the Final Attack starts. */
    private static final double START_INTO_RECOVERY = config.AgentTuning.doubleValue("server.agents.capabilities.combat.AgentFinalAttackProc.START_INTO_RECOVERY");
    /** The Final Attacks that are buffs (StatEffect.isCygnusFA): the client checks theirs is on. */
    private static final Set<Integer> BUFFED_FINAL_ATTACKS = Set.of(DawnWarrior.FINAL_ATTACK, WindArcher.FINAL_ATTACK);

    private AgentFinalAttackProc() {
    }

    /** A Final Attack rolled after a swing: what it swings, from where it faces, and when it starts. */
    record Rolled(int skillId, int level, AgentAttackExecutionProvider.FinalAttackSwing swing, int stance, int speed,
                  int mapId, int startMs) {
    }

    /**
     * After a committed swing: when {@code swing} sets off a Final Attack the Agent has and the roll comes up, the Agent
     * stays in the attack through it, and it is sent when due, its damage credited to the swing's learner decision.
     */
    static void afterSwing(AgentRuntimeEntry entry, Character bot, AgentAttackPlan swing) {
        Rolled rolled = roll(bot, swing, chance());
        if (rolled == null) {
            return;
        }
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, rolled.startMs() + rolled.swing().animationMs());
        long decision = AgentCombatLearner.decisions(entry);
        int lockClears = AgentCombatCooldownStateRuntime.attackLockClears(entry);
        AgentCombatRuntime.afterDelay(entry, rolled.startMs(), () -> send(entry, bot, rolled, decision, lockClears));
    }

    /** The roll against a Final Attack's prop, 0 to 1. */
    static double chance() {
        return Math.random();
    }

    /** The Final Attack {@code swing} sets off with a roll of {@code roll} (0 to 1), or null for none. */
    static Rolled roll(Character bot, AgentAttackPlan swing, double roll) {
        if (bot == null || swing == null || swing.skillId <= 0) {
            return null;
        }
        Skill trigger = SkillFactory.getSkill(swing.skillId);
        int finalAttackId = trigger == null || trigger.getFinalAttacks().isEmpty() ? 0
                : finalAttackFor(trigger.getFinalAttacks(), weaponType(bot), bot::getSkillLevel);
        Skill finalAttack = finalAttackId == 0 ? null : SkillFactory.getSkill(finalAttackId);
        if (finalAttack == null) {
            return null;
        }
        int level = bot.getSkillLevel(finalAttackId);
        StatEffect effect = finalAttack.getEffect(level);
        if (BUFFED_FINAL_ATTACKS.contains(finalAttackId) && bot.getBuffedValue(BuffStat.FINALATTACK) == null) {
            return null; // the client follows up with a Cygnus Final Attack only while its buff is on
        }
        if (roll >= effect.getProp()) {
            return null;
        }
        AgentAttackExecutionProvider.FinalAttackSwing finalSwing = AgentAttackExecutionProvider.finalAttackSwing(bot);
        if (finalSwing == null) {
            return null;
        }
        return new Rolled(finalAttackId, level, finalSwing, swing.stance & 0x80, swing.speed, bot.getMapId(),
                startMs(swing.hitDelayMs, swing.cooldownMs));
    }

    /** The Final Attack of {@code finalAttacks} (a trigger's Skill.wz list) for {@code weaponType} the Agent has, or 0. */
    static int finalAttackFor(Map<Integer, Set<Integer>> finalAttacks, int weaponType, IntUnaryOperator levelOf) {
        for (Map.Entry<Integer, Set<Integer>> finalAttack : finalAttacks.entrySet()) {
            if (finalAttack.getValue().contains(weaponType) && levelOf.applyAsInt(finalAttack.getKey()) > 0) {
                return finalAttack.getKey();
            }
        }
        return 0;
    }

    /** When after the trigger the Final Attack starts: 40% of the way from its hit to the end of its animation. */
    static int startMs(int triggerHitDelayMs, int triggerAnimationMs) {
        int hit = Math.max(0, triggerHitDelayMs);
        return hit + (int) Math.round(START_INTO_RECOVERY * Math.max(0, triggerAnimationMs - hit));
    }

    /** The equipped weapon's type as Skill.wz writes it (item id / 10000 % 100, 31 a one-handed axe); 0 without one. */
    static int weaponType(Character bot) {
        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        Item weapon = equipped == null ? null : equipped.getItem((short) -11);
        return weapon == null ? 0 : weapon.getItemId() / 10000 % 100;
    }

    /**
     * Sends {@code rolled} when it is due: from where the Agent stands then, at whatever its final swing reaches.
     * {@code decision} is the learner decision its trigger opened, {@code lockClears} the attack locks cut short before
     * it. The Final Attack is part of its trigger's animation, which the trigger's attack lock stands for: once that
     * lock is gone (a knockback, a death or a reset cleared it, or it ran out) the Final Attack goes with it, so it never
     * lands after the Agent's next attack.
     */
    static void send(AgentRuntimeEntry entry, Character bot, Rolled rolled, long decision, int lockClears) {
        if (!AgentCombatCooldownStateRuntime.hasAttackCooldown(entry)
                || AgentCombatCooldownStateRuntime.attackLockClears(entry) != lockClears) {
            return;
        }
        if (bot.getMapId() != rolled.mapId() || bot.getHp() <= 0 || bot.getSkillLevel(rolled.skillId()) <= 0) {
            return;
        }
        boolean facingLeft = (rolled.stance() & 0x80) != 0;
        Rectangle hitBox = AgentAttackExecutionProvider.closeRangeWeaponActionHitBox(
                bot, rolled.swing().action(), facingLeft);
        if (hitBox == null) {
            return;
        }
        StatEffect effect = SkillFactory.getSkill(rolled.skillId()).getEffect(rolled.level());
        List<Monster> targets = nearest(bot.getPosition(), hitBox, AgentMapPerception.monsters(bot.getMap()),
                Math.max(1, effect.getMobCount()),
                candidate -> AgentCombatAttackRuntime.mayFollowUpOn(entry, bot, candidate));
        AgentAttackExecutionProvider.FinalAttackSwing swing = rolled.swing();
        AgentAttackPlan plan = new AgentAttackPlan(rolled.skillId(), rolled.level(),
                Math.max(1, effect.getAttackCount()), hitBox, targets, AgentAttackRoute.CLOSE, 0,
                swing.bodyActionId(), swing.bodyActionId(), rolled.stance(), rolled.speed(), swing.hitDelayMs(),
                swing.animationMs(), AgentCombatWeaponPolicy.damageWeaponTypeForAction(rolled.skillId(),
                AgentAttackExecutionProvider.getEquippedWeaponType(bot), swing.action()));
        AgentCombatAttackRuntime.commitFollowUp(entry, bot, plan,
                targets.stream().map(target -> swing.hitDelayMs()).toList(), decision);
    }

    /** Up to {@code limit} eligible monsters inside {@code hitBox}, nearest to {@code origin} first. */
    static List<Monster> nearest(Point origin, Rectangle hitBox, Iterable<Monster> candidates, int limit,
                                 Predicate<Monster> eligible) {
        List<Monster> inside = new ArrayList<>();
        for (Monster candidate : candidates) {
            if (candidate != null && candidate.getPosition() != null && eligible.test(candidate)
                    && AgentCombatHitboxIntersection.intersectsMonster(hitBox, candidate)) {
                inside.add(candidate);
            }
        }
        inside.sort(Comparator.comparingDouble(candidate -> candidate.getPosition().distanceSq(origin)));
        return List.copyOf(inside.subList(0, Math.min(limit, inside.size())));
    }
}

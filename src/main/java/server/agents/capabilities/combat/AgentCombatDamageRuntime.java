package server.agents.capabilities.combat;

import server.agents.capabilities.movement.AgentMovementBroadcastService;
import server.agents.capabilities.movement.AgentMovementTimers;
import server.agents.capabilities.mobcontrol.AgentMobPhysicsConfig;

import client.BuffStat;
import client.Character;
import server.agents.capabilities.movement.AgentKnockbackMovementService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.partyquest.kpq.AgentKpqKnockbackResistancePolicy;
import server.agents.capabilities.partyquest.kpq.AgentKpqKnockbackDirectionPolicy;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.combat.PhysicalContactDamagePolicy;
import server.combat.ServerMobDamageService;
import server.life.Monster;
import server.life.autonomy.BossAction;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

public final class AgentCombatDamageRuntime {
    private AgentCombatDamageRuntime() {
    }

    /** Applies the client-side hit reaction that a headless Agent cannot simulate locally. */
    public static void reactToServerAttack(
            Character agent, Monster attacker, int damageDirection) {
        if (agent == null) return;
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
        int airVelocityX = AgentMobKnockbackPolicy.airVelocityForDamageDirection(
                damageDirection,
                AgentMobPhysicsConfig.cfg.KNOCKBACK_HSPEED,
                AgentMovementPhysicsConfig.configuredMovementTickMs());
        react(entry, agent, attacker, airVelocityX,
                ThreadLocalRandom.current().nextFloat());
    }

    /**
     * The other half of the client-side reaction a headless Agent cannot simulate: skills whose
     * animation carries the attacker along the ground (see {@link AgentCombatDashPolicy}) move it
     * the skill's distance, in the direction the attack faced ({@code stance} bit 0x80 = left).
     */
    static void dashAfterAttack(AgentRuntimeEntry entry, Character bot, int skillId, int skillLevel, int stance) {
        client.Skill skill = skillId <= 0 ? null : server.agents.integration.AgentSkillGatewayRuntime.skills().getSkill(skillId);
        int distance = skill == null || skillLevel <= 0 ? 0
                : AgentCombatDashPolicy.dashDistancePx(skillId, skill.getEffect(skillLevel));
        if (distance == 0) {
            return;
        }
        boolean facingLeft = (stance & 0x80) != 0;
        AgentKnockbackMovementService.beginGroundDash(entry, bot, facingLeft ? -distance : distance);
    }

    /** Inside an invincibility window the client grants after certain hits (Corkscrew Blow): no touch or magic damage. */
    public static boolean invincible(Character agent) {
        return agent != null && AgentClientSkillRules.invincible(
                AgentRuntimeRegistry.findByAgentCharacterId(agent.getId()));
    }

    public static void applyMobHit(AgentRuntimeEntry entry, Character bot, Monster mob, AgentCombatConfig.Config config) {
        if (AgentLpqSessionRegistry.suppressesDarkSightRoomTouch(bot) || AgentClientSkillRules.invincible(entry)) {
            return;
        }
        if (AgentMobTouchPolicy.ignoresTouchDamage(mob.getId())) {
            return;
        }
        int dmg = AgentDefenseDataProvider.getInstance().rollPhysicalTouchDamage(bot, mob);
        AgentMobKnockbackPolicy.MobHitKnockback kb =
                AgentMobKnockbackPolicy.resolveMobHitKnockback(
                        bot.getPosition(), mob.getPosition(), AgentMobPhysicsConfig.cfg.KNOCKBACK_HSPEED,
                        AgentMovementPhysicsConfig.configuredMovementTickMs());
        int airVelX = AgentKpqKnockbackDirectionPolicy.adjustAirVelocityX(bot, kb.airVelX());
        int direction = airVelX == 0 ? kb.direction() : airVelX < 0 ? 0 : 1;
        applyDamage(entry, bot, dmg, -1, mob, direction, airVelX, config);
    }

    public static void tickMobDamage(AgentRuntimeEntry entry, Character bot, AgentCombatConfig.Config config,
                                     IntUnaryOperator cooldownTickDown) {
        Point botPos = bot.getPosition();
        try {
            if (AgentCombatCooldownStateRuntime.hasMobHitCooldown(entry)) {
                AgentCombatCooldownStateRuntime.tickMobHitCooldown(entry, cooldownTickDown);
                return;
            }
            if (bot.getHp() <= 0) return;

            for (Monster mob : server.agents.perception.AgentMapPerception.monsters(bot.getMap())) {
                if (!AgentCombatTargetEligibilityPolicy.isHostileLivingMonster(mob)) continue;
                if (AgentMobTouchPolicy.ignoresTouchDamage(mob.getId())) continue;
                if (AgentMobTouchRuntime.isMobTouchingAgent(entry, bot, mob, config.MOB_TOUCH_SWEEP_HEIGHT)) {
                    applyMobHit(entry, bot, mob, config);
                    return;
                }
            }
        } finally {
            AgentMobTouchRuntime.rememberMobTouchCheck(entry, bot, botPos);
        }
    }

    public static void applyFallDamage(AgentRuntimeEntry entry, Character bot,
                                       float fallDistancePx,
                                       AgentCombatConfig.Config config) {
        if (bot.getHp() <= 0) return;
        if (AgentCombatCooldownStateRuntime.hasMobHitCooldown(entry)) return;
        int dmg = AgentFallDamageCalculator.fallDamageFromDistance(fallDistancePx);
        if (dmg <= 0) return;
        int dirSign = AgentMovementStateRuntime.facingDirectionSign(entry);
        int airVelX = Math.round(-dirSign
                * AgentMobKnockbackPolicy.scaledOpenStoryStep(
                AgentMobPhysicsConfig.cfg.KNOCKBACK_HSPEED,
                AgentMovementPhysicsConfig.configuredMovementTickMs()));
        applyDamage(entry, bot, dmg, -3, null, 0, airVelX, config);
    }

    private static void applyDamage(AgentRuntimeEntry entry, Character bot, int dmg,
                                    int damageFrom, Monster attacker,
                                    int broadcastDirection, int knockbackAirVelX,
                                    AgentCombatConfig.Config config) {
        int monsterId = attacker == null ? 0 : attacker.getId();

        if (PhysicalContactDamagePolicy.isNegated(
                damageFrom, bot.getBuffedValue(BuffStat.DARKSIGHT) != null)) {
            dmg = 0;
        }

        if (dmg <= 0) {
            AgentPacketGatewayRuntime.packets().broadcastDamagePlayer(
                    bot, damageFrom, monsterId, 0, 0, broadcastDirection, false, 0, false, 0, 0, 0);
            AgentCombatCooldownStateRuntime.setMobHitCooldownMs(
                    entry,
                    AgentMovementTimers.delayAfterCurrentTick(config.MOB_HIT_COOLDOWN_MS));
            AgentCombatAlertRuntime.markAlerted(entry);
            return;
        }

        AgentIncomingDamagePolicy.DamageSplit split = AgentIncomingDamagePolicy.splitMagicGuard(
                dmg, bot.getBuffedValue(BuffStat.MAGIC_GUARD), bot.getMp());
        bot.addMPHPAndTriggerAutopot(-split.hpLoss(), -split.mpLoss());

        AgentPacketGatewayRuntime.packets().broadcastDamagePlayer(
                bot, damageFrom, monsterId, dmg, 0, broadcastDirection, false, 0, false, 0, 0, 0);

        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(
                entry,
                AgentMovementTimers.delayAfterCurrentTick(config.MOB_HIT_COOLDOWN_MS));
        if (bot.getHp() <= 0) {
            AgentCombatDeathRuntime.enterDeadState(entry, bot, true);
            return;
        }
        react(
                entry, bot, attacker, knockbackAirVelX,
                ThreadLocalRandom.current().nextFloat());
    }

    private static void react(
            AgentRuntimeEntry entry,
            Character agent,
            Monster attacker,
            int airVelocityX,
            float randomRoll) {
        if (entry == null || agent == null || agent.getPosition() == null || agent.getHp() <= 0) {
            return;
        }
        AgentCombatAlertRuntime.markAlerted(entry);
        if (!AgentMobKnockbackPolicy.shouldApplyMobKnockback(
                AgentMovementStateRuntime.climbing(entry),
                agent.getHp(),
                agent.getBuffedValue(BuffStat.STANCE),
                AgentKpqKnockbackResistancePolicy.resistancePercent(agent),
                randomRoll)) {
            preferAggressor(entry, agent, attacker);
            return;
        }

        AgentCombatActionStateRuntime.clearActionState(entry);
        preferAggressor(entry, agent, attacker);
        if (AgentMovementStateRuntime.inAir(entry)) {
            AgentKnockbackMovementService.applyAirKnockback(entry, agent, airVelocityX);
        } else {
            AgentKnockbackMovementService.beginKnockback(
                    entry,
                    agent,
                    agent.getPosition(),
                    -AgentMobKnockbackPolicy.scaledOpenStoryStep(
                            AgentMobPhysicsConfig.cfg.KNOCKBACK_VFORCE,
                            AgentMovementPhysicsConfig.configuredMovementTickMs()),
                    airVelocityX);
        }
        AgentMovementBroadcastService.broadcastMovement(entry);
    }

    private static void preferAggressor(
            AgentRuntimeEntry entry, Character agent, Monster attacker) {
        if (attacker == null || !attacker.isAlive() || attacker.getMap() != agent.getMap()
                || !AgentCombatObjectiveTargetStateRuntime.allows(entry, attacker.getId())) {
            return;
        }
        AgentGrindTargetStateRuntime.setTarget(entry, attacker);
    }

    /**
     * Applies one boss attack that a human client played for a part it controls (the boss attack mirror). Runs on the
     * Agent's own mailbox. The Agent must still be alive, visible, on the part's map and in its event. Under the
     * shared window ({@code sharedWindow}) a hit inside the touch-damage cooldown is skipped, and a hit that does
     * damage starts that cooldown. A jump attack misses an Agent in the air. {@code show} is asked only for a hit that
     * is applied: false sends no damage packet.
     */
    public static MirroredHit applyMirroredHit(AgentRuntimeEntry entry, Character bot, Monster part,
                                               BossAction.OrdinaryAttack attack, int damagePercent,
                                               BooleanSupplier show, boolean jumpAttack, boolean sharedWindow) {
        MapleMap map = part == null ? null : part.getMap();
        if (entry == null || bot == null || attack == null || map == null || !part.isAlive() || !bot.isAlive()
                || bot.isHidden() || bot.getMap() != map || bot.getEventInstance() != map.getEventInstance()) {
            return MirroredHit.NONE;
        }
        if (sharedWindow && AgentCombatCooldownStateRuntime.hasMobHitCooldown(entry)) {
            return MirroredHit.WINDOW;
        }
        if (jumpAttack && AgentMovementStateRuntime.inAir(entry)) {
            return MirroredHit.AIRBORNE;
        }
        int damage = ServerMobDamageService.applyOrdinaryAttack(part, bot, attack, damagePercent, show.getAsBoolean());
        if (sharedWindow && damage > 0) {
            AgentCombatCooldownStateRuntime.setMobHitCooldownMs(
                    entry,
                    AgentMovementTimers.delayAfterCurrentTick(AgentCombatConfig.cfg.MOB_HIT_COOLDOWN_MS));
        }
        return MirroredHit.APPLIED;
    }

    /** What one mirrored boss attack did to an Agent ({@link #applyMirroredHit}). */
    public enum MirroredHit {
        /** The hit was rolled and applied; a dodge or a 0 counts too. */
        APPLIED,
        /** Skipped: the Agent was still inside its shared hit window. */
        WINDOW,
        /** Skipped: a jump attack, and the Agent was in the air. */
        AIRBORNE,
        /** Skipped: the Agent or the part is no longer alive, on the same map or in the same event. */
        NONE
    }
}

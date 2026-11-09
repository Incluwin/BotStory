package server.pvp;

import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatHitCounter;
import server.agents.capabilities.combat.AgentCombatSkillClassifier;
import server.agents.capabilities.combat.AgentCombatWeaponPolicy;
import server.agents.capabilities.combat.AgentSkillAttackPlanRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A bot's side of a duel, V1 (docs/PVP_PLAN.md section 4): priors only, no learning yet (that is V2). While the duel
 * holds it, its own following and grinding are off; it thinks every 300-600 ms (a person's reaction, not a tick's),
 * swings its strongest attack skill whose box reaches the opponent, and otherwise walks to that skill's reach. Every
 * action runs on the Agent's own thread through its mailbox.
 */
final class DuelBrain {
    private static final Logger log = LoggerFactory.getLogger(DuelBrain.class);
    /** How many of its attack skills a bot tries, strongest first. */
    static final int SKILLS_TRIED = 4;
    /** How far inside a skill's reach the bot stands, so a small step does not take it out of range. */
    static final int REACH_MARGIN_PX = 20;
    static final int MIN_STAND_PX = 30;

    private DuelBrain() {
    }

    /** What the duel remembers of a bot: the modes to give back, its attack skills, when it thinks next. */
    static final class Mind {
        volatile boolean wasFollowing;
        volatile boolean wasGrinding;
        volatile List<Integer> skills = List.of();
        volatile long nextThinkMs;
        volatile int thinkMs = 450;
    }

    static void enter(DuelField.Fighter fighter) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(fighter.chr.getId());
        if (entry == null) {
            return;
        }
        Mind mind = fighter.mind;
        mind.thinkMs = 300 + ThreadLocalRandom.current().nextInt(300);
        AgentMailboxRuntime.dispatch(entry, e -> {
            mind.wasFollowing = AgentModeStateRuntime.following(e);
            mind.wasGrinding = AgentModeStateRuntime.grinding(e);
            AgentModeStateRuntime.setFollowing(e, false);
            AgentModeStateRuntime.setGrinding(e, false);
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(e);
            mind.skills = attackSkills(fighter.chr);
            return null;
        });
    }

    static void leave(DuelField.Fighter fighter) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(fighter.chr.getId());
        if (entry == null) {
            return;
        }
        Mind mind = fighter.mind;
        AgentMailboxRuntime.dispatch(entry, e -> {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(e);
            AgentModeStateRuntime.setFollowing(e, mind.wasFollowing);
            AgentModeStateRuntime.setGrinding(e, mind.wasGrinding);
            return null;
        });
    }

    static void tick(DuelField.Fighter self, DuelField.Fighter foe, long now) {
        Mind mind = self.mind;
        if (now < mind.nextThinkMs) {
            return;
        }
        mind.nextThinkMs = now + mind.thinkMs + ThreadLocalRandom.current().nextInt(150);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(self.chr.getId());
        if (entry == null) {
            return;
        }
        Character bot = self.chr;
        Character target = foe.chr;
        AgentMailboxRuntime.dispatch(entry, e -> {
            try {
                act(e, bot, target, mind.skills);
            } catch (RuntimeException failure) {
                log.warn("PvP duel brain {} against {} failed", bot.getName(), target.getName(), failure);
            }
            return null;
        });
    }

    private static void act(AgentRuntimeEntry entry, Character bot, Character target, List<Integer> skills) {
        Point aim = target.getPosition();
        Point at = bot.getPosition();
        if (aim == null || at == null || bot.getMap() != target.getMap()) {
            return;
        }
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        Rectangle body = PvpHitResolver.hurtbox(aim, 0);
        int reach = 0;
        for (int skillId : skills) {
            AgentAttackPlan plan = AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, aim, skillId);
            if (plan == null || plan.hitBox == null) {
                continue;
            }
            if (plan.hitBox.intersects(body)) {
                gateway.stop(entry);
                gateway.facePosition(bot, aim);
                var result = AgentCombatAttackRuntime.attackAtNothing(entry, bot, plan);
                if (result.committed() || result.status() == server.agents.capabilities.combat
                        .AgentAttackTransactionResult.Status.DEFERRED) {
                    return;   // swung, or its cooldown is still running: either way it stays put
                }
                continue;
            }
            reach = Math.max(reach, reachToward(plan.hitBox, at, aim));
        }
        int stand = Math.max(MIN_STAND_PX, (reach > 0 ? reach : MIN_STAND_PX + REACH_MARGIN_PX) - REACH_MARGIN_PX);
        Point candidate = new Point(aim.x + (at.x >= aim.x ? stand : -stand), aim.y);
        Point ground = gateway.groundPoint(bot.getMap(), candidate);
        gateway.navigate(entry, ground != null ? ground : candidate, true);
    }

    /** How far from the bot, toward the target, a swing's box reaches. */
    static int reachToward(Rectangle box, Point from, Point target) {
        return target.x >= from.x ? Math.max(0, box.x + box.width - from.x) : Math.max(0, from.x - box.x);
    }

    /** The bot's attack skills its weapon can use, strongest per swing first (the attack cache's own filter). */
    static List<Integer> attackSkills(Character bot) {
        WeaponType weapon = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        List<int[]> scored = new ArrayList<>();
        for (Skill skill : bot.getSkills().keySet()) {
            int level = bot.getSkillLevel(skill);
            if (level <= 0) {
                continue;
            }
            StatEffect effect = skill.getEffect(level);
            if (AgentCombatSkillClassifier.classifySkillCacheBucket(skill, effect)
                    != AgentCombatSkillClassifier.SkillCacheBucket.ACTIVE_ATTACK
                    || !AgentCombatWeaponPolicy.canUseSkillWithWeapon(skill.getId(), weapon)
                    || AgentCombatSkillClassifier.requiresPreAction(skill.getId())) {
                continue;
            }
            int perSwing = Math.max(effect.getDamage(), effect.getMatk())
                    * Math.max(1, AgentCombatHitCounter.effectiveHitCount(effect));
            scored.add(new int[] {skill.getId(), perSwing});
        }
        scored.sort(Comparator.comparingInt((int[] s) -> s[1]).reversed());
        List<Integer> skills = new ArrayList<>();
        for (int i = 0; i < Math.min(SKILLS_TRIED, scored.size()); i++) {
            skills.add(scored.get(i)[0]);
        }
        return skills;
    }
}

package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import client.inventory.WeaponType;
import server.StatEffect;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-16 (P20, Pink Bean's Hugin and Munin): whether an Agent's attacks would reach a monster's body if it stood
 * somewhere else, as a boss fight picks where a member stands. Every box the Agent plans is anchored at its feet, so a
 * box planned where it stands, moved by the step to the other point, is the box it would swing there, facing the body
 * as the planner faces it (AgentCombatAimPointPolicy). A skill counts when the Agent knows it and its weapon can use
 * it; cooldown, MP and seal do not, since this answers where to stand, not what to swing now. A close-range skill that
 * names no action of its own swings one the planner samples from the weapon's actions, so the union of their boxes
 * counts. The weapon's own swing counts too, the planner's fallback.
 */
public final class AgentCombatReach {
    private AgentCombatReach() {
    }

    /** True when one of the Agent's attack skills, or its weapon's swing, would hit {@code target} from there. */
    public static boolean reachesFrom(AgentRuntimeEntry entry, Character bot, Monster target, Point stand) {
        return entry != null && reachesFrom(bot, AgentCombatPlanRuntime.attackSkillIds(entry), target, stand);
    }

    /** {@link #reachesFrom(AgentRuntimeEntry, Character, Monster, Point)} over the attack skills {@code skillIds}. */
    public static boolean reachesFrom(Character bot, List<Integer> skillIds, Monster target, Point stand) {
        Point at = bot == null ? null : bot.getPosition();
        Point origin = target == null ? null : target.getPosition();
        if (at == null || origin == null || stand == null) {
            return false;
        }
        Rectangle body = AgentMobHitboxProvider.getInstance().getMobBounds(target);
        boolean facingLeft = AgentCombatAimPointPolicy.nearestPoint(stand, body, origin).x < stand.x;
        for (Rectangle box : boxes(bot, skillIds, facingLeft)) {
            if (box == null) {
                continue;
            }
            box.translate(stand.x - at.x, stand.y - at.y);
            if (AgentCombatHitboxIntersection.intersectsMonsterBounds(box, body, origin)) {
                return true;
            }
        }
        return false;
    }

    /** The Agent's attack boxes where it stands, facing left or right: each usable skill's, then its weapon's swing. */
    private static List<Rectangle> boxes(Character bot, List<Integer> skillIds, boolean facingLeft) {
        List<Rectangle> boxes = new ArrayList<>();
        WeaponType weapon = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        SkillGateway skills = AgentSkillGatewayRuntime.skills();
        for (int skillId : skillIds) {
            Skill skill = skills.getSkill(skillId);
            int level = skill == null ? 0 : bot.getSkillLevel(skill);
            StatEffect effect = level <= 0 ? null : skill.getEffect(level);
            if (effect == null || AgentCombatSkillHitboxPolicy.isStrikePointAnchoredAoeSkill(skillId)
                    || !AgentCombatWeaponPolicy.canUseAttackSkillWithWeapon(skillId, weapon)) {
                continue;
            }
            boolean facing = AgentCombatSkillHitboxPolicy.isReverseFacingSkill(skillId) != facingLeft;
            String action = skill.resolveAnimationAction(level, AgentAttackExecutionProvider.isTwoHandedWeapon(bot));
            boxes.add(effect.hasBoundingBox() ? effect.calculateBoundingBox(bot.getPosition(), facing)
                    : AgentCombatSkillHitboxPolicy.fallbackSkillHitBox(effect, bot, facing,
                            AgentAttackExecutionProvider.determineSkillRoute(bot, skillId), skillId, action));
        }
        AgentAttackRoute route = AgentAttackExecutionProvider.determineBasicWeaponRoute(weapon);
        Rectangle swing = route == AgentAttackRoute.CLOSE
                ? AgentAttackExecutionProvider.closeRangeWeaponActionHitBox(bot, null, facingLeft) : null;
        boxes.add(swing != null ? swing : AgentCombatRangePolicy.basicWeaponReachRect(bot, facingLeft, route));
        return boxes;
    }
}

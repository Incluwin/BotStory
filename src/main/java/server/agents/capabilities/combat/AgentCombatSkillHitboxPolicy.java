package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import constants.skills.Crossbowman;
import constants.skills.Hermit;
import constants.skills.Hunter;
import constants.skills.Brawler;
import constants.skills.NightWalker;
import server.StatEffect;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.life.Monster;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.Set;

public final class AgentCombatSkillHitboxPolicy {
    // Pierce-line projectiles do NOT use the generic +/-50 px vertical band: the actual
    // client projectile sprite is much thinner (Iron Arrow) or much taller (Avenger).
    private record ProjectileVerticalReach(int yAbove, int yBelow) { }

    private static final Map<Integer, ProjectileVerticalReach> PIERCE_LINE_PROJECTILE_REACH = Map.of(
            Crossbowman.IRON_ARROW, new ProjectileVerticalReach(32, -28),
            Hermit.AVENGER, new ProjectileVerticalReach(60, 0),
            NightWalker.AVENGER, new ProjectileVerticalReach(60, 0)
    );

    private static final Set<Integer> STRIKE_POINT_ANCHORED_AOE_SKILL_IDS = Set.of(
            Hunter.ARROW_BOMB
    );

    /** WZ box lies behind the caster (lt.x >= 0): the Agent must face away from the target to land it. */
    private static final Set<Integer> REVERSE_FACING_SKILL_IDS = Set.of(
            Brawler.BACK_SPIN_BLOW,
            15101002  // Thunder Breaker Backspin Blow (no constant in ThunderBreaker)
    );

    public static boolean isReverseFacingSkill(int skillId) {
        return REVERSE_FACING_SKILL_IDS.contains(skillId);
    }

    private AgentCombatSkillHitboxPolicy() {
    }

    public static Rectangle calculateSkillHitBox(StatEffect effect, Character agent, Monster primaryTarget,
                                                 AgentAttackRoute route, int skillId, String action) {
        Point aimPoint = AgentCombatAimPointPolicy.aimPoint(agent, primaryTarget);
        boolean facingLeft = aimPoint.x < agent.getPosition().x;
        if (isReverseFacingSkill(skillId)) {
            facingLeft = !facingLeft;
        }
        if (effect.hasBoundingBox()) {
            Point anchor = isStrikePointAnchoredAoeSkill(skillId)
                    ? aimPoint
                    : agent.getPosition();
            return effect.calculateBoundingBox(anchor, facingLeft);
        }

        return fallbackSkillHitBox(effect, agent, facingLeft, route, skillId, action);
    }

    public static boolean isStrikePointAnchoredAoeSkill(int skillId) {
        return STRIKE_POINT_ANCHORED_AOE_SKILL_IDS.contains(skillId);
    }

    /**
     * 2026-10-16 (P20, Orrin): a close-range skill with no level lt/rb that stores its own afterimage box for the
     * wielded weapon ({@link Skill#getAfterimageBox}) strikes that box, as the client draws it, not the weapon's own
     * swing; null when it stores none. Blast reaches 150 px ahead and 60 px up. Its stand-in, the sword's swing
     * (106 px, 58 px up), ended a row short of Solomon's and Rex's bodies, which start 58 px above the floor (they
     * stand 2 px above it), so a Paladin on the floor at Pink Bean planned nothing and swung 5 times a minute.
     */
    static Rectangle skillAfterimageHitBox(Skill skill, String weaponAfterimage, Point at, boolean facingLeft) {
        Rectangle box = skill == null || weaponAfterimage == null || at == null
                ? null : skill.getAfterimageBox(weaponAfterimage);
        if (box == null) {
            return null;
        }
        int left = facingLeft ? box.x : -(box.x + box.width);
        return new Rectangle(at.x + left, at.y + box.y, box.width, box.height);
    }

    public static Rectangle fallbackCloseRangeSkillHitBox(StatEffect effect, Character agent, String action,
                                                          boolean facingLeft) {
        if (effect == null || agent == null) {
            return null;
        }

        Rectangle weaponBox = AgentAttackExecutionProvider.closeRangeWeaponActionHitBox(agent, action, facingLeft);
        if (weaponBox != null) {
            return weaponBox;
        }

        Point origin = agent.getPosition();
        int horizontalRange = Math.max(AgentCombatConfig.cfg.ATTACK_RANGE_X, effect.getRange());
        int top = origin.y - AgentCombatConfig.cfg.ATTACK_RANGE_Y;
        int height = AgentCombatConfig.cfg.ATTACK_RANGE_Y + AgentCombatConfig.cfg.ATTACK_DOWN_MAX;
        int left = facingLeft ? origin.x - horizontalRange : origin.x;
        return new Rectangle(left, top, horizontalRange, height);
    }

    public static Rectangle fallbackSkillHitBox(StatEffect effect, Character agent, boolean facingLeft,
                                                AgentAttackRoute route, int skillId, String action) {
        if (route == AgentAttackRoute.CLOSE) {
            Rectangle own = effect == null || agent == null ? null : skillAfterimageHitBox(
                    AgentSkillGatewayRuntime.skills().getSkill(skillId),
                    AgentAttackExecutionProvider.equippedWeaponAfterimage(agent), agent.getPosition(), facingLeft);
            return own != null ? own : fallbackCloseRangeSkillHitBox(effect, agent, action, facingLeft);
        }
        if (effect == null || agent == null) {
            return null;
        }

        ProjectileVerticalReach reach = PIERCE_LINE_PROJECTILE_REACH.get(skillId);
        if (reach != null) {
            return AgentProjectileHitbox.clientProjectileHitBox(agent, facingLeft,
                    AgentProjectileHitbox.projectileRangeScale(effect), reach.yAbove(), reach.yBelow());
        }
        return AgentProjectileHitbox.clientProjectileHitBox(agent, facingLeft,
                AgentProjectileHitbox.projectileRangeScale(effect));
    }
}

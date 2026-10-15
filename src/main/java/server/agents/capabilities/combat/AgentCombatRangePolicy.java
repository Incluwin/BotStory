package server.agents.capabilities.combat;

import client.Character;
import client.inventory.WeaponType;
import java.awt.Point;
import java.awt.Rectangle;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

public final class AgentCombatRangePolicy {
    private AgentCombatRangePolicy() {
    }

    public static boolean isBasicAttackInRange(Point botPos, Point targetPos) {
        if (botPos == null || targetPos == null) {
            return false;
        }
        int dx = Math.abs(targetPos.x - botPos.x);
        int dy = botPos.y - targetPos.y;
        boolean inHorizontalRange = dx <= AgentCombatConfig.cfg.ATTACK_RANGE_X;
        boolean inVerticalRange = dy >= -AgentCombatConfig.cfg.ATTACK_DOWN_MAX
                && dy <= AgentCombatConfig.cfg.ATTACK_RANGE_Y;
        return inHorizontalRange && inVerticalRange;
    }

    public static boolean isTargetInAttackRange(Rectangle attackHitBox,
                                                Monster target,
                                                Point agentPosition,
                                                Point targetPosition) {
        if (attackHitBox != null) {
            return AgentCombatHitboxIntersection.intersectsMonster(attackHitBox, target);
        }
        return isBasicAttackInRange(agentPosition, targetPosition);
    }

    public static boolean isTargetInAttackRange(AgentAttackPlan attackPlan, Character agent, Monster target) {
        if (attackPlan == null || agent == null || target == null) {
            return false;
        }
        return isTargetInAttackRange(
                attackPlan.hasHitBox() ? attackPlan.hitBox : null,
                target,
                agent.getPosition(),
                target.getPosition());
    }

    public static Rectangle basicWeaponReachRect(Character bot, boolean facingLeft, AgentAttackRoute route) {
        if (bot == null || bot.getPosition() == null) {
            return null;
        }
        if (route == AgentAttackRoute.RANGED) {
            return AgentProjectileHitbox.clientProjectileHitBox(bot, facingLeft, 1.0f);
        }
        if (route == AgentAttackRoute.CLOSE) {
            Point origin = bot.getPosition();
            int left = facingLeft ? origin.x - AgentCombatConfig.cfg.ATTACK_RANGE_X : origin.x;
            int top = origin.y - AgentCombatConfig.cfg.ATTACK_RANGE_Y;
            int height = AgentCombatConfig.cfg.ATTACK_RANGE_Y + AgentCombatConfig.cfg.ATTACK_DOWN_MAX;
            return new Rectangle(left, top, AgentCombatConfig.cfg.ATTACK_RANGE_X, height);
        }
        return null;
    }

    public static boolean isPrimaryReachableByBasicWeapon(Character bot, Monster target, AgentAttackRoute route) {
        if (bot == null || target == null || bot.getPosition() == null || target.getPosition() == null) {
            return false;
        }
        if (route != AgentAttackRoute.RANGED && route != AgentAttackRoute.CLOSE) {
            return true;
        }
        Point aimPoint = AgentCombatAimPointPolicy.aimPoint(bot, target);
        boolean facingLeft = aimPoint.x < bot.getPosition().x;
        Rectangle basicReach = basicWeaponReachRect(bot, facingLeft, route);
        return basicReach != null && AgentCombatHitboxIntersection.intersectsMonster(basicReach, target);
    }

    /**
     * 2026-10-15 (P20 run 4): whether a close-range plan on a boss whose body sits apart from its origin (Pink Bean's
     * Solomon and Rex, Horntail's parts) is swung from where the Agent stands. Their bodies are wide and raised, so a
     * long, weak box (Shout: +-200 px, 30% x1) reaches one from where the main swing (Brandish: 150 px) does not, and a
     * Hero knocked back to the wall at x=-899 stood there Shouting for minutes at 1/20 of its damage. Such a swing
     * holds only within ATTACK_RANGE_X of the body; farther out the Agent walks in, unless its horizontal boundary
     * keeps it out (then it swings from where it is, as before). Every other plan and target holds wherever it is in
     * range.
     */
    public static boolean closeSwingHoldsHere(AgentRuntimeEntry entry, Character agent, AgentAttackPlan plan,
                                              Monster target) {
        if (plan == null || plan.route != AgentAttackRoute.CLOSE || agent == null || agent.getPosition() == null
                || target == null || !target.isBoss()) {
            return true;
        }
        Rectangle body = AgentMobHitboxProvider.getInstance().getMobBounds(target);
        if (!AgentCombatTargetPositionPolicy.isHorizontallyDetached(target.getPosition(), body)) {
            return true;
        }
        int reach = AgentCombatConfig.cfg.ATTACK_RANGE_X;
        int left = body.x;
        int right = body.x + Math.max(0, body.width - 1);
        int x = agent.getPosition().x;
        if (gapX(x, left, right) <= reach) {
            return true;
        }
        int stand = x < left ? left - reach : right + reach;
        int allowed = AgentPrimitiveCapabilityGatewayRuntime.gateway().clampHorizontalX(entry, agent.getMapId(), stand);
        return gapX(allowed, left, right) > reach;
    }

    private static int gapX(int x, int left, int right) {
        return x < left ? left - x : Math.max(0, x - right);
    }

    public static boolean canUseAttackPlanNow(boolean grounded, WeaponType weaponType, AgentAttackRoute route) {
        if (grounded) {
            return true;
        }
        if (route == AgentAttackRoute.RANGED) {
            return supportsMobileJumpAttack(weaponType);
        }
        return route != AgentAttackRoute.MAGIC;
    }

    public static boolean isTargetJumpable(AgentMovementProfile movementProfile,
                                           boolean closeRangeRoute,
                                           Point botPos,
                                           Point targetPos,
                                           double maxJumpHeightPx) {
        if (!closeRangeRoute || botPos == null || targetPos == null) {
            return false;
        }

        int dx = Math.abs(targetPos.x - botPos.x);
        if (dx > AgentCombatConfig.cfg.ATTACK_RANGE_X + AgentCombatConfig.cfg.ATTACK_JUMP_X_EXTRA) {
            return false;
        }

        int dy = botPos.y - targetPos.y;
        int maxJumpHeight = Math.max(AgentCombatConfig.cfg.ATTACK_JUMP_Y, (int) Math.ceil(maxJumpHeightPx));
        return dy > AgentCombatConfig.cfg.ATTACK_RANGE_Y && dy <= maxJumpHeight;
    }

    public static boolean isTargetJumpable(AgentMovementProfile movementProfile,
                                           WeaponType weaponType,
                                           AgentAttackRoute route,
                                           Point botPos,
                                           Point targetPos,
                                           double maxJumpHeightPx) {
        if (route == AgentAttackRoute.CLOSE) {
            return isTargetJumpable(
                    movementProfile, true, botPos, targetPos, maxJumpHeightPx);
        }
        if (route != AgentAttackRoute.RANGED || !supportsMobileJumpAttack(weaponType)
                || botPos == null || targetPos == null) {
            return false;
        }

        int dx = Math.abs(targetPos.x - botPos.x);
        if (dx > AgentProjectileHitbox.CLIENT_PROJECTILE_BASE_RANGE) {
            return false;
        }

        int dy = botPos.y - targetPos.y;
        int maxJumpHeight = Math.max(AgentCombatConfig.cfg.ATTACK_JUMP_Y,
                (int) Math.ceil(maxJumpHeightPx));
        int projectileTop = AgentProjectileHitbox.clientProjectileTop();
        return dy > projectileTop
                && dy <= projectileTop + maxJumpHeight;
    }

    public static boolean supportsMobileJumpAttack(WeaponType weaponType) {
        return weaponType == WeaponType.GUN || weaponType == WeaponType.CLAW;
    }

    public static boolean supportsSafeSpotSniping(WeaponType weaponType, AgentAttackRoute route) {
        if (route == AgentAttackRoute.RANGED) {
            return weaponType == WeaponType.BOW
                    || weaponType == WeaponType.CROSSBOW
                    || weaponType == WeaponType.CLAW
                    || weaponType == WeaponType.GUN;
        }
        return route == AgentAttackRoute.MAGIC
                && (weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF);
    }
}

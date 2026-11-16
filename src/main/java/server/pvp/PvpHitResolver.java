package server.pvp;

import client.Character;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import server.StatEffect;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatSkillHitboxPolicy;
import server.agents.capabilities.combat.AgentProjectileHitbox;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where an attack reaches and whom it meets (docs/PVP_PLAN.md section 4, V1.2). The v83 client tests hits as
 * rectangles (docs/bot-hitbox-client-gap-analysis.md), so this does too: the skill's own box, else the projectile
 * box, else the weapon's swing box, against each fighter's body box, with the lag rules of section 2.
 */
public final class PvpHitResolver {
    /** A standing character's body, from the foot point: about the width and height the client draws. */
    static final int BODY_HALF_WIDTH = 19;
    static final int BODY_HEIGHT = 62;
    /** A weapon with no afterimage box: 80 px ahead, 50 up, 20 down (AgentCombatConfig's ATTACK_RANGE defaults). */
    static final int FALLBACK_REACH_X = 80;
    static final int FALLBACK_UP = 50;
    static final int FALLBACK_DOWN = 20;

    private PvpHitResolver() {
    }

    /** One possible victim: whether a human plays it, and its recent positions. */
    public record Candidate(Character character, boolean human, PositionTrack track) { }

    /** The attack's box in world space, from the attacker's position and facing; null when it has no position. */
    public static Rectangle attackBox(Character attacker, AttackInfo attack, StatEffect effect) {
        return attackBox(attacker, attack.skill, effect, attack.ranged, attack.magic, (attack.stance & 0x80) != 0);
    }

    /**
     * The same box before the attack is sent: a bot's duel brain asks it to know whether a skill reaches from where it
     * stands ({@code facingLeft} as the packet would carry it).
     */
    public static Rectangle attackBox(Character attacker, int skillId, StatEffect effect, boolean ranged, boolean magic,
                                      boolean facingLeft) {
        Point at = attacker.getPosition();
        if (at == null) {
            return null;
        }
        if (skillId != 0 && AgentCombatSkillHitboxPolicy.isReverseFacingSkill(skillId)) {
            facingLeft = !facingLeft;
        }
        if (effect != null && effect.hasBoundingBox()) {
            return effect.calculateBoundingBox(at, facingLeft);
        }
        if (ranged || magic) {
            float scale = effect == null ? 1f : AgentProjectileHitbox.projectileRangeScale(effect);
            Rectangle projectile = AgentProjectileHitbox.clientProjectileHitBox(attacker, facingLeft, scale);
            if (projectile != null) {
                return projectile;
            }
        }
        Rectangle swing = AgentAttackExecutionProvider.closeRangeWeaponActionHitBox(attacker, null, facingLeft);
        return swing != null ? swing : fallbackBox(at, facingLeft);
    }

    static Rectangle fallbackBox(Point at, boolean facingLeft) {
        return new Rectangle(facingLeft ? at.x - FALLBACK_REACH_X : at.x, at.y - FALLBACK_UP,
                FALLBACK_REACH_X, FALLBACK_UP + FALLBACK_DOWN);
    }

    /** A body standing at {@code foot}, shrunk by {@code inset} px on each side (the top too, never the feet). */
    public static Rectangle hurtbox(Point foot, int inset) {
        int width = Math.max(2, 2 * BODY_HALF_WIDTH - 2 * inset);
        int height = Math.max(2, BODY_HEIGHT - inset);
        return new Rectangle(foot.x - width / 2, foot.y - height, width, height);
    }

    /**
     * Whether {@code box} meets {@code victim}, with ties going to the human:
     * <ul>
     *   <li>a human victim must be inside it where it was last reported and where it is heading (so moving out of a
     *       swing works);</li>
     *   <li>a bot hit by a human counts where the bot stood at any point of the lag window (so the human is judged
     *       by what its screen showed);</li>
     *   <li>bot against bot: where they are.</li>
     * </ul>
     */
    public static boolean reaches(Rectangle box, Candidate victim, boolean attackerHuman, long nowMs,
                                  int lagWindowMs, int humanInset) {
        Point at = victim.character().getPosition();
        if (box == null || at == null) {
            return false;
        }
        if (victim.human()) {
            if (!box.intersects(hurtbox(at, humanInset))) {
                return false;
            }
            Point ahead = victim.track() == null ? at : victim.track().extrapolate(at, lagWindowMs, nowMs);
            return box.intersects(hurtbox(ahead, humanInset));
        }
        if (box.intersects(hurtbox(at, 0))) {
            return true;
        }
        if (attackerHuman && victim.track() != null) {
            for (Point earlier : victim.track().within(lagWindowMs, nowMs)) {
                if (box.intersects(hurtbox(earlier, 0))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The victims an attack meets, nearest first, at most {@code maxTargets}. */
    public static List<Candidate> victims(Rectangle box, Point from, List<Candidate> candidates, int maxTargets,
                                          boolean attackerHuman, long nowMs, int lagWindowMs, int humanInset) {
        List<Candidate> met = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (reaches(box, candidate, attackerHuman, nowMs, lagWindowMs, humanInset)) {
                met.add(candidate);
            }
        }
        if (from != null) {
            met.sort(Comparator.comparingDouble(c -> c.character().getPosition().distanceSq(from)));
        }
        return met.size() > maxTargets ? new ArrayList<>(met.subList(0, maxTargets)) : met;
    }
}

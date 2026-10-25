package server.agents.capabilities.combat;

import constants.skills.Aran;
import constants.skills.Brawler;
import constants.skills.ChiefBandit;
import constants.skills.DarkKnight;
import constants.skills.Hero;
import constants.skills.Paladin;
import constants.skills.ThunderBreaker;
import server.StatEffect;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Set;

/**
 * Skills whose client animation carries the caster along the ground: Rush, Assaulter and Final Charge
 * charge through their hit box, Corkscrew Blow runs forward through it, Backspin Blow slides back into the
 * box behind the caster. A player's own client moves the character and reports it; a headless
 * Agent has no client, so without this it swings the skill in place while the mobs it hit are
 * knocked away from it.
 */
public final class AgentCombatDashPolicy {
    private static final Set<Integer> FORWARD_DASH_SKILL_IDS = Set.of(
            Hero.RUSH, Paladin.RUSH, DarkKnight.RUSH, ChiefBandit.ASSAULTER,
            Brawler.CORKSCREW_BLOW, ThunderBreaker.CORKSCREW_BLOW, Aran.FINAL_CHARGE);
    private static final Set<Integer> BACKWARD_DASH_SKILL_IDS = Set.of(Brawler.BACK_SPIN_BLOW);

    private AgentCombatDashPolicy() {
    }

    /** Signed distance the cast carries the caster (+ = the way it faces, - = behind it); 0 for skills that stay put. */
    public static int dashDistancePx(int skillId, StatEffect effect) {
        if (effect == null || !effect.hasBoundingBox()) {
            return 0;
        }
        return dashDistancePx(skillId, effect.calculateBoundingBox(new Point(0, 0), true));
    }

    /** Same, from the skill's box as the server lays it out for a caster at the origin facing left. */
    static int dashDistancePx(int skillId, Rectangle boxFacingLeft) {
        if (boxFacingLeft == null) {
            return 0;
        }
        if (FORWARD_DASH_SKILL_IDS.contains(skillId)) {
            return Math.max(0, -boxFacingLeft.x);   // facing left, the front is negative x
        }
        if (BACKWARD_DASH_SKILL_IDS.contains(skillId)) {
            return -Math.max(0, boxFacingLeft.x + boxFacingLeft.width);
        }
        return 0;
    }
}

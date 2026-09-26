package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.WhiteKnight;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Set;

/**
 * Skill rules the v83 client enforces and the server does not. A player's client refuses the
 * cast, so the server never needed them; a bot has no client and would break them, which a player
 * watching notices and which lets the learner "discover" loops no player can perform.
 *
 * <ul>
 *   <li>Double Uppercut, Corkscrew Blow, Backspin Blow, Barrage and Snatch cannot be cast again for
 *       a moment after one of them lands; a pirate weaves another skill in between.</li>
 *   <li>Corkscrew Blow leaves the pirate invincible for a moment after it lands, so a Corkscrew
 *       followed by quick attacks takes no touch or magic damage.</li>
 *   <li>Demolition and Snatch only work under Super Transformation.</li>
 *   <li>Charged Blow needs a charge on the weapon.</li>
 * </ul>
 */
public final class AgentClientSkillRules {
    private static final long REUSE_DELAY_MS = config.AgentTuning.longValue("server.agents.capabilities.combat.AgentClientSkillRules.REUSE_DELAY_MS");
    private static final long CORKSCREW_INVINCIBLE_MS = config.AgentTuning.longValue("server.agents.capabilities.combat.AgentClientSkillRules.CORKSCREW_INVINCIBLE_MS");
    private static final Set<Integer> REUSE_DELAY_SKILL_IDS = Set.of(
            Brawler.DOUBLE_UPPERCUT, Brawler.CORKSCREW_BLOW, Brawler.BACK_SPIN_BLOW,
            Buccaneer.BARRAGE, Buccaneer.SNATCH);
    private static final Set<Integer> SUPER_TRANSFORMATION_SKILL_IDS = Set.of(Buccaneer.DEMOLITION, Buccaneer.SNATCH);

    private AgentClientSkillRules() {
    }

    /**
     * A cast of {@code skillId} landed at {@code nowMs} and its animation runs {@code animationMs}:
     * the reuse delay counts from the end of the animation (Barrage alone animates for over two
     * seconds, so a delay counted from the hit would already be over), invincibility from the hit.
     */
    public static void afterLandedHit(AgentRuntimeEntry entry, int skillId, long nowMs, long animationMs) {
        if (REUSE_DELAY_SKILL_IDS.contains(skillId)) {
            state(entry).lockReuse(skillId, nowMs + Math.max(0, animationMs) + REUSE_DELAY_MS);
        }
        if (skillId == Brawler.CORKSCREW_BLOW) {
            state(entry).invincibleUntil(nowMs + CORKSCREW_INVINCIBLE_MS);
        }
    }

    /** Still inside the reuse delay of its last landed cast. */
    public static boolean reuseLocked(AgentRuntimeEntry entry, int skillId) {
        return entry != null && REUSE_DELAY_SKILL_IDS.contains(skillId)
                && state(entry).reuseLocked(skillId, System.currentTimeMillis());
    }

    public static boolean invincible(AgentRuntimeEntry entry) {
        return entry != null && state(entry).invincible(System.currentTimeMillis());
    }

    /** Whether the Agent's current form allows the skill (Demolition and Snatch need Super Transformation). */
    public static boolean usableInCurrentForm(Character bot, int skillId) {
        return !SUPER_TRANSFORMATION_SKILL_IDS.contains(skillId)
                || usableInForm(skillId, bot.getBuffedValue(BuffStat.MORPH) == null ? 0 : bot.getBuffSource(BuffStat.MORPH));
    }

    static boolean usableInForm(int skillId, int morphSourceSkillId) {
        return !SUPER_TRANSFORMATION_SKILL_IDS.contains(skillId) || morphSourceSkillId == Buccaneer.SUPER_TRANSFORMATION;
    }

    /** Whether the Agent may swing {@code skillId} with its weapon's charge state (Charged Blow needs a charge). */
    public static boolean chargeReady(Character bot, int skillId) {
        return chargeReady(skillId, bot.getBuffedValue(BuffStat.WK_CHARGE) != null);
    }

    static boolean chargeReady(int skillId, boolean chargeActive) {
        return skillId != WhiteKnight.CHARGE_BLOW || chargeActive;
    }

    /** Clears every clock; the arena calls this when it resets the fight. */
    public static void reset(AgentRuntimeEntry entry) {
        state(entry).clear();
    }

    private static AgentClientSkillRuleState state(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentClientSkillRuleState.STATE_KEY);
    }
}

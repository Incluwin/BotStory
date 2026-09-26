package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.DarkKnight;

/**
 * Dark Knight damage doubles while HP sits below Berserk's threshold
 * ({@code CombatFormulaProvider}: {@code damage *= 2} when {@code hp * 100 / maxHp < X}).
 *
 * <p>The threshold rises with skill level and reaches 80% at max, so the ordinary potion
 * trigger at 70% already sits inside the window. What costs the doubling is the potion size:
 * drinking at 70% restores the whole bar, which puts the Agent back above the threshold until
 * something hurts it again. This policy keeps the window on both sides - the trigger never
 * rises above it (which matters at the lower skill levels, where the threshold is under the
 * ordinary trigger), and the restore asks for just enough to reach the top of the window
 * rather than a full heal - with a floor so the Agent still drinks before it is in danger.</p>
 */
public final class AgentBerserkWindowPolicy {
    private static final int MARGIN_PERCENT =
            config.AgentTuning.intValue("server.agents.capabilities.combat.AgentBerserkWindowPolicy.MARGIN_PERCENT");
    private static final float MIN_DEFICIT =
            (float) config.AgentTuning.doubleValue("server.agents.capabilities.combat.AgentBerserkWindowPolicy.MIN_DEFICIT");
    private static final float SAFETY_FLOOR =
            (float) config.AgentTuning.doubleValue("server.agents.capabilities.combat.AgentBerserkWindowPolicy.SAFETY_FLOOR");

    private AgentBerserkWindowPolicy() {
    }

    /** Berserk's HP threshold in percent for this character, or 0 when it has not learned it. */
    public static int thresholdPercent(Character bot) {
        if (bot == null) {
            return 0;
        }
        Skill berserk = SkillFactory.getSkill(DarkKnight.BERSERK);
        if (berserk == null) {
            return 0;
        }
        int level = bot.getSkillLevel(berserk);
        if (level <= 0) {
            return 0;
        }
        return berserk.getEffect(level).getX();
    }

    /**
     * The HP fraction at which this Agent should drink. Without Berserk it is the configured
     * value; with Berserk it is a margin below the threshold, never under the safety floor.
     */
    public static float autopotHpThreshold(float configured, int berserkThresholdPercent) {
        if (berserkThresholdPercent <= 0) {
            return configured;
        }
        float window = (berserkThresholdPercent - MARGIN_PERCENT) / 100f;
        return Math.max(SAFETY_FLOOR, Math.min(configured, window));
    }

    public static float autopotHpThreshold(Character bot, float configured) {
        return autopotHpThreshold(configured, thresholdPercent(bot));
    }

    /**
     * The share of maximum HP a potion should aim to restore. Without Berserk that is everything
     * the Agent is missing at its trigger. With Berserk, restoring that much lands the Agent back
     * above the threshold and switches the doubling off until it is hurt again; instead it asks
     * for just enough to reach the top of the window, which keeps the doubling on permanently.
     */
    public static float autopotHpDeficitFraction(float configured, int berserkThresholdPercent) {
        float trigger = autopotHpThreshold(configured, berserkThresholdPercent);
        if (berserkThresholdPercent <= 0) {
            return 1.0f - trigger;
        }
        float window = (berserkThresholdPercent - MARGIN_PERCENT) / 100f;
        return Math.max(MIN_DEFICIT, window - trigger);
    }

    public static float autopotHpDeficitFraction(Character bot, float configured) {
        return autopotHpDeficitFraction(configured, thresholdPercent(bot));
    }

    /** The HP a Berserk Agent should be staged at to sit inside the window; 0 when not applicable. */
    public static int stagedHp(Character bot) {
        int threshold = thresholdPercent(bot);
        if (threshold <= 0) {
            return 0;
        }
        int maxHp = bot.getCurrentMaxHp();
        int target = Math.round(maxHp * (threshold - MARGIN_PERCENT) / 100f);
        return Math.max(1, Math.min(maxHp, target));
    }
}

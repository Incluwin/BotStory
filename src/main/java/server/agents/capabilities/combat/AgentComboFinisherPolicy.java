package server.agents.capabilities.combat;

import client.Character;
import client.Skill;
import constants.game.GameConstants;
import constants.skills.Crusader;
import constants.skills.DawnWarrior;
import constants.skills.Hero;
import server.StatEffect;
import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.integration.AgentSkillGatewayRuntime;

/**
 * When Panic/Coma are worth planning. The client only demands one orb, but on this server a
 * finisher scales from x1.2 at two orbs to x2.5 at five while every finisher drops the whole
 * stack, so spending it early is a DPS loss (the Hero rotation the operator watched: Brandish,
 * Coma at two orbs, rebuild, repeat). The prior the combat learner starts from is therefore
 * "hold to a full stack"; {@code MIN_ORBS} is clamped to what the bot's Combo Attack can hold.
 */
public final class AgentComboFinisherPolicy {
    private static final int MIN_ORBS = config.AgentTuning.intValue("server.agents.capabilities.combat.AgentComboFinisherPolicy.MIN_ORBS");

    private AgentComboFinisherPolicy() {
    }

    public static boolean canPlan(Character bot, int skillId) {
        if (!GameConstants.isFinisherSkill(skillId)) {
            return true;
        }
        // A learner rediscovering the rule gets only the client minimum (one orb).
        int maxOrbs = AgentCombatLearner.priorsRelaxed(bot) ? 1 : maxOrbs(bot);
        return canPlan(skillId, bot.getBuffedValue(client.BuffStat.COMBO), maxOrbs);
    }

    /** Client-equivalent legality plus the full-stack economy gate; {@code comboBuffValue} is orbs + 1. */
    public static boolean canPlan(int skillId, Integer comboBuffValue, int maxOrbs) {
        if (!GameConstants.isFinisherSkill(skillId)) {
            return true;
        }
        if (comboBuffValue == null || comboBuffValue < 2) {
            return false;
        }
        int orbs = comboBuffValue - 1;
        int required = Math.max(1, Math.min(MIN_ORBS, maxOrbs));
        return orbs >= required;
    }

    /** Orbs the bot's Combo Attack (or Advanced Combo) can hold; mirrors CloseRangeDamageHandler's orb gain. */
    public static int maxOrbs(Character bot) {
        int comboId = bot.isCygnus() ? DawnWarrior.COMBO : Crusader.COMBO;
        int advancedId = bot.isCygnus() ? DawnWarrior.ADVANCED_COMBO : Hero.ADVANCED_COMBO;
        Skill advanced = AgentSkillGatewayRuntime.skills().getSkill(advancedId);
        int advancedLevel = advanced == null ? 0 : bot.getSkillLevel(advanced);
        if (advancedLevel > 0) {
            StatEffect effect = advanced.getEffect(advancedLevel);
            return effect == null ? Integer.MAX_VALUE : effect.getX();
        }
        Skill combo = AgentSkillGatewayRuntime.skills().getSkill(comboId);
        int comboLevel = combo == null ? 0 : bot.getSkillLevel(combo);
        if (combo == null) {
            return Integer.MAX_VALUE;
        }
        if (comboLevel <= 0) {
            comboLevel = combo.getMaxLevel();
        }
        StatEffect effect = combo.getEffect(comboLevel);
        return effect == null ? Integer.MAX_VALUE : effect.getX();
    }
}

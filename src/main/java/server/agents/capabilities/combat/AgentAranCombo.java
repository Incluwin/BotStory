package server.agents.capabilities.combat;

import client.Character;
import constants.game.GameConstants;
import constants.skills.Aran;
import server.agents.integration.AgentCombatGatewayRuntime;
import server.agents.integration.CombatGateway;

/**
 * An Aran's combo counter (v83). The client counts every monster the Aran's attacks hit and sends ARAN_COMBO_COUNTER
 * (0xA3) for each; AranComboHandler adds one, starts over once 3 s pass without a hit, and puts Combo Ability's attack
 * buff up every 10. Combo Smash, Combo Fenrir and Combo Tempest need 30, 100 and 200 combo and spend all of it
 * (RangedAttackHandler). A headless Agent has no client, so after each committed attack it sends the same packet once
 * per monster the attack hit (user decision 2026-10-24: counted from its own hits), and it plans a combo skill only
 * with the combo the client would show, never a stale count the server has not cleared yet. When to spend it is the
 * combat learner's choice (the combo bucket in AgentCombatLearningContext).
 */
public final class AgentAranCombo {
    /** AranComboHandler: a count older than this starts the combo over. */
    static final long DECAY_MS = 3_000L;

    private AgentAranCombo() {
    }

    /** The combo {@code skillId} needs and spends (Combo Smash 30, Combo Fenrir 100, Combo Tempest 200), else 0. */
    public static int required(int skillId) {
        return switch (skillId) {
            case Aran.COMBO_SMASH -> 30;
            case Aran.COMBO_FENRIR -> 100;
            case Aran.COMBO_TEMPEST -> 200;
            default -> 0;
        };
    }

    public static boolean spendsCombo(int skillId) {
        return required(skillId) > 0;
    }

    /** The combo the Aran's client would show at {@code nowMs} (server clock): 0 once its last count is 3 s old. */
    public static int current(Character bot, long nowMs) {
        if (bot == null || bot.getJob() == null || !GameConstants.isAran(bot.getJob().getId())) {
            return 0;
        }
        return current(bot.getCombo(), bot.getLastCombo(), nowMs);
    }

    static int current(int combo, long lastComboMs, long nowMs) {
        return combo <= 0 || nowMs - lastComboMs > DECAY_MS ? 0 : combo;
    }

    /** False for a combo skill the shown combo cannot pay for; true for every other skill. */
    public static boolean canPlan(Character bot, int skillId) {
        int required = required(skillId);
        return required == 0 || current(bot, AgentCombatGatewayRuntime.combat().serverTimeMs()) >= required;
    }

    /**
     * After a committed attack: one combo count per monster it hit, as the client sends them. A combo skill spends the
     * combo instead, and a non-Aran has none. Returns how many counts the server took.
     */
    public static int countHits(Character bot, int skillId, int monstersHit) {
        return countHits(bot, skillId, monstersHit, AgentCombatGatewayRuntime.combat());
    }

    static int countHits(Character bot, int skillId, int monstersHit, CombatGateway combat) {
        if (bot == null || bot.getJob() == null || !GameConstants.isAran(bot.getJob().getId())
                || spendsCombo(skillId) || monstersHit <= 0) {
            return 0;
        }
        int counted = 0;
        for (int i = 0; i < monstersHit; i++) {
            if (combat.dispatchAranComboCount(bot)) {
                counted++;
            }
        }
        return counted;
    }
}

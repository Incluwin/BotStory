package server.living.population;

import client.Character;
import config.YamlConfig;

/**
 * A citizen's answer to a player's party invite (docs/BOT_MANAGEMENT_PLAN.md, "Bot invite decisions"). P1 is a
 * plain level rule: a citizen joins a player within the stock {@code EXP_SPLIT_LEECH_INTERVAL} levels, the widest
 * gap at which a party member still shares EXP from a kill it did not hit. P2a adds the guest seat and P4 adds
 * busy and personality.
 */
public final class CitizenInviteDecision {
    private CitizenInviteDecision() {
    }

    public static boolean accepts(Character inviter, Character citizen) {
        return accepts(inviter.getLevel(), citizen.getLevel(), YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL);
    }

    static boolean accepts(int inviterLevel, int citizenLevel, int maxLevelGap) {
        return Math.abs(inviterLevel - citizenLevel) <= maxLevelGap;
    }
}

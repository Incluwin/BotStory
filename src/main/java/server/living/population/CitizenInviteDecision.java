package server.living.population;

import client.Character;
import config.YamlConfig;

/**
 * A citizen's answer to a player's party invite (docs/BOT_MANAGEMENT_PLAN.md, "Bot invite decisions"), in stock
 * words. It joins a player within the stock {@code EXP_SPLIT_LEECH_INTERVAL} levels, the widest gap at which a party
 * member still shares EXP from a kill it did not hit, unless it is busy: inside an event instance, or minding the
 * market (a stall open, a market errand). On yes it becomes the player's guest (a GUEST seat charged to them,
 * CompanionSlots). P4 adds personality.
 */
public final class CitizenInviteDecision {
    /** The answer and the stock party status the inviter sees on a no. */
    public enum Answer {
        ACCEPT(0),
        /** "... is taking care of another invitation." */
        BUSY(22),
        /** "... have denied request to the party." */
        TOO_FAR(23);

        private final int status;

        Answer(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private CitizenInviteDecision() {
    }

    public static Answer decide(Character inviter, Character citizen) {
        return decide(inviter.getLevel(), citizen.getLevel(), YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL,
                busy(citizen));
    }

    public static Answer decide(int inviterLevel, int citizenLevel, int maxLevelGap, boolean busy) {
        if (busy) {
            return Answer.BUSY;
        }
        return Math.abs(inviterLevel - citizenLevel) <= maxLevelGap ? Answer.ACCEPT : Answer.TOO_FAR;
    }

    private static boolean busy(Character citizen) {
        if (citizen.getEventInstance() != null) {
            return true;
        }
        Population population = Population.get();
        CitizenSession session = population == null ? null : population.presence().session(citizen.getId());
        return session != null && (session.stallOpen || session.activity == CitizenSession.Activity.MARKET);
    }
}

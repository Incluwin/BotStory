package server.agents.capabilities.partyquest.epq;

/**
 * How one non-leader member's state bears on an EPQ companion run (the shape of AgentHpqMemberVerdicts; the pass skips
 * the event leader, whose rules are the coordinator's own). EPQ is played stock: a dead member at the stock floor
 * waits like a player who has not pressed OK (EllinPQ never ends for one, EllinPQ.js:180-189) until no companion
 * stands; a departed companion (the Step-0 departure gate let it go) is tapped out through its host; a departed or
 * vanished player is dropped from the run. Inside the event is STAGE_ONE through BOSS; cleared is the event's own clear,
 * never the leader's map alone.
 */
final class AgentEpqMemberVerdicts {
    enum Verdict { OK, MISSING, DROP_HUMAN, DOWN_WAIT, REQUEST_TAP_OUT, KEEP_DEPARTED, REVIVED, IGNORE, SHORT_HANDED }

    /**
     * One member as one pass sees it.
     *
     * @param present            online (a human) or loaded (a companion)
     * @param inBoundEvent       standing in the run's own event instance
     * @param leaderInBoundEvent the run's leader stands in it
     * @param cleared            the event's own clear
     * @param departed           the bound event let it go (the departure gate, or a player who pressed OK)
     * @param downSinceMs        when it was first seen down; 0 when it is not down
     * @param tapOutRequested    its host took the tap-out
     * @param tapOutRefused      its host refused the tap-out
     * @param standingCompanions the run's other companions online, up, in the bound event and not departed
     */
    record Facts(AgentEpqSession.Phase phase,
                 AgentEpqMemberState.MemberType type,
                 boolean present,
                 int hp,
                 boolean inBoundEvent,
                 boolean leaderInBoundEvent,
                 boolean cleared,
                 boolean departed,
                 long downSinceMs,
                 boolean tapOutRequested,
                 boolean tapOutRefused,
                 int standingCompanions) {
    }

    private AgentEpqMemberVerdicts() {
    }

    /** The verdict for one non-leader member, by the rules in order. */
    static Verdict verdict(Facts facts) {
        boolean human = facts.type() == AgentEpqMemberState.MemberType.HUMAN;
        boolean inside = insideEvent(facts.phase());
        if (!facts.present()) {
            if (!human) return Verdict.MISSING;
            if (facts.cleared()) return Verdict.IGNORE;
            return inside ? Verdict.DROP_HUMAN : Verdict.MISSING;
        }
        // After the clear nothing is asked of a dead or departed member.
        if (facts.cleared()) {
            return facts.hp() <= 0 || facts.downSinceMs() > 0L || facts.departed() ? Verdict.IGNORE : Verdict.OK;
        }
        if (!inside) return Verdict.OK;
        // With the leader out of the bound event the event check ends the run with the real reason.
        if (!facts.leaderInBoundEvent()) return Verdict.OK;
        if (facts.departed()) {
            if (human) return Verdict.DROP_HUMAN;
            if (facts.tapOutRefused()) return Verdict.KEEP_DEPARTED;
            return facts.tapOutRequested() ? Verdict.IGNORE : Verdict.REQUEST_TAP_OUT;
        }
        if (facts.hp() <= 0 && facts.inBoundEvent()) {
            // A corpse waits like a player who has not pressed OK, with no time limit, until no companion stands.
            return !human && facts.standingCompanions() == 0 ? Verdict.SHORT_HANDED : Verdict.DOWN_WAIT;
        }
        if (facts.hp() > 0 && facts.inBoundEvent() && facts.downSinceMs() > 0L) return Verdict.REVIVED;
        return Verdict.OK;
    }

    /** The forest's stages, from the first tree to the golem. */
    static boolean insideEvent(AgentEpqSession.Phase phase) {
        return switch (phase) {
            case STAGE_ONE, STAGE_TWO, STAGE_THREE, STAGE_FOUR, STAGE_FIVE, BOSS -> true;
            default -> false;
        };
    }
}

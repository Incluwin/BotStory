package server.agents.capabilities.partyquest.ppq;

/**
 * How one non-leader member's state bears on a PPQ companion run (P6i Step 4, plan 9.2, the shape of
 * AgentRjpqMemberVerdicts). A dead companion lies where it fell while its departure gate holds it at the stock floor,
 * like a player who has not pressed OK; one the gate let go respawns outside and the coordinator's departure pass hands
 * it back (a tap-out in a desk run). A human who left the event alive has left the run. With no other companion
 * standing, a companion the gate holds down ends the run.
 */
final class AgentPpqMemberVerdicts {
    enum Verdict { OK, MISSING, DROP_HUMAN, DOWN_WAIT, REVIVED, IGNORE, SHORT_HANDED }

    /**
     * One member as one pass sees it.
     *
     * @param inside             the run's bound event is up and not cleared
     * @param present            online (a human) or loaded (a companion)
     * @param inBoundEvent       standing in the run's own event instance
     * @param seenInside         the bound event held it at some pass (a human never in it has not left it)
     * @param downSinceMs        when it was first seen down; 0 when it is not down
     * @param held               its departure gate holds it down at the floor
     * @param standingCompanions the run's other companions alive and in the bound event
     */
    record Facts(boolean inside,
                 AgentPpqMemberState.MemberType type,
                 boolean present,
                 long hp,
                 boolean inBoundEvent,
                 boolean seenInside,
                 long downSinceMs,
                 boolean held,
                 int standingCompanions) {
    }

    private AgentPpqMemberVerdicts() {
    }

    /** The verdict for one non-leader member, by the rules in order. */
    static Verdict verdict(Facts facts) {
        boolean human = facts.type() == AgentPpqMemberState.MemberType.HUMAN;
        if (!facts.inside()) {
            return Verdict.IGNORE;
        }
        if (!facts.present()) {
            return human ? (facts.seenInside() ? Verdict.DROP_HUMAN : Verdict.IGNORE) : Verdict.MISSING;
        }
        if (!facts.inBoundEvent()) {
            // an Agent out of the event is the departure pass's (left, or between two maps)
            return human && facts.seenInside() ? Verdict.DROP_HUMAN : Verdict.IGNORE;
        }
        if (facts.hp() <= 0) {
            return !human && facts.held() && facts.standingCompanions() == 0 ? Verdict.SHORT_HANDED
                    : Verdict.DOWN_WAIT;
        }
        return facts.downSinceMs() > 0L ? Verdict.REVIVED : Verdict.OK;
    }
}

package server.agents.capabilities.partyquest.rjpq;

/**
 * How one non-leader member's state bears on an RJPQ companion run (the shape of AgentEpqMemberVerdicts). RJPQ needs
 * exactly four (MagatiaPQ_Z.js minPlayers), so nobody can tap out and nobody is replaced: a dead companion waits like a
 * player who has not pressed OK (its departure gate holds it) while another companion stands; with none standing the
 * run ends. A player who left the event already ended the stock event, whose end check speaks for the run.
 */
final class AgentRjpqMemberVerdicts {
    enum Verdict { OK, MISSING, DOWN_WAIT, REVIVED, IGNORE, SHORT_HANDED, DROP_HUMAN }

    /**
     * One member as one pass sees it.
     *
     * @param inside             the run is on a stage (books to the Center Lab), not before the event or past it
     * @param present            online (a human) or loaded (a companion)
     * @param inBoundEvent       standing in the run's own event instance
     * @param cleared            the event's own clear (the boss is down)
     * @param downSinceMs        when it was first seen down; 0 when it is not down
     * @param standingCompanions the run's other companions online, up and in the bound event
     */
    record Facts(boolean inside,
                 AgentRjpqMemberState.MemberType type,
                 boolean present,
                 long hp,
                 boolean inBoundEvent,
                 boolean cleared,
                 long downSinceMs,
                 int standingCompanions) {
    }

    private AgentRjpqMemberVerdicts() {
    }

    /** The verdict for one non-leader member, by the rules in order. */
    static Verdict verdict(Facts facts) {
        boolean human = facts.type() == AgentRjpqMemberState.MemberType.HUMAN;
        if (!facts.present()) {
            if (facts.cleared()) {
                return Verdict.IGNORE;
            }
            return human && facts.inside() ? Verdict.DROP_HUMAN : Verdict.MISSING;
        }
        if (facts.cleared()) {
            return facts.hp() <= 0 || facts.downSinceMs() > 0L ? Verdict.IGNORE : Verdict.OK;
        }
        if (!facts.inside()) {
            return Verdict.OK;
        }
        if (!facts.inBoundEvent()) {
            return human ? Verdict.DROP_HUMAN : Verdict.MISSING;
        }
        if (facts.hp() <= 0) {
            return !human && facts.standingCompanions() == 0 ? Verdict.SHORT_HANDED : Verdict.DOWN_WAIT;
        }
        return facts.downSinceMs() > 0L ? Verdict.REVIVED : Verdict.OK;
    }

    /** The run's stages, from the books to the Center Lab. */
    static boolean inside(AgentRjpqSession.Phase phase) {
        return switch (phase) {
            case BOOKS, TUNNEL, BEAKERS, KEYS, LABS, HANDIN, OFFICE, UNITS, CENTER -> true;
            default -> false;
        };
    }
}

package server.agents.capabilities.partyquest.rjpq;

/**
 * How a companion of an RJPQ companion run puts the run's early end to the players around it (the shape of
 * AgentEpqParting): casual, lower case but for names, never an id or a reason (the reason goes only to the log). Said
 * only before the clear: after it a companion run never fails. SILENT says nothing: a run that never got going.
 *
 * @param leader the leader's name, for the lines that speak to him; null when unknown
 */
record AgentRjpqParting(Kind kind, String leader) {
    enum Kind { SILENT, GENERIC, WENT_DOWN, DEFEATED, LEADER_DOWN, GONE, TIMEOUT, TOO_LONG }

    static final AgentRjpqParting SILENT = new AgentRjpqParting(Kind.SILENT, null);
    static final AgentRjpqParting GENERIC = new AgentRjpqParting(Kind.GENERIC, null);

    static AgentRjpqParting of(Kind kind, String leader) {
        return new AgentRjpqParting(kind, leader);
    }

    /**
     * Why the event ended under the run before the clear: the leader went down before the files were in (nobody else
     * may hand them in) or after; else the event timer, when its last read had at most {@code slackMs} left; else
     * neither.
     */
    static Kind endedCause(boolean leaderWentDown, boolean filesHandedIn, long lastTimeLeftMs, long slackMs) {
        if (leaderWentDown) {
            return filesHandedIn ? Kind.LEADER_DOWN : Kind.DEFEATED;
        }
        return lastTimeLeftMs <= slackMs ? Kind.TIMEOUT : Kind.GENERIC;
    }

    /** The line, or null for SILENT. */
    String line() {
        boolean named = leader != null && !leader.isBlank();
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> "that's it for this one.";
            case WENT_DOWN -> "i'm down too... that's it for us.";
            case DEFEATED -> named ? leader + ", we can't hand in the files without you. let's head back."
                    : "we can't hand in the files now. let's head back.";
            case LEADER_DOWN -> named ? leader + ", we couldn't finish it without you. let's head back."
                    : "we couldn't finish it. let's head back.";
            case GONE -> named ? leader + "'s gone. we'll head back." : "our leader's gone. we'll head back.";
            case TIMEOUT -> "out of time... yulete got away.";
            case TOO_LONG -> "we're stuck. let's call it.";
        };
    }
}

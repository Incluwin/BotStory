package server.agents.capabilities.partyquest.lmpq;

/**
 * How a companion of an LMPQ companion run puts the run's early end to its party (P6h Step 3, the shape of RJPQ's and
 * LPQ's partings): casual, lower case but for names, never an id or a reason (the reason goes only to the log). Said
 * only before the clear: after it a companion run never fails in words. SILENT says nothing: a run that never got going
 * (its desk already answered the leader).
 *
 * @param subject the member the line is about (the leader, or the companion that went down); null for none
 */
record AgentLmpqParting(Kind kind, String subject) {
    enum Kind { SILENT, GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, TIMEOUT, TOO_LONG }

    static final AgentLmpqParting SILENT = new AgentLmpqParting(Kind.SILENT, null);
    static final AgentLmpqParting GENERIC = new AgentLmpqParting(Kind.GENERIC, null);

    static AgentLmpqParting of(Kind kind, String subject) {
        return new AgentLmpqParting(kind, subject);
    }

    /**
     * Why the event ended under the run before the clear: a companion that went down left it at the stock floor
     * ({@code floorDeparture}, its name); else LudiMazePQ.js's timer, when the run has been inside at least
     * {@code timerMs - slackMs}; else no reason a player would name.
     */
    static AgentLmpqParting eventEnded(String floorDeparture, long insideMs, long timerMs, long slackMs) {
        if (floorDeparture != null) return of(Kind.WENT_DOWN, floorDeparture.isBlank() ? null : floorDeparture);
        return insideMs >= timerMs - slackMs ? of(Kind.TIMEOUT, null) : GENERIC;
    }

    /** The line, or null for SILENT. */
    String line() {
        boolean named = subject != null && !subject.isBlank();
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> "that's it for this one. let's head out.";
            case WENT_DOWN -> named ? subject + " went down and we're too few now. let's head out."
                    : "we're too few now. let's head out.";
            case LEFT -> named ? subject + ", you left the maze, so that's it for this one."
                    : "our leader left the maze, so that's it for this one.";
            case DEFEATED -> named ? subject + ", we can't finish without you. let's head back."
                    : "we can't finish without our leader. let's head back.";
            case GONE -> named ? subject + "'s gone. we'll head back." : "our leader's gone. we'll head back.";
            case TIMEOUT -> "out of time... the maze kicked us out.";
            case TOO_LONG -> "we're stuck. let's call it.";
        };
    }
}

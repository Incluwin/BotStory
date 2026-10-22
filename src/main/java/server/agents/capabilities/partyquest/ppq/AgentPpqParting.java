package server.agents.capabilities.partyquest.ppq;

/**
 * How a companion of a PPQ companion run puts the run's early end to the players around it (P6i Step 4, plan 9.5, the
 * shape of AgentRjpqParting): casual, lower case but for names, never an id or a reason (the reason goes only to the
 * log). Said only before the clear: after it a companion run never fails. SILENT says nothing: a run that never got
 * going.
 *
 * @param leader the leader's name, for the lines that speak to him; null when unknown
 */
record AgentPpqParting(Kind kind, String leader) {
    enum Kind { SILENT, GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, TIMEOUT, TOO_LONG }

    static final AgentPpqParting SILENT = new AgentPpqParting(Kind.SILENT, null);
    static final AgentPpqParting GENERIC = new AgentPpqParting(Kind.GENERIC, null);

    static AgentPpqParting of(Kind kind, String leader) {
        return new AgentPpqParting(kind, leader);
    }

    /**
     * Why the bound event ended under the run before the clear (plan 9.4), from what the run's last pass saw: the
     * leader lying dead in it (his OK ends the stock event: DEFEATED); else the event timer, when its last read had at
     * most {@code slackMs} left; else the event at its floor ({@code lastRegistered} at most {@code floor}: one more
     * leaving ends PiratePQ.js); else neither.
     */
    static Kind endedCause(boolean leaderDown, long lastTimeLeftMs, long slackMs, int lastRegistered, int floor) {
        if (leaderDown) return Kind.DEFEATED;
        if (lastTimeLeftMs <= slackMs) return Kind.TIMEOUT;
        if (lastRegistered >= 0 && lastRegistered <= floor) return Kind.LEFT;
        return Kind.GENERIC;
    }

    /** The line, or null for SILENT. */
    String line() {
        boolean named = leader != null && !leader.isBlank();
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> "that's it for this one.";
            case WENT_DOWN -> "we're all down... that's it.";
            case LEFT -> "not enough of us left. let's head back.";
            case DEFEATED -> named ? leader + ", you went down... we can't finish without you."
                    : "we can't finish it without you. let's head back.";
            case GONE -> named ? leader + "'s gone. we'll head back." : "our leader's gone. we'll head back.";
            case TIMEOUT -> "out of time... let's head back.";
            case TOO_LONG -> "we're stuck. let's call it.";
        };
    }
}

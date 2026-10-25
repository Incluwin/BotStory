package server.agents.capabilities.partyquest.apq;

/**
 * How a companion of an APQ run puts the run's early end to the players around it (the shape of AgentPpqParting):
 * casual, lower case but for names, never an id or a reason (the reason goes only to the log). Said only before the
 * clear: after it a run never fails. SILENT says nothing: a run that never got going.
 *
 * @param leader the leader's name, for the lines that speak to him; null when unknown
 * @param down   the member whose going down ended the event (WENT_DOWN); null when unknown
 */
record AgentApqParting(Kind kind, String leader, String down) {
    enum Kind { SILENT, GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, TIMEOUT, TOO_LONG }

    static AgentApqParting of(Kind kind, String leader, String down) {
        return new AgentApqParting(kind, leader, down);
    }

    /**
     * Why the bound event ended under the run before the clear, from what the run's last pass saw: the leader lying dead
     * in it (his OK ends AmoriaPQ.js: DEFEATED); else a member seen down (its OK ended it: WENT_DOWN); else the timer,
     * when its last read had at most {@code slackMs} left; else a member gone below six (LEFT); else neither.
     */
    static Kind endedCause(boolean leaderDown, boolean memberDown, long lastTimeLeftMs, long slackMs,
                           int lastRegistered) {
        if (leaderDown) return Kind.DEFEATED;
        if (memberDown) return Kind.WENT_DOWN;
        if (lastTimeLeftMs <= slackMs) return Kind.TIMEOUT;
        if (lastRegistered >= 0 && lastRegistered < AgentApqDefinition.PARTY_SIZE) return Kind.LEFT;
        return Kind.GENERIC;
    }

    /** The line, or null for SILENT. */
    String line() {
        boolean named = leader != null && !leader.isBlank();
        boolean fell = down != null && !down.isBlank();
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> "that's it for this one.";
            case WENT_DOWN -> fell ? down + " went down... amos needs all six of us. let's head back."
                    : "one of us went down... amos needs all six. let's head back.";
            case LEFT -> "we're not six anymore. let's head back.";
            case DEFEATED -> named ? leader + ", you went down... we can't finish without you."
                    : "we can't finish it without you. let's head back.";
            case GONE -> named ? leader + "'s gone. we'll head back." : "our leader's gone. we'll head back.";
            case TIMEOUT -> "out of time... let's head back.";
            case TOO_LONG -> "we're stuck. let's call it.";
        };
    }
}

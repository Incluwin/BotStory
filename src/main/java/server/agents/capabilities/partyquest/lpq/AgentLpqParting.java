package server.agents.capabilities.partyquest.lpq;

import client.Character;

/**
 * How a companion of an LPQ companion run puts the run's early end to the players around it: casual, lower-case but
 * for names, never an id or a reason (the reason goes only to the log). SILENT says nothing: a run that never got going
 * (its desk already answered the leader). LPQ's own copy of HPQ's parting (AgentHpqParting), with LPQ's floor of five
 * and no Moon Bunny.
 *
 * @param subjectId the member the line is about; 0 for none
 * @param subject   that member's name; null for none
 */
record AgentLpqParting(Kind kind, int subjectId, String subject) {
    enum Kind { SILENT, GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, TIMEOUT, TOO_LONG }

    static final AgentLpqParting SILENT = new AgentLpqParting(Kind.SILENT, 0, null);
    static final AgentLpqParting GENERIC = new AgentLpqParting(Kind.GENERIC, 0, null);

    static AgentLpqParting of(Kind kind) {
        return new AgentLpqParting(kind, 0, null);
    }

    static AgentLpqParting of(Kind kind, Character subject) {
        return subject == null ? of(kind) : new AgentLpqParting(kind, subject.getId(), subject.getName());
    }

    /**
     * Why the event ended under the run: LudiPQ's timer, once the run has stood in the event at least
     * {@code timerMs - slackMs}; else no reason a player would name.
     */
    static Kind eventEnded(long inEventMs, long timerMs, long slackMs) {
        return inEventMs >= timerMs - slackMs ? Kind.TIMEOUT : Kind.GENERIC;
    }

    /**
     * The line as this speaker says it: about itself when it is the one who went down, and as a wrap-up once the run
     * is cleared; null for SILENT.
     */
    String line(int speakerId, boolean pastTheClear) {
        return line(speakerId, 0, pastTheClear);
    }

    /**
     * The line as this speaker says it in a run led by {@code leaderId}: as {@link #line(int, boolean)}, and to the
     * leader himself ("you went down") when he is the one who went down, since he lies there and hears it.
     */
    String line(int speakerId, int leaderId, boolean pastTheClear) {
        if (kind == Kind.SILENT) return null;
        boolean self = subjectId > 0 && subjectId == speakerId;
        boolean named = subject != null && !subject.isBlank();
        String downed = subjectId > 0 && subjectId == leaderId ? "you" : subject;
        return pastTheClear ? wrapUp(self, named, downed) : beforeTheClear(self, named, downed);
    }

    /** {@code downed}: who went down as the line names them (the subject, or "you" for the leader). */
    private String beforeTheClear(boolean self, boolean named, String downed) {
        String generic = "we can't finish this one. let's head out.";
        String shortNow = "we're short now, and we need five for this. let's head out.";
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> generic;
            case WENT_DOWN -> self ? "sorry, i went down. we need five for this, so let's head out."
                    : named ? downed + " went down, and we need five for this. let's head out." : shortNow;
            case LEFT -> named ? subject + " left, and we need five for this. let's head out." : shortNow;
            case DEFEATED -> self ? "sorry, i went down. we can't finish this one, let's head out."
                    : named ? downed + " went down, so we can't finish this one. let's head out." : generic;
            case GONE -> named ? subject + " dropped out, so we can't finish this one. let's head out." : generic;
            case TIMEOUT -> "we ran out of time. let's head out.";
            case TOO_LONG -> "we've been stuck too long. let's call it and head out.";
        };
    }

    /** After the clear nobody is told the run can't be finished, or that it needs five: it is done. */
    private String wrapUp(boolean self, boolean named, String downed) {
        String wrapUp = "let's wrap it up and head back.";
        return switch (kind) {
            case SILENT -> null;
            case GENERIC, TIMEOUT -> wrapUp;
            case WENT_DOWN, DEFEATED -> self ? "sorry, i went down. " + wrapUp
                    : named ? downed + " went down. " + wrapUp : wrapUp;
            case LEFT -> named ? subject + " left. " + wrapUp : wrapUp;
            case GONE -> named ? subject + " dropped out. " + wrapUp : wrapUp;
            case TOO_LONG -> "we've been at this a while. " + wrapUp;
        };
    }
}

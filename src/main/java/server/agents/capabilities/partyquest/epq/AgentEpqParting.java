package server.agents.capabilities.partyquest.epq;

import client.Character;

import java.util.ArrayList;
import java.util.List;

/**
 * How a companion of an EPQ companion run puts the run's early end to the players around it (the shape of
 * AgentHpqParting): casual, lower case but for names, never an id or a reason (the reason goes only to the log). Said
 * only before the clear: after it a companion run never fails (Steps 2.9, 2.10). SILENT says nothing: a run that never
 * got going (the desk already answered the leader).
 *
 * @param subjectId the member the line is about; 0 for none
 * @param subject   that member's name; null for none
 * @param itemId    the objective a LOST run lost (the monster marbles or the purple stone); 0 otherwise
 */
record AgentEpqParting(Kind kind, int subjectId, String subject, int itemId) {
    enum Kind { SILENT, GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, LOST, TIMEOUT, TOO_LONG }

    static final AgentEpqParting SILENT = of(Kind.SILENT);
    static final AgentEpqParting GENERIC = of(Kind.GENERIC);

    static AgentEpqParting of(Kind kind) {
        return new AgentEpqParting(kind, 0, null, 0);
    }

    static AgentEpqParting of(Kind kind, Character subject) {
        return subject == null ? of(kind) : new AgentEpqParting(kind, subject.getId(), subject.getName(), 0);
    }

    /** The run lost this objective for good. */
    static AgentEpqParting lost(int itemId) {
        return new AgentEpqParting(Kind.LOST, 0, null, itemId);
    }

    /**
     * Why the event ended under the run: the leader went down (and pressed OK); else EllinPQ's timer, once the run has
     * stood in the event at least {@code timerMs - slackMs}; else neither.
     */
    static Kind endedCause(boolean leaderWentDown, long inEventMs, long timerMs, long slackMs) {
        if (leaderWentDown) return Kind.DEFEATED;
        return inEventMs >= timerMs - slackMs ? Kind.TIMEOUT : Kind.GENERIC;
    }

    /** The line as this speaker says it in the event (about itself when it is the one who went down); null for SILENT. */
    String line(int speakerId) {
        return line(speakerId, false);
    }

    /**
     * The line as this speaker says it; {@code outside}: the speaker already stands outside the event (EllinPQ's end
     * moved everyone to 930000800: the leader pressed OK, went offline, or the event ended), so the line has no
     * "let's head out" (review B). Null for SILENT.
     */
    String line(int speakerId, boolean outside) {
        if (outside) return outsideLine(speakerId);
        boolean self = subjectId > 0 && subjectId == speakerId;
        boolean named = subject != null && !subject.isBlank();
        String generic = "we can't finish this one. let's head out.";
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> generic;
            case WENT_DOWN -> self ? "sorry, i went down too. let's regroup outside."
                    : "too many of us went down. let's regroup outside.";
            case LEFT -> named ? subject + " left, and we need four for the forest. let's head out."
                    : "we're short now, and we need four for the forest. let's head out.";
            case DEFEATED -> named ? subject + ", you went down, so we can't finish this one. let's head out."
                    : generic;
            case GONE -> named ? subject + " dropped out, so we can't finish this one. let's head out." : generic;
            case LOST -> "we lost the "
                    + (itemId == AgentEpqDefinition.MAGIC_STONE ? "purple stone" : "monster marbles")
                    + ", so there's no way to finish now. let's head out.";
            case TIMEOUT -> "we ran out of time. we'll get the golem next time.";
            case TOO_LONG -> "we've been stuck too long. let's call it and head out.";
        };
    }

    private String outsideLine(int speakerId) {
        boolean self = subjectId > 0 && subjectId == speakerId;
        boolean named = subject != null && !subject.isBlank();
        String generic = "we couldn't finish this one.";
        return switch (kind) {
            case SILENT -> null;
            case GENERIC -> generic;
            case WENT_DOWN -> self ? "sorry, i went down too." : "too many of us went down.";
            case LEFT -> named ? subject + " left, and we need four for the forest."
                    : "we're short now, and we need four for the forest.";
            case DEFEATED -> named ? subject + ", you went down, so we couldn't finish this one." : generic;
            case GONE -> named ? subject + " dropped out, so we couldn't finish this one." : generic;
            case LOST -> "we lost the "
                    + (itemId == AgentEpqDefinition.MAGIC_STONE ? "purple stone" : "monster marbles")
                    + ", so there was no way to finish.";
            case TIMEOUT -> "we ran out of time. we'll get the golem next time.";
            case TOO_LONG -> "we were stuck too long, so we called it.";
        };
    }

    /**
     * Every parting line, each kind as said by itself, about a named member and about nobody, in the event and from
     * outside it (for the lint).
     */
    static List<String> sampleLines(String name) {
        List<String> lines = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (AgentEpqParting parting : List.of(new AgentEpqParting(kind, 7, name, 0),
                    new AgentEpqParting(kind, 0, null, 0), new AgentEpqParting(kind, 7, name,
                            AgentEpqDefinition.MAGIC_STONE))) {
                for (int speaker : new int[] {7, 8}) {
                    for (boolean outside : new boolean[] {false, true}) {
                        String line = parting.line(speaker, outside);
                        if (line != null && !lines.contains(line)) lines.add(line);
                    }
                }
            }
        }
        return lines;
    }
}

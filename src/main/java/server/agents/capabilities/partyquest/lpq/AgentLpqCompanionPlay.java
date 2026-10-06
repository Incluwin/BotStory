package server.agents.capabilities.partyquest.lpq;

import java.util.List;

/**
 * A companion run's LPQ play, kept apart from the coordinator so each rule and every line is pinned on its own: when
 * the party counts as inside, and what the companions say on each stage. Lines are lower case and name NPCs in lower
 * case; only a player's name keeps its capitals. No line carries a counter, an arrow, an id or a stage number.
 */
final class AgentLpqCompanionPlay {
    /** S2a, said on the leader's map while the two scouts go into stage 2 first. */
    static final String SCOUTS_GO_FIRST = "two of us go in first, there's a trap in there. wait here a sec.";
    /** S2b, once a scout reached the trap room and the way is clear. */
    static final String TRAP_CLEAR = "trap's clear, let's go in.";
    /** S6, said at the bottom of the portal maze, where everyone starts. */
    static final String MAZE_CODE = "it's 133 221 333 123 111 from the bottom, left to right. see you up top.";

    private static final List<String> BALLOONS = List.of("red balloon", "orange balloon", "yellow balloon",
            "lime balloon", "green balloon", "aqua balloon", "sky-blue balloon", "blue balloon", "violet balloon");

    private AgentLpqCompanionPlay() {
    }

    /** The stage's balloon as players call it (stages 1-9). */
    static String balloonName(int stage) {
        if (stage < 1 || stage > BALLOONS.size()) throw new IllegalArgumentException("LPQ stage 1-9: " + stage);
        return BALLOONS.get(stage - 1);
    }

    /** S1, as the party stands inside on stage 1. */
    static String stageOneLine(String leader) {
        return leader + ", talk to the " + balloonName(1) + " so it knows we're here. we'll get the passes.";
    }

    /** S3, as stage 3 begins. */
    static String stageThreeLine(String leader) {
        return leader + ", talk to the " + balloonName(3) + ", we'll get the passes.";
    }

    /** The line a stage begins with (S1, S3), or null for a stage that has none of its own. */
    static String stageStartLine(int stage, String leader) {
        return switch (stage) {
            case 1 -> stageOneLine(leader);
            case 3 -> stageThreeLine(leader);
            default -> null;
        };
    }

    /** How a companion run's entry stands, as HPQ's: everyone inside, keep waiting, go on without some, or give up. */
    enum Entry { WAIT, ENTERED, EJECT_ABSENT, GIVE_UP }

    static Entry entering(int inside, int members, long waitedMs, long entryWaitMs, int minimumParty) {
        if (inside >= members) return Entry.ENTERED;
        if (waitedMs < entryWaitMs) return Entry.WAIT;
        return inside >= minimumParty ? Entry.EJECT_ABSENT : Entry.GIVE_UP;
    }
}

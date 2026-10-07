package server.agents.capabilities.partyquest.epq;

/**
 * A companion run's EPQ play, kept apart from the coordinator so each rule and every line is pinned on its own: when
 * the companions go through a walking exit with their leader, how one lurer works both sides of stage 2, when and
 * where a carrier brings the monster marbles or the purple stone to the leader's feet, what they do after the golem
 * and at the fountain, and every line they say. Lines are lower-case and use the game's own words ("diluted poison",
 * "the fountain", "milepost", "purification marble", "golem's marble", "sprite"); only a player's name keeps its
 * capitals, and no line holds a digit.
 */
final class AgentEpqCompanionPlay {
    private AgentEpqCompanionPlay() {
    }

    // ---- the entry ----

    /** What the entry does: wait, go on with everyone, go on without the absent, or give up. */
    enum Entry { WAIT, ENTERED, EJECT_ABSENT, GIVE_UP }

    /**
     * Everyone seen inside goes on; after the entry wait at least {@code minimumParty} who entered go on without the
     * {@code absent} rest, and fewer give up.
     */
    static Entry entering(int entered, int absent, long waitedMs, long entryWaitMs, int minimumParty) {
        if (absent <= 0) return Entry.ENTERED;
        if (waitedMs < entryWaitMs) return Entry.WAIT;
        return entered >= minimumParty ? Entry.EJECT_ABSENT : Entry.GIVE_UP;
    }

    // ---- walking exits ----

    /** E0: a companion has waited at a walking exit for LEADER_HINT_MS. */
    static String exitNudgeLine(String leader) {
        return "ready when you are, " + leader + ". the portal's over here.";
    }

    /**
     * A companion takes a walking exit once its leader went ahead to a later stage, or stands on its stage within
     * {@code atExitPx} of the portal ({@code leaderToPortalPx} -1 when that cannot be told).
     */
    static boolean mayTakeWalkingExit(int leaderStage, int agentStage, long leaderToPortalPx, int atExitPx) {
        if (leaderStage > agentStage) return true;
        return leaderStage == agentStage && leaderToPortalPx >= 0L && leaderToPortalPx <= atExitPx;
    }

    // ---- stage 2 ----

    /**
     * The side a companion lures Stone Bugs from (0 left of the fountain, 1 right of it, -1 none). Two or more lurers
     * keep their order (today's split). One lurer takes the left while it has a bug, else the right. The carrier with
     * no lurer at all (the only companion standing) lures the same way, but only while no bug waits in the pond and no
     * diluted poison is held or lying: then it hits the bug in the pond and carries, as today.
     */
    static int lureSide(int index, int lurers, boolean leftHasTarget, boolean rightHasTarget, boolean loneCarrier,
                        boolean bugInPond, boolean bottleHeldOrLying) {
        if (lurers >= 2) return index;
        if (lurers == 1) return index == 0 ? oneLurerSide(leftHasTarget, rightHasTarget) : -1;
        if (loneCarrier && !bugInPond && !bottleHeldOrLying) return oneLurerSide(leftHasTarget, rightHasTarget);
        return -1;
    }

    private static int oneLurerSide(boolean leftHasTarget, boolean rightHasTarget) {
        return leftHasTarget ? 0 : rightHasTarget ? 1 : 0;
    }
}

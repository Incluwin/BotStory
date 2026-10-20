package server.agents.capabilities.partyquest.lmpq;

/**
 * How one non-leader member's state bears on an LMPQ companion run (P6h Step 3, the shape of the KPQ/HPQ/LPQ/RJPQ
 * tables). LMPQ's floor is three (LudiMazePQ.js minPlayers): above it a companion that went down is let out by the
 * death gate and leaves the maze (its host taps it out, in its own words), and a human who left is dropped with a
 * line; at the floor a dead companion is held only where its body does not keep the team apart (Room 16, or the
 * clear map from CLEARING on), and before the clearing it is held no longer than MEMBER_RECOVERY_TIMEOUT_MS: then it
 * is SHORT_HANDED, its hold ends, and the stock event ends as a player pressing OK would. After the clear nothing is
 * asked of anyone.
 */
final class AgentLmpqMemberVerdicts {
    enum Verdict { OK, DOWN_WAIT, REVIVED, REQUEST_TAP_OUT, DROP_HUMAN, SHORT_HANDED, IGNORE }

    /**
     * One member as one pass sees it.
     *
     * @param held        the bound event holds it now
     * @param seenHeld    the bound event was seen holding it (a member not held yet is still entering)
     * @param departed    the run already marked it departed
     * @param cleared     the event's own clear (Pietri's)
     * @param downSinceMs when it was first seen down; 0 when it is not down
     * @param clearing    the run is CLEARING or later (a body on the clear map no longer needs a timeout)
     */
    record Facts(AgentLmpqMemberState.MemberType type, int hp, boolean held, boolean seenHeld, boolean departed,
                 boolean cleared, long downSinceMs, boolean clearing, long nowMs, long timeoutMs) {
    }

    private AgentLmpqMemberVerdicts() {
    }

    /** The verdict for one non-leader member, by the rules in order. */
    static Verdict verdict(Facts facts) {
        if (facts.cleared() || facts.departed()) return Verdict.IGNORE;
        boolean human = facts.type() == AgentLmpqMemberState.MemberType.HUMAN;
        if (!facts.held()) {
            if (!facts.seenHeld()) return Verdict.OK; // still entering (or never registered: a plain hand-back)
            return human ? Verdict.DROP_HUMAN : Verdict.REQUEST_TAP_OUT;
        }
        if (facts.hp() <= 0) {
            if (!human && !facts.clearing() && facts.downSinceMs() > 0L
                    && facts.nowMs() - facts.downSinceMs() >= facts.timeoutMs()) {
                return Verdict.SHORT_HANDED;
            }
            return Verdict.DOWN_WAIT;
        }
        return facts.downSinceMs() > 0L ? Verdict.REVIVED : Verdict.OK;
    }
}

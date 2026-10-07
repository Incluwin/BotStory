package server.agents.capabilities.partyquest.lpq;

/**
 * How one member's state bears on an LPQ companion run, by HPQ's rule table (AgentHpqMemberVerdicts, itself KPQ's
 * memberVerdict), kept as LPQ's own copy. Inside the event is STAGE_1 through STAGE_9; cleared is BONUS,
 * CLAIMING_REWARD, EXITING, or the event's own clear. LPQ's floor is five (LudiPQ.js minPlayers): a downed companion is
 * waited out only while at least five others still stand. As in HPQ, a downed non-leader player is never timed out:
 * LudiPQ.js ends nothing for a dead player until he presses OK, and then its own floor check decides.
 */
final class AgentLpqMemberVerdicts {
    enum Verdict { OK, MISSING, DROP_HUMAN, DOWN_WAIT, REQUEST_TAP_OUT, REVIVED, IGNORE, DEFEATED, SHORT_HANDED }

    /**
     * One member as one tick sees it.
     *
     * @param present         online (a human) or loaded (a companion)
     * @param inBoundEvent    standing in the run's own event instance
     * @param cleared         the event's own clear (the Violet Balloon took the key), whatever the phase says yet
     * @param downSinceMs     when it was first seen down; 0 when it is not down
     * @param standingOthers  the run's other members not marked down
     */
    record Facts(boolean adopted,
                 AgentLpqSession.Phase phase,
                 AgentLpqMemberState.MemberType type,
                 boolean eventLeader,
                 boolean present,
                 int hp,
                 boolean inBoundEvent,
                 boolean leaderInBoundEvent,
                 boolean cleared,
                 long downSinceMs,
                 boolean tapOutRequested,
                 int standingOthers,
                 long nowMs,
                 long timeoutMs) {
    }

    private AgentLpqMemberVerdicts() {
    }

    /**
     * Outside a companion run, and for the event leader, any death fails it as before. In a companion run a downed
     * non-leader is waited out (the stage is not paused: the others play on) until the stock respawn, let through by
     * the departure gate, carries it out of the event: a companion is then tapped out by its host, and a human is
     * dropped once it is up outside. A companion still down after the recovery timeout ends the run. Fewer than five
     * others still standing ends the run, since LudiPQ.js ends the event below five anyway.
     */
    static Verdict verdict(Facts facts) {
        boolean human = facts.type() == AgentLpqMemberState.MemberType.HUMAN;
        boolean cleared = facts.cleared() || clearedPhase(facts.phase());
        if (!facts.present()) {
            if (!facts.adopted() || !human || facts.eventLeader()) return Verdict.MISSING;
            // After the clear an absent member only forfeits its share; before entry a changed party ends the entry.
            if (cleared) return Verdict.IGNORE;
            return insideEvent(facts.phase()) ? Verdict.DROP_HUMAN : Verdict.MISSING;
        }
        if (!facts.adopted() || facts.eventLeader()) {
            return facts.hp() <= 0 ? Verdict.DEFEATED : Verdict.OK;
        }
        // After the clear nothing is asked of a downed member; it only must not hold the rewards up.
        if (cleared) {
            return facts.hp() <= 0 || facts.downSinceMs() > 0L ? Verdict.IGNORE : Verdict.OK;
        }
        if (!insideEvent(facts.phase())) {
            return facts.hp() <= 0 ? Verdict.DEFEATED : Verdict.OK;
        }
        // With the leader out of the bound event the event check ends the run with the real reason.
        if (!facts.leaderInBoundEvent()) return Verdict.OK;
        boolean standing = facts.hp() > 0 && facts.inBoundEvent();
        boolean shortHanded = facts.standingOthers() < AgentLpqDefinition.MIN_PARTY_SIZE;
        if (facts.downSinceMs() == 0L) {
            if (standing) return Verdict.OK;
            if (human && facts.hp() > 0) return Verdict.DROP_HUMAN;
            // Up outside the event is a death no tick saw: the respawn already carried it out.
            return shortHanded ? Verdict.SHORT_HANDED : Verdict.DOWN_WAIT;
        }
        if (!human && facts.nowMs() - facts.downSinceMs() >= facts.timeoutMs()) return Verdict.DEFEATED;
        if (standing) return Verdict.REVIVED;
        if (shortHanded) return Verdict.SHORT_HANDED;
        if (facts.hp() > 0 && human) return Verdict.DROP_HUMAN;
        if (facts.hp() > 0 && !facts.tapOutRequested()) return Verdict.REQUEST_TAP_OUT;
        return Verdict.DOWN_WAIT;
    }

    /** The tower's stages, from the Red Balloon to the Violet Balloon's clear. */
    static boolean insideEvent(AgentLpqSession.Phase phase) {
        return switch (phase) {
            case STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5, STAGE_6, STAGE_7, STAGE_8, STAGE_9 -> true;
            default -> false;
        };
    }

    /** The phases after the clear. */
    static boolean clearedPhase(AgentLpqSession.Phase phase) {
        return switch (phase) {
            case BONUS, CLAIMING_REWARD, EXITING -> true;
            default -> false;
        };
    }
}

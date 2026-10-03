package server.agents.capabilities.partyquest.hpq;

/**
 * How one member's state bears on an HPQ run, by the same rule table as AgentKpqCoordinator.memberVerdict (kept as
 * HPQ's own copy until a third coordinator needs it), with one difference: a downed non-leader player is never timed
 * out, since HenesysPQ.js ends nothing for a dead player and the stage needs no one in particular. Inside the event is
 * COLLECTING_SEEDS through DELIVERING_CAKES; cleared is the bonus phases, CLAIMING_REWARD, EXITING, or the event's own
 * clear.
 */
final class AgentHpqMemberVerdicts {
    enum Verdict { OK, MISSING, DROP_HUMAN, DOWN_WAIT, REQUEST_TAP_OUT, REVIVED, IGNORE, DEFEATED, SHORT_HANDED }

    /**
     * One member as one tick sees it.
     *
     * @param present         online (a human) or loaded (a companion)
     * @param inBoundEvent    standing in the run's own event instance
     * @param cleared         the event's own clear (Growlie took the cakes), whatever the phase says yet
     * @param downSinceMs     when it was first seen down; 0 when it is not down
     * @param standingOthers  the run's other members not marked down
     */
    record Facts(boolean adopted,
                 AgentHpqSession.Phase phase,
                 AgentHpqMemberState.MemberType type,
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

    private AgentHpqMemberVerdicts() {
    }

    /**
     * Outside a companion run, and for the event leader, any death fails it as before. In a companion run a downed
     * non-leader is waited out (the stage is not paused: the Moon Bunny's clock runs regardless) until the stock
     * respawn carries it out of the event: a companion is then tapped out by its host, and a human is dropped once it
     * is up outside. A companion still down after the recovery timeout ends the run; a player may lie there waiting
     * for a Resurrection as long as he likes. Fewer than three still standing ends the run, since HenesysPQ ends the
     * event at three anyway.
     */
    static Verdict verdict(Facts facts) {
        boolean human = facts.type() == AgentHpqMemberState.MemberType.HUMAN;
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
        boolean shortHanded = facts.standingOthers() < AgentHpqDefinition.MINIMUM_PARTY_SIZE;
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

    /** Primrose Hill's stage, from the first seed to Growlie's clear. */
    static boolean insideEvent(AgentHpqSession.Phase phase) {
        return switch (phase) {
            case COLLECTING_SEEDS, PLANTING_SEEDS, DEFENDING_BUNNY, DELIVERING_CAKES -> true;
            default -> false;
        };
    }

    /** The phases after Growlie's clear. */
    static boolean clearedPhase(AgentHpqSession.Phase phase) {
        return switch (phase) {
            case BONUS_DECISION, BONUS_FARMING, CLAIMING_REWARD, EXITING -> true;
            default -> false;
        };
    }
}

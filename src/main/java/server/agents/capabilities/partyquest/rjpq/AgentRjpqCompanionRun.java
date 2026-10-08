package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;

/**
 * The run-level rules of an RJPQ companion run (Step 3), on the lease holder's pass: the leader, the members, the
 * event's end, the stall watchdog and the end after the clear, and every early end said in the companions' own words.
 * Past the clear a companion run never fails: its companions claim and leave.
 */
final class AgentRjpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqCompanionRun.class);
    private static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqCompanionRun.TIMEOUT_SLACK_MS");
    private static final long STALL_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqCompanionRun.STALL_TIMEOUT_MS");
    private static final long COMPANION_AFTER_CLEAR_LIMIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqCompanionRun.COMPANION_AFTER_CLEAR_LIMIT_MS");

    private AgentRjpqCompanionRun() {
    }

    /**
     * One pass over a bound run; false when it ended the run. After the clear the run completes once the event ends,
     * no companion is left in it or COMPANION_AFTER_CLEAR_LIMIT_MS has passed (the release takes out any still
     * inside). Before it: the event's end, the leader (offline, the lead passed, down), the members, then the stall
     * watchdog.
     */
    static boolean pass(AgentRjpqSession session, EventInstanceManager event, Character leader, long nowMs) {
        if (AgentRjpqTerminationService.pastTheClear(event)) {
            if (event.isEventDisposed() || nowMs - session.clearedAtMs() >= COMPANION_AFTER_CLEAR_LIMIT_MS
                    || !anyCompanionInEvent(session)) {
                log.info("RJPQ after clear: session={} side={} companionsLeft={} afterClearMs={}", session.sessionId(),
                        session.side().key(), anyCompanionInEvent(session), nowMs - session.clearedAtMs());
                session.complete(nowMs);
                return false;
            }
            return true;
        }
        if (event.isEventDisposed()) {
            AgentRjpqParting.Kind kind = AgentRjpqParting.endedCause(session.leaderWentDown(),
                    session.filesHandedIn(), session.lastTimeLeftMs(), TIMEOUT_SLACK_MS);
            fail(session, "The RJPQ event ended before the clear",
                    AgentRjpqParting.of(kind, session.eventLeaderName()), null, nowMs);
            return false;
        }
        boolean filesIn = leader != null && AgentRjpqStagePlay.intProperty(leader, "statusStg4") == 1;
        session.observeEvent(timeLeft(event), filesIn, leader == null ? -1 : leader.getMapId(), nowMs);
        if (leader == null) {
            fail(session, "The RJPQ event leader is offline",
                    AgentRjpqParting.of(AgentRjpqParting.Kind.GONE, session.eventLeaderName()), null, nowMs);
            return false;
        }
        if (event.getLeaderId() != session.eventLeaderId()) {
            fail(session, "The RJPQ event is no longer led by the run's leader", AgentRjpqParting.GENERIC, null, nowMs);
            return false;
        }
        if (leader.getHp() <= 0 && leader.getEventInstance() == event && session.markLeaderWentDown()) {
            log.info("RJPQ leader down: session={} side={} filesIn={}", session.sessionId(), session.side().key(),
                    session.filesHandedIn());
            Character speaker = partingSpeaker(session, leader);
            AgentRjpqDialogue.narrate(session, "D1", speaker, AgentRjpqCompanionPlay.leaderDown(leader.getName()));
        }
        if (!verdicts(session, event, nowMs)) {
            return false;
        }
        if (nowMs - session.lastProgressAtMs() >= STALL_TIMEOUT_MS) {
            fail(session, "The RJPQ run made no progress for " + STALL_TIMEOUT_MS + " ms",
                    AgentRjpqParting.of(AgentRjpqParting.Kind.TOO_LONG, session.eventLeaderName()), null, nowMs);
            return false;
        }
        return true;
    }

    /** Every non-leader member by AgentRjpqMemberVerdicts; false when it ended the run. */
    private static boolean verdicts(AgentRjpqSession session, EventInstanceManager event, long nowMs) {
        boolean inside = AgentRjpqMemberVerdicts.inside(session.phase());
        for (AgentRjpqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId()) {
                continue;
            }
            Character character = AgentRjpqCoordinator.character(id);
            AgentRjpqMemberVerdicts.Verdict verdict = AgentRjpqMemberVerdicts.verdict(new AgentRjpqMemberVerdicts.Facts(
                    inside, member.memberType(), character != null, character == null ? 0 : character.getHp(),
                    character != null && character.getEventInstance() == event, false, session.downSinceMs(id),
                    standingCompanions(session, id)));
            switch (verdict) {
                case DOWN_WAIT -> session.markDown(id, nowMs);
                case REVIVED -> session.clearDown(id);
                case SHORT_HANDED -> {
                    fail(session, character.getName() + " went down, and no companion of the RJPQ run stands",
                            AgentRjpqParting.of(AgentRjpqParting.Kind.WENT_DOWN, session.eventLeaderName()),
                            character, nowMs);
                    return false;
                }
                case DROP_HUMAN, MISSING -> {
                    if (session.claimLogMark("verdict-" + verdict + "-" + id)) {
                        log.info("RJPQ member: session={} side={} member={} verdict={}", session.sessionId(),
                                session.side().key(), id, verdict);
                    }
                }
                default -> {
                }
            }
        }
        return true;
    }

    /**
     * The departure gate's end of a companion run with no other companion standing (8.4), on the dying companion's
     * own tick, in its own words.
     */
    static void shortHanded(AgentRjpqSession session, int dyingId, long nowMs) {
        Character dying = AgentRjpqCoordinator.character(dyingId);
        fail(session, (dying == null ? "Companion " + dyingId : dying.getName())
                        + " went down, and no companion of the RJPQ run stands",
                AgentRjpqParting.of(AgentRjpqParting.Kind.WENT_DOWN, session.eventLeaderName()), dying, nowMs);
    }

    /**
     * Ends the run with its companions' own words. A run that already ended or is being released is left alone; past
     * the clear a companion run never fails. Only the run's first failure speaks and fails it. SILENT, or a run that
     * never went in, says nothing; otherwise the given speaker, else a companion on the leader's map, else any
     * companion standing in the event, says the parting once ('RJPQ parting:').
     */
    static void fail(AgentRjpqSession session, String reason, AgentRjpqParting parting, Character speaker,
                     long nowMs) {
        if (!session.companionAdopted()) {
            AgentRjpqCoordinator.fail(session, reason, nowMs);
            return;
        }
        if (session.terminal() || session.terminationStarted()
                || AgentRjpqTerminationService.pastTheClear(session.eventInstance()) || !session.claimFailure()) {
            return;
        }
        if (parting != null && parting.kind() != AgentRjpqParting.Kind.SILENT && session.eventInstance() != null) {
            Character who = speaker != null ? speaker
                    : partingSpeaker(session, AgentRjpqCoordinator.character(session.eventLeaderId()));
            log.info("RJPQ parting: session={} side={} kind={} speaker={}", session.sessionId(), session.side().key(),
                    parting.kind(), who == null ? "none" : who.getName());
            AgentRjpqDialogue.narrate(session, "parting", who, parting.line());
        }
        AgentRjpqCoordinator.failClaimed(session, reason, nowMs);
    }

    /** A companion on the leader's map, else any companion standing in the run's event, else any loaded one. */
    static Character partingSpeaker(AgentRjpqSession session, Character leader) {
        Character standing = null;
        Character loaded = null;
        for (AgentRjpqMemberState member : session.members()) {
            if (!member.agent()) {
                continue;
            }
            Character agent = AgentRjpqCoordinator.character(member.characterId());
            if (agent == null) {
                continue;
            }
            boolean up = agent.getHp() > 0 && agent.getEventInstance() == session.eventInstance();
            if (up && leader != null && agent.getMapId() == leader.getMapId()) {
                return agent;
            }
            if (up && standing == null) {
                standing = agent;
            }
            if (loaded == null) {
                loaded = agent;
            }
        }
        return standing != null ? standing : loaded;
    }

    /** The run's companions other than {@code exceptId} that are online, up and in the run's event. */
    static int standingCompanions(AgentRjpqSession session, int exceptId) {
        int standing = 0;
        for (AgentRjpqMemberState member : session.members()) {
            if (!member.agent() || member.characterId() == exceptId) {
                continue;
            }
            Character agent = AgentRjpqCoordinator.character(member.characterId());
            if (agent != null && agent.getHp() > 0 && session.eventInstance() != null
                    && agent.getEventInstance() == session.eventInstance()) {
                standing++;
            }
        }
        return standing;
    }

    private static boolean anyCompanionInEvent(AgentRjpqSession session) {
        for (AgentRjpqMemberState member : session.members()) {
            Character agent = member.agent() ? AgentRjpqCoordinator.character(member.characterId()) : null;
            if (agent != null && session.eventInstance() != null
                    && agent.getEventInstance() == session.eventInstance()) {
                return true;
            }
        }
        return false;
    }

    private static long timeLeft(EventInstanceManager event) {
        try {
            return event.getTimeLeft();
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }
}

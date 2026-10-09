package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final long ENTRY_HOLD_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqCompanionRun.ENTRY_HOLD_GRACE_MS");

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
            session.pace().markDown(leader.getId());
            log.info("RJPQ leader down: session={} side={} filesIn={}", session.sessionId(), session.side().key(),
                    session.filesHandedIn());
            Character speaker = partingSpeaker(session, leader);
            AgentRjpqDialogue.narrate(session, "D1", speaker, AgentRjpqCompanionPlay.leaderDown(leader.getName()));
        }
        observeDepartures(session, event, nowMs);
        if (AgentRjpqTerminationService.humansCarryOn(session, nowMs)) {
            return false;
        }
        if (!verdicts(session, event, nowMs)) {
            return false;
        }
        paceMarks(session, event, leader, nowMs);
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
            if (id == session.eventLeaderId() || session.departed(id)) {
                continue;
            }
            Character character = AgentRjpqCoordinator.character(id);
            AgentRjpqMemberVerdicts.Verdict verdict = AgentRjpqMemberVerdicts.verdict(new AgentRjpqMemberVerdicts.Facts(
                    inside, member.memberType(), character != null, character == null ? 0 : character.getHp(),
                    character != null && character.getEventInstance() == event, false, session.downSinceMs(id),
                    standingCompanions(session, id)));
            switch (verdict) {
                case DOWN_WAIT -> {
                    if (session.markDown(id, nowMs)) {
                        session.pace().markDown(id);
                    }
                }
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
     * A member the bound event held and no longer holds (a disconnect, a walk-out), or one it never held once
     * ENTRY_HOLD_GRACE_MS has passed since the bind (the event started without it), has departed instead of stalling
     * the run: it is logged once ('RJPQ departed:'), the run stops counting it, and a departed Agent is handed back to
     * its owner at once. A dead member the event still holds has not departed (its gate holds it). Before the clear
     * only.
     */
    private static void observeDepartures(AgentRjpqSession session, EventInstanceManager event, long nowMs) {
        if (session.terminationStarted()) {
            return;
        }
        Set<Integer> held = new HashSet<>();
        for (Character player : event.getPlayers()) {
            if (player != null) {
                held.add(player.getId());
            }
        }
        session.noteHeld(held);
        boolean entryOver = nowMs - session.enteredEventAtMs() >= ENTRY_HOLD_GRACE_MS;
        for (AgentRjpqMemberState member : session.members()) {
            int id = member.characterId();
            if (held.contains(id) || !session.seenHeld(id) && !entryOver || !session.markDeparted(id)) {
                continue;
            }
            Character character = AgentRjpqCoordinator.character(id);
            log.info("RJPQ departed: session={} side={} member={}({}) type={} map={} phase={} registered={} reason={}",
                    session.sessionId(), session.side().key(), character == null ? "-" : character.getName(), id,
                    member.memberType(), character == null ? "-" : String.valueOf(character.getMapId()),
                    session.phase(), held.size(), session.seenHeld(id) ? "left" : "never-registered");
            if (member.agent()) {
                handBack(session, id, nowMs);
            }
        }
    }

    /** Hands a departed Agent back to its owner: the run no longer routes it and the lifecycle rehomes it. */
    private static void handBack(AgentRjpqSession session, int id, long nowMs) {
        try {
            session.releaseHold(id, nowMs);
            AgentRjpqFullRateSimulation.apply(session, id, AgentRuntimeRegistry.findByAgentCharacterId(id), false);
            AgentRjpqSessionRegistry.unindexMember(session, id);
            AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
            if (engagement != null) {
                AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
            }
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, session.side().key(), nowMs);
        } catch (RuntimeException failure) {
            log.warn("RJPQ departed member hand-back failed: session={} member={}", session.sessionId(), id, failure);
        }
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
            if (!member.agent() || session.departed(member.characterId())) {
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
            if (!member.agent() || member.characterId() == exceptId || session.departed(member.characterId())) {
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

    /**
     * The pace boundaries the run's pass sees (Step 4): both Dark Lab doors open, both files in the leader's bag, the
     * files in, every standing companion out of the Lab Units, the leader out of his. Never throws.
     */
    private static void paceMarks(AgentRjpqSession session, EventInstanceManager event, Character leader,
                                  long nowMs) {
        try {
            AgentRjpqSide side = session.side();
            AgentRjpqPaceMarks pace = session.pace();
            MapleMap lab = event.getMapInstance(side.map(AgentRjpqDefinition.SPECIAL_LAB_MAP));
            if (lab != null && open(lab, side.reactorName(AgentRjpqDefinition.LAB_ONE_DOOR))
                    && open(lab, side.reactorName(AgentRjpqDefinition.LAB_TWO_DOOR))) {
                pace.mark(AgentRjpqPaceMarks.Boundary.DOORS_OPEN, nowMs);
            }
            var actions = AgentRjpqCoordinator.actions();
            if (actions.itemCount(leader, AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID) > 0
                    && actions.itemCount(leader, AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID) > 0) {
                pace.mark(AgentRjpqPaceMarks.Boundary.FILES_HELD, nowMs);
            }
            if (session.filesHandedIn()) {
                pace.mark(AgentRjpqPaceMarks.Boundary.FILES_IN, nowMs);
            }
            if (pastTheUnits(side, leader)) {
                pace.mark(AgentRjpqPaceMarks.Boundary.LEADER_OUT, nowMs);
            }
            int standing = 0;
            int out = 0;
            for (AgentRjpqMemberState member : session.members()) {
                Character agent = member.agent() ? AgentRjpqCoordinator.character(member.characterId()) : null;
                if (agent != null && agent.getHp() > 0 && agent.getEventInstance() == event) {
                    standing++;
                    out += pastTheUnits(side, agent) ? 1 : 0;
                }
            }
            if (standing > 0 && out == standing) {
                pace.mark(AgentRjpqPaceMarks.Boundary.COMPANIONS_OUT, nowMs);
            }
        } catch (RuntimeException failure) {
            log.debug("RJPQ pace marks unreadable: session={}", session.sessionId(), failure);
        }
    }

    private static boolean open(MapleMap map, String reactorName) {
        Reactor reactor = map.getReactorByName(reactorName);
        return reactor != null && reactor.getState() == 1;
    }

    private static boolean pastTheUnits(AgentRjpqSide side, Character character) {
        int map = side.zenumistMap(character.getMapId());
        return map == AgentRjpqDefinition.CENTER_ENTRANCE_MAP || map == AgentRjpqDefinition.CENTER_LAB_MAP;
    }

    /** The party is inside: who entered, and each member's power from the side's warmed profile (empty while cold). */
    static void markEntered(AgentRjpqSession session, long nowMs) {
        List<Integer> entrants = new ArrayList<>();
        List<AgentPqMemberPower> power = new ArrayList<>();
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(session.side().key());
        boolean readable = profile != null;
        for (AgentRjpqMemberState member : session.members()) {
            entrants.add(member.characterId());
            if (!readable) {
                continue;
            }
            Character character = AgentRjpqCoordinator.character(member.characterId());
            if (character == null) {
                readable = false;
                continue;
            }
            try {
                power.add(AgentPqMemberPower.of(character, profile,
                        member.memberType() == AgentRjpqMemberState.MemberType.HUMAN,
                        member.characterId() == session.eventLeaderId()));
            } catch (RuntimeException | LinkageError failure) {
                readable = false;
                log.debug("RJPQ pace power unreadable: session={} member={}", session.sessionId(),
                        member.characterId(), failure);
            }
        }
        session.pace().markEntered(readable ? power : List.of(), entrants, nowMs);
    }

    private static long timeLeft(EventInstanceManager event) {
        try {
            return event.getTimeLeft();
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }
}

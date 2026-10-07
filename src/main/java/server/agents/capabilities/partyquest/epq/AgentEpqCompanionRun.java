package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * A companion run's side effects (a player leading his own companions, adopted through AgentEpqAdmissionService.admit),
 * called from the coordinator at its gated points: who speaks, what the companions say, and how the run waits, carries
 * on and ends. Stock, Agent-led and GM-observed runs never come here. Nothing here holds the session monitor while it
 * moves a character, drops, chats or logs.
 */
final class AgentEpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqCompanionRun.class);
    static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.COMPANION_ENTRY_WAIT_MS");
    static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.TIMEOUT_SLACK_MS");
    static final int LEADER_AT_EXIT_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.LEADER_AT_EXIT_PX");
    static final long LEADER_HINT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.LEADER_HINT_MS");
    static final String QUEST_KEY = "epq";

    private AgentEpqCompanionRun() {
    }

    // ---- who speaks ----

    /** True when the line was said now; each key is said once a run, and a missing speaker leaves it unclaimed. */
    static boolean narrate(AgentEpqSession session, Character speaker, String key, String line) {
        if (speaker == null || line == null || !session.narrateOnce(key)) return false;
        AgentEpqDialogue.sayMapNow(session.sessionId(), key, speaker, line);
        return true;
    }

    /** A companion run's coordination line, while its host has the members' coordination chat on. */
    static boolean coordinate(AgentEpqSession session, Character speaker, String key, String line) {
        if (!session.companionAdopted() || !session.memberCoordinationChatEnabled()) return false;
        return narrate(session, speaker, key, line);
    }

    /**
     * Who says a line the leader should hear: the first companion (session order) that is online, up, not departed, in
     * the run's bound event and on the leader's map; null when none is (the line waits for a later pass).
     */
    static Character narrator(AgentEpqSession session, Character leader) {
        if (leader == null) return null;
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null && agent.getMapId() == leader.getMapId()) return agent;
        }
        return null;
    }

    /** The first companion standing in the run's bound event, on any map; null when none is. */
    static Character anyStanding(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null) return agent;
        }
        return null;
    }

    /** A companion of the run that is online, up, not departed and in the run's bound event; null otherwise. */
    static Character standing(AgentEpqSession session, AgentEpqMemberState member) {
        if (member == null || member.memberType() != AgentEpqMemberState.MemberType.AGENT
                || session.departed(member.characterId())) {
            return null;
        }
        Character agent = AgentEpqCoordinator.character(member.characterId());
        return agent != null && agent.getHp() > 0 && inBoundEvent(session, agent) ? agent : null;
    }

    /** This character stands in the run's bound event instance. */
    static boolean inBoundEvent(AgentEpqSession session, Character character) {
        EventInstanceManager bound = session.eventInstance();
        return character != null && bound != null && character.getEventInstance() == bound;
    }

    // ---- the entry ----

    /**
     * The run's backstop clock: from the party's entry (the bind) plus TIMEOUT_SLACK_MS, so EllinPQ's own 30-minute
     * timer, started at the leader's Ellin click, always ends the event first; never before the entry and never past
     * the clear.
     */
    static boolean timerExpired(AgentEpqSession session, long nowMs, long eventTimeoutMs) {
        long entered = session.enteredEventAtMs();
        return entered > 0L && !AgentEpqTerminationService.pastTheClear(session.eventInstance())
                && nowMs - entered >= eventTimeoutMs + TIMEOUT_SLACK_MS;
    }

    /**
     * The leader is offline. Before the party went in (no bound event) the entry wait still runs, as it does for a
     * leader online; true when this owns the pass. Otherwise the coordinator's watchdog keeps watch, as today.
     */
    static boolean leaderOffline(AgentEpqSession session, long nowMs) {
        if (session.eventInstance() != null) return false;
        beforeEntry(session, null, nowMs);
        return true;
    }

    /**
     * The leader clicks Ellin himself. Until he stands in an event on an EPQ map the run waits COMPANION_ENTRY_WAIT_MS
     * from its admission, then ends without a word (the desk already answered him). Once he is in, every member must
     * have been seen in the bound event within COMPANION_ENTRY_WAIT_MS of his entry, whatever phase he has reached by
     * then (a leader who walks on at once skips ENTERING): the absent ones are taken out of the run while at least four
     * entered, and with fewer the run ends without a word. Past the clear nothing is counted. True when it ended the
     * run.
     */
    static boolean beforeEntry(AgentEpqSession session, Character leader, long nowMs) {
        EventInstanceManager bound = session.eventInstance();
        if (bound == null) {
            boolean leaderInside = leader != null && leader.getEventInstance() != null
                    && AgentEpqDefinition.isEventMap(leader.getMapId());
            if (!leaderInside && nowMs - session.startedAtMs() >= COMPANION_ENTRY_WAIT_MS) {
                AgentEpqCoordinator.fail(session,
                        "The companion run's leader did not take the party into EPQ within the entry wait", nowMs);
                return true;
            }
            return false;
        }
        long enteredAtMs = session.enteredEventAtMs();
        if (enteredAtMs <= 0L || AgentEpqTerminationService.pastTheClear(bound)) return false;
        markSeen(session);
        List<AgentEpqMemberState> absent = new ArrayList<>();
        for (AgentEpqMemberState member : session.members()) {
            if (!session.seenInEvent(member.characterId()) && !session.departed(member.characterId())) {
                absent.add(member);
            }
        }
        switch (AgentEpqCompanionPlay.entering(session.memberCount() - absent.size(), absent.size(),
                nowMs - enteredAtMs, COMPANION_ENTRY_WAIT_MS, AgentEpqDefinition.MIN_PARTY_SIZE)) {
            case EJECT_ABSENT -> {
                for (AgentEpqMemberState member : absent) departMember(session, member, "did not enter", nowMs);
                return false;
            }
            case GIVE_UP -> {
                AgentEpqCoordinator.fail(session,
                        "Fewer than four of the companion run entered EPQ within the entry wait", nowMs);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Marks every member standing in the bound event as seen there. */
    static void markSeen(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character character = AgentEpqCoordinator.character(member.characterId());
            if (inBoundEvent(session, character)) session.markSeenInEvent(member.characterId());
        }
    }

    /**
     * Takes one member out of the run and both indexes (out of the event first, while still in it) and hands a
     * companion back to its owner. A tick already running for it finds no member state and returns.
     */
    static void departMember(AgentEpqSession session, AgentEpqMemberState member, String reason, long nowMs) {
        int id = member.characterId();
        Character character = AgentEpqCoordinator.character(id);
        EventInstanceManager event = session.eventInstance();
        if (character != null && event != null && character.getEventInstance() == event) {
            try {
                event.exitPlayer(character);
            } catch (RuntimeException failure) {
                log.warn("EPQ member exit failed: session={} member={}", session.sessionId(), id, failure);
            }
        }
        log.warn("EPQ member left the run and was ejected: session={} member={} type={} phase={} reason={} "
                        + "remainingBefore={}", session.sessionId(), id, member.memberType(), session.phase(), reason,
                session.memberCount());
        session.removeMember(id);
        AgentEpqSessionRegistry.unindexMember(session, id);
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
        if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
        if (member.memberType() == AgentEpqMemberState.MemberType.AGENT) {
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, QUEST_KEY, nowMs);
        }
    }

    // ---- walking exits ----

    /**
     * A walking exit (portal 4 of the entrance, portal 3 of stages 1 and 2: per-player warps) is taken together with
     * the leader: once he stands within LEADER_AT_EXIT_PX of it, or is already on a later stage. Until then the
     * companion walks to the portal and waits there, and one companion says E0 once a companion has waited
     * LEADER_HINT_MS. True while it waits.
     */
    static boolean holdAtWalkingExit(AgentEpqSession session, AgentRuntimeEntry entry, Character agent, int portalId,
                                     long nowMs) {
        Character leader = AgentEpqCoordinator.character(session.eventLeaderId());
        Point portal = AgentEpqCoordinator.actions().portalPosition(agent, portalId);
        boolean leaderInside = inBoundEvent(session, leader);
        int leaderStage = leaderInside ? AgentEpqDefinition.stageForMap(leader.getMapId()) : -1;
        long leaderToPortal = leaderInside && leader.getMapId() == agent.getMapId() && portal != null
                && leader.getPosition() != null ? Math.round(leader.getPosition().distance(portal)) : -1L;
        String hint = "exit-" + agent.getMapId();
        if (AgentEpqCompanionPlay.mayTakeWalkingExit(leaderStage, AgentEpqDefinition.stageForMap(agent.getMapId()),
                leaderToPortal, LEADER_AT_EXIT_PX)) {
            session.clearLeaderHint(hint);
            return false;
        }
        AgentEpqCoordinator.walkToPortal(entry, agent, portalId);
        if (leaderInside && leader.getMapId() == agent.getMapId()
                && nowMs - session.leaderHintSince(hint, nowMs) >= LEADER_HINT_MS) {
            coordinate(session, agent, "exit-nudge-" + agent.getMapId(),
                    AgentEpqCompanionPlay.exitNudgeLine(leader.getName()));
        }
        return true;
    }
}

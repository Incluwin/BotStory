package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

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
    static final long CLEAR_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.CLEAR_WAIT_MS");
    static final long GOLEM_MARBLE_LEADER_FIRST_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCompanionRun.GOLEM_MARBLE_LEADER_FIRST_MS");
    static final String QUEST_KEY = "epq";

    private AgentEpqCompanionRun() {
    }

    /**
     * A companion run that still acts: adopted, not ended and not being released. Every companion row of the
     * coordinator checks it, so a run another thread ended (a failure, the host's stop) never drops, leaves, ejects or
     * speaks again, and is never failed a second time over its first reason.
     */
    static boolean acting(AgentEpqSession session) {
        return session.companionAdopted() && !session.terminal() && !session.terminationStarted();
    }

    /** The live party, as the first online member of the run sees it; null when none has one (HPQ's shape). */
    static AgentPartySnapshot liveParty(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character character = AgentEpqCoordinator.character(member.characterId());
            AgentPartySnapshot party = character == null ? null : AgentPartyGatewayRuntime.party().snapshot(character);
            if (party != null) return party;
        }
        return null;
    }

    /** Whether the run's leader still leads the live party (false when no member has a party). */
    static boolean leaderLeadsLiveParty(AgentEpqSession session) {
        AgentPartySnapshot party = liveParty(session);
        return party != null && party.members().stream().anyMatch(member -> member != null && member.leader()
                && member.id() == session.eventLeaderId());
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
        EventInstanceManager bound = session.eventInstance();
        if (bound == null) {
            beforeEntry(session, null, nowMs);
            return true;
        }
        if (AgentEpqTerminationService.pastTheClear(bound)) {
            // Past the clear an offline leader is not waited on: the companions leave (Step 2.9).
            afterClear(session, null, nowMs);
            AgentEpqCoordinator.completeWhenOut(session, nowMs);
            return true;
        }
        return false;
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

    // ---- after the golem ----

    /**
     * The lease holder's pass past the clear (Step 2.9). The companions tell their leader that only he can open the
     * way out (E16, or E16d when he is down), point out the golem's marble (E16m) and say when the work Agent took it
     * (E16t). They leave once he let go (offline, down, out of the event, no longer leading the live party) or after
     * CLEAR_WAIT_MS with him still on the golem's map; a leave not yet gone is called off when he took out00 in time.
     * Before it goes, a companion holding the golem's marble leaves it with him (afterClearMember). Never fails the run.
     */
    static void afterClear(AgentEpqSession session, Character leader, long nowMs) {
        long clearSeenAtMs = session.clearSeenAtMs(nowMs);
        if (session.leftAfterClear()) return;
        boolean leaderInEvent = inBoundEvent(session, leader);
        boolean leaderStands = leaderInEvent && leader.getHp() > 0;
        boolean onBossMap = leader != null && leader.getMapId() == AgentEpqDefinition.BOSS_MAP;
        Character narrator = narrator(session, leader);
        if (leaderInEvent && onBossMap) {
            coordinate(session, narrator, "cleared", leaderStands
                    ? AgentEpqCompanionPlay.clearedLine(leader.getName())
                    : AgentEpqCompanionPlay.clearedLeaderDownLine(leader.getName()));
            if (leaderStands && golemMarbleLying(leader)) {
                coordinate(session, narrator, "golem-marble", AgentEpqCompanionPlay.golemMarbleLine(leader.getName()));
            }
        }
        String why = AgentEpqCompanionPlay.leaveWhy(leader != null, leader == null ? 0 : leader.getHp(),
                leaderInEvent, leaderLeadsLiveParty(session), onBossMap, nowMs - clearSeenAtMs, CLEAR_WAIT_MS);
        if (why == null && leaderStands && leader.getMapId() == AgentEpqDefinition.REWARD_MAP) {
            if (session.cancelLeave()) {
                log.info("EPQ after clear: session={} action=stay why=out00 leader={}", session.sessionId(),
                        leader.getName());
            }
        } else if (why != null) {
            session.decideLeave(nowMs, why);
        }
        if (session.leaveDecidedAtMs() > 0L) {
            leaveWhenReady(session, leader, nowMs);
            return;
        }
        Character workAgent = AgentEpqCoordinator.character(session.workAgentId());
        if (leaderStands && workAgent != null && inBoundEvent(session, workAgent)
                && AgentEpqCoordinator.actions().itemCount(workAgent, AgentEpqDefinition.GOLEMS_MARBLE) > 0) {
            coordinate(session, workAgent, "golem-marble-taken", AgentEpqCompanionPlay.GOLEM_MARBLE_TAKEN);
        }
    }

    /**
     * The watchdog past the clear (Step 2.9): the companions leave instead of failing the run (why=stall). A run whose
     * companions already left and still stalls is complete: a cleared companion run never fails.
     */
    static void stalledAfterClear(AgentEpqSession session, long nowMs) {
        if (session.leftAfterClear()) {
            log.info("EPQ after clear: session={} action=complete why=stall", session.sessionId());
            session.complete(nowMs);
            return;
        }
        session.decideLeave(nowMs, "stall");
        leaveWhenReady(session, AgentEpqCoordinator.character(session.eventLeaderId()), nowMs);
    }

    /**
     * A decided leave goes once no companion in the event holds the golem's marble, or AgentEpqHandOver.TIMEOUT_MS
     * after the decision: E16w while the leader still stands in the event, the EXITING phase and the event's own exit
     * for the companions only ('EPQ after clear: ... action=leave').
     */
    static void leaveWhenReady(AgentEpqSession session, Character leader, long nowMs) {
        int holders = golemMarbleHolders(session);
        if (!AgentEpqCompanionPlay.leaveGoes(holders, nowMs - session.leaveDecidedAtMs(), AgentEpqHandOver.TIMEOUT_MS)
                || !session.markLeftAfterClear()) {
            return;
        }
        if (leader != null && leader.getHp() > 0 && inBoundEvent(session, leader)) {
            coordinate(session, narrator(session, leader), "wait-outside", AgentEpqCompanionPlay.WAIT_OUTSIDE);
        }
        log.info("EPQ after clear: session={} action=leave why={} leader={} marble={}", session.sessionId(),
                session.leaveWhy(), leader == null ? String.valueOf(session.eventLeaderId()) : leader.getName(),
                holders > 0 ? "held" : session.golemMarble());
        AgentEpqCoordinator.transition(session, AgentEpqSession.Phase.EXITING, nowMs);
        AgentEpqTerminationService.exitAgentsOnly(session);
    }

    /**
     * A companion on the golem's map past the clear, on its own tick (Step 2.9). Out00 is the leader's
     * (party6_out.js), so no companion tries it, and the invisible dummy is left alone. Before the leave the work Agent
     * takes the golem's marble once the leader had GOLEM_MARBLE_LEADER_FIRST_MS to take it; once the leave is decided a
     * companion holding it hands it over at the leader's feet (AgentEpqHandOver, no NPC: an unreachable leader or a
     * walk past TIMEOUT_MS gets it where the companion stands), or drops it where it stands without a word when he
     * cannot take it. Everyone else stands.
     */
    static void afterClearMember(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        int held = AgentEpqCoordinator.actions().itemCount(agent, AgentEpqDefinition.GOLEMS_MARBLE);
        if (session.leaveDecidedAtMs() > 0L) {
            if (held <= 0 || session.leftAfterClear()) {
                AgentEpqCoordinator.actions().stop(entry);
                return;
            }
            Character leader = AgentEpqCoordinator.character(session.eventLeaderId());
            boolean leaderTakesIt = leader != null && leader.getHp() > 0 && inBoundEvent(session, leader)
                    && leader.getMap() == agent.getMap();
            if (!leaderTakesIt) {
                AgentEpqHandOver.dropHere(session, entry, agent, member, AgentEpqDefinition.GOLEMS_MARBLE, held, nowMs);
                return;
            }
            AgentEpqHandOver.tick(session, entry, agent, member, AgentEpqDefinition.GOLEMS_MARBLE, 0, nowMs);
            return;
        }
        if (held <= 0 && agent.getId() == session.workAgentId()) {
            MapItem marble = AgentMapPerception.items(agent.getMap()).stream()
                    .filter(drop -> drop != null && !drop.isPickedUp() && drop.getPosition() != null
                            && drop.getItemId() == AgentEpqDefinition.GOLEMS_MARBLE)
                    .filter(drop -> AgentEpqCompanionPlay.golemMarbleMayBeTaken(true, nowMs - drop.getDropTime(),
                            GOLEM_MARBLE_LEADER_FIRST_MS))
                    .findFirst().orElse(null);
            if (marble != null) {
                AgentEpqCoordinator.pickUp(entry, agent, marble);
                return;
            }
        }
        AgentEpqCoordinator.actions().stop(entry);
    }

    /** A golem's marble lies on this leader's map. */
    private static boolean golemMarbleLying(Character leader) {
        return leader.getMap() != null && AgentMapPerception.items(leader.getMap()).stream()
                .anyMatch(drop -> drop != null && !drop.isPickedUp()
                        && drop.getItemId() == AgentEpqDefinition.GOLEMS_MARBLE);
    }

    /** The run's companions in its bound event that hold the golem's marble (read from the characters). */
    static int golemMarbleHolders(AgentEpqSession session) {
        int holders = 0;
        for (AgentEpqMemberState member : session.members()) {
            if (member.memberType() != AgentEpqMemberState.MemberType.AGENT) continue;
            Character agent = AgentEpqCoordinator.character(member.characterId());
            if (inBoundEvent(session, agent)
                    && AgentEpqCoordinator.actions().itemCount(agent, AgentEpqDefinition.GOLEMS_MARBLE) > 0) {
                holders++;
            }
        }
        return holders;
    }
}

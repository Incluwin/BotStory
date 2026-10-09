package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/** Idempotent LMPQ cleanup that cannot address another PQ's state. */
final class AgentLmpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqTerminationService.class);

    private AgentLmpqTerminationService() { }

    /**
     * Lets an ended run go, from the tick (a terminal run), a stop request (a terminal run) or a forced stop (any
     * run). Past the clear no release takes a human out or disposes the event under him, in any mode (LMPQ base fix
     * 2): a completed run is released, any other lets only its Agents go (failAfterClear, which disposes the event
     * only once nobody is left in it), so a human still in the event keeps his reward. Before the clear,
     * {@code stockDispose} is the dispose a stock run gets, as it always has: the tick passes whether the run failed,
     * a forced stop true and a stop request false. A companion run before the clear is released without a dispose
     * when it completed and disposed otherwise, whoever ends it, because its party is kept and nobody else would end
     * the event.
     */
    static void finish(AgentLmpqSession session, String reason, long nowMs, boolean stockDispose) {
        if (session == null) return;
        boolean completed = session.phase() == AgentLmpqSession.Phase.COMPLETED;
        if (pastTheClear(session)) {
            if (completed) release(session, reason, nowMs, false);
            else failAfterClear(session, reason, nowMs);
            return;
        }
        if (!session.companionAdopted()) {
            release(session, reason, nowMs, stockDispose);
            return;
        }
        release(session, reason, nowMs, !completed);
    }

    /**
     * True once the bound event reports its clear (Pietri's clearPQ). Never read from the session's phase: the
     * coordinator fails a run before it is released, so a phase test would hold for every failure.
     */
    static boolean pastTheClear(AgentLmpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) return false;
        try {
            return event.isEventCleared();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Ends a run after its clear: its companions leave through the event's own exit, and the event and the players
     * still in it stay as they are, so nobody loses his reward. The release is claimed before anyone leaves, so a
     * second failure takes no one out twice. When nobody is left in the event, it is disposed: LudiMazePQ.js stopped
     * its timer at the clear, and a player's exit unregisters him before the script could end it.
     */
    static void failAfterClear(AgentLmpqSession session, String reason, long nowMs) {
        if (session == null || !session.beginTermination()) return;
        EventInstanceManager event = session.eventInstance();
        exitAgentsOnly(session);
        disposeWhenEmpty(session, event);
        release(session, reason, nowMs, false, true);
    }

    /**
     * Takes a run's Agents out of its event, through the event's own exit (809050017, from which a companion host's
     * handback has them follow their owner), and leaves the event and its humans as they are.
     */
    static void exitAgentsOnly(AgentLmpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) return;
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .map(AgentLmpqMemberState::characterId)
                .collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("LMPQ companion exit failed: event={} member={}", event.getName(), participant.getId(),
                        failure);
            }
        }
    }

    /** Disposes {@code event} when it holds no player any more; a failure is logged and nothing else changes. */
    static void disposeWhenEmpty(AgentLmpqSession session, EventInstanceManager event) {
        if (event == null) return;
        try {
            if (event.getPlayers().isEmpty()) event.dispose();
        } catch (RuntimeException failure) {
            log.warn("LMPQ empty event dispose failed: session={}", session == null ? "-" : session.sessionId(),
                    failure);
        }
    }

    /** The companions' HP/MP potions, by name, as 'LMPQ potions:' logs them: {Mira:40/50, Kes:12/30}. */
    static String potions(AgentLmpqSession session) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .map(member -> AgentLmpqCoordinator.character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/" + gateway.mpPotionCount(agent))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * A companion run's party is its owner's own: his companions stay in it whatever the outcome, as KPQ, HPQ, LPQ and
     * EPQ keep a party they did not form. Every other run's Agents leave it, as they always have.
     */
    static boolean keepPartyAfterRelease(boolean companionRun) {
        return companionRun;
    }

    static void release(AgentLmpqSession session, String reason, long nowMs, boolean dispose) {
        release(session, reason, nowMs, dispose, false);
    }

    /** {@code claimed}: the caller already began the termination (failAfterClear); every other release claims it. */
    private static void release(AgentLmpqSession session, String reason, long nowMs, boolean dispose,
                                boolean claimed) {
        if (session == null || !claimed && !session.beginTermination()) return;
        boolean companionRun = session.companionAdopted();
        boolean pastTheClear = pastTheClear(session);
        session.members().forEach(member -> {
            if (session.departed(member.characterId())) return; // handed back already: its new activity is its own
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        });
        if (companionRun) {
            quietly(session, "log-potions", () -> log.info("LMPQ potions: session={} start={} end={}",
                    session.sessionId(), session.potionsAtEntry(), potions(session)));
        }
        EventInstanceManager event = session.eventInstance();
        if (dispose && event != null) {
            for (Character participant : new ArrayList<>(event.getPlayers())) {
                try { event.exitPlayer(participant); } catch (RuntimeException ignored) { }
            }
            try { event.dispose(); } catch (RuntimeException ignored) { }
        }
        if (!keepPartyAfterRelease(companionRun)) {
            session.members().stream()
                    .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                    .map(member -> AgentLmpqCoordinator.character(member.characterId()))
                    .filter(java.util.Objects::nonNull).filter(AgentPartyGatewayRuntime.party()::hasParty)
                    .forEach(AgentPartyGatewayRuntime.party()::leaveCurrentParty);
        }
        session.clearEventInstance();
        boolean completed = session.phase() == AgentLmpqSession.Phase.COMPLETED;
        if (!completed) session.fail(reason, nowMs);
        AgentLmpqSessionRegistry.remove(session);
        // A companion run's pace goes to the host's clear-time records; a stock run reports none.
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                companionRun ? pace(session, nowMs) : null);
        quietly(session, "log-released", () -> log.info(
                "LMPQ session released: session={} outcome={} members={} companionRun={} pastTheClear={}",
                session.sessionId(), completed ? "completed" : reason, session.memberCount(), companionRun,
                pastTheClear));
    }

    /**
     * A companion run's pace for the host's records, its span lines logged; a report that fails leaves it null rather
     * than the release unfinished.
     */
    private static AgentPqPace pace(AgentLmpqSession session, long nowMs) {
        AgentLmpqPaceReport.logSpans(session, nowMs);
        try {
            return AgentLmpqPaceReport.of(session, nowMs);
        } catch (RuntimeException failure) {
            log.warn("LMPQ pace report failed: session={}", session.sessionId(), failure);
            return null;
        }
    }

    /** Runs a step the release added for its lines; a failure is logged and the release goes on. */
    private static void quietly(AgentLmpqSession session, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("LMPQ cleanup failed but release will continue: session={} operation={}",
                    session.sessionId(), operation, failure);
        }
    }
}

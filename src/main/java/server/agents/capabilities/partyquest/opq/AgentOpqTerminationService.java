package server.agents.capabilities.partyquest.opq;

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

/** Idempotent OPQ cleanup; never moves a live run as a recovery shortcut. */
final class AgentOpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqTerminationService.class);

    private AgentOpqTerminationService() { }

    /**
     * Ends the run as a failure. A companion run past its clear (failAfterClear) lets only its companions go, so the
     * human keeps the jail bonus and his reward; every other run is released as it always was: everyone out, the event
     * disposed.
     */
    static void fail(AgentOpqSession session, String reason, long nowMs) {
        if (session != null && session.companionAdopted() && pastTheClear(session)) {
            failAfterClear(session, reason, nowMs);
            return;
        }
        release(session, reason, nowMs, true);
    }

    /**
     * The run completed (every member claimed and left): it is released as a completion, with no exit and no dispose.
     * The release is claimed before the session is marked complete, so a release already under way wins.
     */
    static void complete(AgentOpqSession session, long nowMs) {
        if (session == null || !session.beginTermination()) return;
        session.complete(nowMs);
        release(session, "completed", nowMs, false, true);
    }

    /**
     * The run is past its clear: the event says it is cleared, Minerva is free (statusStg8, which 2006001.js sets with
     * the clear), or the run is claiming its reward. The property is read as text: a missing one is not the clear.
     */
    static boolean pastTheClear(AgentOpqSession session) {
        EventInstanceManager event = session.eventInstance();
        return (event != null && (event.isEventCleared() || "1".equals(event.getProperty("statusStg8"))))
                || session.phase() == AgentOpqSession.Phase.CLAIMING_REWARD;
    }

    /**
     * Ends a run after its clear: its companions leave through the event's own exit, and the event and the players
     * still in it stay as they are; an event left with no player is disposed. The release is claimed before anyone
     * leaves, so a second failure (a force stop or '!opqtest stop', which fail a run without its lock) takes no one
     * out twice, and the tick leaves the run alone while its companions go.
     */
    static void failAfterClear(AgentOpqSession session, String reason, long nowMs) {
        if (session == null || !session.beginTermination()) return;
        EventInstanceManager event = session.eventInstance();
        exitAgentsOnly(session);
        if (event != null) {
            try {
                if (event.getPlayers().isEmpty()) event.dispose();
            } catch (RuntimeException failure) {
                log.warn("OPQ empty event dispose failed: session={}", session.sessionId(), failure);
            }
        }
        release(session, reason, nowMs, false, true);
    }

    /**
     * Takes a run's Agents out of its event, through the event's own exit (OrbisPQ sends them to 920011200, from which
     * the host's handback has them follow their owner), and leaves the event and its humans as they are.
     */
    static void exitAgentsOnly(AgentOpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) return;
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(AgentOpqMemberState::characterId)
                .collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("OPQ companion exit failed: event={} member={}", event.getName(), participant.getId(), failure);
            }
        }
    }

    /** The companions' HP/MP potions, by name, as 'OPQ potions:' logs them: {Mira:40/50, Kes:12/30}. */
    static String potions(AgentOpqSession session) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(member -> AgentOpqCoordinator.character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/" + gateway.mpPotionCount(agent))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * A companion run's party is its owner's own: his companions stay in it whatever the outcome, as KPQ, HPQ, LPQ and
     * EPQ keep a party they did not form. Every other run leaves it, as it always has.
     */
    static boolean keepPartyAfterRelease(boolean companionRun) {
        return companionRun;
    }

    static void release(AgentOpqSession session, String reason, long nowMs, boolean dispose) {
        release(session, reason, nowMs, dispose, false);
    }

    /** {@code claimed}: the caller already began the termination (failAfterClear); every other release claims it. */
    private static void release(AgentOpqSession session, String reason, long nowMs, boolean dispose,
                                boolean claimed) {
        if (session == null || !claimed && !session.beginTermination()) return;
        boolean companionRun = session.companionAdopted();
        if (session.phase() != AgentOpqSession.Phase.COMPLETED) {
            // Before session.fail below, so the phase is the one the run failed in.
            quietly(session, "log-failure", () -> log.warn(
                    "OPQ session failure: session={} phase={} reason={} pastTheClear={} companionRun={} members={}",
                    session.sessionId(), session.phase(), reason, pastTheClear(session), companionRun,
                    session.memberCount()));
        }
        session.members().forEach(member -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        });
        if (companionRun) {
            quietly(session, "log-potions", () -> log.info("OPQ potions: session={} start={} end={}",
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
            session.members().stream().map(member -> AgentOpqCoordinator.character(member.characterId()))
                    .filter(java.util.Objects::nonNull).filter(AgentPartyGatewayRuntime.party()::hasParty)
                    .forEach(AgentPartyGatewayRuntime.party()::leaveCurrentParty);
        }
        session.clearEventInstance();
        boolean completed = session.phase() == AgentOpqSession.Phase.COMPLETED;
        if (!completed) session.fail(reason, nowMs);
        AgentOpqSessionRegistry.remove(session);
        // A companion run's pace goes to the host's clear-time records; a stock run reports none.
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                companionRun ? pace(session, nowMs) : null);
        quietly(session, "log-released", () -> log.info(
                "OPQ session released: session={} outcome={} members={} companionRun={}",
                session.sessionId(), completed ? "completed" : reason, session.memberCount(), companionRun));
    }

    /**
     * A companion run's pace for the host's records, its span lines logged; a report that fails leaves it null rather
     * than the release unfinished.
     */
    private static AgentPqPace pace(AgentOpqSession session, long nowMs) {
        AgentOpqPaceReport.logSpans(session, nowMs);
        try {
            return AgentOpqPaceReport.of(session, nowMs);
        } catch (RuntimeException failure) {
            log.warn("OPQ pace report failed: session={}", session.sessionId(), failure);
            return null;
        }
    }

    /** Runs a step the release added for its lines; a failure is logged and the release goes on. */
    private static void quietly(AgentOpqSession session, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("OPQ cleanup failed but release will continue: session={} operation={}",
                    session.sessionId(), operation, failure);
        }
    }
}

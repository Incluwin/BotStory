package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/** Idempotent LPQ event, party, action, and index cleanup. */
final class AgentLpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqTerminationService.class);

    private AgentLpqTerminationService() { }

    /**
     * Ends the run. A companion run past its clear (failAfterClear) lets only its companions go, so the human keeps
     * his bonus and his reward; every other run is released as it always was: everyone out, the event disposed.
     */
    static void fail(AgentLpqSession session, String reason, long nowMs) {
        if (session != null && session.companionAdopted() && pastTheClear(session)) {
            failAfterClear(session, reason, nowMs);
            return;
        }
        release(session, reason, nowMs, true, false, false);
    }

    static void complete(AgentLpqSession session, long nowMs) {
        release(session, "completed", nowMs, false, true, false);
    }

    /** The run is past its clear: the reward map's eligibility is frozen, or the event says it is cleared. */
    static boolean pastTheClear(AgentLpqSession session) {
        EventInstanceManager event = session.eventInstance();
        return session.rewardEligibilityFrozen() || (event != null && event.isEventCleared());
    }

    /**
     * Ends a companion run after its clear: its companions leave through the event's own exit, and the event and the
     * players still in it stay as they are. Nothing is disposed. The release is claimed before anyone leaves, so a
     * second failure (the watchdog, a force stop or '!lpqtest stop', which fail a run without its lock) takes no one
     * out twice, and the tick leaves the run alone while its companions go.
     */
    static void failAfterClear(AgentLpqSession session, String reason, long nowMs) {
        if (session == null || !session.beginTermination()) return;
        exitAgentsOnly(session);
        release(session, reason, nowMs, false, false, true);
    }

    /**
     * Takes a companion run's Agents out of its event, through the event's own exit (the exit map, from which the
     * host's handback has them follow their owner), and leaves the event and its humans as they are.
     */
    static void exitAgentsOnly(AgentLpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) return;
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(AgentLpqMemberState::characterId)
                .collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("LPQ companion exit failed: event={} member={}", event.getName(), participant.getId(), failure);
            }
        }
    }

    /** The companions' HP/MP potions, by name, as 'LPQ potions:' logs them: {Mira:40/50, Kes:12/30}. */
    static String potions(AgentLpqSession session) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/" + gateway.mpPotionCount(agent))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * A companion run's party is its owner's own: his companions stay in it whatever the outcome. Every other run
     * keeps it only after a completed observation run.
     */
    static boolean keepPartyAfterRelease(AgentLpqSession.Mode mode, boolean completed, boolean companionRun) {
        return companionRun || (completed && mode == AgentLpqSession.Mode.TEST_OBSERVATION);
    }

    /** {@code claimed}: the caller already began the termination (failAfterClear); every other release claims it. */
    private static void release(AgentLpqSession session, String reason, long nowMs, boolean dispose, boolean completed,
                                boolean claimed) {
        if (session == null || !claimed && !session.beginTermination()) return;
        if (!completed) {
            quietly(session, "log-failure", () -> log.warn(
                    "LPQ session failure: session={} phase={} reason={} pastTheClear={} companionRun={} members={}",
                    session.sessionId(), session.phase(), reason, pastTheClear(session), session.companionAdopted(),
                    session.memberCount()));
        }
        session.members().forEach(member -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        });
        // A companion run's full-rate holds go with it (a stock run never holds one, so this does nothing there).
        quietly(session, "release-full-rate", () -> AgentLpqFullRateSimulation.releaseAll(session));
        if (session.companionAdopted()) {
            quietly(session, "log-potions", () -> log.info("LPQ potions: session={} start={} end={}",
                    session.sessionId(), session.potionsAtEntry(), potions(session)));
        }
        EventInstanceManager event = session.eventInstance();
        if (dispose && event != null) {
            for (Character participant : new ArrayList<>(event.getPlayers())) {
                try { event.exitPlayer(participant); } catch (RuntimeException ignored) { }
            }
            try { event.dispose(); } catch (RuntimeException ignored) { }
        }
        if (!keepPartyAfterRelease(session.mode(), completed, session.companionAdopted())) {
            session.members().stream().filter(member -> session.partyOwnership() == AgentLpqSession.PartyOwnership.LPQ_OWNED
                            || member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                    .map(member -> character(member.characterId())).filter(java.util.Objects::nonNull)
                    .filter(AgentPartyGatewayRuntime.party()::hasParty)
                    .forEach(AgentPartyGatewayRuntime.party()::leaveCurrentParty);
        }
        session.clearEventInstance();
        if (completed) session.complete(nowMs); else session.fail(reason, nowMs);
        AgentLpqSessionRegistry.remove(session);
        // A companion run's pace goes to the host's clear-time records; a stock run reports none.
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                session.companionAdopted() ? pace(session, nowMs) : null);
        quietly(session, "log-released", () -> log.info(
                "LPQ session released: session={} outcome={} members={} companionRun={}",
                session.sessionId(), completed ? "completed" : reason, session.memberCount(),
                session.companionAdopted()));
    }

    /**
     * A companion run's pace for the host's records, its span lines logged; a report that fails leaves it null rather
     * than the release unfinished.
     */
    private static AgentPqPace pace(AgentLpqSession session, long nowMs) {
        AgentLpqPaceReport.logSpans(session, nowMs);
        try {
            return AgentLpqPaceReport.of(session, nowMs);
        } catch (RuntimeException failure) {
            log.warn("LPQ pace report failed: session={}", session.sessionId(), failure);
            return null;
        }
    }

    /** Runs a step the release added for its lines; a failure is logged and the release goes on. */
    private static void quietly(AgentLpqSession session, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("LPQ cleanup failed but release will continue: session={} operation={}",
                    session.sessionId(), operation, failure);
        }
    }

    private static Character character(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}

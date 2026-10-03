package server.agents.capabilities.partyquest.hpq;

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

/** Idempotent owner for HPQ event, registry, and HPQ-owned party cleanup. */
final class AgentHpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentHpqTerminationService.class);

    private AgentHpqTerminationService() {
    }

    static void fail(AgentHpqSession session, String reason, long nowMs) {
        release(session, reason, nowMs, true, false);
    }

    static void complete(AgentHpqSession session, long nowMs) {
        release(session, "completed", nowMs, false, true);
    }

    /** Lets go of a companion run whose last companion tapped out; the event stays up, and the humans carry on. */
    static void detach(AgentHpqSession session, String reason, long nowMs) {
        release(session, reason, nowMs, false, false);
    }

    /**
     * Ends a companion run after its clear (a stall): its companions leave through the event's own exit, and the event
     * and the players still in it stay as they are. Nothing is disposed.
     */
    static void failAfterClear(AgentHpqSession session, String reason, long nowMs) {
        if (session == null || session.terminationStarted()) return;
        exitAgentsOnly(session);
        release(session, reason, nowMs, false, false);
    }

    /**
     * Takes a companion run's Agents out of its event, through the event's own exit (the exit map, from which the
     * host's handback has them follow their owner), and leaves the event and its humans as they are: a failure after the
     * clear must not take a player's reward from him.
     */
    static void exitAgentsOnly(AgentHpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) return;
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentHpqMemberState.MemberType.AGENT)
                .map(AgentHpqMemberState::characterId)
                .collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("HPQ companion exit failed: event={} member={}", event.getName(), participant.getId(), failure);
            }
        }
    }

    /** The companions' HP/MP potions, by name, as 'HPQ potions:' logs them: {Mira:12/30, Tobi:0/5}. */
    static String potions(AgentHpqSession session) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentHpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/" + gateway.mpPotionCount(agent))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static void release(AgentHpqSession session, String reason, long nowMs,
                                boolean disposeEvent, boolean completed) {
        if (session == null || !session.beginTermination()) return;
        EventInstanceManager event = session.eventInstance();
        tryCleanup(session, "stop-actions", () -> session.members().forEach(member -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        }));
        tryCleanup(session, "release-full-rate", () -> AgentHpqFullRateSimulation.releaseAll(session));
        if (session.companionAdopted()) {
            tryCleanup(session, "log-potions", () -> log.info("HPQ potions: session={} start={} end={}",
                    session.sessionId(), session.potionsAtEntry(), potions(session)));
        }
        if (disposeEvent && event != null) {
            tryCleanup(session, "dispose-event", () -> closeEvent(event));
        }
        if (!keepPartyAfterRelease(session.mode(), completed, session.companionAdopted())) {
            tryCleanup(session, "cleanup-party", () -> session.members().stream()
                        .filter(member -> session.partyOwnership() == AgentHpqSession.PartyOwnership.HPQ_OWNED
                                || member.memberType() == AgentHpqMemberState.MemberType.AGENT)
                        .map(member -> character(member.characterId()))
                        .filter(java.util.Objects::nonNull)
                        .filter(AgentPartyGatewayRuntime.party()::hasParty)
                        .forEach(AgentPartyGatewayRuntime.party()::leaveCurrentParty));
        }
        session.clearEventInstance();
        if (completed) session.complete(nowMs);
        else session.fail(reason, nowMs);
        AgentHpqSessionRegistry.remove(session);
        // A companion run's pace goes to the host's clear-time records; a stock run reports none.
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                session.companionAdopted() ? pace(session, nowMs) : null);
        log.info("HPQ session released: session={} outcome={} members={}",
                session.sessionId(), completed ? "completed" : reason, session.memberCount());
    }

    /**
     * A companion run's pace for the host's records, its span lines logged; a report that fails leaves it null rather
     * than the release unfinished.
     */
    private static AgentPqPace pace(AgentHpqSession session, long nowMs) {
        AgentHpqPaceReport.logSpans(session, nowMs);
        try {
            return AgentHpqPaceReport.of(session, nowMs);
        } catch (RuntimeException failure) {
            log.warn("HPQ pace report failed: session={}", session.sessionId(), failure);
            return null;
        }
    }

    /**
     * A companion run's party is its owner's own: his companions stay in it whatever the outcome, as KPQ keeps a party
     * it did not form. Every other run keeps it only after a completed observation run.
     */
    static boolean keepPartyAfterRelease(AgentHpqSession.Mode mode, boolean completed, boolean companionRun) {
        if (companionRun) return true;
        return completed && mode == AgentHpqSession.Mode.TEST_OBSERVATION;
    }

    private static void closeEvent(EventInstanceManager event) {
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("HPQ participant exit failed: event={} member={}",
                        event.getName(), participant == null ? -1 : participant.getId(), failure);
            }
        }
        event.dispose();
    }

    private static void tryCleanup(AgentHpqSession session, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("HPQ cleanup failed but release will continue: session={} operation={}",
                    session.sessionId(), operation, failure);
        }
    }

    private static Character character(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent
                : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(characterId);
    }
}

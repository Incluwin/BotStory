package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Idempotent PPQ cleanup. A stock run is released as it always was: with {@code dispose} everyone exits and the event
 * is disposed, and every member leaves the party. A companion run keeps the owner's party, and only its companions leave
 * the event: no release takes a human out of his event or disposes one that still holds him (an Agents-only exit
 * unregisters first, so the stock end never runs); an event left with no player is disposed. After the clear, in every
 * mode, an Agent still in the event (Wu Yang's room has no exit) is walked out through the stock exit.
 */
public final class AgentPpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqTerminationService.class);

    private AgentPpqTerminationService() { }

    public static void release(AgentPpqSession session, String reason, long nowMs, boolean dispose) {
        if (session == null || !session.beginTermination()) return;
        boolean companionRun = session.companionAdopted();
        session.members().forEach(member -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        });
        EventInstanceManager event = session.eventInstance();
        if (companionRun) {
            logPotions(session);
            if (event != null) {
                exitAgentsOnly(session, event);
                disposeIfEmpty(session, event);
            }
        } else if (dispose && event != null) {
            for (Character participant : new ArrayList<>(event.getPlayers())) {
                try { event.exitPlayer(participant); } catch (RuntimeException ignored) { }
            }
            try { event.dispose(); } catch (RuntimeException ignored) { }
        } else if (event != null && cleared(event)) {
            exitAgentsOnly(session, event);
        }
        boolean completed = session.phase() == AgentPpqSession.Phase.COMPLETED;
        if (!keepPartyAfterRelease(session.mode(), completed, companionRun)) {
            session.members().stream().map(member -> AgentPpqCoordinator.character(member.characterId()))
                    .filter(Objects::nonNull).filter(AgentPartyGatewayRuntime.party()::hasParty)
                    .forEach(AgentPartyGatewayRuntime.party()::leaveCurrentParty);
        }
        session.clearEventInstance();
        if (!completed) session.fail(reason, nowMs);
        AgentPpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                companionRun ? pace(session, nowMs) : null);
        if (companionRun) {
            log.info("PPQ session released: session={} outcome={} members={} companionRun=true",
                    session.sessionId(), completed ? "completed" : reason, session.memberCount());
        }
    }

    /** A companion run keeps the owner's party; every other mode leaves it, as it always did. */
    static boolean keepPartyAfterRelease(AgentPpqSession.Mode mode, boolean completed, boolean companionRun) {
        return companionRun;
    }

    /** The run's pace for the host's records; none until the PPQ pace step. */
    static AgentPqPace pace(AgentPpqSession session, long nowMs) {
        return null;
    }

    /** "{name:hp/mp, ...}" of the run's companions, for the 'PPQ potions:' line. Never throws. */
    static String potions(AgentPpqSession session) {
        try {
            PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
            return session.members().stream()
                    .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT)
                    .map(member -> AgentPpqCoordinator.character(member.characterId()))
                    .filter(Objects::nonNull)
                    .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/"
                            + gateway.mpPotionCount(agent))
                    .collect(Collectors.joining(", ", "{", "}"));
        } catch (RuntimeException failure) {
            return "{}";
        }
    }

    private static void logPotions(AgentPpqSession session) {
        log.info("PPQ potions: session={} start={} end={}", session.sessionId(), session.potionsAtEntry(),
                potions(session));
    }

    private static boolean cleared(EventInstanceManager event) {
        try {
            return event.isEventCleared();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static void exitAgentsOnly(AgentPpqSession session, EventInstanceManager event) {
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT)
                .map(AgentPpqMemberState::characterId).collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("PPQ companion exit failed: session={} member={}", session.sessionId(), participant.getId(),
                        failure);
            }
        }
    }

    /** Disposes the run's event once no player is left in it; one a human is still in stays his. Never throws. */
    static void disposeIfEmpty(AgentPpqSession session, EventInstanceManager event) {
        try {
            if (!event.isEventDisposed() && event.getPlayers().isEmpty()) event.dispose();
        } catch (RuntimeException failure) {
            log.warn("PPQ empty event dispose failed: session={}", session.sessionId(), failure);
        }
    }
}

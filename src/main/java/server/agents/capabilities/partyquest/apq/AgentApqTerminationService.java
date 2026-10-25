package server.agents.capabilities.partyquest.apq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;
import server.maps.Portal;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Idempotent APQ cleanup (the shape of AgentPpqTerminationService's companion-run path). The owner's party is kept, and
 * only the companions leave the event: no release takes a human out of his event or disposes one that still holds him;
 * an event left with no player is disposed. A companion still on the exit room (670011000) or on a vault the event has
 * let go of is taken to Amos' Training Ground, as the Glimmer Man would take it.
 */
public final class AgentApqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentApqTerminationService.class);

    private AgentApqTerminationService() {
    }

    public static void release(AgentApqSession session, String reason, long nowMs) {
        if (session == null || !session.beginTermination()) return;
        session.members().forEach(member -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        });
        EventInstanceManager event = session.eventInstance();
        AgentApqFullRateSimulation.releaseAll(session);
        log.info("APQ potions: session={} start={} end={}", session.sessionId(), session.potionsAtEntry(),
                potions(session));
        if (event != null && !cleared(event)) {
            exitAgentsOnly(session, event);
            disposeIfEmpty(session, event);
        }
        session.members().stream()
                .filter(member -> member.memberType() == AgentApqMemberState.MemberType.AGENT)
                .map(member -> AgentApqCoordinator.character(member.characterId()))
                .filter(Objects::nonNull)
                .filter(agent -> agent.getMapId() == AgentApqDefinition.EXIT_MAP
                        || agent.getMapId() >= AgentApqDefinition.MIRROR_MAP && agent.getEventInstance() == null
                        && AgentApqDefinition.isEventMap(agent.getMapId()))
                .forEach(agent -> leaveToTheGate(session, agent));
        boolean completed = session.phase() == AgentApqSession.Phase.COMPLETED;
        session.clearEventInstance();
        if (!completed) session.fail(reason, nowMs);
        AgentApqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                pace(session, nowMs));
        log.info("APQ session released: session={} outcome={} members={} companionRun=true", session.sessionId(),
                completed ? "completed" : reason, session.memberCount());
    }

    /** The run's pace for the host's records, with one 'APQ pace span:' line per begun span. */
    static AgentPqPace pace(AgentApqSession session, long nowMs) {
        AgentApqPaceReport.logSpans(session, nowMs);
        return AgentApqPaceReport.of(session, nowMs);
    }

    /** "{name:hp/mp, ...}" of the run's companions, for the 'APQ potions:' line. Never throws. */
    static String potions(AgentApqSession session) {
        try {
            PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
            return session.members().stream()
                    .filter(member -> member.memberType() == AgentApqMemberState.MemberType.AGENT)
                    .map(member -> AgentApqCoordinator.character(member.characterId()))
                    .filter(Objects::nonNull)
                    .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/"
                            + gateway.mpPotionCount(agent))
                    .collect(Collectors.joining(", ", "{", "}"));
        } catch (RuntimeException failure) {
            return "{}";
        }
    }

    private static boolean cleared(EventInstanceManager event) {
        try {
            return event.isEventCleared();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static void exitAgentsOnly(AgentApqSession session, EventInstanceManager event) {
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentApqMemberState.MemberType.AGENT)
                .map(AgentApqMemberState::characterId).collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("APQ companion exit failed: session={} member={}", session.sessionId(), participant.getId(),
                        failure);
            }
        }
    }

    /**
     * Takes an Agent to Amos' Training Ground (670010000, beside Amos the Strong), where the Glimmer Man's exit sends a
     * player. Never throws.
     */
    static void leaveToTheGate(AgentApqSession session, Character agent) {
        try {
            MapleMap gate = AgentMapGatewayRuntime.map().resolveMap(agent.getWorld(),
                    AgentClientGatewayRuntime.clients().channel(agent), AgentApqDefinition.GATE_MAP);
            if (gate == null) return;
            Portal portal = gate.getPortal("st00");
            AgentMapGatewayRuntime.map().changeMapNear(agent, gate, portal == null ? null : portal.getPosition());
        } catch (RuntimeException failure) {
            log.warn("APQ exit to the gate failed: session={} member={}", session.sessionId(), agent.getId(),
                    failure);
        }
    }

    /** Disposes the run's event once no player is left in it; one a human is still in stays his. Never throws. */
    static void disposeIfEmpty(AgentApqSession session, EventInstanceManager event) {
        try {
            if (!event.isEventDisposed() && event.getPlayers().isEmpty()) event.dispose();
        } catch (RuntimeException failure) {
            log.warn("APQ empty event dispose failed: session={}", session.sessionId(), failure);
        }
    }
}

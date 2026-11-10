package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
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
 * Idempotent PPQ cleanup. A stock run is released as it always was: with {@code dispose} everyone exits and the event
 * is disposed, and every member leaves the party. A companion run keeps the owner's party, and only its companions leave
 * the event: no release takes a human out of his event or disposes one that still holds him (an Agents-only exit
 * unregisters first, so the stock end never runs); an event left with no player is disposed. After the clear nobody
 * is in the event (clearPQ's warp to 925100600, past PiratePQ.js's maxMapId, unregisters everyone), so in every mode an
 * Agent still in Wu Yang's room, which has no exit, is taken to Guon's map as his exit would.
 */
public final class AgentPpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqTerminationService.class);
    /** The release reason when a companion run's last companion left and its humans go on without it. */
    static final String HUMANS_CARRY_ON = "every companion left; the humans carry on";

    private AgentPpqTerminationService() { }

    public static void release(AgentPpqSession session, String reason, long nowMs, boolean dispose) {
        if (session == null || !session.beginTermination()) return;
        boolean companionRun = session.companionAdopted();
        // Each member's step on its own: one that throws never leaves the run registered and terminating.
        session.members().forEach(member -> quietly(session, "stop-" + member.characterId(), () -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        }));
        EventInstanceManager event = session.eventInstance();
        if (companionRun) {
            quietly(session, "release-full-rate", () -> AgentPpqFullRateSimulation.releaseAll(session));
            quietly(session, "log-potions", () -> logPotions(session));
            if (event != null && !cleared(event)) {
                quietly(session, "exit-agents", () -> exitAgentsOnly(session, event));
                disposeIfEmpty(session, event);
            }
        } else if (dispose && event != null) {
            quietly(session, "exit-players", () -> {
                for (Character participant : new ArrayList<>(event.getPlayers())) {
                    try {
                        event.exitPlayer(participant);
                    } catch (RuntimeException failure) {
                        log.warn("PPQ participant exit failed: session={} member={}", session.sessionId(),
                                participant == null ? -1 : participant.getId(), failure);
                    }
                }
            });
            quietly(session, "dispose-event", event::dispose);
        }
        if (event != null && cleared(event)) {
            quietly(session, "leave-clear-room", () -> session.members().stream()
                    .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT)
                    .map(member -> AgentPpqCoordinator.character(member.characterId()))
                    .filter(Objects::nonNull)
                    .filter(agent -> agent.getMapId() == AgentPpqDefinition.CLEAR_MAP)
                    .forEach(agent -> leaveWuYangsRoom(session, agent)));
        }
        boolean completed = session.phase() == AgentPpqSession.Phase.COMPLETED;
        if (!keepPartyAfterRelease(session.mode(), completed, companionRun)) {
            session.members().forEach(member -> quietly(session, "leave-party-" + member.characterId(), () -> {
                Character character = AgentPpqCoordinator.character(member.characterId());
                if (character != null && AgentPartyGatewayRuntime.party().hasParty(character)) {
                    AgentPartyGatewayRuntime.party().leaveCurrentParty(character);
                }
            }));
        }
        try {
            session.clearEventInstance();
            if (!completed) session.fail(reason, nowMs);
        } finally {
            AgentPpqSessionRegistry.remove(session);
            AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                    companionRun ? pace(session, nowMs) : null);
        }
        if (companionRun) {
            log.info("PPQ session released: session={} outcome={} members={} companionRun=true",
                    session.sessionId(), completed ? "completed" : reason, session.memberCount());
        }
    }

    /** A companion run keeps the owner's party; every other mode leaves it, as it always did. */
    static boolean keepPartyAfterRelease(AgentPpqSession.Mode mode, boolean completed, boolean companionRun) {
        return companionRun;
    }

    /**
     * The run's pace for the host's records (P6i Step 5), with one 'PPQ pace span:' line per begun span. A report that
     * fails leaves it null rather than the release unfinished.
     */
    static AgentPqPace pace(AgentPpqSession session, long nowMs) {
        try {
            AgentPpqPaceReport.logSpans(session, nowMs);
            return AgentPpqPaceReport.of(session, nowMs);
        } catch (RuntimeException failure) {
            log.warn("PPQ pace report failed: session={}", session.sessionId(), failure);
            return null;
        }
    }

    /** Runs one step of the release; a failure is logged and the release goes on. */
    private static void quietly(AgentPpqSession session, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("PPQ cleanup failed but release will continue: session={} operation={}",
                    session.sessionId(), operation, failure);
        }
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

    /**
     * Takes an Agent out of Wu Yang's room to Guon's map (251010404), where his own exit sends players: the room has no
     * exit portal, and after the clear there is no event left to exit. Never throws.
     */
    static void leaveWuYangsRoom(AgentPpqSession session, Character agent) {
        try {
            MapleMap recruit = AgentMapGatewayRuntime.map().resolveMap(agent.getWorld(),
                    AgentClientGatewayRuntime.clients().channel(agent), AgentPpqDefinition.RECRUIT_MAP);
            if (recruit == null) return;
            Portal portal = recruit.getPortal(0);
            AgentMapGatewayRuntime.map().changeMapNear(agent, recruit, portal == null ? null : portal.getPosition());
        } catch (RuntimeException failure) {
            log.warn("PPQ Wu Yang room exit failed: session={} member={}", session.sessionId(), agent.getId(),
                    failure);
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

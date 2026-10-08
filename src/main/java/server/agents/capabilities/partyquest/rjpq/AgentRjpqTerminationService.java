package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatSkillConstraintState;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Idempotent RJPQ cleanup; it cannot address another PQ's state. */
final class AgentRjpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqTerminationService.class);

    private AgentRjpqTerminationService() {
    }

    /**
     * Ends the run. The owner's party is always kept (every RJPQ run is a companion run). A run that did not complete
     * leaves its event: before the clear every participant exits, humans too, and the event is disposed, as the stock
     * event itself would end when a companion leaves a run of four (an Agents-only exit unregisters before it moves, so
     * the stock end never runs); after the clear only the companions still in the event leave it, through the event's
     * own exit, and the event and its humans stay, so nobody loses a claim.
     */
    static void release(AgentRjpqSession session, String reason, long nowMs, boolean dispose) {
        if (session == null || !session.beginTermination()) {
            return;
        }
        AgentRjpqSession.Phase phaseAtRelease = session.phase();
        AgentRjpqFullRateSimulation.releaseAll(session);
        for (AgentRjpqMemberState member : session.members()) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) {
                entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY).clear();
                AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            }
        }
        EventInstanceManager event = session.eventInstance();
        boolean pastTheClear = pastTheClear(event);
        logPotions(session);
        boolean leaveEvent = dispose || phaseAtRelease != AgentRjpqSession.Phase.COMPLETED;
        if (event != null && (leaveEvent || pastTheClear)) {
            if (pastTheClear) {
                exitAgentsOnly(session);
            } else {
                for (Character participant : new ArrayList<>(event.getPlayers())) {
                    try {
                        event.exitPlayer(participant);
                    } catch (RuntimeException ignored) {
                        // the next participant still leaves
                    }
                }
                try {
                    event.dispose();
                } catch (RuntimeException ignored) {
                    // a disposed event is the goal
                }
            }
        }
        session.clearEventInstance();
        boolean completed = session.phase() == AgentRjpqSession.Phase.COMPLETED;
        if (!completed) {
            session.fail(reason, nowMs);
        }
        AgentRjpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), completed, reason, nowMs,
                pace(session, nowMs));
        log.info("RJPQ session released: session={} side={} outcome={} phase={} members={} pastTheClear={}",
                session.sessionId(), session.side().key(), completed ? "completed" : reason, phaseAtRelease,
                session.memberCount(), pastTheClear);
    }

    /** True once the bound event reports its clear. */
    static boolean pastTheClear(EventInstanceManager event) {
        if (event == null) {
            return false;
        }
        try {
            return event.isEventCleared();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Takes the run's companions still in its event out through the event's own exit; humans stay. */
    static void exitAgentsOnly(AgentRjpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) {
            return;
        }
        Set<Integer> agents = session.members().stream()
                .filter(AgentRjpqMemberState::agent)
                .map(AgentRjpqMemberState::characterId)
                .collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) {
                continue;
            }
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("RJPQ companion exit failed: event={} member={}", event.getName(), participant.getId(),
                        failure);
            }
        }
    }

    /** The companions' HP/MP potions, by name, as 'RJPQ potions:' logs them: {Mira:12/30, Tobi:0/5}. */
    static String potions(AgentRjpqSession session) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return session.members().stream()
                .filter(AgentRjpqMemberState::agent)
                .map(member -> AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(member.characterId()))
                .filter(Objects::nonNull)
                .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/" + gateway.mpPotionCount(agent))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /** The run's pace for the host's records (Step 4), with one 'RJPQ pace span:' line per begun span. */
    static AgentPqPace pace(AgentRjpqSession session, long nowMs) {
        AgentRjpqPaceReport.logSpans(session, nowMs);
        return AgentRjpqPaceReport.of(session, nowMs);
    }

    private static void logPotions(AgentRjpqSession session) {
        try {
            log.info("RJPQ potions: session={} side={} start={} end={}", session.sessionId(), session.side().key(),
                    session.potionsAtEntry(), potions(session));
        } catch (RuntimeException failure) {
            log.warn("RJPQ potions could not be read: session={}", session.sessionId(), failure);
        }
    }
}

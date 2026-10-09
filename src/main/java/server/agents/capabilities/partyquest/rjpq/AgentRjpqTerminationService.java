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

    /** The release reason of a run whose companions all departed while its humans stay in the event. */
    static final String HUMANS_CARRY_ON = "every companion left; the humans carry on";

    /**
     * Ends the run. The owner's party is always kept (every RJPQ run is a companion run). Only the companions still
     * in the event leave it, through the event's own exit, before the clear as after it: no release ever takes a human
     * out of his event or disposes an event that still holds him (an Agents-only exit unregisters before it moves, so
     * the stock end never runs; he carries on, or leaves by Romeo's or Juliet's retreat). An event left with no player
     * is disposed. A departed member, already handed back, is left alone.
     */
    static void release(AgentRjpqSession session, String reason, long nowMs, boolean dispose) {
        if (session == null || !session.beginTermination()) {
            return;
        }
        AgentRjpqSession.Phase phaseAtRelease = session.phase();
        AgentRjpqFullRateSimulation.releaseAll(session);
        for (AgentRjpqMemberState member : session.members()) {
            AgentRuntimeEntry entry = session.departed(member.characterId()) ? null
                    : AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
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
            exitAgentsOnly(session);
            disposeIfEmpty(session, event);
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

    /**
     * Every companion of the run departed while its bound, undisposed event still holds a human member: the run is
     * released ({@link #HUMANS_CARRY_ON}) and the humans go on as they wish. True when it released the run.
     */
    static boolean humansCarryOn(AgentRjpqSession session, long nowMs) {
        if (session == null || session.terminationStarted() || !onlyHumansRemain(session)) {
            return false;
        }
        release(session, HUMANS_CARRY_ON, nowMs, false);
        return true;
    }

    private static boolean onlyHumansRemain(AgentRjpqSession session) {
        EventInstanceManager event = session.eventInstance();
        boolean companionLeft = session.members().stream()
                .anyMatch(member -> member.agent() && !session.departed(member.characterId()));
        if (companionLeft || event == null) {
            return false;
        }
        try {
            return !event.isEventDisposed() && event.getPlayers().stream().anyMatch(player -> player != null
                    && session.member(player.getId()) != null && !session.member(player.getId()).agent());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Disposes the run's event once no player is left in it; one a human is still in stays his. Never throws. */
    static void disposeIfEmpty(AgentRjpqSession session, EventInstanceManager event) {
        try {
            if (event != null && !event.isEventDisposed() && event.getPlayers().isEmpty()) {
                event.dispose();
            }
        } catch (RuntimeException failure) {
            log.warn("RJPQ empty event dispose failed: session={}", session.sessionId(), failure);
        }
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

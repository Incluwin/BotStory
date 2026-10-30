package server.agents.capabilities.townlife;

import client.Character;
import server.agents.events.AgentEventPriority;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSessionEventRuntime;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.agents.runtime.townlife.AgentTownLifeTerminalState;

final class AgentTownLifeEventPublisher {
    private AgentTownLifeEventPublisher() {
    }

    static void arrival(AgentRuntimeEntry entry,
                        Character agent,
                        AgentTownLifeState state,
                        long nowMs) {
        if (entry == null || agent == null || state == null
                || state.fidelity() == AgentTownLifeFidelity.BACKGROUND_ABSTRACT) {
            return;
        }
        AgentTownLifeProfile profile = AgentTownLifeProfileRepository.defaultRepository()
                .require(state.townMapId());
        AgentSessionEventRuntime.bus(entry).publish(new AgentTownLifeArrivalEvent(
                agent.getId(), nowMs, state.townMapId(), profile.profileId(),
                state.visitPurpose(), state.visitReason(),
                agent.getMapId() == state.townMapId() && agent.getMap() != null
                        ? agent.getMap().getMapName() : ""), AgentEventPriority.AMBIENT);
    }

    static void lifecycle(AgentRuntimeEntry entry,
                          Character agent,
                          AgentTownLifeState state,
                          AgentTownLifeLifecycleEvent.Phase phase,
                          String reason,
                          long nowMs) {
        if (entry == null || agent == null || state == null || phase == null) {
            return;
        }
        // The session's identity in one read: a citizen's presence scheduler stops its TownLife on its own thread, and
        // a stop between separate field reads built the event with a blank request id (live 2026-10-19).
        AgentTownLifeSessionHandle session = state.sessionHandle(agent.getId());
        if (session == null) {
            return;
        }
        entry.capabilityStates().require(AgentTownLifeTerminalState.STATE_KEY)
                .record(session.sessionId(), phase, reason, nowMs);
        AgentSessionEventRuntime.bus(entry).publish(new AgentTownLifeLifecycleEvent(
                agent.getId(), nowMs, session.townMapId(), session.sessionId(),
                session.requestId(), session.callerId(), phase, reason,
                state.activity(), state.activityResult()), AgentEventPriority.IMPORTANT);
        AgentTownLifeMetrics.lifecycle(phase);
    }

    static void activity(AgentRuntimeEntry entry,
                         Character agent,
                         AgentTownLifeState state,
                         AgentTownLifeActivityEvent.Phase phase,
                         long nowMs) {
        if (entry == null || agent == null || state == null || phase == null
                || state.fidelity() == AgentTownLifeFidelity.BACKGROUND_ABSTRACT) {
            return;
        }
        String correlation = state.decisionCorrelationId().isBlank()
                ? "townlife:" + agent.getId() + ':' + state.sequence()
                : state.decisionCorrelationId();
        AgentTownLifeProfile profile = AgentTownLifeProfileRepository.defaultRepository()
                .require(state.townMapId());
        AgentSessionEventRuntime.bus(entry).publish(new AgentTownLifeActivityEvent(
                agent.getId(), nowMs, state.townMapId(), profile.profileId(), state.activity(),
                phase, state.venueId(), state.targetCharacterId(), state.decisionSource(), correlation),
                AgentEventPriority.AMBIENT);
        AgentTownLifeMetrics.activity(phase, state.venueId());
    }

    static void encounter(AgentRuntimeEntry entry,
                          Character agent,
                          AgentTownLifeEncounterState.Snapshot encounter,
                          long nowMs) {
        if (entry == null || agent == null || encounter == null
                || entry.simulationState().mode() == AgentSimulationMode.BACKGROUND_ABSTRACT
                || encounter.encounterId() == null || encounter.encounterId().isBlank()) {
            return;
        }
        AgentSessionEventRuntime.bus(entry).publish(new AgentTownLifeEncounterEvent(
                agent.getId(), nowMs, agent.getMapId(), encounter.encounterId(), encounter.type(),
                encounter.role(), encounter.phase(), encounter.peerAgentId(),
                encounter.turnOwnerAgentId(), encounter.venueId(), encounter.correlationId()),
                AgentEventPriority.AMBIENT);
        AgentTownLifeMetrics.encounter(encounter.phase());
    }
}

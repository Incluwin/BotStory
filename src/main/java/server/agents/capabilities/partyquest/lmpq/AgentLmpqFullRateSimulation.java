package server.agents.capabilities.partyquest.lmpq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A companion of an LMPQ companion run simulates at full rate while it stands on one of the event's maps (P6h Step
 * 2f, RJPQ's shape): it is usually alone in a maze room no player sees, where the background cadence would slow its
 * farming. The hold is the run's own (kept in the session, applied by each companion for itself), it lets go when the
 * companion leaves the event, departs or the run ends, and it never clears a requirement something else set. A hold
 * and a let-go each happen under the run's monitor, so a release that has begun refuses a new hold and one that
 * begins later finds every hold taken before it.
 */
final class AgentLmpqFullRateSimulation {
    private AgentLmpqFullRateSimulation() {
    }

    /** Holds full rate for this companion while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentLmpqSession session, int agentId, AgentRuntimeEntry entry, boolean wanted) {
        AgentSimulationState state = entry == null ? null : entry.simulationState();
        if (session == null || state == null) {
            return;
        }
        synchronized (session) {
            if (wanted) {
                if (!session.terminationStarted() && !session.departed(agentId)) {
                    session.holdFullRate(agentId, state.fullRateSimulationRequired());
                    state.requireFullRateSimulation();
                }
                return;
            }
            Boolean requiredBefore = session.releaseFullRate(agentId);
            if (requiredBefore != null && !requiredBefore) {
                state.clearFullRateSimulationRequirement();
            }
        }
    }

    /** Lets go of every hold the run has, as it ends. */
    static void releaseAll(AgentLmpqSession session) {
        if (session == null) {
            return;
        }
        for (int agentId : session.fullRateHeld()) {
            apply(session, agentId, AgentRuntimeRegistry.findByAgentCharacterId(agentId), false);
        }
    }
}

package server.agents.capabilities.partyquest.rjpq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * An RJPQ companion simulates at full rate while it stands in its run's event: it is often alone on a map no player
 * sees (a Dark Lab, a Lab Unit, a stage ahead of its leader), where the background cadence would slow its climb and
 * its fights. The hold is the run's own (kept in the session, applied by each companion for itself), it lets go when
 * the companion leaves the event or the run ends, and it never clears a requirement something else set. A hold and
 * a let-go each happen under the run's monitor, so a release that has begun refuses a new hold and one that begins
 * later finds every hold taken before it.
 */
final class AgentRjpqFullRateSimulation {
    private AgentRjpqFullRateSimulation() {
    }

    /** Holds full rate for this companion while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentRjpqSession session, int agentId, AgentRuntimeEntry entry, boolean wanted) {
        AgentSimulationState state = entry == null ? null : entry.simulationState();
        if (session == null || state == null) {
            return;
        }
        synchronized (session) {
            if (wanted) {
                if (!session.terminationStarted()) {
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
    static void releaseAll(AgentRjpqSession session) {
        if (session == null) {
            return;
        }
        for (int agentId : session.fullRateHeld()) {
            apply(session, agentId, AgentRuntimeRegistry.findByAgentCharacterId(agentId), false);
        }
    }
}

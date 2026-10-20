package server.agents.capabilities.partyquest.ppq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A PPQ companion simulates at full rate while it stands in its run's event on a map its leader is not on (a stage
 * ahead of him, a deck while he is in its chest room), and in Wu Yang's room after the clear until it leaves (plan 7.9,
 * the shape of AgentRjpqFullRateSimulation): there no player sees it, and the background cadence would slow its fights
 * and its walk. The hold is the run's own (kept in the session, applied by each companion for itself), it lets go when
 * the companion no longer needs it, departs, or the run ends, and it never clears a requirement something else set. A
 * hold and a let-go each happen under the run's monitor, so a release that has begun refuses a new hold and one that
 * begins later finds every hold taken before it.
 */
final class AgentPpqFullRateSimulation {
    private AgentPpqFullRateSimulation() {
    }

    /** Holds full rate for this companion while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentPpqSession session, int agentId, AgentRuntimeEntry entry, boolean wanted) {
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
    static void releaseAll(AgentPpqSession session) {
        if (session == null) {
            return;
        }
        for (int agentId : session.fullRateHeld()) {
            apply(session, agentId, AgentRuntimeRegistry.findByAgentCharacterId(agentId), false);
        }
    }
}

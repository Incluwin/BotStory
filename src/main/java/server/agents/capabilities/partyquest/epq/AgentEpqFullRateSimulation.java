package server.agents.capabilities.partyquest.epq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A companion run's companions simulate at full rate while they stand in its EPQ instance: rooms nobody watches run at
 * reduced fidelity otherwise (LPQ's stage 5 showed it), and a stage the leader is not on would wait on slow background
 * ticks. The shape of AgentHpqFullRateSimulation and KPQ's hold: the hold is the run's own, lets go when the member
 * leaves the instance or the run, or the run ends, and never clears a requirement something else set. The hold flags
 * live on the session (synchronized), because the lease holder's pass and the release (any thread) both write them; the
 * simulation state is touched only after the session call returned, never under its monitor.
 */
final class AgentEpqFullRateSimulation {
    private AgentEpqFullRateSimulation() {
    }

    /** Holds full rate for this member while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentEpqSession session, int memberId, AgentRuntimeEntry entry, boolean wanted) {
        AgentSimulationState state = entry == null ? null : entry.simulationState();
        if (session == null || state == null) return;
        if (wanted) {
            if (!session.holdFullRate(memberId, state.fullRateSimulationRequired())) return;
            state.requireFullRateSimulation();
            // A release that began meanwhile has already let go of the run's holds: take this one back.
            if (session.terminationStarted()) apply(session, memberId, entry, false);
        } else {
            Boolean wasRequired = session.releaseFullRate(memberId);
            if (wasRequired != null && !wasRequired) state.clearFullRateSimulationRequirement();
        }
    }

    /** Lets go of every hold the run has, as it ends or its companions leave. */
    static void releaseAll(AgentEpqSession session) {
        if (session == null) return;
        for (int memberId : session.fullRateHolders()) {
            apply(session, memberId, AgentRuntimeRegistry.findByAgentCharacterId(memberId), false);
        }
    }
}

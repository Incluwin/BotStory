package server.agents.capabilities.partyquest.lpq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A companion run's companions simulate at full rate while they stand in its LPQ instance (stages 1-9, the bonus map,
 * and the reward map until their reward is claimed): the stage-2 trap room, the split rooms of stages 4 and 5, the
 * portal maze, the bonus and the reward map often have no player on them, and at the background cadence a room nobody
 * watches runs at reduced fidelity. A copy of AgentHpqFullRateSimulation (KPQ's rule): the hold is the run's own, it
 * lets go when the member leaves the instance, is ejected, has its reward, or the run ends, and it never clears a
 * requirement something else set.
 */
final class AgentLpqFullRateSimulation {
    private AgentLpqFullRateSimulation() {
    }

    /** Holds full rate for this member while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentLpqMemberState member, AgentRuntimeEntry entry, boolean wanted) {
        AgentSimulationState state = entry == null ? null : entry.simulationState();
        if (member == null || state == null) return;
        if (wanted) {
            if (!member.fullRateHeld()) member.holdFullRate(state.fullRateSimulationRequired());
            state.requireFullRateSimulation();
        } else if (member.fullRateHeld()) {
            if (!member.fullRateWasRequired()) state.clearFullRateSimulationRequirement();
            member.releaseFullRate();
        }
    }

    /** Lets go of every hold the run has, as it ends. */
    static void releaseAll(AgentLpqSession session) {
        if (session == null) return;
        for (AgentLpqMemberState member : session.members()) {
            apply(member, AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()), false);
        }
    }
}

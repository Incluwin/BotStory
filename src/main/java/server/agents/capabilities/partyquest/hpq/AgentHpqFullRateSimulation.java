package server.agents.capabilities.partyquest.hpq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A companion run's companions simulate at full rate while they stand in its HPQ instance after the clear with their
 * reward still to claim (BONUS_DECISION, BONUS_FARMING, CLAIMING_REWARD): the clear map and Pig Town often have no
 * player on them, and at the background cadence a claim waits on a slow tick. A copy of AgentKpqFullRateSimulation:
 * the hold is the run's own, it lets go when the member leaves the instance, is ejected, has its reward, or the run
 * ends, and it never clears a requirement something else set.
 */
final class AgentHpqFullRateSimulation {
    private AgentHpqFullRateSimulation() {
    }

    /** Holds full rate for this member while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentHpqMemberState member, AgentRuntimeEntry entry, boolean wanted) {
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
    static void releaseAll(AgentHpqSession session) {
        if (session == null) return;
        for (AgentHpqMemberState member : session.members()) {
            apply(member, AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()), false);
        }
    }
}

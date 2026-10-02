package server.agents.capabilities.partyquest.kpq;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A companion run's companions simulate at full rate while they are in its KPQ instance, as on a map a player
 * watches. Live (2026-10-01) they fought stage 5's mobs for 163 s at the background cadence, a fifth of the rate,
 * while the leader was still on stage 4. The hold is the run's own: it lets go when the member leaves the instance,
 * is ejected or tapped out, has its reward, or the run ends, and it never clears a requirement something else set.
 */
final class AgentKpqFullRateSimulation {
    private AgentKpqFullRateSimulation() {
    }

    /** Holds full rate for this member while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentKpqMemberState member, AgentRuntimeEntry entry, boolean wanted) {
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
    static void releaseAll(AgentKpqSession session) {
        if (session == null) return;
        for (AgentKpqMemberState member : session.members()) {
            apply(member, AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()), false);
        }
    }
}

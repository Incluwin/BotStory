package server.agents.capabilities.expedition.boss;

import server.agents.runtime.simulation.AgentSimulationState;

/**
 * A boss run's members simulate at full rate while they stand in its event, as on a map a player watches. Live (P18
 * Horntail, 2026-10-14) one owner's client crashed and the other owner stood on another map of the fight: with nobody
 * watching theirs, the five members inside dropped to the background heartbeat and all but stopped (the right head
 * lost 2.8% in 8 minutes), and one stood at 100-160 HP beside 600 potions, since its autopot runs in the live tick. The
 * hold is the run's own, as a party quest's (AgentKpqFullRateSimulation): it lets go when the member is out of the
 * event or the run, or the run ends, and it never clears a requirement something else set.
 */
final class AgentBossFullRateSimulation {
    private AgentBossFullRateSimulation() {
    }

    /** Holds full rate for this member while {@code wanted}, and lets go of the run's hold otherwise. */
    static void apply(AgentBossRunMember member, AgentSimulationState state, boolean wanted) {
        if (member == null || state == null) return;
        if (wanted) {
            if (!member.fullRateHeld()) member.holdFullRate(state.fullRateSimulationRequired());
            state.requireFullRateSimulation();
        } else if (member.fullRateHeld()) {
            if (!member.fullRateWasRequired()) state.clearFullRateSimulationRequirement();
            member.releaseFullRate();
        }
    }
}

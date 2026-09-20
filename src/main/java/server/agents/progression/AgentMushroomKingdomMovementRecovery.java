package server.agents.progression;

import client.Character;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;

/** Stops emergency relocation in Mushroom Castle, including the shared physics paths. */
public final class AgentMushroomKingdomMovementRecovery {
    private AgentMushroomKingdomMovementRecovery() { }

    public static boolean blockWithoutTeleport(AgentRuntimeEntry entry, Character agent, String reason) {
        if (entry == null || agent == null) return false;
        var story = entry.capabilityStates().find(AgentMushroomKingdomState.STATE_KEY)
                .filter(state -> state.phase() == AgentMushroomKingdomState.Phase.ACTIVE
                        && state.progressAtMs() > 0L);
        var farming = entry.capabilityStates().find(AgentMushroomKingdomPostStoryState.STATE_KEY)
                .filter(state -> state.phase() == AgentMushroomKingdomPostStoryState.Phase.ACTIVE
                        && state.activity() != AgentMushroomKingdomPostStoryState.Activity.NONE);
        boolean castleMap = agent.getMapId() >= 106020000 && agent.getMapId() <= 106021800;
        if (!castleMap && story.isEmpty() && farming.isEmpty()) return false;
        AgentModeService.startStop(entry);
        // Freeze at the last actual position; never claim a new landing point.
        AgentMovementStateRuntime.setInAir(entry, false);
        String failure = reason + "; Mushroom Castle stopped without recovery teleport";
        story.ifPresent(state -> {
            state.clearCheckpointRoute();
            state.clearHuntMap();
            state.block(failure);
        });
        farming.ifPresent(state -> state.block(failure));
        AgentMushroomKingdomMapReservationRuntime.release(agent.getId());
        return true;
    }
}

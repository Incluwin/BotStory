package server.agents.runtime.maintenance;

import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveStatus;
import server.agents.runtime.AgentRuntimeEntry;

/** Owns the common suspend/finish/resume protocol for all maintenance capabilities. */
public final class AgentRemediationCoordinator {
    private AgentRemediationCoordinator() {
    }

    public static boolean begin(AgentRuntimeEntry entry,
                                AgentRemediationFrame frame,
                                AgentObjectiveDefinition maintenance,
                                String reason,
                                long nowMs) {
        if (entry == null || frame == null || maintenance == null
                || !frame.maintenanceObjectiveId().equals(maintenance.objectiveId())
                || nowMs > frame.deadlineAtMs()) {
            return false;
        }
        AgentRemediationState state = entry.capabilityStates().require(AgentRemediationState.STATE_KEY);
        if (!state.begin(frame)) {
            return false;
        }
        AgentObjectiveDefinition foreground = AgentObjectiveKernel.active(entry);
        if (foreground == null) {
            AgentObjectiveKernel.start(entry, maintenance, nowMs);
            return true;
        }
        if (foreground.objectiveId().equals(maintenance.objectiveId())) {
            return true;
        }
        if (AgentObjectiveKernel.suspendFor(entry, maintenance, reason, nowMs)) {
            return true;
        }
        state.clear(frame.frameId());
        return false;
    }

    public static boolean finish(AgentRuntimeEntry entry,
                                 String frameId,
                                 AgentObjectiveStatus terminalStatus,
                                 String reason,
                                 long nowMs) {
        if (entry == null || frameId == null || frameId.isBlank()) {
            return false;
        }
        AgentRemediationState state = entry.capabilityStates().require(AgentRemediationState.STATE_KEY);
        AgentRemediationFrame frame = state.active();
        if (frame == null || !frame.frameId().equals(frameId)) {
            return false;
        }
        boolean finished = AgentObjectiveKernel.finishAndResume(entry, frame.maintenanceObjectiveId(),
                terminalStatus, reason, nowMs);
        // Release the frame even when something else already ended the objective: a stranded
        // frame refuses every later remediation, so the Agent could never resupply again.
        state.clear(frameId);
        return finished;
    }

    /**
     * True while a live remediation frame drives this maintenance objective. Frames are never
     * checkpointed, so a maintenance objective restored after a relog has none.
     */
    public static boolean owns(AgentRuntimeEntry entry, String maintenanceObjectiveId) {
        if (entry == null || maintenanceObjectiveId == null) {
            return false;
        }
        AgentRemediationFrame frame = entry.capabilityStates()
                .require(AgentRemediationState.STATE_KEY).active();
        return frame != null && frame.maintenanceObjectiveId().equals(maintenanceObjectiveId);
    }

    /**
     * Reattaches typed remediation metadata to a maintenance objective restored from an older
     * checkpoint that predates remediation frames.
     */
    public static boolean reattach(AgentRuntimeEntry entry, AgentRemediationFrame frame) {
        if (entry == null || frame == null) {
            return false;
        }
        AgentObjectiveDefinition active = AgentObjectiveKernel.active(entry);
        if (active == null || !active.objectiveId().equals(frame.maintenanceObjectiveId())) {
            return false;
        }
        return entry.capabilityStates().require(AgentRemediationState.STATE_KEY).begin(frame);
    }
}

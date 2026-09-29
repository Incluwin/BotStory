package server.agents.runtime.maintenance;

import org.junit.jupiter.api.Test;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.objectives.AgentObjectiveStatus;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRemediationCoordinatorTest {
    @Test
    void suspendsAndResumesTheSameForegroundCorrelation() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentObjectiveDefinition foreground = objective("plan:step", "chain-1", 10_000L);
        AgentObjectiveDefinition maintenance = objective("maintenance:supply", "chain-1", 5_000L);
        AgentObjectiveKernel.start(entry, foreground, 100L);
        AgentRemediationFrame frame = new AgentRemediationFrame(
                "supply:1", AgentRemediationKind.LOW_SUPPLIES, maintenance.objectiveId(),
                foreground.correlationId(), 1, 101L, 5_000L, Map.of("hp", "restored"));

        assertTrue(AgentRemediationCoordinator.begin(entry, frame, maintenance,
                "HP potions critical", 101L));
        assertEquals(maintenance, AgentObjectiveKernel.active(entry));
        assertTrue(AgentRemediationCoordinator.finish(entry, frame.frameId(),
                AgentObjectiveStatus.SUCCEEDED, "restored", 200L));
        assertEquals(foreground, AgentObjectiveKernel.active(entry));
        assertFalse(AgentRemediationCoordinator.finish(entry, frame.frameId(),
                AgentObjectiveStatus.SUCCEEDED, "duplicate", 201L));
    }

    @Test
    void rejectsExpiredOrCompetingFrames() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentObjectiveDefinition maintenance = objective("maintenance:supply", "chain-1", 5_000L);
        AgentRemediationFrame expired = new AgentRemediationFrame(
                "expired", AgentRemediationKind.LOW_SUPPLIES, maintenance.objectiveId(),
                "chain-1", 1, 100L, 150L, Map.of());

        assertFalse(AgentRemediationCoordinator.begin(entry, expired, maintenance,
                "expired", 151L));
    }

    @Test
    void finishReleasesTheFrameWhenTheObjectiveWasAlreadyEndedElsewhere() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentObjectiveDefinition maintenance = objective("maintenance:supply:1", "chain-1", 5_000L);
        AgentRemediationFrame frame = new AgentRemediationFrame(
                "supply:1", AgentRemediationKind.LOW_SUPPLIES, maintenance.objectiveId(),
                "chain-1", 1, 100L, 5_000L, Map.of());
        assertTrue(AgentRemediationCoordinator.begin(entry, frame, maintenance, "HP empty", 100L));
        assertTrue(AgentRemediationCoordinator.owns(entry, maintenance.objectiveId()));
        AgentObjectiveKernel.finishAndResume(entry, maintenance.objectiveId(),
                AgentObjectiveStatus.FAILED, "ended by another owner", 150L);

        assertFalse(AgentRemediationCoordinator.finish(entry, frame.frameId(),
                AgentObjectiveStatus.FAILED, "route failed", 200L));
        assertFalse(AgentRemediationCoordinator.owns(entry, maintenance.objectiveId()));

        // The next shortage can start its own remediation instead of being refused forever.
        AgentObjectiveDefinition next = objective("maintenance:supply:2", "chain-2", 9_000L);
        AgentRemediationFrame nextFrame = new AgentRemediationFrame(
                "supply:2", AgentRemediationKind.LOW_SUPPLIES, next.objectiveId(),
                "chain-2", 1, 300L, 9_000L, Map.of());
        assertTrue(AgentRemediationCoordinator.begin(entry, nextFrame, next, "HP empty", 300L));
        assertEquals(next, AgentObjectiveKernel.active(entry));
    }

    private static AgentObjectiveDefinition objective(String id, String correlation, long deadline) {
        return new AgentObjectiveDefinition(id, "test", 100, deadline, 1,
                AgentObjectiveSource.RECOVERY_POLICY, "test-v1", correlation);
    }
}

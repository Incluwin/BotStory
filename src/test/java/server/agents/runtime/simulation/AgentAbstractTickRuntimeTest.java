package server.agents.runtime.simulation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAbstractTickRuntimeTest {
    @Test
    void tickAfterTheScopeEndedRecordsNoUnsupportedOutcome() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.simulationState().allowAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        AgentSimulationTransitionService transitions = new AgentSimulationTransitionService(
                runtime -> true,
                AgentBackgroundOutcomeReconciler.ledgerBacked(),
                new AgentAbstractRecoveryPolicy(map -> false, Long.MAX_VALUE));
        transitions.transition(entry, AgentSimulationMode.BACKGROUND_ABSTRACT, 10L);
        entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);

        AgentAbstractTickRuntime.tick(entry, 20L);
        AgentAbstractTickRuntime.tick(entry, 30L);

        AgentBackgroundOutcomeLedger.Snapshot outcomes =
                entry.simulationState().backgroundOutcomes().snapshot();
        assertTrue(outcomes.active());
        assertNull(outcomes.unsupportedOutcome());
        assertEquals(0L, outcomes.heartbeatCount());
        assertTrue(entry.simulationState().backgroundOutcomes().reconcile(
                AgentMaterializedStateFingerprint.capture(agent)));
    }
}

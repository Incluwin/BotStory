package server.agents.runtime.simulation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.townlife.AgentTownLifeState;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAbstractTickRuntimeTest {
    private static final int HENESYS = 100_000_000;

    @Test
    void aPendingShopVisitKeepsATownLifeAgentOutOfAbstractExecution() {
        // live 10-29: Miruro and CrazyMuffin stood at Henesys's spawn (112,197) with a visit to Inkwell 9000069 pending
        // 21-23 minutes; the abstract tick runs TownLife alone, so the visit was never ticked
        Character agent = mock(Character.class);
        MapleMap henesys = mock(MapleMap.class);
        when(agent.getMap()).thenReturn(henesys);
        when(agent.getMapId()).thenReturn(HENESYS);
        when(agent.getPosition()).thenReturn(new Point(112, 197));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.simulationState().allowAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        entry.capabilityStates().require(AgentTownLifeState.STATE_KEY).start(0L, 0, HENESYS);
        assertTrue(AgentAbstractTickRuntime.permits(entry, henesys), "an unwatched town life runs abstract");

        AgentShopStateRuntime.startShopVisit(entry, new Point(3532, 382), new Point(3532, 394), 0, 1_000L);
        assertFalse(AgentAbstractTickRuntime.permits(entry, henesys), "its shop visit is ticked in an active mode");

        AgentShopStateRuntime.clearShopState(entry);
        assertTrue(AgentAbstractTickRuntime.permits(entry, henesys), "the visit over, town life runs abstract again");
    }

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

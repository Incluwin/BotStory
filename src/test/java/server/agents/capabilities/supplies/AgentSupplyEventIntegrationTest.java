package server.agents.capabilities.supplies;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.events.AgentEventBus;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentEventDispatchRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSessionEventRuntime;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSupplyEventIntegrationTest {
    @Test
    void typedThresholdFactFansOutWithoutMutatingMaintenanceDuringDispatch() throws Exception {
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(agent.getId()).thenReturn(200);
        when(agent.getMapId()).thenReturn(1010100);
        when(agent.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(false);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentRelationshipRuntime.setCohortId(entry, 100L);
        boolean previousDialogueTransport =
                config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
        AgentEventBus bus = AgentSessionEventRuntime.bus(entry);

        try {
            AgentResourcePlanningRuntime.observe(
                    entry,
                    AgentResourceCategory.HP_POTION,
                    0,
                    10,
                    2,
                    20,
                    1_000L);

            assertEquals(1, bus.snapshot().queued());
            assertEquals(2, AgentEventDispatchRuntime.drain(entry));
            assertEquals(1, entry.actionMailbox().size());
            assertNull(entry.capabilityStates()
                    .require(AgentSupplyMaintenanceEvaluationState.STATE_KEY).next());

            AgentSupplyEventMetricsState metrics = entry.capabilityStates()
                    .require(AgentSupplyEventMetricsState.STATE_KEY);
            assertEquals(1L, metrics.transitionsTo(AgentSupplyUrgency.EMPTY));
            assertEquals(AgentResourceCategory.HP_POTION, metrics.lastEvent().category());

            assertEquals(1, AgentMailboxRuntime.drain(entry));
            assertSame(metrics.lastEvent(), entry.capabilityStates()
                    .require(AgentSupplyMaintenanceEvaluationState.STATE_KEY).next());
        } finally {
            AgentSessionEventRuntime.close(entry);
            config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED =
                    previousDialogueTransport;
        }
    }
}

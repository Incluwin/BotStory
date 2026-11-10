package server.agents.stabilization;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.runtime.AgentCapabilityResource;
import server.agents.capabilities.runtime.AgentCapabilityResourceLockState;
import server.agents.policy.behavior.AgentBehaviorCapability;
import server.agents.runtime.AgentBehaviorRoutingRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lightweight 500-Agent gate for bounded control-plane state. Live movement,
 * map, and mob soak testing remains an operational server test.
 */
class AgentStabilizationScaleGateTest {
    @Test
    void fiveHundredAgentControlPlaneRemainsBounded() throws Exception {
        List<AgentRuntimeEntry> entries = new ArrayList<>();
        for (int id = 1; id <= 500; id++) {
            Character agent = mock(Character.class);
            when(agent.getId()).thenReturn(id);
            AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
            entries.add(entry);

            AgentBehaviorRoutingRuntime.assignStableCanary(
                    entry, AgentBehaviorCapability.COMBAT, "v1", "v2", 10);
            AgentCapabilityResourceLockState locks = entry.capabilityStates()
                    .require(AgentCapabilityResourceLockState.STATE_KEY);
            assertTrue(locks.acquire("soak:" + id,
                    Set.of(AgentCapabilityResource.MOVEMENT), id, id + 1_000L));
            locks.releaseOwner("soak:" + id);
        }

        assertEquals(500, entries.size());
        for (AgentRuntimeEntry entry : entries) {
            assertEquals(0, entry.capabilityStates()
                    .require(AgentCapabilityResourceLockState.STATE_KEY).size(1_000L));
        }
    }
}

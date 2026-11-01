package server.agents.runtime.activity.control.facade;

import org.junit.jupiter.api.Test;
import server.agents.runtime.activity.session.AgentActivityKind;
import client.Character;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLiveActivityFacadeRegistryTest {
    @Test
    void standardRegistryCoversEverySessionedPrimarySystemAndPublishesHonestRollbackReadiness() {
        AgentLiveActivityFacadeRegistry registry = AgentStandardLiveActivityFacades.registry();
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(27);
        // COMMERCE runs no sessions since the agent economy engine went (AgentActivityBootstrap.commerce)
        java.util.EnumSet.complementOf(java.util.EnumSet.of(AgentActivityKind.COMMERCE)).forEach(kind -> assertTrue(
                registry.bind(kind, entry, agent).rollbackSupported(), kind.name()));
        assertThrows(IllegalStateException.class, () -> registry.bind(AgentActivityKind.COMMERCE, entry, agent));
    }
}

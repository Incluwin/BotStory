package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.plans.AgentScriptTaskQueueService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentMovementCommandRuntimeTest {
    @Test
    void followAfterStopFollowsTheOwnerAgain() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(5);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);

        try (MockedStatic<AgentScriptTaskQueueService> tasks = mockStatic(AgentScriptTaskQueueService.class);
             MockedStatic<AgentShopService> shops = mockStatic(AgentShopService.class)) {
            AgentModeService.startStop(entry);
            assertNull(AgentRelationshipRuntime.followTarget(entry));
            assertFalse(AgentModeStateRuntime.following(entry));

            AgentMovementCommandRuntime.followConfiguredTarget(entry);

            assertSame(owner, AgentRelationshipRuntime.followTarget(entry));
            assertTrue(AgentModeStateRuntime.following(entry));
        }
    }

    @Test
    void ownerlessAgentWithoutFollowTargetStaysStopped() {
        Character bot = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);

        try (MockedStatic<AgentScriptTaskQueueService> tasks = mockStatic(AgentScriptTaskQueueService.class);
             MockedStatic<AgentShopService> shops = mockStatic(AgentShopService.class)) {
            AgentMovementCommandRuntime.followConfiguredTarget(entry);

            assertNull(AgentRelationshipRuntime.followTarget(entry));
            assertFalse(AgentModeStateRuntime.following(entry));
        }
    }
}

package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAirborneMovementServiceTest {
    private AgentEpqSession registered;

    @AfterEach
    void clearSession() {
        if (registered != null) AgentEpqSessionRegistry.remove(registered);
    }

    @Test
    void appliesAirSteeringWhenNoFixedArcGraceOrNavigationEdgeExists() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertTrue(AgentAirborneMovementService.shouldApplyAirSteering(entry));
    }

    @Test
    void fixedAirArcSuppressesAirSteering() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementPhysicsStateRuntime.setFixedAirArc(entry, true);

        assertFalse(AgentAirborneMovementService.shouldApplyAirSteering(entry));
    }

    @Test
    void downJumpGraceSuppressesAirSteering() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentMovementStateRuntime.setDownJumpGracePeriodMs(entry, 10L);

        assertFalse(AgentAirborneMovementService.shouldApplyAirSteering(entry));
    }

    @Test
    void epqAirborneStepWaitsForNewMapTrackingBeforeUsingPhysics() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(7);
        when(agent.getMapId()).thenReturn(AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMapStateRuntime.setMapTracking(
                entry, AgentEpqDefinition.STAGE_THREE_MAP, Map.of());

        registered = new AgentEpqSession(
                AgentEpqSession.Mode.TEST_OBSERVATION, 11L, 7, 10L);
        for (int id = 7; id <= 11; id++) {
            registered.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        registered.bindEventInstance(mock(EventInstanceManager.class));
        AgentEpqSessionRegistry.registerComplete(registered);

        assertTrue(AgentAirborneMovementService.awaitingEpqMapTransition(entry, agent));
        AgentMapStateRuntime.setMapTracking(
                entry, AgentEpqDefinition.STAGE_FOUR_MAP, Map.of());
        assertFalse(AgentAirborneMovementService.awaitingEpqMapTransition(entry, agent));
    }
}

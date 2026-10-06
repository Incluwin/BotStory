package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.navigation.AgentNavigationDebugStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentClimbMovementServiceTest {
    @Test
    void holdClimbIdleUsesAgentMovementDistances() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertTrue(AgentClimbMovementService.shouldHoldClimbIdle(entry, 0, 0));
        assertFalse(AgentClimbMovementService.shouldHoldClimbIdle(entry, AgentMovementPhysicsConfig.configuredStopDist(), 0));
    }

    @Test
    void snapToClimbTargetAllowsPreciseBottomAnchor() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Rope rope = new Rope(10, 100, 300, false);
        AgentClimbStateRuntime.setClimbingOnRope(entry, rope);
        AgentNavigationDebugStateRuntime.setNavPreciseTarget(entry, true);

        assertTrue(AgentClimbMovementService.shouldSnapToClimbTarget(entry, new Point(10, 300), 1));
    }

    @Test
    void ropeJumpsLaunchWithTheUnroundedRopeJumpVelocity() {
        MapleMap map = mock(MapleMap.class);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(100, 50));
        when(agent.getHp()).thenReturn(1);
        double westward = AgentMovementKinematicsService.ropeJumpVelocityX(map, AgentMovementProfile.base(), -1);

        AgentRuntimeEntry transfer = new AgentRuntimeEntry(agent, null, null);
        AgentClimbStateRuntime.setClimbingOnRope(transfer, new Rope(100, 0, 200, false));
        AgentClimbMovementService.jumpToRope(transfer, agent, -6);
        AgentRuntimeEntry exit = new AgentRuntimeEntry(agent, null, null);
        AgentClimbStateRuntime.setClimbingOnRope(exit, new Rope(100, 0, 200, false));
        AgentClimbMovementService.jumpOffRope(exit, agent, -6);

        assertEquals(westward, AgentMovementPhysicsStateRuntime.airVelocityX(transfer));
        assertEquals(westward, AgentMovementPhysicsStateRuntime.airVelocityX(exit));
    }
}

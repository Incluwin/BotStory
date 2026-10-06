package server.agents.capabilities.movement;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentMovementKinematicsServiceTest {
    @Test
    void baseWalkStepUsesConfiguredMovementProfile() {
        MapleMap map = mock(MapleMap.class);

        assertEquals(
                AgentMovementKinematicsService.walkStep(map, AgentMovementProfile.base()),
                AgentMovementKinematicsService.walkStep(map));
    }

    @Test
    void walkStepScalesWithMovementProfile() {
        MapleMap map = mock(MapleMap.class);

        int base = AgentMovementKinematicsService.walkStep(map, AgentMovementProfile.base());
        int faster = AgentMovementKinematicsService.walkStep(map, new AgentMovementProfile(150, 100));

        assertEquals(Math.round(base * 1.5f), faster);
    }

    @Test
    void ropeJumpVelocityKeepsTheWalkSpeedUnrounded() {
        MapleMap map = mock(MapleMap.class);

        assertEquals(6, AgentMovementKinematicsService.walkStep(map, AgentMovementProfile.base()));
        assertEquals(-6.25, AgentMovementKinematicsService.ropeJumpVelocityX(map, AgentMovementProfile.base(), -6), 0.001);
        assertEquals(7, AgentMovementKinematicsService.walkStep(map, new AgentMovementProfile(105, 100)));
        assertEquals(6.5625, AgentMovementKinematicsService.ropeJumpVelocityX(map, new AgentMovementProfile(105, 100), 1), 0.001);
        assertEquals(0.0, AgentMovementKinematicsService.ropeJumpVelocityX(map, AgentMovementProfile.base(), 0));
    }
}

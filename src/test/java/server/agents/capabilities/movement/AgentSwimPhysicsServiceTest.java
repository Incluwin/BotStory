package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSwimPhysicsServiceTest {
    @Test
    void applySwimMotionPreservesLegacySwimStateTransition() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(null);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);

        AgentSwimPhysicsService.applySwimMotion(entry);

        assertTrue(AgentSwimStateRuntime.swimming(entry));
    }

    @Test
    void applySwimMotionAppliesAgentOwnedHorizontalIntent() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(null);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentSwimStateRuntime.setSwimMoveDirection(entry, 1);

        AgentSwimPhysicsService.applySwimMotion(entry);

        assertEquals(1, AgentMovementStateRuntime.facingDirection(entry));
        assertTrue(AgentMovementPhysicsStateRuntime.horizontalSpeed(entry) > 0.0);
        assertTrue(AgentSwimStateRuntime.swimming(entry));
    }

    @Test
    void applySwimMotionRecordsSteeredWallCollision() {
        MapleMap map = new MapleMap(910000013, 0, 0, 910000013, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(50, 0), new Point(50, 100), 1));
        map.setFootholds(footholds);

        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(48, 50));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentSwimStateRuntime.setSwimming(entry, true);
        AgentSwimStateRuntime.setSwimMoveDirection(entry, 1);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, 48, 50);
        AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry, 400);

        AgentSwimPhysicsService.applySwimMotion(entry);

        assertTrue(AgentSwimStateRuntime.swimWallBlocked(entry));
    }

    @Test
    void aDownJumpInTheWaterSinksThroughTheFloorItStartsOn() {
        MapleMap map = new MapleMap(910000206, 0, 0, 910000206, 1.0f);
        map.setSwim(true);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(0, 300), new Point(200, 300), 2));
        map.setFootholds(footholds);

        AtomicReference<Point> position = new AtomicReference<>(new Point(100, 100));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementPoseService.resetMotion(entry, position.get());

        AgentQueuedMovementActionService.beginDownJump(entry, agent);
        for (int tick = 0; tick < 40 && AgentMovementStateRuntime.inAir(entry); tick++) {
            AgentMotionTimerService.tickMotionTimers(entry);
            AgentSwimStateRuntime.setSwimVerticalHold(entry, 1);
            AgentSwimPhysicsService.applySwimMotion(entry);
        }

        assertFalse(AgentMovementStateRuntime.inAir(entry));
        assertEquals(new Point(100, 300), position.get());
    }

    @Test
    void aDownJumpOnAquaRoadsFloorOverUnder00SinksToTheFloorBelow() {
        // Aqua Road (230040000): each down-jump toward under00 landed back on the floor it started from, and Agents
        // walking back from a supply trip down-jumped in place there for the trip's 300 s.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(230040000);
        AtomicReference<Point> position = new AtomicReference<>(new Point(-600, 324));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementPoseService.resetMotion(entry, position.get());

        AgentQueuedMovementActionService.beginDownJump(entry, agent);
        for (int tick = 0; tick < 80 && AgentMovementStateRuntime.inAir(entry); tick++) {
            AgentMotionTimerService.tickMotionTimers(entry);
            AgentSwimStateRuntime.setSwimVerticalHold(entry, 1);
            AgentSwimPhysicsService.applySwimMotion(entry);
        }

        assertFalse(AgentMovementStateRuntime.inAir(entry));
        assertTrue(position.get().y > 580 && position.get().y < 595, "landed at " + position.get());
    }

    private static Character trackedAgent(MapleMap map, AtomicReference<Point> position) {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        return agent;
    }
}

// Pins behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2. When the fix lands, flip the assertion and rename the test.
package server.agents.capabilities.follow;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentIdlePhysicsService;
import server.agents.capabilities.movement.AgentMovementPoseService;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.MapGateway;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterizes the cross-map follow warp as production wires it: the public
 * {@link AgentFollowMapSyncCoordinator#syncFollowMap(AgentRuntimeEntry, Character, Character)} (:19-32, called
 * from AgentLiveTickGateRuntime:83 and AgentMovementOnlyModeCoordinator:52) builds the hooks from
 * AgentGroundingService::findGroundPoint, AgentMapGatewayRuntime.map()::changeMap and
 * AgentIdlePhysicsService::tickPhysicsOnly and hands them to {@link AgentFollowMapSyncService#syncFollowMap}
 * (:33-59). The grounding, pose, reset and physics statics and the map gateway are mocked.
 */
class AgentFollowMapSyncCoordinatorCharacterizationTest {
    private final MapGateway gateway = mock(MapGateway.class);

    private MockedStatic<AgentGroundingService> grounding;
    private MockedStatic<AgentMapGatewayRuntime> gatewayRuntime;
    private MockedStatic<AgentMovementPoseService> pose;
    private MockedStatic<AgentMovementStateResetService> reset;
    private MockedStatic<AgentIdlePhysicsService> physics;

    @BeforeEach
    void mockRuntimes() {
        grounding = mockStatic(AgentGroundingService.class);
        gatewayRuntime = mockStatic(AgentMapGatewayRuntime.class);
        gatewayRuntime.when(AgentMapGatewayRuntime::map).thenReturn(gateway);
        pose = mockStatic(AgentMovementPoseService.class);
        reset = mockStatic(AgentMovementStateResetService.class);
        physics = mockStatic(AgentIdlePhysicsService.class);
    }

    @AfterEach
    void closeRuntimes() {
        physics.close();
        reset.close();
        pose.close();
        gatewayRuntime.close();
        grounding.close();
    }

    @Test
    void followerPopsInBesideTheAnchorInsteadOfAtThePortal() {
        // the owner came through a portal and has walked to (840, 150) when the follow tick runs
        MapleMap henesys = map(100000000);
        MapleMap destination = map(100010000);
        Character agent = character(henesys, 100000000, new Point(10, 10));
        Character anchor = character(destination, 100010000, new Point(840, 150));
        Point ground = new Point(842, 148);
        grounding.when(() -> AgentGroundingService.findGroundPoint(destination, new Point(840, 149)))
                .thenReturn(ground);
        AgentRuntimeEntry entry = following(agent);

        assertTrue(AgentFollowMapSyncCoordinator.syncFollowMap(entry, agent, anchor));

        // syncFollowMap:50-54 grounds the anchor's current position one pixel up and :56 warps there with the
        // gateway's plain changeMap; no portal is looked up and changeMapNear (CosmicMapGateway:59-81) is unused
        grounding.verify(() -> AgentGroundingService.findGroundPoint(destination, new Point(840, 149)));
        pose.verify(() -> AgentMovementPoseService.idleOnGround(entry, agent));
        verify(gateway).changeMap(agent, destination, ground);
        verify(gateway, never()).changeMapNear(any(), any(), any());
        verify(gateway, never()).enterPortal(any(), anyInt());
        reset.verify(() -> AgentMovementStateResetService.resetEntryState(entry));
        verify(destination, never()).findClosestPortal(any());
        verify(destination, never()).findClosestPlayerSpawnpoint(any());
        verify(destination, never()).getPortal(anyInt());
        verify(destination, never()).getPortal(anyString());
    }

    @Test
    void unregisteredFollowerHoldsAtTheEntranceInsteadOfEnteringTheAnchorsInstanceMap() {
        // KPQ stage 1: the owner is registered in the event instance, the companion is not
        MapleMap town = map(103000000);
        MapleMap instanceStage = map(103000800);
        Character agent = character(town, 103000000, new Point(10, 10));
        Character anchor = character(instanceStage, 103000800, new Point(-200, 100));
        EventInstanceManager kpq = mock(EventInstanceManager.class);
        when(anchor.getEventInstance()).thenReturn(kpq);
        when(agent.getEventInstance()).thenReturn(null);
        when(instanceStage.getEventInstance()).thenReturn(kpq);
        AgentRuntimeEntry entry = following(agent);

        // the tick is consumed, so the companion neither warps nor walks toward the anchor's coordinates
        assertTrue(AgentFollowMapSyncCoordinator.syncFollowMap(entry, agent, anchor));

        // mayWarpInto (syncFollowMap:45, AgentFollowMapSyncService:66-76) sees that 103000800 belongs to an
        // instance the Agent is not registered in. A warp would re-resolve the id through the Agent's getWarpMap
        // (Character.java:1556-1567) into the channel's ordinary copy, so :46-48 hold it on the entrance map with
        // physics only instead.
        physics.verify(() -> AgentIdlePhysicsService.tickPhysicsOnly(entry, agent));
        verify(gateway, never()).changeMap(any(), any(), any());
        verify(gateway, never()).changeMapNear(any(), any(), any());
        grounding.verifyNoInteractions();
        pose.verifyNoInteractions();
        reset.verifyNoInteractions();
    }

    private static AgentRuntimeEntry following(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentModeStateRuntime.setFollowing(entry, true);
        return entry;
    }

    private static Character character(MapleMap map, int mapId, Point position) {
        Character character = mock(Character.class);
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(mapId);
        when(character.getPosition()).thenReturn(new Point(position));
        return character;
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }
}

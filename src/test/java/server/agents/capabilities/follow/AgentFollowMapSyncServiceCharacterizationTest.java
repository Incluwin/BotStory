// Pins behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2. When the fix lands, flip the assertion and rename the test.
package server.agents.capabilities.follow;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Characterizes the cross-map warp in {@link AgentFollowMapSyncService#syncFollowMap}. */
class AgentFollowMapSyncServiceCharacterizationTest {
    private final List<Point> groundQueries = new ArrayList<>();
    private final List<MapleMap> changedMaps = new ArrayList<>();
    private final List<Point> changedPositions = new ArrayList<>();

    @Test
    void followerPopsInBesideTheAnchorInsteadOfAtThePortal() {
        // the owner came through a portal and has walked to (840, 150) when the follow tick runs
        MapleMap henesys = map(100000000);
        MapleMap destination = map(100010000);
        Character agent = character(henesys, 100000000, new Point(10, 10));
        Character anchor = character(destination, 100010000, new Point(840, 150));
        Point ground = new Point(842, 148);

        assertTrue(AgentFollowMapSyncService.syncFollowMap(following(agent), agent, anchor, hooks(ground)));

        // syncFollowMap:43-47 grounds the anchor's position; it never asks the target map for a portal
        assertEquals(List.of(new Point(840, 149)), groundQueries);
        assertEquals(List.of(ground), changedPositions);
        verifyNoInteractions(destination);
    }

    @Test
    void followerIsWarpedToTheAnchorsInstanceMapWithoutAnyEventCheck() {
        // KPQ stage 1: the owner is registered in the event instance, the companion is not
        MapleMap town = map(103000000);
        MapleMap instanceStage = map(103000800);
        Character agent = character(town, 103000000, new Point(10, 10));
        Character anchor = character(instanceStage, 103000800, new Point(-200, 100));
        when(anchor.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(agent.getEventInstance()).thenReturn(null);

        assertTrue(AgentFollowMapSyncService.syncFollowMap(following(agent), agent, anchor, hooks(null)));

        // the guards at :35-38 are mode, anchor, changing-maps and map id only. The production hook
        // (CosmicMapGateway.changeMap:53 -> Character.changeMap(MapleMap, Point), Character.java:1677-1691)
        // re-resolves the target by id through getWarpMap (:1556-1567, :1681), so an Agent with no event
        // instance lands in the channel's ordinary copy of 103000800.
        assertEquals(1, changedMaps.size());
        assertSame(instanceStage, changedMaps.get(0));
        assertEquals(103000800, changedMaps.get(0).getId());
        verify(anchor, never()).getEventInstance();
        verify(agent, never()).getEventInstance();
    }

    private AgentFollowMapSyncService.FollowMapSyncHooks hooks(Point groundPoint) {
        return new AgentFollowMapSyncService.FollowMapSyncHooks(
                (map, point) -> {
                    groundQueries.add(new Point(point));
                    return groundPoint;
                },
                (entry, agent) -> { },
                (agent, map, point) -> {
                    changedMaps.add(map);
                    changedPositions.add(new Point(point));
                },
                entry -> { });
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

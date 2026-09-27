package server.agents.capabilities.follow;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The follow instance guard (docs/BOT_MANAGEMENT_PLAN.md P1, "Follow guards"): a following Agent is warped
 * to its anchor's map only when the warp can land beside the anchor. Otherwise it holds where it is.
 */
class FollowInstanceGuardTest {
    private static final int KERNING = 103000000;
    private static final int KPQ_STAGE_1 = 103000800;

    private final List<String> calls = new ArrayList<>();
    private final MapleMap entrance = map(KERNING);
    private final MapleMap stage = map(KPQ_STAGE_1);
    private final Character agent = character(entrance, KERNING);
    private final Character anchor = character(stage, KPQ_STAGE_1);
    private final AgentRuntimeEntry entry = following(agent);

    @Test
    void unregisteredAgentHoldsOnTheEntranceMapWhileTheAnchorIsInAnInstance() {
        when(stage.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        assertTrue(sync());

        assertEquals(List.of("hold"), calls);
    }

    @Test
    void agentRegisteredInTheAnchorsInstanceFollowsIntoIt() {
        EventInstanceManager kpq = mock(EventInstanceManager.class);
        when(stage.getEventInstance()).thenReturn(kpq);
        when(agent.getEventInstance()).thenReturn(kpq);

        assertTrue(sync());

        assertEquals(List.of("ground", "idle", "warp " + KPQ_STAGE_1, "reset"), calls);
    }

    @Test
    void agentRegisteredInAnotherInstanceHolds() {
        when(stage.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(agent.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        assertTrue(sync());

        assertEquals(List.of("hold"), calls);
    }

    @Test
    void agentStillInAnInstanceFollowsTheAnchorOutOfIt() {
        // the stock warp runs the event's changedMap, which lets the Agent out with the anchor
        EventInstanceManager kpq = mock(EventInstanceManager.class);
        when(entrance.getEventInstance()).thenReturn(kpq);
        when(agent.getEventInstance()).thenReturn(kpq);
        MapleMap town = map(KERNING + 1);
        Character outside = character(town, KERNING + 1);

        assertTrue(AgentFollowMapSyncService.syncFollowMap(entry, agent, outside, hooks()));

        assertEquals(List.of("ground", "idle", "warp " + (KERNING + 1), "reset"), calls);
    }

    @Test
    void neverWarpsAcrossChannels() {
        when(entrance.getChannelServer()).thenReturn(mock(Channel.class));
        when(stage.getChannelServer()).thenReturn(mock(Channel.class));

        assertTrue(sync());

        assertEquals(List.of("hold"), calls);
    }

    @Test
    void neverWarpsWhileTheAnchorIsAwayFromTheChannelWorld() {
        // Client.changeChannel marks the leaving character away; the arriving one stays away until it enters
        when(anchor.isAwayFromWorld()).thenReturn(true);

        assertTrue(sync());

        assertEquals(List.of("hold"), calls);
    }

    @Test
    void anchorMidMapTransitionIsNeitherFollowedNorHeld() {
        when(stage.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(anchor.isChangingMaps()).thenReturn(true);

        assertFalse(sync());

        assertEquals(List.of(), calls);
    }

    @Test
    void heldAgentFollowsOnFootOnceTheAnchorIsBackOnTheEntranceMap() {
        when(stage.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(sync());

        Character back = character(entrance, KERNING);

        assertFalse(AgentFollowMapSyncService.syncFollowMap(entry, agent, back, hooks()));
        assertEquals(List.of("hold"), calls);
    }

    @Test
    void ordinaryMapsOnTheSameChannelAreStillWarpedTo() {
        assertTrue(sync());

        assertEquals(List.of("ground", "idle", "warp " + KPQ_STAGE_1, "reset"), calls);
    }

    private boolean sync() {
        return AgentFollowMapSyncService.syncFollowMap(entry, agent, anchor, hooks());
    }

    private AgentFollowMapSyncService.FollowMapSyncHooks hooks() {
        return new AgentFollowMapSyncService.FollowMapSyncHooks(
                (map, point) -> {
                    calls.add("ground");
                    return null;
                },
                (entry, agent) -> calls.add("idle"),
                (agent, map, point) -> calls.add("warp " + map.getId()),
                entry -> calls.add("reset"),
                (entry, agent) -> calls.add("hold"));
    }

    private static AgentRuntimeEntry following(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentModeStateRuntime.setFollowing(entry, true);
        return entry;
    }

    private static Character character(MapleMap map, int mapId) {
        Character character = mock(Character.class);
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(mapId);
        when(character.getPosition()).thenReturn(new Point(0, 0));
        return character;
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }
}

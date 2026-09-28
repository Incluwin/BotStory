package server.agents.capabilities.follow;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.plans.AgentScriptTaskQueueService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The follow seam (docs/BOT_MANAGEMENT_PLAN.md P2b, "Follow guards"). Travel guard: an Agent the host holds off follow
 * never starts following and is never warped after its anchor. Portal-true follow: a follower comes into its anchor's
 * new map where the host says the anchor came in, on its turn; with nothing known it lands beside the anchor as
 * before, and the instance, channel and away-from-world guards still come first.
 */
class AgentFollowGuardTest {
    private static final int BOT = 7;
    private static final int OWNER = 5;
    private static final int HENESYS = 100000000;
    private static final int KERNING = 103000000;
    private static final int PERION = 102000000;
    private static final int KPQ_STAGE_1 = 103000800;

    private final List<String> calls = new ArrayList<>();
    private final Set<Integer> heldOff = new HashSet<>();
    private AgentFollowGuard.Crossing crossing;
    private final MapleMap henesys = map(HENESYS);
    private final Character agent = character(BOT, henesys, new Point(0, 0));
    private final Character owner = character(OWNER, map(KERNING), new Point(1300, 90));

    @AfterEach
    void uninstall() {
        AgentFollowGuard.install(null);
    }

    @Test
    void noFollowCommandStartsAnAgentTheHostHoldsOffFollow() {
        install();
        heldOff.add(BOT);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, owner, null);
        try (MockedStatic<AgentScriptTaskQueueService> tasks = mockStatic(AgentScriptTaskQueueService.class);
             MockedStatic<AgentShopService> shops = mockStatic(AgentShopService.class)) {
            AgentModeService.startStop(entry); // loaded standing still, as a travelling companion is

            AgentMovementCommandRuntime.followConfiguredTarget(entry); // "follow me", a job advance, a resupply...
            AgentMovementCommandRuntime.follow(entry, owner);           // "follow <name>", a script task

            assertFalse(AgentModeStateRuntime.following(entry));
            tasks.verify(() -> AgentScriptTaskQueueService.clearTasks(any()), never());
            shops.verify(() -> AgentShopService.cancelShopVisit(any()), never());

            heldOff.remove(BOT); // the host's own arrival lets it go

            AgentMovementCommandRuntime.followConfiguredTarget(entry);

            assertTrue(AgentModeStateRuntime.following(entry));
        }
    }

    @Test
    void anAgentTheHostHoldsOffFollowIsNeverWarpedWhenItsAnchorChangesMap() {
        install();
        heldOff.add(BOT);
        AgentRuntimeEntry entry = following(agent);

        assertTrue(AgentFollowMapSyncService.syncFollowMap(entry, agent, owner, hooks()));
        Character movedOn = character(OWNER, map(PERION), new Point(400, 150)); // and changes map again
        assertTrue(AgentFollowMapSyncService.syncFollowMap(entry, agent, movedOn, hooks()));

        assertEquals(List.of("hold", "hold"), calls);
    }

    @Test
    void otherAgentsFollowAsBefore() {
        install();
        heldOff.add(BOT + 1);

        assertTrue(sync());

        assertEquals(List.of("ground 1300,89", "idle", "warp " + KERNING + " at 1300,90", "reset"), calls);
    }

    @Test
    void aFollowerComesInWhereItsAnchorCameInNotWhereItStandsNow() {
        install();
        crossing = new AgentFollowGuard.Crossing(new Point(250, 180), 0L);

        assertTrue(sync());

        assertEquals(List.of("ground 250,179", "idle", "warp " + KERNING + " at 250,180", "reset"), calls);
    }

    @Test
    void aFollowerWaitsWhereItIsUntilItsTurn() {
        install();
        crossing = new AgentFollowGuard.Crossing(new Point(250, 180), Long.MAX_VALUE);

        assertTrue(sync());

        assertEquals(List.of("hold"), calls);
    }

    @Test
    void withNoArrivalKnownItLandsBesideTheAnchorAsBefore() {
        install(); // crossing stays null

        assertTrue(sync());

        assertEquals(List.of("ground 1300,89", "idle", "warp " + KERNING + " at 1300,90", "reset"), calls);
    }

    @Test
    void theInstanceChannelAndAwayGuardsComeBeforeAnyCrossing() {
        install();
        crossing = new AgentFollowGuard.Crossing(new Point(250, 180), 0L);
        MapleMap stage = map(KPQ_STAGE_1);
        when(stage.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        Character inRun = character(OWNER, stage, new Point(10, 10));
        AgentRuntimeEntry entry = following(agent);

        assertTrue(AgentFollowMapSyncService.syncFollowMap(entry, agent, inRun, hooks()));

        when(owner.isAwayFromWorld()).thenReturn(true); // changing channel, or in the cash shop
        assertTrue(AgentFollowMapSyncService.syncFollowMap(entry, agent, owner, hooks()));

        MapleMap otherChannel = map(PERION);
        when(otherChannel.getChannelServer()).thenReturn(mock(Channel.class));
        when(henesys.getChannelServer()).thenReturn(mock(Channel.class));
        assertTrue(AgentFollowMapSyncService.syncFollowMap(entry, agent, character(OWNER, otherChannel, new Point(0, 0)),
                hooks()));

        assertEquals(List.of("hold", "hold", "hold"), calls);
    }

    @Test
    void withNothingInstalledEveryAgentFollowsAsBefore() {
        assertTrue(sync());

        assertEquals(List.of("ground 1300,89", "idle", "warp " + KERNING + " at 1300,90", "reset"), calls);
    }

    private void install() {
        AgentFollowGuard.install(new AgentFollowGuard.Rule() {
            @Override
            public boolean holdsOffFollow(int agentCharacterId) {
                return heldOff.contains(agentCharacterId);
            }

            @Override
            public AgentFollowGuard.Crossing crossing(int agentCharacterId, Character anchor) {
                return crossing;
            }
        });
    }

    private boolean sync() {
        return AgentFollowMapSyncService.syncFollowMap(following(agent), agent, owner, hooks());
    }

    private AgentFollowMapSyncService.FollowMapSyncHooks hooks() {
        return new AgentFollowMapSyncService.FollowMapSyncHooks(
                (map, point) -> {
                    calls.add("ground " + point.x + "," + point.y);
                    return null;
                },
                (entry, agent) -> calls.add("idle"),
                (agent, map, point) -> calls.add("warp " + map.getId() + " at " + point.x + "," + point.y),
                entry -> calls.add("reset"),
                (entry, agent) -> calls.add("hold"));
    }

    private static AgentRuntimeEntry following(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentModeStateRuntime.setFollowing(entry, true);
        return entry;
    }

    private static Character character(int id, MapleMap map, Point at) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getMap()).thenReturn(map);
        int mapId = map.getId();
        when(character.getMapId()).thenReturn(mapId);
        when(character.getPosition()).thenReturn(at);
        return character;
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }
}

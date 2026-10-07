package server.agents.capabilities.navigation;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentNavigationWarmupServiceTest {
    private static final int LEADER_ID = 910_001;
    private static final int MAP_ID = 103000000;

    @AfterEach
    void clearThrottle() {
        AgentNavigationWarmupService.clearLeaderRuntimeState(LEADER_ID);
    }

    @Test
    void warmupNoticeGoesToServerLogNotLeaderChat() {
        Character leader = leader();
        Character agent = agent(map(100));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, leader, null);

        assertTrue(AgentNavigationWarmupService.notifyWarmup(entry, agent));
        assertFalse(AgentNavigationWarmupService.notifyWarmup(entry, agent),
                "repeat notices for the same leader and map stay throttled");

        verify(leader, never()).dropMessage(anyInt(), anyString());
        verify(leader, never()).yellowMessage(anyString());
    }

    @Test
    void smallMapsWarmTooQuicklyToReport() {
        Character leader = leader();
        Character agent = agent(map(99));

        assertFalse(AgentNavigationWarmupService.notifyWarmup(new AgentRuntimeEntry(agent, leader, null), agent));
        verify(leader, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void agentsWithoutAWatchingLeaderAreNotReported() {
        Character agent = agent(map(100));

        assertFalse(AgentNavigationWarmupService.notifyWarmup(new AgentRuntimeEntry(agent, null, null), agent));
    }

    private static Character leader() {
        Character leader = mock(Character.class);
        when(leader.getId()).thenReturn(LEADER_ID);
        when(leader.getName()).thenReturn("Leader");
        when(leader.getClient()).thenReturn(mock(Client.class));
        return leader;
    }

    private static Character agent(MapleMap map) {
        Character agent = mock(Character.class);
        when(agent.getName()).thenReturn("Jinx");
        when(agent.getMap()).thenReturn(map);
        return agent;
    }

    private static MapleMap map(int walkableFootholds) {
        List<Foothold> footholds = new ArrayList<>();
        for (int i = 0; i < walkableFootholds; i++) {
            footholds.add(new Foothold(new Point(i * 10, 0), new Point(i * 10 + 10, 0), i + 1));
        }
        footholds.add(new Foothold(new Point(0, 0), new Point(0, -100), walkableFootholds + 1));
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(10_000, 1000));
        footholds.forEach(tree::insert);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP_ID);
        when(map.getFootholds()).thenReturn(tree);
        return map;
    }
}

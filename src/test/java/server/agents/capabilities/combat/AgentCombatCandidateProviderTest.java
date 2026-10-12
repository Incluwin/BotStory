package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P12 G3: a part its run never targets is no primary candidate either (the selector already skipped it as a secondary
 * or projectile target).
 */
class AgentCombatCandidateProviderTest {
    @Test
    void aNeverTargetPartIsNoPrimaryCandidate() {
        MapleMap map = mock(MapleMap.class);
        Monster display = mob(8_810_018, map, new Point(10, 0));
        Monster head = mob(8_810_002, map, new Point(20, 0));
        when(map.getAllMonsters()).thenReturn(List.of(display, head));
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);

        try (MockedStatic<AgentBossRunRegistry> runs = mockStatic(AgentBossRunRegistry.class)) {
            runs.when(() -> AgentBossRunRegistry.neverTarget(display)).thenReturn(true);

            assertEquals(List.of(head), AgentCombatCandidateProvider.local(agent, new Point(0, 0), 10_000));
        }
        assertEquals(List.of(display, head), AgentCombatCandidateProvider.local(agent, new Point(0, 0), 10_000),
                "with no run, both are candidates as today");
    }

    private static Monster mob(int id, MapleMap map, Point at) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getMap()).thenReturn(map);
        when(monster.getPosition()).thenReturn(at);
        return monster;
    }
}

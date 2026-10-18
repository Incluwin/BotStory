package server.life;

import net.server.coordinator.world.MonsterAggroCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;
import server.maps.MapleMap;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A mob with a removeAfter timer (9400547 schedules one 90000 s out) used to keep that task, and itself, in the map
 * scheduler after it died or was removed, until the timer fired. Death and removal now drop the task at once.
 */
class MonsterRemoveAfterDismissTest {
    private static Monster monster(MapleMap map) {
        when(map.getAggroCoordinator()).thenReturn(mock(MonsterAggroCoordinator.class));
        Monster monster = new Monster(9400547, new MonsterStats());
        monster.setMap(map);
        return monster;
    }

    @Test
    void aKillWithAKillerDropsTheRemoveAfterTask() {
        MapleMap map = mock(MapleMap.class);
        Monster monster = monster(map);
        try (MockedStatic<LivingWorldHooks> ignored = mockStatic(LivingWorldHooks.class)) {
            monster.dispatchMonsterKilled(true);
        }
        verify(map, times(1)).dismissRemoveAfter(monster);
    }

    @Test
    void aKillWithoutAKillerDropsTheRemoveAfterTask() {
        MapleMap map = mock(MapleMap.class);
        Monster monster = monster(map);
        try (MockedStatic<LivingWorldHooks> ignored = mockStatic(LivingWorldHooks.class)) {
            monster.dispatchMonsterKilled(false);
        }
        verify(map, times(1)).dismissRemoveAfter(monster);
    }

    @Test
    void removingALiveMobWithoutAKillDropsTheRemoveAfterTask() {
        MapleMap map = spy(new MapleMap(100000000, 0, 1, 999999999, 1f));
        doReturn(mock(MonsterAggroCoordinator.class)).when(map).getAggroCoordinator();
        doNothing().when(map).dismissRemoveAfter(org.mockito.ArgumentMatchers.any());
        Monster monster = new Monster(9400547, new MonsterStats());
        monster.setMap(map);

        map.removeMonsterWithoutKill(monster);
        verify(map, times(1)).dismissRemoveAfter(monster);

        map.removeMonsterWithoutKill(monster);    // already gone: nothing more to drop
        verify(map, times(1)).dismissRemoveAfter(monster);
        verify(map, never()).killMonster(monster, null, false, (short) 0);
    }
}

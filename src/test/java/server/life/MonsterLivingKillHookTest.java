package server.life;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;
import net.server.coordinator.world.MonsterAggroCoordinator;
import server.maps.MapleMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** Every death reaches the living world: Resident quest kill steps, contract proofs and journals count it. */
class MonsterLivingKillHookTest {
    private static Monster monster(MapleMap map) {
        when(map.getAggroCoordinator()).thenReturn(mock(MonsterAggroCoordinator.class));
        Monster monster = new Monster(2230101, new MonsterStats());
        monster.setMap(map);
        return monster;
    }

    @Test
    void aKillWithAKillerReachesTheLivingWorld() {
        MapleMap map = mock(MapleMap.class);
        Monster monster = monster(map);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            monster.dispatchMonsterKilled(true);

            hooks.verify(() -> LivingWorldHooks.mobKilled(eq(monster), anySet(), eq(map)), times(1));
        }
    }

    @Test
    void aKillWithoutAKillerReachesItOnce() {
        MapleMap map = mock(MapleMap.class);
        Monster monster = monster(map);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            monster.dispatchMonsterKilled(false);

            hooks.verify(() -> LivingWorldHooks.mobKilled(any(), anySet(), any()), times(1));
        }
    }
}

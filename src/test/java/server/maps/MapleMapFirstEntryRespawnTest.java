package server.maps;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The first player into a map restores its dormant mobs and respawns it, so a channel map skipped by the dormant
 * update is full on arrival. An event instance map is never dormant and its script owns its spawns: PPQ's side room
 * 925100202 had its 17 Kru and Captains spawned by davy2_hd1 and then 17 more by this respawn when the leader walked
 * in (live 2026-10-18, liveMobs=34). Event maps no longer respawn here.
 */
class MapleMapFirstEntryRespawnTest {
    @Test
    void aChannelMapRespawnsOnItsFirstEntry() {
        MapleMap map = spy(new MapleMap(100000000, 0, 1, 999999999, 1f));
        doNothing().when(map).respawn();

        map.restoreDormantMonsterState();

        verify(map).respawn();
    }

    @Test
    void anEventInstanceMapLeavesItsSpawnsToTheEventScript() {
        MapleMap map = spy(new MapleMap(925100202, 0, 1, 925100700, 1f));
        map.setEventInstance(mock(EventInstanceManager.class));
        doNothing().when(map).respawn();

        map.restoreDormantMonsterState();

        verify(map, never()).respawn();
    }
}

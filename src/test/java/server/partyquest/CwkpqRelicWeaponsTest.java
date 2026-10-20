package server.partyquest;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan Question 4 (a), user-approved content fix: a stage-5 weapon lost before its relic took it (a drop that
 * expired, a holder who left the run) is given once more, and only when no copy is left anywhere in the instance. The
 * stock rooms gave each weapon once, so a lost one locked the run for good.
 */
class CwkpqRelicWeaponsTest {
    private static final int SWORD = 4_001_259;
    private static final int GUN = 4_001_260;

    private final Map<String, String> props = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final List<Character> players = new ArrayList<>();
    private final Reactor swordRelic = reactor(6_109_005, 0);
    private final Reactor gunChest = reactor(6_102_001, 0);

    @BeforeEach
    void anInstance() {
        for (int mapId : CwkpqRelicWeapons.STAGE_MAPS) {
            MapleMap map = mock(MapleMap.class);
            maps.put(mapId, map);
            when(event.getMapInstance(mapId)).thenReturn(map);
        }
        Reactor gunRelic = reactor(6_109_009, 0);
        Reactor decoy = reactor(6_108_003, 0);
        when(maps.get(610_030_500).getAllReactors()).thenReturn(List.of(swordRelic, gunRelic));
        when(maps.get(610_030_550).getAllReactors()).thenReturn(List.of(gunChest, decoy));
        when(event.getPlayers()).thenReturn(players);
        when(event.getProperty(anyString())).thenAnswer(call -> props.get(call.<String>getArgument(0)));
        doAnswer(call -> props.put(call.getArgument(0), call.getArgument(1))).when(event)
                .setProperty(anyString(), anyString());
        players.add(holding(0));
    }

    @Test
    void aWeaponNobodyHoldsAndNoneLiesOnTheStageIsLostOnce() {
        assertTrue(CwkpqRelicWeapons.lost(event, SWORD));

        CwkpqRelicWeapons.markReissued(event, SWORD);
        assertFalse(CwkpqRelicWeapons.lost(event, SWORD), "given a second time only once");
        assertTrue(CwkpqRelicWeapons.lost(event, GUN), "each weapon has its own once");
    }

    @Test
    void aCopyHeldOrLyingAnywhereOnTheStageIsNotLost() {
        players.add(holding(SWORD));
        assertFalse(CwkpqRelicWeapons.lost(event, SWORD), "someone holds it");

        players.removeLast();
        when(maps.get(610_030_510).getDroppedItemsCountById(SWORD)).thenReturn(1);
        assertFalse(CwkpqRelicWeapons.lost(event, SWORD), "it lies in the warrior room");
    }

    @Test
    void aRelicThatTookItsWeaponNeedsNoOther() {
        when(swordRelic.getState()).thenReturn((byte) 1);
        assertFalse(CwkpqRelicWeapons.lost(event, SWORD));
    }

    @Test
    void theGunIsLostOnlyAfterItsChestBroke() {
        assertFalse(CwkpqRelicWeapons.gunChestBroken(event), "still in its chest");
        when(gunChest.getState()).thenReturn((byte) 4);
        assertTrue(CwkpqRelicWeapons.gunChestBroken(event));
        when(gunChest.getState()).thenReturn((byte) 0);
        when(gunChest.isAlive()).thenReturn(false);
        assertTrue(CwkpqRelicWeapons.gunChestBroken(event), "broken and gone");
    }

    @Test
    void notAWeaponOrNoEventIsNeverLost() {
        assertFalse(CwkpqRelicWeapons.lost(event, 4_000_000));
        assertFalse(CwkpqRelicWeapons.lost(null, SWORD));
    }

    @Test
    void theWeaponsAndRelicsAreTheGamesOwn() throws IOException {
        for (CwkpqRelicWeapons.Weapon weapon : CwkpqRelicWeapons.Weapon.values()) {
            String relic = Files.readString(Path.of("wz/Reactor.wz/" + weapon.relicReactorId + ".img.xml"),
                    StandardCharsets.UTF_8);
            assertTrue(relic.contains("<int name=\"0\" value=\"" + weapon.itemId() + "\"/>"), weapon.name());
        }
        assertEquals(4_001_260, CwkpqRelicWeapons.Weapon.FORBIDDEN_GUN.itemId());
    }

    @Test
    void eachMasterGivesALostWeaponOnceMore() throws IOException {
        Map<String, Integer> masters = Map.of("9201107", SWORD, "9201108", 4_001_258, "9201109", 4_001_257,
                "9201110", 4_001_256, "9201111", GUN);
        for (Map.Entry<String, Integer> master : masters.entrySet()) {
            String script = Files.readString(Path.of("scripts/npc/" + master.getKey() + ".js"), StandardCharsets.UTF_8);
            assertTrue(script.contains(".lost(eim, " + master.getValue() + ")"), master.getKey());
            assertTrue(script.contains(".markReissued(eim, " + master.getValue() + ")"), master.getKey());
            assertTrue(script.indexOf("markReissued") < script.indexOf("cm.gainItem(" + master.getValue() + ", 1);",
                    script.indexOf("markReissued")), master.getKey() + " marks it before it gives it");
        }
    }

    private static Character holding(int itemId) {
        Character player = mock(Character.class);
        when(player.getItemQuantity(anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(0);
        if (itemId != 0) {
            when(player.getItemQuantity(itemId, false)).thenReturn(1);
        }
        return player;
    }

    private static Reactor reactor(int id, int state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getId()).thenReturn(id);
        when(reactor.getState()).thenReturn((byte) state);
        when(reactor.isAlive()).thenReturn(true);
        return reactor;
    }
}

package server.agents.capabilities.shop;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A visit's purchase steps trade with the shopkeeper standing at the visit's NPC position, not with the first shop
 * NPC in range: on Herb Town's map the armor shop (2093001) stands 538 px from Lan Ming (2093002), inside the search
 * radius, and live (2026-10-04) every visit to Lan Ming bought nothing from the armor shop's list.
 */
class AgentShopNpcLookupTest {
    private static final Point LAN_MING = new Point(349, 230);

    @Test
    void theShopkeeperNearestTheVisitsPositionIsTheOneTradedWith() {
        NPC armorShop = npc(2093001, new Point(-129, -17), true);
        NPC lanMing = npc(2093002, LAN_MING, true);
        Character bot = botOnMapWith(armorShop, lanMing); // the map lists the armor shop first

        assertEquals(2093002, AgentShopService.findNpcNear(bot, LAN_MING).getId());
    }

    @Test
    void anNpcWithoutAShopNeverAnswersAndNoShopkeeperInRangeIsNone() {
        NPC villager = npc(9010000, LAN_MING, false);
        assertNull(AgentShopService.findNpcNear(botOnMapWith(villager), LAN_MING));
        assertNull(AgentShopService.findNpcNear(botOnMapWith(), LAN_MING));
    }

    private static NPC npc(int id, Point position, boolean shop) {
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(id);
        when(npc.getPosition()).thenReturn(position);
        when(npc.hasShop()).thenReturn(shop);
        return npc;
    }

    private static Character botOnMapWith(NPC... npcs) {
        MapleMap map = mock(MapleMap.class);
        List<MapObject> objects = List.of(npcs);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(objects);
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        return bot;
    }
}

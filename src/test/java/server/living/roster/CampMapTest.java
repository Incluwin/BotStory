package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crew camp's map (docs/WORLD_SYSTEMS_PLAN.md N7.1, decision D10) on the real WZ geometry of the default and the two
 * other candidates: the owner's landing (the first portal) is over ground, every spot a camp companion is set down or
 * walks to is on a floor inside the visible area and they spread over it, and the default's one door is the one the
 * copy closes.
 */
class CampMapTest {
    @ParameterizedTest
    @ValueSource(ints = {220000306, 130000110, 140000010})
    void theLandingIsOverGroundAndEverySpotIsOnAFloorInsideTheMap(int mapId) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        Portal first = map.getPortal(0);
        assertNotNull(first, "a spawn point");
        assertNotNull(map.getPointBelow(new Point(first.getPosition())), "the landing is over ground");
        BenchCampForm.LiveHands hands = new BenchCampForm.LiveHands(null);
        Random random = new Random(3);
        Rectangle area = map.getMapArea();
        Set<Integer> columns = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            Point spot = hands.spot(map, random);
            assertTrue(area.contains(spot), mapId + " spot " + spot + " outside " + area);
            Point below = map.getPointBelow(new Point(spot.x, spot.y - 2));
            assertNotNull(below, "ground under " + spot);
            assertTrue(Math.abs(below.y - spot.y) <= 3, mapId + " " + spot + " stands on " + below);
            columns.add(spot.x / 100);
        }
        assertTrue(columns.size() >= 5, "they spread over the floor: " + columns);
    }

    @Test
    void theDefaultCampIsEmptyHouseTwoWithOneDoorIntoLudibriumThatTheCopyCloses() {
        assertEquals(220000306, new LivingWorldConfig().CREW_CAMP_MAP_ID);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(220000306);
        List<String> doors = new ArrayList<>();
        for (Portal portal : map.getPortals()) {
            if (portal.getTargetMapId() != 999999999) {
                doors.add(portal.getName() + " -> " + portal.getTargetMapId());
            }
        }
        assertEquals(List.of("out00 -> 220000300"), doors, "one door: CrewCamp closes it on every copy");
    }
}

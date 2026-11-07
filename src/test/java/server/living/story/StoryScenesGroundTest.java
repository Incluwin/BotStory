package server.living.story;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a scene sets things down, on the real maps of scripts/config/living-world/scenes/henesys.yaml. A scene's landing
 * is the map's first portal; live 11-07, on The Field South of Ellinia, a monster's spot beside the owner standing there
 * had no ground below it and the scene ended as its fight began.
 */
class StoryScenesGroundTest {

    /** {@link StoryScenes}' spots for an unplaced spawn beat: to either side of the owner, spreading by 30 px. */
    private static Point spot(Point owner, int index) {
        return new Point(owner.x + (index % 2 == 0 ? 160 : -160) + 30 * (index / 2), owner.y);
    }

    @ParameterizedTest
    @ValueSource(ints = {101_040_001, 100_050_000, 100_040_105, 101_040_003})
    void everyMonsterSpotBesideAnOwnerAtTheLandingCanBeSpawnedOn(int mapId) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        Point landing = Stagehand.landing(map, 0, 0);
        Point floor = map.getPointBelow(landing);
        assertNotNull(floor, "the landing is over ground");
        for (int yOffset : new int[] {0, 2}) { // the client's reported height is not exactly the foothold's on a slope
            Point owner = new Point(floor.x, floor.y + yOffset);
            for (int index = 0; index < 8; index++) {
                Point at = StoryScenes.ground(map, spot(owner, index), owner);
                // what spawnMonsterOnGroundBelow does with it
                assertNotNull(map.getPointBelow(new Point(at.x, at.y - 1)),
                        "map " + mapId + " monster " + index + " from " + owner + " at " + at);
            }
        }
    }

    @Test
    void aCompanionBesideTheLandingStandsWellBelowThePortalNotInTheAir() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(100_050_000);
        Point landing = Stagehand.landing(map, 0, 0);

        Point at = StoryScenes.ground(map, new Point(landing.x - 40, landing.y), landing);

        assertTrue(at.y > landing.y + 100, "the portal is about 150 px above this map's floor: " + at);
    }
}

package server.agents.capabilities.movement;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapleMap;

import java.awt.Point;

class ScratchWalkOffProbeTest {
    @Test
    void probe() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(551000100);
        AgentMovementProfile profile = AgentMovementProfile.base();
        System.out.println("BEFORE GRAPH");
        dump(map, profile);
        AgentNavigationGraphService.getGraph(map, profile);
        System.out.println("AFTER GRAPH");
        dump(map, profile);
    }

    private static void dump(MapleMap map, AgentMovementProfile profile) {
        for (Point from : new Point[]{new Point(-869, 297), new Point(-871, 297), new Point(-897, 264),
                new Point(-885, 280), new Point(-889, 296)}) {
            for (int dir : new int[]{-1, 1}) {
                for (AgentWalkOffLanding v : AgentJumpProbeService.walkOffLandingVariants(map, from, dir, profile)) {
                    System.out.printf("WALKOFF from=(%d,%d) dir=%d launch=%s landing=%s fh=%s%n", from.x, from.y,
                            dir, v == null ? null : v.launchPoint(),
                            v == null || v.landing() == null ? null : v.landing().point(),
                            v == null || v.landing() == null || v.landing().foothold() == null ? null
                                    : v.landing().foothold().getId());
                }
            }
            AgentJumpLanding down = AgentJumpProbeService.simulateDownJumpLanding(map, from);
            System.out.printf("DOWNJUMP from=(%d,%d) landing=%s fh=%s%n", from.x, from.y,
                    down == null ? null : down.point(),
                    down == null || down.foothold() == null ? null : down.foothold().getId());
        }
    }
}

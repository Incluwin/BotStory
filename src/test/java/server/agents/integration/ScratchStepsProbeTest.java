package server.agents.integration;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

class ScratchStepsProbeTest {
    @Test
    void sweep() {
        int ticks = Integer.getInteger("probe.ticks", 1200);
        boolean verbose = Boolean.getBoolean("probe.verbose");
        BotMovementSimulationLab.loadMap(551000100);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(551000100);
        int[][] profiles = {{100, 100}, {105, 100}, {110, 100}, {115, 100}, {120, 100}, {110, 105}};
        List<Point> starts = new ArrayList<>();
        starts.add(new Point(-1259, 673));
        for (int x = -1100; x <= -560; x += 20) {
            starts.add(new Point(x, 315));
        }
        for (int x = -925; x <= -835; x += 15) {
            starts.add(new Point(x, 296));
        }
        for (int x = -925; x <= -860; x += 15) {
            starts.add(new Point(x, 264));
            starts.add(new Point(x, 279));
        }
        String only = System.getProperty("probe.only");
        int id = 1000;
        for (int[] p : profiles) {
            AgentMovementProfile profile = new AgentMovementProfile(p[0], p[1]);
            AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, profile);
            for (Point start : starts) {
                String label = p[0] + "/" + p[1] + " " + start.x + "," + start.y;
                if (only != null && !only.equals(label)) {
                    continue;
                }
                BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
                String name = "Corvin" + id;
                AgentRuntimeEntry entry = lab.spawnBot(name, id++, map, start);
                AgentMovementStateRuntime.setMovementProfile(entry, profile);
                lab.setMoveTarget(name, new Point(538, 430), true);
                int arrived = -1;
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                for (int i = 0; i < ticks; i++) {
                    lab.step(1);
                    Point at = lab.position(name);
                    if (verbose) {
                        System.out.println("T " + lab.describeCurrentState(name));
                    }
                    if (i >= ticks - 200) {
                        minX = Math.min(minX, at.x);
                        maxX = Math.max(maxX, at.x);
                    }
                    if (at.distance(538, 430) < 20) {
                        arrived = i;
                        break;
                    }
                }
                Point end = lab.position(name);
                System.out.printf("RESULT %s arrived=%d end=(%d,%d) lastXs=%d..%d%n", label, arrived, end.x, end.y,
                        minX, maxX);
            }
        }
    }
}

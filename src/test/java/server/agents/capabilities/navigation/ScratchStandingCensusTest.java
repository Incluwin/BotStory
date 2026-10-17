package server.agents.capabilities.navigation;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementKinematicsService;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

class ScratchStandingCensusTest {
    @Test
    void census() throws Exception {
        Path dir = Path.of(System.getProperty("census.dir", "cache/bot-nav/v60"));
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".bin")).sorted().toList();
        }
        Map<Integer, MapleMap> maps = new HashMap<>();
        int graphs = 0;
        int affectedGraphs = 0;
        int affectedPoints = 0;
        int sameRegion = 0;
        Map<Integer, TreeSet<String>> byMap = new TreeMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString().replace(".bin", "");
            String[] parts = name.split("-");
            int mapId = Integer.parseInt(parts[0]);
            int speed = Integer.parseInt(parts[1].substring(1));
            int jump = Integer.parseInt(parts[2].substring(1));
            AgentNavigationGraph graph;
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
                graph = (AgentNavigationGraph) in.readObject();
            } catch (Exception e) {
                System.out.println("SKIP " + name + " " + e);
                continue;
            }
            MapleMap map;
            try {
                map = maps.computeIfAbsent(mapId, AgentNavigationMapLoader::loadMapGeometry);
            } catch (Exception e) {
                System.out.println("SKIPMAP " + name + " " + e);
                continue;
            }
            if (map == null || map.getFootholds() == null) {
                continue;
            }
            graphs++;
            AgentMovementProfile profile = new AgentMovementProfile(speed, jump);
            int runwayPx = AgentMovementKinematicsService.launchRunwayPx(map, profile);
            int here = 0;
            for (AgentNavigationGraph.Region from : graph.regions) {
                if (from.isRopeRegion) {
                    continue;
                }
                for (int direction : new int[]{-1, 1}) {
                    Point endpoint = direction < 0 ? from.leftPoint() : from.rightPoint();
                    int launchX = direction < 0 ? Math.min(from.maxX, endpoint.x + runwayPx)
                            : Math.max(from.minX, endpoint.x - runwayPx);
                    if (Math.abs(launchX - endpoint.x) < Math.min(runwayPx, 20)) {
                        continue;
                    }
                    Point start = from.pointAt(launchX);
                    Foothold old = AgentGroundingService.findGroundFoothold(map, start);
                    Foothold now = AgentGroundingService.findStandingFoothold(map, start);
                    int oldId = old == null ? -1 : old.getId();
                    int newId = now == null ? -1 : now.getId();
                    int oldRegion = graph.regionIdByFootholdId.getOrDefault(oldId, -9);
                    int newRegion = graph.regionIdByFootholdId.getOrDefault(newId, -9);
                    if (oldId != newId && oldRegion == newRegion) {
                        sameRegion++;
                    }
                    if (oldId != newId && oldRegion != newRegion) {
                        here++;
                        byMap.computeIfAbsent(mapId, k -> new TreeSet<>()).add(
                                "r" + from.id + (direction < 0 ? "W" : "E") + " fh" + oldId + "->fh" + newId
                                        + " regionFh=" + graph.regionIdByFootholdId.getOrDefault(newId, -9)
                                        + "/" + from.id + " oldRegion=" + oldRegion);
                    }
                }
            }
            if (here > 0) {
                affectedGraphs++;
                affectedPoints += here;
                System.out.println("AFFECTED " + name + " points=" + here);
            }
        }
        for (Map.Entry<Integer, TreeSet<String>> e : byMap.entrySet()) {
            System.out.println("MAP " + e.getKey() + " " + e.getValue());
        }
        System.out.printf("CENSUS graphs=%d affectedGraphs=%d affectedPoints=%d maps=%d sameRegionSwaps=%d%n", graphs,
                affectedGraphs, affectedPoints, byMap.size(), sameRegion);
    }
}

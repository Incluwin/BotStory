package server.agents.capabilities.navigation;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * LPQ stage 2's trap room (922010201): a companion scout leaves it on foot, never moved, so the ledge of each of its
 * four boxes needs a route up the two ropes to out00 (portal 2, back to 922010200 st00).
 */
class AgentLpqTrapRoomExitPreflightTest {
    @Test
    void everyBoxLedgeHasARouteToOut00() {
        int mapId = 922_010_201;
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        Portal exit = map.getPortal(2);
        assertNotNull(exit);
        assertEquals(922_010_200, exit.getTargetMapId());
        assertEquals(new Point(-157, -3_888), exit.getPosition());

        AgentNavigationGraph graph = AgentNavigationGraphService.rebuildGraph(map);
        int exitRegion = graph.findRegionId(map, exit.getPosition());
        Data reactors = DataProviderFactory.getDataProvider(WZFiles.MAP)
                .getData("Map/Map9/" + mapId + ".img").getChildByPath("reactor");
        assertEquals(4, reactors.getChildren().size());
        for (Data reactor : reactors) {
            Point box = new Point(DataTool.getInt("x", reactor, 0), DataTool.getInt("y", reactor, 0));
            List<AgentNavigationGraph.Edge> path = AgentNavigationPathService.findPath(graph, map, box,
                    graph.findRegionId(map, box), exitRegion, exit.getPosition(), "lpq-trap-exit-preflight");
            assertFalse(path.isEmpty(), () -> "the box ledge at " + box + " has no route to out00");
        }
    }
}

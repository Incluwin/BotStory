package server.agents.runtime.field;

import constants.id.MapId;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.life.SpawnPoint;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Field Agents enter at a player-spawn portal and walk to their staging point, so both the
 * generated staging point and the chosen entry portal must respect edge direction: drop-only
 * roofs share the street's connected component but cannot be climbed from the street.
 */
class AgentFieldSafeSpotPolicyTest {
    private static final int STREET = 1;
    private static final int ROOF = 2;
    private static final int LEDGE = 3;
    private static final int UPPER_ROOF = 4;

    @Test
    void generatedStagingSkipsRoofsTheEntryPortalCannotClimbTo() {
        AgentNavigationGraph graph = graph();
        MapleMap map = map(List.of(spawnPortal(100, 500)), List.of(spawn(1000, 500)));
        assertEquals(graph.connectedComponentId(STREET), graph.connectedComponentId(ROOF));
        assertFalse(graph.canReach(STREET, ROOF));
        assertFalse(graph.canReach(STREET, UPPER_ROOF));

        Point first = AgentFieldSafeSpotPolicy.staging(map, graph, 0);
        assertEquals(LEDGE, graph.findRegionId(map, first),
                "the spawn-free ledge the Agent can climb to beats the nearer drop-only roof");
        for (int ordinal = 0; ordinal < 64; ordinal++) {
            Point staging = AgentFieldSafeSpotPolicy.staging(map, graph, ordinal);
            int region = graph.findRegionId(map, staging);
            assertTrue(graph.canReach(STREET, region),
                    "ordinal " + ordinal + " staged on unreachable region " + region + " at " + staging);
        }
    }

    @Test
    void entryPortalMustReachTheStagingPointNotMerelyShareItsComponent() {
        AgentNavigationGraph graph = graph();
        Point streetPortal = new Point(300, 500);
        Point roofPortal = new Point(900, 100);
        MapleMap map = map(List.of(spawnPortal(streetPortal.x, streetPortal.y),
                spawnPortal(roofPortal.x, roofPortal.y)), List.of());

        assertEquals(roofPortal, AgentFieldSafeSpotPolicy.nearestEntry(map, graph, new Point(300, 200)),
                "the street portal is nearer, but only the upper roof can drop onto the roof");
        assertEquals(roofPortal, AgentFieldSafeSpotPolicy.nearestEntry(map, graph, new Point(1500, 500)),
                "a portal on a drop-only roof still serves staging below it");
        assertEquals(streetPortal, AgentFieldSafeSpotPolicy.nearestEntry(map, graph, new Point(400, 500)));
    }

    /**
     * Street 1 (0..2000, y 500); drop-only roof 2 (100..400, y 200); climbable ledge 3
     * (1500..1800, y 200); upper roof 4 (800..1000, y 100) that only drops onto roof 2 or the street.
     */
    private static AgentNavigationGraph graph() {
        List<AgentNavigationGraph.Region> regions = List.of(
                ground(STREET, 0, 2000, 500), ground(ROOF, 100, 400, 200),
                ground(LEDGE, 1500, 1800, 200), ground(UPPER_ROOF, 800, 1000, 100));
        Map<Integer, AgentNavigationGraph.Region> regionsById = new HashMap<>();
        Map<Integer, Integer> regionIdByFootholdId = new HashMap<>();
        for (AgentNavigationGraph.Region region : regions) {
            regionsById.put(region.id, region);
            regionIdByFootholdId.put(region.id, region.id);
        }
        Map<Integer, List<AgentNavigationGraph.Edge>> outgoing = Map.of(
                STREET, List.of(edge(STREET, LEDGE, AgentNavigationGraph.EdgeType.JUMP,
                        new Point(1600, 500), new Point(1600, 200))),
                ROOF, List.of(edge(ROOF, STREET, AgentNavigationGraph.EdgeType.DROP,
                        new Point(250, 200), new Point(250, 500))),
                LEDGE, List.of(edge(LEDGE, STREET, AgentNavigationGraph.EdgeType.DROP,
                        new Point(1650, 200), new Point(1650, 500))),
                UPPER_ROOF, List.of(
                        edge(UPPER_ROOF, ROOF, AgentNavigationGraph.EdgeType.DROP,
                                new Point(800, 100), new Point(390, 200)),
                        edge(UPPER_ROOF, STREET, AgentNavigationGraph.EdgeType.DROP,
                                new Point(950, 100), new Point(950, 500))));
        return new AgentNavigationGraph(1, 1, AgentMovementProfile.base(), regions, regionsById,
                regionIdByFootholdId, outgoing, Set.of());
    }

    private static MapleMap map(List<Portal> portals, List<SpawnPoint> spawns) {
        FootholdTree footholds = new FootholdTree(new Point(-3000, -3000), new Point(3000, 3000));
        for (AgentNavigationGraph.Region region : graph().regions) {
            AgentNavigationGraph.Segment segment = region.segments.getFirst();
            footholds.insert(new Foothold(
                    new Point(segment.x1, segment.y1), new Point(segment.x2, segment.y2), segment.footholdId));
        }
        MapleMap map = mock(MapleMap.class);
        when(map.getFootholds()).thenReturn(footholds);
        when(map.getPortals()).thenReturn(portals);
        when(map.getMonsterSpawn()).thenReturn(spawns);
        return map;
    }

    private static Portal spawnPortal(int x, int y) {
        Portal portal = mock(Portal.class);
        when(portal.getPosition()).thenReturn(new Point(x, y));
        when(portal.getType()).thenReturn(0);
        when(portal.getTargetMapId()).thenReturn(MapId.NONE);
        return portal;
    }

    private static SpawnPoint spawn(int x, int y) {
        SpawnPoint spawn = mock(SpawnPoint.class);
        when(spawn.getPosition()).thenReturn(new Point(x, y));
        return spawn;
    }

    private static AgentNavigationGraph.Region ground(int id, int x1, int x2, int y) {
        return new AgentNavigationGraph.Region(id, List.of(new AgentNavigationGraph.Segment(
                new Foothold(new Point(x1, y), new Point(x2, y), id))));
    }

    private static AgentNavigationGraph.Edge edge(int from,
                                                  int to,
                                                  AgentNavigationGraph.EdgeType type,
                                                  Point start,
                                                  Point end) {
        return new AgentNavigationGraph.Edge(from, to, type, start, end, 0, 0, 0, 0, 0, 10);
    }
}

package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-14: Horntail's focus ledges on the real maps, on the offline navigation graph a base-speed Agent uses. Every
 * ledge the shipped focus steps name lies on its foothold, has a searched path up from the floor and back down to it,
 * and puts each part it names in reach of a 150 px forward swing (Brandish's reach): the user saw members hit the heads
 * from the floor when the top platform put them in reach.
 */
class AgentBossHorntailAnchorReachTest {
    private static final int LEFT_HEAD_MAP = 240_060_000;
    private static final int RIGHT_HEAD_MAP = 240_060_100;
    private static final int ALTAR = 240_060_200;
    /** Where the event spawns each part (on the ground below): the pre-heads in HorntailBattle.js, the body by its reactor. */
    private static final Map<Integer, Point> SPAWN = Map.ofEntries(
            Map.entry(8_810_000, new Point(960, 120)),
            Map.entry(8_810_001, new Point(-420, 120)),
            Map.entry(8_810_002, new Point(71, 260)), Map.entry(8_810_003, new Point(71, 260)),
            Map.entry(8_810_004, new Point(71, 260)), Map.entry(8_810_005, new Point(71, 260)),
            Map.entry(8_810_006, new Point(71, 260)), Map.entry(8_810_007, new Point(71, 260)));
    /** The part each step's ledges are for, by map. */
    private static final Map<Integer, Integer> MAP_OF_PART = Map.ofEntries(
            Map.entry(8_810_000, LEFT_HEAD_MAP), Map.entry(8_810_001, RIGHT_HEAD_MAP),
            Map.entry(8_810_002, ALTAR), Map.entry(8_810_003, ALTAR), Map.entry(8_810_004, ALTAR),
            Map.entry(8_810_005, ALTAR), Map.entry(8_810_006, ALTAR), Map.entry(8_810_007, ALTAR));
    /** Floor spots members walk up from: on the altar, left of the Legs' touch area (x -217 and further left). */
    private static final Map<Integer, List<Point>> FLOOR = Map.of(
            LEFT_HEAD_MAP, List.of(new Point(450, 260), new Point(900, 260)),
            RIGHT_HEAD_MAP, List.of(new Point(-400, 260), new Point(0, 260)),
            ALTAR, List.of(new Point(-217, 260), new Point(-400, 260), new Point(-560, 260)));
    private static final int SWING_PX = 150;
    private static final int SWING_UP_PX = 110;

    @Test
    void everyFocusLedgeLiesOnAFootholdWithAPathUpFromTheFloorAndBackDown() {
        List<AgentBossRules.Anchor> anchors = anchors();
        assertEquals(9, anchors.size(),
                "two ledges for each pre-head, two for the wings and hands, three for the heads");
        for (AgentBossRules.Anchor anchor : anchors) {
            int mapId = MAP_OF_PART.get(anchor.mobIds().get(0));
            MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
            AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
            Point ledge = new Point(anchor.x(), anchor.y());
            assertEquals(ledge, ground(map, ledge), "ledge on its foothold: " + ledge + " on " + mapId);
            int ledgeRegion = region(graph, map, ledge);
            assertTrue(ledgeRegion >= 0, "ledge on a region: " + ledge);
            for (Point spot : FLOOR.get(mapId)) {
                assertEquals(spot, ground(map, spot), "floor spot on the floor: " + spot);
                int spotRegion = region(graph, map, spot);
                assertFalse(path(graph, map, spot, spotRegion, ledge, ledgeRegion).isEmpty(),
                        "path up " + spot + " -> " + ledge + " on " + mapId);
                assertFalse(path(graph, map, ledge, ledgeRegion, spot, spotRegion).isEmpty(),
                        "path down " + ledge + " -> " + spot + " on " + mapId);
            }
        }
    }

    @Test
    void everyFocusLedgePutsItsPartsInReachOfAForwardSwing() {
        for (AgentBossRules.Anchor anchor : anchors()) {
            for (int mobId : anchor.mobIds()) {
                MapleMap map = AgentNavigationMapLoader.loadMapGeometry(MAP_OF_PART.get(mobId));
                Point origin = ground(map, SPAWN.get(mobId));
                Rectangle body = AgentMobHitboxProvider.getInstance().getMobBounds(mobId, origin, false);
                boolean faceRight = body.getCenterX() >= anchor.x();
                Rectangle swing = faceRight
                        ? new Rectangle(anchor.x(), anchor.y() - SWING_UP_PX, SWING_PX, SWING_UP_PX + 1)
                        : new Rectangle(anchor.x() - SWING_PX, anchor.y() - SWING_UP_PX, SWING_PX, SWING_UP_PX + 1);
                assertTrue(swing.intersects(body), "ledge (" + anchor.x() + ", " + anchor.y() + ") reaches "
                        + mobId + " at " + body);
            }
        }
    }

    private static List<AgentBossRules.Anchor> anchors() {
        List<AgentBossRules.Anchor> anchors = new ArrayList<>();
        for (AgentBossRules.Focus step : AgentBossRules.of("horntail").focus()) {
            anchors.addAll(step.anchors());
        }
        return anchors;
    }

    private static List<AgentNavigationGraph.Edge> path(AgentNavigationGraph graph, MapleMap map,
                                                        Point from, int fromRegion, Point to, int toRegion) {
        List<AgentNavigationGraph.Edge> path = AgentNavigationPathService.findPath(
                graph, map, from, fromRegion, toRegion, to);
        return path == null ? List.of() : path;
    }

    private static Point ground(MapleMap map, Point point) {
        return CosmicPrimitiveCapabilityGateway.INSTANCE.groundPoint(map, point);
    }

    /** The region under a point, looked up from just above it, as the gateway's canReach does. */
    private static int region(AgentNavigationGraph graph, MapleMap map, Point point) {
        return graph.findRegionId(map, new Point(point.x, point.y - 1));
    }
}

package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapleMap;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1's platform clusters (L1), ranged firing spots (L4) and fight-before-loot rule (L6). Live (2026-10-01) the
 * three companions spent about 15 s of overhead per Ligator kill against 6-13 s of attacking, most of it on ropes and
 * in the air between 22 spawns spread over 13 platforms of one to three.
 */
class AgentKpqStageOneClusterPolicyTest {
    private static final int LINK_X = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_LINK_X_PX");
    private static final int LINK_Y = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_LINK_Y_PX");
    private static final int MIN_SPAWNS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_MIN_SPAWNS");
    private static final int STANDOFF = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_RANGED_SPAWN_STANDOFF_PX");
    private static final int SPOT_MIN_SPAWNS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_RANGED_SPOT_MIN_SPAWNS");
    private static final Point CLOTO = new Point(592, 106);

    private static MapleMap stageOne;
    private static AgentNavigationGraph graph;
    private static List<Point> spawns;

    @BeforeAll
    static void loadStageOne() throws Exception {
        stageOne = AgentNavigationMapLoader.loadMapGeometry(AgentKpqDefinition.STAGE_1_MAP);
        graph = AgentNavigationGraphService.getGraph(stageOne, AgentMovementProfile.base());
        spawns = ligatorSpawns(stageOne);
    }

    @Test
    void theRealStageOneSpawnsSplitIntoFiveClustersOfThreeToSix() {
        assertEquals(22, spawns.size());

        List<List<Point>> clusters = AgentKpqStageOneClusterPolicy.clusters(spawns, LINK_X, LINK_Y, MIN_SPAWNS);

        assertEquals(List.of(5, 5, 3, 6, 3), clusters.stream().map(List::size).toList());
        Set<Point> covered = new HashSet<>();
        clusters.forEach(cluster -> cluster.forEach(spawn -> assertTrue(covered.add(spawn), "in two clusters")));
        assertEquals(new HashSet<>(spawns), covered);
        // The low one beside Cloto: the two short platforms over his floor and the one east of them.
        assertEquals(List.of(-329, -233, 123), clusters.get(2).stream().map(spawn -> spawn.x).toList());
    }

    @Test
    void aPlatformShortOfSpawnsJoinsItsNearestNeighbourClosestPairFirst() {
        List<Point> layout = List.of(new Point(0, 0), new Point(50, 0), new Point(100, 0),
                new Point(400, -300), new Point(1500, 0), new Point(1550, 0), new Point(1600, 0));

        List<List<Point>> clusters = AgentKpqStageOneClusterPolicy.clusters(layout, 150, 60, 3);

        // The lone spawn is 300 px walked and 300 climbed from the west platform, 1,400 from the east one.
        assertEquals(List.of(List.of(new Point(0, 0), new Point(50, 0), new Point(100, 0), new Point(400, -300)),
                List.of(new Point(1500, 0), new Point(1550, 0), new Point(1600, 0))), clusters);
    }

    @Test
    void theSmallestTargetCampsByClotoAndTheHardestHitterTheRichestCluster() {
        List<List<Point>> clusters = AgentKpqStageOneClusterPolicy.clusters(spawns, LINK_X, LINK_Y, MIN_SPAWNS);
        var weakBowman = new AgentKpqStageOneClusterPolicy.Collector(2, 35, 41.0);
        var warrior = new AgentKpqStageOneClusterPolicy.Collector(3, 20, 95.0);
        var thief = new AgentKpqStageOneClusterPolicy.Collector(4, 10, 60.0);

        Map<Integer, Integer> clusterOf = AgentKpqStageOneClusterPolicy.assign(
                clusters, List.of(weakBowman, warrior, thief), CLOTO);

        // The thief's ten coupons: the low cluster by Cloto. The warrior: the six on the middle platforms. The
        // bowman: of the two fives, the one nearer Cloto (the west-low one with the floor-level platform).
        assertEquals(Map.of(4, 2, 3, 3, 2, 0), clusterOf);
    }

    @Test
    void moreCollectorsThanClustersShareTheRichestAgain() {
        List<List<Point>> clusters = List.of(List.of(new Point(0, 0), new Point(50, 0)),
                List.of(new Point(900, 0), new Point(950, 0), new Point(1000, 0)));
        List<AgentKpqStageOneClusterPolicy.Collector> collectors = List.of(
                new AgentKpqStageOneClusterPolicy.Collector(1, 10, 10.0),
                new AgentKpqStageOneClusterPolicy.Collector(2, 20, 30.0),
                new AgentKpqStageOneClusterPolicy.Collector(3, 20, 20.0));

        Map<Integer, Integer> clusterOf = AgentKpqStageOneClusterPolicy.assign(clusters, collectors, new Point(0, 0));

        assertEquals(Map.of(1, 0, 2, 1, 3, 1), clusterOf);
        assertEquals(Map.of(), AgentKpqStageOneClusterPolicy.assign(List.of(), collectors, new Point(0, 0)));
    }

    @Test
    void aFiringSpotStandsTheStandoffFromEverySpawnOnTheirOwnPlatform() {
        // One platform from x=350 to x=650 at y=-1000, the floor at y=100 below it.
        UnaryOperator<Point> ground = point -> point.x >= 350 && point.x <= 650 && point.y <= -1000
                ? new Point(point.x, -1000) : new Point(point.x, 100);
        ToIntFunction<Point> platform = point -> point.y == -1000 ? 30 : 1;
        List<Point> area = List.of(new Point(400, -1000), new Point(450, -1000), new Point(500, -1000));

        assertEquals(new Point(600, -1000),
                AgentKpqStageOneClusterPolicy.firingSpot(area, 100, 2, ground, platform));
        // No spot on a platform too short for the standoff: off its end lies the floor.
        assertNull(AgentKpqStageOneClusterPolicy.firingSpot(area, 160, 2, ground, platform));
        // Nor while the platforms are unknown.
        assertNull(AgentKpqStageOneClusterPolicy.firingSpot(area, 100, 2, ground, point -> -1));
    }

    @Test
    void aFiringSpotIsNeverOnAPlatformFedByTooFewSpawns() {
        // Two spawns crowd the low platform (x 0 to 200); the high one (x 350 to 650) holds a single spawn.
        UnaryOperator<Point> ground = point -> point.x >= 350 && point.x <= 650 && point.y <= -1000
                ? new Point(point.x, -1000) : new Point(point.x, 100);
        ToIntFunction<Point> platform = point -> point.y == -1000 ? 30 : point.x >= 0 && point.x <= 200 ? 1 : 2;
        List<Point> area = List.of(new Point(60, 100), new Point(140, 100), new Point(500, -1000));

        // Anchored by the lone spawn, it would wait out that one respawn after every kill.
        assertNull(AgentKpqStageOneClusterPolicy.firingSpot(area, 100, 2, ground, platform));
        assertEquals(new Point(400, -1000), AgentKpqStageOneClusterPolicy.firingSpot(area, 100, 1, ground, platform));
    }

    @Test
    void theRealClustersFiringSpotsKeepTheStandoffOnASpawnsOwnPlatform() {
        UnaryOperator<Point> ground = point -> AgentGroundingService.findGroundPoint(
                stageOne, new Point(point.x, point.y - 1));
        ToIntFunction<Point> platform = point -> graph.findRegionId(stageOne, new Point(point.x, point.y - 1));
        List<List<Point>> clusters = AgentKpqStageOneClusterPolicy.clusters(spawns, LINK_X, LINK_Y, MIN_SPAWNS);
        int withSpot = 0;

        for (List<Point> cluster : clusters) {
            Point spot = AgentKpqStageOneClusterPolicy.firingSpot(cluster, STANDOFF, SPOT_MIN_SPAWNS, ground,
                    platform);
            if (spot == null) continue;
            withSpot++;
            for (Point spawn : cluster) {
                assertTrue(spawn.distance(spot) >= STANDOFF, () -> spot + " stands too near " + spawn);
            }
            int spotPlatform = platform.applyAsInt(spot);
            long fedBy = cluster.stream().filter(spawn -> platform.applyAsInt(spawn) == spotPlatform).count();
            // Live geometry at the shipped values: never a platform a single spawn feeds.
            assertTrue(spotPlatform >= 0 && fedBy >= Math.max(2, SPOT_MIN_SPAWNS),
                    () -> spot + " stands on a platform of " + cluster + " fed by too few spawns");
            assertEquals(spot, ground.apply(spot), () -> spot + " is not on a foothold");
        }
        assertTrue(withSpot >= 3, "a ranged companion finds a firing spot in most clusters, got " + withSpot);
    }

    @Test
    void aFightingCollectorTakesOnlyANearOldOrFinishingCoupon() {
        // A clear platform: any coupon, as before.
        assertTrue(AgentKpqStageOneClusterPolicy.lootNow(false, 900.0, 0L, false, 150, 120_000L));
        // A Ligator on its platform: not a far, fresh coupon.
        assertFalse(AgentKpqStageOneClusterPolicy.lootNow(true, 900.0, 5_000L, false, 150, 120_000L));
        assertTrue(AgentKpqStageOneClusterPolicy.lootNow(true, 150.0, 5_000L, false, 150, 120_000L));
        assertTrue(AgentKpqStageOneClusterPolicy.lootNow(true, 900.0, 120_000L, false, 150, 120_000L));
        assertTrue(AgentKpqStageOneClusterPolicy.lootNow(true, 900.0, 5_000L, true, 150, 120_000L));
    }

    /** The map's Ligator spawn points where the server puts them: one pixel over the foothold under each life. */
    private static List<Point> ligatorSpawns(MapleMap map) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("wz", "Map.wz", "Map", "Map1", "103000800.img.xml").toFile()).getDocumentElement();
        List<Point> found = new ArrayList<>();
        for (Element life : children(child(root, "life"))) {
            Map<String, String> values = values(life);
            if (!"m".equals(values.get("type")) || !"9300001".equals(values.get("id"))) continue;
            Point below = AgentGroundingService.findGroundPoint(map, new Point(Integer.parseInt(values.get("x")),
                    Integer.parseInt(values.get("cy")) - 1));
            assertNotNull(below);
            found.add(new Point(below.x, below.y - 1));
        }
        return found;
    }

    private static Element child(Element parent, String name) {
        return children(parent).stream().filter(element -> name.equals(element.getAttribute("name"))).findFirst()
                .orElseThrow();
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) elements.add(element);
        }
        return elements;
    }

    private static Map<String, String> values(Element life) {
        Map<String, String> values = new java.util.HashMap<>();
        children(life).forEach(element -> values.put(element.getAttribute("name"), element.getAttribute("value")));
        return values;
    }
}

package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementPhysicsStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentJumpProbeService;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.capabilities.navigation.AgentPortalApproachService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOpqNavigationGraphTest {
    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @Test
    void loungeSpawnCanReachUpperPuzzleRowWithGearedJump() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOUNGE_MAP);
        Point portal = AgentPortalApproachService.navigableTarget(map, map.getPortal(11));
        assertRoute(AgentOpqDefinition.LOUNGE_MAP, new Point(-45, -728), portal,
                new AgentMovementProfile(100, 123));
    }

    @Test
    void loungeHighSubroomsTakeTheClimbersJumpAndTheLowOneABaseJump() {
        // 2026-10-20 live: a Fighter (jump 100) stood at the lounge's door (st00) for 43 minutes. The ledges up to in00
        // and the r4 portal row are 84-96 px apart and a base jump rises 77 px: reachable at LOUNGE_JUMP, none at 110.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOUNGE_MAP);
        Point door = new Point(-45, -728);
        List<Point> high = List.of(3, 11, 12, 13).stream()
                .map(id -> AgentPortalApproachService.navigableTarget(map, map.getPortal(id))).toList();
        AgentNavigationGraph climber = AgentNavigationGraphService.rebuildGraph(map,
                new AgentMovementProfile(100, AgentOpqCoordinator.LOUNGE_JUMP));
        AgentNavigationGraph below = AgentNavigationGraphService.rebuildGraph(map, new AgentMovementProfile(100, 110));
        for (Point target : high) {
            assertTrue(reaches(climber, map, door, target), "jump " + AgentOpqCoordinator.LOUNGE_JUMP + " to " + target);
            assertFalse(reaches(below, map, door, target), "jump 110 to " + target);
        }
        Point low = AgentPortalApproachService.navigableTarget(map, map.getPortal(6));
        assertTrue(reaches(AgentNavigationGraphService.rebuildGraph(map, AgentMovementProfile.base()), map, door, low));
    }

    @Test
    void lobbyRunnerClimbsFromTheFloorToTheUpperPlatform() {
        // 2026-10-20 live (Marrow, two whole runs): each jump off the x -1598 rope landed 11 px short of the ledge it
        // ends on at x -1664. The rope's traversal settled as she left it, the next resolve planned that jump-off again
        // from beside the rope, and air steering toward its start pulled her back
        // (AgentNavigationClimbExitExecutionService). The runner follows the coordinator's targets as it does live.
        for (int speed : new int[] {100, 103}) {
            for (int phase = 0; phase < 2; phase++) {
                Point end = followCoordinatorTargets(AgentOpqDefinition.LOBBY_MAP, new Point(-1_633, -127), speed,
                        phase, AgentOpqCoordinator::lobbyAscentTarget, position -> position.y <= -850);
                assertTrue(end.y <= -850, "speed " + speed + " phase " + phase + " ended at " + end);
            }
        }
    }

    @Test
    void wayUpRunnerCatchesTheLongRopeFromItsPlatform() {
        // 2026-10-20 live (Kettle, two whole runs): the authored jump from x -117 had no climbing intent and passed the
        // long rope (x -43) below its bottom; the graph's edge onto it launches from x -108.
        for (int speed : new int[] {100, 102}) {
            for (int phase = 0; phase < 2; phase++) {
                AgentOpqMemberState runner = new AgentOpqMemberState(1, AgentOpqMemberState.MemberType.AGENT);
                Point end = followCoordinatorTargets(AgentOpqDefinition.ON_WAY_UP_MAP, new Point(-150, -846), speed,
                        phase, position -> AgentOpqCoordinator.wayUpAscentTarget(position, runner),
                        position -> position.y <= -1_600);
                assertTrue(end.y <= -1_600, "speed " + speed + " phase " + phase + " ended at " + end);
            }
        }
    }

    @Test
    void wayUpRunnerStandingBesideTheLaunchCatchesTheLongRopeOnItsFirstJump() {
        // 2026-10-20: a runner stopped at x -112 kept its continuous X 0.43 px off the pixel (reported as vx -9 or +9).
        // From a stop the precise approach to the launch (-108, window -109..-107) overran the platform's end at -106 and
        // fell to the row below unless that offset happened to end its first substep 1 px out: 0/40 first laps offline.
        for (int speed : new int[] {100, 104}) {
            for (double offset : new double[] {-0.432, 0.0, 0.431}) {
                for (int phase = 0; phase < 2; phase++) {
                    List<Point> path = new ArrayList<>();
                    Point end = followCoordinatorTargets(AgentOpqDefinition.ON_WAY_UP_MAP, new Point(-112, -846), speed,
                            phase, entry -> AgentMovementPhysicsStateRuntime.setPhysicsX(entry, -112 + offset),
                            position -> {
                                path.add(position);
                                return new Point(-43, -1_623);
                            },
                            position -> position.y <= -1_600);
                    String run = "speed " + speed + " offset " + offset + " phase " + phase;
                    assertTrue(end.y <= -1_600, run + " ended at " + end);
                    assertTrue(path.stream().allMatch(position -> position.y <= -820), run + " fell off the platform");
                }
            }
        }
    }

    @Test
    void wayUpRunnerLeavesTheLaunchStagingOnlyFromRest() {
        // 2026-10-20: the route to the long rope was planned while the runner still slid into its staging spot and
        // overran the launch off the platform's end; offline, live code got up only when an AI tick fell after it stopped.
        AgentOpqMemberState runner = new AgentOpqMemberState(1, AgentOpqMemberState.MemberType.AGENT);
        Point staging = new Point(-114, -846);
        Point rope = new Point(-43, -1_623);
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-150, -846), runner));
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-119, -846), runner));
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-115, -846), runner));
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-115, -846), runner), "still one tick");
        assertEquals(rope, AgentOpqCoordinator.wayUpAscentTarget(new Point(-115, -846), runner));
        assertEquals(rope, AgentOpqCoordinator.wayUpAscentTarget(new Point(-109, -846), runner), "walking to the launch");
        assertEquals(rope, AgentOpqCoordinator.wayUpAscentTarget(new Point(-80, -872), runner), "in flight");
        assertEquals(new Point(-173, -844), AgentOpqCoordinator.wayUpAscentTarget(new Point(-120, -700), runner));
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-115, -846), runner), "a new lap waits");
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-116, -846), runner), "moved");
        assertEquals(staging, AgentOpqCoordinator.wayUpAscentTarget(new Point(-116, -846), runner));
        assertEquals(rope, AgentOpqCoordinator.wayUpAscentTarget(new Point(-116, -846), runner));
    }

    @Test
    void wayUpInitialRopesConnectSpawnToFirstPuzzleRow() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.ON_WAY_UP_MAP);
        Rope longRope = map.getRopes().stream()
                .filter(rope -> rope.x() == -43 && rope.topY() == -1_623)
                .findFirst().orElseThrow();
        assertTrue(AgentJumpProbeService.canReachRopeFromGround(
                map, new Point(-112, -846), longRope, AgentMovementProfile.base()));
        assertRopeRoute(map, longRope, new Point(-43, -878), new Point(-43, -1_625));
    }

    @Test
    void lobbyFirstRopeLandingConnectsToSecondRope() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOBBY_MAP);
        Rope firstRope = map.getRopes().stream()
                .filter(rope -> rope.x() == -1_598)
                .findFirst().orElseThrow();
        assertRopeRoute(map, firstRope,
                new Point(-1_598, -556), new Point(-1_777, -528));
    }

    @Test
    void lobbyAscentTargetsTheNextRegionInsteadOfTheCurrentRopeHead() {
        assertEquals(new Point(-1_598, -408), AgentOpqCoordinator.lobbyAscentTarget(
                new Point(-1_623, -127)));
        assertEquals(new Point(-1_777, -528), AgentOpqCoordinator.lobbyAscentTarget(
                new Point(-1_598, -438)));
        assertEquals(new Point(-1_777, -528), AgentOpqCoordinator.lobbyAscentTarget(
                new Point(-1_702, -511)));
        assertEquals(new Point(-1_717, -926), AgentOpqCoordinator.lobbyAscentTarget(
                new Point(-1_777, -616)));
        assertEquals(null, AgentOpqCoordinator.lobbyAscentTarget(
                new Point(-1_717, -900)));
    }

    @Test
    void entranceCloudsAreDeterministicallyDistributedAcrossAllSixAgents() {
        List<Point> authoredClouds = List.of(
                new Point(-799, -260), new Point(-369, -258), new Point(-4, -917),
                new Point(-268, -903), new Point(334, -762), new Point(452, -453),
                new Point(194, -300), new Point(1388, -887), new Point(1068, -929),
                new Point(744, -595), new Point(1082, -680), new Point(1450, -399),
                new Point(921, -242), new Point(201, -443), new Point(728, 83),
                new Point(-504, -540), new Point(-54, -554), new Point(681, -931),
                new Point(1132, -223));

        Set<Integer> owners = authoredClouds.stream()
                .map(point -> AgentOpqCoordinator.entranceCloudOwner(point, 6))
                .collect(Collectors.toSet());

        assertEquals(Set.of(0, 1, 2, 3, 4, 5), owners);
        assertEquals(3, AgentOpqCoordinator.entranceCloudOwner(new Point(-799, -260), 6));
    }

    @Test
    void entranceCenterCloudStrikePlatformHasALegalRouteFromLowerLeft() {
        assertRoute(AgentOpqDefinition.ENTRANCE_MAP,
                new Point(-307, -201), new Point(-54, -498));
    }

    private static boolean reaches(AgentNavigationGraph graph, MapleMap map, Point from, Point to) {
        return graph.canReach(graph.findRegionId(map, from), graph.findRegionId(map, to));
    }

    /**
     * Runs an Agent's live movement (background ticks of five substeps, an AI tick every second one) toward the
     * target {@code targetFor} gives for its position, re-issued every tick as the coordinator does; returns where it
     * stands once {@code done} holds or after two minutes.
     */
    private static Point followCoordinatorTargets(int mapId, Point start, int speed, int phase,
                                                  Function<Point, Point> targetFor, Predicate<Point> done) {
        return followCoordinatorTargets(mapId, start, speed, phase, entry -> { }, targetFor, done);
    }

    private static Point followCoordinatorTargets(int mapId, Point start, int speed, int phase,
                                                  Consumer<AgentRuntimeEntry> prepare,
                                                  Function<Point, Point> targetFor, Predicate<Point> done) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        AgentMovementProfile profile = new AgentMovementProfile(speed, 100);
        // The tick only asks for the graph in the background; live, a room's graph is warm before its runner arrives.
        // Without this the loop raced that build; in a full suite run the build never landed and the Agent never moved.
        AgentNavigationGraphService.getGraph(map, profile);
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getId()).thenReturn(1);
        when(agent.getHp()).thenReturn(100);
        when(agent.getMaxHp()).thenReturn(100);
        when(agent.isAlive()).thenReturn(true);
        when(agent.getTotalMoveSpeedStat()).thenReturn(speed);
        when(agent.getTotalJumpStat()).thenReturn(100);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, profile);
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        prepare.accept(entry);
        for (int tick = 0; tick < 480 && !done.test(position.get()); tick++) {
            AgentModeService.startMoveTo(entry, targetFor.apply(position.get()), true);
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(entry, agent, (tick + phase) % 2 == 0);
        }
        return position.get();
    }

    private static void assertRoute(int mapId, Point start, Point target) {
        assertRoute(mapId, start, target, AgentMovementProfile.base());
    }

    private static void assertRoute(int mapId, Point start, Point target, AgentMovementProfile profile) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        AgentNavigationGraph graph = AgentNavigationGraphService.rebuildGraph(map, profile);
        int startRegion = graph.findRegionId(map, start);
        int targetRegion = graph.findRegionId(map, target);
        assertTrue(startRegion > 0, "Missing start region for " + start);
        assertTrue(targetRegion > 0, "Missing target region for " + target);
        List<AgentNavigationGraph.Edge> path = AgentNavigationPathService.findPath(
                graph, map, start, startRegion, targetRegion, target);
        assertFalse(path.isEmpty(), () -> "No legal route on map " + mapId + " from r" + startRegion
                + " " + start + " to r" + targetRegion + " " + target
                + "; start=" + describeRegion(graph.getRegion(startRegion))
                + "; target=" + describeRegion(graph.getRegion(targetRegion))
                + "; outgoing=" + graph.getOutgoing(startRegion).stream()
                        .map(AgentOpqNavigationGraphTest::describeEdge).collect(Collectors.joining(", "))
                + "; targetOutgoing=" + graph.getOutgoing(targetRegion).stream()
                        .map(AgentOpqNavigationGraphTest::describeEdge).collect(Collectors.joining(", ")));
    }

    private static void assertRopeRoute(MapleMap map, Rope rope, Point start, Point target) {
        AgentNavigationGraph graph = AgentNavigationGraphService.rebuildGraph(map, AgentMovementProfile.base());
        int startRegion = graph.regions.stream()
                .filter(region -> region.isRopeRegion && region.minX == rope.x()
                        && region.minY == rope.topY() && region.maxY == rope.bottomY())
                .mapToInt(region -> region.id).findFirst().orElse(-1);
        int targetRegion = graph.findRegionId(map, target);
        assertTrue(startRegion > 0, "Missing rope region for " + rope);
        assertTrue(targetRegion > 0, "Missing target region for " + target);
        List<AgentNavigationGraph.Edge> path = AgentNavigationPathService.findPath(
                graph, map, start, startRegion, targetRegion, target);
        assertFalse(path.isEmpty(), "Attached rope has no legal route to its top platform");
    }

    private static String describeRegion(AgentNavigationGraph.Region region) {
        return "r" + region.id + "[" + region.minX + ".." + region.maxX + ","
                + region.minY + ".." + region.maxY + ",rope=" + region.isRopeRegion + "]";
    }

    private static String describeEdge(AgentNavigationGraph.Edge edge) {
        return edge.type + "(r" + edge.fromRegionId + "->r" + edge.toRegionId + ","
                + edge.startPoint + "->" + edge.endPoint + ",rope=" + edge.ropeX + "/"
                + edge.ropeTopY + ".." + edge.ropeBottomY + ")";
    }
}

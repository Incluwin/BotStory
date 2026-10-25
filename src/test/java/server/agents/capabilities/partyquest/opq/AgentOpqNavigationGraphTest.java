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
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.ReactorFactory;
import server.maps.ReactorStats;
import server.maps.Rope;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void everyLoungeSubroomRunnerFindsItsWayBackToTheLounge() {
        // 2026-10-25 live (runs 4 and 5): the low subroom has no portal 9, its out00 is portal 1. Looking for 9 there,
        // Sadie stood at (-857,-385) with her ten pieces until the clock ran out, both runs.
        for (int mapId : AgentOpqDefinition.LOUNGE_ROOM_MAPS) {
            server.maps.Portal out = AgentNavigationMapLoader.loadMapGeometry(mapId)
                    .getPortal(AgentOpqDefinition.LOUNGE_SUBROOM_EXIT_PORTALS.get(mapId));
            assertNotNull(out, "subroom " + mapId);
            assertEquals("out00", out.getName(), "subroom " + mapId);
            assertEquals(AgentOpqDefinition.LOUNGE_MAP, out.getTargetMapId(), "subroom " + mapId);
        }
        int low = AgentOpqCoordinator.LOUNGE_LOW_SUBROOM;
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(low);
        server.maps.Portal out = map.getPortal(AgentOpqDefinition.LOUNGE_SUBROOM_EXIT_PORTALS.get(low));
        Point target = AgentPortalApproachService.navigableTarget(map, out);
        for (int phase = 0; phase < 2; phase++) {
            Point end = followCoordinatorTargets(low, new Point(-857, -385), 100, phase, position -> target,
                    position -> AgentOpqInteractionPolicy.mayEnterPortal(position, out.getPosition()));
            assertTrue(AgentOpqInteractionPolicy.mayEnterPortal(end, out.getPosition()), "phase " + phase + " ended at " + end);
        }
    }

    @Test
    void lobbyRunnerReachesTheBoxOfEveryDayItsMovementReaches() {
        // Every LP box sits in a walled cell of its own (Map.wz 920010400) and the runner goes for the day's box by the
        // graph. 2026-10-24 live (run 4, a Sunday on the server): the old two-rope ascent led only into Monday's cell,
        // and Sunday's cell takes a climber (its last ledge is 100 px up), as Tuesday's does (a rope a base run-up does
        // not reach). A climber is a companion with Haste 19 on: speed and jump 100 + 38 and + 19. 2026-10-20 live: the
        // first rope's jump-off (x -1598 to the ledge ending at x -1664) landed 11 px short until rope jumps flew a
        // fixed arc.
        int[] boxX = {-838, -1_717, -3_055, -357, -2_615, -1_285, -2_176}; // Sunday..Saturday: 2002004-2002010
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOBBY_MAP);
        Point floor = new Point(-1_633, -127);
        for (int dayOfWeek = Calendar.SUNDAY; dayOfWeek <= Calendar.SATURDAY; dayOfWeek++) {
            Point box = new Point(boxX[dayOfWeek - 1], -907);
            assertEquals(dayOfWeek == Calendar.WEDNESDAY, AgentOpqCoordinator.lobbyBoxOutOfReach(dayOfWeek));
            if (AgentOpqCoordinator.lobbyBoxOutOfReach(dayOfWeek)) {
                // Out of reach at any speed and jump; the user kept the original puzzle (2026-10-25), so the runner
                // waits by the music player for a player's LP (AgentOpqLobbyBoxesTest).
                AgentNavigationGraph graph = AgentNavigationGraphService.rebuildGraph(map, new AgentMovementProfile(160, 123));
                assertFalse(reaches(graph, map, floor, box), "Wednesday at speed 160 jump 123");
                continue;
            }
            boolean climber = AgentOpqCoordinator.lobbyWantsClimber(dayOfWeek);
            int speed = climber ? 138 : 100;
            int jump = climber ? 119 : 100;
            if (climber) {
                AgentNavigationGraph base = AgentNavigationGraphService.rebuildGraph(map, AgentMovementProfile.base());
                assertFalse(reaches(base, map, floor, box), "day " + dayOfWeek + " without a climber");
            }
            for (int phase = 0; phase < 2; phase++) {
                Point end = followCoordinatorTargets(AgentOpqDefinition.LOBBY_MAP, floor, speed, jump, phase,
                        entry -> { }, position -> box,
                        position -> Math.abs(position.x - box.x) <= 30 && position.y == box.y);
                assertTrue(Math.abs(end.x - box.x) <= 30 && end.y == box.y,
                        "day " + dayOfWeek + " speed " + speed + " jump " + jump + " phase " + phase + " ended at " + end);
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
    void wayUpRowsCountASectionsLandingWithTheRowAboveIt() {
        assertEquals(-1, AgentOpqCoordinator.wayUpRow(-1_865), "r6fail, below the first row");
        assertEquals(0, AgentOpqCoordinator.wayUpRow(-2_024), "atop the rope to the first row");
        assertEquals(0, AgentOpqCoordinator.wayUpRow(-2_093));
        assertEquals(3, AgentOpqCoordinator.wayUpRow(-2_449));
        assertEquals(3, AgentOpqCoordinator.wayUpRow(-2_526), "a jump's top above the fourth row");
        assertEquals(4, AgentOpqCoordinator.wayUpRow(-2_583), "pt00, the landing past the fourth row");
        assertEquals(4, AgentOpqCoordinator.wayUpRow(-2_810), "atop the rope from that landing");
        assertEquals(8, AgentOpqCoordinator.wayUpRow(-3_352), "pt01");
        assertEquals(12, AgentOpqCoordinator.wayUpRow(-4_134), "pt02");
        assertEquals(15, AgentOpqCoordinator.wayUpRow(-4_796));
    }

    @Test
    void wayUpRunnerFromEachSectionsLandingReachesEveryDoorOfItsFirstRow() {
        // 2026-10-24 live (run 4, Kettle, 30 minutes): past the fourth row the runner took its landing for that row, went
        // back for its door, and for the doors right of the rope climbed it, came off its top on a jump that touched
        // down 2 px from the end of that small platform, slid off and fell back to the landing, every 3 seconds.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.ON_WAY_UP_MAP);
        List<Point> landings = List.of(new Point(-107, -2_582), new Point(98, -3_353), new Point(-190, -4_138));
        for (Point landing : landings) {
            int row = AgentOpqCoordinator.wayUpRow(landing.y);
            for (int choice = 0; choice < 4; choice++) {
                Point door = map.getPortal(24 + row * 4 + choice).getPosition();
                for (int phase = 0; phase < 2; phase++) {
                    Point end = followCoordinatorTargets(AgentOpqDefinition.ON_WAY_UP_MAP, landing, 100, phase,
                            position -> {
                                Point stage = AgentOpqCoordinator.wayUpSectionStage(position,
                                        AgentOpqCoordinator.wayUpRow(position.y));
                                return stage != null ? stage : door;
                            }, position -> AgentOpqInteractionPolicy.mayEnterPortal(position, door));
                    assertTrue(AgentOpqInteractionPolicy.mayEnterPortal(end, door),
                            "from " + landing + " door " + door + " phase " + phase + " ended at " + end);
                }
            }
        }
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
    void lobbyMusicPlayerDropSpotIsFloorInsideThePlayersDropBox() {
        // 2026-10-20 live: Marrow carried the LP to the reactor's own point (-1706,-240), 68 px above the floor, and
        // never dropped it. The drop spot must be standable and every drop within the radius must reach the reactor.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.LOBBY_MAP);
        Point drop = AgentOpqCoordinator.MUSIC_PLAYER_DROP;
        Foothold floor = map.getFootholds().findBelow(new Point(drop.x, drop.y - 1));
        assertNotNull(floor);
        assertEquals(drop.y, floor.calculateFooting(drop.x));
        ReactorStats musicPlayer = ReactorFactory.getReactor(2_008_006);
        Point reactor = new Point(-1_706, -240); // Map.wz 920010400 reactor "music"
        Rectangle box = new Rectangle(reactor.x + musicPlayer.getTL().x, reactor.y + musicPlayer.getTL().y,
                musicPlayer.getBR().x - musicPlayer.getTL().x, musicPlayer.getBR().y - musicPlayer.getTL().y);
        int radius = AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX;
        for (int x = drop.x - radius; x <= drop.x + radius; x++) {
            assertTrue(box.contains(new Point(x, drop.y)), "drop at x " + x + " outside " + box);
        }
    }

    @Test
    void everyStatueScarDropSpotIsFloorInsideItsScarsDropBox() {
        // 2026-10-24 live (run 4): Jinx carried the storage piece to scar6's own point, 40 px above the floor she stood
        // on, and never dropped it; scar2, 3 and 4 sit 77-88, 88 and 41 px above theirs. A piece dropped from anywhere
        // a carrier may stand within the drop radius of the spot must land in the scar's box.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(AgentOpqDefinition.CENTER_MAP);
        Map<String, Integer> reactorIds = Map.of("scar1", 2_008_000, "scar2", 2_008_001, "scar3", 2_008_002,
                "scar4", 2_008_003, "scar5", 2_008_004, "scar6", 2_008_005);
        int radius = AgentOpqInteractionPolicy.ITEM_DROP_RADIUS_PX;
        for (String scar : AgentOpqDefinition.STATUE_SCAR_BY_ITEM.values()) {
            Point drop = AgentOpqDefinition.STATUE_SCAR_DROP.get(scar);
            Foothold floor = map.getFootholds().findBelow(new Point(drop.x, drop.y - 1));
            assertNotNull(floor, scar);
            assertEquals(drop.y, floor.calculateFooting(drop.x), scar + " drop spot is not on its floor");
            ReactorStats stats = ReactorFactory.getReactor(reactorIds.get(scar));
            Point reactor = AgentOpqDefinition.STATUE_SCAR_POSITION.get(scar);
            Rectangle box = new Rectangle(reactor.x + stats.getTL().x, reactor.y + stats.getTL().y,
                    stats.getBR().x - stats.getTL().x, stats.getBR().y - stats.getTL().y);
            for (int x = drop.x - radius; x <= drop.x + radius; x++) {
                Foothold below = map.getFootholds().findBelow(new Point(x, drop.y - 1));
                if (below == null || below.calculateFooting(x) != drop.y) continue; // nowhere to stand there
                assertTrue(box.contains(new Point(x, drop.y)), scar + " drop at x " + x + " outside " + box);
            }
        }
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
        return followCoordinatorTargets(mapId, start, speed, 100, phase, prepare, targetFor, done);
    }

    private static Point followCoordinatorTargets(int mapId, Point start, int speed, int jump, int phase,
                                                  Consumer<AgentRuntimeEntry> prepare,
                                                  Function<Point, Point> targetFor, Predicate<Point> done) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        AgentMovementProfile profile = new AgentMovementProfile(speed, jump);
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
        when(agent.getTotalJumpStat()).thenReturn(jump);
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

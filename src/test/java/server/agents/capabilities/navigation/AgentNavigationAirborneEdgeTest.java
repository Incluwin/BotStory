package server.agents.capabilities.navigation;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentFootholdIndexService;
import server.agents.capabilities.movement.AgentMapStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentRopeMovementService;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-geometry walks at background cadence: ticks of 250 ms with five movement substeps, and an
 * AI tick every second tick. While an Agent is in the air its region is -1. Validation rejected
 * the committed jump, drop or rope jump then as "unexpected-source-region", charged a reliability
 * failure and turned on air steering. One steering target held for five substeps lands the arc
 * short, and three such failures suppress the edge for 30 s.
 */
class AgentNavigationAirborneEdgeTest {
    private static final int CLOUD_PARK_I = 200010000;
    private static final int TUNNEL_THAT_LOST_LIGHT_II = 105090100;
    private static final int MU_LUNG = 250000000;
    private static final int ROAD_TO_GARDEN_OF_3_COLORS = 200010100;
    private static final int ARRIVAL_PX = 45;

    @Test
    void cloudParkAgentReachesTop01WithoutFailures() {
        // Orbis Cloud Park I: from top00 a drop, two rope jumps and three jumps lead to top01.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(CLOUD_PARK_I);
        Walk walk = walk(map, map.getPortal("top00").getPosition(), "top01", 100, 120, null);

        assertTrue(walk.reachedAt() >= 0, "stalled at " + walk.position());
        assertEquals(0, walk.maxFailures(), "a landed jump is not a failure");
    }

    @Test
    void tunnelLeftColumnAgentReachesWest00() {
        // The Tunnel That Lost Light II: from the left column (-251, 947) the route climbs by a
        // rope jump from rope x -160 to rope x -73. Live, the Agent dropped off at the first AI
        // tick of that jump and fell to the bottom, until the 300 s supply-trip watchdog.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(TUNNEL_THAT_LOST_LIGHT_II);
        Walk walk = walk(map, new Point(-251, 947), "west00", 100, 240, null);

        assertTrue(walk.reachedAt() >= 0, "stalled at " + walk.position());
    }

    @Test
    void muLungAgentReachesWest00() {
        // Mu Lung, from the shop door in01 through the in-map portals and five jumps to west00.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(MU_LUNG);
        Walk walk = walk(map, map.getPortal("in01").getPosition(), "west00", 100, 320, null);

        assertTrue(walk.reachedAt() >= 0, "stalled at " + walk.position());
    }

    @Test
    void muLungMissedSlopeJumpIsChargedOncePerMiss() {
        // At speed 105 the Agent overshoots the launch of the slope jump at (-3443, -146) and
        // walks off the slope onto the street below. With airborne edges no longer rejected,
        // nothing charged that miss: the next plan began a new attempt, and the Agent repeated
        // the jump about 40 times. Each miss now charges the jump one failure.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(MU_LUNG);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, new AgentMovementProfile(105, 100));
        AgentNavigationGraph.Edge slopeJump = jumpFrom(graph, new Point(-3443, -146));
        List<String> otherCharges = new ArrayList<>();
        boolean[] inFlight = {false};
        int[] misses = {0};
        int[] maxJumpFailures = {0};

        Walk walk = walk(map, map.getPortal("in01").getPosition(), "west00", 105, 320, (entry, position, tick) -> {
            boolean airborne = AgentMovementStateRuntime.inAir(entry);
            if (airborne && AgentNavigationDebugStateRuntime.activeNavigationEdge(entry)
                    instanceof AgentNavigationGraph.Edge active
                    && AgentNavigationCommittedEdgeService.sameEdge(active, slopeJump)) {
                inFlight[0] = true;
            } else if (!airborne && inFlight[0]) {
                inFlight[0] = false;
                if (graph.findRegionId(map, position) != slopeJump.toRegionId) {
                    misses[0]++;
                }
            }
            for (AgentNavigationEdgeReliabilityState.EdgeFailure failure
                    : AgentNavigationEdgeReliabilityRuntime.snapshot(entry, System.currentTimeMillis()).edges()) {
                if (failure.type() == slopeJump.type && failure.toRegionId() == slopeJump.toRegionId
                        && failure.startX() == slopeJump.startPoint.x && failure.startY() == slopeJump.startPoint.y) {
                    maxJumpFailures[0] = Math.max(maxJumpFailures[0], failure.failureCount());
                } else {
                    otherCharges.add("t=" + tick + " " + failure);
                }
            }
        });

        assertTrue(walk.reachedAt() >= 0, "stalled at " + walk.position());
        assertTrue(misses[0] >= 1, "precondition: the slope jump misses at speed 105");
        assertTrue(misses[0] <= 3, misses[0] + " misses");
        assertEquals(misses[0], maxJumpFailures[0], "one failure per miss");
        assertEquals(List.of(), otherCharges, "edges that landed are not charged");
    }

    @Test
    void ropeJumpCatchesItsTargetRopeBesideAnother() {
        // The Road to Garden of 3 Colors: the jump from rope x148 (y -500) to rope x69 passes rope
        // x68 (y -646..-361) above the top of rope x69 (y -305). The Agent caught the first rope
        // within reach, x68, and the jump never reached x69.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(ROAD_TO_GARDEN_OF_3_COLORS);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        Rope source = rope(map, 148);
        Rope target = rope(map, 69);
        Rope beside = rope(map, 68);
        AgentNavigationGraph.Edge transfer = transfer(graph, source, target, -500);
        AgentNavigationGraph.Edge entryEdge = groundEntry(graph, transfer.fromRegionId);

        AtomicReference<Point> position = new AtomicReference<>(new Point(source.x(), -500));
        Character agent = agentAt(map, position, AgentMovementProfile.base());
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        AgentModeService.startMoveTo(entry, new Point(transfer.endPoint), false);
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        AgentMapStateRuntime.setMapTracking(entry, map, AgentFootholdIndexService.buildFhIndex(map));
        AgentRopeMovementService.attachToRope(entry, agent, source, -500);
        entry.verticalTraversalState().begin(graph, entryEdge, transfer, new Point(transfer.endPoint),
                transfer.toRegionId);
        entry.verticalTraversalState().observeRopeAttachment();
        AgentNavigationDebugStateRuntime.setActiveNavigationEdge(entry, transfer);
        AgentNavigationDebugStateRuntime.setPlannedNavigationTargetPosition(entry, new Point(transfer.endPoint));
        AgentNavigationDebugStateRuntime.setNavTargetRegionId(entry, transfer.toRegionId);

        boolean caught = false;
        for (int tick = 0; tick < 12 && !caught; tick++) {
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(entry, agent, tick % 2 == 0, -1);
            assertFalse(hangsOn(entry, beside), "caught rope x68 at " + position.get());
            caught = hangsOn(entry, target);
        }

        assertTrue(caught, "never caught rope x69; ended at " + position.get());
    }

    @FunctionalInterface
    private interface TickObserver {
        void observe(AgentRuntimeEntry entry, Point position, int tick);
    }

    private static Walk walk(MapleMap map,
                             Point start,
                             String portalName,
                             int speed,
                             int maxTicks,
                             TickObserver observer) {
        AgentMovementProfile profile = new AgentMovementProfile(speed, 100);
        AgentNavigationGraphService.getGraph(map, profile);
        Point target = AgentPortalApproachService.navigableTarget(map, map.getPortal(portalName));
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        Character agent = agentAt(map, position, profile);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, profile);
        AgentModeService.startMoveTo(entry, target, true);
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);

        int reachedAt = -1;
        int maxFailures = 0;
        for (int tick = 0; tick < maxTicks && reachedAt < 0; tick++) {
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(entry, agent, tick % 2 == 0, -1);
            if (observer != null) {
                observer.observe(entry, position.get(), tick);
            }
            maxFailures = Math.max(maxFailures, failures(entry));
            if (position.get().distanceSq(target) <= ARRIVAL_PX * ARRIVAL_PX
                    && !AgentMovementStateRuntime.inAir(entry)) {
                reachedAt = tick;
            }
        }
        return new Walk(reachedAt, maxFailures, position.get());
    }

    private record Walk(int reachedAt, int maxFailures, Point position) {
    }

    private static int failures(AgentRuntimeEntry entry) {
        int count = 0;
        for (AgentNavigationEdgeReliabilityState.EdgeFailure failure
                : AgentNavigationEdgeReliabilityRuntime.snapshot(entry, System.currentTimeMillis()).edges()) {
            count += failure.failureCount();
        }
        return count;
    }

    private static AgentNavigationGraph.Edge jumpFrom(AgentNavigationGraph graph, Point start) {
        for (AgentNavigationGraph.Region region : graph.regions) {
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == AgentNavigationGraph.EdgeType.JUMP && edge.startPoint.equals(start)) {
                    return edge;
                }
            }
        }
        throw new AssertionError("no JUMP edge from " + start);
    }

    private static AgentNavigationGraph.Edge transfer(AgentNavigationGraph graph, Rope from, Rope to, int startY) {
        int fromRegion = ropeRegion(graph, from);
        int toRegion = ropeRegion(graph, to);
        for (AgentNavigationGraph.Edge edge : graph.getOutgoing(fromRegion)) {
            if (edge.toRegionId == toRegion && edge.type == AgentNavigationGraph.EdgeType.CLIMB
                    && edge.startPoint.y == startY) {
                return edge;
            }
        }
        throw new AssertionError("no CLIMB edge x" + from.x() + " -> x" + to.x() + " at y=" + startY);
    }

    private static int ropeRegion(AgentNavigationGraph graph, Rope rope) {
        for (AgentNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion && region.minX == rope.x()
                    && region.minY == rope.topY() && region.maxY == rope.bottomY()) {
                return region.id;
            }
        }
        throw new AssertionError("no region for rope x=" + rope.x());
    }

    private static AgentNavigationGraph.Edge groundEntry(AgentNavigationGraph graph, int ropeRegion) {
        for (AgentNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                continue;
            }
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.toRegionId == ropeRegion && edge.type == AgentNavigationGraph.EdgeType.CLIMB) {
                    return edge;
                }
            }
        }
        throw new AssertionError("no ground entry onto r" + ropeRegion);
    }

    private static boolean hangsOn(AgentRuntimeEntry entry, Rope rope) {
        Rope climbRope = AgentClimbStateRuntime.climbRope(entry);
        return AgentClimbStateRuntime.climbing(entry) && climbRope != null
                && climbRope.x() == rope.x()
                && climbRope.topY() == rope.topY()
                && climbRope.bottomY() == rope.bottomY();
    }

    private static Rope rope(MapleMap map, int x) {
        for (Rope rope : map.getRopes()) {
            if (rope.x() == x) {
                return rope;
            }
        }
        throw new AssertionError("no rope at x=" + x);
    }

    private static Character agentAt(MapleMap map, AtomicReference<Point> position, AgentMovementProfile profile) {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getId()).thenReturn(1);
        when(agent.getHp()).thenReturn(100);
        when(agent.getMaxHp()).thenReturn(100);
        when(agent.isAlive()).thenReturn(true);
        when(agent.getTotalMoveSpeedStat()).thenReturn(profile.totalSpeedStat());
        when(agent.getTotalJumpStat()).thenReturn(profile.totalJumpStat());
        // Mu Lung's in-map portals (h000..h006) enter through the client: a same-map changeMap.
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        MapManager mapManager = mock(MapManager.class);
        when(agent.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(agent);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(mapManager);
        when(mapManager.getMap(anyInt())).thenReturn(map);
        doAnswer(invocation -> {
            Portal to = invocation.getArgument(1);
            position.set(new Point(to.getPosition()));
            return null;
        }).when(agent).changeMap(any(MapleMap.class), any(Portal.class));
        return agent;
    }
}

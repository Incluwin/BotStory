package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScratchStepsBgProbeTest {
    @Test
    void sweep() throws Exception {
        int ticks = Integer.getInteger("probe.ticks", 600);
        boolean verbose = Boolean.getBoolean("probe.verbose");
        long sleepMs = Long.getLong("probe.sleep", 0L);
        String only = System.getProperty("probe.only");
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
        int id = 5000;
        for (int[] p : profiles) {
            AgentMovementProfile profile = new AgentMovementProfile(p[0], p[1]);
            AgentNavigationGraphService.getGraph(map, profile);
            for (Point start : starts) {
                for (int phase = 0; phase < 2; phase++) {
                    String label = p[0] + "/" + p[1] + " " + start.x + "," + start.y + " ph" + phase;
                    if (only != null && !only.equals(label)) {
                        continue;
                    }
                    AtomicReference<Point> position = new AtomicReference<>(new Point(start));
                    Character agent = agentAt(map, position, id++);
                    AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
                    AgentMovementStateRuntime.setMovementProfile(entry, profile);
                    entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
                    Point target = new Point(538, 430);
                    int arrived = -1;
                    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                    StringBuilder ys = new StringBuilder();
                    for (int tick = 0; tick < ticks; tick++) {
                        AgentModeService.startMoveTo(entry, target, true);
                        AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(
                                entry, agent, (tick + phase) % 2 == 0);
                        Point at = position.get();
                        if (verbose) {
                            Object edge = AgentNavigationDebugStateRuntime.activeNavigationEdge(entry);
                            System.out.printf("T %d at=(%d,%d) air=%s rope=%s nav=%s edge=%s%n", tick, at.x, at.y,
                                    AgentMovementStateRuntime.inAir(entry), AgentClimbStateRuntime.climbing(entry),
                                    AgentNavigationDebugStateRuntime.lastDecision(entry), describe(edge));
                        }
                        if (tick >= ticks - 100) {
                            minX = Math.min(minX, at.x);
                            maxX = Math.max(maxX, at.x);
                            if (ys.indexOf("" + at.y + ";") < 0) {
                                ys.append(at.y).append(';');
                            }
                        }
                        if (at.distance(target) < 25) {
                            arrived = tick;
                            break;
                        }
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    }
                    Point end = position.get();
                    System.out.printf("RESULT %s arrived=%d end=(%d,%d) lastXs=%d..%d ys=%s%n", label, arrived,
                            end.x, end.y, minX, maxX, ys);
                }
            }
        }
    }

    private static String describe(Object o) {
        if (!(o instanceof AgentNavigationGraph.Edge e)) {
            return "none";
        }
        return e.type + " r" + e.fromRegionId + "->r" + e.toRegionId + " (" + e.startPoint.x + "," + e.startPoint.y
                + ")->(" + e.endPoint.x + "," + e.endPoint.y + ") step=" + e.launchStepX + " launch=" + e.launchMinX
                + ".." + e.launchMaxX;
    }

    private static Character agentAt(MapleMap map, AtomicReference<Point> position, int id) {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getId()).thenReturn(id);
        when(agent.getHp()).thenReturn(100);
        when(agent.getMaxHp()).thenReturn(100);
        when(agent.isAlive()).thenReturn(true);
        when(agent.getTotalMoveSpeedStat()).thenReturn(100);
        when(agent.getTotalJumpStat()).thenReturn(100);
        return agent;
    }
}

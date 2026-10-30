package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentStandaloneMoveTargetCoordinator;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The grounded hand-off a rope or ladder crossing holds for one AI tick after its exit lands. Real geometry at
 * background cadence: 250 ms ticks of five movement substeps, an AI tick every second tick.
 */
class AgentNavigationRopeExitHoldTest {
    private static final int TUNNEL_THAT_LOST_LIGHT_II = 105090100;
    private static final int ARRIVAL_PX = 45;

    @Test
    void tunnelArrivalsFromAnotherEntranceReachWest00() {
        // Live 2026-10-25 to 10-29, 160 resupply walks stood 300 s on 105090100 without reaching west00. The way
        // up from either arrival jumps from rope x 829 onto a 160 px ledge (x 817..975). The hand-off then steered
        // toward west00, far left on another region, unplanned: the Agent walked off the ledge's left end back to
        // the floor below, climbed the rope again and jumped again, forever.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(TUNNEL_THAT_LOST_LIGHT_II);
        Point west00 = AgentPortalApproachService.navigableTarget(map, map.getPortal("west00"));
        for (String arrival : new String[] {"east01", "east00"}) {
            int reachedAt = walk(map, map.getPortal(arrival).getPosition(), west00, 400);

            assertTrue(reachedAt >= 0, "from " + arrival + " never reached west00");
        }
    }

    @Test
    void theHandOffStillWalksOnToATargetOnTheLandingGround() {
        // From the ledge's own rope exit to a spot on that ledge, the hand-off target is the spot itself.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(TUNNEL_THAT_LOST_LIGHT_II);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        Point onLedge = new Point(940, 1321);
        int ledge = graph.findRegionId(map, onLedge);
        assertEquals(ledge, graph.findRegionId(map, new Point(855, 1326)));

        int reachedAt = walk(map, map.getPortal("east01").getPosition(), onLedge, 300);

        assertTrue(reachedAt >= 0, "never reached the ledge spot");
    }

    private static int walk(MapleMap map, Point start, Point target, int maxTicks) {
        AgentMovementProfile profile = AgentMovementProfile.base();
        AgentNavigationGraphService.getGraph(map, profile);
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        Character agent = agentAt(map, position, profile);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, profile);
        AgentModeService.startMoveTo(entry, target, true);
        entry.simulationState().transitionTo(AgentSimulationMode.BACKGROUND_ACTIVE, 0L);
        for (int tick = 0; tick < maxTicks; tick++) {
            AgentStandaloneMoveTargetCoordinator.tickStandaloneMoveTarget(entry, agent, tick % 2 == 0, -1);
            if (position.get().distanceSq(target) <= ARRIVAL_PX * ARRIVAL_PX
                    && !AgentMovementStateRuntime.inAir(entry)) {
                return tick;
            }
        }
        return -1;
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
        when(agent.getHp()).thenReturn(30_000);
        when(agent.getMaxHp()).thenReturn(30_000);
        when(agent.isAlive()).thenReturn(true);
        when(agent.getTotalMoveSpeedStat()).thenReturn(profile.totalSpeedStat());
        when(agent.getTotalJumpStat()).thenReturn(profile.totalJumpStat());
        return agent;
    }
}

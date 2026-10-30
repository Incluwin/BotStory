package server.agents.capabilities.movement;

import client.Character;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.runtime.AgentRuntimeEntry;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentMoveTargetStateRuntimeTest {
    @Test
    void adaptsMoveTargetStateWithDefensiveCopies() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Point target = new Point(100, 200);

        AgentMoveTargetStateRuntime.setMoveTarget(entry, target, false);
        target.x = 999;

        assertTrue(AgentMoveTargetStateRuntime.hasMoveTarget(entry));
        assertFalse(AgentMoveTargetStateRuntime.isPrecise(entry));
        assertEquals(new Point(100, 200), AgentMoveTargetStateRuntime.moveTarget(entry));
        assertTrue(AgentMoveTargetStateRuntime.moveTargetEquals(entry, new Point(100, 200)));

        Point exposed = AgentMoveTargetStateRuntime.moveTarget(entry);
        exposed.y = 999;

        assertEquals(new Point(100, 200), AgentMoveTargetStateRuntime.moveTarget(entry));
    }

    @Test
    void adaptsPreciseAndNormalArrivalChecks() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        AgentMoveTargetStateRuntime.setMoveTarget(entry, new Point(100, 100), false);

        assertTrue(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(125, 100), 25));
        assertFalse(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(126, 100), 25));

        AgentMoveTargetStateRuntime.setPreciseMoveTarget(entry, new Point(100, 100));

        assertTrue(AgentMoveTargetStateRuntime.isPrecise(entry));
        assertTrue(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(108, 100), 25));
        assertFalse(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(109, 100), 25));
    }

    @Test
    void clearingTargetAlsoClearsPreciseFlag() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        AgentMoveTargetStateRuntime.setPreciseMoveTarget(entry, new Point(100, 100));
        AgentMoveTargetStateRuntime.clearMoveTarget(entry);

        assertFalse(AgentMoveTargetStateRuntime.hasMoveTarget(entry));
        assertFalse(AgentMoveTargetStateRuntime.isPrecise(entry));
        assertNull(AgentMoveTargetStateRuntime.moveTarget(entry));

        AgentMoveTargetStateRuntime.setMoveTarget(entry, null, true);

        assertFalse(AgentMoveTargetStateRuntime.hasMoveTarget(entry));
        assertFalse(AgentMoveTargetStateRuntime.isPrecise(entry));
    }
    @Test
    void preciseTargetIsNotReachedInTheAirPassingThroughItsBox() {
        // A jump or fall that passes within 8 px of the staging spot cleared the target mid-air,
        // and the Agent then landed wherever the arc took it.
        MapleMap map = mapWithFootholds(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        Point standing = new Point(104, 96);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agentAt(map, standing), null, null);
        AgentMoveTargetStateRuntime.setPreciseMoveTarget(entry, new Point(100, 100));

        AgentMovementStateRuntime.setInAir(entry, true);
        assertFalse(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, standing, 25));

        AgentMovementStateRuntime.setInAir(entry, false);
        assertTrue(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(104, 100), 25));
    }

    @Test
    void preciseTargetIsNotReachedFromAnotherPlatformInsideItsBox() {
        // A ledge 6 px above the floor overhangs the staging spot: standing on it is inside the
        // 8 px box, but not on the spot's ground.
        Foothold floor = new Foothold(new Point(0, 100), new Point(300, 100), 1);
        Foothold ledge = new Foothold(new Point(80, 94), new Point(160, 94), 2);
        MapleMap map = mapWithFootholds(floor, ledge);
        AgentNavigationGraphService.rebuildGraph(map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agentAt(map, new Point(104, 94)), null, null);
        AgentMoveTargetStateRuntime.setPreciseMoveTarget(entry, new Point(100, 100));

        assertFalse(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(104, 94), 25));
        assertTrue(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(104, 100), 25));

        AgentMoveTargetStateRuntime.setMoveTarget(entry, new Point(100, 100), false);
        assertTrue(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(104, 94), 25),
                "a normal target keeps the box alone");
    }

    @Test
    void preciseGroundTargetIsNotReachedHangingOnTheLadderBesideIt() {
        MapleMap map = mapWithFootholds(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        Rope ladder = new Rope(104, 20, 95, true);
        map.addRope(ladder);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agentAt(map, new Point(104, 93)), null, null);
        AgentMoveTargetStateRuntime.setPreciseMoveTarget(entry, new Point(100, 100));
        AgentClimbStateRuntime.setClimbingOnRope(entry, ladder);

        assertFalse(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(104, 93), 25));

        // A spot up on the ladder itself, with no ground under it, is reached hanging there.
        AgentMoveTargetStateRuntime.setPreciseMoveTarget(entry, new Point(104, 40));
        assertTrue(AgentMoveTargetStateRuntime.hasReachedMoveTarget(entry, new Point(104, 44), 25));
    }

    private static Character agentAt(MapleMap map, Point position) {
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getPosition()).thenReturn(new Point(position));
        return agent;
    }

    private static MapleMap mapWithFootholds(Foothold... footholds) {
        MapleMap map = new MapleMap(910000008, 0, 0, 910000008, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        for (Foothold foothold : footholds) {
            tree.insert(foothold);
        }
        map.setFootholds(tree);
        return map;
    }
}

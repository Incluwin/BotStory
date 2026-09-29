package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4 hired hands, live 2026-09-29: a citizen hired on the far side of Kerning City or Henesys was adopted where it
 * stood and followed its new owner, and 2,150 / 3,850 px on it stood beside them in one step. Following them from
 * there, the route search took a hidden in-map portal, which it counts as free: Kerning's ph03_1 (1313,154) to ph03
 * (-855,154), Henesys' hp00_1 (5169,92) to hp00 (1302,93), the exact jumps sampled live. Ordinary routing keeps those
 * shortcuts; an Agent asked to arrive on foot walks until it is near its target, and a target only a portal reaches is
 * still reached.
 */
class AgentNavigationOnFootTest {
    private static final int HENESYS = 100000000;
    private static final int KERNING_CITY = 103000000;
    private static final int ARIANT = 260000000;
    private static final int REACH_PX = 160;
    private static final int KERNING_PH03_1 = 42;
    private static final int HENESYS_HP00_1 = 13;
    /** Each Agent its own id: the path service remembers routes per Agent. */
    private static final AtomicInteger IDS = new AtomicInteger(7440);

    @Test
    void kerningHireFollowingItsOwnerWestTakesPh03UnlessItArrivesOnFoot() {
        MapleMap map = load(KERNING_CITY);
        Point mainStreet = new Point(1000, 156); // Faro723 came down to the main street east of ph03_1
        Point owner = new Point(-1507, 6);       // ohboy, west of the main street

        AgentRuntimeEntry anyAgent = entryAt(mainStreet, map);
        AgentNavigationTargetService.resolveTarget(anyAgent, owner, true);
        AgentNavigationGraph.Edge shortcut = activeEdge(anyAgent);
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, shortcut.type,
                "the live jump: its route starts with ph03_1");
        assertEquals(KERNING_PH03_1, shortcut.portalId);
        assertEquals(-855, shortcut.endPoint.x);

        AgentRuntimeEntry hired = entryAt(mainStreet, map);
        AgentNavigationOnFootRuntime.arriveOnFoot(hired, KERNING_CITY, REACH_PX);
        AgentNavigationTargetService.resolveTarget(hired, owner, true);
        AgentNavigationGraph.Edge walk = activeEdge(hired);
        assertNotEquals(AgentNavigationGraph.EdgeType.PORTAL, walk.type);
        assertTrue(walk.startPoint.x < -855, "it walks the main street west past ph03 before it leaves it");
        assertTrue(AgentNavigationOnFootRuntime.onFoot(hired), "still far from its owner");
    }

    @Test
    void henesysHireOnTheFarSideTakesHp00UnlessItArrivesOnFoot() {
        MapleMap map = load(HENESYS);
        Point ledge = new Point(5100, 94); // the ledge beside hp00_1 where Liriki landed
        Point owner = new Point(900, 275);  // ohboy, the middle of town

        AgentRuntimeEntry anyAgent = entryAt(ledge, map);
        AgentNavigationTargetService.resolveTarget(anyAgent, owner, true);
        AgentNavigationGraph.Edge shortcut = activeEdge(anyAgent);
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, shortcut.type,
                "the live jump: its route starts with hp00_1");
        assertEquals(HENESYS_HP00_1, shortcut.portalId);
        assertEquals(1302, shortcut.endPoint.x);

        AgentRuntimeEntry hired = entryAt(ledge, map);
        AgentNavigationOnFootRuntime.arriveOnFoot(hired, HENESYS, REACH_PX);
        AgentNavigationTargetService.resolveTarget(hired, owner, true);
        AgentNavigationGraph.Edge walk = activeEdge(hired);
        assertEquals(AgentNavigationGraph.EdgeType.DROP, walk.type, "it drops off the ledge and walks west");
        assertTrue(AgentNavigationOnFootRuntime.onFoot(hired));
    }

    @Test
    void aPortalAlreadyCommittedIsDroppedOnceTheAgentIsAskedToWalk() {
        MapleMap map = load(KERNING_CITY);
        Point owner = new Point(-1507, 6);
        AgentRuntimeEntry entry = entryAt(new Point(1000, 156), map);
        AgentNavigationTargetService.resolveTarget(entry, owner, true);
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, activeEdge(entry).type);

        AgentNavigationOnFootRuntime.arriveOnFoot(entry, KERNING_CITY, REACH_PX);
        AgentNavigationTargetService.resolveTarget(entry, owner, true);

        assertNotEquals(AgentNavigationGraph.EdgeType.PORTAL, activeEdge(entry).type);
    }

    @Test
    void theRequestEndsNearItsTargetOrOnAnotherMap() {
        MapleMap map = load(KERNING_CITY);
        AgentRuntimeEntry far = entryAt(new Point(1000, 156), map);
        AgentNavigationOnFootRuntime.arriveOnFoot(far, KERNING_CITY, REACH_PX);
        AgentNavigationTargetService.resolveTarget(far, new Point(1000 - REACH_PX - 1, 156), true);
        assertTrue(AgentNavigationOnFootRuntime.onFoot(far));

        AgentNavigationTargetService.resolveTarget(far, new Point(1000 - REACH_PX, 156), true);
        assertFalse(AgentNavigationOnFootRuntime.onFoot(far), "arrived: it routes as any other Agent again");

        AgentRuntimeEntry moved = entryAt(new Point(1000, 156), map);
        AgentNavigationOnFootRuntime.arriveOnFoot(moved, HENESYS, REACH_PX);
        AgentNavigationTargetService.resolveTarget(moved, new Point(-1507, 6), true);
        assertFalse(AgentNavigationOnFootRuntime.onFoot(moved), "asked on another map");
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, activeEdge(moved).type);

        AgentRuntimeEntry unasked = entryAt(new Point(1000, 156), map);
        AgentNavigationOnFootRuntime.arriveOnFoot(unasked, KERNING_CITY, 0);
        assertFalse(AgentNavigationOnFootRuntime.onFoot(unasked), "no reach, no request");
    }

    @Test
    void aTargetOnlyAPortalReachesIsStillReached() {
        // Ariant's upper terraces are reached from the street only through their in-map portals.
        MapleMap map = load(ARIANT);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Point street = new Point(900, 275);
        Point terrace = new Point(-496, -21);
        int streetRegion = graph.findRegionId(map, street);
        int terraceRegion = graph.findRegionId(map, terrace);
        assertTrue(graph.canReach(streetRegion, terraceRegion));
        AgentNavigationPathService.MovementPathSelection onFootOnly = AgentNavigationPathService.movementPathSelection(
                graph, map, street, streetRegion, terraceRegion, terrace, null,
                AgentNavigationPathService.MOVEMENT_MAX_EDGE_CHECKS, AgentNavigationPathService.MAX_EDGE_CHECKS,
                false, null, AgentNavigationOnFootRuntime::walks, null);
        assertFalse(onFootOnly.outcome().reached(), "no route on foot");

        AgentRuntimeEntry entry = entryAt(street, map);
        AgentNavigationOnFootRuntime.arriveOnFoot(entry, ARIANT, REACH_PX);
        AgentNavigationTargetService.resolveTarget(entry, terrace, true);

        assertFalse(AgentNavigationOnFootRuntime.onFoot(entry), "the request ends");
        AgentNavigationGraph.Edge edge = activeEdge(entry);
        assertNotNull(edge);
        assertEquals(AgentNavigationGraph.EdgeType.PORTAL, edge.type, "it takes the terrace portal as before");
    }

    /** The map with its graph built, as a warmed live map has it. */
    private static MapleMap load(int mapId) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        assertNotNull(AgentNavigationGraphService.getGraph(map));
        return map;
    }

    private static AgentNavigationGraph.Edge activeEdge(AgentRuntimeEntry entry) {
        return (AgentNavigationGraph.Edge) AgentNavigationDebugStateRuntime.activeNavigationEdge(entry);
    }

    private static AgentRuntimeEntry entryAt(Point position, MapleMap map) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(IDS.incrementAndGet());
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(position));
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        return entry;
    }
}

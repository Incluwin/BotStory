package server.agents.capabilities.navigation;

import client.Character;
import client.Skill;
import constants.skills.FPWizard;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.StatEffect;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqMovementSkillPolicy;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSession;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSessionRegistry;
import server.agents.capabilities.partyquest.rjpq.RjpqDarkLabRunners;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * RJPQ Dark Labs on the real Map.wz geometry (2026-10-17): Lab 1's six box ledges and its exit are reachable at a jump
 * of 120 (max Haste on a base jump) and none at 110, which sets AgentRjpqDarkLabs.LAB_ONE_JUMP; Lab 2's walled cells
 * get Teleport edges, and the run's Lab 2 runner reaches every one of the thirty box spots from the corridor and gets
 * back to the exit, while a companion that is not the runner may not route over them.
 */
class AgentRjpqDarkLabNavigationTest {
    private static final int LAB_ONE = 926_100_201;
    private static final int LAB_TWO = 926_100_202;

    @Test
    void labOnesBoxesTakeAJumpOfOneHundredTwenty() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(LAB_ONE);
        List<Point> boxes = List.of(new Point(82, -326), new Point(1058, -294), new Point(1232, -248),
                new Point(208, -250), new Point(436, -322), new Point(774, -246));
        Point floor = new Point(1260, 229);
        Point exit = new Point(1272, 222);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(LAB_ONE);
        AgentNavigationGraph hasted = AgentNavigationGraphService.rebuildGraph(map, new AgentMovementProfile(140, 120));
        AgentNavigationGraph short_ = AgentNavigationGraphService.rebuildGraph(map, new AgentMovementProfile(120, 110));
        for (Point box : boxes) {
            assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                    route(hasted, map, agent, floor, box).completeness(), "up to " + box);
            assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                    route(hasted, map, agent, box, exit).completeness(), "out from " + box);
            assertNotEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                    route(short_, map, agent, floor, box).completeness(), "jump 110 to " + box);
        }
    }

    @Test
    void labTwosCellsAreTheTeleportRunnersOnly() {
        assertTrue(AgentRjpqMovementSkillPolicy.authorsTeleportEdges(LAB_TWO));
        assertTrue(AgentRjpqMovementSkillPolicy.authorsTeleportEdges(926_110_202));
        assertFalse(AgentRjpqMovementSkillPolicy.authorsTeleportEdges(LAB_ONE));
        assertFalse(AgentRjpqMovementSkillPolicy.authorsTeleportEdges(926_100_200));

        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(LAB_TWO);
        AgentNavigationGraph graph = AgentNavigationGraphService.rebuildGraph(map);
        EventInstanceManager event = mock(EventInstanceManager.class);
        AgentRjpqSession session = session(event);
        Character runner = mage(72_001, map, event);
        Character other = mage(72_002, map, event);
        Skill teleport = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(teleport.getEffect(1)).thenReturn(effect);
        when(effect.getMpCon()).thenReturn((short) 10);
        try (MockedStatic<client.SkillFactory> skills = mockStatic(client.SkillFactory.class)) {
            skills.when(() -> client.SkillFactory.getSkill(FPWizard.TELEPORT)).thenReturn(teleport);
            AgentNavigationGraph.Edge cellTeleport = graph.regions.stream()
                    .flatMap(region -> graph.getOutgoing(region.id).stream())
                    .filter(edge -> edge.type == AgentNavigationGraph.EdgeType.TELEPORT)
                    .findFirst().orElseThrow();
            assertTrue(AgentMovementSkillPolicy.canUseActivePath(runner, cellTeleport));
            assertFalse(AgentMovementSkillPolicy.canUseActivePath(other, cellTeleport));

            Point corridor = new Point(-1094, 153);
            Point exit = new Point(-1092, 144);
            int spots = 0;
            for (int x : new int[] {-858, -582, -315, -44, 224, 495}) {
                for (int y : new int[] {154, 10, -125, -269, -405}) {
                    Point spot = new Point(x, y);
                    assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                            route(graph, map, runner, corridor, spot).completeness(), "to cell " + spot);
                    assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                            route(graph, map, runner, spot, exit).completeness(), "out from cell " + spot);
                    spots++;
                }
            }
            assertEquals(30, spots);
        } finally {
            AgentRjpqSessionRegistry.remove(session);
        }
    }

    private static AgentNavigationPathService.MovementPathSelection route(AgentNavigationGraph graph, MapleMap map,
                                                                          Character agent, Point from, Point to) {
        return AgentNavigationPathService.findNextEdgeSelectionVaried(graph, agent, from,
                graph.findRegionId(map, from), graph.findRegionId(map, to), to, null, edge -> true);
    }

    private static AgentRjpqSession session(EventInstanceManager event) {
        return RjpqDarkLabRunners.registeredRun(event, 72_000, new int[] {72_001, 72_002, 72_003}, 72_003, 72_001);
    }

    private static Character mage(int id, MapleMap map, EventInstanceManager event) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getMapId()).thenReturn(LAB_TWO);
        when(agent.getMap()).thenReturn(map);
        when(agent.getEventInstance()).thenReturn(event);
        when(agent.getSkillLevel(FPWizard.TELEPORT)).thenReturn(1);
        when(agent.getMaxMp()).thenReturn(1_000);
        when(agent.getMp()).thenReturn(1_000);
        return agent;
    }
}

package server.agents.capabilities.navigation;

import client.Character;
import client.Skill;
import constants.skills.Cleric;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.StatEffect;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqMovementSkillPolicy;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * CWKPQ's stage-5 magician room on the real Map.wz geometry (C9 live 2026-10-22: iWantPanda stood at the room's arrival
 * spot with 22 monsters around and none in reach). Walking and jumping reach no spawn from where the portal lands; the
 * room gets Teleport edges, and a CWKPQ member reaches spawns on both sides of the room over them, while a mage in no
 * CWKPQ run may not route over them.
 */
class AgentCwkpqMageRoomNavigationTest {
    private static final int MAGE_ROOM = 610_030_521;
    private static final Point ARRIVAL = new Point(3371, -384);
    /** Spawns (Map.wz life) from the far left of the room to its right half. */
    private static final List<Point> SPAWNS = List.of(new Point(183, -324), new Point(1076, -444),
            new Point(1437, -264), new Point(2344, -324));

    @Test
    void onlyTheMagicianRoomGetsTeleportEdgesAndOnlyARunsMemberUsesThem() {
        assertTrue(AgentCwkpqMovementSkillPolicy.authorsTeleportEdges(MAGE_ROOM));
        assertFalse(AgentCwkpqMovementSkillPolicy.authorsTeleportEdges(610_030_510));
        assertFalse(AgentCwkpqMovementSkillPolicy.authorsTeleportEdges(610_030_500));

        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(MAGE_ROOM);
        AgentNavigationGraph graph = AgentNavigationGraphService.rebuildGraph(map);
        EventInstanceManager event = mock(EventInstanceManager.class);
        Character member = cleric(81_001, map, event);
        Character stranger = cleric(81_002, map, event);
        AgentBossRunSession run = mock(AgentBossRunSession.class);
        when(run.definition()).thenReturn(AgentBossRunCatalog.CWKPQ);
        Skill teleport = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(teleport.getEffect(anyInt())).thenReturn(effect);
        when(effect.getMpCon()).thenReturn((short) 10);
        try (MockedStatic<client.SkillFactory> skills = mockStatic(client.SkillFactory.class);
             MockedStatic<AgentBossRunRegistry> registry = mockStatic(AgentBossRunRegistry.class)) {
            skills.when(() -> client.SkillFactory.getSkill(Cleric.TELEPORT)).thenReturn(teleport);
            registry.when(() -> AgentBossRunRegistry.forMember(81_001)).thenReturn(run);
            AgentNavigationGraph.Edge hop = graph.regions.stream()
                    .flatMap(region -> graph.getOutgoing(region.id).stream())
                    .filter(edge -> edge.type == AgentNavigationGraph.EdgeType.TELEPORT)
                    .findFirst().orElseThrow();
            assertTrue(AgentMovementSkillPolicy.canUseActivePath(member, hop));
            assertFalse(AgentMovementSkillPolicy.canUseActivePath(stranger, hop), "no CWKPQ run");

            for (Point spawn : SPAWNS) {
                assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                        route(graph, map, member, ARRIVAL, spawn, true).completeness(), "to " + spawn);
                assertNotEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                        route(graph, map, member, ARRIVAL, spawn, false).completeness(), "on foot to " + spawn);
                // combat target scoring counts the member's Teleport too, so the grind picks these monsters
                assertEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                        scored(graph, map, member, spawn).completeness(), "scored to " + spawn);
                assertNotEquals(AgentNavigationPathService.RouteCompleteness.COMPLETE,
                        scored(graph, map, null, spawn).completeness(), "scored on foot to " + spawn);
            }
        }
    }

    private static AgentNavigationPathService.MovementPathSelection route(AgentNavigationGraph graph, MapleMap map,
                                                                          Character agent, Point from, Point to,
                                                                          boolean teleport) {
        return AgentNavigationPathService.findNextEdgeSelectionVaried(graph, agent, from,
                graph.findRegionId(map, from), graph.findRegionId(map, to), to, null,
                edge -> teleport || edge.type != AgentNavigationGraph.EdgeType.TELEPORT);
    }

    private static AgentNavigationPathService.SearchOutcome scored(AgentNavigationGraph graph, MapleMap map,
                                                                   Character agent, Point to) {
        return AgentNavigationPathService.findRouteForTargetScore(graph, map, ARRIVAL,
                graph.findRegionId(map, ARRIVAL), graph.findRegionId(map, to), to, agent, edge -> true, edge -> 0);
    }

    private static Character cleric(int id, MapleMap map, EventInstanceManager event) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getMapId()).thenReturn(MAGE_ROOM);
        when(agent.getMap()).thenReturn(map);
        when(agent.getEventInstance()).thenReturn(event);
        when(agent.getSkillLevel(Cleric.TELEPORT)).thenReturn(1);
        when(agent.getMaxMp()).thenReturn(1_000);
        when(agent.getMp()).thenReturn(1_000);
        return agent;
    }
}

package server.agents.progression;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which portal travelStatus walks to when several on a map lead to the next map. Live 2026-10-09 (main ac57d636f):
 * Thane, Flint, Ingrid and Sorrel left the Balrog stairway (105100000) for Ant Tunnel Park's store, came out of the
 * temple's under00 portal on the bottom floor of Another Entrance (105090200) and stood at (222,1602) for 8+ minutes:
 * the router sent them to west00 (id 6) on the top floor, which nothing below climbs to, while west01 (id 7) on the
 * middle floor also leads to 105090100 and is two climbs away.
 */
class AgentVictoriaRoutePortalChoiceTest {
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int NEXT_MAP = 105090100;
    private static final int ANT_TUNNEL_PARK = 105070001;
    private static final int WEST00 = 6;
    private static final int WEST01 = 7;
    private static final Point UNDER00_LANDING = new Point(222, 1602);
    private static final Point WEST00_SPOT = new Point(-345, 187);
    private static final Point WEST01_SPOT = new Point(-299, 1022);

    @Test
    void underTempleArrivalWalksToTheMiddleFloorPortalItCanReach() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(ANOTHER_ENTRANCE);
        AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(4_310);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(ANOTHER_ENTRANCE);
        when(agent.getPosition()).thenReturn(new Point(UNDER00_LANDING));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        PrimitiveCapabilityGateway real = CosmicPrimitiveCapabilityGateway.INSTANCE;
        // The map as the router saw it live: west00 first, and nothing on the bottom floor reaches it.
        assertEquals(WEST00, real.directPortalIdTo(agent, NEXT_MAP));
        assertEquals(List.of(WEST00, WEST01), real.directPortalIdsTo(agent, NEXT_MAP));
        assertFalse(real.canReach(entry, agent, real.portalPosition(agent, WEST00)));
        assertTrue(real.canReach(entry, agent, real.portalPosition(agent, WEST01)));
        PrimitiveCapabilityGateway gateway = delegatingTo(real);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, ANT_TUNNEL_PARK, gateway, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        assertEquals(NEXT_MAP, outcome.nextMapId());
        verify(gateway).navigate(entry, real.portalPosition(agent, WEST01), true);
        verify(gateway, never()).navigate(entry, real.portalPosition(agent, WEST00), true);
    }

    @Test
    void theReachablePortalStaysChosenWhileTheAgentIsInTheAir() {
        Character agent = agentAtTheLanding();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = twoPortalGateway(agent);
        // On the floor its graph rules west00 out; in the air (the jump to the rope) it rules nothing out.
        when(gateway.canReach(entry, agent, WEST00_SPOT)).thenReturn(false, true);
        when(gateway.canReach(entry, agent, WEST01_SPOT)).thenReturn(true);

        AgentVictoriaRouteRuntime.travelStatus(entry, agent, ANT_TUNNEL_PARK, gateway, 1_000L);
        AgentVictoriaRouteRuntime.travelStatus(entry, agent, ANT_TUNNEL_PARK, gateway, 1_100L);

        verify(gateway, never()).navigate(any(), eq(WEST00_SPOT), anyBoolean());
        verify(gateway, times(2)).navigate(entry, WEST01_SPOT, true);
    }

    @Test
    void aGatewayThatListsNoAlternativesKeepsTheFirstPortalAsBefore() {
        Character agent = agentAtTheLanding();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.directPortalIdTo(agent, NEXT_MAP)).thenReturn(WEST00);
        when(gateway.portalPosition(agent, WEST00)).thenReturn(WEST00_SPOT);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, ANT_TUNNEL_PARK, gateway, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        verify(gateway).navigate(entry, WEST00_SPOT, true);
    }

    @Test
    void whenTheGraphRulesOutEveryPortalTheFirstOneIsStillWalkedToAsBefore() {
        Character agent = agentAtTheLanding();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = twoPortalGateway(agent);
        when(gateway.canReach(entry, agent, WEST00_SPOT)).thenReturn(false);
        when(gateway.canReach(entry, agent, WEST01_SPOT)).thenReturn(false);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, ANT_TUNNEL_PARK, gateway, 1_000L);

        // canReach ignores movement-skill edges, so "false" is no proof: the supply trip's no-progress watchdog, not
        // the router, ends a walk that reaches no portal.
        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        verify(gateway).navigate(entry, WEST00_SPOT, true);
    }

    private static Character agentAtTheLanding() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(ANOTHER_ENTRANCE);
        when(agent.getPosition()).thenReturn(new Point(UNDER00_LANDING));
        return agent;
    }

    /** Both portals to 105090100 open, west00 first, as a real gateway lists them (its first is directPortalIdTo's). */
    private static PrimitiveCapabilityGateway twoPortalGateway(Character agent) {
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.directPortalIdTo(agent, NEXT_MAP)).thenReturn(WEST00);
        when(gateway.directPortalIdsTo(agent, NEXT_MAP)).thenReturn(List.of(WEST00, WEST01));
        when(gateway.portalPosition(agent, WEST00)).thenReturn(WEST00_SPOT);
        when(gateway.portalPosition(agent, WEST01)).thenReturn(WEST01_SPOT);
        return gateway;
    }

    private static PrimitiveCapabilityGateway delegatingTo(PrimitiveCapabilityGateway real) {
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.directPortalIdTo(any(), anyInt())).thenAnswer(
                call -> real.directPortalIdTo(call.getArgument(0), call.getArgument(1)));
        when(gateway.directPortalIdsTo(any(), anyInt())).thenAnswer(
                call -> real.directPortalIdsTo(call.getArgument(0), call.getArgument(1)));
        when(gateway.portalPosition(any(), anyInt())).thenAnswer(
                call -> real.portalPosition((Character) call.getArgument(0), (int) call.getArgument(1)));
        when(gateway.canReach(any(), any(), any())).thenAnswer(
                call -> real.canReach(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        return gateway;
    }
}

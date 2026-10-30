package server.agents.progression;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hops travelStatus passes over because no walk gets the Agent through them. Live 2026-10-25 to 10-29: 1,579 resupply
 * walks stood 300 s on a map without reaching its portal to the next one, 698 on hops like these.
 */
class AgentVictoriaRouteTrapTest {
    private static final int CRIMSONWOOD_MOUNTAIN_SLOPE = 610020003;
    private static final int CRIMSONWOOD_MOUNTAIN_BASE = 610020000;
    private static final int TUNNEL_THAT_LOST_LIGHT_II = 105090100;
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int BEYOND_ANOTHER_ENTRANCE = 105090300;
    private static final PrimitiveCapabilityGateway REAL = CosmicPrimitiveCapabilityGateway.INSTANCE;

    @Test
    void crimsonwoodPitPortalsArePassedOverForAPortalTheAgentCanWalkTo() {
        // Every portal of 610020003 to 610020000 is a pit along the bottom of the map, entered by falling into it.
        // The shortest way down went through them; the Agent walked toward a pit it could never reach.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(CRIMSONWOOD_MOUNTAIN_SLOPE);
        Character agent = agentOn(map, map.getPortal("sp").getPosition(), null);
        AgentRuntimeEntry entry = entryFor(agent);
        Portal pit = map.getPortal("CM3_c3");
        assertTrue(REAL.canReach(entry, agent, REAL.portalPosition(agent, pit.getId())),
                "precondition: canReach cannot rule out a portal over no ground");
        assertTrue(REAL.unwalkablePortal(entry, agent, pit.getId()));
        assertFalse(REAL.unwalkablePortal(entry, agent, map.getPortal("CM4_1").getId()));
        PrimitiveCapabilityGateway gateway = delegatingTo(REAL);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, CRIMSONWOOD_MOUNTAIN_BASE, gateway, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        assertNotEquals(CRIMSONWOOD_MOUNTAIN_BASE, outcome.nextMapId(), "took the pit hop");
        int portalId = REAL.directPortalIdTo(agent, outcome.nextMapId());
        assertFalse(REAL.unwalkablePortal(entry, agent, portalId));
        verify(gateway).navigate(entry, REAL.portalPosition(agent, portalId), true);
    }

    @Test
    void anAgentInTheAirKeepsThePitHopUntilItLands() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(CRIMSONWOOD_MOUNTAIN_SLOPE);
        Character agent = agentOn(map, map.getPortal("sp").getPosition(), null);
        AgentRuntimeEntry entry = entryFor(agent);
        AgentMovementStateRuntime.setInAir(entry, true);

        assertFalse(REAL.unwalkablePortal(entry, agent, map.getPortal("CM3_c3").getId()));
    }

    @Test
    void theTunnelTakesThePortalThatSetsTheAgentDownOnAnotherEntrancesTopFloor() {
        // Both portals of 105090100 lead to Another Entrance. east01 sets the Agent down on its middle floor, from
        // which nothing reaches east00 to 105090300 on the top floor; east00 sets it down on the top floor.
        MapleMap tunnel = AgentNavigationMapLoader.loadMapGeometry(TUNNEL_THAT_LOST_LIGHT_II);
        MapleMap anotherEntrance = AgentNavigationMapLoader.loadMapGeometry(ANOTHER_ENTRANCE);
        AgentNavigationGraphService.getGraph(anotherEntrance, AgentMovementProfile.base());
        Character agent = agentOn(tunnel, tunnel.getPortal("west00").getPosition(), anotherEntrance);
        AgentRuntimeEntry entry = entryFor(agent);
        int east00 = tunnel.getPortal("east00").getId();
        int east01 = tunnel.getPortal("east01").getId();
        assertEquals(east01, REAL.directPortalIdTo(agent, ANOTHER_ENTRANCE), "precondition: east01 comes first");
        assertTrue(REAL.canReach(entry, agent, REAL.portalPosition(agent, east01)));
        // From west01's landing only 105090400 and the way back are walkable; east00 lands beside 105090300's portal.
        assertEquals(Set.of(105090400, TUNNEL_THAT_LOST_LIGHT_II), REAL.landingExits(entry, agent, east01));
        assertTrue(REAL.landingExits(entry, agent, east00).contains(BEYOND_ANOTHER_ENTRANCE));
        PrimitiveCapabilityGateway gateway = delegatingTo(REAL);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, BEYOND_ANOTHER_ENTRANCE, gateway, 1_000L);

        Point east01Spot = REAL.portalPosition(agent, east01);
        assertEquals(ANOTHER_ENTRANCE, outcome.nextMapId());
        verify(gateway).navigate(entry, REAL.portalPosition(agent, east00), true);
        verify(gateway, never()).navigate(any(), eq(east01Spot), anyBoolean());
    }

    @Test
    void withTheNextMapNotLoadedThePortalOrderStaysAsBefore() {
        MapleMap tunnel = AgentNavigationMapLoader.loadMapGeometry(TUNNEL_THAT_LOST_LIGHT_II);
        Character agent = agentOn(tunnel, tunnel.getPortal("west00").getPosition(), null);
        AgentRuntimeEntry entry = entryFor(agent);
        int east01 = tunnel.getPortal("east01").getId();
        assertEquals(Set.of(), REAL.landingExits(entry, agent, east01));
        PrimitiveCapabilityGateway gateway = delegatingTo(REAL);

        AgentVictoriaRouteRuntime.travelStatus(entry, agent, BEYOND_ANOTHER_ENTRANCE, gateway, 1_000L);

        verify(gateway).navigate(entry, REAL.portalPosition(agent, east01), true);
    }

    private static AgentRuntimeEntry entryFor(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        AgentNavigationGraphService.getGraph(agent.getMap(), AgentMovementProfile.base());
        return entry;
    }

    /** An Agent on map whose channel has loadedNext loaded (or nothing else, when null). */
    private static Character agentOn(MapleMap map, Point position, MapleMap loadedNext) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(4_311);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getPosition()).thenReturn(new Point(position));
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        MapManager maps = mock(MapManager.class);
        when(agent.getClient()).thenReturn(client);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        if (loadedNext != null) {
            when(maps.getLoadedMap(loadedNext.getId())).thenReturn(loadedNext);
        }
        return agent;
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
        when(gateway.unwalkablePortal(any(), any(), anyInt())).thenAnswer(
                call -> real.unwalkablePortal(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(gateway.landingExits(any(), any(), anyInt())).thenAnswer(
                call -> real.landingExits(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        return gateway;
    }
}

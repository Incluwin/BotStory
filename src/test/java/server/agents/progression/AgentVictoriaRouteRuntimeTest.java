package server.agents.progression;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentVictoriaRouteRuntimeTest {
    @Test
    void authoredElliniaLibraryEntranceWinsOverAnInferredDirectPortal() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(101000000);
        when(agent.getPosition()).thenReturn(new Point(334, -3987));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.directPortalIdTo(agent, 101000003)).thenReturn(99);
        when(gateway.portalPosition(agent, 26)).thenReturn(new Point(334, -3987));
        when(gateway.enterPortal(agent, 26)).thenReturn(true);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 101000003, gateway, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        verify(gateway).enterPortal(agent, 26);
        verify(gateway, never()).enterPortal(agent, 99);
        verify(gateway, never()).directPortalIdTo(agent, 101000003);
    }

    @Test
    void newlyEnteredMapRemainsStoppedUntilItsArrivalPauseExpires() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(101000003);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY)
                .recordPortalSuccess(101000003, 1_000L, 1_500L);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);

        AgentVictoriaRouteRuntime.TravelOutcome settling = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 101000003, gateway, 2_000L);
        AgentVictoriaRouteRuntime.TravelOutcome arrived = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 101000003, gateway, 2_500L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, settling.status());
        assertEquals(AgentVictoriaRouteRuntime.Status.ARRIVED, arrived.status());
        verify(gateway).stop(entry);
    }

    @Test
    void unobservedScriptedLibraryArrivalDoesNotConsumeTheNpcRouteImmediately() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(101000000, 101000003, 101000003);
        when(agent.getPosition()).thenReturn(new Point(334, -3987));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.portalPosition(agent, 26)).thenReturn(new Point(334, -3987));
        when(gateway.enterPortal(agent, 26)).thenReturn(true);
        when(gateway.observedByPlayer(agent)).thenReturn(false);

        AgentVictoriaRouteRuntime.travelStatus(entry, agent, 101000003, gateway, 1_000L);
        AgentVictoriaRouteRuntime.TravelOutcome waiting = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 101000003, gateway, 3_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, waiting.status());
        verify(gateway).stop(entry);
    }

    @Test
    void kerningSquaresWayBackBoardsByWalkingIntoTheStationExit() {
        int[] map = {103000310};
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenAnswer(ignored -> map[0]);
        when(agent.getPosition()).thenReturn(new Point(-200, 150));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.portalId(agent, "out00")).thenReturn(2);
        when(gateway.portalPosition(agent, 2)).thenReturn(new Point(-200, 150));
        doAnswer(ignored -> {
            map[0] = 103000302; // the KerningTrain event puts it on the wagon
            return true;
        }).when(gateway).enterPortal(agent, 2);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 103000100, gateway, 1_000L, new AgentVictoriaRouteRuntime.RideBudget(10_000L));

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        assertEquals(103000302, outcome.nextMapId(), "on the train back to the subway");
        verify(gateway).enterPortal(agent, 2);
    }

    @Test
    void aWayBackToASavedLocationIsDoneWhereverItSetsTheRiderDown() {
        int[] map = {800000000};
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenAnswer(ignored -> map[0]);
        when(agent.getPosition()).thenReturn(new Point(400, 120));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.npcPosition(agent, 9000020)).thenReturn(new Point(400, 120));
        when(gateway.grounded(agent)).thenReturn(true);
        doAnswer(ignored -> {
            map[0] = 100000000; // the tour began in Henesys, not at Spinel's default Lith Harbor
            return true;
        }).when(gateway).runNpcScript(agent, 9000020, 0, 0);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 102000000, gateway, 1_000L, new AgentVictoriaRouteRuntime.RideBudget(10_000L));

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        assertEquals(100000000, outcome.nextMapId());
        assertFalse(outcome.edgeBlocked());
        assertEquals(java.util.Set.of(), entry.capabilityStates().require(AgentVictoriaRouteState.STATE_KEY)
                .blockedEdges(1_000L), "landing in Henesys is no refusal");
    }

    @Test
    void jeffsDungeonIsNoWayForAnAgentBelowLevelFifty() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(211040200);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        when(agent.getLevel()).thenReturn(49);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.npcPosition(agent, 2030000)).thenReturn(new Point(0, 0));
        when(gateway.grounded(agent)).thenReturn(true);

        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, AgentVictoriaRouteRuntime.travelStatus(
                entry, agent, 211040300, gateway, 1_000L, new AgentVictoriaRouteRuntime.RideBudget(10_000L)).status());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));

        when(agent.getLevel()).thenReturn(50);
        AgentVictoriaRouteRuntime.travelStatus(entry, agent, 211040300, gateway, 2_000L,
                new AgentVictoriaRouteRuntime.RideBudget(10_000L));
        verify(gateway).runNpcScript(agent, 2030000);
    }
}

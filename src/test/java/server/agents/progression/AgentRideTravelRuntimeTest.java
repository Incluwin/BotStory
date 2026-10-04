package server.agents.progression;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Travel that no portal path covers rides the NPC legs: buy the ticket, board, wait out the ride. */
class AgentRideTravelRuntimeTest {
    private static final int ELLINIA_STATION = 101000300;
    private static final int ORBIS = 200000000;
    private static final int ORBIS_STATION = 200000100;
    private static final int LUDIBRIUM = 220000000;
    private static final int LUDIBRIUM_PLATFORM = 220000110;
    private static final int TRAIN_PLATFORM_GATE = 200000121;
    private static final int TRAIN_PLATFORM = 200000120;
    private static final int LEAFRE_PLATFORM = 240000110;
    private static final int KERNING_SUBWAY = 103000100;
    private static final int NEW_LEAF_CITY = 600000000;
    private static final int HAK_TO_MU_LUNG = 200090300;
    private static final int ORBIS_TICKET = 4031045;
    private static final int SUBWAY_TICKET = 4031711;
    private static final Point HERE = new Point(100, 200);

    private final Character agent = mock(Character.class);
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final Map<Integer, Integer> items = new HashMap<>();
    private AgentRuntimeEntry entry;
    private int mapId;

    @BeforeEach
    void anAgentWithMesos() {
        when(agent.getName()).thenReturn("Rurik");
        when(agent.getMeso()).thenReturn(100_000);
        when(agent.getPosition()).thenReturn(HERE);
        when(agent.getMapId()).thenAnswer(inv -> mapId);
        when(gateway.grounded(agent)).thenReturn(true);
        when(gateway.npcPosition(any(), anyInt())).thenReturn(HERE); // every NPC stands right beside it
        when(gateway.itemCount(any(), anyInt())).thenAnswer(inv -> items.getOrDefault((int) inv.getArgument(1), 0));
        when(gateway.directPortalIdTo(any(), anyInt())).thenReturn(7); // walking steps head for a far portal
        when(gateway.portalPosition(any(), anyInt())).thenReturn(new Point(5_000, 200));
        entry = new AgentRuntimeEntry(agent, null, null);
    }

    @Test
    void atElliniaStationWithoutATicketItBuysOneFromJoelBeforeBoarding() {
        mapId = ELLINIA_STATION;
        when(gateway.runNpcScript(agent, 1032007, 0)).thenAnswer(inv -> items.put(ORBIS_TICKET, 1) == null);

        AgentVictoriaRouteRuntime.TravelOutcome outcome = travel(ORBIS, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        verify(gateway).runNpcScript(agent, 1032007, 0);
        verify(gateway, never()).runNpcScript(agent, 1032008, 0);
    }

    @Test
    void withTheTicketItBoardsTheBoatAndPausesOnArrivalAtOrbisStation() {
        mapId = ELLINIA_STATION;
        items.put(ORBIS_TICKET, 1);
        when(gateway.runNpcScript(agent, 1032008, 0)).thenAnswer(inv -> {
            mapId = ORBIS_STATION; // the boarding NPC's "instantly" warps at once
            return true;
        });

        AgentVictoriaRouteRuntime.TravelOutcome boarded = travel(ORBIS, 1_000L);
        AgentVictoriaRouteRuntime.TravelOutcome settling = travel(ORBIS, 1_100L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, boarded.status());
        assertEquals(ORBIS_STATION, boarded.nextMapId());
        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, settling.status());
        verify(gateway).stop(entry); // the visible arrival pause, as after a portal
    }

    @Test
    void atOrbisStationHeadingForLudibriumItBuysTheTrainTicketBeforeTakingThePlatform() {
        mapId = ORBIS_STATION;

        travel(LUDIBRIUM, 1_000L);

        verify(gateway).runNpcScript(agent, 2012000, 1, 0); // Agatha, the Ludibrium ticket
        verify(gateway, never()).runNpcScript(agent, 2012006, 1, 0); // not Isa yet
    }

    @Test
    void atTheTrainGateWithoutItsTicketItWalksBackToTheTicketSeller() {
        mapId = TRAIN_PLATFORM_GATE;

        AgentVictoriaRouteRuntime.TravelOutcome outcome = travel(LUDIBRIUM, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        assertEquals(AgentVictoriaTrainingRouteCatalog.nextHop(TRAIN_PLATFORM_GATE, ORBIS_STATION),
                outcome.nextMapId());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void onARideMapItWaitsForTheServerToSetItDown() {
        mapId = HAK_TO_MU_LUNG;

        AgentVictoriaRouteRuntime.TravelOutcome outcome = travel(250000000, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, outcome.status());
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void aClosedBoardingGateIsWaitedOutNotBlocked() {
        mapId = KERNING_SUBWAY;
        items.put(SUBWAY_TICKET, 1);
        when(gateway.runNpcScript(agent, 1052007, 2, 0)).thenReturn(true); // "boarding begins a minute before"

        for (long now = 1_000L; now <= 61_000L; now += 10_000L) {
            assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, travel(NEW_LEAF_CITY, now).status());
        }

        verify(gateway, times(7)).runNpcScript(agent, 1052007, 2, 0);
    }

    @Test
    void aClosedGateIsNotAskedAgainBeforeTheRetryDelay() {
        mapId = KERNING_SUBWAY;
        items.put(SUBWAY_TICKET, 1);
        when(gateway.runNpcScript(agent, 1052007, 2, 0)).thenReturn(true);

        travel(NEW_LEAF_CITY, 1_000L);
        travel(NEW_LEAF_CITY, 1_200L);
        travel(NEW_LEAF_CITY, 3_000L);

        verify(gateway, times(1)).runNpcScript(agent, 1052007, 2, 0);
    }

    @Test
    void anInstantRideThatKeepsRefusingIsBlockedAndTheRouterThenHasNoWay() {
        mapId = LUDIBRIUM_PLATFORM;
        items.put(ORBIS_TICKET, 1);
        when(gateway.runNpcScript(agent, 2041000, 0)).thenReturn(true); // talks, but never sends it anywhere

        travel(ORBIS, 1_000L);
        travel(ORBIS, 11_000L);
        AgentVictoriaRouteRuntime.TravelOutcome third = travel(ORBIS, 21_000L);
        AgentVictoriaRouteRuntime.TravelOutcome after = travel(ORBIS, 31_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.PORTAL_UNAVAILABLE, third.status());
        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, after.status());
    }

    @Test
    void ordinaryTravelOnlyWalksSoItNeverBoardsAnything() {
        mapId = ELLINIA_STATION;
        items.put(ORBIS_TICKET, 1);

        AgentVictoriaRouteRuntime.TravelOutcome outcome =
                AgentVictoriaRouteRuntime.travelStatus(entry, agent, ORBIS, gateway, 1_000L);

        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, outcome.status());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void onAPlatformWithoutItsTicketItGoesOnToTheSellerInsteadOfBackToTheGate() {
        mapId = TRAIN_PLATFORM;

        AgentVictoriaRouteRuntime.TravelOutcome outcome = travel(LUDIBRIUM, 1_000L);

        assertEquals(AgentVictoriaTrainingRouteCatalog.nextHop(TRAIN_PLATFORM, ORBIS_STATION), outcome.nextMapId());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void aJourneyItCannotPayForToTheEndIsNeverStarted() {
        mapId = ELLINIA_STATION;
        when(agent.getMeso()).thenReturn(6_000); // the boat ticket (5,000) but not the train's too (6,000)

        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, travel(LUDIBRIUM, 1_000L).status());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void anErrandToTheSellerNeverBoardsARideWhoseTicketItLacks() {
        mapId = LEAFRE_PLATFORM; // no walk leads off it: only the ride to Orbis, which needs a ticket

        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, travel(ORBIS, 1_000L).status());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void aScheduledRideThatNeverOpensCountsAgainstItAfterItsBoardingPatience() {
        mapId = KERNING_SUBWAY;
        items.put(SUBWAY_TICKET, 1);
        when(gateway.runNpcScript(agent, 1052007, 2, 0)).thenReturn(true); // the gate never opens
        long start = 1_000L;
        long late = start + boardingPatienceMs() + 1_000L;

        assertEquals(AgentVictoriaRouteRuntime.Status.MOVING, travel(NEW_LEAF_CITY, start).status());
        assertEquals(AgentVictoriaRouteRuntime.Status.PORTAL_UNAVAILABLE, travel(NEW_LEAF_CITY, late).status());
        travel(NEW_LEAF_CITY, late + 10_000L);
        AgentVictoriaRouteRuntime.TravelOutcome third = travel(NEW_LEAF_CITY, late + 20_000L);

        assertTrue(third.edgeBlocked());
        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, travel(NEW_LEAF_CITY, late + 30_000L).status());
    }

    @Test
    void withoutTheMesosForTheTicketThereIsNoRide() {
        mapId = ELLINIA_STATION;
        when(agent.getMeso()).thenReturn(4_999);

        assertEquals(AgentVictoriaRouteRuntime.Status.NO_ROUTE, travel(ORBIS, 1_000L).status());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
    }

    @Test
    void aHeldTicketNeedsNoMesosForItself() {
        mapId = ELLINIA_STATION;
        items.put(ORBIS_TICKET, 1);
        when(agent.getMeso()).thenReturn(0);

        travel(ORBIS, 1_000L);

        verify(gateway).runNpcScript(agent, 1032008, 0);
    }

    @Test
    void walkingStillWinsWhereverAPortalPathExists() {
        mapId = ELLINIA_STATION;

        AgentVictoriaRouteRuntime.TravelOutcome outcome = travel(100000000, 1_000L);

        assertEquals(AgentVictoriaTrainingRouteCatalog.nextHop(ELLINIA_STATION, 100000000), outcome.nextMapId());
        verify(gateway, never()).runNpcScript(any(), anyInt(), any(int[].class));
        verify(gateway, never()).itemCount(any(), anyInt());
    }

    /** Travel that may ride, with the whole purse to spend on fares and tickets. */
    private AgentVictoriaRouteRuntime.TravelOutcome travel(int destinationMapId, long nowMs) {
        return AgentVictoriaRouteRuntime.travelStatus(entry, agent, destinationMapId, gateway, nowMs,
                new AgentVictoriaRouteRuntime.RideBudget(agent.getMeso()));
    }

    private static long boardingPatienceMs() {
        return config.AgentTuning.longValue(
                "server.agents.progression.AgentVictoriaRouteRuntime.BOARDING_PATIENCE_MS");
    }
}

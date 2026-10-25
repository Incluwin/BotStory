package server.agents.progression;

import org.junit.jupiter.api.Test;
import server.agents.progression.AgentVictoriaRouteRuntime.PlannedStep;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole way a citizen walking to another map plans with (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md 2.2), on the shipped
 * route graphs and travel legs: portal hops while a walking path exists, and the boarding NPC of each leg where the way
 * crosses the sea.
 */
class AgentVictoriaRoutePlannedRouteTest {
    private static final int HENESYS = 100_000_000;
    private static final int ELLINIA = 101_000_000;
    private static final int ELLINIA_STATION = 101_000_300;
    private static final int ORBIS = 200_000_000;
    private static final int ORBIS_STATION = 200_000_100;
    private static final int CHERRY = 1_032_008;

    @Test
    void aWayOnTheIslandIsPortalHopsAlongTheGraph() {
        List<PlannedStep> route = AgentVictoriaRouteRuntime.plannedRoute(null, HENESYS, ELLINIA, 0L);

        assertFalse(route.isEmpty());
        assertEquals(HENESYS, route.get(0).fromMapId());
        assertEquals(ELLINIA, route.get(route.size() - 1).toMapId());
        for (int i = 0; i < route.size(); i++) {
            PlannedStep step = route.get(i);
            assertFalse(step.ride(), "Henesys to Ellinia is on foot");
            assertEquals(1, AgentVictoriaTrainingRouteCatalog.distance(step.fromMapId(), step.toMapId(), Set.of()),
                    () -> step + " is one portal of the graph");
            if (i > 0) {
                assertEquals(route.get(i - 1).toMapId(), step.fromMapId());
            }
        }
        assertEquals(AgentVictoriaTrainingRouteCatalog.distance(HENESYS, ELLINIA, Set.of()), route.size(),
                "as short as travelStatus walks it");
    }

    @Test
    void aWayAcrossTheSeaBoardsAtTheDock() {
        List<PlannedStep> route = AgentVictoriaRouteRuntime.plannedRoute(null, HENESYS, ORBIS, 0L);

        List<PlannedStep> rides = route.stream().filter(PlannedStep::ride).toList();
        assertEquals(1, rides.size(), () -> "one ship: " + route);
        assertEquals(new PlannedStep(ELLINIA_STATION, ORBIS_STATION, CHERRY), rides.get(0));
        assertEquals(ORBIS, route.get(route.size() - 1).toMapId());
        int ride = route.indexOf(rides.get(0));
        assertTrue(ride > 0, "it walks to Ellinia station first");
        assertEquals(ELLINIA_STATION, route.get(ride - 1).toMapId());
    }

    @Test
    void noWayIsEmpty() {
        assertEquals(List.of(), AgentVictoriaRouteRuntime.plannedRoute(null, HENESYS, HENESYS, 0L), "already there");
        assertEquals(List.of(), AgentVictoriaRouteRuntime.plannedRoute(null, HENESYS, 910_000_000, 0L),
                "the Free Market is on neither graph");
    }
}

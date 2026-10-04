package server.agents.progression;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paths that walk the portal graphs and ride the travel legs between them. */
class AgentTravelLegRouteTest {
    private static final int RIDE_HOP_COST = 10;

    @Test
    void crossesToLudibriumByTheElliniaBoatTheStationGuideAndTheOrbisTrain() {
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, 220000000, Set.of(), leg -> true, RIDE_HOP_COST);

        assertEquals(List.of("ellinia-to-orbis", "orbis-station-to-ludibrium-platform", "orbis-to-ludibrium"),
                legs(path));
        assertNull(path.get(0).leg(), "it walks from Henesys to Ellinia Station first");
        assertEquals(100000000, path.get(0).fromMapId());
        assertEquals(220000000, path.get(path.size() - 1).toMapId());
        for (int i = 1; i < path.size(); i++) {
            assertEquals(path.get(i - 1).toMapId(), path.get(i).fromMapId(), "the steps join up");
        }
    }

    @Test
    void walkingIsCheaperWhereAPortalPathExists() {
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                211000000, 200000000, Set.of(), leg -> true, RIDE_HOP_COST);

        assertEquals(List.of(), legs(path), "El Nath climbs Orbis Tower to Orbis");
        assertEquals(AgentVictoriaTrainingRouteCatalog.distance(211000000, 200000000), path.size());
    }

    @Test
    void hakFliesToMuLungAndOnToHerbTownRatherThanTheLongWalk() {
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                200000000, 251000000, Set.of(), leg -> true, RIDE_HOP_COST);

        assertEquals(List.of("orbis-station-to-mu-lung-platform", "orbis-to-mu-lung", "mu-lung-to-herb-town"),
                legs(path));
    }

    @Test
    void aLegItCannotUseIsLeftOutAndWithoutItThereIsNoWay() {
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(
                200000000, 220000000, Set.of(), leg -> !leg.id().equals("orbis-to-ludibrium"), RIDE_HOP_COST));
    }

    @Test
    void theRideMapsAreKnownAndNeverStartAWalk() {
        assertTrue(AgentTravelLegCatalog.rideMap(200090300), "Hak's flight to Mu Lung");
        assertTrue(AgentTravelLegCatalog.rideMap(600010004), "the Kerning subway's waiting room");
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(
                200090300, 250000000, Set.of(), leg -> true, RIDE_HOP_COST), "the rider waits; it plans nothing");
    }

    private static List<String> legs(List<AgentVictoriaTrainingRouteCatalog.Step> path) {
        return path.stream().map(AgentVictoriaTrainingRouteCatalog.Step::leg).filter(Objects::nonNull)
                .map(AgentTravelLegCatalog.Leg::id).toList();
    }
}

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
    void aRideWeighedHeavierThanTheWalkLeavesMuLungToHerbTownOnFoot() {
        int walk = AgentVictoriaTrainingRouteCatalog.distance(250000100, 251000000);
        assertTrue(walk > RIDE_HOP_COST, "Hak's crane beats the walk at the usual weight");

        assertEquals(List.of("mu-lung-to-herb-town"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                250000100, 251000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertEquals(List.of(), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                250000100, 251000000, Set.of(), leg -> true, walk + 1)));
    }

    @Test
    void reachesLukanOnPhantomRoadByTheSubwayTheNlcTaxiAndTheHauntedHouse() {
        List<AgentVictoriaTrainingRouteCatalog.Step> path = AgentVictoriaTrainingRouteCatalog.ridePath(
                103000000, 610010003, Set.of(), leg -> true, RIDE_HOP_COST);

        assertEquals(List.of("kerning-to-new-leaf-city", "new-leaf-city-to-haunted-house"), legs(path));
        assertEquals(610010003, path.get(path.size() - 1).toMapId());
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(103000000, 610010003, Set.of(),
                leg -> !leg.id().equals("new-leaf-city-to-haunted-house"), RIDE_HOP_COST),
                "without the taxi no portal leads from New Leaf City into the Phantom Forest");
    }

    @Test
    void reachesAmosTrainingGroundByThomasSwiftAmoriaAndPurplewoodForest() {
        List<AgentVictoriaTrainingRouteCatalog.Step> there = AgentVictoriaTrainingRouteCatalog.ridePath(
                101000000, 670010000, Set.of(), leg -> true, RIDE_HOP_COST);

        assertEquals(List.of("henesys-to-amoria"), legs(there), "Ellinia walks to Henesys and Thomas Swift");
        assertEquals(List.of(670000100, 670000200, 670010000),
                there.stream().map(AgentVictoriaTrainingRouteCatalog.Step::toMapId).toList()
                        .subList(there.size() - 3, there.size()), "through both Purplewood Forests");
        assertEquals(List.of("amoria-to-henesys"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                670010000, 100000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(100000000, 680000000, Set.of(),
                leg -> !leg.id().equals("henesys-to-amoria"), RIDE_HOP_COST), "no portal leads to Amoria");
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

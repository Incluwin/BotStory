package server.agents.progression;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void henesysReachesEveryTownTheTownAuditFoundCutOff() {
        Map<Integer, String> towns = Map.of(550000000, "Kuala Lumpur", 551000000, "Kampung Village",
                800000000, "Mushroom Shrine", 801000000, "Showa Town", 110000000, "Florina Beach",
                103040000, "Kerning Square", 300000000, "Altaire Camp", 270000100, "the Temple of Time",
                211040300, "Sharp Cliff I", 106020000, "the Mushroom Castle's forest field");
        towns.forEach((town, name) -> assertFalse(AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, town, Set.of(), leg -> true, RIDE_HOP_COST).isEmpty(), () -> "Henesys reaches " + name));
        towns.forEach((town, name) -> assertFalse(AgentVictoriaTrainingRouteCatalog.ridePath(
                town, 100000000, Set.of(), leg -> true, RIDE_HOP_COST).isEmpty(), () -> name + " reaches Henesys"));
    }

    @Test
    void malaysiaIsTheKerningPlaneAWalkToBoatQuayAndSpinelAndAudreyFliesBack() {
        assertEquals(List.of("kerning-to-singapore", "boat-quay-to-kuala-lumpur"), legs(
                AgentVictoriaTrainingRouteCatalog.ridePath(103000000, 551000000, Set.of(), leg -> true, RIDE_HOP_COST)),
                "Kampung Village is on foot from Kuala Lumpur");
        assertEquals(List.of("kuala-lumpur-to-boat-quay", "singapore-to-kerning"), legs(
                AgentVictoriaTrainingRouteCatalog.ridePath(551000000, 103000000, Set.of(), leg -> true, RIDE_HOP_COST)));
    }

    @Test
    void theTempleOfTimeIsADragonFlightFromLeafreStationAndTheWayBackIsFlown() {
        List<AgentVictoriaTrainingRouteCatalog.Step> there = AgentVictoriaTrainingRouteCatalog.ridePath(
                240000000, 270000100, Set.of(), leg -> true, RIDE_HOP_COST);
        assertEquals(List.of("leafre-station-to-the-temple-flight"), legs(there));
        assertEquals(List.of(200090500, 200090510, 270000100), there.stream()
                .map(AgentVictoriaTrainingRouteCatalog.Step::toMapId).toList().subList(there.size() - 3, there.size()));
        assertEquals(240000110, AgentVictoriaTrainingRouteCatalog.nextHop(200090500, 240000110),
                "minar00 turns the dragon back at Leafre Station");
        assertNotNull(AgentVictoriaTrainingRouteCatalog.nextHop(270000100, 240000000),
                "from the Temple the flight back is walked (flown) through portals alone");
        assertEquals(11, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(200090510, 270000100), "in00");
    }

    @Test
    void anEmptyHousesDoorOpensOnlyForTheMushroomCastleQuests() {
        long door = AgentVictoriaTrainingRouteCatalog.edgeKey(100000002, 106020000);
        Character newcomer = mock(Character.class);
        assertTrue(AgentVictoriaRouteRuntime.withGates(Set.of(), newcomer).contains(door));
        assertTrue(AgentVictoriaRouteRuntime.withGates(Set.of(), null).contains(door), "nobody to ask: shut");
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(100000000, 106020000,
                AgentVictoriaRouteRuntime.withGates(Set.of(), newcomer), leg -> true, RIDE_HOP_COST));

        Character done = mock(Character.class);
        when(done.getQuestStatus(2260)).thenReturn((byte) QuestStatus.Status.COMPLETED.getId());
        assertFalse(AgentVictoriaRouteRuntime.withGates(Set.of(), done).contains(door));
        Character started = mock(Character.class);
        when(started.getQuestStatus(2305)).thenReturn((byte) QuestStatus.Status.STARTED.getId());
        assertFalse(AgentVictoriaRouteRuntime.withGates(Set.of(), started).contains(door));
        Character halfway = mock(Character.class);
        when(halfway.getQuestStatus(2260)).thenReturn((byte) QuestStatus.Status.STARTED.getId());
        assertTrue(AgentVictoriaRouteRuntime.withGates(Set.of(), halfway).contains(door),
                "2260 counts only once it is completed");
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

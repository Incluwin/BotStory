package server.agents.progression;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.Test;
import server.living.config.Content;
import server.living.config.ContentLoader;
import server.living.resident.ResidentSpots;

import java.nio.file.Path;
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
    void everyRecruitsHomeTownIsReachableFromHenesysAndBack() {
        Map<String, Integer> homes = new java.util.TreeMap<>();
        for (Content.Persona persona : ContentLoader.load(Path.of("scripts/config/living-world")).personas().values()) {
            if (persona.recruitable()) {
                homes.put(persona.id() + " (" + persona.homeTown() + ")", ResidentSpots.townMapId(persona.homeTown()));
            }
        }
        for (String recruit : List.of("caedmon", "liesl", "hrafn", "maelis", "gaspard", "lianhua", "caius", "brynja")) {
            assertTrue(homes.keySet().stream().anyMatch(key -> key.startsWith(recruit + " (")), recruit);
        }
        homes.forEach((recruit, home) -> {
            assertTrue(home > 0, () -> recruit + " has a home map");
            if (home != 100000000) {
                assertFalse(AgentVictoriaTrainingRouteCatalog.ridePath(100000000, home, Set.of(), leg -> true,
                        RIDE_HOP_COST).isEmpty(), () -> "Henesys reaches " + recruit + "'s home " + home);
                assertFalse(AgentVictoriaTrainingRouteCatalog.ridePath(home, 100000000, Set.of(), leg -> true,
                        RIDE_HOP_COST).isEmpty(), () -> recruit + "'s home " + home + " reaches Henesys");
            }
        });
    }

    @Test
    void ereveIsTheSkyFerryFromElliniaOrOrbisAndRienTheWhaleFromLithHarbor() {
        List<AgentVictoriaTrainingRouteCatalog.Step> toEreve = AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, 130000000, Set.of(), leg -> true, RIDE_HOP_COST);
        assertEquals(List.of("ellinia-to-ereve"), legs(toEreve));
        assertEquals(List.of(130000210, 130000200, 130000100, 130000000), toEreve.stream()
                .map(AgentVictoriaTrainingRouteCatalog.Step::toMapId).toList().subList(toEreve.size() - 4, toEreve.size()),
                "off the dock, through the Crossroads' rankRoom door and the Knights' passage into town");
        assertEquals(27, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(101000000, 101000400), "Ellinia's in04");
        assertEquals(4, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(130000200, 130000100), "the Crossroads' east00");
        assertEquals(1, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(130000000, 130000100), "the town's west00");
        assertEquals(List.of("ereve-to-ellinia"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                130000000, 100000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertEquals(List.of("ereve-to-orbis"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                130000000, 200000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertEquals(List.of("orbis-station-to-ereve-platform", "orbis-to-ereve"), legs(AgentVictoriaTrainingRouteCatalog
                .ridePath(200000000, 130000000, Set.of(), leg -> true, RIDE_HOP_COST)));

        List<AgentVictoriaTrainingRouteCatalog.Step> toRien = AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, 140000000, Set.of(), leg -> true, RIDE_HOP_COST);
        assertEquals(List.of("lith-harbor-to-rien"), legs(toRien));
        assertEquals(List.of("rien-to-lith-harbor"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                140000000, 100000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertEquals(2, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(140020200, 140020300), "enterPort to the dock");
    }

    @Test
    void theDojoIsTheBoardInMuLungTempleAndNeverAWayToVictoria() {
        assertEquals(List.of("mu-lung-temple-to-the-dojo"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                250000000, 925020000, Set.of(), leg -> true, RIDE_HOP_COST)));
        List<AgentVictoriaTrainingRouteCatalog.Step> out = AgentVictoriaTrainingRouteCatalog.ridePath(
                925020000, 250000000, Set.of(), leg -> true, RIDE_HOP_COST);
        assertEquals(List.of("the-dojo-to-mu-lung-temple"), legs(out));
        List<String> toVictoria = legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                250000000, 100000000, Set.of(), leg -> true, RIDE_HOP_COST));
        assertFalse(toVictoria.contains("mu-lung-temple-to-the-dojo"),
                "the exit lands back in Mu Lung Temple, so the Dojo is no way to Henesys: " + toVictoria);
    }

    @Test
    void mapleIslandSailsToLithHarborOnlyAndHappyvilleIsRooneyInElliniaAndRupiBack() {
        assertEquals(List.of("southperry-to-lith-harbor"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                10000, 100000000, Set.of(), leg -> true, RIDE_HOP_COST)), "Mushroom Town walks the island road to Shanks");
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, 2000000, Set.of(), leg -> true, RIDE_HOP_COST), "nothing sails back to Maple Island");

        assertEquals(List.of("ellinia-to-happyville"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, 209000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertEquals(List.of("happyville-to-ellinia"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                209000000, 100000000, Set.of(), leg -> true, RIDE_HOP_COST)));
        assertFalse(legs(AgentVictoriaTrainingRouteCatalog.ridePath(240000000, 101000000, Set.of(), leg -> true,
                RIDE_HOP_COST)).contains("happyville-to-ellinia"), "Happyville is no shortcut to Ellinia");
    }

    @Test
    void everyArcaneRiverTownIsATempleKeepersDoorAndItsGuideLeadsBackToThreeDoors() {
        Map<Integer, String> towns = Map.of(450001000, "Nameless Town", 450002000, "Chu Chu Village",
                450003000, "Lachelein", 450005000, "Arcana", 450006130, "Trueffet Square",
                450007040, "Esfera's Base Camp", 450009050, "Moonbridge's Outpost",
                450011120, "the Labyrinth's Crash Site", 450012000, "World's Sorrow");
        towns.forEach((town, name) -> {
            List<String> there = legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                    100000000, town, Set.of(), leg -> true, RIDE_HOP_COST));
            assertFalse(there.isEmpty(), () -> "Henesys reaches " + name);
            assertTrue(there.get(there.size() - 1).startsWith("three-doors-to-"),
                    () -> name + " is the last door the Temple Keeper opens: " + there);
            List<String> back = legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                    town, 100000000, Set.of(), leg -> true, RIDE_HOP_COST));
            assertFalse(back.isEmpty(), () -> name + " reaches Henesys");
            assertTrue(back.get(0).endsWith("-to-three-doors"), () -> name + "'s guide leads back first: " + back);
        });
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(100000000, 450001000, Set.of(),
                leg -> leg.npcId() != 2140000, RIDE_HOP_COST), "no portal leads into Arcane River");
    }

    @Test
    void twilightPerionIsTheTempleKeepersDoorAndClaudineTheWayBackToThreeDoors() {
        List<String> there = legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                100000000, 273050000, Set.of(), leg -> true, RIDE_HOP_COST));
        assertEquals(List.of("leafre-station-to-the-temple-flight", "three-doors-to-twilight-perion"),
                there.subList(there.size() - 2, there.size()),
                "to the Tribal Refugee Camp by the Temple Keeper's door, not the Gate to the Future's roads");
        assertFalse(AgentVictoriaTrainingRouteCatalog.ridePath(273050000, 100000000, Set.of(), leg -> true,
                RIDE_HOP_COST).isEmpty(), "the camp reaches Henesys");

        assertEquals(7, AgentVictoriaTrainingRouteCatalog.distance(273000000, 270000000),
                "on foot the way back is the Gate to the Future's roads");
        assertEquals(List.of("twilight-perion-to-three-doors"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                273000000, 270000000, Set.of(), leg -> true, 1)), "Claudine sends the rider straight to Three Doors");
        assertEquals(List.of("twilight-perion-to-three-doors"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                273000000, 270000000, Set.of(AgentVictoriaTrainingRouteCatalog.edgeKey(273000000, 273010000)),
                leg -> true, RIDE_HOP_COST)), "and with the road out shut she is the only way");
    }

    @Test
    void theCaveOfLifeIsKerobensDoorForACertificateHolderAndTheEntrancesOut00TheWayBack() {
        AgentTravelLegCatalog.Leg keroben = AgentTravelLegCatalog.legs().stream()
                .filter(leg -> leg.id().equals("cave-of-life-entrance-to-the-cave")).findFirst().orElseThrow();
        Character outsider = mock(Character.class);
        when(outsider.getLevel()).thenReturn(120);
        Character young = mock(Character.class);
        when(young.getLevel()).thenReturn(99);
        when(young.haveItem(4001086)).thenReturn(true);
        Character member = mock(Character.class);
        when(member.getLevel()).thenReturn(100);
        when(member.haveItem(4001086)).thenReturn(true);
        assertFalse(AgentVictoriaRouteRuntime.letsOn(outsider, keroben), "no certificate");
        assertFalse(AgentVictoriaRouteRuntime.letsOn(young, keroben), "below level 100");
        assertTrue(AgentVictoriaRouteRuntime.letsOn(member, keroben));
        assertFalse(AgentVictoriaRouteRuntime.letsOn(null, keroben), "nobody to ask: shut");

        assertEquals(List.of("cave-of-life-entrance-to-the-cave"), legs(AgentVictoriaTrainingRouteCatalog.ridePath(
                240000000, 240050000, Set.of(), leg -> AgentVictoriaRouteRuntime.letsOn(member, leg), RIDE_HOP_COST)));
        assertEquals(List.of(), AgentVictoriaTrainingRouteCatalog.ridePath(240000000, 240050000, Set.of(),
                leg -> AgentVictoriaRouteRuntime.letsOn(outsider, leg), RIDE_HOP_COST), "no other way into the cave");

        assertEquals(240040600, AgentVictoriaTrainingRouteCatalog.nextHop(240040700, 240000000),
                "out00 (hontale_morph2) leads back up to the Peak of the Big Nest");
        assertEquals(2, AgentVictoriaTrainingRouteCatalog.scriptedPortalId(240040700, 240040600));
        assertNotNull(AgentVictoriaTrainingRouteCatalog.nextHop(240050000, 240000000), "the cave walks back out");
    }

    @Test
    void theTempleOfTimesRoadsOpenOneQuestAtATimeAsTheirPortalsDo() {
        Character newcomer = mock(Character.class);
        Set<Long> shut = AgentVictoriaRouteRuntime.withGates(Set.of(), newcomer);
        assertTrue(shut.contains(AgentVictoriaTrainingRouteCatalog.edgeKey(270010100, 270010110)));
        assertNotNull(AgentVictoriaTrainingRouteCatalog.nextHop(270000100, 270010100, shut), "Memory Lane 1 is open");
        assertNull(AgentVictoriaTrainingRouteCatalog.nextHop(270000100, 270010110, shut));

        Character walker = mock(Character.class);
        when(walker.getQuestStatus(3501)).thenReturn((byte) QuestStatus.Status.COMPLETED.getId());
        Set<Long> one = AgentVictoriaRouteRuntime.withGates(Set.of(), walker);
        assertFalse(one.contains(AgentVictoriaTrainingRouteCatalog.edgeKey(270010100, 270010110)));
        assertNotNull(AgentVictoriaTrainingRouteCatalog.nextHop(270000100, 270010200, one), "on to Memory Lane 2");
        assertNull(AgentVictoriaTrainingRouteCatalog.nextHop(270000100, 270010210, one), "Memory Lane 2's exit needs 3502");

        assertNotNull(AgentVictoriaTrainingRouteCatalog.nextHop(270000100, 270040100, Set.of()),
                "with every road's quest done the Temple Ruins are a walk");
        assertTrue(shut.contains(AgentVictoriaTrainingRouteCatalog.edgeKey(106020400, 106020501)),
                "Mushroom Castle's gate needs quest 2324");
        assertTrue(shut.contains(AgentVictoriaTrainingRouteCatalog.edgeKey(106020300, 106020400)),
                "the vines need quest 30000");
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

package server.living.townlife;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileValidator;
import server.agents.capabilities.townlife.AgentTownLifeState;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownLifeProfileGeneratorTest {
    @Test
    void regeneratingOrbisReproducesTheCommittedProfile() throws Exception {
        TownLifeProfileGenerator.Result result = TownLifeProfileGenerator.generate(
                TownMapData.load(200000000), "orbis", AgentTownLifeAdmissionMode.AMBIENT);

        assertTrue(result.ok(), () -> result.problems().toString());
        assertEquals(Files.readString(Path.of("src/main/resources/agents/town-life/orbis.json"), StandardCharsets.UTF_8),
                result.json(), "orbis.json is stale or generation is not deterministic");
    }

    @Test
    void refusesSwimMaps() {
        TownLifeProfileGenerator.Result result = TownLifeProfileGenerator.generate(
                TownMapData.load(230000000), "aquarium", AgentTownLifeAdmissionMode.AMBIENT);

        assertFalse(result.ok());
        assertTrue(result.problems().getFirst().startsWith("swim map"), () -> result.problems().toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {200000000, 220000000, 103000000})
    void generatesAValidProfileStandingOnTheMap(int mapId) throws Exception {
        TownMapData data = TownMapData.load(mapId);
        TownLifeProfileGenerator.Result result = TownLifeProfileGenerator.generate(
                data, "generated-town", AgentTownLifeAdmissionMode.AMBIENT);

        assertTrue(result.ok(), () -> result.problems().toString());
        AgentTownLifeProfile profile = new ObjectMapper().readValue(result.json(), AgentTownLifeProfile.class);
        AgentTownLifeProfileValidator.Validation validation = AgentTownLifeProfileValidator.validate(profile);
        assertTrue(validation.valid(), () -> validation.errors().toString());
        TownLifeGeometryCheck.Result check = TownLifeGeometryCheck.check(profile, data);
        assertTrue(check.valid(), () -> check.errors().toString());

        for (AgentTownLifeProfile.Venue venue : profile.venues()) {
            assertEquals(venue.spots().size(), venue.capacity(), venue.id());
        }
        for (AgentTownLifeState.Activity activity : List.of(AgentTownLifeState.Activity.REST,
                AgentTownLifeState.Activity.SOCIALIZE, AgentTownLifeState.Activity.LINGER,
                AgentTownLifeState.Activity.SHOW_OFF)) {
            assertFalse(profile.venuesFor(activity).isEmpty(), activity + " venue");
        }
        assertTrue(profile.venues().stream().anyMatch(venue -> venue.district() == AgentTownLifeState.District.LOWER),
                "the street level is the LOWER district");
        Set<String> labels = new HashSet<>();
        profile.venues().forEach(venue -> assertTrue(labels.add(venue.label()), "duplicate label " + venue.label()));
        for (AgentTownLifeProfile.ArrivalPortal portal : profile.arrivalPortals()) {
            assertNotNull(data.map().getPortal(portal.name()), portal.name());
        }

        List<Point> seats = new ArrayList<>();
        List<Point> standing = new ArrayList<>(profile.roamFallbackPoints());
        Set<Point> browseSpots = new HashSet<>();
        for (AgentTownLifeProfile.Venue venue : profile.venues()) {
            for (AgentTownLifeProfile.VenueSpot spot : venue.spots()) {
                (spot.seatId() >= 0 ? seats : standing).add(spot.point());
                if (venue.affordances().contains(AgentTownLifeProfile.Affordance.BROWSE)) {
                    browseSpots.add(spot.point());
                }
            }
        }
        // A facility approach is also its browse venue's spot; any other repeat is a stacked spot.
        profile.facilities().forEach(facility -> facility.approachPoints().stream()
                .map(AgentTownLifeProfile.PointSpec::point).filter(point -> !browseSpots.contains(point))
                .forEach(standing::add));
        for (AgentTownLifeProfile.NpcSpot spot : profile.npcSpots()) {
            TownMapData.Npc npc = data.npc(spot.npcId());
            assertFalse(npc.global(), npc.name() + " is a global NPC");
            standing.add(new Point(npc.x() + spot.offsetX(), npc.y()));
        }
        List<Point> points = new ArrayList<>(standing);
        points.addAll(profile.restPoints());
        for (Point point : points) {
            assertTrue(profile.allowsOccupancy(point), () -> point + " lies in an excluding zone");
        }
        List<Point> others = new ArrayList<>(seats);
        for (Point point : standing) {
            for (Point other : others) {
                assertFalse(Math.abs(point.x - other.x) < 40 && Math.abs(point.y - other.y) < 30,
                        () -> point + " stands on " + other);
            }
            others.add(point);
        }
    }
}

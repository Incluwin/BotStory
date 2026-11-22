package server.living.resident;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileRepository;
import server.living.config.Content;
import server.living.config.Content.RoutineSlot;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.resident.ResidentVenuePlacement.Claim;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No two Residents of one town stand on one point at the same hour (found 2026-11-08: Hayate stood under Fumiko at
 * the middle of the Ninja Castle street, so a double-click opened only the top one; also Brand/Eskil, Ottoline/Oren,
 * Yrsa/Vidar, Seori/Gwang). The venue spot came from {@code hash(id) % 7} alone.
 */
class ResidentVenuePlacementTest {
    /** Ninja Castle's middle-central-street: Fumiko and Hayate both hash to its third spot. */
    private static final List<Point> STREET = List.of(new Point(-646, 97), new Point(-561, 97), new Point(-476, 97));
    private static final Function<String, List<Point>> VENUES = id -> switch (id) {
        case "street" -> STREET;
        case "stall" -> List.of(new Point(-878, 60));
        default -> List.of();
    };
    private static final ResidentVenuePlacement.Ground FLAT = (x, aboveY) -> aboveY + ResidentVenuePlacement.GROUND_TOLERANCE_PX;

    private static RoutineSlot at(String spot, int from, int to) {
        return new RoutineSlot(from, to, spot, "LINGER", true);
    }

    private static Point place(String id, RoutineSlot slot, List<Claim> claims, ResidentVenuePlacement.Ground ground,
                               List<Point> npcs) {
        return ResidentVenuePlacement.place(id, slot, claims, VENUES, spot -> null, ground, npcs);
    }

    @Test
    void twoResidentsAtOneVenueInOverlappingHoursStandApartAndTheLowerIdKeepsItsHashedSpot() {
        assertEquals(2, ResidentVenuePlacement.hashedIndex("fumiko", 3));
        assertEquals(2, ResidentVenuePlacement.hashedIndex("hayate", 3), "the stacked pair of 2026-11-08");
        RoutineSlot fumiko = at("venue:street", 17, 22);
        RoutineSlot hayate = at("venue:street", 18, 23);
        List<Claim> claims = List.of(new Claim("hayate", hayate), new Claim("fumiko", fumiko));

        assertEquals(STREET.get(2), place("fumiko", fumiko, claims, FLAT, List.of()), "the lower id keeps its spot");
        assertEquals(STREET.get(0), place("hayate", hayate, claims, FLAT, List.of()), "the next free spot, wrapping");
    }

    @Test
    void residentsWhoseHoursNeverMeetKeepTheirHashedSpots() {
        RoutineSlot fumiko = at("venue:street", 6, 11);
        RoutineSlot hayate = at("venue:street", 18, 23);
        List<Claim> claims = List.of(new Claim("fumiko", fumiko), new Claim("hayate", hayate));

        assertEquals(STREET.get(2), place("hayate", hayate, claims, FLAT, List.of()));
        assertEquals(STREET.get(2), place("fumiko", fumiko, claims, FLAT, List.of()));
    }

    @Test
    void theSameContentGivesTheSamePointsInAnyOrderAndAnArrivalNeverMovesAnEarlierClaim() {
        List<Claim> claims = new ArrayList<>();
        for (String id : List.of("oharu", "goro", "fumiko", "hayate", "kenta", "masaru")) {
            claims.add(new Claim(id, at("venue:street", 17, 22)));
        }
        List<Claim> shuffled = new ArrayList<>(claims);
        Collections.reverse(shuffled);
        for (Claim claim : claims) {
            assertEquals(place(claim.personaId(), claim.slot(), claims, FLAT, List.of()),
                    place(claim.personaId(), claim.slot(), shuffled, FLAT, List.of()), claim.personaId());
        }

        List<Claim> two = List.of(claims.get(1), claims.get(2)); // goro, fumiko
        List<Claim> three = List.of(claims.get(1), claims.get(2), new Claim("zora", at("venue:street", 18, 20)));
        assertEquals(place("goro", claims.get(1).slot(), two, FLAT, List.of()),
                place("goro", claims.get(1).slot(), three, FLAT, List.of()), "a later id never moves an earlier one");
        assertEquals(place("fumiko", claims.get(2).slot(), two, FLAT, List.of()),
                place("fumiko", claims.get(2).slot(), three, FLAT, List.of()));
    }

    @Test
    void aFullVenueStepsAlongTheSameGroundAndNeverOntoAnotherResidentOrAStockNpc() {
        RoutineSlot oren = at("venue:stall", 16, 21);
        RoutineSlot ottoline = at("venue:stall", 17, 21);
        List<Claim> claims = List.of(new Claim("oren", oren), new Claim("ottoline", ottoline));
        Point stall = new Point(-878, 60);

        assertEquals(stall, place("oren", oren, claims, FLAT, List.of()));
        assertEquals(new Point(-833, 60), place("ottoline", ottoline, claims, FLAT, List.of()), "one step right");

        ResidentVenuePlacement.Ground ledgeOnTheRight = (x, aboveY) -> x > stall.x ? aboveY + 200 : aboveY + 12;
        assertEquals(new Point(-923, 60), place("ottoline", ottoline, claims, ledgeOnTheRight, List.of()),
                "a drop on the right: it steps left instead");

        assertEquals(new Point(-923, 60), place("ottoline", ottoline, claims, FLAT, List.of(new Point(-830, 58))),
                "a stock NPC on the right step: left instead");

        ResidentVenuePlacement.Ground nowhere = (x, aboveY) -> null;
        assertEquals(stall, place("ottoline", ottoline, claims, nowhere, List.of()),
                "no ground anywhere: it keeps its spot rather than stand in the air");
    }

    @Test
    void aFixedSpotInOverlappingHoursIsHeldAndKeepsItsPoint() {
        RoutineSlot fumiko = at("venue:street", 17, 22);
        RoutineSlot goro = at("rest:0", 18, 20);
        List<Claim> claims = List.of(new Claim("fumiko", fumiko), new Claim("goro", goro));
        Point rest = STREET.get(2);

        Point placed = ResidentVenuePlacement.place("fumiko", fumiko, claims, VENUES,
                spot -> spot.equals("rest:0") ? rest : null, FLAT, List.of());
        assertEquals(STREET.get(0), placed, "the authored rest spot keeps its Resident");
    }

    // ---- content: the five pairs of 2026-11-08, and every town ----------------------------------------

    @Test
    void theFiveStackedPairsOf20261108NowStandApart() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        String[][] pairs = {{"hayate", "fumiko"}, {"brand", "eskil"}, {"ottoline", "oren"}, {"yrsa", "vidar"},
                {"seori", "gwang"}};
        for (String[] pair : pairs) {
            Content.Persona a = content.persona(pair[0]);
            Content.Persona b = content.persona(pair[1]);
            assertEquals(a.homeTown(), b.homeTown(), pair[0] + "/" + pair[1]);
            boolean met = false;
            for (RoutineSlot slotA : a.routine()) {
                for (RoutineSlot slotB : b.routine()) {
                    if (!slotA.materialize() || !slotB.materialize() || !ResidentVenuePlacement.overlap(slotA, slotB)) {
                        continue;
                    }
                    met = true;
                    Point pa = placeInTown(content, a, slotA);
                    Point pb = placeInTown(content, b, slotB);
                    if (pa != null && pb != null) {
                        assertTrue(Math.abs(pa.x - pb.x) >= ResidentVenuePlacement.MIN_GAP_PX
                                        || Math.abs(pa.y - pb.y) >= ResidentVenuePlacement.MIN_GAP_PX,
                                pair[0] + " " + pa + " and " + pair[1] + " " + pb + " stand on one point");
                    }
                }
            }
            assertTrue(met, pair[0] + "/" + pair[1] + " share no hour any more: the test no longer covers them");
        }
    }

    @Test
    void noTwoResidentsOfOneTownStandOnOnePointAtOneHourOnLevelGround() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        List<String> stacked = new ArrayList<>();
        Map<String, Content.Persona> personas = content.personas();
        List<Content.Persona> residents = personas.values().stream()
                .filter(persona -> !LivingContent.isHire(persona.id()) && persona.homeTown() != null).toList();
        for (Content.Persona a : residents) {
            for (Content.Persona b : residents) {
                if (a.id().compareTo(b.id()) >= 0 || !a.homeTown().equals(b.homeTown())) {
                    continue;
                }
                for (RoutineSlot slotA : a.routine()) {
                    for (RoutineSlot slotB : b.routine()) {
                        if (!slotA.materialize() || !slotB.materialize()
                                || ResidentVenuePlacement.venueOf(slotA) == null
                                || ResidentVenuePlacement.venueOf(slotB) == null
                                || !ResidentVenuePlacement.overlap(slotA, slotB)) {
                            continue;
                        }
                        Point pa = placeInTown(content, a, slotA);
                        Point pb = placeInTown(content, b, slotB);
                        if (pa != null && pa.equals(pb)) {
                            stacked.add(a.homeTown() + " " + a.id() + "/" + b.id() + " " + pa);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), stacked);
    }

    /** As ResidentRuntime.spotFor places it, on level ground with no stock NPCs (the map's own is not loaded here). */
    private static Point placeInTown(LivingContent content, Content.Persona persona, RoutineSlot slot) {
        int mapId = ResidentSpots.townMapId(persona.homeTown());
        AgentTownLifeProfile profile = AgentTownLifeProfileRepository.defaultRepository().find(mapId).orElse(null);
        if (profile == null) {
            return null;
        }
        List<Claim> claims = new ArrayList<>();
        for (Content.Persona other : content.personas().values()) {
            if (!LivingContent.isHire(other.id()) && persona.homeTown().equals(other.homeTown())) {
                for (RoutineSlot each : other.routine()) {
                    claims.add(new Claim(other.id(), each));
                }
            }
        }
        return ResidentVenuePlacement.place(persona.id(), slot, claims, venue -> ResidentRuntime.venueSpots(profile, venue),
                spot -> restOrPoint(profile, spot), FLAT, List.of());
    }

    private static Point restOrPoint(AgentTownLifeProfile profile, String spot) {
        if (spot.startsWith("rest:") && !profile.restSpots().isEmpty()) {
            int index = Integer.parseInt(spot.substring(5).trim());
            return profile.restSpots().get(Math.floorMod(index, profile.restSpots().size())).point();
        }
        String[] xy = spot.split(",");
        return xy.length == 2 && !spot.contains(":")
                ? new Point(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())) : null;
    }

    @Test
    void theHashedIndexIsTheOneResidentsHadBefore() {
        for (String id : List.of("hayate", "fumiko", "brand", "eskil", "seori", "gwang")) {
            assertEquals(Math.floorMod(Math.floorMod(id.hashCode(), 7), 4), ResidentVenuePlacement.hashedIndex(id, 4));
        }
        assertNotEquals(ResidentVenuePlacement.hashedIndex("seori", 4), ResidentVenuePlacement.hashedIndex("gwang", 4));
    }
}

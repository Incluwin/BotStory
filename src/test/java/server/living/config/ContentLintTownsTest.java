package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.Content.Rumor;
import server.living.config.Content.RoutineSlot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residents can live in any town with a TownLife profile (docs/REGIONAL_RESIDENTS_PLAN.md), and every spot their
 * routines and rumors name must be in that town's profile: one that is not puts the Resident at the fallback spot
 * without a word.
 */
class ContentLintTownsTest {
    @Test
    void everyTownLifeProfileIsAHomeTown() {
        for (String town : List.of("lith-harbor", "henesys", "kerning-city", "perion", "ellinia", "nautilus", "sleepywood",
                "orbis", "el-nath", "ludibrium", "leafre", "mu-lung")) {
            assertTrue(ContentLint.KNOWN_TOWNS.contains(town), town);
        }
    }

    @Test
    void spotsResolveInTheirTownsProfile() {
        assertNull(ContentLint.unresolvedSpot("orbis", "venue:by-trina"));
        assertNull(ContentLint.unresolvedSpot("sleepywood", "hotspot:sleepywood-rest-crowd"));
        assertNull(ContentLint.unresolvedSpot("orbis", "rest:3"));
        assertNull(ContentLint.unresolvedSpot("orbis", "npc:2010006"));
        assertNull(ContentLint.unresolvedSpot("orbis", "120, -40"));
        assertNull(ContentLint.unresolvedSpot("orbis", null), "an away slot names no spot");
        assertNull(ContentLint.unresolvedSpot("atlantis", "venue:anything"), "an unknown town is reported on its own");
        assertEquals("spot 'venue:central-camp' is not in the orbis TownLife profile",
                ContentLint.unresolvedSpot("orbis", "venue:central-camp"));
        assertEquals("spot 'hotspot:nowhere' is not in the el-nath TownLife profile",
                ContentLint.unresolvedSpot("el-nath", "hotspot:nowhere"));
        assertEquals("spot 'bench:2' is not in the ludibrium TownLife profile", ContentLint.unresolvedSpot("ludibrium", "bench:2"));
        assertEquals("spot 'by the fountain' is neither x,y nor kind:id", ContentLint.unresolvedSpot("ludibrium", "by the fountain"));
    }

    @Test
    void theLintReportsRoutineAndRumorSpotsThatDoNotResolve() {
        Map<String, Persona> personas = new LinkedHashMap<>();
        personas.put("aurel", persona("aurel", "orbis", List.of(
                new RoutineSlot(8, 20, "venue:station-entrance-door", "LINGER", true),
                new RoutineSlot(20, 23, "venue:hotel-approach", "REST", true),
                new RoutineSlot(23, 8, null, null, false))));
        personas.put("brisk", persona("brisk", "orbis", List.of(new RoutineSlot(0, 24, "venue:by-trina", "LINGER", true))));
        Rumor rumor = new Rumor("r1", List.of("aurel", "brisk"), "orbis", "venue:nowhere", List.of(12), 1, null,
                List.of(new Content.RumorLine("aurel", "hi")), null, "test");
        LivingContent content = new LivingContent(personas, Map.of(), Map.of(), Map.of(), Map.of("r1", rumor), Map.of(),
                List.of(), Map.of(), Map.of(), List.of());

        List<String> errors = ContentLint.check(content).stream().filter(finding -> finding.startsWith("ERROR")).toList();

        assertEquals(List.of(
                "ERROR test persona aurel: routine spot 'venue:hotel-approach' is not in the orbis TownLife profile",
                "ERROR test rumor r1: spot 'venue:nowhere' is not in the orbis TownLife profile"), errors);
    }

    private static Persona persona(String id, String town, List<RoutineSlot> routine) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(30, 30), Content.Strength.ROOKIE, null, "relaxed-v1",
                null, town, routine, false, null, List.of(), null, List.of(), List.of(),
                Map.of("greet", List.of("hi")), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test");
    }
}

package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.config.Content.Spouse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lore couples (docs/BOT_LORE_COUPLES_PLAN.md, user decisions 1-2): {@code spouse:} names another persona, who names this
 * one back, or a citizen spouse by name and gender; either way the two genders are written and opposite (stock
 * marriages assume a husband and a wife). The shipped couples load and lint clean.
 */
class ContentLintSpouseTest {

    @Test
    void theShippedCouplesLoadPairedAndLintClean() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));

        Map<String, String> couples = Map.of("sigrun", "bjarke", "saskia", "jasper", "vesna", "ansel", "magna", "thane",
                "brunhild", "corvin", "mae", "kettle");
        couples.forEach((wife, husband) -> {
            assertEquals(husband, content.persona(wife).spouse().persona(), wife);
            assertEquals(wife, content.persona(husband).spouse().persona(), husband);
        });
        Spouse rowena = content.persona("gunnar").spouse();
        assertTrue(rowena.citizen());
        assertEquals("Rowena", rowena.name());
        assertEquals("female", rowena.gender());
        assertNull(content.persona("edric").spouse(), "an unmarried persona has none");
        assertTrue(content.warnings().stream().noneMatch(finding -> finding.contains("spouse")), content.warnings().toString());
        assertFalse(ContentLint.hasErrors(content.warnings()), content.warnings().toString());
    }

    @Test
    void aPersonaSpouseMustNameThisOneBackWithTheOtherGender() {
        List<String> findings = lint(
                persona("ada", "female", new Spouse("bo", null, null)),
                persona("bo", "female", null),
                persona("cy", "male", new Spouse("nobody", null, null)),
                persona("di", null, new Spouse("cy", null, null)));

        assertTrue(findings.contains("ERROR test persona ada: spouse 'bo' does not name ada back"), findings.toString());
        assertTrue(findings.contains("ERROR test persona ada: spouse 'bo' has the same gender"), findings.toString());
        assertTrue(findings.contains("ERROR test persona cy: spouse 'nobody' not found"), findings.toString());
        assertTrue(findings.contains("ERROR test persona di: a married persona needs look.gender"), findings.toString());
    }

    @Test
    void aMutualPairOfOppositeGendersLintsClean() {
        List<String> findings = lint(
                persona("ada", "female", new Spouse("bo", null, null)),
                persona("bo", "male", new Spouse("ada", null, null)));

        assertTrue(findings.stream().noneMatch(finding -> finding.contains("spouse")), findings.toString());
    }

    @Test
    void aCitizenSpouseNeedsACharacterNameAndTheOtherGender() {
        List<String> findings = lint(
                persona("ada", "female", new Spouse(null, "Al", "male")),
                persona("bo", "male", new Spouse(null, "Rowena", "male")),
                persona("cy", "male", new Spouse(null, "Ada", "female")),
                persona("di", "male", new Spouse(null, "Rowena", "female")));

        assertTrue(findings.contains("ERROR test persona ada: citizen spouse name 'Al' must be 4-12 letters"), findings.toString());
        assertTrue(findings.contains("ERROR test persona bo: citizen spouse 'Rowena' has the same gender"), findings.toString());
        assertTrue(findings.contains("ERROR test persona cy: citizen spouse name 'Ada' is already persona ada"), findings.toString());
        assertTrue(findings.contains("ERROR test persona di: citizen spouse name 'Rowena' is already the citizen spouse of bo"),
                findings.toString());
    }

    @Test
    void spouseYamlTakesAPersonaIdOrACitizen() {
        Node byId = Node.root(Map.of("spouse", "bjarke"), "test").get("spouse");
        Node citizen = Node.root(Map.of("spouse", Map.of("name", "Rowena", "gender", "Female")), "test").get("spouse");

        assertEquals(new Spouse("bjarke", null, null), ContentLoader.spouse(byId));
        assertEquals(new Spouse(null, "Rowena", "female"), ContentLoader.spouse(citizen));
        assertNull(ContentLoader.spouse(Node.root(Map.of(), "test").get("spouse")));
    }

    private static List<String> lint(Persona... personas) {
        Map<String, Persona> byId = new LinkedHashMap<>();
        for (Persona persona : personas) {
            byId.put(persona.id(), persona);
        }
        LivingContent content = new LivingContent(byId, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                Map.of(), Map.of(), List.of());
        return ContentLint.check(content);
    }

    private static Persona persona(String id, String gender, Spouse spouse) {
        String name = id.substring(0, 1).toUpperCase() + id.substring(1);
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(),
                Map.of("greet", List.of("hi")), -1, -1, -1, List.of(), 0, new Content.Look(gender, -1, 0, 0), "test",
                spouse);
    }
}

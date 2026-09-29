package server.living.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Arc;
import server.living.config.Content.Contract;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Rumor;
import server.living.config.Content.Step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hired citizens' ids ({@code hire:<characterId>}, docs/BOT_MANAGEMENT_PLAN.md P4, the risk row on hire ids) are never
 * content: the lint refuses an authored persona with that prefix and finds no persona behind a reference to one, without
 * an exception and without ever asking the installed hire resolver; the shipped hired-hand contract lints clean although
 * no persona lists it.
 */
class ContentLintHireIdsTest {
    private static final String HIRE = "hire:12";

    @AfterEach
    void uninstall() {
        LivingContent.installHireResolver(null);
    }

    @Test
    void theShippedHiredHandContractLintsCleanThoughNoPersonaListsIt() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));

        Contract contract = content.contract(LivingContent.HIRED_HAND_CONTRACT);
        assertNotNull(contract);
        assertEquals(1_000L, contract.price().meso(), "the fee per citizen level");
        assertEquals(0, contract.terms().durationHours());
        assertEquals(0, contract.terms().maxLevelGap(), "no outlevel walk-out");
        assertEquals(List.of(Content.LeaveRule.DISMISS), contract.terms().leavesOn());
        assertTrue(content.personas().values().stream().noneMatch(persona -> LivingContent.isHire(persona.id())));
        assertTrue(content.warnings().stream().noneMatch(finding -> finding.contains(LivingContent.HIRED_HAND_CONTRACT)),
                content.warnings().toString());
        assertFalse(ContentLint.hasErrors(content.warnings()), content.warnings().toString());
    }

    @Test
    void hireIdsAreNeverContentAndReferencesToThemAreNotFound() {
        List<String> asked = new ArrayList<>();
        LivingContent.installHireResolver(id -> {
            asked.add(id);
            return persona(id, List.of());
        });
        Map<String, Persona> personas = new LinkedHashMap<>();
        personas.put("olwen", persona("olwen", List.of(new Content.Relationship(HIRE, "friend"))));
        personas.put(HIRE, persona(HIRE, List.of()));
        Step step = new Step("s1", Content.StepType.DELIVER, List.of(), 0, 1, List.of(), List.of(), HIRE, HIRE, null, 0, 0, 0,
                null, HIRE, null, null);
        Quest quest = new Quest("q1", HIRE, "Q", Content.QuestLength.SHORT, false,
                new Content.Requirements(1, 200, Content.BondStage.NONE, List.of(), List.of(), List.of()), List.of(step),
                new Content.Reward(0, 0, List.of(), 0, List.of(), 0, List.of()), null, "test");
        Rumor rumor = new Rumor("r1", List.of("olwen", HIRE), "lith-harbor", null, List.of(12), 1, null,
                List.of(new Content.RumorLine("olwen", "hi")), null, "test");
        Arc arc = new Arc("a1", HIRE, List.of(), "test");
        LivingContent content = new LivingContent(personas, Map.of("q1", quest), Map.of(), Map.of("a1", arc),
                Map.of("r1", rumor), Map.of(), List.of(), Map.of(), Map.of(), List.of());

        List<String> findings = ContentLint.check(content);

        assertTrue(findings.contains("ERROR test persona " + HIRE + ": 'hire:' ids are hired citizens, never content"), findings.toString());
        assertTrue(findings.contains("ERROR test quest q1: persona '" + HIRE + "' not found"), findings.toString());
        assertTrue(findings.contains("ERROR test quest q1 step s1: persona '" + HIRE + "' not found"), findings.toString());
        assertTrue(findings.contains("ERROR test quest q1 step s1: with persona '" + HIRE + "' not found"), findings.toString());
        assertTrue(findings.contains("ERROR test rumor r1: participant '" + HIRE + "' not found"), findings.toString());
        assertTrue(findings.contains("ERROR test arc a1: persona '" + HIRE + "' not found"), findings.toString());
        assertTrue(findings.contains("WARN test persona olwen: relationship to unknown persona '" + HIRE + "'"), findings.toString());
        assertEquals(List.of(), asked, "the lint never resolves a hire id");
        assertEquals(HIRE, content.persona(HIRE).id(), "the runtime lookup goes to the resolver");
    }

    @Test
    void aHireIdNamesItsCharacter() {
        assertTrue(LivingContent.isHire("hire:950"));
        assertEquals(950, LivingContent.hireCharacterId("hire:950"));
        assertFalse(LivingContent.isHire("olwen"));
        assertEquals(0, LivingContent.hireCharacterId("olwen"));
        assertEquals(0, LivingContent.hireCharacterId("hire:"));
        assertEquals(0, LivingContent.hireCharacterId("hire:x9"));
        assertFalse(LivingContent.isHire(null));
    }

    private static Persona persona(String id, List<Content.Relationship> relationships) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null, "relaxed-v1",
                null, "lith-harbor", List.of(), false, null, List.of(), null, relationships, List.of(),
                Map.of("greet", List.of("hi")), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test");
    }
}

package server.living.config;

import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.knowledge.WorldKnowledge;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The living-world lint with server knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 9.4): the shipped content has no ERROR (so
 * MapleEdit saves are never blocked by it), unknown ids are ERRORs naming their kind, a quest item nobody can farm warns
 * once unless the quest drops it itself, gear above a persona's band warns, and without knowledge the lint is unchanged.
 */
class ContentLintKnowledgeTest {
    private static final int FAIRY_WING = 4003003;

    @Test
    void shippedContentHasNoErrorsWithKnowledge() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            List<String> findings = ContentLoader.load(Path.of("scripts/config/living-world")).warnings();

            assertFalse(ContentLint.hasErrors(findings), findings.toString());
            assertEquals(1, findings.stream().filter(f -> f.contains("item 4003003 Fairy Wing drops only from")).count(),
                    findings.toString());
        }
    }

    @Test
    void scriptOnlyQuestItemWarnsOnceAndDropFromSilencesIt() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            List<String> findings = knowledgeFindings(ContentLint.check(content(wingQuest(null), null)));
            assertEquals(1, findings.size(), findings.toString());
            assertTrue(findings.get(0).startsWith("WARN test quest q1 step wing: item 4003003 Fairy Wing drops only from"),
                    findings.get(0));

            Content.DropRule dropped = new Content.DropRule(List.of(100100), 0.5);
            assertEquals(List.of(), knowledgeFindings(ContentLint.check(content(wingQuest(dropped), null))));
        }
    }

    @Test
    void unknownIdsAreErrorsNamingTheirKind() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            Step kill = step("kill", StepType.KILL_MOBS, 0, List.of(7777777), List.of(), null, null);
            Step visit = step("visit", StepType.VISIT_MAP, 0, List.of(), List.of(999999998), null, null);
            Quest quest = quest(List.of(kill, visit), List.of(new Content.ItemStack(9999998, 1)));
            Content.Loadout loadout = new Content.Loadout("x", List.of(9999997), List.of(), Map.of(), Map.of());

            List<String> findings = ContentLint.check(content(quest, loadout));

            List<String> unknown = findings.stream().filter(f -> f.endsWith("does not exist in the server WZ")).toList();
            assertEquals(List.of("ERROR test quest q1 step kill: mob 7777777 does not exist in the server WZ",
                    "ERROR test quest q1 step visit: map 999999998 does not exist in the server WZ",
                    "ERROR test quest q1 rewards: item 9999998 does not exist in the server WZ",
                    "ERROR loadouts.yaml loadout x: item 9999997 does not exist in the server WZ"), unknown);
            assertTrue(ContentLint.hasErrors(findings));
        }
    }

    @Test
    void withoutKnowledgeTheLintIsUnchanged() throws Exception {
        assertFalse(WorldKnowledge.current().ready(), "no snapshot is published outside publishSeed");
        Content.Loadout loadout = new Content.Loadout("x", List.of(9999997), List.of(), Map.of(), Map.of("hat", List.of(1302000)));
        LivingContent content = content(wingQuest(null), loadout);

        List<String> without = ContentLint.check(content);
        assertEquals(List.of(), knowledgeFindings(without), without.toString());
        assertTrue(without.contains("ERROR loadouts.yaml loadout x: outfit hat item 1302000 is not a hat item"), without.toString());

        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            List<String> with = ContentLint.check(content);
            assertTrue(with.contains("ERROR loadouts.yaml loadout x: outfit hat item 1302000 Sword is not a hat item"), with.toString());
            assertEquals(without.size() + 2, with.size(), "the Fairy Wing source and the unknown equip: " + with);
        }
    }

    @Test
    void loadoutAboveThePersonaBandWarns() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            Content.Loadout hat = new Content.Loadout("x", List.of(1002026), List.of(), Map.of(), Map.of());
            List<String> findings = knowledgeFindings(ContentLint.check(content(null, hat)));

            assertEquals(List.of("WARN loadouts.yaml loadout x: equip 1002026 Brown Bamboo Hat needs level 25, above persona p "
                    + "(levels 6-8); it is carried, never worn"), findings);
        }
    }

    /** Findings only the knowledge checks make. */
    private static List<String> knowledgeFindings(List<String> findings) {
        return findings.stream().filter(f -> f.contains(" in the server WZ") || f.contains(": item ") && f.contains(" drops ")
                || f.contains(": equip ") || f.contains(": mob ")).toList();
    }

    /** COLLECT Fairy Wing (optionally dropped by the quest itself), then hand it over. */
    private static Quest wingQuest(Content.DropRule dropFrom) {
        Step wing = step("wing", StepType.COLLECT_ITEM, FAIRY_WING, List.of(), List.of(), null, dropFrom);
        Step back = step("return", StepType.DELIVER, 0, List.of(), List.of(), new Content.ItemStack(FAIRY_WING, 1), null);
        return quest(List.of(wing, back), List.of());
    }

    private static Step step(String id, StepType type, int itemId, List<Integer> mobIds, List<Integer> mapIds,
                             Content.ItemStack take, Content.DropRule dropFrom) {
        return new Step(id, type, List.of(), itemId, 1, mobIds, mapIds, type == StepType.DELIVER ? "p" : null, null, null,
                0, 0, 0, dropFrom, null, take, null);
    }

    private static Quest quest(List<Step> steps, List<Content.ItemStack> rewardItems) {
        return new Quest("q1", "p", "Q", Content.QuestLength.SHORT, false,
                new Content.Requirements(1, 200, Content.BondStage.NONE, List.of(), List.of(), List.of()), steps,
                new Content.Reward(0, 0, rewardItems, 0, List.of(), 0, List.of()), null, "test");
    }

    /** One persona "p" at levels 6-8 giving {@code quest} (if any) and wearing {@code loadout} (if any). */
    private static LivingContent content(Quest quest, Content.Loadout loadout) {
        Map<String, Persona> personas = new LinkedHashMap<>();
        personas.put("p", new Persona("p", "P", "", "BEGINNER", new Content.LevelBand(6, 8), Content.Strength.ROOKIE,
                loadout == null ? null : loadout.id(), "relaxed-v1", null, "lith-harbor", List.of(), false, null,
                quest == null ? List.of() : List.of(quest.id()), null, List.of(), List.of(), Map.of("greet", List.of("hi")),
                -1, -1, -1, List.of(), 0, Content.Look.NONE, "test"));
        return new LivingContent(personas, quest == null ? Map.of() : Map.of(quest.id(), quest), Map.of(), Map.of(), Map.of(),
                loadout == null ? Map.of() : Map.of(loadout.id(), loadout), List.of(), Map.of(), Map.of(), new ArrayList<>());
    }
}

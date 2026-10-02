package server.living.config;

import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeLint;
import server.knowledge.KnowledgeTestKit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Personas' {@code knows:} lists (docs/SERVER_KNOWLEDGE_PLAN.md 7.7): every shipped entry is "prices" or a name the knowledge
 * layer resolves by alias or exact name (the content lint WARN stays silent), and LivingContent hands them back in order.
 */
class PersonaKnowsResolveTest {
    @Test
    void everyShippedEntryResolves() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
            KnowledgeLint lint = KnowledgeLint.current();
            assertNotNull(lint);
            List<String> findings = new ArrayList<>();
            int entries = 0;
            for (String persona : content.personas().keySet()) {
                for (String known : content.knows(persona)) {
                    lint.personaKnows(known, persona, findings);
                    entries++;
                }
            }
            assertEquals(List.of(), findings);
            assertTrue(entries >= 14, entries + " entries");
            assertTrue(content.warnings().stream().noneMatch(w -> w.contains(" knows '")), content.warnings().toString());
            assertEquals(List.of("prices", "free market"), content.knows("tavi"));
            assertEquals(List.of("ellinia", "perion", "kerning city", "sleepywood", "lith harbor", "nautilus"), content.knows("sable"));
            assertEquals(List.of(), content.knows("fenna"));
        }
    }

    @Test
    void anEntryThatNamesNothingWarns() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            List<String> findings = new ArrayList<>();
            KnowledgeLint.current().personaKnows("the moon kingdom of zzz", "henesys.yaml persona x", findings);
            assertEquals(1, findings.size());
            assertTrue(findings.get(0).startsWith("WARN henesys.yaml persona x: knows 'the moon kingdom of zzz'"), findings.get(0));
        }
    }
}

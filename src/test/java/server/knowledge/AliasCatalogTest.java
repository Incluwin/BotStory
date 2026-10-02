package server.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aliases.yaml against the generated knowledge file (docs/SERVER_KNOWLEDGE_PLAN.md 5.2): every id exists for
 * its kind (nothing dropped at load), every alias resolves back to its own entity, every role finds an NPC,
 * and every spoken name passes the reply lint (ASCII, no brackets, colons or long ids).
 */
class AliasCatalogTest {
    private static final Pattern LINT = Pattern.compile("[\\[<>:]|\\d{5,}");

    @Test
    void everyIdExistsInTheKnowledgeFile() throws Exception {
        AliasCatalog catalog = KnowledgeFixtures.aliases();
        assertEquals(List.of(), catalog.dropped, "aliases.yaml names ids the knowledge file does not have");
        assertTrue(catalog.aliases.size() >= 220, "the draft's entries plus roles, PQs and interiors");
        assertEquals(5, catalog.jobs.size());
        byte[] raw = Files.readAllBytes(KnowledgeFixtures.DIR.resolve("aliases.yaml"));
        assertEquals(new String(raw, StandardCharsets.US_ASCII), new String(raw, StandardCharsets.UTF_8), "US-ASCII only");
    }

    @Test
    void everyAliasResolvesToItsOwnEntity() {
        NameIndex names = SeedOverlay.knowledge().names();
        List<String> wrong = new ArrayList<>();
        for (AliasCatalog.Alias alias : KnowledgeFixtures.aliases().aliases) {
            for (String name : alias.names()) {
                NameIndex.Resolution resolution = names.resolve(name, List.of(alias.kind()));
                Entity best = resolution.best().map(NameIndex.Candidate::entity).orElse(null);
                boolean same = best != null && best.kind() == alias.kind()
                        && (alias.ids().isEmpty() || alias.ids().getInt(0) == best.id() || best.ids().equals(alias.ids()));
                if (!same || !"alias".equals(resolution.best().get().how())) {
                    wrong.add(name + " -> " + resolution);
                }
            }
            assertTrue(!LINT.matcher(alias.say()).find() && alias.say().equals(alias.say().toLowerCase()),
                    "say name must be lowercase and lint-clean: " + alias.say());
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void everyRoleFindsAnNpcOnAMap() {
        NameIndex names = SeedOverlay.knowledge().names();
        for (AliasCatalog.Alias alias : KnowledgeFixtures.aliases().aliases) {
            if (alias.kind() == EntityKind.ROLE) {
                Entity role = names.resolve(alias.names().get(0), List.of(EntityKind.ROLE)).best().orElseThrow().entity();
                assertTrue(!role.ids().isEmpty(), alias.say() + " has no NPC");
            }
        }
    }
}

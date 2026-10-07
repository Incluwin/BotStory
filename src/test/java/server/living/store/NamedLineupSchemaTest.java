package server.living.store;

import liquibase.util.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 46 (companion_named_lineup, P9's named lineups): registered after 45 with its rollback; one row per place
 * of an owner's Hunting, PQ or Boss lineup, keyed by owner, lineup and place; no foreign key; ASCII only, split by
 * Liquibase into exactly that one statement each way; and only LivingStore names the table in SQL.
 */
class NamedLineupSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/037-companion-named-lineups.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/037-companion-named-lineups-down.sql");

    @Test
    void changeset46IsRegisteredAfter45WithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"46-companion-named-lineups\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"45-living-knowledge\""), "after 45");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/037-companion-named-lineups.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/037-companion-named-lineups-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"46-"), changelog.lastIndexOf("<changeSet id=\"46-"), "once");
    }

    @Test
    void theTableKeepsEachPlaceOfEachLineup() throws IOException {
        String up = Files.readString(UP);
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS companion_named_lineup ("));
        for (String column : List.of("owner_character_id INT          NOT NULL", "lineup             VARCHAR(8)   NOT NULL",
                "slot               TINYINT      NOT NULL", "persona_id         VARCHAR(64)  NOT NULL",
                "saved_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP",
                "PRIMARY KEY (owner_character_id, lineup, slot)")) {
            assertTrue(up.contains(column), column);
        }
        String statements = up.lines().filter(line -> !line.strip().startsWith("--")).reduce("", String::concat);
        assertFalse(statements.toUpperCase().contains("REFERENCES"), "no foreign key, as living_roster");
        assertTrue(up.chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void liquibaseRunsExactlyTheOneStatementEachWay() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(1, up.length, String.join("\n;;\n", up));
        assertTrue(StringUtil.stripComments(up[0]).strip().startsWith("CREATE TABLE IF NOT EXISTS companion_named_lineup"));
        assertEquals(1, down.length, String.join("\n;;\n", down));
        assertEquals("DROP TABLE IF EXISTS companion_named_lineup", StringUtil.stripComments(down[0]).strip());
    }

    @Test
    void onlyLivingStoreTalksToTheTable() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+companion_named_lineup\\b[^\"]*\"");
        Set<String> files = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (sql.matcher(Files.readString(source)).find()) {
                    files.add(source.toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(Set.of("src/main/java/server/living/store/LivingStore.java"), files);
    }
}

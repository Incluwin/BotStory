package server.living.population;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 49 (citizens remember the people they meet): registered after 47 with its rollback; living_acquaintance
 * gains the memory columns and the prune's index beside the shared minutes, in one ALTER each way, ASCII only; and only
 * CitizenStore writes them.
 */
class CitizenPersonMemorySchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/040-citizen-person-memory.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/040-citizen-person-memory-down.sql");

    @Test
    void changeset49IsRegisteredAfter47WithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"49-citizen-person-memory\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"47-character-storage-bag\""), "after 47");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/040-citizen-person-memory.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/040-citizen-person-memory-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"49-"), changelog.lastIndexOf("<changeSet id=\"49-"), "once");
    }

    @Test
    void theAcquaintanceRowGainsTheMemoryColumnsAndThePrunesIndex() throws IOException {
        String up = Files.readString(UP);
        for (String column : List.of("ADD COLUMN warmth       SMALLINT     NOT NULL DEFAULT 0",
                "ADD COLUMN memory_at    TIMESTAMP    NULL", "ADD COLUMN first_met_at TIMESTAMP    NULL",
                "ADD COLUMN episodes     VARCHAR(600) NOT NULL DEFAULT ''", "ADD INDEX idx_living_acq_memory_at (memory_at)")) {
            assertTrue(up.contains(column), column);
        }
        assertTrue(up.chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128), "ASCII only");
    }

    @Test
    void liquibaseRunsExactlyOneStatementEachWay() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(1, up.length, String.join("\n;;\n", up));
        assertTrue(StringUtil.stripComments(up[0]).strip().startsWith("ALTER TABLE living_acquaintance"));
        assertEquals(1, down.length, String.join("\n;;\n", down));
        String rollback = StringUtil.stripComments(down[0]).strip();
        for (String dropped : List.of("DROP INDEX idx_living_acq_memory_at", "DROP COLUMN episodes", "DROP COLUMN first_met_at",
                "DROP COLUMN memory_at", "DROP COLUMN warmth")) {
            assertTrue(rollback.contains(dropped), dropped);
        }
    }

    @Test
    void onlyCitizenStoreTalksToTheMemoryColumns() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*\\b(?:memory_at|first_met_at)\\b[^\"]*\"");
        Set<String> files = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (sql.matcher(Files.readString(source)).find()) {
                    files.add(source.toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(Set.of("src/main/java/server/living/population/CitizenStore.java"), files);
    }
}

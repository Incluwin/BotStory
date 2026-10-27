package server.living.store;

import liquibase.util.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 52 (docs/BOT_CONCEPT_PLAN.md F2): living_owner_habit, one habits document per human, and
 * companion_yield.source in the key, so citizens' stints are booked beside the bench slices; registered after 50 with its
 * rollback, which drops the citizens' rows before the old key comes back; ASCII only; and only LivingStore names the
 * new table in SQL.
 */
class OwnerHabitSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/043-owner-habits.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/043-owner-habits-down.sql");

    @Test
    void changeset52IsRegisteredAfter50WithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"52-owner-habits\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"50-living-alliance-mark\""), "after 50");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/043-owner-habits.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/043-owner-habits-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"52-"), changelog.lastIndexOf("<changeSet id=\"52-"), "once");
    }

    @Test
    void oneDocumentPerHumanAndTheSourceInTheYieldKey() throws IOException {
        String up = Files.readString(UP);
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_owner_habit ("));
        assertTrue(up.contains("PRIMARY KEY (character_id)"));
        assertTrue(up.contains("ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'companion'"), "every existing row is a companion's");
        assertTrue(up.contains("ADD PRIMARY KEY (job_branch, level_band, camp_map_id, source)"));
        String statements = up.lines().filter(line -> !line.strip().startsWith("--")).reduce("", String::concat);
        assertFalse(statements.toUpperCase().contains("REFERENCES"), "no foreign key, as living_roster");
        assertTrue(up.chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128), "ASCII only");
    }

    @Test
    void liquibaseRunsTheStatementsInOrderEachWay() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(2, up.length, String.join("\n;;\n", up));
        assertTrue(StringUtil.stripComments(up[0]).strip().startsWith("CREATE TABLE IF NOT EXISTS living_owner_habit"));
        assertTrue(StringUtil.stripComments(up[1]).strip().startsWith("ALTER TABLE companion_yield"));
        assertEquals(3, down.length, String.join("\n;;\n", down));
        assertEquals("DELETE FROM companion_yield WHERE source <> 'companion'", StringUtil.stripComments(down[0]).strip(),
                "the citizens' rows go first, or the old key would clash");
        assertTrue(StringUtil.stripComments(down[1]).strip().startsWith("ALTER TABLE companion_yield"));
        assertEquals("DROP TABLE IF EXISTS living_owner_habit", StringUtil.stripComments(down[2]).strip());
    }

    @Test
    void onlyLivingStoreTalksToTheTable() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+living_owner_habit\\b[^\"]*\"");
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

package server.living.store;

import liquibase.util.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 53 (docs/BOT_CONCEPT_PLAN.md F7): living_clear, living_clear_member and living_rival, registered once with
 * the rollback (after 51); the rollback drops all three; ASCII only; and only ClearStore and RivalStore name them.
 */
class RivalSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/044-rival-crew.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/044-rival-crew-down.sql");

    @Test
    void changeset53IsRegisteredOnceWithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"53-rival-crew\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"51-living-need\""), "after 51");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/044-rival-crew.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/044-rival-crew-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"53-"), changelog.lastIndexOf("<changeSet id=\"53-"), "once");
    }

    @Test
    void threeTablesUpAndThreeDropsDown() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(3, up.length, String.join("\n;;\n", up));
        Map<String, String[]> columns = Map.of(
                "living_clear", new String[]{"quest", "instance", "channel", "map_id", "started_at", "cleared_at",
                        "duration_ms", "kind", "crew_id", "members"},
                "living_clear_member", new String[]{"clear_id", "character_id", "kind"},
                "living_rival", new String[]{"account_id", "main_id", "name", "guild_id", "circle_id", "leader_id",
                        "members", "formed_at", "help_asked_at"});
        for (String statement : up) {
            String table = StringUtil.stripComments(statement).strip();
            String name = table.substring("CREATE TABLE IF NOT EXISTS ".length(), table.indexOf(" (")).strip();
            assertTrue(columns.containsKey(name), name);
            for (String column : columns.get(name)) {
                assertTrue(table.contains("\n    " + column + " "), name + "." + column);
            }
            assertFalse(table.toUpperCase().contains("REFERENCES"), "no foreign key, as living_roster");
        }
        assertEquals(3, down.length, String.join("\n;;\n", down));
        assertEquals("DROP TABLE IF EXISTS living_rival", StringUtil.stripComments(down[0]).strip());
        assertEquals("DROP TABLE IF EXISTS living_clear_member", StringUtil.stripComments(down[1]).strip());
        assertEquals("DROP TABLE IF EXISTS living_clear", StringUtil.stripComments(down[2]).strip());
        assertTrue(Files.readString(UP).chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void onlyTheirStoresTalkToTheTables() throws IOException {
        Pattern clears = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+living_clear(_member)?\\b[^\"]*\"");
        Pattern rivals = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+living_rival\\b[^\"]*\"");
        Set<String> clearFiles = new TreeSet<>();
        Set<String> rivalFiles = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                String name = source.toString().replace('\\', '/');
                if (clears.matcher(text).find()) {
                    clearFiles.add(name);
                }
                if (rivals.matcher(text).find()) {
                    rivalFiles.add(name);
                }
            }
        }
        assertEquals(Set.of("src/main/java/server/living/population/records/ClearStore.java"), clearFiles);
        assertEquals(Set.of("src/main/java/server/living/population/rivals/RivalStore.java"), rivalFiles);
    }
}

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
 * Changeset 67 (docs/WORLD_SYSTEMS_PLAN.md N5): living_guild_goal and pvp_guild_rating, registered once with the
 * rollback; one row a guild a week; the rollback drops both; ASCII only; and only their stores name them.
 */
class GuildLifeSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/058-guild-life.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/058-guild-life-down.sql");

    @Test
    void changeset67IsRegisteredOnceWithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"67-guild-life\" author=\"ganre\">");
        assertTrue(start > 0);
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/058-guild-life.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/058-guild-life-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"67-"), changelog.lastIndexOf("<changeSet id=\"67-"), "once");
    }

    @Test
    void twoTablesUpAndTwoDropsDown() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(2, up.length, String.join("\n;;\n", up));
        Map<String, String[]> columns = Map.of(
                "living_guild_goal", new String[]{"guild_id", "week_of", "goal", "detail", "target", "progress", "done",
                        "set_at", "done_at"},
                "pvp_guild_rating", new String[]{"guild_id", "bracket", "season", "rating", "deviation", "volatility",
                        "wins", "losses", "updated_at", "week_of", "week_rating"});
        for (String statement : up) {
            String table = StringUtil.stripComments(statement).strip();
            String name = table.substring("CREATE TABLE IF NOT EXISTS ".length(), table.indexOf(" (")).strip();
            assertTrue(columns.containsKey(name), name);
            for (String column : columns.get(name)) {
                assertTrue(table.contains("\n    " + column + " "), name + "." + column);
            }
            assertFalse(table.toUpperCase().contains("REFERENCES"), "no foreign key, as living_roster");
        }
        assertTrue(StringUtil.stripComments(up[0]).contains("PRIMARY KEY (guild_id, week_of)"), "one goal a guild a week");
        assertEquals(2, down.length, String.join("\n;;\n", down));
        assertEquals("DROP TABLE IF EXISTS pvp_guild_rating", StringUtil.stripComments(down[0]).strip());
        assertEquals("DROP TABLE IF EXISTS living_guild_goal", StringUtil.stripComments(down[1]).strip());
        assertTrue(Files.readString(UP).chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void onlyTheirStoresTalkToTheTables() throws IOException {
        Pattern goals = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+living_guild_goal\\b[^\"]*\"");
        Pattern ratings = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+pvp_guild_rating\\b[^\"]*\"");
        Set<String> goalFiles = new TreeSet<>();
        Set<String> ratingFiles = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                String name = source.toString().replace('\\', '/');
                if (goals.matcher(text).find()) {
                    goalFiles.add(name);
                }
                if (ratings.matcher(text).find()) {
                    ratingFiles.add(name);
                }
            }
        }
        assertEquals(Set.of("src/main/java/server/living/population/GuildGoalStore.java"), goalFiles);
        assertEquals(Set.of(), ratingFiles, "the guild book comes with N5.3");
    }
}

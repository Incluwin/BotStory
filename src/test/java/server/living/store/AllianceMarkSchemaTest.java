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
 * Changeset 48 (living_alliance_mark, docs/BOT_GUILD_ALLIANCES_PLAN.md): registered after 47 with its rollback; one row
 * per guild and kind, its date in epoch milliseconds; no foreign key; ASCII only, split by Liquibase into exactly that
 * one statement each way; and only CitizenStore names the table in SQL.
 */
class AllianceMarkSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/039-living-alliance-mark.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/039-living-alliance-mark-down.sql");

    @Test
    void changeset48IsRegisteredAfter47WithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"48-living-alliance-mark\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"47-character-storage-bag\""), "after 47");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/039-living-alliance-mark.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/039-living-alliance-mark-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"48-"), changelog.lastIndexOf("<changeSet id=\"48-"), "once");
    }

    @Test
    void theTableKeepsOneDatePerGuildAndKind() throws IOException {
        String up = Files.readString(UP);
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_alliance_mark ("));
        for (String column : List.of("guild_id INT         NOT NULL", "kind     VARCHAR(8)  NOT NULL",
                "at_ms    BIGINT      NOT NULL", "PRIMARY KEY (guild_id, kind)")) {
            assertTrue(up.contains(column), column);
        }
        String statements = up.lines().filter(line -> !line.strip().startsWith("--")).reduce("", String::concat);
        assertFalse(statements.toUpperCase().contains("REFERENCES"), "no foreign key: a disbanded guild's rows are passed over");
        assertFalse(statements.toUpperCase().contains("TIMESTAMP"), "epoch milliseconds, untouched by session time zones");
        assertTrue(up.chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void liquibaseRunsExactlyTheOneStatementEachWay() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(1, up.length, String.join("\n;;\n", up));
        assertTrue(StringUtil.stripComments(up[0]).strip().startsWith("CREATE TABLE IF NOT EXISTS living_alliance_mark"));
        assertEquals(1, down.length, String.join("\n;;\n", down));
        assertEquals("DROP TABLE IF EXISTS living_alliance_mark", StringUtil.stripComments(down[0]).strip());
    }

    @Test
    void onlyCitizenStoreTalksToTheTable() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+living_alliance_mark\\b[^\"]*\"");
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

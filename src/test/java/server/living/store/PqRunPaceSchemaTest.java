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
 * Changeset 44 (pq_run_pace, the PQ clear-time records): registered after 43, and before 45, with its rollback; one
 * table whose columns hold the estimate at the Yes beside the actual time, the run's own times and its pace as JSON, one
 * row per session, looked up by leader and by quest and epoch; ASCII only, split by Liquibase into exactly that one
 * statement; and only LivingStore names the table in SQL.
 */
class PqRunPaceSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/035-pq-run-pace.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/035-pq-run-pace-down.sql");

    @Test
    void changeset44IsRegisteredAfter43WithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"44-pq-run-pace\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"43-companion-crew-fresh\""), "after 43");
        int next = changelog.indexOf("<changeSet ", start + 1); // newer work only: 45-living-knowledge
        assertTrue(next < 0 || changelog.startsWith("<changeSet id=\"45-living-knowledge\"", next), "44 comes before 45");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/035-pq-run-pace.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>")
                < changeSet.indexOf("<sqlFile path=\"db/tables/035-pq-run-pace-down.sql\" splitStatements=\"true\"/>"));
    }

    @Test
    void theTableHoldsTheEstimateBesideTheRunAndItsPace() throws IOException {
        String up = Files.readString(UP);
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS pq_run_pace ("));
        for (String column : List.of("id                  BIGINT       NOT NULL AUTO_INCREMENT",
                "pq_key              VARCHAR(16)  NOT NULL", "session_id          VARCHAR(64)  NOT NULL",
                "leader_character_id INT          NOT NULL", "party_size          TINYINT      NOT NULL",
                "humans              TINYINT      NOT NULL", "epoch               SMALLINT     NOT NULL",
                "outcome             VARCHAR(12)  NOT NULL", "est_p10_s           INT          NULL",
                "est_p50_s           INT          NULL", "est_p90_s           INT          NULL",
                "est_records         SMALLINT     NULL", "actual_s            INT          NULL",
                "started_at_ms       BIGINT       NOT NULL", "cleared_at_ms       BIGINT       NOT NULL DEFAULT 0",
                "spans               TEXT         NOT NULL", "members             TEXT         NOT NULL",
                "drawn               TEXT         NOT NULL",
                "recorded_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP", "PRIMARY KEY (id)",
                "UNIQUE KEY uq_pq_run_pace_session (session_id)", "INDEX idx_pq_run_pace_leader (leader_character_id)",
                "INDEX idx_pq_run_pace_epoch (pq_key, epoch, id)")) {
            assertTrue(up.contains(column), column);
        }
        String statements = up.lines().filter(line -> !line.strip().startsWith("--")).reduce("", String::concat);
        assertFalse(statements.toUpperCase().contains("REFERENCES"), "no foreign key, as companion_performance");
        assertTrue(Files.readString(DOWN).contains("DROP TABLE IF EXISTS pq_run_pace;"));
        assertTrue(up.chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void liquibaseRunsExactlyTheOneStatementEachWay() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(1, up.length, String.join("\n;;\n", up));
        assertTrue(StringUtil.stripComments(up[0]).strip().startsWith("CREATE TABLE IF NOT EXISTS pq_run_pace"));
        assertEquals(1, down.length, String.join("\n;;\n", down));
        assertEquals("DROP TABLE IF EXISTS pq_run_pace", StringUtil.stripComments(down[0]).strip());
    }

    @Test
    void onlyLivingStoreTalksToTheTable() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+pq_run_pace\\b[^\"]*\"");
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

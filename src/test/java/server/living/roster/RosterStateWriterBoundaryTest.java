package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.RosterState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 40 and its writer rule (docs/BOT_MANAGEMENT_PLAN.md P2a, "Data model"): only RosterService moves a
 * companion between seat states; the bench writes progress only; the schema carries the new columns and table.
 */
class RosterStateWriterBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final Path LIVING_STORE = MAIN.resolve("server/living/store/LivingStore.java");
    private static final Path ROSTER_SERVICE = MAIN.resolve("server/living/roster/RosterService.java");
    /** A full-row upsert (which writes state), a delete, or a state change on a row (P2b: to travel, hold or park too). */
    private static final Pattern STATE_WRITE = Pattern.compile(
            "\\.saveRoster\\(|\\.deleteRoster\\(|\\.with\\(RosterState\\.|\\.with(Travel|Hold|Park)\\(");
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/031-companion-manager.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/031-companion-manager-down.sql");

    @Test
    void onlyRosterServiceWritesLivingRosterState() throws IOException {
        assertEquals(Set.of(ROSTER_SERVICE.toString().replace('\\', '/')), filesMatching(STATE_WRITE, LIVING_STORE));
    }

    @Test
    void onlyLivingStoreTalksToTheRosterTable() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*living_roster[^\"]*\"");
        assertEquals(Set.of(LIVING_STORE.toString().replace('\\', '/')), filesMatching(sql, null));
    }

    @Test
    void theBenchsProgressWriteNeverTouchesState() throws IOException {
        String store = Files.readString(LIVING_STORE);
        Matcher columns = Pattern.compile("ROSTER_PROGRESS_COLUMNS = ((?:\"[^\"]*\"\\s*\\+?\\s*)+);").matcher(store);
        assertTrue(columns.find(), "ROSTER_PROGRESS_COLUMNS");
        assertFalse(columns.group(1).matches("(?s).*\\bstate\\b.*"), columns.group(1));
        assertFalse(columns.group(1).contains("lineup_slot"), columns.group(1));
        assertTrue(store.contains("\"UPDATE living_roster SET \" + ROSTER_PROGRESS_COLUMNS"), "an UPDATE, never an upsert");
        for (String bench : List.of("BenchLedger.java", "BenchSessions.java")) {
            String source = Files.readString(MAIN.resolve("server/living/roster/" + bench));
            assertTrue(source.contains(".saveRosterProgress("), bench);
        }
    }

    @Test
    void everySeatStateFitsTheStateColumn() {
        for (String added : List.of("TRAVELLING", "HOLD", "PARKED")) {
            assertEquals(added, RosterState.valueOf(added).name());
        }
        for (RosterState state : RosterState.values()) {
            assertTrue(state.name().length() <= 16, state.name()); // living_roster.state is VARCHAR(16)
        }
    }

    @Test
    void changesets40To43AreRegisteredInOrderWithTheirRollbacks() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"40-companion-manager\"");
        assertTrue(start >= 0, "changeset 40 is registered");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/031-companion-manager.sql\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/031-companion-manager-down.sql\""));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf("031-companion-manager-down.sql"));
        // P3b: the crews' formations (living_crew), right after it
        int crew = changelog.indexOf("<changeSet id=\"41-companion-crew\"");
        assertTrue(crew > start, "changeset 41 is registered after 40");
        String crewSet = changelog.substring(crew, changelog.indexOf("</changeSet>", crew));
        assertTrue(crewSet.contains("<sqlFile path=\"db/tables/032-companion-crew.sql\""));
        assertTrue(crewSet.indexOf("<rollback>") < crewSet.indexOf("032-companion-crew-down.sql"));
        // P5: the measured bench yields (companion_yield), then the crews kept fresh (living_crew.keep_fresh)
        int yields = changelog.indexOf("<changeSet id=\"42-companion-bench-learning\"");
        assertTrue(yields > crew, "changeset 42 is registered after 41");
        String yieldSet = changelog.substring(yields, changelog.indexOf("</changeSet>", yields));
        assertTrue(yieldSet.contains("<sqlFile path=\"db/tables/033-companion-bench-learning.sql\""));
        assertTrue(yieldSet.indexOf("<rollback>") < yieldSet.indexOf("033-companion-bench-learning-down.sql"));
        int fresh = changelog.indexOf("<changeSet id=\"43-companion-crew-fresh\"");
        assertTrue(fresh > yields, "changeset 43 is registered after 42");
        String freshSet = changelog.substring(fresh, changelog.indexOf("</changeSet>", fresh));
        assertTrue(freshSet.contains("<sqlFile path=\"db/tables/034-companion-crew-fresh.sql\""));
        assertTrue(freshSet.indexOf("<rollback>") < freshSet.indexOf("034-companion-crew-fresh-down.sql"));
        Matcher ids = Pattern.compile("<changeSet id=\"(\\d+)").matcher(changelog);
        int highest = 0;
        int forty = 0;
        int fortyOne = 0;
        int fortyTwo = 0;
        int fortyThree = 0;
        while (ids.find()) {
            int id = Integer.parseInt(ids.group(1));
            highest = Math.max(highest, id);
            forty += id == 40 ? 1 : 0;
            fortyOne += id == 41 ? 1 : 0;
            fortyTwo += id == 42 ? 1 : 0;
            fortyThree += id == 43 ? 1 : 0;
        }
        assertTrue(highest >= 43);
        assertEquals(1, forty);
        assertEquals(1, fortyOne);
        assertEquals(1, fortyTwo);
        assertEquals(1, fortyThree);
        // what comes after 43 is newer work (45-living-knowledge, docs/SERVER_KNOWLEDGE_PLAN.md 7.6), never an older id
        Matcher later = Pattern.compile("<changeSet id=\"(\\d+)").matcher(changelog.substring(fresh + 1));
        while (later.find()) {
            assertTrue(Integer.parseInt(later.group(1)) > 43, "changeset " + later.group(1) + " registered after 43");
        }
        String yieldUp = Files.readString(Path.of("src/main/resources/db/tables/033-companion-bench-learning.sql"));
        assertTrue(yieldUp.contains("CREATE TABLE IF NOT EXISTS companion_yield"));
        for (String column : List.of("job_branch      VARCHAR(16)", "level_band      TINYINT", "camp_map_id     INT",
                "samples         DOUBLE", "exp_per_hour    DOUBLE", "meso_per_hour   DOUBLE", "items_per_hour  DOUBLE",
                "deaths_per_hour DOUBLE", "updated_at      TIMESTAMP", "PRIMARY KEY (job_branch, level_band, camp_map_id)")) {
            assertTrue(yieldUp.contains(column), column);
        }
        String yieldDown = Files.readString(Path.of("src/main/resources/db/tables/033-companion-bench-learning-down.sql"));
        assertTrue(yieldDown.contains("DROP TABLE IF EXISTS companion_yield"));
        assertTrue(yieldUp.chars().allMatch(c -> c < 128) && yieldDown.chars().allMatch(c -> c < 128), "ASCII only");
        String freshUp = Files.readString(Path.of("src/main/resources/db/tables/034-companion-crew-fresh.sql"));
        String freshDown = Files.readString(Path.of("src/main/resources/db/tables/034-companion-crew-fresh-down.sql"));
        assertTrue(freshUp.contains("ADD COLUMN keep_fresh TINYINT(1) NOT NULL DEFAULT 0"), "off by default");
        assertTrue(freshDown.contains("DROP COLUMN keep_fresh"));
        assertTrue(freshUp.chars().allMatch(c -> c < 128) && freshDown.chars().allMatch(c -> c < 128), "ASCII only");
        String up = Files.readString(Path.of("src/main/resources/db/tables/032-companion-crew.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_crew"));
        assertTrue(up.contains("formation       VARCHAR(32)"));
        assertFalse(up.toUpperCase().contains("REFERENCES"), "no foreign key, as living_roster");
        assertTrue(Files.readString(Path.of("src/main/resources/db/tables/032-companion-crew-down.sql"))
                .contains("DROP TABLE IF EXISTS living_crew"));
    }

    @Test
    void changeset40AddsTheRosterColumnsAndTheRunRecords() throws IOException {
        String up = Files.readString(UP);
        for (String column : List.of("lineup_slot   TINYINT", "settings_json VARCHAR(1024)", "eta_at        TIMESTAMP",
                "hold_map_id   INT", "parked_at     TIMESTAMP")) {
            assertTrue(up.contains("ADD COLUMN " + column), column);
        }
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS companion_performance"));
        for (String column : List.of("owner_character_id", "companion_character_id", "persona_id              VARCHAR(64)",
                "activity                VARCHAR(16)", "outcome                 VARCHAR(12)", "duration_s", "deaths",
                "damage_share            DECIMAL(5,4)", "party_size              TINYINT", "recorded_at")) {
            assertTrue(up.contains(column), column);
        }
        assertTrue(up.contains("INDEX idx_companion_performance_companion (companion_character_id, activity)"));
        String statements = up.lines().filter(line -> !line.strip().startsWith("--")).reduce("", String::concat);
        assertFalse(statements.toUpperCase().contains("REFERENCES"), "deletion cleanup is a hook, not a foreign key");

        String down = Files.readString(DOWN);
        assertTrue(down.contains("DROP TABLE IF EXISTS companion_performance"));
        assertTrue(down.contains("SET state = 'BENCHED' WHERE state IN ('TRAVELLING', 'HOLD', 'PARKED')"));
        for (String column : List.of("lineup_slot", "settings_json", "eta_at", "hold_map_id", "parked_at")) {
            assertTrue(down.contains("DROP COLUMN " + column), column);
        }
        assertTrue(up.chars().allMatch(c -> c < 128) && down.chars().allMatch(c -> c < 128), "ASCII only");
    }

    /** Main sources (except {@code skip}) with a line matching {@code pattern} outside comments. */
    private static Set<String> filesMatching(Pattern pattern, Path skip) throws IOException {
        Set<String> files = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(MAIN)) {
            for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.equals(skip)) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    String code = line.strip();
                    if (!code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*")
                            && pattern.matcher(code).find()) {
                        files.add(file.toString().replace('\\', '/'));
                        break;
                    }
                }
            }
        }
        return files;
    }
}

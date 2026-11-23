package server.living.roster;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 66 (docs/WORLD_SYSTEMS_PLAN.md N9.1) adds the two columns LivingStore writes and reads for the companion
 * traits to companion_performance, both nullable (old rows measured nothing), and its rollback drops them.
 */
class CompanionTraitsSchemaTest {
    private static final Path TABLES = Path.of("src/main/resources/db/tables");

    @Test
    void theRunRowsHoldTheHpFiguresTheStoreWrites() throws IOException {
        String up = Files.readString(TABLES.resolve("057-companion-traits.sql"));
        assertTrue(up.contains("ALTER TABLE companion_performance"));
        assertTrue(up.contains("ADD COLUMN min_hp_percent TINYINT  NULL"));
        assertTrue(up.contains("ADD COLUMN close_calls    SMALLINT NULL"));
        String down = Files.readString(TABLES.resolve("057-companion-traits-down.sql"));
        assertTrue(down.contains("DROP COLUMN min_hp_percent") && down.contains("DROP COLUMN close_calls"));
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"66-companion-traits\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/057-companion-traits.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/057-companion-traits-down.sql\" splitStatements=\"true\"/>"));
        String store = Files.readString(Path.of("src/main/java/server/living/store/LivingStore.java"));
        assertTrue(store.contains("party_size, min_hp_percent, close_calls)"), "the insert writes both");
        assertTrue(store.contains("SELECT activity, outcome, deaths, close_calls, recorded_at FROM companion_performance"));
    }
}

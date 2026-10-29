package server.living.population;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 57 (docs/ECONOMY_PLAN.md E3) makes living_gear_errand with every column CitizenStore reads and writes,
 * one row per citizen and slot, and its rollback drops it.
 */
class GearErrandSchemaTest {
    private static final Path TABLES = Path.of("src/main/resources/db/tables");

    @Test
    void theErrandTableHoldsWhatTheStoreWrites() throws IOException {
        String up = Files.readString(TABLES.resolve("048-gear-errand.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_gear_errand ("));
        for (String column : new String[] {"character_id", "slot", "category", "worn_level", "weapon_type", "until_at"}) {
            assertTrue(up.contains("    " + column + " "), column);
        }
        assertTrue(up.contains("PRIMARY KEY (character_id, slot)"), "the upsert keys on citizen and slot");
        assertTrue(Files.readString(TABLES.resolve("048-gear-errand-down.sql")).contains("DROP TABLE IF EXISTS living_gear_errand;"));
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"57-gear-errand\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/048-gear-errand.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/048-gear-errand-down.sql\" splitStatements=\"true\"/>"));
    }
}

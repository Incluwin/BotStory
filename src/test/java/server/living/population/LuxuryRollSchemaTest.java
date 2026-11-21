package server.living.population;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 63 (docs/ECONOMY_PLAN.md section 9.6) makes living_luxury_roll with every column CitizenStore reads and
 * writes, one row per citizen and look, and its rollback drops it.
 */
class LuxuryRollSchemaTest {
    private static final Path TABLES = Path.of("src/main/resources/db/tables");

    @Test
    void theRollTableHoldsWhatTheStoreWrites() throws IOException {
        String up = Files.readString(TABLES.resolve("054-luxury-roll.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS living_luxury_roll ("));
        for (String column : new String[] {"character_id", "look", "day"}) {
            assertTrue(up.contains("    " + column + " "), column);
        }
        assertTrue(up.contains("look            VARCHAR(8)"), "HAIR, FACE and SKIN fit");
        assertTrue(up.contains("PRIMARY KEY (character_id, look)"), "the upsert keys on citizen and look");
        assertTrue(Files.readString(TABLES.resolve("054-luxury-roll-down.sql")).contains("DROP TABLE IF EXISTS living_luxury_roll;"));
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"63-luxury-roll\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/054-luxury-roll.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/054-luxury-roll-down.sql\" splitStatements=\"true\"/>"));
        for (CitizenSalons.Look look : CitizenSalons.Look.values()) {
            assertTrue(look.name().length() <= 8, look.name());
        }
    }
}

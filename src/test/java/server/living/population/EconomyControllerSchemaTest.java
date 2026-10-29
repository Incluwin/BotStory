package server.living.population;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changeset 58 (docs/ECONOMY_PLAN.md E4) makes economy_controller_daily with every column EconomyController uses. */
class EconomyControllerSchemaTest {
    private static final Path TABLES = Path.of("src/main/resources/db/tables");

    @Test
    void theControllerTableHoldsWhatTheControllerWrites() throws IOException {
        String up = Files.readString(TABLES.resolve("049-economy-controller.sql"));
        assertTrue(up.contains("CREATE TABLE IF NOT EXISTS economy_controller_daily ("));
        for (String column : new String[] {"day", "growth", "cpi_change", "pickup_share", "luxury_rate", "budget_scale",
                "applied"}) {
            assertTrue(up.contains("    " + column + " "), column);
        }
        assertTrue(up.contains("PRIMARY KEY (day)"), "REPLACE keys on the day");
        assertTrue(Files.readString(TABLES.resolve("049-economy-controller-down.sql"))
                .contains("DROP TABLE IF EXISTS economy_controller_daily;"));
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"58-economy-controller\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/049-economy-controller.sql\" splitStatements=\"true\"/>"));
    }
}

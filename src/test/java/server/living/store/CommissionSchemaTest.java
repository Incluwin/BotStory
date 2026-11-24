package server.living.store;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changeset 69 (docs/WORLD_SYSTEMS_PLAN.md N8): living_commission, a buy order's poster, and what rolls them back. */
class CommissionSchemaTest {
    private static final Path TABLES = Path.of("src/main/resources/db/tables");

    @Test
    void changeset69MakesTheTableAndTheColumnAndTakesThemBack() throws IOException {
        String up = Files.readString(TABLES.resolve("060-living-commission.sql"));
        for (String column : new String[]{"customer_id", "crafter_id", "recipe", "quantity", "fee", "state", "map_id",
                "work_ms", "worked_ms", "ordered_at", "due_at", "closed_at"}) {
            assertTrue(up.contains("\n    " + column + " "), column);
        }
        assertTrue(up.contains("ALTER TABLE living_need ADD COLUMN poster_character_id INT NULL;"));
        String down = Files.readString(TABLES.resolve("060-living-commission-down.sql"));
        assertTrue(down.contains("DROP TABLE IF EXISTS living_commission;"));
        assertTrue(down.contains("DROP COLUMN poster_character_id"));
        assertTrue(down.indexOf("WHERE inventoryitems.type = 15") < down.indexOf("DROP TABLE"), "what is held goes first");
        assertTrue((up + down).chars().allMatch(c -> c < 128), "ASCII only");
        String changelog = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changelog.contains("<changeSet id=\"69-living-commission\""));
        assertTrue(changelog.contains("<sqlFile path=\"db/tables/060-living-commission.sql\" splitStatements=\"true\"/>"));
        assertEquals(15, client.inventory.ItemFactory.COMMISSION.getValue());
    }

    @Test
    void allThreeSwitchesShipOff() {
        config.PopulationConfig config = new config.PopulationConfig();
        assertTrue(!config.PROFESSIONS_ENABLED && !config.COMMISSIONS_ENABLED && !config.BUY_ORDERS_ENABLED);
        config.validate();
    }
}

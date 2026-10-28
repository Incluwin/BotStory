package server.economy.ledger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ledger's changeset (55-economy-ledger) creates its five tables, and its rollback drops each of them. */
class EconomyLedgerSchemaTest {
    private static final Path UP = Path.of("src/main/resources/db/tables/046-economy-ledger.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/046-economy-ledger-down.sql");
    private static final String[] TABLES = {"economy_flow_daily", "economy_supply_daily", "economy_cpi_daily",
            "economy_close_daily", "economy_purse_reset"};

    @Test
    void theChangesetCreatesAndDropsEveryTable() throws IOException {
        String up = Files.readString(UP);
        String down = Files.readString(DOWN);
        for (String table : TABLES) {
            assertTrue(up.contains("CREATE TABLE IF NOT EXISTS " + table + " ("), table);
            assertTrue(down.contains("DROP TABLE IF EXISTS " + table + ";"), table);
        }
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"55-economy-ledger\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/046-economy-ledger.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/046-economy-ledger-down.sql\" splitStatements=\"true\"/>"));
    }
}

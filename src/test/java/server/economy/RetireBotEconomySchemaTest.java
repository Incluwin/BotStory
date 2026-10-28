package server.economy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision D5 (docs/ECONOMY_PLAN.md E2): changeset 56 drops every table changeset 27 made for the old bot merchant
 * economy, its rollback recreates each one, and it leaves the bot_flag columns other systems still read.
 */
class RetireBotEconomySchemaTest {
    private static final Path TABLES = Path.of("src/main/resources/db/tables");

    @Test
    void everyBotEconomyTableIsDroppedAndRestorable() throws IOException {
        List<String> created = new ArrayList<>();
        Matcher create = Pattern.compile("CREATE TABLE IF NOT EXISTS (bot_\\w+)")
                .matcher(Files.readString(TABLES.resolve("027-bot-economy.sql")));
        while (create.find()) {
            created.add(create.group(1));
        }
        assertEquals(19, created.size(), created.toString());
        String up = Files.readString(TABLES.resolve("047-retire-bot-economy.sql"));
        String down = Files.readString(TABLES.resolve("047-retire-bot-economy-down.sql"));
        for (String table : created) {
            assertTrue(up.contains("DROP TABLE IF EXISTS " + table + ";"), table);
            assertTrue(down.contains("CREATE TABLE IF NOT EXISTS " + table + " "), table);
        }
        assertFalse(up.contains("ALTER TABLE"), "the bot_flag columns stay");
        String changeSet = Files.readString(Path.of("src/main/resources/db/changelog-tables.xml"));
        assertTrue(changeSet.contains("<changeSet id=\"56-retire-bot-economy\""));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/047-retire-bot-economy-down.sql\" splitStatements=\"true\"/>"));
    }
}

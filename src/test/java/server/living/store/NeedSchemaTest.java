package server.living.store;

import liquibase.util.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changeset 51 (docs/BOT_CONCEPT_PLAN.md F1): living_need, what citizens ask for on the needs board, registered once
 * with its rollback (after 52, which went live first); the rollback takes the escrowed items (inventoryitems type 14,
 * ItemFactory.NEED_ESCROW) and their equip rows before the table; ASCII only; and only NeedStore names the table.
 */
class NeedSchemaTest {
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog-tables.xml");
    private static final Path UP = Path.of("src/main/resources/db/tables/042-living-need.sql");
    private static final Path DOWN = Path.of("src/main/resources/db/tables/042-living-need-down.sql");

    @Test
    void changeset51IsRegisteredOnceWithItsRollback() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("<changeSet id=\"51-living-need\" author=\"ganre\">");
        assertTrue(start > changelog.indexOf("<changeSet id=\"52-owner-habits\""), "after 52");
        String changeSet = changelog.substring(start, changelog.indexOf("</changeSet>", start));
        assertTrue(changeSet.contains("<sqlFile path=\"db/tables/042-living-need.sql\" splitStatements=\"true\"/>"));
        assertTrue(changeSet.indexOf("<rollback>") < changeSet.indexOf(
                "<sqlFile path=\"db/tables/042-living-need-down.sql\" splitStatements=\"true\"/>"));
        assertEquals(changelog.indexOf("<changeSet id=\"51-"), changelog.lastIndexOf("<changeSet id=\"51-"), "once");
    }

    @Test
    void oneTableTheEscrowGoesFirstOnTheWayDown() throws IOException {
        String[] up = StringUtil.processMultiLineSQL(Files.readString(UP), false, true, null);
        String[] down = StringUtil.processMultiLineSQL(Files.readString(DOWN), false, true, null);

        assertEquals(1, up.length, String.join("\n;;\n", up));
        String table = StringUtil.stripComments(up[0]).strip();
        assertTrue(table.startsWith("CREATE TABLE IF NOT EXISTS living_need"));
        for (String column : new String[]{"citizen_id", "kind", "item_id", "quantity", "delivered", "offer", "detail",
                "map_id", "state", "claimed_by", "claimed_until", "fulfilled_by", "thanked", "created_at", "deadline_at",
                "closed_at"}) {
            assertTrue(table.contains("\n    " + column + " "), column);
        }
        assertFalse(table.toUpperCase().contains("REFERENCES"), "no foreign key, as living_roster");
        assertEquals(3, down.length, String.join("\n;;\n", down));
        assertTrue(StringUtil.stripComments(down[0]).strip().startsWith("DELETE inventoryequipment FROM inventoryequipment"));
        assertEquals("DELETE FROM inventoryitems WHERE type = 14", StringUtil.stripComments(down[1]).strip());
        assertEquals("DROP TABLE IF EXISTS living_need", StringUtil.stripComments(down[2]).strip());
        assertTrue(Files.readString(UP).chars().allMatch(c -> c < 128) && Files.readString(DOWN).chars().allMatch(c -> c < 128),
                "ASCII only");
    }

    @Test
    void escrowIsItemFactoryTypeFourteen() {
        assertEquals(14, client.inventory.ItemFactory.NEED_ESCROW.getValue());
    }

    @Test
    void onlyNeedStoreTalksToTheTable() throws IOException {
        Pattern sql = Pattern.compile("\"[^\"]*\\b(?i:FROM|INTO|UPDATE|TABLE|JOIN)\\s+living_need\\b[^\"]*\"");
        Set<String> files = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (sql.matcher(Files.readString(source)).find()) {
                    files.add(source.toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(Set.of("src/main/java/server/living/population/needs/NeedStore.java"), files);
    }
}

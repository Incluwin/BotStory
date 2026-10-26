package server.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline Liquibase replay: the repo's changelogs give the rows a fresh DB holds (KPQ coupon 80%, EPQ poison 60%,
 * shop 1338 without the Teleport Rock), rollbacks never run, and data changes it cannot apply are reported, not guessed.
 */
class SeedDataTest {
    private static final Path ROOT = Path.of(SeedData.DEFAULT_ROOT);
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\">\n";
    private static final String DROP_COLUMNS = "(dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)";
    private static final String GLOBAL_COLUMNS = "(continent, itemid, minimum_quantity, maximum_quantity, questid, chance)";

    @Test
    void replaysTheRepoChangelogsInLiquibaseOrder() throws IOException {
        long started = System.nanoTime();
        SeedData.Replay replay = SeedData.replay(ROOT);
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertEquals(List.of(), replay.warnings());
        assertEquals(22_157 + 1_384, replay.tables().get("drop_data").size(), "the seed and the Repack mobs' rows");
        assertEquals(6, replay.tables().get("drop_data_global").size());
        assertEquals(110, replay.tables().get("shops").size());
        assertEquals(3_881, replay.tables().get("shopitems").size());
        assertEquals(List.of(800_000), chances(replay, 9300001, 4001007));
        assertEquals(List.of(600_000), chances(replay, 9300173, 4001161));
        List<Map<String, String>> shop1338 = replay.tables().get("shopitems").stream()
                .filter(row -> SeedData.column(row, "shopid") == 1338).toList();
        assertEquals(25, shop1338.size());
        assertTrue(shop1338.stream().noneMatch(row -> SeedData.column(row, "itemid") == 5041000));
        List<Map<String, String>> globals = replay.tables().get("drop_data_global");
        assertEquals(1, globals.stream().filter(row -> SeedData.column(row, "itemid") == 4310000).count());
        assertEquals(3000, globals.stream().filter(row -> SeedData.column(row, "itemid") == 4031865)
                .mapToInt(row -> SeedData.column(row, "chance")).findFirst().orElse(-1));
        System.out.println("[seed-data] replay " + ms + " ms");
    }

    @Test
    void appliesChangesetsInDocumentThenExtensionOrder() throws IOException {
        assertEquals(List.of("101", "102", "151", "152", "162", "163", "200", "2026-07-15-kpq-coupon-chance",
                "2026-08-20-kpq-coupon-chance-80", "2026-09-05-epq-poison-bottle-chance-60", "2026-10-26-repack-import-drops"),
                SeedData.replay(ROOT).applied());
    }

    @Test
    void everySeedTupleSurvivesExceptTheChangesetEdits() throws IOException {
        // the regex the test fixture used before the replay: one (mob, item, min, max, quest, chance) tuple per row
        Pattern six = Pattern.compile("\\((-?\\d+), (\\d+), (\\d+), (\\d+), (\\d+), (\\d+)[,)]");
        Matcher tuple = six.matcher(Files.readString(ROOT.resolve("data/152-drop-data.sql"), StandardCharsets.UTF_8));
        List<int[]> oracle = new ArrayList<>();
        while (tuple.find()) {
            int[] row = new int[6];
            for (int i = 0; i < 6; i++) {
                row[i] = Integer.parseInt(tuple.group(i + 1));
            }
            oracle.add(row);
        }
        List<Map<String, String>> replayed = SeedData.replay(ROOT).tables().get("drop_data");
        assertEquals(oracle.size() + 1_384, replayed.size(), "the seed, then the Repack mobs' rows (extensions)");
        int edited = 0;
        for (int i = 0; i < oracle.size(); i++) {
            int[] expected = oracle.get(i);
            Map<String, String> row = replayed.get(i);
            int[] actual = {SeedData.column(row, "dropperid"), SeedData.column(row, "itemid"),
                    SeedData.column(row, "minimum_quantity"), SeedData.column(row, "maximum_quantity"),
                    SeedData.column(row, "questid"), SeedData.column(row, "chance")};
            if (expected[0] == 9300001 && expected[1] == 4001007) {
                expected[5] = 800_000;
                edited++;
            } else if (expected[0] == 9300173 && expected[1] == 4001161) {
                expected[5] = 600_000;
                edited++;
            }
            assertArrayEquals(expected, actual, "row " + i);
        }
        assertEquals(2, edited);
    }

    @Test
    void rollbackNeverRunsAndUnsupportedChangesOnWatchedTablesWarn(@TempDir Path dir) throws IOException {
        Path db = seedRoot(dir, """
                <changeSet id="1" author="t">
                    <sql>
                        INSERT INTO drop_data %s VALUES (100100, 4000019, 1, 1, 0, 600000), (100101, 4000000, 1, 1, 0, 500000);
                        INSERT INTO shops (shopid, npcid) VALUES (1, 2);
                        INSERT INTO shopitems (shopid, itemid, price, pitch, position) VALUES (1, 2000000, 50, 0, 104);
                    </sql>
                </changeSet>
                """.formatted(DROP_COLUMNS));
        write(db.resolve("extensions/a.xml"), HEADER + """
                <changeSet id="upd" author="t">
                    <update tableName="drop_data">
                        <column name="chance" valueNumeric="700000"/>
                        <where>dropperid = 100100 AND itemid = 4000019</where>
                    </update>
                    <rollback>
                        <delete tableName="drop_data"><where>dropperid = 100100</where></delete>
                    </rollback>
                </changeSet>
                <changeSet id="pre" author="t">
                    <preConditions onFail="MARK_RAN"><tableExists tableName="shopitems"/></preConditions>
                    <delete tableName="shopitems"><where>itemid = 999</where></delete>
                </changeSet>
                </databaseChangeLog>
                """);
        write(db.resolve("extensions/b.xml"), HEADER + """
                <changeSet id="bad" author="t">
                    <sqlFile path="bad.sql" relativeToChangelogFile="true"/>
                </changeSet>
                </databaseChangeLog>
                """);
        write(db.resolve("extensions/bad.sql"), """
                -- neither shape is replayed
                REPLACE INTO shopitems (shopid, itemid, price, pitch, position) VALUES (1, 2000001, 50, 0, 108);
                UPDATE drop_data SET chance = chance * 2 WHERE dropperid = 100101;
                UPDATE accounts SET x = 1 WHERE id = 1;
                """);

        SeedData.Replay replay = SeedData.replay(db);

        List<Map<String, String>> drops = replay.tables().get("drop_data");
        assertEquals(2, drops.size(), "the rollback's delete never runs");
        assertEquals("700000", drops.get(0).get("chance"));
        assertEquals("500000", drops.get(1).get("chance"));
        assertEquals(1, replay.tables().get("shopitems").size());
        assertEquals(List.of("1", "upd", "pre"), replay.applied());
        assertEquals(3, replay.warnings().size(), replay.warnings().toString());
        assertEquals("db/extensions/a.xml: changeset pre: preconditions not evaluated", replay.warnings().get(0));
        assertTrue(replay.warnings().get(1).startsWith("db/extensions/bad.sql: changeset bad: not applied: REPLACE INTO shopitems"),
                replay.warnings().get(1));
        assertTrue(replay.warnings().get(2).startsWith("db/extensions/bad.sql: changeset bad: not applied: UPDATE drop_data SET chance = chance * 2"),
                replay.warnings().get(2));
    }

    @Test
    void overlayJoinsShopsAndDropsZeroChanceGlobals(@TempDir Path dir) throws IOException {
        Path db = seedRoot(dir, """
                <changeSet id="1" author="t">
                    <sql>
                        INSERT INTO shops (shopid, npcid) VALUES (1, 1012000);
                        INSERT INTO shopitems (shopid, itemid, price, pitch, position) VALUES (1, 2000000, 50, 0, 104), (9, 2000001, 160, 0, 104);
                        INSERT INTO drop_data_global %1$s VALUES (-1, 4031865, 1, 1, 0, 3000), (-1, 2049100, 1, 1, 0, 0);
                        INSERT INTO drop_data_global %1$s
                        SELECT * FROM (SELECT -1 AS continent, 4310000 AS itemid, 1 AS minimum_quantity, 1 AS maximum_quantity,
                                              0 AS questid, 2000 AS chance) AS tmp
                        WHERE NOT EXISTS (SELECT 1 FROM drop_data_global WHERE itemid = 4310000);
                        INSERT INTO drop_data_global %1$s
                        SELECT * FROM (SELECT -1 AS continent, 4031865 AS itemid, 1 AS minimum_quantity, 1 AS maximum_quantity,
                                              0 AS questid, 9 AS chance) AS tmp
                        WHERE NOT EXISTS (SELECT 1 FROM drop_data_global WHERE itemid = 4031865);
                    </sql>
                </changeSet>
                """.formatted(GLOBAL_COLUMNS));

        KnowledgeOverlay overlay = SeedData.load(db);

        assertEquals(1, overlay.shopRows(), "shop 9 has no shops row");
        assertArrayEquals(new int[]{1, 1012000, 2000000, 50, 0}, overlay.shopItems.get(0));
        assertEquals(2, overlay.globalDropRows(), "the chance-0 global is dropped; the guarded insert adds only 4310000");
        assertArrayEquals(new int[]{-1, 4031865, 3000, 1, 1, 0}, overlay.globalDrops.get(0));
        assertArrayEquals(new int[]{-1, 4310000, 2000, 1, 1, 0}, overlay.globalDrops.get(1));
    }

    private static List<Integer> chances(SeedData.Replay replay, int mob, int item) {
        return replay.tables().get("drop_data").stream()
                .filter(row -> SeedData.column(row, "dropperid") == mob && SeedData.column(row, "itemid") == item)
                .map(row -> SeedData.column(row, "chance")).toList();
    }

    /** {@code dir/db} with a root changelog: data.xml (the given changesets), then the extensions folder. */
    private static Path seedRoot(Path dir, String changeSets) throws IOException {
        Path db = dir.resolve("db");
        write(db.resolve("changelog-root.xml"), HEADER + """
                <include file="data.xml" relativeToChangelogFile="true"/>
                <includeAll path="extensions" relativeToChangelogFile="true" errorIfMissingOrEmpty="false"/>
                </databaseChangeLog>
                """);
        write(db.resolve("data.xml"), HEADER + changeSets + "</databaseChangeLog>\n");
        return db;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}

package server.living.store;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The learned price levers keep their arms in companion_yield rows of source 'price' (docs/WORLD_SYSTEMS_PLAN.md I2),
 * where job_branch and level_band mean the lever key and the arm, not a job branch and a level band. So every reader of
 * the table must leave those rows out: the bench model's companion rows (BenchYieldModel.load), the citizens' spot rows
 * (citizenYields), and nothing else in the server reads the table. A 'price' row also never overwrites another source's
 * row (the primary key includes source, changeset 52).
 */
class CompanionYieldPriceRowsTest {

    /** Each yields read against a table holding one row of each source: what it returns. */
    private static List<LivingStore.YieldRow> read(java.util.function.Function<LivingStore, List<LivingStore.YieldRow>> reader)
            throws Exception {
        List<String> sources = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenAnswer(inv -> {
            sql.add(inv.getArgument(0));
            return ps;
        });
        org.mockito.Mockito.doAnswer(inv -> sources.add(inv.getArgument(1))).when(ps).setString(anyInt(), anyString());
        when(ps.executeQuery()).thenAnswer(inv -> table(sources.get(sources.size() - 1)));
        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            List<LivingStore.YieldRow> rows = reader.apply(new LivingStore());
            assertTrue(sql.get(0).endsWith("FROM companion_yield WHERE source = ?"), sql.get(0));
            return rows;
        }
    }

    /** The table filtered by source as MySQL would: one companion, one citizen and one price row. */
    private static ResultSet table(String source) throws Exception {
        String[][] all = {{"WARRIOR", "companion"}, {"THIEF", "citizen"}, {"ASK_EQUIP", "price"}};
        List<String> branches = new ArrayList<>();
        for (String[] row : all) {
            if (row[1].equals(source)) {
                branches.add(row[0]);
            }
        }
        ResultSet rs = mock(ResultSet.class);
        int[] at = {-1};
        when(rs.next()).thenAnswer(inv -> ++at[0] < branches.size());
        when(rs.getString(1)).thenAnswer(inv -> branches.get(at[0]));
        return rs;
    }

    @Test
    void theBenchModelsReadsNeverSeeAPriceRow() throws Exception {
        assertEquals(List.of("WARRIOR"), read(LivingStore::companionYields).stream().map(LivingStore.YieldRow::jobBranch).toList());
        assertEquals(List.of("THIEF"), read(LivingStore::citizenYields).stream().map(LivingStore.YieldRow::jobBranch).toList());
        assertEquals(List.of("ASK_EQUIP"), read(LivingStore::priceArms).stream().map(LivingStore.YieldRow::jobBranch).toList());
    }

    @Test
    void nothingElseInTheServerReadsTheTable() throws IOException {
        List<String> readers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (text.contains("FROM companion_yield") || text.contains("UPDATE companion_yield")
                        || text.contains("DELETE FROM companion_yield") || text.contains("INTO companion_yield")) {
                    readers.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("LivingStore.java"), readers, "only the store touches companion_yield");
        String store = Files.readString(Path.of("src/main/java/server/living/store/LivingStore.java"));
        assertEquals(1, count(store, "FROM companion_yield"), "one read, by source");
        assertTrue(store.contains("FROM companion_yield WHERE source = ?\""));
        assertTrue(store.contains("return yields(YIELD_COMPANION);") && store.contains("return yields(YIELD_CITIZEN);")
                && store.contains("return yields(YIELD_PRICE);"));
        String bench = Files.readString(Path.of("src/main/java/server/living/roster/BenchYieldModel.java"));
        assertTrue(!bench.contains("priceArms()"), "the bench model never reads the price rows");
    }

    @Test
    void theR5StepOfTheEconomyPlanSparesThePriceRows() throws IOException {
        String plan = Files.readString(Path.of("docs/ECONOMY_PLAN.md"));
        assertTrue(plan.contains("source = 'price'"), "R5's companion_yield re-key or reset leaves the price rows alone");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}

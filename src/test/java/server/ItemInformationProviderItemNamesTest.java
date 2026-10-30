package server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * getAllItems (every item's id and name: !search, item names in Resident talk, the friend forge, MTS search) read the
 * six String files on each call: its cache was checked but never filled (docs/SERVER_KNOWLEDGE_PLAN.md section 11).
 * The list is now read once, shared read-only by every caller and thread, and read again after clearCaches.
 */
class ItemInformationProviderItemNamesTest {
    private static ItemInformationProvider ii;

    /** The provider with no database (its monster card table is all it reads there). */
    @BeforeAll
    static void provider() {
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            ii = ItemInformationProvider.getInstance();
        }
    }

    @Test
    void theNamesAreReadOnceAndSharedReadOnly() {
        List<Pair<Integer, String>> names = ii.getAllItems();

        assertTrue(names.size() > 10_000, "every String file: " + names.size());
        assertTrue(names.stream().anyMatch(item -> item.getLeft() == 2000000), "Red Potion");
        assertSame(names, ii.getAllItems());
        assertThrows(UnsupportedOperationException.class, () -> names.add(new Pair<>(0, "NO-NAME")));
        assertTrue(ii.getAllEtcItems().stream().allMatch(item -> item.getLeft() / 1_000_000 == 4),
                "the Etc list stays the Etc file's");
    }

    @Test
    void threadsAskingAtOnceShareOneList() throws Exception {
        ii.clearCaches();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<Pair<Integer, String>>>> asks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                asks.add(ii::getAllItems);
            }
            List<Future<List<Pair<Integer, String>>>> answers = pool.invokeAll(asks, 60, TimeUnit.SECONDS);
            List<Pair<Integer, String>> first = answers.get(0).get();
            for (Future<List<Pair<Integer, String>>> answer : answers) {
                assertSame(first, answer.get());
            }
            ii.clearCaches();
            List<Pair<Integer, String>> again = ii.getAllItems();
            assertTrue(again != first && again.size() == first.size(), "read again after clearCaches");
        } finally {
            pool.shutdownNow();
        }
    }
}

package server.living.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A GM level record read after a boot (LivingStore.latestGmLevel): its newest gm.level.hold or gm.level.release row,
 * and whether any release of it was a keep. The newest row alone lost a keep under a later set that was not applied or
 * was restored (GmLevelHolds.kept). Empty for no rows, null for a failed read.
 */
class GmLevelRecordStoreTest {
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final List<String> sql = new ArrayList<>();
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void openTheDatabase() throws SQLException {
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            sql.add(inv.getArgument(0));
            return statement;
        });
        when(statement.executeQuery()).thenReturn(rows);
    }

    @AfterEach
    void closeTheDatabase() {
        database.close();
    }

    @Test
    void theNewestRowSaysWhetherAnyReleaseOfTheRecordWasAKeep() throws SQLException {
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("gm.level.release");
        when(rows.getString(2)).thenReturn("not applied");
        when(rows.getBoolean(3)).thenReturn(true);

        Optional<LivingStore.EventRow> row = new LivingStore().latestGmLevel(1010, "flint");

        assertEquals(Optional.of(new LivingStore.EventRow("gm.level.release", "not applied", true)), row);
        String query = sql.get(0);
        assertTrue(query.contains("k.kind = 'gm.level.release' AND k.detail = 'keep'"), query);
        assertTrue(query.contains("ORDER BY id DESC LIMIT 1"), query);
        verify(statement).setInt(1, 1010);
        verify(statement).setString(2, "flint");
        verify(statement).setInt(3, 1010);
        verify(statement).setString(4, "flint");
    }

    @Test
    void noRowsIsNoRecordAndAFailedReadIsNull() throws SQLException {
        when(rows.next()).thenReturn(false);
        assertEquals(Optional.empty(), new LivingStore().latestGmLevel(1010, "flint"));

        when(statement.executeQuery()).thenThrow(new SQLException("gone"));
        assertNull(new LivingStore().latestGmLevel(1010, "flint"));
    }
}

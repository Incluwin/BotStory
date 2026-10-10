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
 * A companion's newest event of some kinds (LivingStore.latestEvent: the boss-gear record, BossGearRecord): one row,
 * the newest, of that owner, persona and kinds, read through the character index. Empty for no rows, null for a failed
 * read.
 */
class LatestEventStoreTest {
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
    void theNewestRowOfTheOwnerPersonaAndKinds() throws SQLException {
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("bossgear.worn");
        when(rows.getString(2)).thenReturn("displaced: -1:1002027");

        Optional<LivingStore.EventRow> row = new LivingStore().latestEvent(870, "tamsin",
                List.of("bossgear.worn", "bossgear.removed"));

        assertEquals(Optional.of(new LivingStore.EventRow("bossgear.worn", "displaced: -1:1002027")), row);
        String query = sql.get(0);
        assertTrue(query.contains("WHERE character_id = ? AND persona_id = ? AND kind IN (?,?)"), query);
        assertTrue(query.contains("ORDER BY id DESC LIMIT 1"), query);
        verify(statement).setInt(1, 870);
        verify(statement).setString(2, "tamsin");
        verify(statement).setString(3, "bossgear.worn");
        verify(statement).setString(4, "bossgear.removed");
    }

    @Test
    void noRowsIsEmptyAndAFailedReadIsNull() throws SQLException {
        when(rows.next()).thenReturn(false);
        assertEquals(Optional.empty(), new LivingStore().latestEvent(870, "tamsin", List.of("bossgear.worn")));

        when(statement.executeQuery()).thenThrow(new SQLException("gone"));
        assertNull(new LivingStore().latestEvent(870, "tamsin", List.of("bossgear.worn")));
    }
}

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * companion_named_lineup in LivingStore (changeset 46): an owner's lineups read back by name, each in place order; a
 * save replaces one lineup in one transaction (its old places gone, the new ones 1.. in order, 5 at most), and a failed
 * save rolls back and answers false. A failed read throws ReadFailed, never an empty map.
 */
class NamedLineupStoreTest {
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final List<String> sql = new ArrayList<>();
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void openTheDatabase() throws SQLException {
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
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
    void theLineupsAreReadByNameInPlaceOrder() throws SQLException {
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getString(1)).thenReturn("HUNTING", "PQ", "PQ");
        when(rows.getString(2)).thenReturn("mira", "casse", "vex");

        Map<String, List<String>> lineups = new LivingStore().namedLineups(10);

        assertEquals(Map.of("HUNTING", List.of("mira"), "PQ", List.of("casse", "vex")), lineups);
        assertTrue(sql.get(0).contains("FROM companion_named_lineup WHERE owner_character_id = ?"));
        assertTrue(sql.get(0).contains("ORDER BY lineup, slot"));
        verify(statement).setInt(1, 10);
    }

    @Test
    void aSaveReplacesTheLineupInOneTransactionFivePlacesAtMost() throws SQLException {
        assertTrue(new LivingStore().saveNamedLineup(10, "PQ", List.of("casse", "vex", "mira", "tobi", "hadrik", "pip")));

        assertTrue(sql.get(0).startsWith("DELETE FROM companion_named_lineup WHERE owner_character_id = ? AND lineup = ?"));
        assertTrue(sql.get(1).startsWith("INSERT INTO companion_named_lineup (owner_character_id, lineup, slot, persona_id)"));
        var order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(statement).executeUpdate();
        order.verify(statement).executeBatch();
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
        verify(statement).setString(4, "hadrik");
        verify(statement).setInt(3, 5);
        verify(statement, never()).setString(4, "pip");
    }

    @Test
    void aFailedSaveRollsBackAndAnswersFalse() throws SQLException {
        when(statement.executeBatch()).thenThrow(new SQLException("gone"));

        assertFalse(new LivingStore().saveNamedLineup(10, "PQ", List.of("casse")));

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void aFailedReadThrows() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("gone"));

        assertThrows(LivingStore.ReadFailed.class, () -> new LivingStore().namedLineups(10));
    }
}

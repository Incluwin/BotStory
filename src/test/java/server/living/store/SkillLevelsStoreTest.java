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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The level of each skill a character holds (LivingStore.skillLevels: what weapon a released companion's build fights
 * with, CompanionGearTool): one entry per skills row of that character. Empty for no rows, null for a failed read.
 */
class SkillLevelsStoreTest {
    private static final int SWORD_MASTERY = 1100000;
    private static final int POWER_STRIKE = 1001004;

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
    void eachSkillRowOfTheCharacterByItsId() throws SQLException {
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getInt(1)).thenReturn(SWORD_MASTERY, POWER_STRIKE);
        when(rows.getInt(2)).thenReturn(1, 20);

        Map<Integer, Integer> levels = new LivingStore().skillLevels(501);

        assertEquals(Map.of(SWORD_MASTERY, 1, POWER_STRIKE, 20), levels);
        assertEquals("SELECT skillid, skilllevel FROM skills WHERE characterid = ?", sql.get(0));
        verify(statement).setInt(1, 501);
    }

    @Test
    void noRowsIsEmptyAndAFailedReadIsNull() throws SQLException {
        when(rows.next()).thenReturn(false);
        assertEquals(Map.of(), new LivingStore().skillLevels(501));

        when(statement.executeQuery()).thenThrow(new SQLException("gone"));
        assertNull(new LivingStore().skillLevels(501));
    }
}

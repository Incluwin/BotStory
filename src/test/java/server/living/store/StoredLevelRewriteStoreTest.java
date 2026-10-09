package server.living.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.store.LivingStore.LevelRewrite;
import server.living.store.LivingStore.StoredLevel;
import server.living.store.LivingStore.StoredSkill;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A released companion's stored level rewrite (LivingStore.rewriteStoredLevel, ReleasedLevelRestore): one transaction
 * reads its characters row (locked) and skill rows, and writes the level, job, EXP, stats, AP, SP and HP/MP the plan
 * decided and its skill rows; nothing is written when the plan says null or there is no row, and a failed write is
 * rolled back.
 */
class StoredLevelRewriteStoreTest {
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement items = mock(PreparedStatement.class);
    private final PreparedStatement character = mock(PreparedStatement.class);
    private final PreparedStatement skills = mock(PreparedStatement.class);
    private final PreparedStatement update = mock(PreparedStatement.class);
    private final PreparedStatement delete = mock(PreparedStatement.class);
    private final PreparedStatement insert = mock(PreparedStatement.class);
    private final ResultSet characterRow = mock(ResultSet.class);
    private final ResultSet skillRows = mock(ResultSet.class);
    private final List<String> sql = new ArrayList<>();
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void openTheDatabase() throws SQLException {
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String query = inv.getArgument(0);
            sql.add(query);
            if (query.contains("inventoryitems")) {
                return items;
            }
            if (query.startsWith("SELECT level, job")) {
                return character;
            }
            if (query.startsWith("SELECT skillid")) {
                return skills;
            }
            if (query.startsWith("UPDATE characters")) {
                return update;
            }
            if (query.startsWith("DELETE FROM skills")) {
                return delete;
            }
            if (query.startsWith("INSERT INTO skills")) {
                return insert;
            }
            throw new SQLException("unexpected " + query);
        });
        ResultSet noItems = mock(ResultSet.class);
        when(items.executeQuery()).thenReturn(noItems);
        when(character.executeQuery()).thenReturn(characterRow);
        when(skills.executeQuery()).thenReturn(skillRows);
        when(update.executeUpdate()).thenReturn(1);
        // Hadrik as a GM set him: a Lv70 Crusader with one Crusader skill
        when(characterRow.next()).thenReturn(true);
        when(characterRow.getInt(anyInt())).thenAnswer(inv -> switch ((int) inv.getArgument(0)) {
            case 1 -> 70;
            case 2 -> 111;
            case 3 -> 900;
            case 4 -> 250;
            case 5 -> 60;
            case 6, 7 -> 4;
            case 8 -> 3;
            case 10 -> 0;
            case 11 -> 0;
            case 12 -> 24;
            default -> -1;
        });
        when(characterRow.getString(9)).thenReturn("5,0,0,0,0,0,0,0,0,0");
        when(skillRows.next()).thenReturn(true, false);
        when(skillRows.getInt(1)).thenReturn(1111002);
        when(skillRows.getInt(2)).thenReturn(20);
        when(skillRows.getInt(3)).thenReturn(0);
        when(skillRows.getLong(4)).thenReturn(-1L);
    }

    @AfterEach
    void closeTheDatabase() {
        database.close();
    }

    private static LevelRewrite fortyFighter() {
        return new LevelRewrite(40, 110, 1234, 35, 4, 4, 4, 168, new int[] {12, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 1500, 200,
                1500, 200, List.of(new StoredSkill(1001004, 20, 0, -1), new StoredSkill(1101004, 20, 0, -1)), Map.of());
    }

    @Test
    void itReadsTheLockedRowAndItsSkillsAndWritesWhatThePlanDecidedInOneTransaction() throws SQLException {
        List<StoredLevel> read = new ArrayList<>();

        int written = new LivingStore().rewriteStoredLevel(501, stored -> {
            read.add(stored);
            return fortyFighter();
        });

        assertEquals(1, written);
        StoredLevel stored = read.get(0);
        assertEquals(List.of(70, 111, 900, 250, 60, 4, 4, 3, 0, 0, 24), List.of(stored.level(), stored.jobId(),
                stored.exp(), stored.str(), stored.dex(), stored.intel(), stored.luk(), stored.ap(), stored.hpMpUsed(),
                stored.gmLevel(), stored.equipSlots()));
        assertArrayEquals(new int[] {5, 0, 0, 0, 0, 0, 0, 0, 0, 0}, stored.sp());
        assertEquals(List.of(new StoredSkill(1111002, 20, 0, -1)), stored.skills());
        assertTrue(sql.stream().anyMatch(query -> query.endsWith("FROM characters WHERE id = ? FOR UPDATE")), sql.toString());
        verify(update).setInt(1, 40);
        verify(update).setInt(2, 110);
        verify(update).setInt(3, 1234);
        verify(update).setInt(4, 35);
        verify(update).setInt(8, 168);
        verify(update).setString(9, "12,0,0,0,0,0,0,0,0,0");
        verify(update).setInt(10, 1500);
        verify(update).setInt(13, 200);
        verify(update).setInt(14, 501);
        verify(delete).setInt(1, 501);
        verify(insert, times(2)).addBatch();
        verify(insert).setInt(2, 1001004);
        verify(insert).setInt(2, 1101004);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void whenThePlanSaysNullNothingIsWritten() throws SQLException {
        assertEquals(0, new LivingStore().rewriteStoredLevel(501, stored -> null));

        verify(update, never()).executeUpdate();
        verify(delete, never()).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void withNoCharactersRowThePlanIsNeverAsked() throws SQLException {
        when(characterRow.next()).thenReturn(false);
        List<StoredLevel> read = new ArrayList<>();

        assertEquals(0, new LivingStore().rewriteStoredLevel(501, stored -> {
            read.add(stored);
            return fortyFighter();
        }));

        assertEquals(List.of(), read);
        verify(update, never()).executeUpdate();
    }

    @Test
    void aFailedWriteIsRolledBackAndSaysSo() throws SQLException {
        when(insert.executeBatch()).thenThrow(new SQLException("gone"));

        assertEquals(-1, new LivingStore().rewriteStoredLevel(501, stored -> fortyFighter()));

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).setAutoCommit(true);
    }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The start's release cleanup reads its candidates in one bounded query (LivingStore.releaseCleanupCandidates,
 * RosterService.finishReleaseCleanupAtStart): persona characters nobody employs (no living_roster row but a RELEASED
 * one) that still have a GM level hold with that persona or a stored item with the boss gear tag, worn or in the Equip
 * tab. Its persona rows never name a human's character; a failed read is null.
 */
class ReleaseCleanupCandidatesStoreTest {
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement query = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final List<String> sql = new ArrayList<>();
    private MockedStatic<DatabaseConnection> database;

    @BeforeEach
    void openTheDatabase() throws SQLException {
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            sql.add(inv.getArgument(0));
            return query;
        });
        when(query.executeQuery()).thenReturn(rows);
    }

    @AfterEach
    void closeTheDatabase() {
        database.close();
    }

    @Test
    void itReadsEveryPersonaNobodyEmploysWithAHoldOrTaggedGearInOneBoundedQuery() throws SQLException {
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("hadrik", "hire:543");
        when(rows.getInt(2)).thenReturn(501, 543);

        Map<String, Integer> candidates = new LivingStore().releaseCleanupCandidates("bossgear", 200);

        assertEquals(Map.of("hadrik", 501, "hire:543", 543), candidates);
        assertEquals(List.of("hadrik", "hire:543"), List.copyOf(candidates.keySet()), "in the order read");
        assertEquals(1, sql.size(), "one query");
        String read = sql.get(0);
        assertTrue(read.startsWith("SELECT p.persona_id, p.character_id FROM living_persona_state p"), read);
        assertTrue(read.contains("NOT EXISTS (SELECT 1 FROM living_roster r WHERE r.persona_id = p.persona_id"
                + " AND r.state <> 'RELEASED')"), "nobody employs it: " + read);
        assertTrue(read.contains("FROM inventoryitems i WHERE i.characterid = p.character_id AND i.type = ?"
                + " AND i.inventorytype IN (?, ?) AND i.owner = ?"), "its tagged gear: " + read);
        assertTrue(read.contains("FROM living_event_log e WHERE e.character_id = p.character_id"
                + " AND e.persona_id = p.persona_id AND e.kind IN ('gm.level.hold', 'gm.level.release')"
                + " ORDER BY e.id DESC LIMIT 1) = 'gm.level.hold'"), "its latest GM level row a hold: " + read);
        assertTrue(read.endsWith(" ORDER BY p.persona_id LIMIT ?"), read);
        verify(query).setInt(1, 1); // ItemFactory.INVENTORY
        verify(query).setInt(2, -1); // worn
        verify(query).setInt(3, 1); // the Equip tab
        verify(query).setString(4, "bossgear");
        verify(query).setInt(5, 200);
    }

    @Test
    void withNoneFoundItIsEmpty() throws SQLException {
        when(rows.next()).thenReturn(false);

        assertEquals(Map.of(), new LivingStore().releaseCleanupCandidates("bossgear", 200));
    }

    @Test
    void aFailedReadIsNull() throws SQLException {
        when(query.executeQuery()).thenThrow(new SQLException("gone"));

        assertNull(new LivingStore().releaseCleanupCandidates("bossgear", 200));
    }
}

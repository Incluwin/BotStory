package server.living.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.living.store.LivingStore.PqRunPaceRow;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pq_run_pace in LivingStore (changeset 44): a run's row is written column by column in a UTC session, an estimate not
 * made and a time not reached as SQL NULL and recorded_at as the UTC wall clock; a quest's rows of one epoch are read
 * newest first, up to the limit, NULLs back as null. A failed write answers false; a failed read throws ReadFailed,
 * never an empty list.
 */
class PqRunPaceStoreTest {
    private static final long RECORDED = Instant.parse("2027-03-14T02:28:19Z").toEpochMilli(); // New York's DST gap
    private static final PqRunPaceRow ROW = new PqRunPaceRow(0L, "kpq", "kpq-8353c615", 2, 4, 1, 1, "cleared", 810,
            1_062, 1_398, 2, 1_031, 1_790_000_000_000L, 1_790_001_031_000L, "[spans]", "[members]", "{drawn}", RECORDED);
    private static final PqRunPaceRow FAILED = new PqRunPaceRow(0L, "kpq", "kpq-x", 865, 3, 1, 1, "failed", null,
            null, null, null, null, 1_790_000_000_000L, 0L, "[]", "[]", "{}", RECORDED);

    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private MockedStatic<DatabaseConnection> database;
    private String sql;

    @BeforeEach
    void openTheDatabase() throws SQLException {
        database = mockStatic(DatabaseConnection.class);
        database.when(DatabaseConnection::getConnection).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            sql = inv.getArgument(0);
            return statement;
        });
        when(statement.executeQuery()).thenReturn(rows);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aRowIsWrittenColumnByColumnInAUtcSession() throws SQLException {
        assertTrue(new LivingStore().savePqRunPace(ROW));

        assertEquals("INSERT /*+ SET_VAR(time_zone = '+00:00') */ INTO pq_run_pace (pq_key, session_id,"
                + " leader_character_id, party_size, humans, epoch, outcome, est_p10_s, est_p50_s, est_p90_s, est_records,"
                + " actual_s, started_at_ms, cleared_at_ms, spans, members, drawn, recorded_at) VALUES (?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", sql);
        InOrder bound = inOrder(statement);
        bound.verify(statement).setString(1, "kpq");
        bound.verify(statement).setString(2, "kpq-8353c615");
        bound.verify(statement).setInt(3, 2);
        bound.verify(statement).setInt(4, 4);
        bound.verify(statement).setInt(5, 1);
        bound.verify(statement).setInt(6, 1);
        bound.verify(statement).setString(7, "cleared");
        bound.verify(statement).setInt(8, 810);
        bound.verify(statement).setInt(9, 1_062);
        bound.verify(statement).setInt(10, 1_398);
        bound.verify(statement).setInt(11, 2);
        bound.verify(statement).setInt(12, 1_031);
        bound.verify(statement).setLong(13, 1_790_000_000_000L);
        bound.verify(statement).setLong(14, 1_790_001_031_000L);
        bound.verify(statement).setString(15, "[spans]");
        bound.verify(statement).setString(16, "[members]");
        bound.verify(statement).setString(17, "{drawn}");
        bound.verify(statement).setObject(18, LocalDateTime.parse("2027-03-14T02:28:19"));
        bound.verify(statement).executeUpdate();
    }

    @Test
    void anEstimateNotMadeAndATimeNotReachedAreNull() throws SQLException {
        assertTrue(new LivingStore().savePqRunPace(FAILED));

        for (int column = 8; column <= 12; column++) {
            verify(statement).setNull(column, Types.INTEGER);
        }
        verify(statement).setLong(14, 0L);
    }

    @Test
    void aFailedWriteAnswersFalse() throws SQLException {
        when(statement.executeUpdate()).thenThrow(new SQLException("duplicate session_id"));

        assertFalse(new LivingStore().savePqRunPace(ROW));
    }

    @Test
    void aQuestsRowsOfOneEpochAreReadNewestFirstUpToTheLimit() throws SQLException {
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong("id")).thenReturn(7L, 5L);
        when(rows.getString("pq_key")).thenReturn("kpq", "kpq");
        when(rows.getString("session_id")).thenReturn("kpq-8353c615", "kpq-x");
        when(rows.getInt("leader_character_id")).thenReturn(2, 865);
        when(rows.getInt("party_size")).thenReturn(4, 3);
        when(rows.getInt("humans")).thenReturn(1, 1);
        when(rows.getInt("epoch")).thenReturn(1, 1);
        when(rows.getString("outcome")).thenReturn("cleared", "failed");
        when(rows.getInt("est_p10_s")).thenReturn(810, 0);
        when(rows.getInt("est_p50_s")).thenReturn(1_062, 0);
        when(rows.getInt("est_p90_s")).thenReturn(1_398, 0);
        when(rows.getInt("est_records")).thenReturn(2, 0);
        when(rows.getInt("actual_s")).thenReturn(1_031, 0);
        // the first row's five nullable reads are values; the second row's are SQL NULL
        when(rows.wasNull()).thenReturn(false, false, false, false, false, true, true, true, true, true);
        when(rows.getLong("started_at_ms")).thenReturn(1_790_000_000_000L, 1_790_000_000_000L);
        when(rows.getLong("cleared_at_ms")).thenReturn(1_790_001_031_000L, 0L);
        when(rows.getString("spans")).thenReturn("[spans]", "[]");
        when(rows.getString("members")).thenReturn("[members]", "[]");
        when(rows.getString("drawn")).thenReturn("{drawn}", "{}");
        when(rows.getObject("recorded_at", LocalDateTime.class)).thenReturn(LocalDateTime.parse("2027-03-14T02:28:19"));

        List<PqRunPaceRow> read = new LivingStore().pqRunPaces("kpq", 1, 60);

        assertEquals(List.of(withId(ROW, 7L), withId(FAILED, 5L)), read);
        assertEquals("SELECT /*+ SET_VAR(time_zone = '+00:00') */ id, pq_key, session_id, leader_character_id,"
                + " party_size, humans, epoch, outcome, est_p10_s, est_p50_s, est_p90_s, est_records, actual_s,"
                + " started_at_ms, cleared_at_ms, spans, members, drawn, recorded_at FROM pq_run_pace WHERE pq_key = ?"
                + " AND epoch = ? ORDER BY id DESC LIMIT ?", sql);
        verify(statement).setString(1, "kpq");
        verify(statement).setInt(2, 1);
        verify(statement).setInt(3, 60);
    }

    @Test
    void aFailedReadThrowsRatherThanReadingAsNoRuns() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("gone"));

        assertThrows(LivingStore.ReadFailed.class, () -> new LivingStore().pqRunPaces("kpq", 1, 60));
    }

    private static PqRunPaceRow withId(PqRunPaceRow row, long id) {
        return new PqRunPaceRow(id, row.pqKey(), row.sessionId(), row.leaderId(), row.partySize(), row.humans(),
                row.epoch(), row.outcome(), row.estP10S(), row.estP50S(), row.estP90S(), row.estRecords(),
                row.actualS(), row.startedAtMs(), row.clearedAtMs(), row.spans(), row.members(), row.drawn(),
                row.recordedAtMs());
    }
}

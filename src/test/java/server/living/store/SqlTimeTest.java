package server.living.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Living timestamps cross JDBC as UTC wall clocks whatever the JVM zone, so a New York DST gap or overlap never reaches
 * MySQL (2026-09-30: citizen 366's retires_at, 2027-03-14T02:28:19Z, rendered in GMT fell in the spring-forward gap and
 * every save of the row failed). CitizenStoreDstMySqlIntegrationTest covers the same instants against MySQL.
 */
class SqlTimeTest {
    static final long SPRING_FORWARD_GAP_AS_GMT = Instant.parse("2027-03-14T02:28:19Z").toEpochMilli();
    /** 01:30 in New York twice on 2026-11-01: first as EDT, then as EST. */
    static final long FALL_BACK_EDT = Instant.parse("2026-11-01T05:30:00Z").toEpochMilli();
    static final long FALL_BACK_EST = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli();

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(original);
    }

    @Test
    void hintGoesAfterTheVerb() {
        assertEquals("SELECT /*+ SET_VAR(time_zone = '+00:00') */ a FROM t", SqlTime.utc("SELECT a FROM t"));
        assertEquals("UPDATE /*+ SET_VAR(time_zone = '+00:00') */ t SET a = ?", SqlTime.utc("UPDATE t SET a = ?"));
        assertThrows(IllegalArgumentException.class, () -> SqlTime.utc(" SELECT 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GMT", "America/New_York"})
    void bindsTheUtcWallClockWhateverTheJvmZone(String zone) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        PreparedStatement ps = mock(PreparedStatement.class);

        SqlTime.set(ps, 1, SPRING_FORWARD_GAP_AS_GMT);
        SqlTime.setNullable(ps, 2, FALL_BACK_EDT);
        SqlTime.setNullable(ps, 3, FALL_BACK_EST);
        SqlTime.setNullable(ps, 4, 0L);

        verify(ps).setObject(1, LocalDateTime.parse("2027-03-14T02:28:19"));
        verify(ps).setObject(2, LocalDateTime.parse("2026-11-01T05:30:00"));
        verify(ps).setObject(3, LocalDateTime.parse("2026-11-01T06:30:00"));
        verify(ps).setNull(4, Types.TIMESTAMP);
        verifyNoMoreInteractions(ps); // never a java.sql.Timestamp, which the driver would render in a local zone
    }

    @ParameterizedTest
    @ValueSource(strings = {"GMT", "America/New_York"})
    void readsTheUtcWallClockBackAsTheSameInstant(String zone) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("retires_at", LocalDateTime.class)).thenReturn(LocalDateTime.parse("2027-03-14T02:28:19"));
        when(rs.getObject(1, LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-11-01T05:30:00"));
        when(rs.getObject(2, LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-11-01T06:30:00"));

        assertEquals(SPRING_FORWARD_GAP_AS_GMT, SqlTime.get(rs, "retires_at"));
        assertEquals(FALL_BACK_EDT, SqlTime.get(rs, 1));
        assertEquals(FALL_BACK_EST, SqlTime.get(rs, 2));
        assertEquals(0L, SqlTime.get(rs, "last_online_at")); // SQL NULL
    }
}

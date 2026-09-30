package server.living.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Epoch millis in and out of the living tables' TIMESTAMP columns, in UTC at both ends.
 *
 * <p>MySQL stores a TIMESTAMP as UTC but converts it through the session time zone, which is SYSTEM here
 * (America/New_York). Connector/J renders a {@link java.sql.Timestamp} in the zone its pooled connection captured when
 * it opened: the machine zone for the first connections, then server.TIMEZONE (GMT) once Server has set the JVM
 * default. A GMT wall clock that falls in New York's spring-forward hour (2027-03-14 02:00-03:00) then fails the whole
 * write with "Incorrect datetime value", and one in the fall-back hour is ambiguous. So each statement that binds or
 * reads such a column is built with {@link #utc(String)}, which runs it in a +00:00 session. Values cross JDBC as UTC
 * {@link LocalDateTime}, which the driver passes through without zone conversion.
 *
 * <p>{@link tools.DatabaseConnection} now opens every pooled session in UTC too; the hint keeps these statements right
 * whatever zone a connection has.
 */
public final class SqlTime {
    private static final String UTC_SESSION = " /*+ SET_VAR(time_zone = '+00:00') */";

    private SqlTime() {
    }

    /** {@code sql} run in a +00:00 session: a hint after its leading verb (SELECT, INSERT, UPDATE or DELETE). */
    public static String utc(String sql) {
        int verbEnd = sql.indexOf(' ');
        if (verbEnd <= 0) {
            throw new IllegalArgumentException("expected a statement verb first: " + sql);
        }
        return sql.substring(0, verbEnd) + UTC_SESSION + sql.substring(verbEnd);
    }

    public static LocalDateTime toUtc(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    public static long fromUtc(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** Binds {@code ms} in a statement built with {@link #utc(String)}. */
    public static void set(PreparedStatement ps, int index, long ms) throws SQLException {
        ps.setObject(index, toUtc(ms));
    }

    /** As {@link #set}, with 0 or less bound as SQL NULL. */
    public static void setNullable(PreparedStatement ps, int index, long ms) throws SQLException {
        if (ms <= 0L) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            set(ps, index, ms);
        }
    }

    /** A column read by a statement built with {@link #utc(String)}, in epoch millis; 0 for NULL. */
    public static long get(ResultSet rs, String column) throws SQLException {
        LocalDateTime utc = rs.getObject(column, LocalDateTime.class);
        return utc == null ? 0L : fromUtc(utc);
    }

    public static long get(ResultSet rs, int index) throws SQLException {
        LocalDateTime utc = rs.getObject(index, LocalDateTime.class);
        return utc == null ? 0L : fromUtc(utc);
    }
}

package server.living.population.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC for {@code living_world_event} (changeset 68) and the player samples an event's hour is picked from. A read that
 * failed is null (the caller tries again next minute), never an empty answer; a write that failed is false and logged.
 */
final class WorldEventStore implements WorldEvents.Store {
    private static final Logger log = LoggerFactory.getLogger(WorldEventStore.class);
    private static final String COLUMNS = "SELECT id, event_id, occurrence, starts_at, ends_at, state, channel, site, result"
            + " FROM living_world_event ";

    @Override
    public List<WorldEvents.Occurrence> open() {
        return read(COLUMNS + "WHERE state IN ('PLANNED', 'RUNNING') ORDER BY starts_at", ps -> { });
    }

    @Override
    public List<WorldEvents.Occurrence> between(long fromMs, long toMs) {
        return read(COLUMNS + "WHERE starts_at >= ? AND starts_at < ? ORDER BY starts_at", ps -> {
            SqlTime.set(ps, 1, fromMs);
            SqlTime.set(ps, 2, toMs);
        });
    }

    @Override
    public Boolean exists(String eventId, int occurrence) {
        if (!DatabaseConnection.isInitialized()) {
            return null;
        }
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM living_world_event WHERE event_id = ? AND occurrence = ?")) {
            ps.setString(1, eventId);
            ps.setInt(2, occurrence);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException failure) {
            log.warn("living_world_event read failed for {} {}", eventId, occurrence, failure);
            return null;
        }
    }

    @Override
    public boolean insert(String eventId, int occurrence, long startsAtMs, long endsAtMs, int channel, String site) {
        return write("INSERT IGNORE INTO living_world_event (event_id, occurrence, starts_at, ends_at, state, channel, site)"
                + " VALUES (?, ?, ?, ?, 'PLANNED', ?, ?)", ps -> {
            ps.setString(1, eventId);
            ps.setInt(2, occurrence);
            SqlTime.set(ps, 3, startsAtMs);
            SqlTime.set(ps, 4, endsAtMs);
            ps.setInt(5, channel);
            ps.setString(6, site);
        });
    }

    @Override
    public boolean claim(long id, long endsAtMs, int channel) {
        return write("UPDATE living_world_event SET state = 'RUNNING', ends_at = ?, channel = ? WHERE id = ?"
                + " AND state = 'PLANNED'", ps -> {
            SqlTime.set(ps, 1, endsAtMs);
            ps.setInt(2, channel);
            ps.setLong(3, id);
        });
    }

    @Override
    public boolean finish(long id, String state, String result) {
        return write("UPDATE living_world_event SET state = ?, result = ? WHERE id = ? AND state IN ('PLANNED', 'RUNNING')",
                ps -> {
                    ps.setString(1, state);
                    ps.setString(2, result.length() > 64 ? result.substring(0, 64) : result);
                    ps.setLong(3, id);
                });
    }

    /** Epoch hour -> humans online in it on average, all channels (as /runtime/stats/player-samples adds them). */
    @Override
    public Map<Long, Double> samples(int days) {
        if (!DatabaseConnection.isInitialized()) {
            return null;
        }
        String sql = "SELECT hour_s, SUM(channel_avg) FROM (SELECT FLOOR(UNIX_TIMESTAMP(ts) / 3600) AS hour_s,"
                + " AVG(player_count) AS channel_avg FROM admin_player_samples WHERE ts >= (NOW() - INTERVAL ? DAY)"
                + " GROUP BY hour_s, world_id, channel_id) per_channel GROUP BY hour_s";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, days);
            Map<Long, Double> out = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getDouble(2));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("player samples read failed", failure);
            return null;
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<WorldEvents.Occurrence> read(String sql, Binder binder) {
        if (!DatabaseConnection.isInitialized()) {
            return null;
        }
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(SqlTime.utc(sql))) {
            binder.bind(ps);
            List<WorldEvents.Occurrence> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WorldEvents.Occurrence(rs.getLong(1), rs.getString(2), rs.getInt(3),
                            SqlTime.fromUtc(rs.getObject(4, LocalDateTime.class)),
                            SqlTime.fromUtc(rs.getObject(5, LocalDateTime.class)), rs.getString(6), rs.getInt(7),
                            rs.getString(8), rs.getString(9)));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("living_world_event read failed", failure);
            return null;
        }
    }

    private static boolean write(String sql, Binder binder) {
        if (!DatabaseConnection.isInitialized()) {
            return false;
        }
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(SqlTime.utc(sql))) {
            binder.bind(ps);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("living_world_event write failed", failure);
            return false;
        }
    }
}

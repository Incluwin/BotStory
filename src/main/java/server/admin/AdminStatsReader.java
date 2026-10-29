package server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.ledger.EconomyLedger;
import server.living.population.Population;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Phase D read helpers (ADMIN_DASHBOARD_PLAN.md §9). Queries the admin_*
 * analytics tables plus existing cosmic tables (characters, accounts) and
 * shapes the results for Chart.js in the dashboard.
 *
 * Every method is best-effort: on SQL errors it returns
 * {@code {"ok":false,"error":"sql"}} rather than propagating. That keeps
 * the stats page gracefully degraded when an index is missing or the
 * cosmic DB isn't reachable at that instant.
 */
final class AdminStatsReader {
    private static final Logger log = LoggerFactory.getLogger(AdminStatsReader.class);

    private AdminStatsReader() {}

    /** {@code admin_player_samples} rolled up per 5-min bucket across the window. */
    static String playerSamples(int hours) {
        if (hours <= 0 || hours > 24 * 30) hours = 24;
        String sql =
                "SELECT UNIX_TIMESTAMP(FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(ts)/300)*300)) AS bucket_s, " +
                "       SUM(player_count) AS total " +
                "FROM admin_player_samples " +
                "WHERE ts >= (NOW() - INTERVAL ? HOUR) " +
                "GROUP BY bucket_s ORDER BY bucket_s ASC";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, hours);
            AdminJson.Arr points = new AdminJson.Arr();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.obj()
                            .num("ts", rs.getLong("bucket_s"))
                            .num("total", rs.getLong("total"));
                }
            }
            return "{\"ok\":true,\"hours\":" + hours + ",\"points\":" + points + "}";
        } catch (SQLException e) {
            log.debug("playerSamples failed", e);
            return sqlErr();
        }
    }

    /** {@code admin_login_events} bucketed per hour over a rolling window. */
    static String loginEventsHourly(int hours) {
        if (hours <= 0 || hours > 24 * 60) hours = 168;
        String sql =
                "SELECT UNIX_TIMESTAMP(FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(ts)/3600)*3600)) AS bucket_s, " +
                "       event_type, COUNT(*) AS n " +
                "FROM admin_login_events " +
                "WHERE ts >= (NOW() - INTERVAL ? HOUR) " +
                "GROUP BY bucket_s, event_type ORDER BY bucket_s ASC";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, hours);
            AdminJson.Arr points = new AdminJson.Arr();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.obj()
                            .num("ts", rs.getLong("bucket_s"))
                            .str("event_type", rs.getString("event_type"))
                            .num("n", rs.getLong("n"));
                }
            }
            return "{\"ok\":true,\"hours\":" + hours + ",\"points\":" + points + "}";
        } catch (SQLException e) {
            log.debug("loginEventsHourly failed", e);
            return sqlErr();
        }
    }

    static String levelDistribution() {
        String sql =
                "SELECT level, COUNT(*) AS n FROM characters GROUP BY level ORDER BY level";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            AdminJson.Arr arr = new AdminJson.Arr();
            while (rs.next()) {
                arr.obj().num("level", rs.getInt("level")).num("n", rs.getLong("n"));
            }
            return "{\"ok\":true,\"levels\":" + arr + "}";
        } catch (SQLException e) {
            log.debug("levelDistribution failed", e);
            return sqlErr();
        }
    }

    static String jobDistribution() {
        String sql = "SELECT job, COUNT(*) AS n FROM characters GROUP BY job ORDER BY n DESC LIMIT 40";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            AdminJson.Arr arr = new AdminJson.Arr();
            while (rs.next()) {
                arr.obj().num("job", rs.getInt("job")).num("n", rs.getLong("n"));
            }
            return "{\"ok\":true,\"jobs\":" + arr + "}";
        } catch (SQLException e) {
            log.debug("jobDistribution failed", e);
            return sqlErr();
        }
    }

    static String topMaps(int limit) {
        if (limit <= 0 || limit > 200) limit = 20;
        String sql =
                "SELECT map_id, SUM(count) AS character_minutes " +
                "FROM admin_map_presence_samples " +
                "WHERE ts >= (NOW() - INTERVAL 30 DAY) " +
                "GROUP BY map_id ORDER BY character_minutes DESC LIMIT ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limit);
            AdminJson.Arr arr = new AdminJson.Arr();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Note: count represents a 5-min-interval head-count, so summing
                    // gives a rough "character-intervals." Label it clearly.
                    arr.obj()
                            .num("map_id", rs.getInt("map_id"))
                            .num("character_intervals", rs.getLong("character_minutes"));
                }
            }
            return "{\"ok\":true,\"window\":\"30d\",\"maps\":" + arr + "}";
        } catch (SQLException e) {
            log.debug("topMaps failed", e);
            return sqlErr();
        }
    }

    /** All the meso there is by pool and holder, and the ledger's balance since boot (docs/ECONOMY_PLAN.md E0). */
    static String mesoSupply() {
        try {
            return EconomyLedger.supplyJson();
        } catch (SQLException e) {
            log.debug("mesoSupply failed", e);
            return sqlErr();
        }
    }

    static String mesoFlows(int days) {
        try {
            return EconomyLedger.flowsJson(days);
        } catch (SQLException e) {
            log.debug("mesoFlows failed", e);
            return sqlErr();
        }
    }

    static String closes(int days) {
        try {
            return EconomyLedger.closesJson(days);
        } catch (SQLException e) {
            log.debug("closes failed", e);
            return sqlErr();
        }
    }

    /** The citizen market since boot: stalls, purchases, gear errands (docs/ECONOMY_PLAN.md E3). */
    static String market() {
        Population population = Population.get();
        return population == null ? "{\"ok\":false,\"error\":\"population_off\"}" : population.market().statsJson();
    }

    /** {@code days=N} from the query (1-365), else {@code fallback}. */
    static int days(String rawQuery, int fallback) {
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                if (pair.startsWith("days=")) {
                    try {
                        return Math.clamp(Integer.parseInt(pair.substring(5)), 1, 365);
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                }
            }
        }
        return fallback;
    }

    static String newAccountsPerDay(int days) {
        if (days <= 0 || days > 365) days = 30;
        String sql =
                "SELECT DATE(createdat) AS d, COUNT(*) AS n " +
                "FROM accounts " +
                "WHERE createdat >= (NOW() - INTERVAL ? DAY) " +
                "GROUP BY d ORDER BY d ASC";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, days);
            AdminJson.Arr arr = new AdminJson.Arr();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Date d = rs.getDate("d");
                    arr.obj()
                            .str("date", d == null ? "" : d.toString())
                            .num("n", rs.getLong("n"));
                }
            }
            return "{\"ok\":true,\"days\":" + days + ",\"buckets\":" + arr + "}";
        } catch (SQLException e) {
            log.debug("newAccountsPerDay failed", e);
            return sqlErr();
        }
    }

    /** DAU/WAU/MAU based on admin_login_events distinct account_id. */
    static String retention() {
        String sql =
                "SELECT " +
                "  COUNT(DISTINCT CASE WHEN ts >= NOW() - INTERVAL 1 DAY THEN account_id END) AS dau," +
                "  COUNT(DISTINCT CASE WHEN ts >= NOW() - INTERVAL 7 DAY THEN account_id END) AS wau," +
                "  COUNT(DISTINCT CASE WHEN ts >= NOW() - INTERVAL 30 DAY THEN account_id END) AS mau " +
                "FROM admin_login_events WHERE event_type = 'login' AND account_id IS NOT NULL";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            long dau = 0, wau = 0, mau = 0;
            if (rs.next()) {
                dau = rs.getLong("dau");
                wau = rs.getLong("wau");
                mau = rs.getLong("mau");
            }
            return "{\"ok\":true,\"dau\":" + dau + ",\"wau\":" + wau + ",\"mau\":" + mau + "}";
        } catch (SQLException e) {
            log.debug("retention failed", e);
            return sqlErr();
        }
    }

    private static String sqlErr() {
        return "{\"ok\":false,\"error\":\"sql\"}";
    }
}

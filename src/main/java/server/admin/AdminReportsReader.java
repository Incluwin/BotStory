package server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Thin reader for the {@code reports} table (populated by player !reportbug).
 * Phase C of ADMIN_DASHBOARD_PLAN.md §8 ("Report queue"). Triage state
 * (assigned_to / resolved_at / notes) lives in SQLite on the MapleEdit side
 * under services.reports_state, since we don't have a Python MySQL driver;
 * this class only surfaces the raw reports so the dashboard can render them.
 */
final class AdminReportsReader {
    private static final Logger log = LoggerFactory.getLogger(AdminReportsReader.class);

    private AdminReportsReader() {}

    static String list(int limit) {
        if (limit <= 0 || limit > 1000) limit = 200;
        String sql =
                "SELECT r.id, r.reporttime, r.reporterid, r.victimid, r.reason, " +
                "       r.description, r.chatlog, " +
                "       rc.name AS reporter_name, vc.name AS victim_name " +
                "FROM reports r " +
                "LEFT JOIN characters rc ON rc.id = r.reporterid " +
                "LEFT JOIN characters vc ON vc.id = r.victimid " +
                "ORDER BY r.reporttime DESC LIMIT ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limit);
            AdminJson.Arr arr = new AdminJson.Arr();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AdminJson.Obj o = arr.obj();
                    o.num("id", rs.getInt("id"));
                    java.sql.Timestamp ts = rs.getTimestamp("reporttime");
                    if (ts != null) o.num("ts_ms", ts.getTime());
                    o.num("reporter_id", rs.getInt("reporterid"));
                    o.num("victim_id", rs.getInt("victimid"));
                    o.num("reason", rs.getInt("reason"));
                    String rn = rs.getString("reporter_name");
                    if (rn != null) o.str("reporter_name", rn);
                    String vn = rs.getString("victim_name");
                    if (vn != null) o.str("victim_name", vn);
                    String desc = rs.getString("description");
                    if (desc != null) o.str("description", desc);
                    String chat = rs.getString("chatlog");
                    if (chat != null) {
                        // Chat logs can be long; truncate for list view. Detail
                        // endpoint can surface the full text later.
                        if (chat.length() > 400) chat = chat.substring(0, 400) + "…";
                        o.str("chatlog", chat);
                    }
                }
            }
            return "{\"ok\":true,\"reports\":" + arr + "}";
        } catch (SQLException e) {
            log.debug("reports list failed", e);
            return "{\"ok\":false,\"error\":\"sql\"}";
        }
    }
}

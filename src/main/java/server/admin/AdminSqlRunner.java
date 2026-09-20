package server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Execute ad-hoc SQL against the cosmic DB from the MapleEdit admin dashboard.
 *
 * Safety rails (ADMIN_DASHBOARD_PLAN.md §8 "Canned SQL runner"):
 *   1. Single-statement only — multiple statements rejected by scanning for a
 *      semicolon that isn't inside a quoted literal.
 *   2. 10-second query timeout enforced via {@code Statement.setQueryTimeout}.
 *   3. SELECT without an explicit LIMIT is rejected. (Caller can add LIMIT.)
 *   4. DML (UPDATE / INSERT / DELETE / REPLACE / ALTER / DROP / CREATE /
 *      TRUNCATE / RENAME) only runs when {@code allowMutation=true}; even
 *      then, only a single statement, and the row is always audit-logged on
 *      the Python side before dispatch.
 *   5. Result rows capped at {@code ROW_CAP} to avoid OOMing the dashboard.
 *
 * This class is only reached via the bearer-authenticated control port, and
 * the control port is already tier-gated on the Flask side — so the runner
 * is not a permission boundary, just a defensive sanity filter.
 */
final class AdminSqlRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSqlRunner.class);

    static final int ROW_CAP = 2000;
    static final int TIMEOUT_S = 10;

    private AdminSqlRunner() {}

    static String run(String sql, boolean allowMutation) {
        if (sql == null) return err("empty_sql");
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) return err("empty_sql");

        if (hasExtraStatement(trimmed)) return err("multi_statement_rejected");

        String lower = trimmed.toLowerCase();
        boolean isSelect = lower.startsWith("select") || lower.startsWith("with") || lower.startsWith("show") || lower.startsWith("explain") || lower.startsWith("describe") || lower.startsWith("desc ");
        boolean isDml = !isSelect;

        if (isDml && !allowMutation) {
            return err("dml_requires_confirm");
        }
        if (isSelect && !containsLimitClause(lower)) {
            return err("select_without_limit");
        }

        try (Connection con = DatabaseConnection.getConnection()) {
            if (isSelect) {
                try (PreparedStatement ps = con.prepareStatement(trimmed)) {
                    ps.setQueryTimeout(TIMEOUT_S);
                    try (ResultSet rs = ps.executeQuery()) {
                        return formatResultSet(rs);
                    }
                }
            } else {
                try (Statement st = con.createStatement()) {
                    st.setQueryTimeout(TIMEOUT_S);
                    int affected = st.executeUpdate(trimmed);
                    return "{\"ok\":true,\"mode\":\"dml\",\"affected\":" + affected + "}";
                }
            }
        } catch (SQLException e) {
            log.debug("admin sql failed", e);
            return "{\"ok\":false,\"error\":\"sql\",\"detail\":"
                    + AdminJson.str(e.getMessage() == null ? e.toString() : e.getMessage()) + "}";
        } catch (Throwable t) {
            log.debug("admin sql unexpected", t);
            return err("unexpected");
        }
    }

    private static String err(String code) {
        return "{\"ok\":false,\"error\":" + AdminJson.str(code) + "}";
    }

    private static String formatResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        AdminJson.Arr columns = new AdminJson.Arr();
        for (int i = 1; i <= cols; i++) {
            columns.obj()
                    .str("name", md.getColumnLabel(i))
                    .str("type", md.getColumnTypeName(i));
        }
        AdminJson.Arr rows = new AdminJson.Arr();
        int n = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (n >= ROW_CAP) { truncated = true; break; }
            AdminJson.Obj o = rows.obj();
            for (int i = 1; i <= cols; i++) {
                String name = md.getColumnLabel(i);
                Object val = rs.getObject(i);
                if (val == null) {
                    o.raw(name, "null");
                } else if (val instanceof Number) {
                    o.raw(name, val.toString());
                } else if (val instanceof Boolean) {
                    o.bool(name, (Boolean) val);
                } else {
                    o.str(name, val.toString());
                }
            }
            n++;
        }
        return "{\"ok\":true,\"mode\":\"select\",\"columns\":" + columns
                + ",\"row_count\":" + n
                + ",\"truncated\":" + truncated
                + ",\"rows\":" + rows + "}";
    }

    /** Return true if there's a SQL-significant {@code ;} outside of string literals. */
    private static boolean hasExtraStatement(String sql) {
        boolean inSingle = false, inDouble = false, inBacktick = false;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (!inDouble && !inBacktick && c == '\'') {
                // handle escaped '' inside single-quoted strings
                if (inSingle && i + 1 < n && sql.charAt(i + 1) == '\'') { i++; continue; }
                inSingle = !inSingle;
            } else if (!inSingle && !inBacktick && c == '"') {
                if (inDouble && i + 1 < n && sql.charAt(i + 1) == '"') { i++; continue; }
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && c == '`') {
                inBacktick = !inBacktick;
            } else if (!inSingle && !inDouble && !inBacktick && c == ';') {
                // Allow a trailing ; but no second statement after it.
                for (int j = i + 1; j < n; j++) {
                    char cj = sql.charAt(j);
                    if (!Character.isWhitespace(cj)) return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Loose "does this SELECT have a LIMIT clause?" check. */
    private static boolean containsLimitClause(String lower) {
        int idx = lower.lastIndexOf(" limit ");
        if (idx < 0) idx = lower.lastIndexOf("\nlimit ");
        if (idx < 0) idx = lower.lastIndexOf("\tlimit ");
        return idx >= 0;
    }
}

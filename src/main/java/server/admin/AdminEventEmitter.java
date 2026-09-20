package server.admin;

import client.Client;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Emits rows into {@code admin_login_events} on login / logout.
 *
 * ADMIN_DASHBOARD_PLAN.md §12. Called from Client.updateLoginState(). All
 * writes run on a small daemon executor so login-hot-path latency doesn't
 * take a DB round-trip; failed writes log and drop — the table is an
 * observability aid, not a consistency requirement.
 *
 * Disabled cheaply via YamlConfig.config.server.ENABLE_ADMIN_CONTROL_PORT —
 * we piggyback on the same flag since both are "admin dashboard data."
 * A dedicated flag can be added if instrumentation needs to diverge.
 */
public final class AdminEventEmitter {
    private static final Logger log = LoggerFactory.getLogger(AdminEventEmitter.class);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "admin-event-writer");
        t.setDaemon(true);
        return t;
    });

    private AdminEventEmitter() {}

    public static void login(Client c) {
        emit("login", c);
    }

    public static void logout(Client c) {
        emit("logout", c);
    }

    private static void emit(String kind, Client c) {
        if (c == null) return;
        if (!config.YamlConfig.config.server.ENABLE_ADMIN_CONTROL_PORT) return;

        int accountId;
        int worldId = -1;
        int channelId = -1;
        Integer charId = null;
        String ip = null;
        String hwid = null;
        try {
            accountId = c.getAccID();
            worldId = c.getWorld();
            channelId = c.getChannel();
            if (c.getPlayer() != null) {
                charId = c.getPlayer().getId();
            }
            ip = c.getRemoteAddress();
            if (c.getHwid() != null) hwid = c.getHwid().hwid();
        } catch (Throwable t) {
            // Defensive: instrumentation should never break login.
            return;
        }

        final int accountIdF = accountId;
        final int worldIdF = worldId;
        final int channelIdF = channelId;
        final Integer charIdF = charId;
        final String ipF = ip;
        final String hwidF = hwid;
        try {
            WRITER.submit(() -> writeRow(kind, accountIdF, charIdF, worldIdF, channelIdF, ipF, hwidF));
        } catch (RejectedExecutionException e) {
            // Writer shut down. Silently drop.
        }
    }

    private static void writeRow(String kind, int accountId, Integer charId,
                                  int worldId, int channelId, String ip, String hwid) {
        String sql = "INSERT INTO admin_login_events "
                + "(event_type, account_id, character_id, world_id, channel_id, ip, hwid) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kind);
            if (accountId > 0) ps.setInt(2, accountId);
            else ps.setNull(2, java.sql.Types.INTEGER);
            if (charId != null) ps.setInt(3, charId);
            else ps.setNull(3, java.sql.Types.INTEGER);
            if (worldId >= 0) ps.setInt(4, worldId);
            else ps.setNull(4, java.sql.Types.INTEGER);
            if (channelId >= 0) ps.setInt(5, channelId);
            else ps.setNull(5, java.sql.Types.INTEGER);
            if (ip != null) ps.setString(6, ip);
            else ps.setNull(6, java.sql.Types.VARCHAR);
            if (hwid != null) ps.setString(7, hwid);
            else ps.setNull(7, java.sql.Types.VARCHAR);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("AdminEventEmitter: {} write failed", kind, e);
        } catch (Throwable t) {
            log.debug("AdminEventEmitter: unexpected", t);
        }
    }

    public static void shutdown() {
        WRITER.shutdown();
    }
}

package server.admin;

import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic sampler for Phase D analytics (ADMIN_DASHBOARD_PLAN.md §12).
 *
 *  - every 60s: one row per (world, channel) into {@code admin_player_samples}
 *  - every 5min: one row per (world, channel, map) with >0 players into
 *                {@code admin_map_presence_samples}
 *
 * Starts alongside the admin control port and terminates on server shutdown.
 * Failures log-and-drop; sampler availability must never block gameplay.
 */
public final class AdminSampler {
    private static final Logger log = LoggerFactory.getLogger(AdminSampler.class);
    private static ScheduledExecutorService exec;

    private AdminSampler() {}

    public static synchronized void start() {
        if (exec != null) return;
        if (!YamlConfig.config.server.ENABLE_ADMIN_CONTROL_PORT) return;

        exec = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "admin-sampler");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(AdminSampler::samplePlayerCounts,
                10, 60, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(AdminSampler::sampleMapPresence,
                30, 5 * 60, TimeUnit.SECONDS);
        log.info("AdminSampler started (player 60s, map presence 5min)");
    }

    public static synchronized void stop() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }

    private static void samplePlayerCounts() {
        String sql = "INSERT INTO admin_player_samples (world_id, channel_id, player_count) "
                + "VALUES (?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (World w : Server.getInstance().getWorlds()) {
                for (Channel ch : w.getChannels()) {
                    int count;
                    try { count = ch.getPlayerStorage().getAllCharacters().size(); }
                    catch (Throwable t) { count = 0; }
                    ps.setInt(1, w.getId());
                    ps.setInt(2, ch.getId());
                    ps.setInt(3, count);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.debug("AdminSampler: player sample failed", e);
        } catch (Throwable t) {
            log.debug("AdminSampler: player sample unexpected", t);
        }
    }

    private static void sampleMapPresence() {
        String sql = "INSERT INTO admin_map_presence_samples "
                + "(world_id, channel_id, map_id, count) VALUES (?, ?, ?, ?)";
        // One PreparedStatement, batched across all worlds/channels.
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            boolean any = false;
            for (World w : Server.getInstance().getWorlds()) {
                for (Channel ch : w.getChannels()) {
                    Map<Integer, Integer> byMap = new HashMap<>();
                    try {
                        for (Character chr : ch.getPlayerStorage().getAllCharacters()) {
                            byMap.merge(chr.getMapId(), 1, Integer::sum);
                        }
                    } catch (Throwable t) {
                        continue;
                    }
                    for (Map.Entry<Integer, Integer> e : byMap.entrySet()) {
                        if (e.getValue() <= 0) continue;
                        ps.setInt(1, w.getId());
                        ps.setInt(2, ch.getId());
                        ps.setInt(3, e.getKey());
                        ps.setInt(4, e.getValue());
                        ps.addBatch();
                        any = true;
                    }
                }
            }
            if (any) ps.executeBatch();
        } catch (SQLException e) {
            log.debug("AdminSampler: map presence sample failed", e);
        } catch (Throwable t) {
            log.debug("AdminSampler: map presence sample unexpected", t);
        }
    }
}

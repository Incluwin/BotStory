package server.bot;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Samples the live bot-economy state into {@code bot_economy_metrics} for
 * dashboard charts and the {@code !boteconomy report} command
 * (BOT_ECONOMY.md § Telemetry and economy dashboard).
 *
 * <p>Runs every {@code BOT_METRICS_SAMPLE_INTERVAL_MINUTES}. Keeps the
 * implementation small — the report formatter itself is a follow-up; v1 ships
 * the sampler so the time-series accumulates before the UI lands.
 */
public final class BotTelemetryService {
    private static final Logger log = LoggerFactory.getLogger(BotTelemetryService.class);

    private BotTelemetryService() {}

    public static void sample() {
        if (!YamlConfig.config.bot_economy.ENABLED) return;
        try (Connection con = DatabaseConnection.getConnection()) {
            int active = countActiveMerchants(con);
            InvCounts inv = inventoryCounts(con);
            BotTreasuryService.Balances bal = BotTreasuryService.snapshotBalances();
            int salesVelocity1h = salesVelocity1h(con);
            long dropSimMs = DropSimulationTask.lastTickMs();
            long lifecycleMs = BotMerchantManager.lastTickMs();
            int suppressed = BotSaturationService.suppressedCount();
            long floorClamps = BotPricingService.drainFloorClamps();
            long ceilingClamps = BotPricingService.drainCeilingClamps();

            String sql = "INSERT INTO bot_economy_metrics " +
                    "(active_merchant_count, inventory_pending, inventory_stocked, " +
                    " inventory_sold, inventory_archived, sink_total, event_pool_balance, " +
                    " gm_reserve_balance, sales_velocity_1h, drop_sim_tick_ms, " +
                    " lifecycle_tick_ms, saturation_suppressed_count, clamp_floor_count, " +
                    " clamp_ceiling_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, active);
                ps.setInt(2, inv.pending);
                ps.setInt(3, inv.stocked);
                ps.setInt(4, inv.sold);
                ps.setInt(5, inv.archived);
                ps.setLong(6, bal.sink());
                ps.setLong(7, bal.event());
                ps.setLong(8, bal.gmReserve());
                ps.setInt(9, salesVelocity1h);
                ps.setInt(10, (int) Math.min(Integer.MAX_VALUE, dropSimMs));
                ps.setInt(11, (int) Math.min(Integer.MAX_VALUE, lifecycleMs));
                ps.setInt(12, suppressed);
                ps.setLong(13, floorClamps);
                ps.setLong(14, ceilingClamps);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("BotTelemetryService.sample failed ({})", e.toString());
        }
    }

    private record InvCounts(int pending, int stocked, int sold, int archived) {}

    private static InvCounts inventoryCounts(Connection con) throws SQLException {
        int p = 0, s = 0, so = 0, a = 0;
        String sql = "SELECT status, COUNT(*) FROM bot_inventory GROUP BY status";
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String st = rs.getString(1);
                int n = rs.getInt(2);
                switch (st) {
                    case "pending"  -> p = n;
                    case "stocked"  -> s = n;
                    case "sold"     -> so = n;
                    case "archived" -> a = n;
                    default -> {}
                }
            }
        }
        return new InvCounts(p, s, so, a);
    }

    private static int countActiveMerchants(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM bot_merchants WHERE status = 'active'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int salesVelocity1h(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM bot_sales_ledger WHERE sold_at > NOW() - INTERVAL 1 HOUR");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Prune rows older than BOT_METRICS_RETENTION_DAYS. */
    public static void pruneOldSamples() {
        int retention = YamlConfig.config.bot_economy.BOT_METRICS_RETENTION_DAYS;
        if (retention <= 0) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM bot_economy_metrics WHERE sampled_at < NOW() - INTERVAL ? DAY")) {
            ps.setInt(1, retention);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("BotTelemetryService.pruneOldSamples failed ({})", e.toString());
        }
    }
}

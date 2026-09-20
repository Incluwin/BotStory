package server.bot;

import config.BotEconomyConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Partitions collected meso into sink / event_pool / gm_reserve buckets, with
 * the event pool capped by {@code BOT_EVENT_POOL_CAP}; overflow routes to the
 * sink. Uses the shadow tables when {@code SHADOW_MODE: true}.
 *
 * <p>All operations synchronize on a single lock because the treasury row is
 * a singleton: contention is low, consistency is paramount.
 */
public final class BotTreasuryService {
    private static final Logger log = LoggerFactory.getLogger(BotTreasuryService.class);
    private static final ReentrantLock LOCK = new ReentrantLock(true);

    private BotTreasuryService() {}

    private static String table() {
        return YamlConfig.config.bot_economy.SHADOW_MODE ? "bot_treasury_shadow" : "bot_treasury";
    }

    /**
     * Credit meso collected from a single bot merchant. Partitions per
     * config percentages. Returns the three bucket deltas after cap logic,
     * for logging / telemetry.
     */
    public static Partition credit(long meso) {
        if (meso <= 0) return new Partition(0, 0, 0, 0);
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        long sink = meso * cfg.BOT_MESO_SINK_PCT / 100L;
        long event = meso * cfg.BOT_MESO_EVENT_POOL_PCT / 100L;
        long gm = meso - sink - event;  // ensure total accounting

        LOCK.lock();
        try (Connection con = DatabaseConnection.getConnection()) {
            long currentEvent = readLong(con, "SELECT event_pool_balance FROM " + table() + " WHERE id=1");
            long overflow = 0;
            long eventApplied = event;
            long cap = cfg.BOT_EVENT_POOL_CAP;
            if (currentEvent + event > cap) {
                eventApplied = Math.max(0, cap - currentEvent);
                overflow = event - eventApplied;
                sink += overflow;
            }

            String sql = "UPDATE " + table() + " SET " +
                    "sink_total = sink_total + ?, " +
                    "event_pool_balance = event_pool_balance + ?, " +
                    "gm_reserve_balance = gm_reserve_balance + ?, " +
                    "lifetime_collected = lifetime_collected + ? " +
                    "WHERE id = 1";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, sink);
                ps.setLong(2, eventApplied);
                ps.setLong(3, gm);
                ps.setLong(4, meso);
                ps.executeUpdate();
            }
            return new Partition(sink, eventApplied, gm, overflow);
        } catch (SQLException e) {
            log.error("BotTreasuryService.credit failed (meso={})", meso, e);
            return new Partition(0, 0, 0, 0);
        } finally {
            LOCK.unlock();
        }
    }

    /** Spend from gm_reserve. Returns true on success; false if insufficient balance. */
    public static boolean spendFromGmReserve(long amount, String reason, String gmAccount) {
        if (amount <= 0) return false;
        LOCK.lock();
        try (Connection con = DatabaseConnection.getConnection()) {
            long current = readLong(con, "SELECT gm_reserve_balance FROM " + table() + " WHERE id=1");
            if (current < amount) return false;
            String sql = "UPDATE " + table() + " SET " +
                    "gm_reserve_balance = gm_reserve_balance - ?, " +
                    "lifetime_gm_spent = lifetime_gm_spent + ? " +
                    "WHERE id = 1";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, amount);
                ps.setLong(2, amount);
                ps.executeUpdate();
            }
            auditGmSpend(con, gmAccount, amount, reason, "gm_reserve");
            return true;
        } catch (SQLException e) {
            log.error("BotTreasuryService.spendFromGmReserve failed", e);
            return false;
        } finally {
            LOCK.unlock();
        }
    }

    /** Spend from event_pool with mandatory reason. Returns true on success. */
    public static boolean spendFromEventPool(long amount, String reason, String gmAccount) {
        if (amount <= 0 || reason == null || reason.isBlank()) return false;
        LOCK.lock();
        try (Connection con = DatabaseConnection.getConnection()) {
            long current = readLong(con, "SELECT event_pool_balance FROM " + table() + " WHERE id=1");
            if (current < amount) return false;
            String sql = "UPDATE " + table() + " SET " +
                    "event_pool_balance = event_pool_balance - ?, " +
                    "lifetime_gm_spent = lifetime_gm_spent + ? " +
                    "WHERE id = 1";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, amount);
                ps.setLong(2, amount);
                ps.executeUpdate();
            }
            auditGmSpend(con, gmAccount, amount, reason, "event_pool");
            return true;
        } catch (SQLException e) {
            log.error("BotTreasuryService.spendFromEventPool failed", e);
            return false;
        } finally {
            LOCK.unlock();
        }
    }

    private static void auditGmSpend(Connection con, String gmAccount, long amount, String reason, String bucket)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO bot_gm_audit (gm_account, command, arguments, effect_summary) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, gmAccount == null ? "unknown" : gmAccount);
            ps.setString(2, bucket.equals("gm_reserve") ? "treasury_spend_gm" : "treasury_spend_event");
            ps.setString(3, String.valueOf(amount));
            ps.setString(4, "Spent " + amount + " meso from " + bucket + " (reason: " + reason + ")");
            ps.executeUpdate();
        }
    }

    private static long readLong(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        }
        return 0L;
    }

    public record Partition(long sink, long eventPool, long gmReserve, long eventOverflow) {}

    public record Balances(long sink, long event, long gmReserve, long lifetime, long lifetimeGmSpent) {}

    public static Balances snapshotBalances() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT sink_total, event_pool_balance, gm_reserve_balance, lifetime_collected, lifetime_gm_spent FROM " + table() + " WHERE id=1");
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                return new Balances(
                        rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
            }
        } catch (SQLException e) {
            log.warn("BotTreasuryService.snapshotBalances failed ({})", e.toString());
        }
        return new Balances(0, 0, 0, 0, 0);
    }
}

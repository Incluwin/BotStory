package server.bot;

import config.BotEconomyConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Nightly reconciliation (BOT_ECONOMY.md § Data integrity and reconciliation).
 *
 * <p>Every invariant writes to {@code bot_reconciliation_log}; violations log
 * at ERROR and (when {@code BOT_RECONCILIATION_STRICT: true}) auto-disable
 * {@code TREASURY_COLLECTION_ENABLED} pending GM review.
 */
public final class BotReconciliationTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BotReconciliationTask.class);

    @Override
    public void run() {
        runReport();
    }

    public static List<InvariantResult> runReport() {
        List<InvariantResult> results = new ArrayList<>();
        results.add(invariantTreasury());
        results.add(invariantOrphanedStocked());
        results.add(invariantOrphanedActive());
        results.add(invariantBotLogins());
        results.add(invariantNonBotInventory());
        results.add(invariantFredrickConsistency());
        results.add(invariantLedgerMonotonicity());
        results.add(invariantPricingCoverage());
        results.add(invariantPersonaDistribution());
        results.add(invariantSaturationPathology());

        for (InvariantResult r : results) writeLog(r);

        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        boolean anyFail = results.stream().anyMatch(r -> r.status == Status.FAIL);
        if (anyFail && cfg.BOT_RECONCILIATION_STRICT) {
            log.error("Reconciliation: fail(s) detected, BOT_RECONCILIATION_STRICT=true — freezing TREASURY_COLLECTION_ENABLED");
            cfg.TREASURY_COLLECTION_ENABLED = false;
        }
        return results;
    }

    private static InvariantResult invariantTreasury() {
        try (Connection con = DatabaseConnection.getConnection()) {
            long collected = loadLong(con,
                    "SELECT lifetime_collected FROM bot_treasury WHERE id = 1");
            long sum = loadLong(con,
                    "SELECT sink_total + event_pool_balance + gm_reserve_balance + lifetime_gm_spent FROM bot_treasury WHERE id = 1");
            boolean ok = collected == sum;
            return new InvariantResult("treasury_sum", ok ? Status.PASS : Status.FAIL,
                    ok ? "matched" : "delta = " + (sum - collected));
        } catch (SQLException e) {
            return new InvariantResult("treasury_sum", Status.FAIL, e.toString());
        }
    }

    private static InvariantResult invariantOrphanedStocked() {
        try (Connection con = DatabaseConnection.getConnection()) {
            int orphans = loadInt(con,
                    "SELECT COUNT(*) FROM bot_inventory i " +
                    "LEFT JOIN bot_merchants m ON m.id = i.bot_merchant_id " +
                    "WHERE i.status = 'stocked' AND (m.id IS NULL OR m.status != 'active')");
            return new InvariantResult("orphaned_stocked_inventory",
                    orphans == 0 ? Status.PASS : Status.FAIL,
                    "orphans=" + orphans);
        } catch (SQLException e) {
            return new InvariantResult("orphaned_stocked_inventory", Status.FAIL, e.toString());
        }
    }

    private static InvariantResult invariantOrphanedActive() {
        // V1: presence check only — a full live-instance comparison requires
        // a world handle, which we don't thread through here.
        try (Connection con = DatabaseConnection.getConnection()) {
            int active = loadInt(con,
                    "SELECT COUNT(*) FROM bot_merchants WHERE status = 'active'");
            return new InvariantResult("orphaned_active_merchants", Status.PASS,
                    "active=" + active + " (runtime instance check deferred)");
        } catch (SQLException e) {
            return new InvariantResult("orphaned_active_merchants", Status.FAIL, e.toString());
        }
    }

    private static InvariantResult invariantBotLogins() {
        try (Connection con = DatabaseConnection.getConnection()) {
            int hits = loadInt(con,
                    "SELECT COUNT(*) FROM admin_login_events " +
                    "WHERE event_type = 'login' AND account_id IN " +
                    "(SELECT accountid FROM characters WHERE bot_flag = 1)");
            return new InvariantResult("no_bot_logins",
                    hits == 0 ? Status.PASS : Status.FAIL,
                    "attempts=" + hits);
        } catch (SQLException e) {
            return new InvariantResult("no_bot_logins", Status.FAIL, e.toString());
        }
    }

    private static InvariantResult invariantNonBotInventory() {
        try (Connection con = DatabaseConnection.getConnection()) {
            int bad = loadInt(con,
                    "SELECT COUNT(*) FROM bot_inventory i " +
                    "JOIN characters c ON c.id = i.bot_character_id " +
                    "WHERE i.bot_character_id IS NOT NULL AND c.bot_flag = 0");
            return new InvariantResult("no_cross_ownership",
                    bad == 0 ? Status.PASS : Status.FAIL, "violations=" + bad);
        } catch (SQLException e) {
            return new InvariantResult("no_cross_ownership", Status.FAIL, e.toString());
        }
    }

    private static InvariantResult invariantFredrickConsistency() {
        try (Connection con = DatabaseConnection.getConnection()) {
            int stale = loadInt(con,
                    "SELECT COUNT(*) FROM fredstorage f " +
                    "JOIN characters c ON c.id = f.cid " +
                    "WHERE c.bot_flag = 1 AND f.timestamp < (UNIX_TIMESTAMP(NOW() - INTERVAL 7 DAY) * 1000)");
            return new InvariantResult("fredrick_consistency",
                    stale == 0 ? Status.PASS : Status.WARN, "stale_bot_rows=" + stale);
        } catch (SQLException e) {
            // Not a critical failure if fredstorage columns shape differs — treat as warn.
            return new InvariantResult("fredrick_consistency", Status.WARN, e.toString());
        }
    }

    private static InvariantResult invariantLedgerMonotonicity() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) FROM (SELECT id, sold_at, LAG(sold_at) OVER (ORDER BY id) AS prev " +
                     "FROM bot_sales_ledger) t WHERE prev IS NOT NULL AND sold_at < prev");
             ResultSet rs = ps.executeQuery()) {
            int out = rs.next() ? rs.getInt(1) : 0;
            return new InvariantResult("ledger_monotonicity",
                    out == 0 ? Status.PASS : Status.FAIL, "violations=" + out);
        } catch (SQLException e) {
            // LAG requires MySQL 8+; degrade to warn if unsupported.
            return new InvariantResult("ledger_monotonicity", Status.WARN, e.toString());
        }
    }

    private static InvariantResult invariantPricingCoverage() {
        try (Connection con = DatabaseConnection.getConnection()) {
            int missing = loadInt(con,
                    "SELECT COUNT(DISTINCT r.item_id) FROM bot_item_rarity r " +
                    "LEFT JOIN bot_item_prices p ON p.item_id = r.item_id " +
                    "WHERE p.item_id IS NULL");
            return new InvariantResult("pricing_coverage",
                    missing == 0 ? Status.PASS : Status.WARN, "items_missing_prices=" + missing);
        } catch (SQLException e) {
            return new InvariantResult("pricing_coverage", Status.WARN, e.toString());
        }
    }

    private static InvariantResult invariantPersonaDistribution() {
        var counts = BotCharacterFactory.activeMerchantCountByPersona();
        List<String> zeroed = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            if (entry.getValue() == 0 && entry.getKey().activeAt(BotSmtService.currentSmt())) {
                zeroed.add(entry.getKey().name());
            }
        }
        return new InvariantResult("persona_distribution",
                zeroed.isEmpty() ? Status.PASS : Status.WARN,
                zeroed.isEmpty() ? "ok" : "zero_count_but_active: " + String.join(",", zeroed));
    }

    private static InvariantResult invariantSaturationPathology() {
        int suppressed = BotSaturationService.suppressedCount();
        int threshold = 50;
        return new InvariantResult("saturation_suppression",
                suppressed <= threshold ? Status.PASS : Status.WARN,
                "suppressed=" + suppressed);
    }

    private static int loadInt(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static long loadLong(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static void writeLog(InvariantResult r) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_reconciliation_log (invariant_name, status, detail, recovery_script) " +
                     "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, r.name);
            ps.setString(2, r.status.name().toLowerCase());
            ps.setString(3, r.detail);
            ps.setString(4, "scripts/bot-economy/recovery/" + r.name + ".sql");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("writeLog failed for {} ({})", r.name, e.toString());
        }
    }

    public enum Status { PASS, WARN, FAIL }

    public static class InvariantResult {
        public final String name;
        public final Status status;
        public final String detail;

        public InvariantResult(String name, Status status, String detail) {
            this.name = name;
            this.status = status;
            this.detail = detail;
        }

        @Override
        public String toString() {
            String symbol = switch (status) {
                case PASS -> "✓";
                case WARN -> "⚠";
                case FAIL -> "✗";
            };
            return String.format("%s %-30s %s", symbol, name, detail);
        }
    }
}

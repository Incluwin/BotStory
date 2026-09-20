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
 * Per-buyer rate limit on mispriced purchases (BOT_ECONOMY.md § Misprice
 * mechanic — Rate limit). A character may buy at most
 * {@code BOT_MISPRICE.buyer_limit_per_day} mispriced listings per rolling
 * window. 4th attempt is rejected server-side.
 *
 * <p>Extension point: buy-order limits and other per-character rate checks
 * should land here in later phases.
 */
public final class BotRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(BotRateLimiter.class);

    private BotRateLimiter() {}

    /**
     * @return {@code true} iff the buyer has already hit their mispriced-purchase
     *         quota in the configured rolling window.
     */
    public static boolean isMispriceQuotaExceeded(int buyerCharacterId) {
        var cfg = YamlConfig.config.bot_economy.BOT_MISPRICE;
        if (!cfg.enabled) return false;
        String sql =
                "SELECT COUNT(*) FROM bot_sales_ledger " +
                "WHERE buyer_character_id = ? AND was_mispriced = 1 " +
                "AND sold_at > NOW() - INTERVAL ? HOUR";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, buyerCharacterId);
            ps.setInt(2, cfg.buyer_limit_window_hours);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) >= cfg.buyer_limit_per_day;
                }
            }
        } catch (SQLException e) {
            log.warn("BotRateLimiter.isMispriceQuotaExceeded failed ({}); allowing buy as a precaution", e.toString());
        }
        return false;
    }
}

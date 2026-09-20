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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Server Maturity Tier (BOT_ECONOMY.md § Server Maturity Tier).
 *
 * <p>Computes a composite signal from real-player character data and ratchets
 * the SMT forward at most once per week. The value is cached in
 * {@code bot_smt_state} and read cheaply by drop-sim / pricing.
 */
public final class BotSmtService {
    private static final Logger log = LoggerFactory.getLogger(BotSmtService.class);

    private BotSmtService() {}

    public static int currentSmt() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT smt_current FROM bot_smt_state WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("BotSmtService.currentSmt failed ({}); defaulting to 0", e.toString());
        }
        return 0;
    }

    public static void recompute() {
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        double score = computeSignal(cfg);
        int proposedTier = scoreToTier(score);

        try (Connection con = DatabaseConnection.getConnection()) {
            int current = loadInt(con, "SELECT smt_current FROM bot_smt_state WHERE id=1", 0);
            Instant nextEligible = loadInstant(con,
                    "SELECT next_eligible_promotion_at FROM bot_smt_state WHERE id=1");

            int newTier = current;
            if (proposedTier > current && cfg.BOT_SMT_RATCHET_ONLY) {
                if (nextEligible == null || Instant.now().isAfter(nextEligible)) {
                    // +1 per week hysteresis
                    newTier = current + 1;
                }
            } else if (!cfg.BOT_SMT_RATCHET_ONLY) {
                newTier = proposedTier;
            }

            String sql = "UPDATE bot_smt_state SET " +
                    "smt_current = ?, smt_score_last = ?, last_computed_at = NOW()" +
                    (newTier > current
                            ? ", last_promoted_at = NOW(), next_eligible_promotion_at = NOW() + INTERVAL ? DAY"
                            : "") +
                    " WHERE id = 1";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, newTier);
                ps.setDouble(2, score);
                if (newTier > current) {
                    ps.setInt(3, cfg.BOT_SMT_PROMOTION_COOLDOWN_DAYS);
                }
                ps.executeUpdate();
            }
            if (newTier > current) {
                log.info("BotSmtService: SMT promoted {} -> {} (score={})", current, newTier, score);
            } else {
                log.debug("BotSmtService: SMT unchanged at {} (score={})", current, score);
            }
        } catch (SQLException e) {
            log.error("BotSmtService.recompute failed", e);
        }
    }

    private static double computeSignal(BotEconomyConfig cfg) {
        try (Connection con = DatabaseConnection.getConnection()) {
            int p75 = loadInt(con,
                    "SELECT level FROM characters WHERE bot_flag = 0 AND level > 0 " +
                    "ORDER BY level DESC LIMIT 1 OFFSET " +
                    "GREATEST(0, FLOOR((SELECT COUNT(*) FROM characters WHERE bot_flag = 0 AND level > 0) * 0.25))",
                    0);
            int pastThresh = loadInt(con,
                    "SELECT COUNT(*) FROM characters WHERE bot_flag = 0 AND level >= 70", 0);
            int activeAccounts = loadInt(con,
                    "SELECT COUNT(DISTINCT account_id) FROM admin_login_events " +
                    "WHERE event_type = 'login' AND ts > NOW() - INTERVAL 14 DAY", 0);

            double score = cfg.BOT_SMT_SIGNAL_P75_WEIGHT * p75
                         + cfg.BOT_SMT_SIGNAL_THRESHOLD_WEIGHT * 10 * pastThresh
                         + cfg.BOT_SMT_SIGNAL_ACTIVE_WEIGHT * activeAccounts;
            return score;
        } catch (SQLException e) {
            log.warn("BotSmtService.computeSignal failed ({}); returning 0", e.toString());
            return 0.0;
        }
    }

    private static int scoreToTier(double score) {
        if (score < 15)  return 0;
        if (score < 30)  return 1;
        if (score < 50)  return 2;
        if (score < 70)  return 3;
        if (score < 90)  return 4;
        if (score < 110) return 5;
        return 6;
    }

    private static int loadInt(Connection con, String sql, int defaultValue) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.debug("BotSmtService: sub-query failed, returning default ({}): {}", defaultValue, e.toString());
        }
        return defaultValue;
    }

    private static Instant loadInstant(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        }
        return null;
    }

    /** Force-promote (GM only, bypasses hysteresis). */
    public static boolean forcePromote() {
        try (Connection con = DatabaseConnection.getConnection()) {
            int current = loadInt(con, "SELECT smt_current FROM bot_smt_state WHERE id=1", 0);
            if (current >= 6) return false;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE bot_smt_state SET smt_current = ?, last_promoted_at = NOW(), " +
                    "next_eligible_promotion_at = NOW() + INTERVAL 7 DAY WHERE id = 1")) {
                ps.setInt(1, current + 1);
                ps.executeUpdate();
            }
            log.info("BotSmtService: SMT force-promoted {} -> {}", current, current + 1);
            return true;
        } catch (SQLException e) {
            log.error("BotSmtService.forcePromote failed", e);
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static Instant nowPlusDays(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS);
    }
}

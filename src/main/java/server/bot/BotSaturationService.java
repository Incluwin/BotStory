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
import java.util.HashMap;
import java.util.Map;

/**
 * Computes the per-item saturation multiplier used to downweight overproduced
 * bot inventory (BOT_ECONOMY.md § Per-item saturation feedback).
 *
 * <p>A cache of {@code item_id → multiplier} is rebuilt at the start of every
 * drop-sim tick and then consulted for the whole tick. Missing items default
 * to 1.0 (no suppression).
 */
public final class BotSaturationService {
    private static final Logger log = LoggerFactory.getLogger(BotSaturationService.class);

    private static volatile Map<Integer, Double> cache = new HashMap<>();

    private BotSaturationService() {}

    /** Rebuild the cache — should be called at the top of every drop-sim tick. */
    public static void rebuild() {
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        Map<Integer, Double> next = new HashMap<>();
        String sql =
                "SELECT item_id, SUM(quantity) AS aged_units, COALESCE(r.rarity, 'R1') AS rarity " +
                "FROM bot_inventory i " +
                "LEFT JOIN bot_item_rarity r ON r.item_id = i.item_id " +
                "WHERE i.status IN ('pending','stocked') " +
                "  AND i.generated_at < NOW() - INTERVAL ? HOUR " +
                "GROUP BY i.item_id, r.rarity";

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, cfg.BOT_SATURATION_AGE_HOURS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    int aged = rs.getInt("aged_units");
                    String rarity = rs.getString("rarity");
                    double mult = computeMultiplier(cfg, rarity, aged);
                    if (mult < 0.999) next.put(itemId, mult);
                }
            }
        } catch (SQLException e) {
            log.warn("BotSaturationService.rebuild failed ({}); keeping stale cache", e.toString());
            return;
        }
        cache = next;
    }

    private static double computeMultiplier(BotEconomyConfig cfg, String rarity, int aged) {
        int threshold = cfg.BOT_SATURATION_THRESHOLDS.getOrDefault(rarity, 50);
        double minMult = cfg.BOT_SATURATION_MIN_MULTIPLIERS.getOrDefault(rarity, 0.10);
        if (aged <= threshold) return 1.0;
        double m = 1.0 - (aged - threshold) / 100.0;
        return Math.max(minMult, m);
    }

    public static double multiplierFor(int itemId) {
        return cache.getOrDefault(itemId, 1.0);
    }

    public static int suppressedCount() {
        return cache.size();
    }

    /** Returns a read-only snapshot for observability. */
    public static Map<Integer, Double> snapshot() {
        return Map.copyOf(cache);
    }
}

package server.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Produces owner-snapshots for bot merchants without loading a full
 * {@code Character} into memory (BOT_ECONOMY.md § Ephemeral Character instantiation).
 *
 * <p>Used by the lifecycle manager: it needs enough fields (id, name, world,
 * channel, mapId, position) to construct a {@code HiredMerchant} via the
 * snapshot factory without paying the per-{@code Character} memory cost.
 */
public final class BotCharacterFactory {
    private static final Logger log = LoggerFactory.getLogger(BotCharacterFactory.class);

    private BotCharacterFactory() {}

    public record Snapshot(
            int id,
            String name,
            int accountId,
            int world,
            int channel,
            int mapId,
            int positionX,
            int positionY,
            BotPersona persona
    ) {}

    /**
     * Load a bot character's snapshot by id. Returns empty if not found or
     * the row's {@code bot_flag} is not set (defensive).
     */
    public static Optional<Snapshot> loadById(int characterId) {
        String sql =
                "SELECT id, name, accountid, world, map, bot_persona " +
                "FROM characters WHERE id = ? AND bot_flag = 1 LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                BotPersona persona = parsePersona(rs.getString("bot_persona"));
                return Optional.of(new Snapshot(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getInt("accountid"),
                        rs.getInt("world"),
                        /*channel*/ 1,
                        rs.getInt("map"),
                        0, 0,
                        persona));
            }
        } catch (SQLException e) {
            log.warn("BotCharacterFactory.loadById failed for id {} ({})", characterId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Find bot characters that are eligible to spawn a merchant now: bot_flag=1,
     * no active HasMerchant, bot_next_spawn_at null or in the past. Results
     * ordered by persona under-representation so the lifecycle manager can
     * rebalance naturally.
     *
     * @param limit max number of candidates to return
     */
    public static java.util.List<Snapshot> loadEligibleForSpawn(int limit) {
        java.util.List<Snapshot> out = new java.util.ArrayList<>();
        String sql =
                "SELECT id, name, accountid, world, map, bot_persona " +
                "FROM characters " +
                "WHERE bot_flag = 1 AND HasMerchant = 0 " +
                "  AND (bot_next_spawn_at IS NULL OR bot_next_spawn_at <= NOW()) " +
                "ORDER BY RAND() " +
                "LIMIT ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BotPersona persona = parsePersona(rs.getString("bot_persona"));
                    out.add(new Snapshot(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getInt("accountid"),
                            rs.getInt("world"),
                            1,
                            rs.getInt("map"),
                            0, 0,
                            persona));
                }
            }
        } catch (SQLException e) {
            log.warn("BotCharacterFactory.loadEligibleForSpawn failed ({})", e.toString());
        }
        return out;
    }

    /** Map of persona -> active bot merchant count. */
    public static Map<BotPersona, Integer> activeMerchantCountByPersona() {
        Map<BotPersona, Integer> counts = new HashMap<>();
        for (BotPersona p : BotPersona.values()) counts.put(p, 0);
        String sql =
                "SELECT c.bot_persona, COUNT(*) " +
                "FROM bot_merchants m JOIN characters c ON c.id = m.bot_character_id " +
                "WHERE m.status = 'active' " +
                "GROUP BY c.bot_persona";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BotPersona p = parsePersona(rs.getString(1));
                if (p != null) counts.put(p, rs.getInt(2));
            }
        } catch (SQLException e) {
            log.warn("BotCharacterFactory.activeMerchantCountByPersona failed ({})", e.toString());
        }
        return counts;
    }

    private static BotPersona parsePersona(String raw) {
        if (raw == null) return BotPersona.Generalist;
        try {
            return BotPersona.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return BotPersona.Generalist;
        }
    }

    /** Update bot_next_spawn_at on the character row. */
    public static void setNextSpawnAt(int characterId, long baseCooldownMinutes, double jitterPct) {
        double jitter = 1.0 + BotRandomService.uniform(-jitterPct, jitterPct);
        long adjustedMinutes = Math.max(1L, Math.round(baseCooldownMinutes * jitter));
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE characters SET bot_next_spawn_at = NOW() + INTERVAL ? MINUTE WHERE id = ? AND bot_flag = 1")) {
            ps.setLong(1, adjustedMinutes);
            ps.setInt(2, characterId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("BotCharacterFactory.setNextSpawnAt failed for id {} ({})", characterId, e.toString());
        }
    }
}

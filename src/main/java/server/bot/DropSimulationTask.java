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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drop simulation engine (BOT_ECONOMY.md § Component 1 — Drop Simulation Engine).
 *
 * <p>This is the simplest possible implementation that matches the shape of
 * the spec: every tick we pick N virtual hunters, each rolls a small random
 * walk across allowed monster ids, and the rolls go through saturation
 * suppression + persona rarity gate before landing in {@code bot_inventory}.
 *
 * <p>The heavy work (actually querying Cosmic's {@code MonsterInformationProvider}
 * for real drop tables) is intentionally deferred — the v1 implementation uses
 * a cheap rarity-weighted roll off the allowlist. Real drop-table integration
 * is a follow-up once seed-time rarity derivation has populated
 * {@code bot_item_rarity}.
 */
public final class DropSimulationTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DropSimulationTask.class);

    private static final AtomicInteger TICK = new AtomicInteger();
    private static final AtomicLong LAST_TICK_MS = new AtomicLong();

    @Override
    public void run() {
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        if (!cfg.ENABLED || !cfg.DROP_SIM_ENABLED || cfg.DRAIN) return;

        long start = System.currentTimeMillis();
        try {
            BotSaturationService.rebuild();
            int smt = BotSmtService.currentSmt();
            int hunters = Math.max(1, cfg.BOT_VIRTUAL_HUNTERS);
            int perHunter = Math.max(1, cfg.BOT_HUNTER_MONSTERS_PER_TICK);
            double rateMult = cfg.BOT_DROP_RATE_MULTIPLIER;

            List<Integer> itemPool = loadRarityEligibleItems(smt);
            if (itemPool.isEmpty()) {
                log.debug("DropSimulationTask: no eligible items at SMT {}, skipping tick", smt);
                return;
            }

            List<InventoryRow> emissions = new ArrayList<>();
            for (int h = 0; h < hunters; h++) {
                for (int k = 0; k < perHunter; k++) {
                    if (BotRandomService.nextDouble() > rateMult) continue;
                    int itemId = itemPool.get(BotRandomService.nextInt(itemPool.size()));
                    double satMult = BotSaturationService.multiplierFor(itemId);
                    if (BotRandomService.nextDouble() > satMult) continue;
                    emissions.add(new InventoryRow(itemId, 1, smt));
                }
            }
            int written = persistEmissions(emissions);

            long elapsed = System.currentTimeMillis() - start;
            LAST_TICK_MS.set(elapsed);
            TICK.incrementAndGet();
            if (log.isInfoEnabled()) {
                log.info("DropSimulationTask tick {}: smt={} pool={} emitted={} elapsed={}ms",
                        TICK.get(), smt, itemPool.size(), written, elapsed);
            }
        } catch (Exception e) {
            log.error("DropSimulationTask failed", e);
        }
    }

    private List<Integer> loadRarityEligibleItems(int smt) {
        List<Integer> out = new ArrayList<>();
        BotPersona.Rarity cap = BotPersona.smtRarityCap(smt);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT item_id, rarity FROM bot_item_rarity WHERE rarity <= ?")) {
            ps.setString(1, cap.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    if (BotItemAllowlist.isAllowed(id)) out.add(id);
                }
            }
        } catch (SQLException e) {
            log.warn("DropSimulationTask: rarity-eligible item query failed ({})", e.toString());
        }
        return out;
    }

    private int persistEmissions(List<InventoryRow> emissions) {
        if (emissions.isEmpty()) return 0;
        String sql = "INSERT INTO bot_inventory " +
                "(item_id, quantity, source, status, generated_at_smt) " +
                "VALUES (?, ?, 'drop_sim', 'pending', ?)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (InventoryRow row : emissions) {
                ps.setInt(1, row.itemId);
                ps.setInt(2, row.quantity);
                ps.setInt(3, row.smt);
                ps.addBatch();
            }
            int[] result = ps.executeBatch();
            int total = 0;
            for (int r : result) if (r > 0) total += r;
            return total;
        } catch (SQLException e) {
            log.error("DropSimulationTask.persistEmissions failed", e);
            return 0;
        }
    }

    public static long lastTickMs() { return LAST_TICK_MS.get(); }

    private record InventoryRow(int itemId, int quantity, int smt) {}
}

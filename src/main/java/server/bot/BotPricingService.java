package server.bot;

import config.BotEconomyConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computes prices for bot merchant listings. Implements the master pricing
 * formula from BOT_ECONOMY.md § Master pricing formula.
 *
 * <p>Inputs: item id, persona, rarity, price class, SMT, optional stat delta,
 * optional misprice discount. Output: an integer meso price, clamped to the
 * configured floor/ceiling rails and rounded to the nearest 100.
 *
 * <p>Clamp counters are exposed for the next telemetry sample.
 */
public final class BotPricingService {
    private static final Logger log = LoggerFactory.getLogger(BotPricingService.class);

    private static final AtomicLong CLAMP_FLOOR = new AtomicLong();
    private static final AtomicLong CLAMP_CEILING = new AtomicLong();

    public enum PriceClass { npc_consumable, npc_gear, player_equip, scroll_demand, crafting_mat, rare_specialty }

    private BotPricingService() {}

    /**
     * @param itemId       item id
     * @param persona      persona that owns the merchant (drives persona_mult)
     * @param priceClass   class resolved from bot_item_price_class
     * @param rarity       rarity from bot_item_rarity
     * @param smt          current SMT
     * @param statDelta    weighted stat delta for player_equip items (primary+0.2*secondary); 0 for other classes
     * @param isUniversal  true for all-stat / all-class items (overrides primary/secondary)
     * @param demandMult   demand multiplier for scroll_demand (from bot_scroll_demand_cache); 1.0 elsewhere
     * @param mispriceFactor null if not mispriced, otherwise the stored discount factor (e.g. 0.55 == 45% off)
     */
    public static int price(int itemId, BotPersona persona, PriceClass priceClass,
                            BotPersona.Rarity rarity, int smt, double statDelta,
                            boolean isUniversal, double demandMult, Double mispriceFactor) {
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;

        // ───── npc_consumable class: NPC-anchored, bypasses every other axis ─────
        if (priceClass == PriceClass.npc_consumable) {
            int npcBuy = safeGetItemPrice(itemId);
            if (npcBuy <= 0) npcBuy = cfg.BOT_PRICE_ABSOLUTE_FLOOR;
            double anchor = BotRandomService.uniform(
                    cfg.BOT_NPC_STOCK_DISCOUNT_MIN, cfg.BOT_NPC_STOCK_DISCOUNT_MAX);
            return Math.max(1, (int) Math.round(npcBuy * anchor));
        }

        int basePrice = lookupBasePrice(itemId, smt);
        if (basePrice <= 0) {
            // Fallback: NPC_sell × 4 (flagged in reconciliation invariant 8).
            int npcSell = safeGetItemPrice(itemId);
            basePrice = Math.max(cfg.BOT_PRICE_ABSOLUTE_FLOOR, npcSell * 4);
        }

        double personaMult = persona.priceMult;

        double classMult = 1.0;
        if (priceClass == PriceClass.player_equip) {
            double premium = isUniversal ? cfg.BOT_STAT_PREMIUM_UNIVERSAL : cfg.BOT_STAT_PREMIUM_PRIMARY;
            double statMult = 1.0 + premium * statDelta;
            classMult = Math.min(Math.max(statMult, 0.25), cfg.BOT_STAT_MAX_MULT);
        } else if (priceClass == PriceClass.scroll_demand) {
            classMult = clamp(demandMult, cfg.BOT_DEMAND_MULT_CLAMP_MIN, cfg.BOT_DEMAND_MULT_CLAMP_MAX);
        }

        double jitterPct = cfg.BOT_PRICE_JITTER_BY_RARITY.getOrDefault(rarity.name(), 0.10);
        double jitter = BotRandomService.uniform(1.0 - jitterPct, 1.0 + jitterPct);

        double raw = basePrice * personaMult * classMult * jitter;

        if (mispriceFactor != null) {
            raw *= mispriceFactor;
        }

        // Floor / ceiling clamp
        int npcSell = Math.max(1, safeGetItemPrice(itemId));
        long floor = Math.max((long)(npcSell * cfg.BOT_PRICE_FLOOR_NPC_MULT), cfg.BOT_PRICE_ABSOLUTE_FLOOR);
        long ceiling = (long)(npcSell * cfg.BOT_PRICE_CEILING_NPC_MULT);
        if (ceiling <= floor) ceiling = floor + 1;

        long clamped = Math.round(raw);
        if (clamped < floor) { CLAMP_FLOOR.incrementAndGet(); clamped = floor; }
        if (clamped > ceiling) { CLAMP_CEILING.incrementAndGet(); clamped = ceiling; }

        // Round to nearest 100
        long rounded = ((clamped + 50) / 100) * 100;
        if (rounded > Integer.MAX_VALUE) rounded = Integer.MAX_VALUE;
        if (rounded < 1) rounded = 1;
        return (int) rounded;
    }

    private static int lookupBasePrice(int itemId, int smt) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT price FROM bot_item_prices WHERE item_id = ? AND smt = ?")) {
            ps.setInt(1, itemId);
            ps.setInt(2, smt);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("BotPricingService: price lookup failed for item {} smt {} ({})", itemId, smt, e.toString());
        }
        return 0;
    }

    private static int safeGetItemPrice(int itemId) {
        try {
            int p = ItemInformationProvider.getInstance().getPrice(itemId, 1);
            return Math.max(0, p);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Snapshot + reset — used by the telemetry sampler. */
    public static long drainFloorClamps() { return CLAMP_FLOOR.getAndSet(0); }
    public static long drainCeilingClamps() { return CLAMP_CEILING.getAndSet(0); }

    /** Read-only peek for dashboards. */
    public static long peekFloorClamps() { return CLAMP_FLOOR.get(); }
    public static long peekCeilingClamps() { return CLAMP_CEILING.get(); }
}

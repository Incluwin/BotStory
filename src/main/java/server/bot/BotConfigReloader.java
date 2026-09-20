package server.bot;

import com.esotericsoftware.yamlbeans.YamlReader;
import config.BotEconomyConfig;
import config.YamlConfig;
import constants.string.CharsetConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hot-reload of the {@code bot_economy:} block at a tick boundary
 * (BOT_ECONOMY.md § Configuration additions — Hot-reloadable keys).
 *
 * <p>Validation runs first; on failure the previous config stays live and a
 * WARN is logged. Successful reload swaps the value behind {@link YamlConfig#config}.
 */
public final class BotConfigReloader {
    private static final Logger log = LoggerFactory.getLogger(BotConfigReloader.class);

    private BotConfigReloader() {}

    /** Reload bot_economy keys from disk. Returns a human-readable summary. */
    public static String reloadConfig() {
        try {
            YamlConfig fresh;
            try (var reader = new YamlReader(
                    Files.newBufferedReader(Path.of(YamlConfig.CONFIG_FILE_NAME), CharsetConstants.CHARSET))) {
                fresh = reader.read(YamlConfig.class);
            }
            BotEconomyConfig freshBot = fresh.bot_economy != null
                    ? fresh.bot_economy : new BotEconomyConfig();
            freshBot.validate();

            // Copy runtime-tunable fields onto the live config. We keep the
            // YamlConfig.config reference stable (it's static final elsewhere
            // in the codebase); hot-reload mutates in place to avoid thread
            // safety headaches on static references.
            BotEconomyConfig live = YamlConfig.config.bot_economy;
            copyRuntimeKeys(freshBot, live);

            BotRandomService.reinitialize();
            return "bot_economy reloaded";
        } catch (IOException e) {
            log.warn("bot_economy reload failed ({})", e.toString());
            return "reload failed: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("bot_economy reload validation failed ({})", e.toString());
            return "validation failed: " + e.getMessage();
        }
    }

    /**
     * Copy runtime-tunable fields from {@code src} onto {@code dst}. Restart-required
     * fields (BOT_POOL_SIZE, scheduler cadences, TEST_MODE) are intentionally left
     * untouched — they require a restart because their use sites capture the
     * value at startup.
     */
    private static void copyRuntimeKeys(BotEconomyConfig src, BotEconomyConfig dst) {
        dst.ENABLED = src.ENABLED;
        dst.SHADOW_MODE = src.SHADOW_MODE;
        dst.DRAIN = src.DRAIN;
        dst.DROP_SIM_ENABLED = src.DROP_SIM_ENABLED;
        dst.LIFECYCLE_ENABLED = src.LIFECYCLE_ENABLED;
        dst.TREASURY_COLLECTION_ENABLED = src.TREASURY_COLLECTION_ENABLED;
        dst.BUY_ORDERS_ENABLED = src.BUY_ORDERS_ENABLED;

        dst.BOT_MERCHANT_TARGET_TOTAL = src.BOT_MERCHANT_TARGET_TOTAL;
        dst.BOT_FM_ROOM_BUDGET = src.BOT_FM_ROOM_BUDGET;
        dst.BOT_FM_ROOM_REAL_PLAYER_RESERVE = src.BOT_FM_ROOM_REAL_PLAYER_RESERVE;
        dst.BOT_MERCHANT_TTL_HOURS = src.BOT_MERCHANT_TTL_HOURS;
        dst.BOT_MERCHANT_SPAWN_PER_TICK = src.BOT_MERCHANT_SPAWN_PER_TICK;
        dst.BOT_MERCHANT_RESPAWN_COOLDOWN_MINUTES = src.BOT_MERCHANT_RESPAWN_COOLDOWN_MINUTES;
        dst.BOT_MERCHANT_SPAWN_JITTER_PCT = src.BOT_MERCHANT_SPAWN_JITTER_PCT;
        dst.BOT_MERCHANT_ITEMS_MIN = src.BOT_MERCHANT_ITEMS_MIN;
        dst.BOT_MERCHANT_ITEMS_MAX = src.BOT_MERCHANT_ITEMS_MAX;

        dst.BOT_VIRTUAL_HUNTERS = src.BOT_VIRTUAL_HUNTERS;
        dst.BOT_HUNTER_MONSTERS_PER_TICK = src.BOT_HUNTER_MONSTERS_PER_TICK;
        dst.BOT_DROP_RATE_MULTIPLIER = src.BOT_DROP_RATE_MULTIPLIER;

        dst.BOT_SMT_SIGNAL_P75_WEIGHT = src.BOT_SMT_SIGNAL_P75_WEIGHT;
        dst.BOT_SMT_SIGNAL_THRESHOLD_WEIGHT = src.BOT_SMT_SIGNAL_THRESHOLD_WEIGHT;
        dst.BOT_SMT_SIGNAL_ACTIVE_WEIGHT = src.BOT_SMT_SIGNAL_ACTIVE_WEIGHT;
        dst.BOT_SMT_PROMOTION_COOLDOWN_DAYS = src.BOT_SMT_PROMOTION_COOLDOWN_DAYS;
        dst.BOT_SMT_RATCHET_ONLY = src.BOT_SMT_RATCHET_ONLY;

        dst.BOT_SATURATION_AGE_HOURS = src.BOT_SATURATION_AGE_HOURS;
        dst.BOT_SATURATION_THRESHOLDS = src.BOT_SATURATION_THRESHOLDS;
        dst.BOT_SATURATION_MIN_MULTIPLIERS = src.BOT_SATURATION_MIN_MULTIPLIERS;

        dst.BOT_PRICE_JITTER_BY_RARITY = src.BOT_PRICE_JITTER_BY_RARITY;
        dst.BOT_PRICE_FLOOR_NPC_MULT = src.BOT_PRICE_FLOOR_NPC_MULT;
        dst.BOT_PRICE_CEILING_NPC_MULT = src.BOT_PRICE_CEILING_NPC_MULT;
        dst.BOT_PRICE_ABSOLUTE_FLOOR = src.BOT_PRICE_ABSOLUTE_FLOOR;

        dst.BOT_STAT_PREMIUM_PRIMARY = src.BOT_STAT_PREMIUM_PRIMARY;
        dst.BOT_STAT_PREMIUM_SECONDARY = src.BOT_STAT_PREMIUM_SECONDARY;
        dst.BOT_STAT_PREMIUM_UNIVERSAL = src.BOT_STAT_PREMIUM_UNIVERSAL;
        dst.BOT_STAT_MAX_MULT = src.BOT_STAT_MAX_MULT;
        dst.BOT_STAT_KEEP_AVG_PROB_BY_RARITY = src.BOT_STAT_KEEP_AVG_PROB_BY_RARITY;
        dst.BOT_STAT_RESTOCK_REFILTER = src.BOT_STAT_RESTOCK_REFILTER;

        dst.BOT_DEMAND_WINDOW_DAYS = src.BOT_DEMAND_WINDOW_DAYS;
        dst.BOT_DEMAND_MULT_CLAMP_MIN = src.BOT_DEMAND_MULT_CLAMP_MIN;
        dst.BOT_DEMAND_MULT_CLAMP_MAX = src.BOT_DEMAND_MULT_CLAMP_MAX;
        dst.BOT_DEMAND_CACHE_REFRESH_TICKS = src.BOT_DEMAND_CACHE_REFRESH_TICKS;

        dst.BOT_NPC_STOCK_INTERVAL_MINUTES = src.BOT_NPC_STOCK_INTERVAL_MINUTES;
        dst.BOT_NPC_STOCK_DISCOUNT_MIN = src.BOT_NPC_STOCK_DISCOUNT_MIN;
        dst.BOT_NPC_STOCK_DISCOUNT_MAX = src.BOT_NPC_STOCK_DISCOUNT_MAX;
        dst.BOT_NPC_STOCK_QTY_MIN = src.BOT_NPC_STOCK_QTY_MIN;
        dst.BOT_NPC_STOCK_QTY_MAX = src.BOT_NPC_STOCK_QTY_MAX;

        dst.BOT_VISUAL_MODE = src.BOT_VISUAL_MODE;
        dst.BOT_BLEND_PERMIT_ID = src.BOT_BLEND_PERMIT_ID;
        dst.BOT_DISTINCT_PERMIT_POOL = src.BOT_DISTINCT_PERMIT_POOL;
        dst.BOT_HYBRID_PERSONA_MAP = src.BOT_HYBRID_PERSONA_MAP;

        dst.BOT_MISPRICE = src.BOT_MISPRICE;

        dst.BOT_MESO_SINK_PCT = src.BOT_MESO_SINK_PCT;
        dst.BOT_MESO_EVENT_POOL_PCT = src.BOT_MESO_EVENT_POOL_PCT;
        dst.BOT_MESO_GM_RESERVE_PCT = src.BOT_MESO_GM_RESERVE_PCT;
        dst.BOT_EVENT_POOL_CAP = src.BOT_EVENT_POOL_CAP;
        dst.BOT_LOG_ALL_SALES = src.BOT_LOG_ALL_SALES;

        dst.BOT_RECONCILIATION_STRICT = src.BOT_RECONCILIATION_STRICT;
        dst.alerts = src.alerts;
    }
}

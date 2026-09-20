package config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bot-driven FM merchant economy configuration block. See Docs/BOT_ECONOMY.md.
 *
 * All fields are injected directly by YamlBeans from the {@code bot_economy:}
 * block in config.yaml. Defaults match the spec's recommended values so a
 * missing key doesn't crash boot.
 *
 * The feature is gated by {@link #ENABLED}. Everything is no-op until that
 * flips to true AND an operator runs the seeding script.
 */
public class BotEconomyConfig {
    // Master switches
    public boolean ENABLED = false;
    public boolean SHADOW_MODE = false;
    public boolean DRAIN = false;

    // Per-component kill switches
    public boolean DROP_SIM_ENABLED = true;
    public boolean LIFECYCLE_ENABLED = true;
    public boolean TREASURY_COLLECTION_ENABLED = true;
    public boolean BUY_ORDERS_ENABLED = false;

    // Population and spawning
    public int  BOT_POOL_SIZE = 200;
    public int  BOT_MERCHANT_TARGET_TOTAL = 175;
    public int  BOT_FM_ROOM_BUDGET = 12;
    public int  BOT_FM_ROOM_REAL_PLAYER_RESERVE = 5;
    public int  BOT_MERCHANT_TICK_MINUTES = 5;
    public int  BOT_MERCHANT_TTL_HOURS = 24;
    public int  BOT_MERCHANT_SPAWN_PER_TICK = 10;
    public int  BOT_MERCHANT_RESPAWN_COOLDOWN_MINUTES = 30;
    public double BOT_MERCHANT_SPAWN_JITTER_PCT = 0.30;
    public int  BOT_MERCHANT_ITEMS_MIN = 3;
    public int  BOT_MERCHANT_ITEMS_MAX = 8;

    // Drop simulation
    public int  BOT_VIRTUAL_HUNTERS = 32;
    public int  BOT_HUNTER_MONSTERS_PER_TICK = 50;
    public int  BOT_DROP_SIM_INTERVAL_MINUTES = 15;
    public double BOT_DROP_RATE_MULTIPLIER = 0.35;

    // SMT
    public double BOT_SMT_SIGNAL_P75_WEIGHT = 0.60;
    public double BOT_SMT_SIGNAL_THRESHOLD_WEIGHT = 0.30;
    public double BOT_SMT_SIGNAL_ACTIVE_WEIGHT = 0.10;
    public String BOT_SMT_RECOMPUTE_CRON = "0 30 3 * * *";
    public int    BOT_SMT_PROMOTION_COOLDOWN_DAYS = 7;
    public boolean BOT_SMT_RATCHET_ONLY = true;

    // Saturation (rarity-indexed)
    public int BOT_SATURATION_AGE_HOURS = 48;
    public Map<String, Integer> BOT_SATURATION_THRESHOLDS = defaultSaturationThresholds();
    public Map<String, Double>  BOT_SATURATION_MIN_MULTIPLIERS = defaultSaturationMinMults();

    // Rarity derivation
    public Map<String, Double>  BOT_RARITY_DROP_RATE_BOUNDARIES = defaultRarityBoundaries();
    public Map<String, Integer> BOT_RARITY_TARGET_DISTRIBUTION = defaultRarityTargetDistribution();
    public int BOT_RARITY_MAX_OVERRIDES = 100;

    // Pricing (rarity-indexed)
    public Map<String, Double> BOT_PRICE_JITTER_BY_RARITY = defaultJitterByRarity();
    public double BOT_PRICE_FLOOR_NPC_MULT = 2.0;
    public double BOT_PRICE_CEILING_NPC_MULT = 200.0;
    public int    BOT_PRICE_ABSOLUTE_FLOOR = 1000;

    // Stat-roll premium
    public double BOT_STAT_PREMIUM_PRIMARY = 0.25;
    public double BOT_STAT_PREMIUM_SECONDARY = 0.05;
    public double BOT_STAT_PREMIUM_UNIVERSAL = 0.15;
    public double BOT_STAT_MAX_MULT = 2.5;
    public Map<String, Double> BOT_STAT_KEEP_AVG_PROB_BY_RARITY = defaultKeepAvgProb();
    public boolean BOT_STAT_RESTOCK_REFILTER = true;

    // Scroll demand
    public int    BOT_DEMAND_WINDOW_DAYS = 7;
    public double BOT_DEMAND_MULT_CLAMP_MIN = 0.50;
    public double BOT_DEMAND_MULT_CLAMP_MAX = 2.00;
    public int    BOT_DEMAND_CACHE_REFRESH_TICKS = 1;

    // NPC-stock path
    public int    BOT_NPC_STOCK_INTERVAL_MINUTES = 30;
    public double BOT_NPC_STOCK_DISCOUNT_MIN = 0.95;
    public double BOT_NPC_STOCK_DISCOUNT_MAX = 1.00;
    public int    BOT_NPC_STOCK_QTY_MIN = 20;
    public int    BOT_NPC_STOCK_QTY_MAX = 50;

    // Shop visual identity
    public String BOT_VISUAL_MODE = "distinct";
    public int    BOT_BLEND_PERMIT_ID = 5030000;
    public List<Integer> BOT_DISTINCT_PERMIT_POOL = defaultDistinctPermitPool();
    public Map<String, Integer> BOT_HYBRID_PERSONA_MAP = defaultHybridPersonaMap();

    // Misprice mechanic
    public MispriceBlock BOT_MISPRICE = new MispriceBlock();

    public static class MispriceBlock {
        public boolean enabled = true;
        public double  chance_per_shop = 0.015;
        public List<Double> discount_range = List.of(0.40, 0.50);
        public List<String> eligible_classes = List.of("player_equip","scroll_demand","crafting_mat","rare_specialty");
        public int buyer_limit_per_day = 3;
        public int buyer_limit_window_hours = 24;
    }

    // Treasury / meso disposition
    public int  BOT_MESO_SINK_PCT = 70;
    public int  BOT_MESO_EVENT_POOL_PCT = 20;
    public int  BOT_MESO_GM_RESERVE_PCT = 10;
    public long BOT_EVENT_POOL_CAP = 500_000_000L;
    public boolean BOT_LOG_ALL_SALES = true;

    // Reconciliation and telemetry
    public String BOT_RECONCILIATION_CRON = "0 0 4 * * *";
    public boolean BOT_RECONCILIATION_STRICT = false;
    public int BOT_METRICS_SAMPLE_INTERVAL_MINUTES = 5;
    public int BOT_METRICS_RETENTION_DAYS = 90;

    // Alerts
    public AlertsBlock alerts = new AlertsBlock();

    public static class AlertsBlock {
        public String  webhook_url = "";
        public String  severity_threshold = "WARN";
        public boolean treasury_growth_anomaly = true;
        public boolean player_meso_surge = true;
        public boolean player_meso_drain = true;
        public boolean saturation_explosion = true;
        public boolean merchant_churn = true;
        public boolean sales_drought = true;
        public boolean gini_spike = true;
        public boolean pricing_clamp_flood = true;
        public boolean reconciliation_failure = true;
    }

    // Test mode
    public boolean TEST_MODE = false;
    public TestBlock test = new TestBlock();

    public static class TestBlock {
        public long RNG_SEED = 42L;
    }

    // ───── defaults ─────
    private static Map<String, Integer> defaultSaturationThresholds() {
        Map<String, Integer> m = new HashMap<>();
        m.put("R1", 50); m.put("R2", 20); m.put("R3", 8); m.put("R4", 3); m.put("R5", 1);
        return m;
    }

    private static Map<String, Double> defaultSaturationMinMults() {
        Map<String, Double> m = new HashMap<>();
        m.put("R1", 0.10); m.put("R2", 0.10); m.put("R3", 0.15); m.put("R4", 0.25); m.put("R5", 0.40);
        return m;
    }

    private static Map<String, Double> defaultRarityBoundaries() {
        Map<String, Double> m = new HashMap<>();
        m.put("R1_min_rate", 0.01);
        m.put("R2_min_rate", 0.001);
        m.put("R3_min_rate", 0.0001);
        m.put("R4_min_rate", 0.00002);
        return m;
    }

    private static Map<String, Integer> defaultRarityTargetDistribution() {
        Map<String, Integer> m = new HashMap<>();
        m.put("R1", 50); m.put("R2", 25); m.put("R3", 15); m.put("R4", 8); m.put("R5", 2);
        m.put("tolerance_pct", 3);
        return m;
    }

    private static Map<String, Double> defaultJitterByRarity() {
        Map<String, Double> m = new HashMap<>();
        m.put("R1", 0.15); m.put("R2", 0.15); m.put("R3", 0.12); m.put("R4", 0.08); m.put("R5", 0.05);
        return m;
    }

    private static Map<String, Double> defaultKeepAvgProb() {
        Map<String, Double> m = new HashMap<>();
        m.put("R1", 0.50); m.put("R2", 0.60); m.put("R3", 0.85); m.put("R4", 0.95); m.put("R5", 1.00);
        return m;
    }

    private static List<Integer> defaultDistinctPermitPool() {
        return List.of(5030004, 5030005, 5030012);
    }

    private static Map<String, Integer> defaultHybridPersonaMap() {
        Map<String, Integer> m = new HashMap<>();
        m.put("NPCVendor", 5030004);
        m.put("BulkVendor", 5030010);
        m.put("default", 5030000);
        return m;
    }

    /**
     * Startup-validation. Throws IllegalStateException with the first failing
     * invariant — boot should refuse to proceed.
     */
    public void validate() {
        int pctSum = BOT_MESO_SINK_PCT + BOT_MESO_EVENT_POOL_PCT + BOT_MESO_GM_RESERVE_PCT;
        if (pctSum != 100) {
            throw new IllegalStateException(
                "bot_economy: treasury percentages must sum to 100 (got " + pctSum + ")");
        }
        if (BOT_PRICE_FLOOR_NPC_MULT > BOT_PRICE_CEILING_NPC_MULT) {
            throw new IllegalStateException(
                "bot_economy: BOT_PRICE_FLOOR_NPC_MULT > BOT_PRICE_CEILING_NPC_MULT");
        }
        for (String rarity : new String[]{"R1","R2","R3","R4","R5"}) {
            Double jitter = BOT_PRICE_JITTER_BY_RARITY.get(rarity);
            if (jitter == null || jitter < 0.0 || jitter >= 1.0) {
                throw new IllegalStateException(
                    "bot_economy: BOT_PRICE_JITTER_BY_RARITY[" + rarity + "] out of range");
            }
            Double minMult = BOT_SATURATION_MIN_MULTIPLIERS.get(rarity);
            if (minMult == null || minMult <= 0.0) {
                throw new IllegalStateException(
                    "bot_economy: BOT_SATURATION_MIN_MULTIPLIERS[" + rarity + "] must be > 0");
            }
            if (BOT_SATURATION_THRESHOLDS.get(rarity) == null) {
                throw new IllegalStateException(
                    "bot_economy: BOT_SATURATION_THRESHOLDS[" + rarity + "] missing");
            }
            if (BOT_STAT_KEEP_AVG_PROB_BY_RARITY.get(rarity) == null) {
                throw new IllegalStateException(
                    "bot_economy: BOT_STAT_KEEP_AVG_PROB_BY_RARITY[" + rarity + "] missing");
            }
        }
        double smtWeightSum = BOT_SMT_SIGNAL_P75_WEIGHT + BOT_SMT_SIGNAL_THRESHOLD_WEIGHT
                            + BOT_SMT_SIGNAL_ACTIVE_WEIGHT;
        if (Math.abs(smtWeightSum - 1.0) > 0.001) {
            throw new IllegalStateException(
                "bot_economy: SMT signal weights must sum to 1.0 (got " + smtWeightSum + ")");
        }
        if (BOT_DEMAND_MULT_CLAMP_MIN >= BOT_DEMAND_MULT_CLAMP_MAX) {
            throw new IllegalStateException("bot_economy: demand clamp min >= max");
        }
        if (BOT_NPC_STOCK_DISCOUNT_MIN > BOT_NPC_STOCK_DISCOUNT_MAX
                || BOT_NPC_STOCK_DISCOUNT_MAX > 1.0) {
            throw new IllegalStateException("bot_economy: NPC-stock discount range invalid");
        }
        if (BOT_STAT_PREMIUM_PRIMARY <= 0 || BOT_STAT_PREMIUM_PRIMARY >= BOT_STAT_MAX_MULT
                || BOT_STAT_PREMIUM_SECONDARY <= 0 || BOT_STAT_PREMIUM_SECONDARY >= BOT_STAT_MAX_MULT
                || BOT_STAT_PREMIUM_UNIVERSAL <= 0 || BOT_STAT_PREMIUM_UNIVERSAL >= BOT_STAT_MAX_MULT) {
            throw new IllegalStateException("bot_economy: stat premiums out of range");
        }
        if (BOT_MERCHANT_SPAWN_JITTER_PCT < 0.0) {
            throw new IllegalStateException("bot_economy: spawn jitter pct must be >= 0");
        }
        if (SHADOW_MODE && DRAIN) {
            throw new IllegalStateException("bot_economy: SHADOW_MODE + DRAIN are mutually exclusive");
        }
        if (TEST_MODE && ENABLED) {
            throw new IllegalStateException("bot_economy: TEST_MODE forbidden when ENABLED=true outside tests");
        }
    }
}

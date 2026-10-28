-- Rollback for 047-retire-bot-economy.sql: the 19 tables as changeset 27 created them (empty).

-- ───── bot_inventory — queue + stocked + sold archive ─────
CREATE TABLE IF NOT EXISTS bot_inventory (
    id                 INT PRIMARY KEY AUTO_INCREMENT,
    bot_character_id   INT NULL,
    item_id            INT NOT NULL,
    quantity           INT NOT NULL DEFAULT 1,
    source             ENUM('drop_sim','npc_stock','manual_gm','rotation_return') NOT NULL,
    status             ENUM('pending','stocked','sold','archived') NOT NULL DEFAULT 'pending',
    bot_merchant_id    INT NULL,
    price_hint         INT NULL,
    stat_roll_json     JSON NULL,
    generated_at_smt   TINYINT NULL,
    mispriced_discount DOUBLE NULL,
    generated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state_changed_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_inv_status_gen (status, generated_at),
    INDEX idx_bot_inv_character (bot_character_id),
    INDEX idx_bot_inv_merchant (bot_merchant_id),
    INDEX idx_bot_inv_saturation (item_id, status, generated_at)
);

-- ───── rarity / SMT / demand / npc-catalog ─────
CREATE TABLE IF NOT EXISTS bot_item_rarity (
    item_id              INT PRIMARY KEY,
    rarity               ENUM('R1','R2','R3','R4','R5') NOT NULL,
    aggregate_drop_rate  DOUBLE NULL,
    derivation_source    ENUM('drop_rate','override','manual') NOT NULL DEFAULT 'drop_rate',
    derived_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_item_rarity_rarity (rarity)
);

CREATE TABLE IF NOT EXISTS bot_smt_state (
    id                         TINYINT PRIMARY KEY DEFAULT 1,
    smt_current                TINYINT NOT NULL DEFAULT 0,
    smt_score_last             DOUBLE NULL,
    last_computed_at           DATETIME NULL,
    last_promoted_at           DATETIME NULL,
    next_eligible_promotion_at DATETIME NULL,
    CHECK (id = 1)
);
INSERT IGNORE INTO bot_smt_state (id) VALUES (1);

CREATE TABLE IF NOT EXISTS bot_scroll_demand_cache (
    item_id              INT PRIMARY KEY,
    recent_sales_7d      INT NOT NULL DEFAULT 0,
    expected_baseline_7d DOUBLE NOT NULL DEFAULT 1.0,
    demand_mult          DOUBLE NOT NULL DEFAULT 1.0,
    computed_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_demand_computed (computed_at)
);

CREATE TABLE IF NOT EXISTS bot_npc_sold_items (
    item_id                 INT PRIMARY KEY,
    cheapest_npc_buy_price  INT NOT NULL,
    is_equipment            TINYINT(1) NOT NULL DEFAULT 0,
    extracted_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ───── merchant registry ─────
CREATE TABLE IF NOT EXISTS bot_merchants (
    id               INT PRIMARY KEY AUTO_INCREMENT,
    bot_character_id INT NOT NULL,
    channel          INT NOT NULL,
    world            INT NOT NULL,
    map_id           INT NOT NULL,
    permit_item_id   INT NOT NULL DEFAULT 5030000,
    description      VARCHAR(128) NULL,
    opened_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at       DATETIME NOT NULL,
    status           ENUM('opening','active','closing','archived') NOT NULL DEFAULT 'opening',
    INDEX idx_bot_merchants_status_expires (status, expires_at),
    INDEX idx_bot_merchants_bot_status (bot_character_id, status)
);

-- ───── sales ledger (append-only by convention) ─────
CREATE TABLE IF NOT EXISTS bot_sales_ledger (
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    bot_merchant_id    INT NOT NULL,
    bot_character_id   INT NOT NULL,
    buyer_character_id INT NOT NULL,
    item_id            INT NOT NULL,
    quantity           INT NOT NULL,
    price              INT NOT NULL,
    was_mispriced      TINYINT(1) NOT NULL DEFAULT 0,
    discount_factor    DOUBLE NULL,
    sold_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_sales_buyer (buyer_character_id),
    INDEX idx_bot_sales_sold_at (sold_at),
    INDEX idx_bot_sales_buyer_mispriced (buyer_character_id, was_mispriced, sold_at)
);

CREATE TABLE IF NOT EXISTS bot_sales_ledger_corrections (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    original_ledger_id  BIGINT NOT NULL,
    correction_reason   TEXT NOT NULL,
    corrected_price     INT NULL,
    corrected_by        VARCHAR(64) NOT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_sales_corr_original (original_ledger_id),
    FOREIGN KEY (original_ledger_id) REFERENCES bot_sales_ledger(id)
);

-- ───── treasury (single-row) ─────
CREATE TABLE IF NOT EXISTS bot_treasury (
    id                  TINYINT PRIMARY KEY DEFAULT 1,
    sink_total          BIGINT NOT NULL DEFAULT 0,
    event_pool_balance  BIGINT NOT NULL DEFAULT 0,
    gm_reserve_balance  BIGINT NOT NULL DEFAULT 0,
    lifetime_collected  BIGINT NOT NULL DEFAULT 0,
    lifetime_gm_spent   BIGINT NOT NULL DEFAULT 0,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (id = 1)
);
INSERT IGNORE INTO bot_treasury (id) VALUES (1);

-- ───── shadow-mode mirrors ─────
CREATE TABLE IF NOT EXISTS bot_sales_ledger_shadow LIKE bot_sales_ledger;
CREATE TABLE IF NOT EXISTS bot_treasury_shadow LIKE bot_treasury;
INSERT IGNORE INTO bot_treasury_shadow (id) VALUES (1);

-- ───── telemetry time-series ─────
CREATE TABLE IF NOT EXISTS bot_economy_metrics (
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
    sampled_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_merchant_count       INT NOT NULL,
    inventory_pending           INT NOT NULL,
    inventory_stocked           INT NOT NULL,
    inventory_sold              INT NOT NULL,
    inventory_archived          INT NOT NULL,
    sink_total                  BIGINT NOT NULL,
    event_pool_balance          BIGINT NOT NULL,
    gm_reserve_balance          BIGINT NOT NULL,
    sales_velocity_1h           INT NOT NULL,
    drop_sim_tick_ms            INT NULL,
    lifecycle_tick_ms           INT NULL,
    saturation_suppressed_count INT NOT NULL DEFAULT 0,
    clamp_floor_count           INT NOT NULL DEFAULT 0,
    clamp_ceiling_count         INT NOT NULL DEFAULT 0,
    misprice_rolled_count       INT NOT NULL DEFAULT 0,
    misprice_sold_count         INT NOT NULL DEFAULT 0,
    misprice_blocked_buys       INT NOT NULL DEFAULT 0,
    INDEX idx_bot_metrics_sampled (sampled_at)
);

CREATE TABLE IF NOT EXISTS bot_reconciliation_log (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invariant_name   VARCHAR(128) NOT NULL,
    status           ENUM('pass','warn','fail') NOT NULL,
    detail           TEXT NULL,
    recovery_script  VARCHAR(256) NULL,
    INDEX idx_bot_recon_run_at (run_at),
    INDEX idx_bot_recon_status (status, run_at)
);

CREATE TABLE IF NOT EXISTS bot_gm_audit (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    gm_account     VARCHAR(64) NOT NULL,
    command        VARCHAR(128) NOT NULL,
    arguments      TEXT NULL,
    effect_summary TEXT NULL,
    executed_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bot_audit_account (gm_account, executed_at),
    INDEX idx_bot_audit_executed (executed_at)
);

-- ───── admin-dashboard authority tables (YAML-seeded, DB-authoritative at runtime) ─────
CREATE TABLE IF NOT EXISTS bot_runtime_config (
    key_name        VARCHAR(128) PRIMARY KEY,
    value_json      JSON NOT NULL,
    value_type      ENUM('int','double','bool','string','map','list') NOT NULL,
    tier_required   TINYINT NOT NULL,
    change_version  BIGINT NOT NULL DEFAULT 1,
    last_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_updated_by VARCHAR(64) NULL,
    description     VARCHAR(512) NULL
);

CREATE TABLE IF NOT EXISTS bot_item_prices (
    item_id         INT NOT NULL,
    smt             TINYINT NOT NULL,
    price           INT NOT NULL,
    change_version  BIGINT NOT NULL DEFAULT 1,
    last_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_updated_by VARCHAR(64) NULL,
    PRIMARY KEY (item_id, smt),
    INDEX idx_bot_item_prices_smt (smt)
);

CREATE TABLE IF NOT EXISTS bot_item_price_class (
    item_id           INT PRIMARY KEY,
    price_class       ENUM('npc_consumable','npc_gear','player_equip','scroll_demand','crafting_mat','rare_specialty') NOT NULL,
    is_universal      TINYINT(1) NOT NULL DEFAULT 0,
    derivation_source ENUM('heuristic','override') NOT NULL DEFAULT 'heuristic',
    change_version    BIGINT NOT NULL DEFAULT 1,
    last_updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_updated_by   VARCHAR(64) NULL,
    INDEX idx_bot_item_price_class (price_class)
);

CREATE TABLE IF NOT EXISTS bot_persona_rarity_weights (
    persona         VARCHAR(32) NOT NULL,
    rarity          ENUM('R1','R2','R3','R4','R5') NOT NULL,
    weight          DOUBLE NOT NULL,
    change_version  BIGINT NOT NULL DEFAULT 1,
    last_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_updated_by VARCHAR(64) NULL,
    PRIMARY KEY (persona, rarity)
);

CREATE TABLE IF NOT EXISTS bot_persona_target_counts (
    persona         VARCHAR(32) NOT NULL,
    smt             TINYINT NOT NULL,
    target_count    INT NOT NULL,
    change_version  BIGINT NOT NULL DEFAULT 1,
    last_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_updated_by VARCHAR(64) NULL,
    PRIMARY KEY (persona, smt)
);

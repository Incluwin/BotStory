-- Rollback for 027-bot-economy.sql. Drop in reverse order.
DROP TABLE IF EXISTS bot_persona_target_counts;
DROP TABLE IF EXISTS bot_persona_rarity_weights;
DROP TABLE IF EXISTS bot_item_price_class;
DROP TABLE IF EXISTS bot_item_prices;
DROP TABLE IF EXISTS bot_runtime_config;
DROP TABLE IF EXISTS bot_gm_audit;
DROP TABLE IF EXISTS bot_reconciliation_log;
DROP TABLE IF EXISTS bot_economy_metrics;
DROP TABLE IF EXISTS bot_treasury_shadow;
DROP TABLE IF EXISTS bot_sales_ledger_shadow;
DROP TABLE IF EXISTS bot_treasury;
DROP TABLE IF EXISTS bot_sales_ledger_corrections;
DROP TABLE IF EXISTS bot_sales_ledger;
DROP TABLE IF EXISTS bot_merchants;
DROP TABLE IF EXISTS bot_npc_sold_items;
DROP TABLE IF EXISTS bot_scroll_demand_cache;
DROP TABLE IF EXISTS bot_smt_state;
DROP TABLE IF EXISTS bot_item_rarity;
DROP TABLE IF EXISTS bot_inventory;

ALTER TABLE accounts
    DROP INDEX idx_accounts_bot_flag,
    DROP COLUMN bot_flag;

ALTER TABLE characters
    DROP INDEX idx_characters_bot_next_spawn,
    DROP INDEX idx_characters_bot_persona,
    DROP INDEX idx_characters_bot_flag,
    DROP COLUMN bot_next_spawn_at,
    DROP COLUMN bot_persona,
    DROP COLUMN bot_flag;

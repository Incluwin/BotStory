-- Decision D5 (docs/ECONOMY_PLAN.md section 7, user 2026-10-28): the old bot merchant economy (server/bot, changeset
-- 27) is retired. Its 19 tables held 3 default rows; they were exported on 10-28 to
-- FriendServer/exports/bot-economy/db/bot-economy-tables-2026-10-28.sql. The bot_flag / bot_persona /
-- bot_next_spawn_at columns changeset 27 added to characters and accounts stay: other systems read bot_flag.

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

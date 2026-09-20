-- BOT_ECONOMY.md § SQL views.
-- All views are read-only (no DML grants). GMs with DB access can query them
-- directly; !boteconomy report composes from the same source data.

CREATE OR REPLACE VIEW v_bot_inventory_by_status AS
SELECT status, COUNT(*) AS rows, SUM(quantity) AS units
FROM bot_inventory GROUP BY status;

CREATE OR REPLACE VIEW v_bot_sales_last_7d AS
SELECT DATE(sold_at) AS day, COUNT(*) AS sales, SUM(price) AS meso_flow
FROM bot_sales_ledger
WHERE sold_at > NOW() - INTERVAL 7 DAY
GROUP BY DATE(sold_at)
ORDER BY day DESC;

CREATE OR REPLACE VIEW v_bot_sales_by_item AS
SELECT item_id,
       COUNT(*) AS sales,
       SUM(quantity) AS qty_sold,
       AVG(price) AS avg_price,
       MIN(price) AS min_price,
       MAX(price) AS max_price
FROM bot_sales_ledger
GROUP BY item_id;

CREATE OR REPLACE VIEW v_player_meso_distribution AS
SELECT id, name, level, meso
FROM characters
WHERE bot_flag = 0
ORDER BY meso DESC;

CREATE OR REPLACE VIEW v_bot_treasury_timeline AS
SELECT DATE(sampled_at) AS day,
       MAX(sink_total) AS sink_total,
       MAX(event_pool_balance) AS event_pool_balance,
       MAX(gm_reserve_balance) AS gm_reserve_balance
FROM bot_economy_metrics
GROUP BY DATE(sampled_at)
ORDER BY day DESC;

CREATE OR REPLACE VIEW v_bot_persona_distribution AS
SELECT c.bot_persona AS persona,
       COUNT(m.id) AS active_merchants
FROM characters c
LEFT JOIN bot_merchants m ON m.bot_character_id = c.id AND m.status = 'active'
WHERE c.bot_flag = 1
GROUP BY c.bot_persona
ORDER BY active_merchants DESC;

CREATE OR REPLACE VIEW v_bot_saturation_detail AS
SELECT i.item_id,
       COALESCE(r.rarity, 'R1') AS rarity,
       SUM(i.quantity) AS aged_units,
       MIN(i.generated_at) AS oldest,
       MAX(i.generated_at) AS newest
FROM bot_inventory i
LEFT JOIN bot_item_rarity r ON r.item_id = i.item_id
WHERE i.status IN ('pending', 'stocked')
  AND i.generated_at < NOW() - INTERVAL 48 HOUR
GROUP BY i.item_id, COALESCE(r.rarity, 'R1')
ORDER BY aged_units DESC;

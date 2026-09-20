-- Recovery: items in bot_item_rarity without an entry in bot_item_prices.
-- Not critical — fallback to NPC x 4 kicks in — but worth populating explicitly.
-- Inspect:
SELECT r.item_id, r.rarity
FROM bot_item_rarity r
LEFT JOIN bot_item_prices p ON p.item_id = r.item_id
WHERE p.item_id IS NULL
ORDER BY r.item_id;

-- Remediation: seed prices from the bot-prices.yml file OR from observed
-- NPC x 4 averages. Example (uses NPC x 4 baseline per SMT):
--
-- INSERT INTO bot_item_prices (item_id, smt, price)
-- SELECT r.item_id, s.smt, COALESCE(ii.price, 1000) * 4 * (s.smt + 1)
-- FROM bot_item_rarity r
-- CROSS JOIN (
--     SELECT 0 AS smt UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL
--     SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) s
-- LEFT JOIN (SELECT id AS item_id, NULL AS price) ii ON ii.item_id = r.item_id
-- LEFT JOIN bot_item_prices p ON p.item_id = r.item_id AND p.smt = s.smt
-- WHERE p.item_id IS NULL;

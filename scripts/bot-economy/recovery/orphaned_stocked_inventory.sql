-- Recovery: stocked bot_inventory rows without an active merchant.
-- Inspect:
SELECT i.id, i.item_id, i.bot_merchant_id, m.status
FROM bot_inventory i
LEFT JOIN bot_merchants m ON m.id = i.bot_merchant_id
WHERE i.status = 'stocked' AND (m.id IS NULL OR m.status != 'active');

-- Remediation — return orphans to 'pending' and clear merchant FK:
-- UPDATE bot_inventory
-- SET status = 'pending', bot_merchant_id = NULL
-- WHERE status = 'stocked'
--   AND (bot_merchant_id IS NULL
--        OR bot_merchant_id IN (SELECT id FROM bot_merchants WHERE status != 'active'));

-- Recovery: more than 30% of allowlisted items under saturation suppression.
-- Inspect the offenders — bot_inventory rows aged beyond BOT_SATURATION_AGE_HOURS:
SELECT i.item_id,
       COALESCE(r.rarity, 'R1') AS rarity,
       SUM(i.quantity) AS aged_units,
       MIN(i.generated_at) AS oldest
FROM bot_inventory i
LEFT JOIN bot_item_rarity r ON r.item_id = i.item_id
WHERE i.status IN ('pending','stocked')
  AND i.generated_at < NOW() - INTERVAL 48 HOUR
GROUP BY i.item_id, COALESCE(r.rarity, 'R1')
ORDER BY aged_units DESC
LIMIT 50;

-- Remediation options:
-- 1. Tune BOT_DROP_RATE_MULTIPLIER down in bot_economy config.
-- 2. Narrow the allowlist for over-supplied rarities.
-- 3. Archive very old unsold rows:
-- UPDATE bot_inventory SET status = 'archived'
-- WHERE status = 'pending' AND generated_at < NOW() - INTERVAL 30 DAY;

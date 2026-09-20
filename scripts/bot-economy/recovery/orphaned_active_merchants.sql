-- Recovery: bot_merchants marked active but no live instance (post-restart).
-- Inspect:
SELECT id, bot_character_id, world, channel, map_id, opened_at, expires_at
FROM bot_merchants WHERE status = 'active' ORDER BY opened_at;

-- Remediation — archive stale active rows and clear HasMerchant on bot chars.
-- Run after a server restart when no bot merchants have been re-opened yet.
-- UPDATE bot_merchants SET status = 'archived' WHERE status = 'active';
-- UPDATE characters SET HasMerchant = 0 WHERE bot_flag = 1;
-- UPDATE bot_inventory SET status = 'pending', bot_merchant_id = NULL,
--   source = IF(source = 'drop_sim', 'rotation_return', source)
-- WHERE status = 'stocked';

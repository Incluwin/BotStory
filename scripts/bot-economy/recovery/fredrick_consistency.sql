-- Recovery: bot characters with Fredrick storage older than 7 days.
-- Usually means BotMerchantManager.sweep missed a rotation.
-- Inspect:
SELECT f.cid, c.name, FROM_UNIXTIME(f.timestamp/1000) AS stored_at
FROM fredstorage f
JOIN characters c ON c.id = f.cid
WHERE c.bot_flag = 1
ORDER BY f.timestamp;

-- Remediation — call FredrickProcessor.sweepBotStorage for each bot:
-- (Run from Java shell / admin console; direct SQL below as fallback.)
-- DELETE FROM inventoryitems WHERE characterid IN
--     (SELECT id FROM characters WHERE bot_flag = 1) AND inventorytype = 1;
-- DELETE FROM fredstorage WHERE cid IN
--     (SELECT id FROM characters WHERE bot_flag = 1);

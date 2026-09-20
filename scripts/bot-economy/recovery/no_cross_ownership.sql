-- Recovery: bot_inventory rows referencing non-bot characters.
-- Inspect:
SELECT i.id, i.bot_character_id, c.name, c.bot_flag
FROM bot_inventory i
JOIN characters c ON c.id = i.bot_character_id
WHERE i.bot_character_id IS NOT NULL AND c.bot_flag = 0;

-- Remediation — detach the cross-ownership rows and archive:
-- UPDATE bot_inventory SET status = 'archived', bot_character_id = NULL
-- WHERE id IN (
--     SELECT i.id FROM (SELECT id, bot_character_id FROM bot_inventory) i
--     JOIN characters c ON c.id = i.bot_character_id
--     WHERE c.bot_flag = 0
-- );

-- Recovery: a persona with target > 0 has zero active merchants.
-- Inspect:
SELECT c.bot_persona AS persona,
       COUNT(m.id) AS active
FROM characters c
LEFT JOIN bot_merchants m ON m.bot_character_id = c.id AND m.status = 'active'
WHERE c.bot_flag = 1
GROUP BY c.bot_persona
ORDER BY active;

-- Check bot availability per persona:
SELECT bot_persona, COUNT(*) AS bots
FROM characters WHERE bot_flag = 1 GROUP BY bot_persona;

-- Remediation — if a persona has 0 active AND 0 seeded bots, run the seeding script.
-- If bots exist but haven't spawned, clear bot_next_spawn_at to force eligibility:
-- UPDATE characters SET bot_next_spawn_at = NULL
-- WHERE bot_flag = 1 AND bot_persona = '<PersonaName>';

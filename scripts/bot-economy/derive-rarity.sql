-- BOT_ECONOMY.md § Rarity scale — populate bot_item_rarity from Cosmic's
-- drop_data aggregate drop rates. Run once at seed time.
--
-- Cosmic's drop_data rows give per-monster drop probability in basis points
-- (chance / 1_000_000). Aggregate by item and map to rarity tiers.

INSERT INTO bot_item_rarity (item_id, rarity, aggregate_drop_rate, derivation_source, derived_at)
SELECT itemid AS item_id,
       CASE
           WHEN SUM(chance / 1000000.0) >= 0.01    THEN 'R1'
           WHEN SUM(chance / 1000000.0) >= 0.001   THEN 'R2'
           WHEN SUM(chance / 1000000.0) >= 0.0001  THEN 'R3'
           WHEN SUM(chance / 1000000.0) >= 0.00002 THEN 'R4'
           ELSE 'R5'
       END AS rarity,
       SUM(chance / 1000000.0) AS aggregate_drop_rate,
       'drop_rate',
       NOW()
FROM drop_data
WHERE itemid > 0
GROUP BY itemid
ON DUPLICATE KEY UPDATE
    rarity = VALUES(rarity),
    aggregate_drop_rate = VALUES(aggregate_drop_rate),
    derivation_source = CASE
        WHEN bot_item_rarity.derivation_source = 'override' THEN 'override'  -- preserve overrides
        ELSE VALUES(derivation_source)
    END,
    derived_at = NOW();

-- Sanity check — distribution:
-- SELECT rarity, COUNT(*) FROM bot_item_rarity GROUP BY rarity ORDER BY rarity;

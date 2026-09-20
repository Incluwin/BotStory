-- Recovery for invariant: treasury_sum.
-- Drift means lifetime_collected diverges from sum of partitions.
-- Step 1 — inspect:
SELECT
    lifetime_collected AS reported,
    sink_total + event_pool_balance + gm_reserve_balance + lifetime_gm_spent AS computed,
    (sink_total + event_pool_balance + gm_reserve_balance + lifetime_gm_spent) - lifetime_collected AS delta
FROM bot_treasury WHERE id = 1;

-- Step 2 — recompute lifetime_collected from the ledger (source of truth):
-- SELECT COALESCE(SUM(price), 0) AS ledger_total FROM bot_sales_ledger;

-- Step 3 — remediation (uncomment after verifying Step 2):
-- UPDATE bot_treasury
-- SET lifetime_collected = (SELECT COALESCE(SUM(price), 0) FROM bot_sales_ledger),
--     sink_total = 0,
--     event_pool_balance = 0,
--     gm_reserve_balance = 0,
--     lifetime_gm_spent = 0
-- WHERE id = 1;
-- COMMIT;
--
-- NOTE: Step 3 resets the partitioning; re-running natural collection will rebuild it.

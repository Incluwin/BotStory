-- Recovery: bot_sales_ledger rows out of order (clock skew or manual insert).
-- Usually benign — sold_at is a reporting field, not load-bearing — but flag for review.
-- Inspect:
SELECT id, bot_merchant_id, buyer_character_id, sold_at
FROM (
    SELECT id, bot_merchant_id, buyer_character_id, sold_at,
           LAG(sold_at) OVER (ORDER BY id) AS prev
    FROM bot_sales_ledger
) t
WHERE prev IS NOT NULL AND sold_at < prev;

-- Remediation — append a correction row acknowledging the out-of-order entry:
-- INSERT INTO bot_sales_ledger_corrections (original_ledger_id, correction_reason, corrected_price, corrected_by)
-- VALUES (<ledger_id>, 'out-of-order sold_at — clock skew suspected', NULL, 'gm_account');

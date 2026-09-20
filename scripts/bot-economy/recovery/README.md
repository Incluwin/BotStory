# Reconciliation Recovery Scripts

Per BOT_ECONOMY.md § Data integrity and reconciliation — every invariant has
a matching recovery SQL script. GMs run these manually after
`!boteconomy reconcile` flags a violation. No automated healing in v1.

| Invariant (from BotReconciliationTask) | Recovery script |
|---|---|
| treasury_sum | [treasury_sum.sql](treasury_sum.sql) |
| orphaned_stocked_inventory | [orphaned_stocked_inventory.sql](orphaned_stocked_inventory.sql) |
| orphaned_active_merchants | [orphaned_active_merchants.sql](orphaned_active_merchants.sql) |
| no_bot_logins | [no_bot_logins.sql](no_bot_logins.sql) |
| no_cross_ownership | [no_cross_ownership.sql](no_cross_ownership.sql) |
| fredrick_consistency | [fredrick_consistency.sql](fredrick_consistency.sql) |
| ledger_monotonicity | [ledger_monotonicity.sql](ledger_monotonicity.sql) |
| pricing_coverage | [pricing_coverage.sql](pricing_coverage.sql) |
| persona_distribution | [persona_distribution.sql](persona_distribution.sql) |
| saturation_suppression | [saturation_suppression.sql](saturation_suppression.sql) |

Run order: read the script before running — each begins with a safe
investigative `SELECT` and ends with the remediation `UPDATE`/`INSERT`.

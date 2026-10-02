-- Rollback for 035-pq-run-pace.sql: the clear-time records are dropped; the entry NPC estimates from the pace profile's
-- priors again until new runs are recorded.
DROP TABLE IF EXISTS pq_run_pace;

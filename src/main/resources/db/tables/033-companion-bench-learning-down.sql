-- Rollback for 033-companion-bench-learning.sql: the measured bench yields are dropped; the ledger credits its priors.
DROP TABLE IF EXISTS companion_yield;

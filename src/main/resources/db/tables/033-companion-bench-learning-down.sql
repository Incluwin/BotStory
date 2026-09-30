-- Rollback for 033-companion-bench-learning.sql: the measured bench yields are dropped; the ledger credits its priors.
-- A stall's stock still listed here is not handed back by the rollback: run it with no bench stall open.
DROP TABLE IF EXISTS living_stall_stock;
ALTER TABLE living_roster DROP COLUMN your_call;
DROP TABLE IF EXISTS companion_yield;

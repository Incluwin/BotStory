-- Rollback for 032-companion-crew.sql: the crews' formations go back to living in memory only.
DROP TABLE IF EXISTS living_crew;

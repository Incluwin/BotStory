-- Rollback for 055-living-paper.sql.
DROP INDEX idx_living_event_at ON living_event_log;
DROP TABLE IF EXISTS living_paper;

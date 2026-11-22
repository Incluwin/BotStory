-- Rollback for 057-companion-traits.sql.
ALTER TABLE companion_performance
    DROP COLUMN min_hp_percent,
    DROP COLUMN close_calls;

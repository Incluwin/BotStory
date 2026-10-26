-- Rollback for 039-citizen-person-memory.sql: citizens forget the people they met; the shared minutes stay.
ALTER TABLE living_acquaintance
    DROP INDEX idx_living_acq_memory_at,
    DROP COLUMN episodes,
    DROP COLUMN first_met_at,
    DROP COLUMN memory_at,
    DROP COLUMN warmth;

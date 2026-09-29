-- Rollback for 034-companion-crew-fresh.sql: nobody's crew is kept fresh any more.
ALTER TABLE living_crew DROP COLUMN keep_fresh;

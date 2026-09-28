-- Rollback for 031-companion-manager.sql. Rows in the new seat states go back to the bench first, so the code
-- before changeset 40 can still read every row.
DROP TABLE IF EXISTS companion_performance;
UPDATE living_roster SET state = 'BENCHED' WHERE state IN ('TRAVELLING', 'HOLD', 'PARKED');
ALTER TABLE living_roster
    DROP COLUMN parked_at,
    DROP COLUMN hold_map_id,
    DROP COLUMN eta_at,
    DROP COLUMN settings_json,
    DROP COLUMN lineup_slot;

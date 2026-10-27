-- Rollback for 043-owner-habits.sql: the owner habits are dropped (companions drink, loot and camp as configured), and
-- the citizens' measured yields with the source column (the companions' rows stay as they were).
DELETE FROM companion_yield WHERE source <> 'companion';
ALTER TABLE companion_yield
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (job_branch, level_band, camp_map_id),
    DROP COLUMN source;
DROP TABLE IF EXISTS living_owner_habit;

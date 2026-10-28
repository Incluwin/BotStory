-- Rollback for 045-companion-role.sql: mentors and apprentices are plain hires again (a mentor still in a party stays
-- as a hire until its owner lets it go).
ALTER TABLE living_roster
    DROP COLUMN role_target,
    DROP COLUMN role;

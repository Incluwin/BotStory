-- Mentor and apprentice (docs/BOT_CONCEPT_PLAN.md F4, changeset 54-companion-role).
--
-- living_roster.role: what a hired citizen is to its owner besides a companion. NULL for every companion so far;
-- MENTOR for a citizen born to show a new character around until its second job (it holds a party seat but no roster
-- slot, pays nothing and takes nothing); APPRENTICE for a beginner citizen its owner adopted, whose job branch its owner
-- picks at each advancement and who takes it at its instructor. living_roster.role_target: the job an APPRENTICE's
-- owner picked for its next advancement (Job name, e.g. WARRIOR, then FIGHTER), NULL until picked. A row's release
-- deletes it, so a later hire of the same citizen starts with no role. Written apart from the rest of the row
-- (LivingStore.saveRosterRole), never by saveRoster. Ships behind living_world.MENTOR_ENABLED and APPRENTICE_ENABLED.
-- Rollback: 045-companion-role-down.sql.

ALTER TABLE living_roster
    ADD COLUMN role VARCHAR(16) NULL,
    ADD COLUMN role_target VARCHAR(32) NULL;

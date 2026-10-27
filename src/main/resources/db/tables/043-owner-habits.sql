-- Owner habits and learned spots (docs/BOT_CONCEPT_PLAN.md F2, changeset 52-owner-habits).
--
-- living_owner_habit: what the living world noticed of one human who owns companions (OwnerHabits): the HP and MP
-- they drank a potion at, the item categories they pick up or leave, what they sell to shops, the maps they train on
-- per level band and how well each went, the skills they fight with per job, and the habits their companions already
-- mentioned. One JSON document per human, written behind from memory. No foreign key, as living_roster. Ships behind
-- living_world.OWNER_HABITS_ENABLED.
--
-- companion_yield.source: who measured a row. 'companion' rows are the live bench slices' (as before); 'citizen' rows
-- are what loaded citizens measured on their TRAIN stints (population.CITIZEN_SPOT_LEARNING_ENABLED), which the
-- citizens' own spot picker reads and which sharpen the companions' priors. Every existing row is a companion's.
-- Rollback: 043-owner-habits-down.sql.

CREATE TABLE IF NOT EXISTS living_owner_habit (
    character_id    INT          NOT NULL,
    habits          MEDIUMTEXT   NOT NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id)
);

ALTER TABLE companion_yield
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'companion',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (job_branch, level_band, camp_map_id, source);

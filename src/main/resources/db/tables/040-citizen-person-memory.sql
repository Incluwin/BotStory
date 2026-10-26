-- Citizens remember the people they meet (docs/CITIZEN_PERSON_MEMORY_PLAN.md, changeset 49-citizen-person-memory): what
-- a citizen remembers of one human, on the pair's living_acquaintance row beside the shared minutes. warmth -100..100
-- (faded from memory_at when read), memory_at = when the memory last changed (NULL: nothing remembered),
-- first_met_at = its first remembered episode, episodes = at most 8 lines KIND|epochMinute|mapId|count|detail
-- (PersonMemory.encode). The prune finds the oldest memories by memory_at. Ships behind population.MEMORY_ENABLED.
-- Rollback: 040-citizen-person-memory-down.sql.

ALTER TABLE living_acquaintance
    ADD COLUMN warmth       SMALLINT     NOT NULL DEFAULT 0,
    ADD COLUMN memory_at    TIMESTAMP    NULL,
    ADD COLUMN first_met_at TIMESTAMP    NULL,
    ADD COLUMN episodes     VARCHAR(600) NOT NULL DEFAULT '',
    ADD INDEX idx_living_acq_memory_at (memory_at);

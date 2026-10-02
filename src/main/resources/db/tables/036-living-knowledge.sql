-- Personal knowledge (changeset 45-living-knowledge, docs/SERVER_KNOWLEDGE_PLAN.md 7): what each bot (citizen,
-- Resident, companion) has lived and heard, written behind by server.knowledge.PersonalKnowledge at most every 5 min.
-- entries: space-separated tokens, oldest touch first: <kind><id>:<count><source>; kind M map, O mob, I item; source
-- L lived, H heard; at most 144 tokens (32 maps, 48 mobs, 64 items). The synthesized past (career, level curve,
-- persona) is never stored: it is rebuilt from the id and level at load. updated_ms: epoch ms (no TIMESTAMP, so no
-- session time zone). No foreign key, as companion_performance. Ships behind knowledge.PERSONAL.
-- Rollback: 036-living-knowledge-down.sql.

CREATE TABLE IF NOT EXISTS living_known_world (
    character_id INT    NOT NULL,
    entries      TEXT   NOT NULL,
    updated_ms   BIGINT NOT NULL,
    PRIMARY KEY (character_id)
);

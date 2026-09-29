-- Companion crews (docs/BOT_MANAGEMENT_PLAN.md P3b, "Companion settings", changeset 41-companion-crew): what an owner
-- set for their whole crew, one row per owner. formation is the crew's formation ("SPREAD 80 0": type, px, snap
-- range), set on the Crew window's Formation... page or by a formation order in chat, and put back when a companion
-- loads after a relog or a restart; a companion's own place stays in living_roster.settings_json. No foreign key, as
-- living_roster. Ships behind living_world.ENABLED like 029. Rollback: 032-companion-crew-down.sql.

CREATE TABLE IF NOT EXISTS living_crew (
    character_id    INT          NOT NULL,
    formation       VARCHAR(32)  NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id)
);

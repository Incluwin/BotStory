-- Companion manager (docs/BOT_MANAGEMENT_PLAN.md, "Data model", changeset 40-companion-manager).
-- living_roster gains the usual lineup (lineup_slot, 1 = first), per-companion settings, and the travel, hold and
-- park columns. state stays VARCHAR(16) and may also hold TRAVELLING, HOLD and PARKED (entered from P2b on); only
-- RosterService writes it. No foreign key: deleting an owner runs release logic through a hook instead.
-- Ships behind living_world.ENABLED like 029. Rollback: 031-companion-manager-down.sql.

ALTER TABLE living_roster
    ADD COLUMN lineup_slot   TINYINT       NULL,
    ADD COLUMN settings_json VARCHAR(1024) NULL,
    ADD COLUMN eta_at        TIMESTAMP     NULL,
    ADD COLUMN hold_map_id   INT           NULL,
    ADD COLUMN parked_at     TIMESTAMP     NULL;

-- One row per companion per PQ or boss run (CompanionRunRecorder).
CREATE TABLE IF NOT EXISTS companion_performance (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    owner_character_id      INT           NOT NULL,
    companion_character_id  INT           NOT NULL,
    persona_id              VARCHAR(64)   NOT NULL,
    activity                VARCHAR(16)   NOT NULL,
    outcome                 VARCHAR(12)   NOT NULL,
    duration_s              INT           NOT NULL DEFAULT 0,
    deaths                  SMALLINT      NOT NULL DEFAULT 0,
    damage_share            DECIMAL(5,4)  NULL,
    party_size              TINYINT       NOT NULL DEFAULT 0,
    recorded_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_companion_performance_companion (companion_character_id, activity)
);

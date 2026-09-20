-- Living world: resident bots with quest lines, bonds, contracts, roster and bench.
-- See docs/LIVING_RPG_PLAN.md. Ships behind living_world.ENABLED (config.yaml).
-- Fully reversible - see 029-living-world-down.sql.

-- World-level state of one Persona (arc chapter, backing character, employer).
CREATE TABLE IF NOT EXISTS living_persona_state (
    persona_id      VARCHAR(64)  NOT NULL,
    character_id    INT          NULL,
    arc_chapter     VARCHAR(64)  NULL,
    shaped_version  INT          NOT NULL DEFAULT 0,
    employed_by     INT          NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (persona_id),
    INDEX idx_living_persona_character (character_id)
);

-- World flags set by quests, arcs and rumors (shared by every player).
CREATE TABLE IF NOT EXISTS living_world_flag (
    flag        VARCHAR(128) NOT NULL,
    set_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    set_by      INT          NULL,
    PRIMARY KEY (flag)
);

-- Per (player, persona) relationship, bond flags and contract state.
CREATE TABLE IF NOT EXISTS living_bond (
    character_id        INT          NOT NULL,
    persona_id          VARCHAR(64)  NOT NULL,
    stage               VARCHAR(16)  NOT NULL DEFAULT 'MET',
    points              INT          NOT NULL DEFAULT 0,
    flags               TEXT         NULL,
    contract_state      VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    contract_started_at TIMESTAMP    NULL,
    offer_expires_at    TIMESTAMP    NULL,
    proof_progress      INT          NOT NULL DEFAULT 0,
    paid_meso           BIGINT       NOT NULL DEFAULT 0,
    party_seconds       INT          NOT NULL DEFAULT 0,
    left_at             TIMESTAMP    NULL,
    last_interaction_at TIMESTAMP    NULL,
    PRIMARY KEY (character_id, persona_id),
    INDEX idx_living_bond_persona (persona_id, contract_state)
);

-- Player progress on Persona quests.
CREATE TABLE IF NOT EXISTS living_quest_state (
    character_id    INT          NOT NULL,
    quest_id        VARCHAR(64)  NOT NULL,
    step_index      INT          NOT NULL DEFAULT 0,
    progress        INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    started_at      TIMESTAMP    NULL,
    completed_at    TIMESTAMP    NULL,
    repeats         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, quest_id),
    INDEX idx_living_quest_status (character_id, status)
);

-- Recruited companions: active in party, benched at a camp, away or released.
CREATE TABLE IF NOT EXISTS living_roster (
    character_id        INT          NOT NULL,
    persona_id          VARCHAR(64)  NOT NULL,
    state               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    assignment          VARCHAR(16)  NOT NULL DEFAULT 'TRAIN',
    assignment_item     INT          NULL,
    assignment_count    INT          NULL,
    camp_map_id         INT          NULL,
    accrued_exp         BIGINT       NOT NULL DEFAULT 0,
    haul_meso           BIGINT       NOT NULL DEFAULT 0,
    wages_paid_until    TIMESTAMP    NULL,
    recruited_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    benched_at          TIMESTAMP    NULL,
    last_ledger_at      TIMESTAMP    NULL,
    farm_seconds_today  INT          NOT NULL DEFAULT 0,
    farm_day            DATE         NULL,
    PRIMARY KEY (character_id, persona_id),
    INDEX idx_living_roster_persona (persona_id, state)
);

-- Items a benched companion has farmed and not yet handed over.
CREATE TABLE IF NOT EXISTS living_haul_item (
    character_id    INT          NOT NULL,
    persona_id      VARCHAR(64)  NOT NULL,
    item_id         INT          NOT NULL,
    quantity        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, persona_id, item_id)
);

-- Provenance of haul hand-overs (a Persona's own quests ignore what it farmed itself).
CREATE TABLE IF NOT EXISTS living_haul_transfer (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    character_id    INT          NOT NULL,
    persona_id      VARCHAR(64)  NOT NULL,
    item_id         INT          NOT NULL,
    quantity        INT          NOT NULL,
    transferred_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_living_haul_transfer (character_id, persona_id, item_id, transferred_at)
);

-- Free-form phrases the intent judge resolved confidently; matched before the regexes.
CREATE TABLE IF NOT EXISTS living_learned_phrase (
    normalized_text VARCHAR(191) NOT NULL,
    canonical       VARCHAR(64)  NOT NULL,
    hits            INT          NOT NULL DEFAULT 1,
    source          VARCHAR(8)   NOT NULL DEFAULT 'JEV',
    approved        TINYINT(1)   NOT NULL DEFAULT 0,
    first_seen      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (normalized_text)
);

-- Append-only audit of ledger transitions.
CREATE TABLE IF NOT EXISTS living_event_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    character_id    INT          NULL,
    persona_id      VARCHAR(64)  NULL,
    kind            VARCHAR(32)  NOT NULL,
    detail          VARCHAR(512) NULL,
    PRIMARY KEY (id),
    INDEX idx_living_event_character (character_id, at)
);

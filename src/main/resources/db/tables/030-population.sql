-- Populated server: generated citizens with lives (docs/POPULATED_SERVER_PLAN.md).
-- Ships behind population.ENABLED (config.yaml). Fully reversible - see 030-population-down.sql.

-- One row per citizen: a bot character with a cohort, schedule, career and social ties.
CREATE TABLE IF NOT EXISTS living_citizen (
    character_id     INT          NOT NULL,
    name             VARCHAR(13)  NOT NULL,
    cohort           VARCHAR(32)  NOT NULL,
    circle_id        INT          NULL,
    guild_id         INT          NULL,
    home_region      VARCHAR(32)  NOT NULL,
    career           VARCHAR(48)  NOT NULL,
    personality      VARCHAR(32)  NOT NULL,
    voice            VARCHAR(32)  NOT NULL,
    pace             DOUBLE       NOT NULL DEFAULT 1.0,
    offset_minutes   INT          NOT NULL DEFAULT 0,
    channel          INT          NOT NULL DEFAULT 1,
    flags            VARCHAR(255) NOT NULL DEFAULT '',
    state            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    joined_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retires_at       TIMESTAMP    NULL,
    last_online_at   TIMESTAMP    NULL,
    last_ledger_at   TIMESTAMP    NULL,
    accrued_exp      BIGINT       NOT NULL DEFAULT 0,
    scheduled_minutes INT         NOT NULL DEFAULT 0,
    life_summary     VARCHAR(512) NOT NULL DEFAULT '',
    PRIMARY KEY (character_id),
    UNIQUE KEY uq_living_citizen_name (name),
    INDEX idx_living_citizen_cohort (cohort),
    INDEX idx_living_citizen_state (state)
);

-- Friend groups: citizens who log in together, party and buddy each other.
CREATE TABLE IF NOT EXISTS living_circle (
    id          INT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(48)  NOT NULL,
    cohort      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- Bot guild metadata on top of the real guilds table.
CREATE TABLE IF NOT EXISTS living_guild (
    guild_id     INT          NOT NULL,
    stance       VARCHAR(16)  NOT NULL DEFAULT 'open',
    level_min    INT          NOT NULL DEFAULT 1,
    level_max    INT          NOT NULL DEFAULT 200,
    motto        VARCHAR(128) NOT NULL DEFAULT '',
    cohort       VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (guild_id)
);

-- Server price index: what things sell for, drifted by observed sales.
CREATE TABLE IF NOT EXISTS living_price_index (
    item_id     INT          NOT NULL,
    price       BIGINT       NOT NULL,
    samples     INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id)
);

-- Shared time between a citizen and a human (drives buddy requests and guild invites).
CREATE TABLE IF NOT EXISTS living_acquaintance (
    character_id  INT        NOT NULL,
    player_id     INT        NOT NULL,
    party_minutes INT        NOT NULL DEFAULT 0,
    map_minutes   INT        NOT NULL DEFAULT 0,
    buddy_asked   TINYINT(1) NOT NULL DEFAULT 0,
    guild_asked   TINYINT(1) NOT NULL DEFAULT 0,
    last_at       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, player_id),
    INDEX idx_living_acq_player (player_id)
);

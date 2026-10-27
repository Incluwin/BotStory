-- Clear records and the rival crew (docs/BOT_CONCEPT_PLAN.md F7, changeset 53-rival-crew).
--
-- living_clear: one row per cleared event instance (EventInstanceManager.clearPQ: a party quest's end or a boss's death),
-- whoever cleared it: players, their companions, citizens. quest = the event script ("KerningPQ", "BalrogBattle_Easy").
-- started_at = the instance's first event timer (its creation when it never ran one); duration_ms = 0 when unknown.
-- kind: HUMAN (a player was in it), COMPANION (companions, no player), CITIZEN (citizens only), BOT (other Agents).
-- crew_id: the living_rival crew credited with it (two or more of its members in it, its player not), else NULL.
-- members: "id:K:name,..." (K = H player, P companion, C citizen, B other bot), for the records page.
-- Written by population.CLEAR_RECORDS_ENABLED (on: measuring only); read by RIVALS_ENABLED's records page and crews.
--
-- living_clear_member: who was in each clear, for a player's best time per quest (kind as in members).
--
-- living_rival: one rival crew per player account, racing its main character (the account's highest level):
-- name (also its guild's), guild_id and circle_id (the crew's own guild and circle), leader_id (its guild master),
-- members (citizen ids, comma separated), help_asked_at (its last help request, F7.4). Times are UTC (SqlTime).
-- No foreign keys, as living_roster. Ships behind population.RIVALS_ENABLED. Rollback: 044-rival-crew-down.sql.

CREATE TABLE IF NOT EXISTS living_clear (
    id              INT UNSIGNED NOT NULL AUTO_INCREMENT,
    quest           VARCHAR(32)  NOT NULL,
    instance        VARCHAR(64)  NOT NULL DEFAULT '',
    channel         INT          NOT NULL DEFAULT 0,
    map_id          INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMP    NULL,
    cleared_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    duration_ms     BIGINT       NOT NULL DEFAULT 0,
    kind            VARCHAR(9)   NOT NULL,
    crew_id         INT          NULL,
    members         VARCHAR(400) NOT NULL DEFAULT '',
    PRIMARY KEY (id),
    INDEX idx_living_clear_quest (quest, duration_ms),
    INDEX idx_living_clear_crew (crew_id, quest)
);

CREATE TABLE IF NOT EXISTS living_clear_member (
    clear_id        INT UNSIGNED NOT NULL,
    character_id    INT          NOT NULL,
    kind            CHAR(1)      NOT NULL,
    PRIMARY KEY (clear_id, character_id),
    INDEX idx_living_clear_member_character (character_id)
);

CREATE TABLE IF NOT EXISTS living_rival (
    id              INT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id      INT          NOT NULL,
    main_id         INT          NOT NULL,
    name            VARCHAR(12)  NOT NULL,
    guild_id        INT          NOT NULL DEFAULT 0,
    circle_id       INT          NOT NULL DEFAULT 0,
    leader_id       INT          NOT NULL DEFAULT 0,
    members         VARCHAR(96)  NOT NULL DEFAULT '',
    formed_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    help_asked_at   TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX idx_living_rival_account (account_id)
);

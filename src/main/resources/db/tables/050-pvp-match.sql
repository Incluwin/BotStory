-- PvP matches (docs/PVP_PLAN.md V3.4): one row a finished duel that counts for a record (kind RIVAL: a rival crew's
-- challenge; LADDER and TOURNAMENT later), and one row a fighter in it. Side 0 is the player side; winner_side is 0, 1,
-- or -1 for a draw. ended_at is UTC like every living time. Head-to-head tallies (the records page, the crew quoting
-- it, letters) are counted from these. Rollback: 050-pvp-match-down.sql.
CREATE TABLE IF NOT EXISTS pvp_match (
    id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
    kind        VARCHAR(16)  NOT NULL,
    ended_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    map_id      INT          NOT NULL,
    seconds     INT          NOT NULL,
    winner_side TINYINT      NOT NULL,
    reason      VARCHAR(48)  NOT NULL,
    crew_id     INT          NOT NULL DEFAULT 0,
    odds        DOUBLE       NULL,
    PRIMARY KEY (id),
    KEY pvp_match_crew (crew_id, ended_at)
);

CREATE TABLE IF NOT EXISTS pvp_match_fighter (
    match_id     INT UNSIGNED NOT NULL,
    character_id INT          NOT NULL,
    side         TINYINT      NOT NULL,
    human        TINYINT(1)   NOT NULL,
    ko           TINYINT(1)   NOT NULL,
    dealt        BIGINT       NOT NULL,
    taken        BIGINT       NOT NULL,
    PRIMARY KEY (match_id, character_id),
    KEY pvp_match_fighter_character (character_id)
);

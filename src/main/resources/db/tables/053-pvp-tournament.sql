-- The weekly coliseum tournament (docs/PVP_PLAN.md V5): one row a ladder bracket a week (week_of is the week's Monday,
-- days since 1970-01-01, in the server's own time zone), and one row a seat in it. Players who sign up get a seat row
-- before the start (seed 0 until seated); at the start the seats are seeded by rating. reached is how far a seat got:
-- -1 still in or never played, 0 out in the first round, 1 semi-finals, 2 the final, 3 champion. paid 0 marks
-- winnings a player was offline for; Tigun pays them on their next visit. Times are UTC like every living time.
-- Rollback: 053-pvp-tournament-down.sql.
CREATE TABLE IF NOT EXISTS pvp_tournament (
    id           INT UNSIGNED NOT NULL AUTO_INCREMENT,
    bracket      VARCHAR(16)  NOT NULL,
    week_of      INT          NOT NULL,
    starts_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state        VARCHAR(12)  NOT NULL,
    pool         BIGINT       NOT NULL DEFAULT 0,
    champion_id  INT          NOT NULL DEFAULT 0,
    runner_up_id INT          NOT NULL DEFAULT 0,
    finished_at  TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY pvp_tournament_week (bracket, week_of)
);

CREATE TABLE IF NOT EXISTS pvp_tournament_seat (
    tournament_id INT UNSIGNED NOT NULL,
    character_id  INT          NOT NULL,
    name          VARCHAR(13)  NOT NULL,
    human         TINYINT(1)   NOT NULL,
    seed          TINYINT      NOT NULL DEFAULT 0,
    fee           BIGINT       NOT NULL DEFAULT 0,
    reached       TINYINT      NOT NULL DEFAULT -1,
    trophies      INT          NOT NULL DEFAULT 0,
    mesos         BIGINT       NOT NULL DEFAULT 0,
    paid          TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (tournament_id, character_id),
    KEY pvp_tournament_seat_character (character_id)
);

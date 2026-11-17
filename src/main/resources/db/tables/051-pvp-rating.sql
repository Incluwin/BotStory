-- Coliseum ladder ratings (docs/PVP_PLAN.md V4.2): one Glicko-2 rating a character a bracket (ROOKIE Lv 30-59,
-- VETERAN 60-99, CHAMPION 100+) a season, for humans and citizens alike. rating/deviation are on the 1500 scale;
-- updated_at (UTC) is the last rated match, so an idle rating widens by the days since. Rollback: 051-pvp-rating-down.sql.
CREATE TABLE IF NOT EXISTS pvp_rating (
    character_id INT         NOT NULL,
    bracket      VARCHAR(16) NOT NULL,
    season       INT         NOT NULL DEFAULT 1,
    rating       DOUBLE      NOT NULL,
    deviation    DOUBLE      NOT NULL,
    volatility   DOUBLE      NOT NULL,
    wins         INT         NOT NULL DEFAULT 0,
    losses       INT         NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, bracket, season),
    KEY pvp_rating_board (season, bracket, rating)
);

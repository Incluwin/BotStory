-- Guild life (docs/WORLD_SYSTEMS_PLAN.md N5, changeset 67-guild-life).
--
-- living_guild_goal: a bot guild's goal for one week (N5.2, GuildGoals behind population.GUILD_GOALS_ENABLED). week_of
-- counts weeks since the epoch, Monday start (UTC), as pvp_rating.week_of. goal is the kind (PQ_CLEARS, NEEDS_MET,
-- COLISEUM_WINS; FIELD_BOSS_KILLS later), detail what it is of (the lobby id for PQ_CLEARS, "kpq"), target and progress
-- counts of members' real deeds that week (living_clear, living_need MET rows, pvp_match wins). progress only grows.
-- done is set once, by the UPDATE that pays the guild its GP, so a restart never pays twice. Times are UTC (SqlTime).
--
-- pvp_guild_rating: the guild book of the coliseum ladder (N5.3): one Glicko-2 rating a guild a bracket a season for
-- guild-versus-guild team matches, as pvp_rating keeps one a character (bracket GUILD_ROOKIE, GUILD_VETERAN,
-- GUILD_CHAMPION). Kept apart from pvp_rating so nothing reading characters' ratings ever sees a guild id.
-- No foreign keys, as living_roster. Rollback: 058-guild-life-down.sql.

CREATE TABLE IF NOT EXISTS living_guild_goal (
    guild_id        INT          NOT NULL,
    week_of         INT          NOT NULL,
    goal            VARCHAR(16)  NOT NULL,
    detail          VARCHAR(16)  NOT NULL DEFAULT '',
    target          INT          NOT NULL,
    progress        INT          NOT NULL DEFAULT 0,
    done            TINYINT(1)   NOT NULL DEFAULT 0,
    set_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    done_at         TIMESTAMP    NULL,
    PRIMARY KEY (guild_id, week_of)
);

CREATE TABLE IF NOT EXISTS pvp_guild_rating (
    guild_id     INT         NOT NULL,
    bracket      VARCHAR(16) NOT NULL,
    season       INT         NOT NULL DEFAULT 1,
    rating       DOUBLE      NOT NULL,
    deviation    DOUBLE      NOT NULL,
    volatility   DOUBLE      NOT NULL,
    wins         INT         NOT NULL DEFAULT 0,
    losses       INT         NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    week_of      INT         NOT NULL DEFAULT 0,
    week_rating  DOUBLE      NOT NULL DEFAULT 0,
    PRIMARY KEY (guild_id, bracket, season),
    KEY pvp_guild_rating_board (season, bracket, rating)
);

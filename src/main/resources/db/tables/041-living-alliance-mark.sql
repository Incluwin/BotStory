-- Bot guild alliances (docs/BOT_GUILD_ALLIANCES_PLAN.md, changeset 48-living-alliance-mark): dates the alliance check
-- keeps across restarts, one row per (guild, kind). kind 'low': a citizen guild's ties to the rest of its alliance have
-- stayed under ALLIANCE_LEAVE_TIE since at_ms (it leaves after ALLIANCE_LEAVE_AFTER_DAYS). kind 'asked': a citizen
-- alliance last invited this player guild at at_ms (asked again after ALLIANCE_REASK_DAYS). Epoch milliseconds, not a
-- TIMESTAMP, so no session time zone touches them. No foreign key: a disbanded guild's rows are passed over.
-- Ships behind population.ALLIANCES_ENABLED. Rollback: 039-living-alliance-mark-down.sql.

CREATE TABLE IF NOT EXISTS living_alliance_mark (
    guild_id INT         NOT NULL,
    kind     VARCHAR(8)  NOT NULL,
    at_ms    BIGINT      NOT NULL,
    PRIMARY KEY (guild_id, kind)
);

-- The town paper (docs/WORLD_SYSTEMS_PLAN.md N1): one composed issue a week. week_of is the issue week's Monday, days
-- since 1970-01-01 in the server's own time zone (as pvp_tournament.week_of); issued_at is UTC like every living time;
-- body is the issue as JSON (sections, each with its stories' headline, body and gist, written once at compose time so
-- a later read shows the same words). The unique week_of makes a second compose of the same week (a restart in the
-- issue hour, a GM's !paper issue) a no-op. The stories filed during the week are paper.item rows of living_event_log.
-- Rollback: 055-living-paper-down.sql.
CREATE TABLE IF NOT EXISTS living_paper (
    id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
    week_of     INT          NOT NULL,
    issued_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    body        MEDIUMTEXT   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY living_paper_week (week_of)
);

-- The retention sweep (decision D1) deletes old living_event_log rows by age in small batches.
CREATE INDEX idx_living_event_at ON living_event_log (at);

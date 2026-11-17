-- The coliseum board's weekly climb (docs/PVP_PLAN.md V4.4): week_of is the rating's week (UTC, Monday start, counted
-- in weeks since the epoch) and week_rating its rating when that week's first match began, so "this week" is
-- rating - week_rating for rows whose week_of is the current week. Rollback: 052-pvp-rating-week-down.sql.
ALTER TABLE pvp_rating
    ADD COLUMN week_of     INT    NOT NULL DEFAULT 0,
    ADD COLUMN week_rating DOUBLE NOT NULL DEFAULT 0;

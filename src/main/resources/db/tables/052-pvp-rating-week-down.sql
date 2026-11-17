-- Rollback for 052-pvp-rating-week.sql.
ALTER TABLE pvp_rating
    DROP COLUMN week_rating,
    DROP COLUMN week_of;

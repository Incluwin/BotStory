-- Rollback for 028-solomapling-bots.sql.
ALTER TABLE characters
    DROP INDEX idx_characters_bot_kind,
    DROP COLUMN bot_kind;

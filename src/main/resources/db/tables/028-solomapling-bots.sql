-- SoloMapling social-bot integration — bot_kind discriminator.
-- Distinguishes SoloMapling ambient/social bots ('social') from the existing
-- bot-economy FM merchant bots ('economy'), so the SoloMapling isBot() check
-- can never misclassify real players or economy bots (it previously keyed off
-- characters.id > 20000, which collides on a populated server).
-- Ships behind server.ENABLE_SOLO_MAPLING=false; no runtime effect until flipped.
-- Fully reversible — see 028-solomapling-bots-down.sql.

ALTER TABLE characters
    ADD COLUMN bot_kind ENUM('social','economy') NULL DEFAULT NULL,
    ADD INDEX idx_characters_bot_kind (bot_flag, bot_kind);

-- Backfill: every existing bot_flag=1 row is an economy bot (the only bot kind
-- that existed before SoloMapling). Tag them so they are never treated as social.
UPDATE characters SET bot_kind = 'economy' WHERE bot_flag = 1 AND bot_kind IS NULL;

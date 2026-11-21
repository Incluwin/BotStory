-- Luxury rolls (docs/ECONOMY_PLAN.md section 9.6): the UTC day each citizen last rolled for a visit to each kind of
-- stylist (HAIR, FACE, SKIN; CitizenSalons), kept across restarts so a restart does not roll the same day again. One
-- row per citizen and look; each roll overwrites its day.
CREATE TABLE IF NOT EXISTS living_luxury_roll (
    character_id    INT          NOT NULL,
    look            VARCHAR(8)   NOT NULL,
    day             INT          NOT NULL,
    PRIMARY KEY (character_id, look)
);

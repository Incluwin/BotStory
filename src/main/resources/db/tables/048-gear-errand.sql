-- Buy, don't receive, gear (docs/ECONOMY_PLAN.md E3): a citizen's open errand to buy a piece for one equip slot at a
-- stall, kept across restarts so its deadline holds (GearErrands). One row per citizen and slot; the row goes when
-- the citizen buys a fitting piece or the deadline passes and the piece is handed over. Times are UTC (SqlTime).
CREATE TABLE IF NOT EXISTS living_gear_errand (
    character_id    INT          NOT NULL,
    slot            VARCHAR(16)  NOT NULL,
    category        VARCHAR(16)  NOT NULL,
    worn_level      INT          NOT NULL,
    weapon_type     INT          NOT NULL DEFAULT 0,
    until_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (character_id, slot)
);

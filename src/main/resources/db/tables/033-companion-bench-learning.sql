-- Living bench learning (docs/BOT_MANAGEMENT_PLAN.md P5, "BenchYieldModel / BenchDutyChooser", changeset
-- 42-companion-bench-learning): what live bench slices measured, per hour, for one job branch (BEGINNER, WARRIOR,
-- MAGICIAN, BOWMAN, THIEF, PIRATE or OTHER) and level band (level / 10) at one camp. samples counts full live slices
-- (a slice cut short, by a death say, counts for the share of BENCH_LIVE_SLICE_MS it ran), so each rate is the
-- measured total over the measured hours. The ledger credits these rates shrunk toward its priors, and the duty and
-- camp bandit reads its arm statistics from the same rows. No foreign key, as living_roster. Ships behind
-- living_world.ENABLED like 029. Rollback: 033-companion-bench-learning-down.sql.

CREATE TABLE IF NOT EXISTS companion_yield (
    job_branch      VARCHAR(16)  NOT NULL,
    level_band      TINYINT      NOT NULL,
    camp_map_id     INT          NOT NULL,
    samples         DOUBLE       NOT NULL DEFAULT 0,
    exp_per_hour    DOUBLE       NOT NULL DEFAULT 0,
    meso_per_hour   DOUBLE       NOT NULL DEFAULT 0,
    items_per_hour  DOUBLE       NOT NULL DEFAULT 0,
    deaths_per_hour DOUBLE       NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (job_branch, level_band, camp_map_id)
);

-- "Your call" as a standing intent: while it is on (1) the companion picks its own duty and camp again before each
-- bench slice (BenchDutyDispatcher), and any duty or camp its owner gives it turns it off. Off (0) by default.
ALTER TABLE living_roster
    ADD COLUMN your_call TINYINT(1) NOT NULL DEFAULT 0;

-- What a live bench stall holds of its haul: the units moved from living_haul_item into its companion's bag or shop
-- and not yet sold or handed back (BenchStallForm). A crash mid-stall leaves them here, and the companion's next
-- load moves them back to the haul, so no unit is lost. No foreign key, as living_roster.
CREATE TABLE IF NOT EXISTS living_stall_stock (
    character_id    INT          NOT NULL,
    persona_id      VARCHAR(64)  NOT NULL,
    item_id         INT          NOT NULL,
    quantity        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, persona_id, item_id)
);

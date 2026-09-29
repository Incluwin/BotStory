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

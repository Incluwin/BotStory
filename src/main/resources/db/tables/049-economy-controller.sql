-- The economy controller (docs/ECONOMY_PLAN.md E4): one row per daily close, the organic growth and basket change it
-- read (NULL when unknown) and the citizen levers it set; applied = 0 for a shadow setting. The last applied row is
-- what a restart starts from (EconomyController).
CREATE TABLE IF NOT EXISTS economy_controller_daily (
    day             DATE        NOT NULL,
    growth          DOUBLE      NULL,
    cpi_change      DOUBLE      NULL,
    pickup_share    DOUBLE      NOT NULL,
    luxury_rate     DOUBLE      NOT NULL,
    budget_scale    DOUBLE      NOT NULL,
    applied         TINYINT(1)  NOT NULL,
    decided_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (day)
);

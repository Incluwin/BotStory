-- The one-time correction to the scroll formula's new seeds (docs/ECONOMY_PLAN.md section 10.15,
-- population.PRICE_SEED_CORRECTION_ENABLED): each row it changed, before and after, kept for review and an undo. The row
-- of item_id 0 marks that it ran (also when it changed nothing); while any row is here it never runs again. Rows with
-- samples 0 had no sale.
CREATE TABLE IF NOT EXISTS living_price_correction (
    item_id     INT         NOT NULL,
    seed        BIGINT      NOT NULL,
    old_price   BIGINT      NOT NULL,
    new_price   BIGINT      NOT NULL,
    samples     INT         NOT NULL,
    reseeded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id)
);

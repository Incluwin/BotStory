-- The price index's one-time reseed (docs/ECONOMY_PLAN.md section 10.9, population.PRICE_INDEX_RESEED_ENABLED): each
-- row it changed, before and after, kept for review and an undo. The row of item_id 0 marks that it ran (also when it
-- changed nothing); while any row is here it never runs again.
CREATE TABLE IF NOT EXISTS living_price_reseed (
    item_id     INT         NOT NULL,
    seed        BIGINT      NOT NULL,
    old_price   BIGINT      NOT NULL,
    new_price   BIGINT      NOT NULL,
    samples     INT         NOT NULL,
    reseeded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id)
);

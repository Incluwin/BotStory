-- Professions and commissions (docs/WORLD_SYSTEMS_PLAN.md N8, changeset 69-living-commission). living_commission: a
-- player's order with a citizen crafter. recipe: a recipes.yaml id, or "scroll" (a scroller uses the held scroll on the
-- held piece). fee: meso held from the order until the crafter is paid (DELIVERED) or it goes back (CANCELLED,
-- REFUNDED). state: HELD (waiting its turn: the customer may cancel), WORKING, DELIVERED, CANCELLED, REFUNDED.
-- work_ms / worked_ms: how much of the crafter's online time it takes and has had, so a crafter logging off mid-order
-- goes on where it stopped. due_at: what the customer was told. Times are UTC (SqlTime).
--
-- The held materials are inventoryitems rows of type 15 (ItemFactory.COMMISSION) keyed by 1,500,000,000 + the order's id
-- (clear of character ids, so deleting a character never takes an order's items), written in the order's own commit.
-- poster_character_id: a player's buy order (kind BUY, citizen_id 0); NULL for every citizen's request. The meso of its
-- units still to come (offer x (quantity - delivered)) is held while it is OPEN. No foreign keys, as living_need. Ships
-- behind population.COMMISSIONS_ENABLED and BUY_ORDERS_ENABLED. Rollback: 060-living-commission-down.sql.

CREATE TABLE IF NOT EXISTS living_commission (
    id              INT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_id     INT          NOT NULL,
    crafter_id      INT          NOT NULL,
    recipe          VARCHAR(48)  NOT NULL,
    quantity        INT          NOT NULL DEFAULT 1,
    fee             BIGINT       NOT NULL DEFAULT 0,
    state           VARCHAR(10)  NOT NULL DEFAULT 'HELD',
    map_id          INT          NOT NULL DEFAULT 0,
    work_ms         BIGINT       NOT NULL DEFAULT 0,
    worked_ms       BIGINT       NOT NULL DEFAULT 0,
    ordered_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_at          TIMESTAMP    NULL,
    closed_at       TIMESTAMP    NULL,
    PRIMARY KEY (id),
    INDEX idx_living_commission_state (state)
);

ALTER TABLE living_need ADD COLUMN poster_character_id INT NULL;

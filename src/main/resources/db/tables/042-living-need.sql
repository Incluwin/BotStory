-- The needs board (docs/BOT_CONCEPT_PLAN.md F1, changeset 51-living-need): what a citizen asks for, derived from what it
-- really lacks (NeedLedger): SUPPLY (potions short of its Balrog standard), SCROLL (a basket scroll for a piece it wears),
-- GEAR (an upgrade it wants instead of being handed it, decision D4) and PARTY (its forming party is short).
--
-- item_id / quantity / delivered: what it wants, how many, how many it already got (0 item for PARTY). offer: meso a
-- unit it pays. detail: what for (the boss, the slot, the role). map_id: where it asked. state: OPEN, ESCROW (handed in
-- at a board while it was away: the items wait for its next load), MET, BOUGHT (it got them elsewhere), EXPIRED, GONE.
-- claimed_by / claimed_until: a player's 60-minute hold. fulfilled_by: the last player who handed something in.
-- thanked: 0 while a board hand-in still waits for its whispered thanks. Times are UTC (SqlTime).
--
-- The escrowed items themselves are inventoryitems rows of type 14 (ItemFactory.NEED_ESCROW) keyed by the citizen's
-- character id, so a deleted character takes its escrow with it. No foreign key, as living_roster. Ships behind
-- population.NEEDS_ENABLED. Rollback: 042-living-need-down.sql.

CREATE TABLE IF NOT EXISTS living_need (
    id              INT UNSIGNED NOT NULL AUTO_INCREMENT,
    citizen_id      INT          NOT NULL,
    kind            VARCHAR(8)   NOT NULL,
    item_id         INT          NOT NULL DEFAULT 0,
    quantity        INT          NOT NULL DEFAULT 1,
    delivered       INT          NOT NULL DEFAULT 0,
    offer           BIGINT       NOT NULL DEFAULT 0,
    detail          VARCHAR(48)  NOT NULL DEFAULT '',
    map_id          INT          NOT NULL DEFAULT 0,
    state           VARCHAR(8)   NOT NULL DEFAULT 'OPEN',
    claimed_by      INT          NULL,
    claimed_until   TIMESTAMP    NULL,
    fulfilled_by    INT          NULL,
    thanked         TINYINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deadline_at     TIMESTAMP    NULL,
    closed_at       TIMESTAMP    NULL,
    PRIMARY KEY (id),
    INDEX idx_living_need_state (state, deadline_at),
    INDEX idx_living_need_citizen (citizen_id, kind)
);

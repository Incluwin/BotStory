-- The economy ledger (docs/ECONOMY_PLAN.md E0, changeset 55-economy-ledger). Days are UTC.
--
-- economy_flow_daily: meso in and out a day by account (server.economy.ledger.MesoAccount: MOB_DROP, NPC_SALE, TAX,
-- TRADE, ...) and holder (HUMAN, GM, CITIZEN, RETIRED, BOT). Summed in memory (MesoFlows) and flushed every minute.
-- economy_supply_daily: meso held at each day's close by pool (PURSE, STORAGE, FREDRICK, DUEY, HAUL) and holder.
-- economy_cpi_daily: the day's price of each basket item (economy.CPI_BASKET) and where it came from (SALES, LISTINGS,
-- INDEX). economy_close_daily: the day's closing supply, the net of its external accounts, the residual the two leave
-- (closing supply - previous closing supply - external net: what no account claimed) and the basket index.
-- economy_purse_reset: each citizen purse before and after the one-time reset (decision D1), kept for an undo.
-- Rollback: 046-economy-ledger-down.sql.

CREATE TABLE IF NOT EXISTS economy_flow_daily (
    day       DATE        NOT NULL,
    account   VARCHAR(24) NOT NULL,
    holder    VARCHAR(12) NOT NULL,
    meso_in   BIGINT      NOT NULL DEFAULT 0,
    meso_out  BIGINT      NOT NULL DEFAULT 0,
    events    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (day, account, holder)
);

CREATE TABLE IF NOT EXISTS economy_supply_daily (
    day       DATE        NOT NULL,
    pool      VARCHAR(12) NOT NULL,
    holder    VARCHAR(12) NOT NULL,
    meso      BIGINT      NOT NULL,
    PRIMARY KEY (day, pool, holder)
);

CREATE TABLE IF NOT EXISTS economy_cpi_daily (
    day       DATE        NOT NULL,
    item_id   INT         NOT NULL,
    price     BIGINT      NOT NULL,
    source    VARCHAR(8)  NOT NULL,
    samples   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (day, item_id)
);

CREATE TABLE IF NOT EXISTS economy_close_daily (
    day           DATE      NOT NULL,
    supply        BIGINT    NOT NULL,
    external_net  BIGINT    NOT NULL,
    residual      BIGINT    NULL,
    cpi           DOUBLE    NULL,
    closed_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (day)
);

CREATE TABLE IF NOT EXISTS economy_purse_reset (
    character_id  INT       NOT NULL,
    level         INT       NOT NULL,
    old_meso      INT       NOT NULL,
    new_meso      INT       NOT NULL,
    reset_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id)
);

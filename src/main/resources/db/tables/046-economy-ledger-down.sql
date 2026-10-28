-- Rollback for 046-economy-ledger.sql: the ledger's tables go; nothing else reads them. Restore citizen purses from
-- economy_purse_reset first if the reset should be undone (UPDATE characters c JOIN economy_purse_reset r ON
-- r.character_id = c.id SET c.meso = r.old_meso), with no citizen loaded.
DROP TABLE IF EXISTS economy_purse_reset;
DROP TABLE IF EXISTS economy_close_daily;
DROP TABLE IF EXISTS economy_cpi_daily;
DROP TABLE IF EXISTS economy_supply_daily;
DROP TABLE IF EXISTS economy_flow_daily;

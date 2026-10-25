-- Rollback for 038-character-storage-bag.sql: the Auto toggles are forgotten (all off). The bag items stay in
-- inventoryitems (types 10-13) and come back with the table.
DROP TABLE IF EXISTS character_storage_bag;

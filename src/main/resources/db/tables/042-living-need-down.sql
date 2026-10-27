-- Rollback for 042-living-need.sql: the needs board forgets every request, and the items still waiting in escrow
-- (inventoryitems type 14, ItemFactory.NEED_ESCROW) go with their equip rows first.
DELETE inventoryequipment FROM inventoryequipment JOIN inventoryitems USING (inventoryitemid) WHERE inventoryitems.type = 14;
DELETE FROM inventoryitems WHERE type = 14;
DROP TABLE IF EXISTS living_need;

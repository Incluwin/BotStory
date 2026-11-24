-- Rollback for 060-living-commission.sql. Refund open orders first (turn COMMISSIONS_ENABLED and BUY_ORDERS_ENABLED off
-- after cancelling them): the items still held (inventoryitems type 15) go with their equip rows.
DELETE inventoryequipment FROM inventoryequipment JOIN inventoryitems USING (inventoryitemid) WHERE inventoryitems.type = 15;
DELETE FROM inventoryitems WHERE type = 15;
DELETE FROM living_need WHERE poster_character_id IS NOT NULL;
ALTER TABLE living_need DROP COLUMN poster_character_id;
DROP TABLE IF EXISTS living_commission;

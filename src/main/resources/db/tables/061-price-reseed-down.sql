-- Rollback for 061-price-reseed.sql. Dropping the table drops the old prices: with the server stopped, read them back
-- first if the reseed should be undone (UPDATE living_price_index p JOIN living_price_reseed r ON r.item_id = p.item_id
-- SET p.price = r.old_price), and leave PRICE_INDEX_RESEED_ENABLED off or it runs again.
DROP TABLE IF EXISTS living_price_reseed;

-- Rollback for 063-price-correction.sql. Dropping the table drops the old prices: with the server stopped, read them back
-- first if the correction should be undone (UPDATE living_price_index p JOIN living_price_correction r ON r.item_id =
-- p.item_id SET p.price = r.old_price WHERE r.samples > 0; a row without a sale takes its seed again at the next start),
-- and leave PRICE_SEED_CORRECTION_ENABLED off or it runs again.
DROP TABLE IF EXISTS living_price_correction;

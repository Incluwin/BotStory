-- Adds Perfect Pitch (4310000) as a low-chance global drop on existing deployments
-- whose 151-global-drop-data.sql already ran before Perfect Pitch was added there.
-- Idempotent: inserts only if the row is missing, so fresh installs that already got
-- the entry via 151 won't double-insert.

INSERT INTO drop_data_global (continent, itemid, minimum_quantity, maximum_quantity, questid, chance, comments)
SELECT * FROM (SELECT -1 AS continent, 4310000 AS itemid, 1 AS minimum_quantity, 1 AS maximum_quantity,
                      0 AS questid, 2000 AS chance, 'Perfect Pitch' AS comments) AS tmp
WHERE NOT EXISTS (
    SELECT 1 FROM drop_data_global WHERE itemid = 4310000
);

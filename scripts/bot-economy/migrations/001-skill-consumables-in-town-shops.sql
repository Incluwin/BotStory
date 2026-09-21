-- Summoning Rock (4006000) and Magic Rock (4006001) are consumed by Shadow Partner and Mystic Door
-- (and other itemCon skills) but v83 sells them nowhere Agents can reach. Stock the five Victoria
-- town potion shops the Agent supply planner already routes to (shopid == npcid there).
-- Idempotent: re-running inserts nothing new.
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
SELECT s.shopid, r.itemid, r.price, 0, 200 + r.slot
FROM (SELECT 1011100 AS shopid UNION ALL SELECT 1021100 UNION ALL SELECT 1031100
      UNION ALL SELECT 1051002 UNION ALL SELECT 1091002) s
CROSS JOIN (SELECT 4006000 AS itemid, 800 AS price, 0 AS slot
            UNION ALL SELECT 4006001, 800, 1) r
WHERE NOT EXISTS (SELECT 1 FROM shopitems i WHERE i.shopid = s.shopid AND i.itemid = r.itemid);

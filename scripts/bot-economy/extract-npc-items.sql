-- BOT_ECONOMY.md § Price classes — populate bot_npc_sold_items from Cosmic's
-- shops + shopitems tables. Run once at seed time and whenever the NPC
-- catalog changes.
--
-- Equipment is flagged so drop-sim can still see it as npc_gear and exclude
-- it; non-equip items become the stock source for the NPCVendor persona.

INSERT INTO bot_npc_sold_items (item_id, cheapest_npc_buy_price, is_equipment, extracted_at)
SELECT si.itemid AS item_id,
       MIN(si.price) AS cheapest_npc_buy_price,
       CASE WHEN si.itemid BETWEEN 1000000 AND 1999999 THEN 1 ELSE 0 END AS is_equipment,
       NOW()
FROM shopitems si
WHERE si.price > 0
GROUP BY si.itemid
ON DUPLICATE KEY UPDATE
    cheapest_npc_buy_price = VALUES(cheapest_npc_buy_price),
    is_equipment = VALUES(is_equipment),
    extracted_at = NOW();

-- Sanity check — see the NPC-sold pool breakdown:
-- SELECT is_equipment, COUNT(*) FROM bot_npc_sold_items GROUP BY is_equipment;

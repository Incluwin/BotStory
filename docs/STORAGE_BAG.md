# Storage Bags

Four 200-slot bags per character, used through the Kaentake client's `storage_bag` window (a BAG button on the item
inventory). Ported from the community "Storage Bag" release (its per-character "Repair" version); the analysis is in
`MapleServer/new client stuff/_analysis/inventory-shop.md` section 1.

| Bag | Holds |
|---|---|
| Ore | the Maker materials: ores, plates, jewels, crystals, magic stones, monster crystals |
| Scroll | equip scrolls (204xxxx) and the White Scroll |
| Chair | chairs (301xxxx) |
| Mount | taming mobs (1902xxx) and saddles (1912xxx) |

Cash items, one-of-a-kind items and items that expire never go in a bag: the cash shop, the one-of-a-kind rule and
the expiry sweep only look at the inventory. Items in a bag do not count for quests or the Maker.

## In the window

- Drag an item from the inventory onto the bag (or onto one of its slots); the whole stack moves.
- Drag an item out of the bag, or right-click it, to take the whole stack back; it merges into the inventory like a
  pickup. Taking it back needs room for the whole stack.
- Drag inside the bag to move, merge or swap slots. Organize merges every stack and packs the bag by item id.
- Auto: while it is on, a pickup that bag takes goes straight into it (the inventory is left alone; a full bag lets
  the pickup through to the inventory). Not on PQ maps that only let you loot your own drops.

## Server

- `server.bag.StorageBagKind` (the four filters), `StorageBag` (one bag: fixed slots, all-or-nothing changes, stacks
  merge only with the same item, owner, flag and expiration), `StorageBags` (a character's bags and Auto toggles, the
  transfers, the pickup), `StorageBagRequests` (one window request), `net.server.channel.handlers.StorageBagHandler`.
- Opcodes: client → server 0x119 BAG_WINDOW, server → client 0x17E BAG_WINDOW (`docs/CUSTOM_OPCODES.md`), in the
  ECONOMY rate family.
- Switch: `mods.SWITCHES.storage_bag` (on). Clients: `mods.FEATURES.storage_bag` (allow); `deny` makes the client's
  window inert. With the switch off the window's requests are ignored and Auto stops; bags already loaded keep saving.
- Bots never have bags (a `BotClient` character loads none).

## Persistence

- Bag items are `inventoryitems` rows of types 10-13 (`ItemFactory.ORE_BAG` .. `MOUNT_BAG`), keyed by character,
  `position` = the bag slot (0-199). The Auto toggles are `character_storage_bag` (changeset 47, file 038).
- The bags load on the character's first window request, or at login when an Auto toggle is on. Until then they are
  neither read nor saved.
- They save in the character save's INVENTORY section, in the same transaction as the inventory. A transfer runs under
  the character's monitor, which the save also holds while it copies the inventory and the bags, so after any save an
  item is in exactly one of the two. A withdraw checks under the inventory's lock that the whole stack fits the way
  the pickup merges (same flag and owner), so it never lands half.
- A bag never drops a row: rows past 200 (only a hand-edited database has them) stay in an overflow that is saved back.

## Wire

Client → server: `byte action, byte bag`, then per action:

| Action | Body |
|---|---|
| 0 OPEN | (none) |
| 1 WITHDRAW | `short bag slot, short inventory slot` (the second is not used) |
| 2 DEPOSIT | `short inventory type, short inventory slot, short bag slot` (-1: anywhere) |
| 3 ORGANIZE | (none) |
| 4 SET_AUTO | `byte on` |
| 5 MOVE | `short from, short to` |
| 6 CLOSE | (none; no answer) |

Server → client, after every request but CLOSE, and after an Auto pickup into the bag the open window shows:
`byte 1, byte bag, short n, n x (short slot, item info), n x short quantity, byte auto`.

## Tests

`StorageBagTest` (the bag on its own) runs in the suite. `StorageBagsMySqlIntegrationTest` (the transfers on a real
character's inventories, and a save and load) needs the database: `-Dcosmic.test.mysql=true`, with `DB_USER` and
`DB_PASS` set. It writes only bag rows under a character id no character has, and removes them; before changeset 47
has run it keeps the Auto toggles in a temporary table.

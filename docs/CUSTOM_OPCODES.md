# Custom opcodes

BotStory's own packets between the server and the Kaentake client. The client reads every server→client opcode in
this table through its one `CClientSocket::ProcessPacket` hook (Kaentake `src/serverpackets.cpp`, before the game's
own handler), so these never reach stock v83 code. Stock v83 opcodes end at `VEGA_SCROLL` (0x166) server→client and
`USE_HAMMER` (0x104) client→server.

Take the next free number here **before** writing a port, so two branches never claim the same one. Community
releases ship with their own numbers (0x3713 and up, 0x995, 0x168…). Renumber them into this table. Never keep theirs:
the original Storage Bag's 0x113/0x176 are our OBSERVER_CHARACTERS.

New client→server opcodes also need a rate family in `net/packet/PacketFamily.java` (ECONOMY for anything that moves
items or mesos).

## In use

| Client → server (`RecvOpcode`) | Server → client (`SendOpcode`) | Feature |
|---|---|---|
| 0x112 BOT_EQUIP | 0x175 BOT_EQUIP | Android/bot equip window |
| 0x113 OBSERVER_CHARACTERS | 0x176 OBSERVER_CHARACTERS | observer |
| 0x114 OBSERVER_NAV_GRAPH | 0x177 OBSERVER_NAV_GRAPH | observer |
| 0x115 OBSERVER_INTEREST | 0x178 OBSERVER_INTEREST | observer |
| 0x116 OBSERVER_ACTION | 0x179 OBSERVER_ACTION_RESULT | observer |
| 0x117 CREW_WINDOW | 0x17A CREW_WINDOW | native Crew window (`docs/BOT_MANAGEMENT_P10_PLAN.md`) |
| 0x118 CLIENT_FEATURES | 0x17B CLIENT_FEATURES | login feature handshake (`docs/CLIENT_FEATURE_HANDSHAKE.md`) |
| 0x119 BAG_WINDOW | 0x17E BAG_WINDOW | Storage Bag window (`docs/STORAGE_BAG.md`) |
| — | 0x17C WEATHER_SYNC | day and night + the region's sky (`server.weather.WeatherPackets`, `docs/WEATHER.md`) |
| — | 0x17D LAMP_PREVIEW | the `!lamp` placement preview (`server.lamp.LampPackets`) |
| — | 0x17F SHOP_BUYBACK | Buy Back: which list the NPC shop shows (`server.ShopBuyback`) |
| — | 0x186 SET_ITEM_SYNC | set item tooltips, pushed on equip change (`server.set.SetItemSync`) |
| — | 0x187 DEATH_COUNT | boss run death count (`server.expeditions.ExpeditionDeathCount`) |

## Reserved for the community mod ports (2026-10-25)

From the intake of the "new client stuff" releases (Storage Bag, Buy Back, cash shop window, DamageRank, damage
skin, clickable world map, daily check-in, weather, set equip, death count). A row moves to *In use* when its port
merges.

| Client → server | Server → client | Port |
|---|---|---|
| 0x11A CASHSHOP_WINDOW | 0x180 CASHSHOP_WINDOW_SYNC | in-game cash shop window (theirs: 0x3730 / 0x3731) |
| 0x11B DAMAGE_RANK | 0x181 DAMAGE_RANK | DamageRank meter (theirs: 0x3713 / 0x3714) |
| 0x11C DAMAGE_SKIN_APPLY | 0x182 DAMAGE_SKIN_CATALOG | damage skin (theirs: 0x110 / 0x170) |
| 0x11D DAMAGE_SKIN_PURCHASE | 0x183 DAMAGE_SKIN_INVENTORY | damage skin (theirs: 0x111 / 0x171) |
| — | 0x184 DAMAGE_SKIN_RESULT | damage skin (theirs: 0x172) |
| — | 0x185 DAMAGE_SKIN_BROADCAST | damage skin (theirs: 0x173) |
| 0x11E WORLD_MAP_TELEPORT | — | clickable world map (theirs: 0x995) |
| 0x11F DAILY_CHECKIN | 0x188 DAILY_CHECKIN | daily check-in (theirs: 0x371A) |

The next free numbers after these are client → server 0x120 and server → client 0x189.

# Guild PQ (Sharenian) mechanics brief for bot party members

Read from this server on feat/companion-manager (2026-10-25) for the APQ/GPQ companion plan (`docs/APQ_GPQ_COMPANION_PLAN.md`). Map data from `wz/Map.wz/Map/Map9/9900*.img.xml`, reactor state machines from `wz/Reactor.wz`, mob stats from `wz/Mob.wz`, names from `wz/String.wz`.

Notation: a reactor's "rect" is the absolute drop or hit box, computed as position + `lt`/`rb`. `Rectangle.contains` uses x in [x1,x2) and y in [y1,y2). Every item-drop rect below has been checked to contain a foothold. Item landing is the foothold under the dropper.

---

## 0. Flow at a glance

```
101030104 Shuang (register / join)
  -> 990000000 Excavation Site (3-min strategy; portal join00 opens at T+3min)
  -> 990000100 Valley of the Guard (Protector Rocks)  -> 990000200 Entrance to the Remains (2 more rocks)
  -> 990000300 Stage 1 Gatekeeper statues  -> 990000301 ->
  -> 990000400 Stage 2 Hall of the Knight (+410 spears, 420 key mob, 430/431 rusty key + 4th spear, 440 altars) -> 990000401 ->
  -> 990000500 Stage 3 Fountain of the Wiseman (+501 Banquet, 502 Wine Cellar)
  -> 990000600 Stage 4 Underground Waterway (+610/611, 620, 630/631, 640/641 [Lv<=30]) -> 990000700 Sharen III's Grave
  -> 990000800 King's Corridor (kinggate = drop a Protector Rock) -> 990000900 Ergoth's Throne (boss)
  -> clear: 990001000 bonus (30 s) -> 990001100 Returning Path (Nuris) -> 101030104
```

GP awarded to the leader's/trigger's guild: Stage 1 = 15, Stage 2 = 20, Stage 3 = 25, Stage 4 = 30, boss = 100..1000. The event gives no EXP and no item rewards (section 12).

---

## 1. Global rules (all stages)

### 1.1 Event parameters (`scripts/event/GuildQuest.js`)
- minPlayers 6, maxPlayers 30, levels 1-255 (`GuildQuest.js:27-28`). **maxPlayers is never enforced.** It only appears in the requirement text (`:51-74`), and neither `registerPlayer` nor Shuang checks it.
- waitTime 3 min (strategy), eventTime 90 min, bonusTime 30 s (`:37-39`). `maxLobbies = 1`, so there is one GPQ per channel at a time (`:41`).
- Exclusive items are removed from a player when they are unregistered: 1032033, 4001024-4001035, 4001037 (`:76-79`; `EventInstanceManager.java:447-448` calls `gridRemove` and `dropExclusiveItems`). 4001036 is not in the list.
- Clear rewards are set to an empty list (`:81-91`), so `giveEventReward` is a no-op that returns true (`EventInstanceManager.java:1098-1124`).

### 1.2 Instance leader and NPC gating
- The instance leader is the character that queued the guild at Shuang. `addGuildToQueue(guildId, leaderId)` (`npc/9040000.js:123`) eventually calls `startInstance(leader)`, which calls `eim.setLeader(leader)` (`EventManager.java:448-495`).
- **Every stage NPC accepts only the instance leader** (`cm.isEventLeader()`): 9040009 stage 1 (`npc/9040009.js:61`), 9040006 stage 3 (`npc/9040006.js:27`), 9040003 Sharen III (`npc/9040003.js:65`) and 9040010 Rubian turn-in (`npc/9040010.js:25`). With a human leader, all four NPC actions are the human's. Bots cannot do them unless a bot is the registrant.
- `changedLeader` is a no-op (`GuildQuest.js:254`), so the leader never transfers.

### 1.3 Team-size, leave and death rules (critical for bots)
- **Strategy-phase end** (`scheduledTimeout`, `GuildQuest.js:201-222`): canJoin becomes 0. If `checkEventTeamLacking(true, 6)` is true, the instance ends. That check is true when the leader is not registered or fewer than 6 players are registered (`EventInstanceManager.java:1258-1267`). Otherwise the 90-min timer starts.
- **Any departure after canJoin = 0** can end the run: changing to a map outside 990000000-990001101 (`GuildQuest.js:236-245`), disconnecting (`:279-286`), or reviving while canRevive = 0 (`:264-277`). The check is `isEventTeamLackingNow(true, 6, quitter)`, which is true when **the quitter is the leader** or **`playerCount <= 6`**. The count still includes the quitter (`EventInstanceManager.java:1278-1287`). In practice:
  - With N registered players you can lose N - 7 players and survive. **With exactly 6, any single departure or death-revive ends the run. With 7, you can survive one.**
  - **The leader leaving or reviving before canRevive = 1 ends the run.**
- **Death before the boss gate is opened** (canRevive = 0): pressing OK on the death dialog calls `revivePlayer`, which runs `player.respawn(eim, 990001100)`. That unregisters the player, strips the exclusive items, sends them out, and may end the run (`GuildQuest.js:264-273`; `Character.java:8837-8853`). A dead player who has not pressed OK is still registered.
- **After the kinggate opens** (canRevive = 1, set by `reactor/9208009.js:26`), revive returns true. The standard path is `chr.respawn(map.getReturnMapId())` (`ChangeMapHandler.java:101-106`). In-event that goes to the instance's 990000200, because `getReturnMapId` = 990000200 on 990000300-990000900 and `changeMap(int)` routes to the instance map (`Character.java:1707-1718`). The player stays registered, with 50 HP.
- Wheel of Fortune in the cash inventory revives in place and skips `revivePlayer` (`ChangeMapHandler.java:92-99`).
- **Boss wipe:** in 990000900, if `countAlivePlayers()==0 && countMonsters()>0`, the run ends (`GuildQuest.js:256-262`).
- **Bot death today:** `AgentDeathTickCoordinator` (`server/agents/capabilities/combat/AgentDeathTickCoordinator.java`) chains PQ-specific departure gates: LPQ, EPQ, LMPQ, OPQ, PPQ, RJPQ, boss. Anything else falls through to `AgentRespawnCoordinator.respawnAtNearestTown`, which leaves the GPQ map range and therefore unregisters the bot or ends the run. **GPQ needs its own departure gate.** Hold the bot dead until canRevive = 1, then revive into the instance's 990000200, as `AgentBossDeathDepartureGate.respawnOrHold` does with `event.revivePlayer` plus `respawnInto`.

### 1.4 "Bad air" instant death (decHP / protectItem)
- Map info `decHP` and `protectItem` are loaded in `MapFactory.java:326-327`. Entering a map with decHP > 0 registers the player with the world HP-decrease task (`MapleMap.java:2579-2583`).
- The world task runs every `MAP_DAMAGE_OVERTIME_INTERVAL` = 2500 ms and fires on every `MAP_DAMAGE_OVERTIME_COUNT` = 2nd tick (`config.yaml:337-338`; `World.java:1915-1930`). That is one hit every 5 s; the first hit lands 2.5-5 s after entering.
- `Character.doHurtHp` (`Character.java:3340-3349`) subtracts decHP unless an **equipped** item has id = protectItem (1032033 Protector Rock). The El Nath and Aqua `buffMapProtection` paths never apply here. Bots (BotClient) take the hit through `addMPHPAndTriggerAutopot`.
- decHP per map:

| decHP | maps |
|---|---|
| none | 990000000, 990000100, 990000200, 990001100 |
| 3000 | 990000501 (Banquet) only (`990000501.img.xml:3182`) |
| 30000 (instant death) | 990000300 (`990000300.img.xml:2292-2293`), 301, 400, 401, 410, 420, 430, 431, 440, 500, 502, 600, 610, 611, 620, 630, 631, 640, 641, 700, 800, 900, 1000, 1101 |

- Protector Rock 1032033 (`wz/Character.wz/Accessory/01032033.img.xml`):
  - Earring slot (`islot=Ae`), **reqLevel 10**, MDD +10.
  - **`only=1` (one-of-a-kind).** You cannot pick up a second while holding or wearing one (`InventoryManipulator.java:202-206, 311-316`). It is not overridden: `allow_multiple_one_of_a_kind_items: false`, allowlist empty (`config.yaml:27-28`).
  - So nobody can carry a spare.

### 1.5 Field limits (`FieldLimit.java:27-52`)
- 270972 = SUMMON 0x4, DOOR 0x8, CANNOTMIGRATE 0x10, 0x20, CANNOTVIPROCK 0x40, CANNOTUSEMOUNTS 0x200, 0x2000, 0x40000. Potions are allowed, drops are allowed, jump-down is allowed. Every GPQ map has this.
- **270974 = the above + MOVEMENTSKILLS 0x2.** Teleport, Flash Jump and similar are disabled in **990000410 (spear tower), 990000501, 990000502, 990000611, 990000631, 990000641**. Agent movement already reads this bit (`AgentMovementProfile.java:45`, `AgentMovementSkillPolicy.java:168`).

### 1.6 Item, reactor and drop rules
- **Item-drop reactors (type 100)** fire only when one dropped stack has the exact item id **and the exact quantity** in the reactor rect (`MapleMap.java:2393-2408`, `2411-2445`). Activation is scheduled **5000 ms later**. At that point the item is consumed and the reactor is hit with the dropper's client (`MapleMap.java:3934-3975`).
  - Mob drops also trigger it (`MapleMap.java:1178-1198`).
  - After a state change, `searchItemReactors` rescans the ground for the next state's item, so drops can be chained (`Reactor.java:192-202, 300-307`).
- **Hit reactors (type 0):** a player attack sends `ReactorHitHandler`, which calls `hitReactor(wHit=true, ...)` (`ReactorHitHandler.java:33-46`). Agents call the same method (`CosmicPrimitiveCapabilityGateway.java:844-870`).
  - `hitLock.tryLock()` silently drops concurrent hits (`Reactor.java:255-264`).
  - States whose next state equals the current state are "looping": `act` runs on every hit (`Reactor.java:296-302`).
- **Drop expiry:** 180 s (`ITEM_EXPIRE_TIME`, `config.yaml:328`), except on `everlast=1` maps 990000500, 501 and 502 (`MapleMap.java:1036`, `MapFactory.java:320`).
  - **Most GPQ key items are finite (mobTime -1, reactorTime -1).** An unlooted drop that expires is a softlock. This applies to the Key, the Rusty Key, all 4 spears and every Mark of Evil.
- **Self-loot only in 990000500-990000502:** a player-dropped item can be picked up only by its dropper (`MapId.java:162-167`; `Character.java:2513-2514`).
- **Spawns:** mobTime -1 means spawn once and never respawn (`MapFactory.java:123-127`). Reactor `reactorTime` is the respawn delay in seconds (`MapFactory.java:386`). `reactorShuffle=1` in the WZ is **ignored** for GPQ, because shuffling only happens when the event sets `shuffleReactors` (`EventInstanceManager.java:777-778`). Reactor positions are the fixed WZ positions listed here.
- **GM drop gate:** GM characters below level 4 cannot drop items at all (`MINIMUM_GM_LEVEL_TO_DROP: 4`, `config.yaml:462`; `InventoryManipulator.java:762`).
- **Drop-restricted items:** items flagged `tradeBlock` or `quest` vanish when dropped (`InventoryManipulator.java:736-751`; `ItemInformationProvider.java:1461-1463`). None of the GPQ items carry those flags; they carry `pquest`.
- **Agents already have the primitives:** `dropItem` (`CosmicInventoryGateway.java:106-110`), `predictDropLanding` (`CosmicPrimitiveCapabilityGateway.java:651`) and a reactor-hit capability with range and verification (`AgentReactorPrimitiveCapability.java:71-120`).

---

## 2. Registration and waiting room (101030104 -> 990000000)

| Step | Who | What (code) |
|---|---|---|
| Register | **Guild master or Jr. Master** (`Character.isGuildLeader` = guildId > 0 && guildRank < 3, `Character.java:7428-7430`) | Shuang 9040000 at 101030104, option 0 (`npc/9040000.js:79-89, 123`), calls `em.addGuildToQueue(guildId, chrId)`. Returns: -1 = already queued on some channel, 0 = queue full (`EVENT_MAX_GUILD_QUEUE: 10`, `config.yaml:435`), 1 = queued, 2 = started now (`EventManager.java:813-846`). |
| Instance start | system | `attemptStartGuildInstance` polls the queue, looks the leader up **in this channel's player storage** and calls `startInstance(leader)` (`EventManager.java:849-867`). If the leader is not online on this channel, the guild is silently skipped. **Only the leader is registered and warped** (`EventManager.java:489`, `playerEntry` -> 990000000 portal 0, `GuildQuest.js:196-199`). The leader can be anywhere on the channel. `afterSetup` sets eim `guild` = the leader's guildId (`GuildQuest.js:187-192`). A guild notice is broadcast (`EventManager.java:761-768`). |
| Join (first 3 min only) | any member of that guild, **standing at 101030104** | Shuang option 1. `findLobby` matches eim `guild` == my guildId and canJoin == 1 (`npc/9040000.js:10-22, 92-104`).<br>- If I am a party leader: `em.getEligibleParty(party)` keeps members on 101030104 with the same guildId as me (`GuildQuest.js:93-126`), then `eim.registerParty(me)` registers those eligible members **present on my map** (`EventInstanceManager.java:400-415`).<br>- Otherwise only I am registered: `eim.registerPlayer(me)`.<br>- Either way `playerEntry` warps each registrant to 990000000. |
| Strategy phase | - | `entryTimestamp` = setup + 3 min (`GuildQuest.js:168-172`). Portal `join00` (132,161), script `guildwaitingenter`, refuses until then (`portal/guildwaitingenter.js:30-41`) and then warps to 990000100 portal 0 (sp -636,161). `st00` (428,-482), script `guildwaitingexit`, warps to 101030104, which leaves the event. |
| T + 3 min | system | canJoin = 0. If the leader is missing or fewer than 6 are registered, the run ends; otherwise the 90-min clock starts.<br>**If job niches (bit = (jobId/100)%10, `Job.java:123-124`; mask built in `EventInstanceManager.java:141-151`) cover all of bits 1-5**, i.e. Warrior, Magician, Bowman, Thief and Pirate (`isTeamAllJobs`, mask 0b111110, `GuildQuest.js:180-185`), every registered player gets one random "Sharenian Grounds" buff, 1 h (`applyEventPlayersItemBuff`, `EventInstanceManager.java:153-162`):<br>- 2023000 PAD +45 / PDD +30<br>- 2023001 MAD +35 / MDD +40<br>- 2023002 PDD +55 / MDD +55<br>- 2023003 ACC +35 / EVA +35<br>Cygnus jobs map onto the same niche bits (1100 counts as 1, and so on); Aran 2100 counts as 1 (warrior). Beginners are irrelevant. The buff is cancelled on unregister (`GuildQuest.js:224-229`). |

Map 990000000: no decHP, NPC 9040011 notice board at (348,160). On entering 990000100 every player gets an `npcTalk` warning about the Protector Rock (`GuildQuest.js:247-252`).

**Bot implications:**
- A bot joins by talking to Shuang at 101030104 itself, or code calls `eim.registerPlayer(bot)` directly.
- A bot must be in the **same guild** as the leader.
- If the human leader is auto-warped the moment the guild is called, bots cannot be bulk-registered through the human's party. Either each bot joins individually, or a bot party leader standing on 101030104 joins for its whole same-guild party.

---

## 3. Pre-stage: Protector Rocks (990000100 Valley of the Guard, 990000200 Entrance to the Remains)

- **990000100** (no decHP): **25 rock reactors**, 9202000 "guardian stone 1" x13 and 9202009 "guardian stone 2" x12. Each takes 5 hits (type 0, states 0..5), then `rm.dropItems()` drops 1x 1032033 (chance 1 = 100%, `131-reactordrops-data.sql:63-64`).
  - Respawn delay `reactorTime=60` s, except the rock at (281,-497), whose reactorTime is 0 (no respawn).
  - Positions:
    - 9202000: (-741,-444) (252,49) (445,-27) (340,49) (-744,171) (1032,-70) (-972,437) (-219,434) (422,431) (1020,436) (10,429) (1046,163)
    - 9202009: (246,-283) (-819,-38) (1008,-319) (451,153) (-710,-120) (281,-497) (303,-126) (1184,-345) (125,-189) (166,-635) (-862,-489) (-863,-227) (952,-51)
  - Exit `next00` at (238,-704), the top of the map, to 990000200 st00. NPC 9040005 (exit) at (917,179).
- **990000200** (no decHP): 2 more rock reactors, 9202000 at (-596,18) and 9202009 at (587,52), each with **reactorTime=1 s**. This is the respawn and refill point; after canRevive = 1, revived players land here. `next00` at (1070,220) leads to 990000300.
- **Objective:** every member equips 1032033 before entering 990000300. Lv < 10 characters cannot equip it.
- Bots need to hit 5x, loot, and **equip** (earring slot).
- Don't let one bot loot many rocks. It cannot anyway: `only` blocks a second pickup.

---

## 4. Stage 1: Gatekeeper statues (990000300 Door to the Sharenian Castle -> 990000301)

**Map 990000300:** decHP 30000. NPC 9040009 (Gatekeeper) at (645,163); 9040005 exit at (-636,161). Floor y = 165, platforms around y = 74, y = -49 and y = -134; short ropes from y = 76 to 144 at x = -399, -188, 32, 251, 886, 1100 and 1349.

20 statue reactors (all "state 0 -> 0", type 0: every hit runs `act`), named "1".."20":

| row | reactor id | name@x (y) |
|---|---|---|
| bottom (y=163-165) | 9208000 humanoid | 6@-292, 1@-80, 2@132, 3@344, 4@772, 5@984, 16@1212 |
| middle (y=-3/-4) | 9208001 demon | 8@-188, 9@34, 7@246, 10@893, 11@1106; also 20@(554,-140) |
| top (y=-133/-134) | 9208002 goblin | 19@-273, 12@-53, 13@156, 14@370, 15@806, 17@1018, 18@1239 |

`statuegate` 9208003 is at (563,165). Exit portal `next00` at (537,140) runs `statuegate_open`, which requires statuegate state 1 and warps to 990000301 (`portal/statuegate_open.js:28-30`).

**Mechanics** (`npc/9040009.js`, reactor scripts `9208000/1/2.js`, identical):
1. The **leader** talks to 9040009 and clicks through two dialog pages. The NPC builds a combo of `phase+3` distinct random statues (phase 1/2/3 means 4/5/6 statues, `npc/9040009.js:141`). It plays them with `delayedHitReactor(leaderClient, 5000 + 3500*i)` (`:108-110`) and sets `stage1status=display`.
2. Each display hit (`wHit=false`) appends `oid%1000` zero-padded to 3 digits onto eim `stage1combo`. **Player and bot hits during display are ignored** (`isRecentHitFromAttack`, `reactor/9208000.js:42`). On the last display hit, status becomes `active` and the map says "The combo has been displayed; Proceed with caution." (`:49-53`).
3. In `active`, **every** hit on any statue appends to `stage1guess` until it reaches the combo length; extra hits are ignored (`:57-60`). There is no undo.
4. The leader talks again. If `stage1combo === stage1guess` (`npc/9040009.js:79`):
   - phases 1 and 2 advance the phase;
   - phase 3 forces statuegate to 1, sets `stage1clear`, gives +15 GP (`:80-83`).
   - On mismatch: wrong effect, **phase resets to 1** (`:96`).
   - Status returns to `waiting` and the next talk starts a new combo.

**Timers:** about 19 s (phase 1), 22.5 s (phase 2) and 26 s (phase 3) of display. There is no other timer.

**Failure modes:**
- A multi-target attack hits 2 statues and appends 2 entries.
- Any stray hit poisons the guess, and you must restart at phase 1.
- **Possible softlock:** if a player hits a statue at the instant a display hit fires, `hitLock.tryLock` can drop the display hit. `stage1combo` then never reaches full length, the status stays `display`, and the NPC answers "statues are working" forever. Do not touch statues during display.

**Bots:**
- Exactly one hitter, or strictly serialized hitters, single-target, one hit per statue, in order.
- Observe the display: either the `triggerReactor` broadcasts during `display`, or read eim `stage1combo` and map each 3-digit group to a reactor by `getObjectId()%1000`.
- No job requirement. Statues sit at three heights, so the hitter needs to reach them or be in range of the reactor primitive.

990000301 Road to the Fortress: empty. Walk to `next01` at (514,209), which leads to 990000400 st00.

---

## 5. Stage 2: Longinus Spears (990000400 Hall of the Knight + 410/420/430/431/440 -> 990000401)

**Hub 990000400** (decHP 30000):
- NPCs: 9040012 plaque at (-1265,97) (flavor hint), 9040005 at (-1699,-186).
- Reactors: `speargate` 9208004 at (256,96), states 0-4, forced by the altars; `stonegate` 9208006 at (-767,-532).
- Portals:
  - `in00` (-741,137) to 990000410
  - `in01` (-978,-202) to 990000420
  - `in02` (-766,-518), script `stonegate_open` (stonegate state 1), to 990000430 (`portal/stonegate_open.js:27-29`)
  - `in03` (-118,-534) to 990000440
  - `next00` (296,66), script `speargate_open` (**speargate state 4**), to 990000401 (`portal/speargate_open.js:29-31`)

**Sub-steps:**

1. **Key for the stonegate (990000420 Room of Courage).**
   - Mobs (spawn once): **Master Muscle Stone 9300019** x1 at (352,146): Lv 65, 40,000 HP, aggressive. It casts summon 200 lv45, which spawns 4x Muscle Stone 9300020 every 7 s while alive, up to 20 (`wz/Skill.wz/MobSkill.img.xml` 200/45). Also 14x **Muscle Stone 9300020** (Lv 63, 16,000 HP).
   - Master Muscle Stone drops **Key 4001026** (999999/1e6, `152-drop-data.sql:20688`). It is the only source, so loot it within 180 s.
   - Drop the Key (qty 1) on the stonegate: reactor 9208006, type 100, item 4001026 x1, rect x [-840,-691) y [-560,-500); stand on the y = -515 platform in 990000400. After 5 s the stonegate opens.
2. **Rusty Key and 4th spear (990000430 Room of Faith / 990000431 Room of Pledge).**
   - Floor y = 155. "Building debris" **9202002 at (876,153): 30 hits** (states 0..30), then drops **Rusty Key 4001037** (`reactor/9202002.js`, `131-reactordrops-data.sql:67`). reactorTime -1, so there is exactly one key.
   - A zig-zag stair on the left (x -1170..-650, y 155 up to -308) climbs to the upper corridor y = -334 (x -990..1082). That corridor is patrolled by **19 Dark Muscle Stones 9300021**: Lv 200, 9,999,999 HP, **`invincible=1`**, bodyAttack, 0 EXP, positions x -630..1002, y ~-350.
   - **`metalgate` 9208005 at (927,-352)** sits at the far right of that corridor: type 100, item 4001037 x1, rect x [854,1003) y [-380,-320), on the y = -334 floor.
   - Portal `in00` (922,-337) runs `metalgate_open` and leads to 990000431 (`portal/metalgate_open.js:30-32`).
   - In 990000431: sealed spear **9202001 at (-183,-138)**, 5 hits, gives 1 spear.
   - **This is the "Dark Sight thief" step.** You must cross about 1,900 px of invincible body-damage stones carrying the Rusty Key. Nothing in code requires a thief.
3. **Three spears (990000410 Room of Glory).**
   - **fieldLimit 270974, so no Teleport or Flash Jump** (`990000410.img.xml:7198`).
   - Tall jump and rope map: 474 footholds up to y = -1465. Ropes at x = -284 (y -1042..-944), 248 (-1043..-844), 1041 (-1044..-909), 591, 852, 842, 1153, 187, and -14/628/1252 low.
   - Sealed spears **9202001 at (-182,-1155), (447,-1157), (1073,-1157)**: 5 hits each, reactorTime -1. The NPC hint reads: "evil stole spears, chained up behind obstacles... large spear, grab from higher up".
4. **Longinus Spear 4001025: `slotMax 1`, `only=1`.** Each player carries at most one at a time (`wz/Item.wz/Etc/0400.img.xml` 04001025). That makes 4 spears, 4 carriers or 4 trips.
5. **Altars (990000440 Room of Justice).**
   - 4 reactors `spear1..4` 9208007 at **(-2393,-1054), (-1399,-1054), (582,-1054), (1571,-1054)**. Each is type 100, item 4001025 x1, rect = position +- 20 px x, +- 30 px y. They stand on tower-top footholds y = -1044/-1042 (spear1 x -2478..-2320, spear2 x -1483..-1325, spear3 x 497..655, spear4 x 1485..1643).
   - Each altar is one-shot (state 0 -> 1). Its `act` does `speargate.forceHitReactor(state+1)`. At state 4 the clear effect shows in all six stage-2 maps and the guild gets +20 GP (`reactor/9208007.js:30-42`).
   - `reactor/9208004.js` (the speargate script, which also gives +20 GP) is **dead code**: `forceHitReactor` never runs `act` (`Reactor.java:205-213`).
   - Reaching the towers:
     - spear1: ropes x = -2415 (y -717..170) and -2173 (-854..-337), then a stair of 21-px steps up from the y = -898 roof.
     - spear2: a jump stair of ~60-px platforms from y = 518 up to -837, then the roof at -898.
     - **spear3: hidden-portal maze** (all `pt=1`, same-map teleports). Start `st00` (306,504). The correct chain is `h001` (550,363) -> h003 -> `h003` (480,175) -> h006 -> `h010` (792,-6) -> h011 -> `h012` (607,-185) -> h014 -> `h015` (555,-363) -> h017 -> `h017` (309,-543) -> h021 -> `h022` (547,-725) -> h025 (691,-902), the top. Every other `h0xx` returns you to st00.
     - spear4: rope x = 1661 (y -896..-609) and rope x = 1462 (276..490).
   - `gm00..gm03` portals have no target (markers).

**Exit:** speargate state 4, then `next00` to 990000401 Middle Path (empty), then walk `next01` (506,210) to 990000500.

**Failure modes:**
- Every key item is single-source with no respawn: Master Muscle Stone (mobTime -1), debris (reactorTime -1), 4 sealed spears (reactorTime -1). If any drop expires (180 s; these maps are not everlast), the run is dead.
- A spear dropped next to an altar but outside its +-20 px rect just lies there and expires.
- Invincible stones near the metalgate can kill the key carrier, and with canRevive = 0 a death removes that player.

**Jobs:**
- Dark Sight (430 crossing) and good jumpers (410 has no movement skills; 440 towers) are practical requirements, not code checks.
- The hidden portals in 990000440 must be supported by bot navigation as same-map teleports.

---

## 6. Stage 3: Vassals (990000500 Fountain of the Wiseman + 501 Center Banquet + 502 Wine Cellar)

**Map 990000500:**
- decHP 30000, **everlast=1** (`990000500.img.xml:2295-2296`), self-loot only.
- NPC 9040006 (fountain) at (45,-135), on the y = -130 platform x -22..110.
- `watergate` 9208013 at (-557,68).
- Portals: `in00` (629,-169) to 501, `in01` (-568,-167) to 502, `next00` (-557,150) `watergate_open` (state 1) to 990000600 portal #1 (st00 -828,371) (`portal/watergate_open.js:29-31`). Floor y = 155.

**Offering areas** (`<imgdir name="area">`, `990000500.img.xml:2254`). Index i = WZ child order 1..4 (`MapFactory.java:244-250`; `MapleMap.getArea(i)`):

| area index | rect |
|---|---|
| 0 | x [-230,-139) y [120,167) |
| 1 | x [-103,22) y [116,167) |
| 2 | x [71,204) y [117,169) |
| 3 | x [222,318) y [128,168) |

**Item sources** (exactly 4 of each, none respawn):

| value | item | source |
|---|---|---|
| 0 | Medal of Valor 4001027 | **Black Knight 9300022** x4 in 990000500: Lv 66, 40k HP, undead, casts **magic immunity** (141 lv5: 10 s, every 30 s). Spawns at (-181,144) x2 and (272,144) x2. `152-drop-data.sql:20689` |
| 1 | Scroll of Wisdom 4001028 | **Myst Knight 9300023** x4: Lv 66, 40k HP, undead, casts **weapon immunity** (140 lv6: 10 s, every 30 s). Spawns at (-40,144) x2 and (148,147) x2. `:20690` |
| 2 | Spoiled Food 4001029 | 4 real "Dining Hall Cover" plates **9202003** in 990000501 at (-414,-341), (2,-341), (-1123,-190), (1031,-157); 4 hits each. 8 decoys 9202010 at (541,-35), (805,-341), (399,-341), (-223,-213), (-826,-342), (-573,40), (132,41), (375,-166) have no script and drop nothing. 990000501 has decHP 3000, everlast, no movement skills. |
| 3 | Jr. Necki Drink 4001030 | 4 real pantry boxes **9202004** in 990000502 at (-1057,-170), (-752,-264), (-194,-2), (499,-214); 4 hits each. 8 decoys 9202011 at (849,-147), (224,-303), (245,5), (79,-31), (-399,153), (-953,153), (-601,-209), (-356,-349). 990000502 has decHP 30000, everlast, no movement skills. |

**Mechanics** (`npc/9040006.js`):
1. Leader's first talk: random 4-digit base-4 code (digits 0-3, repeats allowed, `:124-130`), stored as eim `stage3combo` (a number; parse it and left-pad to 4 digits). `stage3attempt` = 1 (`:29-34`). **The thousands digit = area 0, ..., the units digit = area 3** (the guess is built that way, `:181-201`).
2. Place offerings: **the map must hold exactly 4 items with ids 4001027-4001030 in total**, one inside each of the 4 areas (`:164-201`). Any extra such item anywhere on the map, or an empty area, makes the leader's talk return "Please make sure your attempt is properly set...". That costs no attempt.
3. Leader talks:
   - **Match:** watergate forced to 1, `stage3clear`, +25 GP, ground items removed (`:41-45`).
   - **Mismatch with attempt < 7:** Mastermind feedback to the leader only (`:48-118`). "N vassals are pleased" = right item in the right area (black peg). "incorrect" = right item, wrong area (white peg). "unknown" = item not in the remaining code. It also spawns 1x Black Knight 9300036 at (-350,150) and 1x Mist Knight 9300037 at (400,150) (22k HP, 100 EXP, no drops), then attempt + 1.
   - **Mismatch on attempt 7:** code reset (`stage3combo=reset`) plus 6 + 6 knights at random x in -350..400 (`:121-130`). The next talk draws a new code.
4. Items are **not consumed on a wrong guess**. Rearranging means each dropper re-picks **its own** drop (self-loot).

**Failure modes:**
- Losing an item: it is never lost to expiry here, but a bot that drops one and is later removed leaves an orphan only it could loot. That orphan is a 5th item on the map, so every later check returns null.
- Bot inventories still holding extra offerings are fine; only ground items count.

**Bots:**
- A Mastermind solver for 4 positions x 4 colors with feedback fits easily in 7 guesses (256 codes; consistent-guess strategies finish in 5 or fewer).
- Feedback is sent only to the leader's NPC dialog. A bot needs it via a hook, by reading eim `stage3attempt` plus re-computing, or a "cheating" read of `stage3combo`.
- Physical attackers for Myst Knight (weapon immunity) windows are the wrong pairing; you need both physical and magic damage dealers, and Heal damages these undead mobs.

---

## 7. Stage 4: Waterway and Sharen III's Grave (990000600 + 610/611, 620, 630/631, 640/641 -> 990000700)

**Hub 990000600 Underground Waterway** (decHP 30000):
- Continuous floor y = 425 from x -1125 to 3015, plus a start ledge y = 375 (x -1124..-363). No walls.
- NPCs: 9040007 note at (-585,372) ("return the clothes to my body **bottom to top**", `npc/9040007.js`); 9040005 at (-1019,371).
- Room portals sit on small raised platforms reached by steps:
  - `in00` (170,312) to 990000610
  - `in01` (875,315) to 990000620
  - `in02` (2636,231) to 990000630
  - **`in03` (1871,329) `under30gate` to 990000640 portal #1, only if Lv <= 30** (`portal/under30gate.js:31-33`)
- `maze1xx..4xx` are hidden shortcut teleports within the map (e.g. maze101 -> maze403, maze103 -> maze301, maze104 -> maze102; several lead back to st01). They are optional.

| room | objective | items / reactors | exit |
|---|---|---|---|
| **990000610** Waterway Maze | Kill **9 Puppet Golems 9300024** (Lv 67, 35k HP; spawned once along y ~165, x -586..580). Each drops 1x **Mark of Evil 4001035** (`152-drop-data.sql:20691`). **Drop one stack of exactly 9** on `secretgate1` 9208010 at (811,115): rect x [738,887) y [87,147), floor y = 132. | 9208010: item 4001035 qty **9** | `in00` (811,131) `secretgate1_open` to 990000611 portal #1 (`portal/secretgate1_open.js:29-31`) |
| **990000611** End of the Maze (270974: no movement skills) | Climb to the clothes box **9202005** at (2623,-74). It takes 5 hits; the 5th and every further hit drop **Sharen III's Pants 4001031** (looping state 5, `Reactor.java:296-302`). | - | `guild1F00` (2618,185) script `guild1F01`: `gridInsert(chr,0)`, warp 990000700 `st00` (`portal/guild1F01.js`) |
| **990000620** Waterway Maze (270972: Teleport allowed) | No mobs. Climb to box **9202006** at (1084,-1037). Ropes at x = -807 (y -856..-591) and 1033 (-333..-147); the map top is y = -1216. Drops **Sharen III's Shoes 4001032**. | - | `guild1F00` (1089,-465) `guild1F02`: grid 1, warp 990000700 |
| **990000630** Waterway Maze | **Gargoyle 9300025** (Lv 68, 35k HP; leash x -115..-45 on the y = -204 ledge) sits on item reactor **9201000** at (-82,-208). The reactor wants **4001036 "." x1** in rect x [-162,-2) y [-238,-178). Gargoyle drops 4001036 on death (`:20692`), so killing it on its ledge triggers the reactor. 5 s later the reactor spawns **8x Jr. Gargoyle 9300033** at (-100,50) (Lv 62, 15k HP; `reactor/9201000.js:31`). Each drops 1 Mark of Evil (`:20694`). **Drop a stack of exactly 8** on `secretgate2` 9208011 at (412,86): rect x [339,488) y [58,118), floor y = 102. Gargoyle also summons **Jr. Gargoyle 9300026** x6 every 21 s, up to 40 (skill 200 lv46); **9300026 drops nothing**. | 9201000: 4001036 x1; 9208011: 4001035 qty **8** | `in00` (411,103) `secretgate2_open` to 990000631 #1 (`portal/secretgate2_open.js:28-30`) |
| **990000631** End of the Maze (270974) | Hidden-portal maze `pt00..pt09`: pt00 -> st01, pt01 -> st01, pt02 -> pt04, pt03 -> pt00, pt04 -> st01, pt05 -> pt08, pt06 -> pt09, pt08 -> st01, pt09 -> st01; st01 = (93,185). Reach box **9202007** at (11,-776). Drops **Sharen III's Top 4001033**. | - | `guild1F00` (655,184) `guild1F03`: grid 3, warp 990000700 |
| **990000640** Waterway Maze (**Lv <= 30 only**) | Kill **20 Devil Slimes 9300027** (Lv 23, 1,000 HP). Each drops 1 Mark of Evil (`:20693`). **Drop a stack of exactly 20** on `secretgate3` 9208012 at (585,-323): rect x [512,661) y [-351,-291), on the upper floor y = -307. | 9208012: 4001035 qty **20** | `in00` (592,-307) `secretgate3_open` to 990000641 #1 (`portal/secretgate3_open.js:28-30`) |
| **990000641** End of the Maze (270974) | Rope at x = 831 (y -333..-152). Box **9202008** at (-1185,-594). Drops **Sharen III's Crown 4001034**. | - | `guild1F00` (1004,-336) `guild1F04`: grid 2, warp 990000700 |

Clothes-box drops: 100% (`131-reactordrops-data.sql:70-73`). Their reactorTime = 1.

**990000700 Sharen III's Grave** (decHP 30000; floor y = 185):
- Sharen III's remains **9201001 at (1438,180)**: a 4-state item reactor, rect x [1393,1473) y [160,200). It needs, **in order, one at a time (qty 1 each): Shoes 4001032, then Pants 4001031, then Top 4001033, then Crown 4001034** (`wz/Reactor.wz/9201001.img.xml`). Each accepted drop fires 5 s after it lands; the next item can already be lying there, because the reactor rescans the ground.
- After the Crown, `act` spawns NPC **9040003** (Sharen III's ghost) at the reactor (`reactor/9201001.js:32`).
- **The leader** talks to it: `stage4clear`, +30 GP, `ghostgate` 9208008 at (1641,180) forced to 1 (`npc/9040003.js:65-70`).
- Then `next00` (1688,160) `ghostgate_open` leads to 990000800 portal 0 (`portal/ghostgate_open.js:30-32`).
- `out00` (66,179) `guild1F00` returns to 990000600 at portal index `[6,8,9,11][gridCheck]`: maze102 (171,163), maze202 (857,174), maze301 (1660,164) or maze402 (2524,166), i.e. back near the room you came from (`portal/guild1F00.js:30-34`). A player with no grid entry (gridCheck = -1) gets `backPortals[-1]` = undefined, so the result is unknown.

**Failure modes:**
- **Mark counts are exact and finite:** 9 golems, 8 Jr. Gargoyles (the reactor never re-arms; reactorTime 0) and 20 slimes, all spawned once, in maps without everlast. Any Mark not looted within 180 s is gone and that room is softlocked. Dropping the wrong quantity (e.g. 10) does nothing.
- If Gargoyle dies off its ledge, its 4001036 lands outside the rect. Anyone can pick it up and re-drop it in the rect; it is not exclusive.
- **There is no Lv <= 30 member, or that member dies before canRevive:** then there is no Crown, so no ghostgate and no clear. This is a hard requirement. The member must also be **Lv >= 10** to wear a Protector Rock, and must survive stages 1-3 alongside the team.
- Clothes delivered out of order are ignored; that item sits until it expires (990000700 is not everlast).

**Jobs:**
- **Lv 10-30 character:** mandatory (640/641).
- Ranged or AoE help on the Gargoyle ledge.
- Jumpers and climbers for 611, 620, 631 and 641. Teleport works only in 620 (and 610, 630, 640); it is disabled in 611, 631 and 641.

---

## 8. Stage 5: Ergoth (990000800 King's Corridor -> 990000900 Ergoth's Throne)

**990000800** (decHP 30000): floor y = 155 (x -1170..450); raised platform y = 103 (x 5..409).
- **`kinggate` 9208009 at (261,83)**: type 100, item **1032033 Protector Rock x1**, rect x [175,303) y [68,116); stand on the y = 103 platform.
- Its `act` sets eim **canRevive = 1** (`reactor/9208009.js:24-28`).
- Portals `boss00` (251,102) and `boss01` (-166,152) (`kinggate_open` / `kinggate2_open`, kinggate state 1) lead to 990000900 portal #1 st00 (-650,146) or #2 st01 (-406,-217) (`portal/kinggate_open.js:30-32`).
- **The gate costs one Protector Rock.** Nobody can hold a spare (`only=1`), so **some member must unequip and drop their own rock and will die from the air within <= 5 s**.
  - Timeline: drop at t0, kinggate act at t0 + 5 s, death between unequip and unequip + 5 s.
  - **That member must not press revive until canRevive = 1**, otherwise they are removed and it counts as a departure (1.3).
  - After canRevive, they revive into the instance's 990000200 and can re-equip there (reactorTime 1 s rocks). They can walk back through the opened gates: 300 -> 301 -> 400 -> 401 -> 500 -> 600 -> 620 (no gate) -> guild1F02 -> 700 -> 800 -> 900.
  - For bots this is a scripted sacrifice plus a held death. A Lv <= 30 member who has finished its job is a natural candidate. The leader must never be the one.

**990000900** (decHP 30000):
- Floor y = 155 (x -720..720), upper-left ledge y = -213 (x -467..-18) where st01 is, small ledge y = -52 (x -718..-539).
- NPCs: 9040010 at (-445,147) (turn-in); 9040005 at (-634,-54).
- **Rubian reactor `boss` 9201002 at (344,12)**: type 0, 5 hits (states 0..5). The final `act` changes music and spawns (`reactor/9201002.js:30-35`):
  - **Ergoth 9300028** at the reactor (ground below).
  - Knight Statue A 9300031 at (130,90), Knight Statue B 9300032 at (540,90).
  - Lion Statue A 9300029 at (130,150), Lion Statue B 9300030 at (540,150).

Mob stats from `wz/Mob.wz` (mob skill params from `wz/Skill.wz/MobSkill.img.xml`):

| mob | stats | skills |
|---|---|---|
| **Ergoth 9300028** | Lv 115, **1,700,000 HP**, 150k MP, 150k EXP, boss, undead, stationary (no `move` node in Mob.wz; the statues have none either). Elem resist `I2F2L2H2S2`. PAD/MAD 700, PDD 1100, MDD 1200. | Attacks are all magic; **attack2 is `deadlyAttack`**, which drops the target to 1 HP/MP. Skills:<br>- 114 lv8 heal (+20k HP, every 23 s)<br>- **140 lv7 weapon immunity, 30 s every 17 s**<br>- **141 lv6 magic immunity, 30 s every 19 s**<br>- 200 lv48: 5x Mist Knight 9300034 (20k HP, 0 EXP, no drops) every 17 s below 90% HP, up to 80 |
| Lion Statue A 9300029 | 1,000,000 HP | heal 114 lv7 (+30k, every 11 s) |
| Lion Statue B 9300030 | 800,000 HP | slow 126 lv4 (every 11 s, 15 s) |
| Knight Statue A 9300031 | 900,000 HP | dispel 127 lv5 (every 10 s) |
| Knight Statue B 9300032 | 900,000 HP | seal 120 lv7 (every 25 s, 30 s, wide area); summon 200 lv47: 5x Jr. Gargoyle 9300035 (15k HP, 0 EXP) every 23 s, up to 80 |

All four statues are boss-flagged with 0 EXP and **need not die**.

- Ergoth drops **Rubian 4001024** (999999/1e6, `152-drop-data.sql:8930`). Other drops: Ergoth card 2388021 (2.4%), mastery book 2280015 (3%), 2290026/2290064/2290075/2290093/2290111 (1% each) (`:10490, 21575-21623`).
- **The leader** must hold the Rubian and talk to 9040010. It removes the Rubian and gives GP = `max(100, 1000 - floor((now - entryTimestamp)/6000))`, i.e. -10 GP per minute since the strategy phase ended. Then `eim.clearPQ()` runs (`npc/9040010.js:25-41`).
  - Rubian is droppable and not `only`, so a bot can loot it and drop it for the leader.

**Failure modes:**
- **All players in 990000900 dead while monsters live: the run ends.** Dead players who revived to 990000200 don't count as alive in 990000900.
- Immunity windows stall the fight for one-damage-type parties.
- The statues' heal (Lion A) and dispel/seal hinder the fight.

**Jobs:** mixed physical + magic damage, a cleric/priest (undead Ergoth; Heal damages undead) and HP sustain against deadlyAttack.

---

## 9. Bonus, clear and exit (990001000 / 990001100 / 990001101)

- `clearPQ` (`GuildQuest.js:308-314`): stops the timer, `setEventCleared`, warps **all registered players** (`warpEventTeam`) to **990001000 Sharen III's Treasure Warehouse** and starts a 30 s timer.
  - `setEventCleared` gives quest points (`QUEST_POINT_PER_EVENT_CLEAR: 0`, `config.yaml:382`) and the BotStory **Perfect Pitch 1-3** per player (`EventInstanceManager.java:1172-1240`, non-expedition range `{1,3}`).
  - `LivingWorldHooks.partyQuestCleared` counts it as a PQ clear for living-world quests and friendship. GuildQuest is not in `BOSS_EVENT_SCRIPTS` (`EventInstanceManager.java:865-874`; `LivingWorldHooks.java:309-334`; `config.yaml:1055`).
- 990001000 (decHP 30000, so keep the rock on): **25 bonus boxes 9202012**, linked to 9202005, at fixed positions across the map.
  - 5 hits to the first spray; per code the looping final state sprays again **on every further hit** (`Reactor.java:296-302`).
  - Spray: `sprayItems(true,1,30,60,10)`, at least 10 drops, meso bags 30-60 (`reactor/9202012.js:29`). Drop table in `131-reactordrops-data.sql` (reactor 9202012):
    - potions at chance 3
    - many 10%/60% scrolls at chance 40
    - mastery books 2290009/31/33/39/45/60/73/81/83/87 at 60
    - 2100000 at 250
    - equips (1002379, 1002383, 1002401, 1002408, 1002646, 1002739/40, 1002856/7, 1032046, 1050127, 1051140, 1052148/9, 1072342/3, 1122040/46/52, 1142153, 1302049, 1332053, 1372011, 1442046) at 15-45
    - Probability = dropRate / chance per entry (`ReactorActionManager.java:223-240`).
  - Internal portals st01 -> st02 and st03 -> st04.
- After 30 s `scheduledTimeout` warps everyone to **990001100 Returning Path** (`GuildQuest.js:201-204`). Nuris 9040001 at (165,525) calls `giveEventReward`, which grants nothing (empty set), then warps to 101030104 (`npc/9040001.js`).
- 990001101 "Guild Union Camp" is a copy of the bonus map with timeLimit 20. **The event never uses it.**
- If the 90-min timer expires uncleared, `end(eim)` exits everyone to 990001100 (`GuildQuest.js:218-220, 296-302`). After dispose, the next queued guild is called after `EVENT_LOBBY_DELAY*1.5` = 15 s (`:322-328`; `config.yaml:436`).

---

## 10. Java hooks used by GPQ (reference)

| Hook | Behaviour (file:line) |
|---|---|
| `gridInsert(chr,n)` / `gridCheck(chr)` | Per-player int map. `gridCheck` returns -1 when absent. Cleared per player on unregister (`EventInstanceManager.java:1470-1497, 447`). Used only to pick the return portal from the Grave. |
| `giveEventPlayersStageReward(stage)` | Gives EXP and mesos from `setEventClearStageExp`/`Meso`. GPQ sets an empty list, **so it gives nothing** (`EventInstanceManager.java:1441-1445, 980-1009`; `GuildQuest.js:89-90`). |
| `applyEventPlayersItemBuff(id)` | Applies a consumable's StatEffect to every registered player (`:153-162`). |
| `getEventPlayersJobs()` | OR of `1 << ((jobId/100)%10)` (`:141-151`; `Job.java:123-124`). bit0 Beginner/Noblesse/Legend, 1 Warrior/DW/Aran, 2 Mage/BW, 3 Bowman/WA, 4 Thief/NW, 5 Pirate/TB. GM jobs land on bit 9. |
| `registerPlayer` / `registerParty` | `registerPlayer` adds the player, sets their event instance and runs `playerEntry` (warp) (`:243-269`). `registerParty` registers the party's eligible members who are on the leader's map (`:400-415`). There is no capacity check. |
| `startInstance(chr)` | Lobby -1, then `setup`, `setLeader`, register this one character, `startEvent` (which runs `afterSetup`) (`EventManager.java:440-509`). |
| Guild queue | `addGuildToQueue` (`:813-846`), `getNextGuildQueue` (`:778-799`), `attemptStartGuildInstance` (`:849-867`), `exportReadyGuild` (`:761-768`), `isQueueFull` (`:801-805`). The world-level `isGuildQueued` stops one guild queuing on two channels. |
| `checkEventTeamLacking` / `isEventTeamLackingNow` | See 1.3 (`:1258-1287`). |
| `revivePlayer` / `playerKilled` | `revivePlayer` calls script `playerRevive`; its boolean decides the standard respawn (`:601-611`; `ChangeMapHandler.java:101-106`). `playerKilled` runs `playerDead` on a blocking task (`:585-592`). |
| `showClearEffect(hasGate[,map])` | Effect, sound and `environmentChange("gate",2)`, remembered for late joiners (`:1390-1437`). |
| `giveEventReward` | Random pick from the reward list for an event level; an empty list means no item and no EXP (`:1098-1150`). |
| `clearPQ` | Living-world hook, then script `clearPQ` (`:865-874`). |

---

## 11. Death and revive summary

| Situation | Result |
|---|---|
| Dies, presses OK, canRevive = 0 | Unregistered and sent to 990001100. Exclusive items (Rock, Rubian, Marks, clothes, keys, spears) are removed. **Run ends** if count <= 6 (counted before removal) or if it is the leader. |
| Dies, never presses OK | Stays registered, as a corpse, until OK. Note: in 990000900 that corpse is "not alive" for the wipe check. |
| Dies, presses OK, canRevive = 1 | Revives in instance 990000200 with 50 HP, still registered. They need a rock there (two 1-s respawn rocks) before going back. |
| Wheel of Fortune | Revives in place with 50 HP, without the event hook. |
| Leaves via exit NPC 9040005, `guildwaitingexit` or a disconnect | Same departure rule as the first row. |
| Bot today | Falls through the death gates to `respawnAtNearestTown`, leaves the map range, and counts as a departure. **A new GPQ gate is needed.** |

---

## 12. Rewards

- **GP (guild):**
  - Stage 1: +15 (`npc/9040009.js:83`)
  - Stage 2: +20 (`reactor/9208007.js:41`; credited to the 4th spear dropper's guild)
  - Stage 3: +25 (`npc/9040006.js:43`)
  - Stage 4: +30 (`npc/9040003.js:69`)
  - Boss: 1000 - 10/min, minimum 100 (`npc/9040010.js:33-38`)
  - `Guild.gainGP` writes to the DB and messages the guild (`Guild.java:693-698`).
- **Per player:** none from the event script itself (empty reward list, empty stage EXP). From BotStory: Perfect Pitch 1-3, living-world PQ-clear credit and friendship. Mob EXP from kills (Ergoth 150k; many GPQ mobs give 0 EXP).
- **Loot:** Ergoth's drop table and the bonus boxes (section 9). Exit NPC Nuris gives nothing.

---

## 13. Guild mechanics (stock + BotStory)

**Stock (`net/server/guild`, handlers):**
- Rank 1 = master, 2 = Jr. Master, 3-5 = members. GPQ registration requires rank <= 2 (`Character.java:7428-7430`).
- **Create:** at Guild HQ 200000301, 1.5M mesos (`config.yaml:385-386`). You need >= `CREATE_GUILD_MIN_PARTNERS` = 6 eligible characters on the map, counting the leader, each in no party and no guild (`Guild.java:747-759`; `GuildOperationHandler.java:70-108`). All of them must accept a match-confirmation dialog. Cofounders who end up in the leader's newly created party become **Jr. Masters (rank 2)**; the rest join at rank 5 (`MatchCheckerGuildCreation.java:96-137`).
- **Invite:** rank <= 2 required (`GuildOperationHandler.java:110-121`). **Accept:** client packet 0x06, joins at rank 5 (`:123-160`).
- **Rank change:** rank <= 2, and only the master can set rank 2 (`:211-222`).
- **Capacity:** DB default **10** (`src/main/resources/db/tables/007-guild.sql:14`). `addGuildMember` refuses when full (`Guild.java:474-478`). Each NPC expansion adds +5, up to 100 (`Guild.java:676-690`).

**BotStory bot guilds** (`server/living/population/SocialFabric.java`):
- `GUILDS_ENABLED: true`, `GUILD_TARGET: 24` (`config.yaml:1100-1101`).
- **Founding** (`ensureGuilds`, `:172-223`): the founder is a random ACTIVE, **not hired**, guildless citizen of Lv >= 10. It gets **rank 1**. Capacity is forced to **30** by SQL (`:48, 225-235`). Guild meta: stance "open", level band = founder level +-15 (`:216-217`).
- **Recruiting** (`:236-266`): up to 3 citizens per tick, 20% chance each. Requirements: ACTIVE, not hired, guildless, inside the level band, and the guild has fewer than 28 members. Same-cohort guilds are weighted double. Recruits join at **rank 5** (`join`, `:269-292`; hired citizens are skipped).
- **No code ever assigns rank 2 or promotes anyone** in bot guilds. `changeRank` is called only from the player handler and guild creation.
- **Humans:** a recruiter invites a guildless human it has spent enough time around, once, if the human's level is in the band (`maybeInviteHuman`, `:305-325`). The human accepts through the normal client and becomes **rank 5, so cannot register GPQ**. A bot master never promotes them.

**Companions (`server/living/roster`):**
- **No guild code at all.** The only "guild" token is the acquaintance flag at `CompanionBuddies.java:124`.
- There is no automatic way for a bot to accept a guild invite or answer the creation match check. The only join path is the client packet; `InviteCoordinator` invites go to a BotClient nobody answers.
- `HiredHands` does not clear a hired citizen's existing guild, so a hired citizen may still be in a bot guild.

**What blocks a human leader plus bot companions:**
1. All participants must share **one guildId**. Party eligibility and Shuang's lobby lookup both compare guildId.
2. The human must be **master or Jr. Master** of that guild.
3. Companions cannot be put into the human's guild by any existing path. That needs a server-side helper modeled on `SocialFabric.join`: `setGuildId`/`setGuildRank`/`setMGC` plus `Guild.addGuildMember` plus `saveGuildStatus`. Hired citizens must leave their bot guild first.
4. The human's guild needs capacity for everyone (default 10).
5. The human cannot create a guild with bot cofounders, because bots don't answer the match confirmation, unless a helper does it.

---

## 14. Role / job requirement matrix

| Requirement | Where | Enforced by code? |
|---|---|---|
| Instance leader (guild master or Jr. Master who registered) | All NPC steps: 9040009, 9040006, 9040003, 9040010 | **Yes** (`isEventLeader`) |
| >= 6 registered at T+3 min; >= 7 to survive one loss | whole run | **Yes** |
| Lv >= 10 (Protector Rock) | everything past 990000200 | **Yes** (equip reqLevel + decHP) |
| **Lv <= 30 member** | 990000640/641 (Crown) | **Yes** (`under30gate`) |
| Dark Sight thief (+ Haste) | 990000430 invincible Dark Muscle Stones before the metalgate | No (practical) |
| Teleport mage | climbs in 620 (and the 610/630/640 floors); **useless** in 410/501/502/611/631/641 | No |
| Long-ranged attacker | e.g. Gargoyle ledge (630), high reactors, Ergoth | No |
| Good jumper (Flash Jump, Wings) | 440 towers, 620; **Flash Jump disabled** in 410/611/631/641 | No |
| One sacrificed Protector Rock | 990000800 kinggate | **Yes** (item reactor) |
| All 5 explorer job niches | +1 random Sharenian buff | Yes (optional bonus) |

---

## 15. Open questions / unclear

1. **Reachability per map with and without movement skills** was not computed. Climbs to check with the agents' nav graph: 410 spears at y ~-1155 with ropes topping out around y -1044 (is a reactor hit possible from below, or is "grab from higher up" literal?), 440 towers, 620/631/641 boxes, and 100 next00 at y = -704.
2. **Hidden same-map teleport portals** (`pt=1`, target = the same map) in 990000440 (h-maze), 990000631 (pt-maze), 990000600 (maze shortcuts), 990000630 (st00 -> st01) and 990001000. Does agent navigation model them?
3. **Dark Muscle Stones (invincible, bodyAttack):** how does the server simulate touch damage for agents, and does Dark Sight block it for bots? For humans it is client-side.
4. **Clothes and bonus boxes re-dropping on every hit after the 5th** (looping state 5) is from code reading; verify live. It would allow repeated bonus-box sprays within the 30 s.
5. `portal/guild1F00.js` with gridCheck = -1 (a player who reached 990000700 without a guild1F0x portal) warps with `backPortals[-1]` = undefined. Behaviour unverified.
6. `dropExclusiveItems` uses `removeAll(itemId)`. Does that also strip an **equipped** Protector Rock on unregister? Unverified.
7. Is `stage3combo` stored as "23" or "23.0"? `parseInt` handles both; a bot reading it should parse it as an int and left-pad.
8. Does the 180 s expiry apply to mob drops inside event-instance maps? The code path is generic (`MapleMap.java:1036`), so the brief assumes yes. That is the basis for the "loot Marks, spears and keys quickly" warnings.
9. Display-hit drop race in stage 1 (concurrent `hitLock.tryLock`) is inferred from code; not observed.
10. `9040011` says "Guild must consist of at least 6 people", but nothing checks guild size, only the >= 6 registered rule. Shuang's party join only pulls members standing on 101030104 in the same guild as the party leader.
11. Ergoth attack ranges and damage specifics (attack1-3 `lt`/`rb`) were not extracted. attack2 is `deadlyAttack`, attack3 is type 3 (area).
12. Whether a bot can be the instance leader (a bot master/Jr. Master registering, with the human just a member) is mechanically fine, but the leader-only NPC dialogs would then need bot NPC-talk for 9040009, 9040006, 9040003 and 9040010.

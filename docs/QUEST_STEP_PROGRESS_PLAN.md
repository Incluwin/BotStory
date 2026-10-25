# Resident quest step progress in the v83 quest window

Branch `feat/quest-step-progress` (from feat/companion-manager 21eb70855). Goal: a Resident quest's kill and collect
steps show live counts in the stock v83 quest window, as stock quests do, without breaking stock quests or the
mirror's start/complete. Background: `docs/LIVING_WORLD_GUIDE.md` "The in-game quest log".

## 1. What already existed (deployed 2026-10-03)

The mirror did more than start and complete: since 10-03 the **Quest Helper** counted Resident steps.
- `QuestLogTargets` picks what the client counts: the first monster of each kill step (KILL_MOBS, KILL_BOSS,
  HUNT_WITH) with its count, and each COLLECT_ITEM item; a target two steps share is listed once (larger count). The
  exporter puts them in its JSON (`mobs`, `items`); `tools/wz-patcher/residents.check_node` writes them as Quest.wz
  `Check.img/<id>/1/mob|item/<i>/{id,count}`.
- Kills: `QuestLedger.creditKill` (on the "living-kills" lane; map filter, companion kills credited to the owner when
  co-present, `with`/HUNT_WITH partner checks) -> `progress` -> `QuestLog.progressed`, which sends the quest record:
  three digits per tracked monster in Check/1 order (`QuestLogTargets.kills`: earlier steps full, the current step its
  count, later steps 0). The same at accept (`started`) and login (`reconcile`). Character's stock kill counter skips
  30000-32767.
- Items: `onItemGained` counts the inventory minus items the giver handed over in the provenance window
  (`countEligible`); the client counts the raw inventory itself, so nothing is sent for items.

## 2. What was missing

1. **The quest window's detail text had no counts.** The v83 window shows a quest's "1" text while it is In Progress
   and builds no requirement list of its own. Stock quests write live codes into that text (server Quest.wz XML):
   - `#a<questId><n>#`: kills of the n-th Check.img/1 monster, n one digit, from 1. Quest 1016 (Snail, Blue Snail,
     Shroom) has `#a10161#`, `#a10162#`, `#a10163#`; quest 20003 has `#a200031#` (the number is questId*10+n). 530 uses.
   - `#c<itemId>#`: how many of the item the inventory holds (1,554 uses, usually `#c4031701# / 5`).
   - `#o<mob>#`, `#t<item>#`, `#m<map>#`: names from the client's own String.wz.

   Ours was one static line (`#bGiver (Town)#k: talk to Giver -> defeat 30 Hector -> report to Giver`), so a
   "kill 30 Jr. Yetis" or "collect 20 feathers" step showed no number in the quest window.
2. **Every kill sent a start result.** The mirror went through `Character.updateQuestStatus`, which for STARTED also
   sends UPDATE_QUEST_INFO type 8 (the stock "quest started" result) on each kill and step; the stock kill counter
   sends only the record (SHOW_STATUS_INFO mode 1).
3. **The record check ignored order and extras.** `QuestLog.shows` compared the tracked monsters one by one, so a
   record with a stale extra entry (content moved a step to another monster) or in another order counted as shown,
   and the client kept reading the wrong slots.

## 3. What was built

### Server (`server.living.quest.QuestLog`)
- A started quest whose kills change gets the new counts on the character's own QuestStatus and one record update
  (`announceUpdateQuest(UPDATE, qs, false)`), as the stock kill counter does. Accept, complete, and a status change at
  login keep `updateQuestStatus` (start/complete results as before).
- The check compares the whole ordered record. A record holding the same monsters in Check/1 order takes the new
  counts in place; any other record (another order, a monster content no longer tracks) is replaced whole, at the next
  kill or step or at login.

### Client data (`QuestLogExportMain`, `QuestLogTargets`, `tools/wz-patcher/residents.py`)
- `inProgress` (Quest.wz QuestInfo "1") is the giver, the line they said on accepting (`questAccept.<id>`, first
  line, `#` removed; none if the persona has no such line), a blank line, and one line per step. The opening TALK step
  is the conversation that started the quest, so it gets no line. Sigrun's "The Cave of Trial" (log id 30200):
  ```
  #bSigrun (El Nath)#k
  past the dead mine, down. the young ones bite. the door doesn't. the door is worse.

  Defeat #o4230108#: #r#a302001##k / 30
  Collect #t4000078#: #b#c4000078##k / 15
  Go to #m211042300#
  Bring Sigrun 15 #t4000078#
  ```
  Step lines: kill `Defeat #o<first mob>#[ and the like][ with X]: #r#a<logId><slot>##k / <count>` (HUNT_WITH
  `Hunt ... with X`); collect `Collect <label or #t<item>#>: #b#c<item>##k / <count>`; give `Hand over ...` with the
  same count; visit `Go to #m<first map>#`; deliver `Bring X <n> <item>` or `Report back to X` / `Report to X`;
  talk-to, escort, pay, reach level, wait, rumor and party quest in words. Line breaks are a backslash and an `n`, as
  stock quest text writes them (about 98,000 such breaks in stock QuestInfo text against 459 values with real
  newlines).
- `QuestLogTargets.mobSlot(quest, step)` gives the slot a step's `#a` code reads, from the same list the Check/1 entry
  is written from, so the two cannot drift; a monster two steps share has one slot. The code has one digit, so only
  the first nine counted monsters get live counts (`QuestLogTargets.CODED_SLOTS`); ContentLint WARNs past that (the
  most any shipped quest counts is 2).
- Check.img/1 items now also list GIVE_ITEM items (none ship today), so the Helper and the text count the same items.
- Monster, item and map names come from the player's client through the codes; the exporter no longer reads the
  server's String.wz for this text. Nothing Nexon-derived is committed or exported beyond ids.
- `residents.py` writes the text as given (no format change).

## 4. Verified offline (2026-10-25)
- `QuestLogTest` (9) over the packets: a kill sends one record update and no start result, and the record's
  SHOW_STATUS_INFO bytes read mode 1, the log id, status 1 and the digits ("012"; "020004" for Fenna's two monsters
  after the first step); accept sends both monsters at "000000" in Check order; a reordered record is replaced whole;
  login drops a monster the quest no longer tracks and catches a stale count up with a record update only.
- `QuestLogExportTextTest` (7): the exact text of Edric's "The Watch"; every step kind of "The Cave of Trial"; two
  monsters in their own slots in Check order (Fenna); a labelled item keeps its label (Garrick's badge); a quest without
  an accept line; over all 285 shipped quests, every `#a` code names its own log id and the slot of the `#o` monster on
  its line, every `#c` item is in the Check/1 items, every kill step counts live, and no raw newline is written.
- `QuestLogTargetsTest` (+2): slots per step, every quest within nine slots. `ContentLoaderTest` updated.
- `tools/wz-patcher/tests/test_residents.py` (+1): text with codes and the two-character breaks survives a Quest.wz
  write and read back, and its `#a` slots and `#c` items match the written Check.img/1.
- End to end: the exporter on a scratch copy of the content (285 quests; ids unchanged) and `residents.patch_quests`
  on a scratch copy of a clean v83 Quest.wz, read back: 186 live kill counts and 190 live item counts each point at
  the right Check.img/1 entry, 2,817 stock quests kept, 0 problems.
- Full split suite: 14,706 / 0 / 37, PASS (section 7).

## 5. Live check after deploy (needs a client re-export)
The server change works with today's clients (the Helper keeps counting from the record). The new quest-window text
needs Quest.wz re-exported into the client, which the Orbis/El Nath/Ludibrium (30197-30279) and Ereve/Rien
(30280-30284) quests need anyway:
1. Re-export and ship Quest.wz: MapleEdit `living_world_quests.py`, deploy Quest.wz (and Etc.wz if changed) from the
   dashboard and `python patch-server/gen_manifest.py --root "<Game Files>"`; or `tools\living-world-client.bat
   "<client folder>"` for a player's own client. Restart the server with the new jar.
2. Accept Edric's "The Watch" (El Nath, level 50+). The quest window's In Progress entry reads the giver line, Edric's
   accept line and `Defeat Hector: 0 / 30`. Kill hectors on 211030000 (a GM `!killall` there credits kills): the
   window and the Quest Helper move together; relog keeps the number.
3. Fenna's "The Pig Problem" (Henesys, level 10+): `Defeat Pig: n / 20`, then `Defeat Ribbon Pig: n / 10` counts in
   its own line while the first stays 20 / 20.
4. A collect step (Sigrun's "The Cave of Trial" teeth, `/ 15`, or Corvin's "The Night Log", `/ 10`): the count
   follows pickups.
5. A stock kill quest (e.g. 1037, Snail x10) still counts, and a Resident kill shows no extra window or pop-up.
6. Check the line breaks render as breaks (stock form); if the client printed `\n` literally, `BREAK` in
   QuestLogExportMain is the one place to change.

## 6. User decision (2026-10-25)
The In Progress text shows the giver's accept line between the giver's name and the step list (chosen over the step
list alone).

## 7. Notes and limits
- A step with several monsters ("and the like") counts under its first monster's name: the ledger counts any of them
  toward one total and the record has one slot for it.
- Items count the raw inventory (as the Helper always has); the ledger leaves out items the giver handed over in the
  provenance window, so in that rare case the window can read higher than the ledger.
- Content that changes a quest's counted monsters needs a client re-export: an old Quest.wz names the old slots.
- Full split suite on 654a54bff (`tools\suite\run-split-suite.ps1`, 2026-10-25): capabilities 6,215 / 0 / 1, agents
  2,376 / 0 / 21, rest 6,115 / 0 / 15 = 14,706 tests, 0 failures, 37 skipped, PASS (baseline 21eb70855: 14,692 / 0 /
  37; +14 new tests). Min free commit 2.26 GB.

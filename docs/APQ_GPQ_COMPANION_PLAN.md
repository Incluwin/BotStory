# Companions in the Amorian Challenge (APQ) and Sharenian (GPQ)

Written 2026-10-25. Exploration only: nothing here is built. Every fact was read from this server's scripts, WZ data,
drop tables and Java on `feat/companion-manager` 087948862, not from GMS guides. File references are to that tree.

## Result

- **No Agent code knows either quest today.** `AgentPartyQuestCatalog` has no `apq`/`gpq` definition, there is no
  stage engine, no desk, no route to them. Each needs what LMPQ and PPQ got in their Steps 1-6: definition, session,
  coordinator, admission, termination, GM harness, companion play, voice, member verdicts, pace profile, entrance and
  NPC hook. APQ alone is about the size of LMPQ plus a boss fight (LMPQ is 24 files, 5,067 lines).
- **APQ's stock rules lock companions out three ways** (`scripts/event/AmoriaPQ.js:27-28,95-127`): exactly 6 members,
  **every member married** (`onlyMarriedPlayers = true`; `Character.isMarried()` needs a wedding ring and a partner),
  and at least one of each gender. On top, each member hands Amos the Strong an **Entrance Ticket** (4031592) to reach
  the entrance (`scripts/npc/9201043.js:49-70`). No bot is married, and no bot code touches marriage.
- **APQ's stage puzzles suit companions.** Stage 2 (5 on 3 ropes) and stage 3 (5 on 9 platforms) are combination
  puzzles with hints, so companions can deduce the answer from the hints the way players do, not walk a fixed list
  (KPQ's `AgentKpqCombinationOrder` walks every combination with no hints). Simulated over the stock generators: stage
  2 averages 3.2 tries (never more than 5 of its 7) when the combination is solvable; stage 3 averages 5.0 tries
  (worst 7).
- **The stock stage-2 generator is broken a third of the time** (`scripts/npc/9201044.js:71-90`): it places a random
  number of people (0-5), while Amos counts only exactly five on the ropes (line 203). 81 of its 243 draws can never
  be answered; the party burns 7 tries, kills 30 spawned mobs, and gets a new draw.
- **Nobody can tap out of APQ.** The event needs all 6 to the end: a revive, a leave or a disconnect below 6 ends the
  run for everyone (`AmoriaPQ.js:230-266`). So a downed companion must stay down where it fell (the CWKPQ hold), and
  stages 1 and 2 cannot finish with a companion down (stage 1 needs both gender rooms cleared, stage 2 needs five bodies
  on the ropes while the leader talks).
- **APQ has a hard 4-minute stage.** Handing Amos the 50 passes restarts the timer at 4 minutes (if 5 or more were
  left) for stage 5: open 7 gates (4 hits each) along a 17,000 px corridor and gather alive by Amos at the far end, or
  the whole run ends (`scripts/npc/9201045.js:113-157`, `AmoriaPQ.js:209-215`). At base walking speed the corridor
  alone is about 140 s, so this is the stage to probe offline first.
- **The boss is big for a Lv40 quest:** Geist Balrog in three phases, 100,000 / 300,000 / 1,500,000 HP (Lv80 / 100 /
  95), the last with weapon and magic immunity and dispel (Mob.wz 9400536 -> 9400537 -> 9400514). Companions should be
  Lv70+ with boss gear for the fight to last minutes, not to wipe.
- **Amoria is outside the bots' world map.** No travel leg crosses Thomas Swift (9201022, Henesys <-> Amoria), and
  Amoria (680000000-680010100) and Purplewood/Amos' Training Ground (670000100-670010000) are in no route group of
  `tools/game-catalog/Export-WorldPortalRouteGraph.ps1`. Without that, a companion cannot be called to the desk.
- **GPQ is about twice APQ** and harsher (Part B): guild-only entry, air that kills without a one-per-person rock,
  a mandatory Lv10-30 member, a rock sacrificed at the boss door, items that never respawn and expire in 180 s, and
  Ergoth (Lv115, 1.7M HP, deadly attack, both immunities). It is built after APQ.

## Decisions taken (user, 2026-10-25)

1. **Married rule: kept.** All six must be married, as stock. Bots get married as **lore couples**: some companions,
   Residents and citizens are written as already married in their content and get their wedding rings on load (no
   ceremony). A companion may be married to another companion, to a Resident or citizen, or to its owner. The wedding
   work is **its own session and branch**; this branch tests APQ with test characters married by its GM harness.
   Consequence: a desk lineup needs six married members, the human leader included; an all-couple six gets the
   couples' vault (670010750, the cape).
2. **Tickets: earned.** Stock for everyone; companions loot Lip Lock Keys, trade 10 at Amos the Strong, and "<name>
   get your ticket" sends one hunting in Purplewood (keystone errand shape). The desk names who has no ticket.
3. **Deaths: stock.** A downed companion respawns at the exit and the run ends for everyone (no hold). The desk and
   lineup work to make deaths rare (levels, potion floors, resting) and the parting line names who went down.
4. **Stage-2 generator: fixed** inside 9201044.js so it always places exactly five; nothing else in the script moves.
5. **GPQ guild rule: companions exempt.** Only the humans share the guild (the registrant still a master or Jr.
   Master); a human's companions may join that guild's GPQ at Shuang without a guild of their own. A script-side
   change in `GuildQuest.js` `getEligibleParty` and Shuang's `findLobby`, through one Java hook that answers "this
   character's owner's guild" for an owned companion; citizen guilds are untouched.
6. **GPQ size: 8-12 with the bench.** The owner, his party's companions, and benched companions who join at Shuang
   one by one. The desk fills the roles (a Lv10-30 member, a Dark Sight thief, a magician, a ranged attacker, a
   jumper, both damage kinds) and asks for at least 8, so one loss before the boss door is survivable.
7. **GPQ stage 3: the fountain says it aloud.** One `cm.mapMessage` per verdict line in 9040006.js, the way Amos
   already speaks in APQ; companions read it like any player there.

## Questions for you (all answered; kept for the record)

**APQ**

1. **The "all married" rule.** (a, recommended) Drop it for everyone: `onlyMarriedPlayers = false`, one line. Keep
   "at least one of each gender" (stage 1 has a girls' room and a boys' room and both must be cleared), and keep the
   couples' vault (670010750, a cape) for an all-couple party as stock does. (b) Drop it only for desk-admitted
   companion runs; parties of humans must still all be married. (c) Keep it: companions would have to marry (no bot
   weddings exist; a feature of its own).
2. **Entrance tickets.** (a, recommended) Stock: every member gives Amos the Strong a ticket, traded for 10 Lip Lock
   Keys (4031593), which Indigo Eyes and Crystal Boars drop in Purplewood Forest 1 and 2 (4% x drop rate 3, about 1 in
   8; ~80 kills per ticket) and in APQ's own stage-2 penalty waves. Companions loot keys when out with their owner,
   trade them at Amos the Strong, and an owner can send one: "<name> get your ticket" (the keystone errand's shape).
   The desk names who has no ticket. (b) Companions go in free; only humans need tickets. (c) No tickets for anyone.
3. **Downed companions.** Stock APQ ends for everyone when one member leaves, so a companion cannot tap out.
   (a, recommended) A companion that goes down stays down where it fell until the clear or the end (CWKPQ's
   HOLD_UNTIL_CLEAR); the run goes on where the stage does not need its body (stages 3-6). In stages 1-2 the run then
   fails in a companion's words. The desk and lineup aim to make deaths rare: Lv70+ crews for the boss, potion
   floors, resting. (b) Stock: a downed companion respawns at the exit and the run ends.
4. **The broken stage-2 combination.** (a, recommended) Fix the generator inside 9201044.js so it always places
   exactly five (as CWKPQ's two script faults were fixed, scripts only). (b) Keep stock: companions notice that no
   arrangement fits the hints, say so, and burn the remaining tries to force a new draw.

**GPQ**

5. Guild membership: (a, recommended) companions join their owner's guild (answer the invite and the creation check
   like players); (b) **chosen:** companions exempt from the guild rule; (c) citizen guild masters promote a human.
6. Size: (a, recommended and **chosen**) 8-12 with bench companions; (b) six; (c) up to 30.
7. Stage-3 verdict: (a, recommended and **chosen**) the fountain speaks it to the map; (b) companions read the event
   invisibly; (c) the leader types it.

## Words used

- **Leader**: the human party leader (the event leader). Only he may talk to Amos, the Glimmer Man and Shuang for the
  stage checks (`isEventLeader`).
- **Companion run**: a run admitted from the desk for a human leader and his companions (`companionAdopted`).
- **Hold**: a downed companion kept in the event where it fell, not respawned.

## Part A: the Amorian Challenge

### A1. Entry on this server

| Step | Where | What | Source |
| --- | --- | --- | --- |
| Town | Amoria 680000000 | from Henesys by Thomas Swift 9201022 (free, both ways) | `scripts/npc/9201022.js` |
| Road | 680000000 in000 -> 680010000 -> 680010100 -> Purplewood Forest 1 670000100 -> 2 670000200 -> Amos' Training Ground 670010000 | plain portals; the forests hold 11 Indigo Eyes, 11 Crystal Boars, 6 9400509 each | Map.wz |
| Gate | Amos the Strong 9201043 (670010000, x 2043) | gives 1 Entrance Ticket 4031592 for the warp to 670010100; trades 10 Lip Lock Keys 4031593 for a ticket | `9201043.js:49-70` |
| Keys | Indigo Eye 9400515, Crystal Boar 9400516 | 4031593 at 40,000/1,000,000 (x drop rate 3) | `152-drop-data.sql:14351,14557` |
| Desk | Amos 9201048 (670010100, x 926 y 2095) | the leader registers the party; one lobby per channel | `9201048.js:71-73`, `AmoriaPQ.js:40` |
| Rules | | 6 exactly, Lv40-255, all on 670010100, at least one male and one female (`mask == 3`), all married | `AmoriaPQ.js:27-28,95-127` |
| Timer | | 75 minutes | `AmoriaPQ.js:38` |
| Exit | 670011000 | the Glimmer Man 9201047 sends you to 670010000 | `9201047.js` |

Clear-stage EXP: 2,000 / 4,000 / 6,000 / 8,000 / 9,000 / 11,000 (`AmoriaPQ.js:91`). The real reward is the bonus
stage. Exclusive items (taken away on leaving): 4031594-4031597.

### A2. The stages

| # | Map | Objective | What players physically do | Who |
| --- | --- | --- | --- | --- |
| 1 | 670010200 Magik Mirror | the mirror's shard | Girls take female00 (x -434) to the left rooms, boys male00 (x 415) to the right rooms (`gendergo.js`); kill every mob on the map (79 Lv38-40 mobs: Indigo Eye PQ 9400533, Crystal Boar PQ 9400534, Magik Fierry A PQ 9400535; spawn points respawn 300 s after a kill). With the map empty the leader talks to the Glimmer Man (x 103 y 783): Magik Fierry B 9400518 appears at (-245, 810) and drops the Wing Hammer 4031596. Drop the hammer on the Magik Mirror (reactor 6702000 at (0,152), item box x -144..144) -> it drops the shard 4031595. The leader hands the shard to the Glimmer Man, then talks to Amos (x -228 y -216): one of go00/go01/go02 opens at random. | rooms by gender; leader talks |
| 2 | 670010300/301/302 Heart Strings | the rope weights | Three gates on the floor (x -223/-45/134) each lead to the top of one rope; three rope areas (x -672..-585, -92..2, 495..585). Exactly five members hang in rope areas while the leader talks to Amos (x 357). Amos answers in map chat: "All ropes weigh differently" (no rope count right) or "One rope weigh the same". 7 misses: 30 mobs spawn (Indigo Eye, Crystal Boar, Magik Fierry A: the keys drop here too), the combination is redrawn. Mobs must be dead before a try. | 5 on ropes, leader talks |
| 3 | 670010400 Twisted Switcher | the platform pattern | Nine platforms (areas 0-8: four upper at y ~45, five lower at y ~200, x 1333-2076). Five members, one per platform; the leader talks to Amos (x 1042). A miss spawns as many slimes (9400519-22) as members stood right ("count the slimes"); kill them and try again; the pattern stays. | 5 on platforms, leader talks |
| 4 | 670010500 Last Man Standing | 50 passes 4031597 | 57 mobs (Curse Eye GL, Zombie Lupin GL, Toy Trojan GL, Lv35-40) each drop one pass. The leader gives Amos (x 14) 50; if 5+ minutes are left the timer restarts at 4 minutes (the rest is banked). | anyone farms, leader hands in |
| 5 | 670010600 Fluttering Hearts | the 4-minute run | sp at x -8535; seven gates (reactor 6702001, 4 hits each) at x -8189, -6387, -4590, -901, 3596, 6300, 7198; a gate's portal (gtXXWP, `apqDoor.js`) passes only an open gate. Everyone alive gathers in area 2 (x 8536-9018) by Amos (x 8916) with every gate open; the leader talks; the banked time comes back. The shortcut gm00 -> gm01 is refused ("you reached here by teleporting"). Out of time = the run ends. | all run, leader talks |
| 6 | 670010700 Love Hurts | Geist Balrog | Each member talks to Amos (x 224) standing in area 0 to be sent to the arena (st01). Geist Balrog 9400536 (100k) -> 9400537 (300k) -> 9400514 (1.5M, skills 140/141 weapon/magic immunity, 127 dispel). Its last phase drops 4031594; the leader gives it to Amos: clear. | all fight, leader hands in |
| 7 | 670010800 Amos' Vault | bonus, 1 min | Leader talks to Amos again: 95 boxes (6702003-12) spray Wish Tickets by tier and items. All-couple party: 670010750 instead, 2 min, 35 passes from box mobs for a cape (first claimer). | all |

### A3. What we reuse and what is missing

Reused as they are: the desk (PqEntrance, LineupPlan, CompanionPartyQuestService, DeskVoice), the engagement/lobby
admission shape (`AgentLmpqAdmissionService.admit`), member verdicts and parting (`AgentLmpqMemberVerdicts`,
`AgentLmpqParting`), the hand-over at the leader's feet (`AgentLmpqHandOver`: coupons there, passes and the shard
here), item drops onto an item reactor (OPQ's statue pieces: `AgentOpqDefinition.STATUE_SCAR_DROP`), reactor hits
(gates), the boss track's fight and HOLD_UNTIL_CLEAR death mode (CWKPQ C3), the pace estimator (`pace.*`), restock
standards (`AgentSupplyReadiness.PARTY_QUEST_STANDARDS`), the keystone errand's hunt-and-hand-in shape for tickets.

Missing: everything APQ-specific below, the route to Amoria, and two solvers (ropes, platforms).

### A4. The build, in steps

Each step ends with targeted tests; the full suite runs before the branch is reported.

- **A0. Offline probes (no game client).** Movement/graph replays on the real maps, as CWKPQ C0 did:
  1. 670010200: each gender room from the middle floor and back; whether ma01/fe01 (plain portals into the rooms)
     bypass the gender check; whether the 300 s respawn refills a room before two companions empty it.
  2. 670010300/301/302: floor -> gate -> rope top -> inside each rope area, and from one rope to another.
  3. 670010400: each platform from the floor, standing inside its 10 px area; the leader clicking Amos from a
     platform (the server has no talk distance; the client needs Amos on screen).
  4. 670010600: sp -> area 2 through the seven gates at base speed and with Haste: under 240 s or not.
  5. 670010700: area 0 -> st01, the arena's in00-in15 teleports, reach to the boss.
  6. A bot-dropped Wing Hammer triggers 6702000 (5 s delay, `MapleMap.activateItemReactors`).
  7. A fight simulation against the three Geist Balrog phases for a Lv55 and a Lv70 crew.
- **A1. Route.** An `amoria` group in the route-graph exporter (680000000-680010100 and 670000100-670010000; the PQ
  maps 670010100+ stay out, entered only by the gate NPC) and a regenerated `world-portal-route-graph.json`; travel
  legs henesys-to-amoria / amoria-to-henesys (Thomas Swift); the gate leg (Amos the Strong, one ticket). No Amoria
  NPC has a shop in `101-shops-data.sql`, so the approach store is Henesys (already in the supplier catalog), the
  BossEntrance `approachMapId` idea: companions top up there on the way, and a trip out of the entrance is held.
- **A2. Tickets** (as Question 2 decides): keys looted like any drop; the trade at Amos the Strong; the errand "get
  your ticket" hunting Purplewood; the economy never sells keys or tickets.
- **A3. Engine (other PQs' Step 1/1b).** `apq` in `AgentPartyQuestCatalog` (AmoriaPQ, recruit 670010100, entry
  670010200, clear 670010800, exit 670011000, Lv40-255, 6-6, 4,500,000 ms); `AgentApqDefinition` (maps, NPCs, areas,
  reactors, items, mobs, pinned to Map.wz/Mob.wz/scripts by tests); session, member state, registry, coordinator
  (phases per stage), runtime and watchdog, admission, termination; `!apqtest party prod` (GM harness like
  `!lmpqtest`); phase/stage log lines (`APQ phase:`, `APQ stage:`).
- **A4. Companion play, stage by stage.**
  - S1: each companion clears its own gender's rooms (balanced between the two sides); "the rooms are clear, talk to
    the glimmer man"; kill Magik Fierry B; whoever picks up the hammer drops it on the mirror; the shard goes to the
    leader's feet; "give the glimmer man the shard, then amos". The open portal is read from the map (portal state),
    and companions go through after the leader.
  - S2: a deduction solver over the arrangements of five on three ropes, fed by Amos's map message; few movers per
    try; "ready, ask amos". After 7 misses, fight the wave (and loot keys), then start over on the new draw.
  - S3: a deduction solver over 5 of 9 platforms, fed by counting the slimes that appear after a miss; kill them;
    next try. If a companion is down, the leader takes a platform Amos is visible from.
  - S4: farm 50 passes, hand them over at the leader's feet in batches; before he hands in, everyone tops up and the
    companions say what comes next ("after this we get four minutes. ready when you are").
  - S5: companions run ahead and open each gate, pass it, and wait for the leader in area 2; anyone behind is called
    by name; nobody takes the shortcut.
  - S6: each companion talks to Amos in area 0 and fights in the arena (boss track fight, immunity respected); the
    drop goes to the leader's feet; "give amos the balrog's mark".
  - Bonus: break boxes; drops are left for the humans for a short claim time, then free.
  - Party chat / map chat voice in lower case (`AgentApqDialogue`, the PPQ lint rules).
- **A5. Deaths and endings** (decision 3, stock): a companion that goes down respawns at the exit and the event ends
  for all; the run parts once in a companion's words (WENT_DOWN naming who fell, DEFEATED, TIMEOUT, LEFT), nothing
  after the clear. Prevention is the work: rest below an HP fraction away from mobs, potion floors per stage, the
  boss fought only by members its touch does not one-shot (the CWKPQ/boss-track reach and keep-out rules), the
  desk's FRAGILE caution.
- **A5b. Married members** (decision 1): admission and the desk check `isMarried()` per member with the stock words;
  `!apqtest party prod married` marries the test party's members to each other for the run's tests only (stock ring
  creation, `RingActionHandler.giveMarriageRings`), never the lore-couple writer, which the wedding branch owns.
- **A6. Clear time.** `AgentApqPaceProfile`: rooms (kill quota from Map.wz), fierry+mirror, handin1, ropes (solver
  tries x arrange), platforms (tries x arrange + slimes), passes (quota 50), handin4, gates (walk, bounded by 240 s),
  boss (HP from Mob.wz), bonus; timer 75 min. Priors marked as guesses until the first live runs.
- **A7. Desk.** `ApqEntrance` at Amos 9201048 (SeatRules 6/6/6), `AmosLines` voice, the gender rule (refuse without
  both, prefer at least two of each), every member married (refuse with the stock words), tickets: the lineup forms
  at Amos the Strong's gate, where a companion without a ticket is named, and those with one go through with the
  leader (decision 2), dps proxies 9400533-35 and the boss, the
  hook in 9201048.js before `getEligibleParty` with the byte-identical-to-stock test, extras' spots and keep-clear box
  from Map.wz 670010100, restock standard `apq` (boss-length run: proposed 100 HP / 100 MP).
- **A8. Switch.** `apq` in `CompanionPqKeys` and `living_world.COMPANION_PQ_ENABLED_KEYS`. The shipped text is pinned
  in `LivingWorldConfigCompanionPqTest.java:73,86` (both move with the flip; grep src/test again before it).
  Regenerate `server-knowledge.json` (KnowledgeLintTest/KnowledgeFileFreshnessTest go red after any scripts/npc edit).

### A5. Live checks (after deploy; for the OPQ live-run session)

- L0: deploy; Amos's stock click unchanged with `apq` off; the pace warm logs no WARN.
- L1: a bench call to Amoria: companions travel Henesys -> Thomas Swift -> Amoria -> Purplewood -> Amos the Strong.
- L2: tickets: a companion with 10 keys trades them; "get your ticket" hunts and comes back with one.
- L3: Amos's window (portrait, title "<Party Quest: Amorian Challenge>", six seats, the gender line, ticket lines).
- L4: `!apqtest party prod` full run with a Lv70 crew of both genders; zero `APQ session failure:` lines.
- L5: stage 2 hints read from Amos's lines, tries counted; stage 3 slime counts; stage 5 under 4 minutes.
- L6: Geist Balrog: fight lasts minutes, immunity respected, the drop at the leader's feet.
- L7: a desk run end to end; `pq_run_pace` 'apq' row; companion_performance rows.
- L8: `!hpmp <companion> 0` in any stage: the event ends, one WENT_DOWN parting naming it, a clean release.
- L8b: (after the wedding branch) a lore-married lineup at Amos; an all-couple six reaches the couples' vault.
- L9: the other desks unchanged.

## Part B: Sharenian (GPQ)

The full stage-by-stage read (maps, reactors, item counts, timers, death rules, Java hooks, guild code) is
`docs/apq-gpq/GPQ_MECHANICS.md`. The short of it:

### B1. What GPQ asks of a party on this server

- **Guild only.** A guild master or Jr. Master (rank 1-2) queues the guild at Shuang 9040000 (101030104). When the
  guild is called, only that registrant is warped in and becomes the instance leader for good; for 3 minutes any
  member of the same guild standing at Shuang may join (a party leader brings their same-guild party). At the end of
  the 3 minutes fewer than 6 registered ends it. `maxPlayers = 30` is never enforced (`GuildQuest.js:27-28,201-222`;
  `npc/9040000.js`).
- **Every stage NPC answers only the instance leader** (statues, fountain, Sharen III, the Rubian turn-in).
- **Air that kills**: every stage map has decHP 30000 every 5 s unless the Protector Rock 1032033 (earring, Lv10+,
  one per person, no spares) is equipped. 25 rock reactors in 990000100, 2 refill rocks in 990000200.
- **Hard roles**: a Lv10-30 member is mandatory (only it can fetch Sharen III's Crown, 990000640/641); one member must
  give up its own rock at the King's Corridor gate and die in the air (held dead until the gate sets canRevive, then
  revived in 990000200); crossing 19 invincible Dark Muscle Stones (Lv200, body damage) carrying the Rusty Key wants
  Dark Sight; five jump maps forbid movement skills; Ergoth and the stage-3 knights cast weapon and magic immunity,
  so both damage kinds are needed.
- **Softlocks**: Key, Rusty Key, the four spears and every Mark of Evil come from mobs or reactors that never
  respawn, in maps where drops expire after 180 s, and the Mark gates take exact stack sizes (9, 8, 20).
- **Deaths**: before the kingate opens a death that is revived removes the member; with 6 registered any single loss
  ends the run, with 7 one loss is survivable. After the gate a death revives in 990000200. All dead in the boss room
  ends the run. Today a bot's death falls through to the town respawn (a departure); GPQ needs its own death gate.
- **Stage puzzles**: stage 1 is "repeat the statues' pattern" (4, 5, then 6 statues; one stray hit resets to phase
  1); stage 3 is Mastermind (4 slots x 4 offerings, 7 tries) whose answer the fountain gives **only in the leader's
  dialog**.
- **Boss**: Ergoth 9300028, Lv115, 1,700,000 HP, undead, stationary, deadly attack (to 1 HP), heal, weapon/magic
  immunity, summons; four boss-flagged statues that need not die.
- **Rewards**: guild points (15/20/25/30 per stage, boss 1000 - 10/min, min 100), the 30-s bonus boxes, Ergoth's
  drops; no event EXP or items; BotStory's Perfect Pitch and living-world PQ credit.

### B2. Where the bots stand

- **Companions have no guild code at all**; nothing lets a bot accept a guild invite or the guild-creation match
  check. The 24 citizen guilds (`SocialFabric`) found and recruit citizens only, never promote anyone to Jr. Master,
  and invite humans at rank 5 (who then cannot register GPQ). A hired citizen keeps its citizen guild.
- Reused: the APQ engine shape (built first), item drops onto item reactors, reactor hits, the boss fight with
  immunity gates (`AgentBossTargetGate`), the Mastermind-style solver idea from APQ stage 3, hand-over at the
  leader's feet, the boss death gate's `revivePlayer` + `respawnInto`.
- Missing besides the engine: guild membership for companions, joining at Shuang (each member talks to her), the rock
  pickup and equip, hidden same-map teleport mazes in navigation (990000440, 631, 600), the stage-1 display watch
  (statue hits broadcast to the map), the sacrifice and held death, the Lv10-30 member's own route, exact-count Mark
  hand-offs.

### B3. Size and order

GPQ is about twice APQ: eleven sub-rooms, each a small job, plus a boss above anything APQ has. It is built only after
APQ clears offline, in the same steps (probe, engine, play, deaths, pace, desk at Shuang, switch), on this branch or a
follow-up branch if APQ is ready to merge first.

## Stock facts worth knowing

- Amos counts bodies by position only (`9201044.js:192-200`): a member standing in two areas counts in the first.
- Stage 2's hint is binary: with five on three ropes, two right ropes force the third.
- Stage 3's combination is never redrawn; stage 2's is redrawn after 7 misses.
- Stage 5's check skips dead members (`9201045.js:136-141`) but needs the team on one map (`isEventTeamTogether`).
- Wish Ticket tiers by level: <50 yellow 4031543, <120 green 4031544, 120+ blue 4031545 (`9201042.js`, Mr. Sandman
  in Amoria trades them for potions and gear).

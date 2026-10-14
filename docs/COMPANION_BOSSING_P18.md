# P18: Horntail

> Built 2026-10-12 on branch `feat/companion-bossing-p18`, on top of P17. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P18. Not deployed, not live-tested yet. The `horntail` key ships off.

## Result

An owner can now sign their companions up for Horntail at the Mark of the Squad, as at Adobis for Zakum. The run
covers all three maps of the fight: the two pre-head maps and the altar. A companion that dies comes to in Leafre, is
moved back to the cave entrance, and signs the Mark again. With `horntail` off (as shipped), the Mark's window and the
fight are stock.

## What the player sees (with the key on)

Example: `horntail` on, the owner at the Mark (240050400) with 5 companions.

- The Mark offers the owner "My party will register too." after they create or join the expedition, and "Call allies
  from my bench..." when `COMPANION_BOSS_ALLY_TYPES` also lists `horntail`. The expedition needs 6 or more.
- The Start puts everyone on 240060000. The companions fight the left head there. Once it falls, they walk to the
  portal and on to 240060100, then to the altar (240060200) once the right head falls.
- On the altar the companions wait until the owner wakes Horntail at the reactor. Then ranged and magic companions
  take the heads, and melee companions take the legs and the tail. They never hit the dead heads or the Horntail HP
  holder (8810010-8810018).
- A companion that dies says one line ("ugh, i'm down. heading back from leafre!") and stands in Leafre for 5 s. If it
  is short of Horntail's standard, it first buys at the Leafre store, as it would with its owner beside it (follow-up
  7a6244dba). Then it is moved to the cave entrance, walks up to the Mark and talks to it. The Mark warps it back to 240060000, and it walks through
  to the altar. If it is not back in 10 minutes, or the fight ends first, it waits outside instead.
- After the clear (or before it, if the owner leaves), the companions leave by the Crystal of Roots on their map.
- On the way in, a short companion buys at the Leafre store when the owner passes Leafre. At the cave entrance it makes
  no shop trip: no route leads out.

## Keys and standards

| Key | Boss | Recruit map, fight maps | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `horntail` | Horntail | 240050400; 240060000, 240060100, 240060200 | 600/600, 4800 |

600/600 with 4800 projectiles is the plan's starting standard: it fills the 18-stack budget. The scaled standard
(850/850, 7000) waits for Questions 5 and 6.

## Changes from the plan

1. **Restock in Leafre on re-entry came in a follow-up commit.** The shared restock rule lets a following companion
   restock only on its owner's map (AgentSupplyReadinessRuntime.free), and its owner is in the cave. P18 shipped
   without the restock. The user asked for it on 2026-10-12, and 7a6244dba (branch
   `feat/companion-bossing-reentry-restock`, on P20) adds it. See "Re-entry restock" below.
2. **No `BOSS_IDS` entries.** Every Horntail part and add carries the boss flag, so `HORNTAIL` in
   `AgentBossScope.EXPEDITION_TYPES` guards them all.
3. **The intro mobs are never-targets.** 8810024-8810026 ("Summon Horntail") live for 1 s while Horntail rises. Not in
   the plan.
4. **Stage maps are general.** `boss-rules.json` gains `stageMaps` and `stagePortal`. A run adopts and fights a member
   on any map of its fight (`AgentBossRunDefinition.fightMap`). P20 reuses this for Pink Bean's resurrection map.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts `horntail`.

`agent-engine.yaml`:

- `AgentBossScope.EXPEDITION_TYPES` changes from `ZAKUM,SCARGA` to `ZAKUM,SCARGA,HORNTAIL`. It acts only when an Agent
  hits a part in a Horntail event, and Agents get there only with the key on.
- `horntail=600/600` joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `horntail=4800` joins `AMMO_STANDARDS`.
- New: `AgentBossDeathWalk.REENTRY_WARP_AFTER_MS: '5000'` and `AgentBossPriorityFight.STAGE_PORTAL_EVERY_MS: '2000'`.

boss-rules.json: the `horntail` row gains `targetPriority` (pre-heads, heads A-C, hands, wings, legs, tail),
`meleePriority` (pre-heads, legs, tail, hands, wings, heads), `adds` 8810019-8810023, the intro mobs in `neverTarget`,
`stageMaps` 240060000 and 240060100, `stagePortal` next00 and `reentryWarpFrom` 240000000.

No database change.

## How it is built

- **The Mark's script.** It was Adobis's stock script without the leader item check (the Slate and Keroben ask for the
  item on the way in). It takes the five P8 hooks with the key `horntail`. `MarkOfTheSquadHookScriptTest` checks that
  it stays Adobis's twin.
- **Run row.** `AgentBossRunCatalog.HORNTAIL`: expedition HORNTAIL, no join choices, the priority fight, the Crystal of
  Roots (2083002) before and after the clear, owner leaving takes the party, `DeathMode.REENTER`.
- **Entrance row.** `BossEntrance.HORNTAIL`: companions wait beside the Mark (898,260), approach store Leafre (store
  NPC 2080001 on 240000002), allies in at `sp` (where the Slate lands players) and out by `st00` beside the Crystal of
  Roots, trips out of the recruit map held.
- **Three maps.** `AgentBossRunDefinition.fightMap` is true for the battle map and the rules' stage maps. The session
  adopts, fights and walks out members on any of them, and the client-load floor counts attacks there.
  `AgentBossPriorityFight`: on a stage map with no live monster but never-targets, a fighter walks to `stagePortal` and
  takes it at most every `STAGE_PORTAL_EVERY_MS` (hontale_BR.js and its seal check run). A melee fighter reads
  `meleePriority`.
- **Re-entry.** `AgentBossDeathWalk`: a REENTERING member whose rules name `reentryWarpFrom` walks to that map instead of
  the unreachable recruit map. There it stands `REENTRY_WARP_AFTER_MS`, is moved to the recruit map
  (`AgentBossRunPorts.moveOut`), then walks up to the Mark. The run's tick talks it back in as for Scarga.
- **Lines.** `BossRunLines.BY_BOSS` gives Horntail its own down, re-enter, refusal, shop, ally and gg lines.
- **Re-entry restock (7a6244dba).** `AgentBossDeathWalk.reentryRestockKey` names the boss key while a REENTERING
  member stands on its `reentryWarpFrom` map (only Horntail names one). `CompanionRestock.standing` then gives it that
  boss's standard, though the run holds it. `AgentSupplyReadinessRuntime.free` lets that member shop with its owner on
  another map; every other following companion still needs its owner on its map. A companion's restock runs only in the
  live tick (objective supervision), after the preflight step where the walk runs, so a consumed walk tick would skip
  it (the first version did, found by the deploy review). The walk leaves the tick to the restock while it still decides
  (at most `AgentBossRunSession.VERDICT_WAIT_MS`, 12 s, of the walk) and while a trip of its own is under way, on the
  store map too, for potions or ammo. The move in waits meanwhile. The Leafre store map (240000002) is one portal from Leafre, well inside `REENTER_TIMEOUT_MS`
  (5 min).

## Tests

- `MarkOfTheSquadHookScriptTest`: the Mark equals Adobis with the Mark's names, no leader item, and the key `horntail`.
- `AgentBossStageFightTest`: the shipped rules; a member fights the head on a pre-head map; once it falls, the member
  walks to next00 and takes it at most every interval; the second pre-head map too; on the altar ranged members take
  head A and melee members the legs; nothing up there means hold; after the clear, hold.
- `AgentBossDeathWalkTest`: a REENTERING Horntail member stands in Leafre, is moved once to 240050400 and walks up to
  the Mark; away from Leafre it walks to Leafre. 7a6244dba: in Leafre it keeps Horntail's standard (not on the store
  map, not in another run, not once INSIDE). While its restock decides, and during its trip (an ammo trip on the store
  map too), the walk leaves the tick to the live tick and the move waits. A source-order test pins the tick order this
  relies on (supply maintenance before follow sync).
- `AgentSupplyReadinessRuntimeTest` (7a6244dba): with its owner on another map, a following companion makes no trip
  unless it walks back into a boss run.
- `CompanionRestockTest` (7a6244dba): a companion walking back in keeps Horntail's standard though the run holds it;
  a party quest's engagement still comes first.
- `AgentBossRunPartyEventTest`: a member on 240060000 is adopted and fights; one on the recruit map is not.
- `AgentPartyBossesTest`: the Horntail row against HorntailBattle.js.
- Updated pins: keys, catalog list, death modes, shipped scope types, standards; the "unlisted boss" examples move to
  Pink Bean and Chaos Horntail.

Full suite on ebba44486: 13,454 tests, 4 failures, 37 skipped (11:04 min). The 4 failures were the standards pins
in the two supply tests; 989eea669 fixes them, and the supply tests pass (120).

Full suite on 7a6244dba (the re-entry restock, on P20): 13,471 tests, 0 failures, 37 skipped (11:49 min).

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `horntail`. For stage 2, set `COMPANION_BOSS_ALLY_TYPES` to include
   `horntail`. For stage 3, set `COMPANION_BOSS_OWNERS_MAX` to 2.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!resetboss <name> HORNTAIL` for each character.
4. Run `!item 4001086` as Bossfriend.
5. Run `!warp 240000000`. Check that short companions buy there.
6. From Bossfriend, run `!warp 240050400`.
7. Start the client memory gate, stage 1: owner plus 5.
8. Create the expedition at the Mark, register the party and start.
9. Kill both pre-heads. Check that the companions walk through to the altar.
10. Hit the reactor six times.
11. Check that the companions never hit 8810010-8810018, and that melee companions stay on the legs and tail.
12. Run `!hpmp Flint 0`. Check the `Boss death walk moved in` and `talks back in` lines, and that Flint reaches
    240060200.
13. Re-entry restock: let Flint fight until it drinks a potion. One potion below 600/600 makes it short. Run
    `!hpmp Flint 0`. Check that Flint walks to 240000002 and buys before the `Boss death walk moved in` line.
14. Never use `!killall`. It completes the fight.
15. Repeat with the bench allies as stage 2, then with ohboy as stage 3. For stage 3, run `!warphere ohboy` from
    Bossfriend on 240050400.

## Live check 2026-10-14 (partial, not cleared)

**Setup.** int/p9bh live (04:28 restart) with a temporary `horntail` in `COMPANION_BOSS_ENABLED_TYPES` and
`COMPANION_BOSS_ALLY_TYPES`, and `COMPANION_BOSS_OWNERS_MAX` 2: stages 1 and 3 in one run. Two owners, 12 members:

- ohboy (Lv200 Night Lord, GM 0, visible, expedition captain) with crew B: Brannock and Garrick (Heroes), Ilse
  (Bishop), Fenna (Bowmaster), Corvin (Dark Knight). All Lv200 with 10% kits and books from P17.
- Bossfriend (Lv200 Hero, hidden GM) with crew A: Thane (Hero), Ingrid (Dark Knight), Isolde (F/P Arch Mage), Sorrel
  (Marksman), Flint (Corsair). Raised from Lv70 3rd job to Lv200 4th job for this run (`!setlevel party 200`, then one
  job set each), with 10% kits, 3M mesos and 91 books (110 reads, 11 still failed after two re-gives).

A heal loop kept ohboy alive with Bossfriend's `!healplayer`. Video of ohboy's view, from the sign-up to his client's
crash: `docs/agents/evidence/video/p18-horntail-2026-10-14.mp4` (336 MB, not in git).

| Step | Result |
|---|---|
| 3-4 resets, leader item | Done. |
| 5 Leafre restock | **PASS.** Once Lv200, crew A cleared the level gate and all five bought up to 600/600 at the Leafre store (Sorrel 8,612 arrows, Flint 5,104 bullets). Garrick topped up to 600/600. |
| 6-8 cave, sign-up, start | **PASS.** Both crews arrived at 240050400 within seconds. ohboy created the expedition, and each owner's "My party will register too." enlisted five (0 refused). The start put all 12 on 240060000 at 04:55:04, and the boss log took an entry for each companion. |
| 9 pre-heads | **PASS (first head).** The left head (330M) fell in about 14.5 min with 10 companions: about 23M a minute against one target, hits of 25k-43k. All ten walked through next00 to 240060100 on their own by 05:10. There, with all 12, the right head lost 6% in 21 s (about 57M a minute). ohboy climbed the three ropes to next00 by script. |
| 12 death walk | **PASS, twice.** Ingrid went down on her own at 05:05:28 (potions 197/348; at 05:04:55 she had run low and found no reachable store). Flint by `!hpmp Flint 0` at 05:10:48. Each said `boss.reenter`, walked from Leafre, was moved to 240050400, talked to the Mark and was back on 240060000. Flint was back inside 66 s after going down and walked on to 240060100. |
| 13 re-entry restock | **PASS, twice.** Flint shopped before the move in: HP 573 -> 600, MP 351 -> 600, bullets 2,760 -> 5,760 (mesos 3.02M -> 0.95M), then `Boss death walk moved in`. Ingrid did the same. |
| 10-11 altar | Not reached. |
| Client memory gate | **FAIL.** ohboy's client (LAA, 4 GB address space) sat at 3,791 MB virtual in the cave entrance before the fight. It climbed to 4,081 MB on entering 240060100 with 12 characters, then crashed at 05:11:25 (connection reset). Private bytes were only about 1,070 MB. |

**After the crash.** Crew B despawned with their owner offline, as designed. Crew A all but stopped: the right head went
from about 92% (05:11) to 89.5% (05:19). No visible player stood on that map (Bossfriend was hidden on 240060000), and
the mirror logged nothing after 05:11, so nothing controlled the head. Isolde and Flint stood at the spawn point, and
Flint stayed at 100-160 HP for 8 minutes holding 600 potions without drinking.

**End.** At 05:20:10 I unhid Bossfriend to give the head a controller. The head killed him within 40 s, the run logged
`no standing human` at 05:20:50, all five left (`NO_HUMAN_LEFT`) and the run ended at 05:21:00 with `cleared=false`. This
was my driving mistake: I unhid the only human left without a heal loop for him.

0 ERROR lines in the run's log (cosmic-log-2026-10-14-2, 04:28-05:22).

## Open items

1. Restock in Leafre on re-entry: built in 7a6244dba. Live PASS on 2026-10-14 (Flint and Ingrid).
2. The scaled standard (850/850, 7000) waits for Questions 5 and 6. Live evidence: Ingrid (Dark Knight) used about
   400 HP potions on the left head alone and went down at 197.
3. Client memory (2026-10-14): 12 characters on Horntail exceed the client's 4 GB address space, while 5 companions are
   too slow to clear 2.75B HP inside 120 minutes. **Cause found, fix built, not shipped** (see "Follow-ups" below).
4. Companions on a fight map with no player watching all but stop (2026-10-14, crew A after ohboy's crash). **Fixed
   in d395c00c1** (see "Follow-ups").
5. Flint did not drink at 100-160 HP after his re-entry (2026-10-14). He stood on the same unwatched map as item 4, so
   the same fix should cover him; the next Horntail run confirms it. Corvin at 1 HP on Pianus (P16) had a player beside
   him and looks like a separate case (he had run out of potions).

## Follow-ups (2026-10-14)

**Unwatched fight maps (open item 4), d395c00c1 on `fix/boss-run-full-rate`.** An Agent on a map no player watches
drops from the 50 ms tick to the background cadence (250 ms, at background priority that can wait up to 2 s under
load). Every party quest holds its members at full rate (`AgentKpqFullRateSimulation` and its siblings); boss runs did
not. `AgentBossFullRateSimulation` now holds each member that stands in the run's event, lets go when it is out of the
event or the run, or the run ends, and never clears a requirement something else set. Tests: three in
`AgentBossRunSessionStartTest` (two fail on the old session). Full suite: 13,692 / 0 / 37.

**Client memory (open item 3), kaentake `fix/wz-large-archive-reads` 3612e71 (on `ship/2026-10-12-crew-window`).**
NameSpace.dll maps every WZ archive whole into the 32-bit address space when the client opens it. This client's
archives total 6.8 GB (Character.wz 1.9 GB, Map.wz 1.4 GB, Mob.wz 1.4 GB, Npc.wz 554 MB). Character.wz and Npc.wz were
mapped (2.5 GB); the rest found no room and were read from their files, which the game already handles. At the login
screen the stock client had 2,714 MB mapped and 591 MB free (largest free block 135 MB). The DLL now passes over the map
call for archives larger than 128 MB in the archive open only (the stream's checksum caller is untouched). Measured on a
test copy of the client: 235 MB mapped and 3,145 MB free at the login screen; 2,927 MB free in game; 1,532 MB peak with
Horntail summoned beside five Lv200 companions (the stock client idled at 3,791 MB). Characters and NPCs render
normally. Not shipped: the friends' Kaentake.dll changes only with the user's OK.

## Risks

- Unconfirmed: whether the crew clears every part within the 120-minute limit.
- The region decode past region 15 may be biased (G1).
- Mob mist does no server damage, which is an immersion gap.
- Potions may run out before the end, because the standard is capped by the Use tab.

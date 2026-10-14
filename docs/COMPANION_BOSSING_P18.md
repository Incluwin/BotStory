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
| `horntail` | Horntail | 240050400; 240060000, 240060100, 240060200 | 900/900, 4800 |

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
- `horntail=900/900` (600/600 until the touch-fence rerun, where Ingrid and both Bishops ran dry) joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `horntail=4800` joins `AMMO_STANDARDS`.
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

## Live check 2026-10-14, rerun (altar reached, not cleared)

**Setup.** int/p9bi live (10:13 restart: the full-rate hold d395c00c1) with the same temporary keys as the first run
(`horntail` in both lists, `COMPANION_BOSS_OWNERS_MAX` 2). Kaentake.dll b5d5b522 (`fix/wz-large-archive-reads`) shipped
to both client folders first, patch manifest regenerated, old DLL kept as `.pre-wzmap-2026-10-14.bak`. Same two owners
and crews, all Lv200. Both owners stayed visible and moved through the maps together; a heal loop healed both through
Bossfriend's `!healplayer`. Video of ohboy's view, sign-up to exit: `docs/agents/evidence/video/p18-horntail-rerun-2026-10-14.mp4`
(979 MB, not in git).

| Step | Result |
|---|---|
| Client memory gate | **PASS.** Through the normal launcher the client starts with 239 MB mapped and 3,066 MB free. With all 12 on Horntail, ohboy's client peaked at 2,295 MB virtual (Bossfriend's 2,046 MB). The stock DLL idled at 3,791 MB and crashed at 4,081 MB. |
| 5 Leafre restock | **PASS.** All ten bought up to 600/600 at the Leafre store (10:18-10:20). |
| 6-8 cave, sign-up, start | **PASS.** 12 enlisted, 0 refused (10:23:55 and 10:24:43); all 12 on 240060000 by 10:25:58. |
| 9 pre-heads | **PASS.** The left head fell at 10:40:35 (14.7 min); all ten walked to 240060100 on their own. The right head fell at about 10:55; the companions walked on to the altar on their own. |
| 10 reactor | **PASS.** ohboy climbed the rope at x=572 to the ledge at y=20 and attacked the crystal (2401000 at 598,-91). Horntail woke at 11:07:20 with a 60-minute clock. |
| 11 never-targets | Not checked live: nothing logs a companion's target. |
| 12-13 death walk, re-entry restock | **PASS, many times.** On the altar companions went down about 40 times in 11 minutes; each walked back from Leafre, was moved in, talked to the Mark and was back inside (for example Corvin restocked HP 511 -> 600 at 11:08:36 first). |
| Clear | **FAIL.** See below. |

**Alone on a pre-head map is by design.** The ten stood on 240060100 for 80 s (10:40:39-10:42:00) before ohboy
arrived, and the right head was still at 100%; with ohboy there it lost 16% in 40 s. `BossTargetRules.hittable` counts a
boss part only while a human drives it, so the mirror can apply its attacks. The full-rate hold changes tick rate, not
this rule.

**The altar.** Horntail fell at about 3% a minute for the first 2.5 minutes (93.1% at 11:09:42), then at about 0.6% a
minute (80.6% at 11:14:52, 77.9% at 11:19:13) as companions died faster than they walked back. They went down with
plenty of potions (one at 600/600), so the hits outrun the potion drinking. Max HP: the Heroes and the Dark Knights
19,776, the archers and the Corsair 9,000-9,900, the two mages 5,288. Ingrid went down 11 times and Thane 6. Ingrid ran
out of mesos after three restocks (3.0M -> 1,689 mesos by 10:51), said the broke line, and got 3M from Bossfriend at
11:10. Horntail also killed ohboy twice: at 9,276 HP and again at 30,000. I revived him with `!hpmp` the first time and
`!healplayer` the second (a second `!hpmp` set his MP but left his HP at 0), and raised both owners to 30,000 max HP
with `!maxhpmp` at 11:09. After the run both were set back in the database while offline: ohboy 8,996/5,309,
Bossfriend 19,776/2,924 (base max HP/MP before the run).

**Mirror.** The log has 256 `Boss mirror calibration miss` lines from 11:07. Each is a hit an owner's own client
reported that the mirror did not predict (`unpredicted`, reportedNotPredicted), not the other way round. 225 are on
Bossfriend: `USE_AUTOHIDE_GM` hid him on login, and the mirror skips hidden characters, so it never predicts for him.
Their delays sit within 30 ms of each attack's attackAfter and inside its game-data box (up to +-800 px), so the
attacks were accepted and timed right. Most of ohboy's 31 are the pillar attacks (8810002/3/4 attack 2, deadly), whose
regions the mirror samples (regionsMask 0, regionsSampled 134). No sign the mirror over-applies; the counter that would
show it (predictedNotReported) is only in `!bossmirror status` and was lost at the restart. Hits apply at 100%
(`DAMAGE_PERCENT`) with the 1.5 s shared window. The hidden Bossfriend also explains the stall while ohboy was dead
(81.1% -> 81.0% from 11:12:45 to 11:14:26): with no visible player, no part was driven (`unengaged` 441).

**Why they die.** The pillar attacks are `deadlyAttack`: HP and MP drop to 1, and the next hit after the 1.5 s window
kills. The companions' HP potions are mixed and the standard counts them all alike: Ingrid held 359 White Potions
(300 HP), Ilse Watermelons (1,000) and Sundaes (2,000), while Brannock and Thane held Reindeer Milk (5,000, the best HP
potion the Leafre store sells).

**End.** At 11:19, with 77.9% left and 48 minutes on the clock (it needed more than 1.6% a minute), both owners left by
the Crystal of Roots. All ten members went out with `OWNER_LEFT`, and the run ended at 11:20:01 with `cleared=false`.
0 ERROR lines in the run's log (cosmic-log-2026-10-14-5, 10:13-11:22).

## Live check 2026-10-14, potion-fix rerun (altar reached, not cleared)

**Setup.** `fix/companion-potion-topup` d9b8f723d live from 14:47 (9c7489b18 drink back up when low without a hit;
d9b8f723d Horntail counts only potions that heal at least a quarter of max HP). Same temporary keys, owners, crews and
DLL as the rerun. Bossfriend was unhidden this time. Owner gifts: 300 Honsters each to the five 19,776-HP fighters
(they cannot walk to New Leaf City) and coin to the short. Both owners raised to 30,000 with `!maxhpmp` and set back in
the database afterwards (ohboy 8,996/5,309, Bossfriend 19,776/2,924). Video: `p18-horntail-potionfix-2026-10-14.mp4`
(891 MB, not in git).

| Step | Result |
|---|---|
| Client memory | ohboy's client peaked at 2,326 MB virtual, Bossfriend's 2,339 MB. |
| 5 Leafre restock | **PASS.** Seven companions bought up to the floored standard (for example Ilse 398 -> 857 counted, 4.2M mesos spent); Brannock, Garrick and Thane already met it. |
| 6-8 sign-up, start | **PASS.** 12 signed up; all on 240060000 at about 15:00:20. |
| 9 pre-heads | **PASS.** Left head down at about 15:15 (14.8 min), right head at about 15:29 (13.5 min). One death in both: Fenna at 15:20. |
| 10 reactor | **PASS.** Horntail woke at about 15:32:30. |
| Clear | **FAIL.** 94.6% at 15:34, 82.3% at 15:37, then 79.9%, 76.1%, 73.6% at 15:40, 15:43, 15:46. Both owners left at 15:46-15:47; the run ended at 15:47:26 with `cleared=false`. |

**The drink-up fix works.** Companions no longer walk back in at low HP: one death in the 32 minutes of pre-heads,
two in the altar's first 4.5 minutes (the rerun: about 40 in 11).

**What kills them now: Horntail's Tails by touch.** From 15:37 the companions went down 48 times in 10 minutes: Ilse
11, Garrick 8, Thane 7, Brannock 6, Corvin 5, Ingrid 4, Isolde 4, Sorrel 2, Flint 1, Fenna 0. The melee fighters and
the two Bishops died; the archers and the Corsair barely did. Sampling HP four times a second showed single drops of
17,848 (Ingrid), 11,250 (Brannock) and 7,401 (Ilse) to 0, and Thane took 24,762 at x=257. The parts that touch the
floor are the Legs (8810008, box x -257..257, PAD 1,160) and the Tails (8810009, box x 120..427, PAD 1,750). A touch
rolls PAD squared times 0.008-0.0085, so the Tails do about 24,500-26,000 before defence. **The game agrees:**
Bossfriend, standing at x 195-283, took 25,839, 24,658, 25,467, 25,710 and 25,242 from his own client. Near x 0 his
client took about 1,700-2,200, so the Legs' real touch was not measured. Mirrored attacks on Brannock were about
5,200-5,600, the size Bossfriend's client took from the same attacks. So the damage is right; the companions stand
where a player would not. Why the deaths start at about 15:37 is not known (a Hyper Body running out, or targets
moving onto the Tails, are guesses).

0 ERROR lines in the run's log (cosmic-log-2026-10-14-7) or after the 15:50 restart on the committed config.

## Live check 2026-10-14, touch-fence rerun (altar reached, not cleared)

**Setup.** `feat/boss-touch-focus` f6340129a live from 17:13 (touch keep-out from half of max HP; Horntail focus order
Legs from the left, Tail from the left, Wings and Hands from the y=80 ledges, Heads from their ledges). Same temporary
keys, owners and crews; both owners at 30,000 again (set back in the database afterwards). Ilse, Isolde and Flint were
broke after the last run and got 4M each; Ingrid 3M mid-run. Video: `p18-horntail-touchfocus-2026-10-14.mp4` (1,738 MB,
not in git).

| Step | Result |
|---|---|
| Client memory | ohboy's client peaked at about 2,320 MB virtual. |
| 5 Leafre restock, 6-8 sign-up, start | **PASS.** 12 signed up; all on 240060000 at about 17:26:20. |
| 9 pre-heads | **PASS, slow.** Left head down at 17:39 (12.7 min, 0 deaths). Right head down at 18:24:50 (45 min): the two archers spent long spells on the right-hand ropes, out of range. |
| 10 reactor | **PASS.** Horntail woke at about 18:30. |
| Touch fence | **PASS.** During the Legs the melee fighters held left of x -217; Ingrid stood inside the Legs (by design: under Hyper Body a Legs touch was under half her max HP). Legs and Tail down by about 18:37. |
| Focus order | **PASS for Legs, Tail and Wings.** Wings down at about 18:51. The fighters then stayed on the y=80 ledges for the Hands. |
| Clear | **FAIL.** 96.9% at 18:32, 67.7% at 18:51 (1.5% a minute), then 62.9% at 19:01 (0.5% a minute). `@bosshp` at 19:00: Left Hand 20%, Right Hand 55%, all three Heads 100%. Both owners left at 19:01-19:02; the run ended at 19:02:39 with `cleared=false`. |

**Seal is the next wall.** The Right Hand (8810006) casts Seal (mob skill 120 level 8) every 25 s for 30 s, 90% of the
time, over a box of 1,600 x 850 px: the whole altar. It also casts Darkness (121) every 15 s and Weakness (122). At
19:01 padlocks hung over most of the crew and ohboy's chat said his skill was sealed. A sealed companion basic-attacks
only (`AgentCombatPlanRuntime`), so only summons (Phoenix, the Corsair's) keep full damage. Companions cure a disease
only with a cure item already in their Use tab (`AgentPotionService.tickDiseaseCure`), and nobody carried one; no
Bishop casts Dispel on sealed allies. The Leafre store sells no cure. Holy Water (2050003, cures Seal and Curse) sells
at 500 in Orbis (2012005), Kerning, Ellinia and elsewhere; All Cure (2050004) at 9201099.

**The Hands are just in reach.** The y=80 ledges end at x -230 and 410. Brandish reaches 150 px forward, so the Heroes
reach the Left Hand (x -139..-44) with room to spare and the Right Hand (x 184..281) by about 20 px. Knockback and
seduce (both Hands cast 128) kept dropping fighters to the floor, where they climb back by the ropes.

**Deaths on the altar came from empty potions, not touch.** 42 downs after 18:29: Ingrid 20 (HP potions 0, out of
mesos until a gift), Ilse 17 and Isolde 6 (MP potions 0, so no Magic Guard), others 0-2. Ilse also walked back in
without Magic Guard after each death (a death clears buffs, and no buff is cast on an empty stage map): fixed in
c44869508 (Magic Guard before the stage portal), with b2031d233 (the touch boundary is let go on a finished stage map
and after the clear). Neither is live yet.

0 ERROR lines in the run's log (cosmic-log-2026-10-14-9) or after the 19:05 restart on the committed config.

## Open items

1. Restock in Leafre on re-entry: built in 7a6244dba. Live PASS on 2026-10-14 (Flint and Ingrid; many times in the rerun).
2. The scaled standard (850/850, 7000) waits for Questions 5 and 6. Live evidence: Ingrid (Dark Knight) used about
   400 HP potions on the left head alone and went down at 197. In the rerun she was broke after three restocks.
3. Client memory (2026-10-14): **fixed and shipped** (kaentake b5d5b522). The rerun peaked at 2,295 MB with 12 characters.
4. Companions on a fight map with no player watching: the full-rate hold (d395c00c1) is live. They still never hit a
   boss part no human drives; that is the targeting rule, not the tick rate (see the rerun).
5. Flint not drinking at 100-160 HP (first run): not seen again in the rerun, where both owners stayed visible.
6. **Lv200 crews cannot hold the altar** (rerun): about 40 downs in 11 minutes, often with full potions, and the clear
   rate fell from 3% to 0.6% a minute. The potion-fix rerun narrowed it to item 9.
7. **Mirror on Horntail** (rerun): checked, no sign of over-applying (see "Mirror" above). Next run: unhide Bossfriend
   and read `!bossmirror status` before any restart.
8. **Weak potions count toward the boss standard** (rerun): fixed in d9b8f723d (Horntail's floor: a quarter of max HP),
   with 9c7489b18 (drink back up when low without a hit). Live: one death in 32 minutes of pre-heads.
9. **Companions stand in the Tails' touch area** (potion-fix rerun): fixed in f6340129a (keep out of a touch that
   would take a quarter of max HP since c44869508; Horntail focus order). Live PASS in the touch-fence rerun.
10. **Seal** (touch-fence rerun): the Right Hand seals the whole altar every 25 s for 30 s, and companions carry no
    cure and do not Dispel. Waits on the user: how companions get Holy Water, and Bishops casting Dispel.
11. **Empty potions on the altar** (touch-fence rerun): Ingrid's HP potions and both Bishops' MP potions ran out; the
    600/600 standard is short for a full altar fight (see item 2).
12. **Archers on the ropes** (touch-fence rerun): on 240060100 the archers spent long spells on the right-hand ropes,
    out of the right head's range; that head took 45 minutes against 13 in the potion-fix rerun.
13. **More hands** (touch-fence rerun): before the Hands, 12 Lv200 characters did about 1.5% a minute; the altar needs
    about 1.7% for the 60-minute limit. Plan: Bossfriend's four bench allies (Gunnar Lv79 Crusader, Magna Lv75 White
    Knight, Orrin, Tamsin) raised to Lv200.

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
normally. Shipped 2026-10-14 with the user's OK as Kaentake.dll b5d5b522 (both client folders; patch manifest regenerated).

## Risks

- Unconfirmed: whether the crew clears every part within the 120-minute limit.
- The region decode past region 15 may be biased (G1).
- Mob mist does no server damage, which is an immersion gap.
- Potions may run out before the end, because the standard is capped by the Use tab.

# CWKPQ Step C0: offline results

Written 2026-10-19 on branch `research/cwkpq-c0` (from `feat/companion-manager` cb21e2908), for
[CWKPQ_COMPANION_PLAN.md](CWKPQ_COMPANION_PLAN.md) Step C0. Offline only: real Map.wz, Reactor.wz, Skill.wz, scripts and
Java, plus throwaway probe code. No gameplay code, config or script was changed. The live client checks of C0 (trap
damage, potions under field limit 0x1000) are not part of this file.

The probe code and its raw output are kept in [cwkpq-c0/](cwkpq-c0/) so every number here can be re-run (see
"How the probes were run" at the end).

## Result

- **Stage 3 is climbable without Flash Jump, but not at base stats.** On the real map, with the down-jump forbidden
  as the map's field limit says, a member at base 100/100 reaches only the two low sigils (6109000 on the floor,
  6109001 at y 636): a 110 px flat gap at y -684 stops the climb. With Haste 20 (145/120), and from 120/120 or 145/110
  up, all five sigils and the top portal are reachable (38-55 jumps). Flash Jump or Teleport reaches everything even
  at base. Every thief at 3rd job or above holds Haste 20 under its build, and Haste is a party buff, so the plan's
  fallback (a human per unreachable sigil) is needed only when no Haste reaches the climbers. Two graph faults showed
  up: the server never reads CANNOTJUMPDOWN (the graph would let a bot down-jump where a player cannot), and the top
  portal's point snaps to a short duplicate foothold that only a down-jump reaches, while the long floor under it,
  where a player stands at the portal too, is reachable. The 110 px gap is the graph's jump model; a human at base
  stats should try it in the live part of C0.

- **Stage 4, stage 5, the warrior room and the Valley climb are all reachable at base stats.** All 8 stage-4 spots,
  a standing spot for every relic drop area (and the way there from the hall's spawn and from every class-room
  return), the warrior master, CM1_E, CM2_F and Lower Ascent to CM3_2 are COMPLETE routes at 100/100. Nothing in C4,
  C6 or C1 needs a movement skill.
- **The stage-4 fakes look exactly like real sigils.** Fake 6108001 has the warrior sigil's pixels, 6108000 the
  mage's and 6108005 the thief's (byte-identical canvases in the client's Reactor.wz). A bot cannot read a spot's class
  from the reactor id "as a player reads the picture": a player cannot tell either. But a fake turns on any hit
  (event type 0, no skill list, no script), and a real sigil ignores a hit with the wrong skill, so one basic hit tells
  them apart. Plan B5 changes accordingly.
- **The stirge plate script fails twice.** `killAllMonsters(true)` throws `Arity error - expected: 0 actual: 1` under
  this GraalJS; with only that fixed, the next line throws `ReferenceError: eim is not defined`. Both are caught and
  logged by ReactorScriptManager, the "All stirges have disappeared." message is still broadcast, the stirges stay, and
  the counter sticks at 5, so every later plate step repeats the message and the error. Question 4 (b) needs both
  fixes, not one.
- **Not every 3rd-job build can turn its sigil at CWKPQ's Lv90 entry.** Under the build that shaping, `!setlevel`
  and every companion load apply (MapleRoyals 2026), a Crusader gets Shout and a White Knight gets Charged Blow only
  at about Lv100, and an Outlaw gets Gaviota only at about Lv96 (a Gunslinger never takes Flash Fist). Every other 3rd
  job has a sigil skill from Lv70 or earlier, and every 4th job has one. No persona is at 3rd job today (the two
  authored as 3rd job are shaped as 2nd job below Lv70); the leveled crews live in the database and were not read.
- **The quest mirror duplicates the keystone.** When the owner completes 8223, a party companion that already
  completed it is force-started again and force-completed again: it gets a second Crimsonwood Keystone in a new SETUP
  slot (slotMax 1 does not stop it; the item has no "only one" flag) and 1,320,000 EXP again. Nothing fails.

## Check 1: offline nav probes

How a target is judged: the route search the bots use (`AgentNavigationPathService.findNextEdgeSelectionVaried`, with
its recovery budget) on a graph built by `AgentNavigationGraphService.rebuildGraph(map, profile)` from the real
Map.wz. COMPLETE = a full route; NO = no route. The target point is the ground under the object: a sigil's position is
the centre of its 180x208 picture, 103-105 px above its platform, and the sigil's skill box (lt -125,-19 to rb 123,122)
reaches that ground (`wz/Reactor.wz/6109016.img.xml:27-28`).

Movement profiles (speed/jump, the graph's own bucketed stats):

| Profile | Stats | Source |
|---|---|---|
| Base | 100/100 | no buffs, no movement gear |
| Gear | 120/110 | a mid point (speed shoes, jump gear) |
| Haste 20 | 145/120 | `wz/Skill.wz/410.img.xml` 4101004 level 20: speed 45, jump 20 (same for 4201003); Haste is a party buff, so any member can have it. The graph caps jump at 123 and buckets by 5 (`AgentMovementProfile.java:19`, `:48`). The first probe run used 140/120 for this row (the RJPQ probe's value); the stage-3 sweep uses the exact 145/120 |
| Flash Jump | Hermit 4111006 edges | Edges are authored only when `FLASH_JUMP_MODE` is SHADOW or ACTIVE (`AgentNavigationGraphService.java:1256-1259`); shipped `OFF` (`agent-engine.yaml:2033`). The probe set it to ACTIVE in memory only |
| Teleport | mage Teleport edges | Same, `TELEPORT_MODE` shipped `OFF` (`agent-engine.yaml:2029`) |

Field limits that matter here (`FieldLimit.java:28`, `:44`):

| Map | fieldLimit | Meaning for bots |
|---|---|---|
| 610030300 stage 3 | 0x22378 | CANNOTJUMPDOWN (0x20000). **The server never reads this bit** (no use of `FieldLimit.CANNOTJUMPDOWN` in `src/main/java`), and the nav graph authors down-jumps (DROP edges with `launchStepX` 0, `AgentNavigationGraphService.java:1065-1091`). The probe forbids those edges on this map and keeps walk-off drops |
| 610030510 warrior room | 0x233F8 | CANNOTJUMPDOWN, same handling |
| 610030400 stage 4 | 0x13F8 | includes 0x1000 (the potion bit, a live check) |
| 610030500 stage 5 | 0x37C | includes SUMMON 0x04 |
| 610020000, 610020001 Valley 1, 2 | 0x4A | MOVEMENTSKILLS (0x02): a bot there moves at base 100/100 whatever its buffs (`AgentMovementProfile.java:37-46`), and Flash Jump / Teleport edges are refused (`AgentMovementSkillPolicy.java:162-167`) |
| 610020002 Lower Ascent | 0x8 | none that matter |

### 1a. Stage 3, Test of Agility (610030300)

From the spawn `sp` (-483,1470), ground (-483,1536), on the bottom floor (x -595..955). The top portal warps back to
`sp` (`glpqPortal2.js`: `pi.warp(610030300, 0)`), so "from the bottom" is the same start; both ends of the floor,
(-590,1536) and (950,1536), gave the same result in every row. Down-jumps forbidden (CANNOTJUMPDOWN), walk-off drops
allowed. Evidence: [cwkpq-c0/cwkpq-c0-stage3.txt](cwkpq-c0/cwkpq-c0-stage3.txt) lines 1-40 (sweep; each cell is A*
result / graph reach | result with the down-jump allowed),
[cwkpq-c0/cwkpq-c0-stage3-floor.txt](cwkpq-c0/cwkpq-c0-stage3-floor.txt) lines 1-16 (the portal's floor), and
[cwkpq-c0/cwkpq-c0-nav.txt](cwkpq-c0/cwkpq-c0-nav.txt) lines 2-100 (route lengths in brackets; that first run used
140/120 for the Haste rows).

| Profile | 6109000 (857,1431) floor | 6109001 (240,531) | 6109002 (322,-789) | 6109003 (982,-1209) | 6109004 (-480,-1269) | Top portal `next00` (215,-1107) |
|---|---|---|---|---|---|---|
| Base 100/100 | COMPLETE | COMPLETE (15 jumps) | NO | NO | NO | NO |
| 100/110, 100/120 | COMPLETE | COMPLETE | NO | NO | NO | NO |
| 120/115 | COMPLETE | COMPLETE | NO | NO | NO | NO |
| 145/100 (Haste speed, no jump) | COMPLETE | COMPLETE | NO | NO | NO | NO |
| 120/120 | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE on the long floor* |
| 145/110 | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE on the long floor* |
| 145/115, 160/120 | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE | not probed on the long floor |
| **Haste 20, 145/120** | COMPLETE | COMPLETE | COMPLETE (38 jumps) | COMPLETE (45) | COMPLETE (54 + 1 walk-off) | COMPLETE on the long floor* |
| Flash Jump, 100/100 | COMPLETE | COMPLETE | COMPLETE (27 jumps + 12 FJ) | COMPLETE | COMPLETE | COMPLETE (also foothold 442) |
| Flash Jump + Haste | COMPLETE | COMPLETE | COMPLETE (4 + 19 FJ) | COMPLETE | COMPLETE | COMPLETE |
| Teleport, 100/100 | COMPLETE | COMPLETE | COMPLETE (29 teleports) | COMPLETE | COMPLETE | COMPLETE |

\* The portal sits where two footholds meet at the same height: the long floor of group 85 (footholds 397-479,
x 55..325, y -1104, a 156 px wall on its right) and a short duplicate on top of it, foothold 442 (x 145..295,
`cantThrough`, layer 3 group 0), found with
[cwkpq-c0/footholds_near.py](cwkpq-c0/footholds_near.py). The probe's ground point for (215,-1107) resolves to 442
(`cwkpq-c0-stage3-floor.txt:16`), which the graph enters only by a down-jump from y -1524 or by Flash Jump / Teleport
(`cwkpq-c0-stage3.txt:44-66`). The long floor, region 16, is reached from 120/120 up (`cwkpq-c0-stage3-floor.txt:5-15`,
targets at x 100 and 310), and a member standing on it at x 145..295 stands on the portal. So the table's NO for
`next00` in the first run is this target artifact, not a real wall.

Where the base climb stops: 105 of 145 regions are reached at base, 139 with Haste (`cwkpq-c0-stage3.txt:42`, `:491`).
The left column tops out on foothold 326 (x -125..-55, y -684); the next platform, foothold 399 (x 55..125, y -684, the
start of sigil 6109002's floor), is 110 px away at the same height. The graph has that jump at Haste
(`cwkpq-c0-stage3.txt:198`) and not at base (base's edges into that floor, `:558-805`, all come from above). Every way
into the upper half passes there or comes from above.

The down-jump control changes no sigil cell; it only makes foothold 442 reachable (by a down-jump onto the portal from
y -1524). So the field limit costs nothing once the portal is targeted on the long floor.

### 1b. Stage 4, Test of Wit (610030400): the 8 sigil spots

`CWKPQ.js:98-150` puts 8 reactors in state 1, one per row of `toDeploy`, then `map.shuffleReactors(toRandomize)`
(`CWKPQ.js:149`, `MapleMap.java:1840`) swaps their positions among themselves, so the 8 spots are fixed: one per row.
From the spawn `sp` (-841,584), ground (-841,636). Evidence: [cwkpq-c0/cwkpq-c0-nav.txt](cwkpq-c0/cwkpq-c0-nav.txt)
lines 102-172.

| Spot (row of `toDeploy`) | Position | Ground | Base 100/100 | Gear 120/110 | Haste 140/120 | Flash Jump | Teleport |
|---|---|---|---|---|---|---|---|
| A (row 0) | (1181,-189) | (1181,-84) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| B (row 1) | (-799,-668) | (-799,-564) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| C (row 2) | (7,-969) | (7,-864) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| D (row 3) | (374,-969) | (374,-864) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| E (row 4) | (998,-847) | (998,-744) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| F (row 5) | (1175,-547) | (1175,-444) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| G (row 6) | (-804,52) | (-804,156) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| H (row 7, always a fake slot) | (1176,172) | (1176,276) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |
| Exit `next00` | (-433,635) | (-433,636) | COMPLETE | COMPLETE | COMPLETE | COMPLETE | COMPLETE |

Base routes are 12-25 jumps (no ropes on this map). The flame traps and stirges are not in the graph.

### 1c. Stage 5, Test of Unity (610030500): standing where a dropped weapon lands in its relic area

Area = the relic reactor's position plus its Reactor.wz state-0 `lt`/`rb` (`Reactor.getArea`, `Reactor.java:400-402`).
A member's drop lands where `MapleMap.calcDropPos` puts it (`predictDropLanding`,
`CosmicPrimitiveCapabilityGateway.java:648-659`: the first foothold under a point 85 px over the dropper,
`MapleMap.java:630-647`). The probe scanned every ground point in and around each area and kept those whose own drop
lands inside it, then routed to them from the hall spawn and from the five room returns (`in01`-`in05`, where the rooms'
`out00` portals put you back). Evidence: [cwkpq-c0/cwkpq-c0-nav.txt](cwkpq-c0/cwkpq-c0-nav.txt) lines 174-244.

| Relic (weapon) | Reactor at | Area x / y | Standing spots that land inside | First spot routed to | From sp (1215,342) | From in01-in05 |
|---|---|---|---|---|---|---|
| 6109005 warrior (4001259) | (781,-777) | 1093..1293 / -665..-454 | 53, x 1093-1288 | (1093,-586) | COMPLETE (15 jumps) | all COMPLETE |
| 6109006 bowman (4001258) | (1249,-281) | 1280..1444 / -453..-316 | 18, x 1280-1440 | (1280,-415) | COMPLETE (13 jumps) | all COMPLETE |
| 6109007 mage (4001257) | (794,-782) | 795..994 / -959..-831 | 40, x 795-990 | (795,-916) | COMPLETE (20 jumps) | all COMPLETE |
| 6109008 thief (4001256) | (1332,-835) | 1343..1534 / -1027..-860 | 39, x 1343-1533 | (1343,-887) | COMPLETE (20 jumps) | all COMPLETE |
| 6109009 pirate (4001260) | (1297,-484) | 1266..1480 / -607..-506 | 47, x 1266-1476 | (1266,-565) | COMPLETE (15 jumps) | all COMPLETE |

Same result at base 100/100 and Haste 140/120. The relic reacts only to its own weapon, quantity 1, and fires 5 s
after the drop (`MapleMap.java:2411-2440`). Note the warrior area lies 300-500 px right of its reactor: drop at the
area, not at the picture.

### 1d. Warrior room (610030510): the master at (117,-2259)

From the room's `sp` (0,0), ground (0,156) (`glpqPortal00.js` warps to portal 0). Evidence:
[cwkpq-c0/cwkpq-c0-nav.txt](cwkpq-c0/cwkpq-c0-nav.txt) lines 246-260.

| Target | Ground | Base | Gear | Haste | Flash Jump | Teleport |
|---|---|---|---|---|---|---|
| Master 9201107 (117,-2259) | (117,-2244) | COMPLETE: 18 rope climbs + 5 jumps | COMPLETE | COMPLETE: 16 climbs | COMPLETE | COMPLETE |

The climb is the 8 rope pairs (`ladderRope` 1-16, y 95 to -2242). The 21 Crimson Guardians are not in the graph.

### 1e. Valley of Heroes 1, 2 and Lower Ascent, without the Marker

The statue portals (`Masteria_CM1_*`, `Masteria_CM2_*`, script portals with target 999999999) are not graph edges, so
these are climbs without the Marker of Heroism. Valley 1 and 2 carry MOVEMENTSKILLS, so only the base row applies to a
bot there. Evidence: [cwkpq-c0/cwkpq-c0-nav.txt](cwkpq-c0/cwkpq-c0-nav.txt) lines 262-336.

| Map | From | To | Base 100/100 | Other profiles |
|---|---|---|---|---|
| 610020000 Valley 1 | spawn `sp` (-1672,-32) | `CM1_E` (1882,-1476) | COMPLETE: 19 jumps, 4 climbs, 2 drops | all COMPLETE (moot: base is forced) |
| 610020000 Valley 1 | `CM1_AA` (-1810,83), the arrival from Forgotten Path | `CM1_E` | COMPLETE | all COMPLETE |
| 610020001 Valley 2 | `CM2_A` (-2085,-817), the arrival from CM1_E | `CM2_F` (1784,-1655) | COMPLETE: 25 jumps, 4 climbs, 4 drops | all COMPLETE |
| 610020002 Lower Ascent | `CM3_1` (164,24), the arrival from CM2_F | `CM3_2` (-1196,-938) to Jack | COMPLETE: 3 drops, 2 climbs, 1 in-map portal (`CM3_A1` to `CM3_A2`) | all COMPLETE |

No Lower Ascent route goes below y=28; the fall portals `CM3_c1`-`CM3_c32` (back to Valley 1) are at y=498. The
world route graph still treats this whole climb as one portal edge (plan A2); C1 can now author it as real in-map legs.

## Check 2: does each 3rd-job+ build put a point into a sigil skill?

Sigil skills, read again from Reactor.wz `activeSkillID` (`wz/Reactor.wz/6109000.img.xml:15` to `6109004`, and
`6109016`-`6109020` line 21): they match the plan's B1 table exactly, for stages 2/3 and stage 4 alike.

Where a companion's SP comes from:

- Shaping (`ResidentShaper.java:100`), `!setlevel` (`SetLevelCommand.java:326`, `AgentLevelSetService.java:230`) and
  the skill probe apply `AgentSpBuildProfileService.assignOffline` (`AgentSpBuildProfileService.java:40-77`): the
  MapleRoyals 2026 profile of every job on the chain (first job, second, third, fourth), each to the character's level,
  spending SP in the profile's order (`:209-235`).
- Between those, a level-up spends SP by the Agent's assigned profile, and with none assigned by the older build orders
  (`AgentBuildService.java:253-264`; `WarriorBuilds`, `BowmanBuilds`, `MageBuilds`, `ThiefBuilds`). Those older orders
  have no Paladin, no Fire/Poison line, no Bandit line and no pirate at all (`WarriorBuilds.java:26-37`,
  `MageBuilds.java:24-33`, `ThiefBuilds.java:21-27`): such a companion keeps its SP unspent until the next
  `!setlevel`.

"First point" is the level at which the profile's order reaches the skill, at 3 SP a level from the job's starting
level (an estimate from the order, not a run).

| 3rd job (4th) | Archetype sigil skills it can own | MapleRoyals 2026 build (shaping, `!setlevel`) | Older build order (level-ups with no profile) | Turns its sigil at Lv90? |
|---|---|---|---|---|
| Crusader (Hero) | Shout 1111008 | Shout 30 at SP #91: **about Lv100** (`mapleroyals-optimal-2026-sp-build-profiles.json:2744`, `:2782`). Hero inherits 30 | Shout 20 after 110 SP: about Lv107 (`WarriorBuilds.java:72-82`) | **No, until about Lv100** |
| White Knight (Paladin) | Charged Blow 1211002 | Charged Blow 3 at SP #91: **about Lv100** (`:2799`, `:2837`). Paladin inherits 30 | Charged Blow first: Lv70 (`WarriorBuilds.java:132-141`) | **No under the MapleRoyals build, until about Lv100**; yes under the older order |
| Dragon Knight (Dark Knight) | Dragon Fury 1311003 spear / 1311004 pole arm | Hybrid: pole arm 30 from Lv70, spear 1 at about Lv80; spear build: spear 1 at Lv70 (`:2859`, `:2930`) | Spear Dragon Fury after 63 SP: about Lv91 (`WarriorBuilds.java:157-174`) | Yes (a hybrid holding a spear needs Lv80+) |
| F/P Mage (Arch Mage) | Magic Claw 2001005, Explosion 2111002 | Magic Claw 20 from first job (`:1630`); Explosion from Lv70 (`:3006`) | none for this line; Magic Claw 20 from the first-job order (`MageBuilds.java:45-55`) | Yes |
| I/L Mage (Arch Mage) | Magic Claw, Ice Strike 2211002 | Magic Claw 20; Ice Strike 30 from Lv70 (`:3091`) | Magic Claw 20 | Yes |
| Priest (Bishop) | Magic Claw, Bahamut 2321003 | Magic Claw 20 only; Bahamut at about Lv136 (`:3171`) | Magic Claw 20 | Yes (Magic Claw) |
| Ranger (Bowmaster) | Strafe 3111006, Silver Hawk 3111005 | Strafe from Lv70 (`:3246`) | Strafe first (`BowmanBuilds.java:85-110`) | Yes |
| Sniper (Marksman) | Strafe 3211006, Golden Eagle 3211005, Piercing Arrow 3221001 | Strafe from Lv70 (`:3316`) | Golden Eagle 1 at about Lv72 (`BowmanBuilds.java:124-136`) | Yes |
| Hermit (Night Lord) | Avenger 4111005, Shadow Partner 4111002 | Avenger 1 at Lv70, Shadow Partner 30 next (`:3401`) | same order (`ThiefBuilds.java:68-80`) | Yes |
| Chief Bandit (Shadower) | Chakra 4211001, Band of Thieves 4211004, Meso Explosion 4211006 | Meso Explosion from Lv70, Chakra 3 at about Lv71 (`:3476`) | **none** (no Bandit line) | Yes under the MapleRoyals build; a naturally levelled one with no profile has no 3rd-job SP spent |
| Marauder (Buccaneer) | Flash Fist 5001001, Transformation 5111005, Dragon Strike 5121001 | Flash Fist 20 from the Brawler first job (`:1807`, `:1818`); Transformation from Lv70 (`:3556`) | none | Yes (Flash Fist) |
| Outlaw (Corsair) | Flash Fist, Gaviota 5211002, Rapid Fire 5221004 | **No Flash Fist** (the Gunslinger first job takes Double Shot, Somersault Kick, Dash, Bullet Time, `:1845-1881`; the Outlaw profile only assumes 1 inherited, `:3653`); Gaviota 1 at SP #77: **about Lv96** (`:3722`). Corsair inherits Gaviota 15, Rapid Fire from Lv120 | none | **No, until about Lv96** |

Personas and residents (`scripts/config/living-world/personas/*.yaml`, 72 entries, 41 of them Beginners):

| Persona | Authored job, level | Shaped as | 3rd job it would reach | Sigil skill by Lv90 (MapleRoyals build) |
|---|---|---|---|---|
| thane (`sleepywood.yaml:2`) | CRUSADER, 40-43 | Fighter: a 3rd job below Lv70 stops at its 2nd job (`ResidentShaper.java:36-38`) | Crusader | **No** (Shout at about Lv100) |
| marrow (`henesys.yaml:185`) | RANGER, 44-47 | Hunter | Ranger | Yes |
| dorran, gunnar (`henesys.yaml:83`, `:402`), sadie (`kerning-city.yaml:156`), brannock, hadrik, yara (`lith-harbor.yaml:42`, `:224`, `:308`), garrick, brunhild (`perion.yaml:2`, `:311`) | FIGHTER | Fighter | Crusader | **No** (about Lv100) |
| rurik (`henesys.yaml:254`), tamsin (`perion.yaml:122`), orrin, magna (`sleepywood.yaml:77`, `:215`) | PAGE | Page | White Knight | **No** (about Lv100) |
| mae, flint (`nautilus.yaml:75`, `:223`) | GUNSLINGER | Gunslinger | Outlaw | **No** (about Lv96) |
| ingrid (`lith-harbor.yaml:335`), halvard, corvin (`perion.yaml:155`, `:382`) | SPEARMAN | Spearman | Dragon Knight | Yes |
| lioren, casse, isolde, ilse (`ellinia.yaml:45`, `:97`, `:321`; `henesys.yaml:136`) | FP_WIZARD, IL_WIZARD, CLERIC | same | F/P, I/L Mage, Priest | Yes (Magic Claw) |
| pell, fenna, sorrel (`ellinia.yaml:258`; `henesys.yaml:2`, `:475`) | HUNTER, CROSSBOWMAN | same | Ranger, Sniper | Yes |
| jinx, kestrel, vex (`kerning-city.yaml:111`, `:348`, `:2`) | ASSASSIN, BANDIT | same | Hermit, Chief Bandit | Yes |
| kettle (`nautilus.yaml:2`) | BRAWLER | Brawler | Marauder | Yes (Flash Fist) |
| bram (`ellinia.yaml:170`) | WARRIOR | Warrior | by its 2nd job | by branch |

So **no persona or resident is at 3rd job or above as authored and shaped**. The ones that reach CWKPQ's level by
play or `!setlevel` (the bossing crews, e.g. Brannock as a Lv200 Hero, `AgentSpBuildProfileService.java:226-229`) hold
whatever their job's row above gives; at 4th job every branch has a sigil skill. The live crews' real skill levels are
in the database and were not read here: C9 should check them with the skill probe before a run.

## Check 3: are the stage-4 fakes drawn like real sigils?

The server's XML export has no pixels (canvases are sizes only, no UOL links), so the probe read the client's
`v83 Client/Reactor.wz` with the repo's wzlib and hashed every decoded canvas
([cwkpq-c0/reactor_canvas.txt](cwkpq-c0/reactor_canvas.txt); script
[cwkpq-c0/reactor_canvas_cmp.py](cwkpq-c0/reactor_canvas_cmp.py)).

| Fake | Info string | Idle frame `1/0` | All 15 frames identical to | Hit behaviour |
|---|---|---|---|---|
| 6108000 | "fake skill 2" | f13202904a7a | **6109018 mage sigil** (and stage-2 6109002) | state 1 event type 0, no `activeSkillID`, no script (`wz/Reactor.wz/6108000.img.xml:17-22`; no `scripts/reactor/6108000.js`): any hit, even skill 0, flips it to state 2 and nothing counts |
| 6108001 | "fake skill 1" | 20c22c6d4a0f | **6109016 warrior sigil** (and 6109000) | same |
| 6108005 | "fake skill 3" | 14e5ed2cae3d | **6109019 thief sigil** (and 6109003) | same |
| 6109016-6109020 (real) | warrior, bowman, mage, thief, pirate skill | 20c22c6d4a0f, fddace684f08, f13202904a7a, 14e5ed2cae3d, 3de56e922ea2 | - | event type 5 with `activeSkillID` and lt/rb (`6109016.img.xml:17-29`): a hit with any other skill id changes nothing (`Reactor.java:276-281`); its script adds 1 to `glpq4` |

So a fake shows a real class picture (warrior, mage or thief). The only differences a player can see are the hit
animation speed (40 ms frames on fakes, 100 ms on reals) and the result.

What this means for B5: reading the class "from the picture" means the warrior/mage/thief picture, true or fake. A
companion can do what a careful player does: hit a spot of its class once with a plain attack (the gateway's skill-0
hit, `CosmicPrimitiveCapabilityGateway.java:854`). A fake animates and turns (broadcast to the map); a real sigil does
not move, and then takes the class skill. Bowman and pirate pictures are never fakes. Per run there are at least 5 real
sigils (one of each class, `CWKPQ.js:112-126` loops until all five classes and a fake are drawn) and at least one fake
(row 7 is always fake); any 5 activations clear, so a second sigil of one class also counts.

## Check 4: `scripts/reactor/6109013.js` under GraalJS

Run for real ([cwkpq-c0/CwkpqC0StirgeScriptProbe.java](cwkpq-c0/CwkpqC0StirgeScriptProbe.java)): the stock file is
evaluated in an engine made exactly as `AbstractScriptManager.getInvocableScriptEngine` makes it (graal.js factory,
`polyglot.js.allowHostAccess` and `allowHostClassLookup`, `AbstractScriptManager.java:44-67`, `:83-87`), `rm` is a real
`ReactorActionManager` over the real 610030400 geometry, and `touch()` is called with `glpq_s` at its trigger value 5.

| Variant | Result | Evidence |
|---|---|---|
| Stock script | `ScriptException: TypeError: invokeMember (killAllMonsters) on server.maps.MapleMap failed due to: Arity error - expected: 0 actual: 1`, line 5 | [cwkpq-c0/stirge-probe.txt](cwkpq-c0/stirge-probe.txt); `MapleMap.java:1730` has only `killAllMonsters()` (no overload); GraalJS host calls need the exact arity |
| Arity fixed, nothing else | `ReferenceError: eim is not defined`, line 6: `action()` uses `eim`, which is a local of `touch()`/`untouch()` only | same file; `6109013.js:3-7` |
| Effect in a run | `ReactorScriptManager.touching` catches the `ScriptException` and logs an error (`ReactorScriptManager.java:117-129`). Line 4 already broadcast "All stirges have disappeared.", so players see it. The throw skips `setIntProperty(fid, +1/-1)`, so `glpq_s` stays at 5 and every later touch or untouch repeats the message and the error. The 48 Blood Stirges stay | `6109013.js:1-24`; `glpq_s` starts at "0" (`CWKPQ.js:173`) |

The trigger needs 5 plate touches at once (two plates, `stirge0`/`stirge1`, counted per touching player); bots send no
touch packets today (`TouchReactorHandler.java:30-48` is client-only). A fix for Question 4 (b) is
`rm.getMap().killAllMonsters()` and `rm.getEventInstance()` inside `action()`.

## Check 5: the quest mirror when a companion already holds a keystone

| Step | What happens | Evidence |
|---|---|---|
| Owner completes 8223 at Lukan | `Quest.complete` runs the owner's reward, then `AgentPartyQuestSyncService.syncPartyAgentsQuestComplete` | `Quest.java:340-357` |
| Mirror, per party Agent | `ensureQuestStarted`: the companion's 8223 is COMPLETED, not STARTED, so it is `forceStartWithActions` again (no `canStart` check), then `forceCompleteWithActions` | `AgentPartyQuestSyncService.java:101-113`, `:133-139` |
| `forceStart` | New STARTED status, completed count kept; 8223 has no start actions | `Quest.java:387-424`, `wz/Quest.wz/Act.img.xml:39575` (8223 `0` is empty) |
| `forceComplete` + actions | COMPLETED again; `ExpAction` gives 220,000 x 6 = 1,320,000 EXP again; `ItemAction.forceRun` gives 3992041 x1 again with no `canHold` check | `Quest.java:430-467`, `ExpAction.java:48-54`, `ItemAction.java:100-102`, `:180-181` |
| The keystone add | `InventoryManipulator.addByIdInternal`: the held stack is at slotMax 1, so a new slot gets a second keystone; there is no "only one" check (3992041 has `slotMax 1`, `tradeBlock 1`, no `only`). Only a full SETUP tab stops it (it then sends "inventory full" and returns false; nothing throws) | `InventoryManipulator.java:90-135`; `wz/Item.wz/Install/0399.img.xml:2190-2201` |
| Also on start | When the owner starts 8223 (start script `scripts/quest/8223.js:25`, `qm.forceStartQuest()`), the same mirror force-starts it on a companion that already completed it (`syncPartyAgentsQuestStart` skips only STARTED) | `AgentPartyQuestSyncService.java:26-38`, `Quest.java:383-386` |

So the plan's risk 5 is real but harmless: no failure, a duplicate keystone and duplicate EXP. 8222 behaves the same
way (85,000 x 6 EXP again; its 10 badges are taken only if held, `ItemAction.java:150-159`). A fix belongs in C1 or C2:
the mirror should skip a companion whose quest is already COMPLETED (and not repeatable).

## What changes in the plan

| Plan part | Change | Why (check) |
|---|---|---|
| **B4 / C5 stage 3** | Replace "fallback: that archetype's sigil becomes a human's job" with: climbers need Haste (or at least 120/120 or 145/110 from gear), or Flash Jump / Teleport. One climber per archetype stays, but each must be in a party with a Haste thief (Haste is a party buff, 300 s at level 20, so it is recast during the 10 minutes; a thief climber casts it on itself, as RJPQ's runner does, `AgentRjpqDarkLabs.java:45`, `:138-150`). The human fallback remains only for a run with no Haste, no Hermit and no mage. The desk warns when no thief of 3rd job or above is in the lineup | 1a: base reaches 2 of 5 sigils, Haste reaches all; every 3rd-job+ thief build holds Haste 20 (`mapleroyals-optimal-2026-sp-build-profiles.json:2425`, `:2494`: 20 points by about Lv51-55) |
| B4 / C5 | The graph must honor CANNOTJUMPDOWN: no down-jump DROP edges on maps with field limit 0x20000 (stage 3 and the warrior room). Walk-off drops stay | 1 (field limits): the server never reads the bit, so bots could down-jump where players cannot |
| B4 / C5 | Target the top portal on the long floor (group 85) or treat coincident footholds as one ground; otherwise every climber's route to `next00` is NO without a movement skill | 1a, foothold 442 |
| B4 / C5 | Flash Jump and Teleport are shipped OFF (`agent-engine.yaml:2029`, `:2033`); using them in stage 3 needs a per-map policy like `AgentRjpqMovementSkillPolicy`. Not needed if Haste is used | 1a |
| C0 live part | Add one human check: at base stats, can a player jump the 110 px gap from (-55,-684) to (55,-684) in 610030300? If yes, the graph's jump model is short there and base climbers may be possible | 1a |
| **B5 / C4 stage 4** | Replace "if the fakes look different, read the class from the reactor id" with: a fake shows the warrior, mage or thief picture, so the picture names a class but not real-or-fake. A companion of that class hits the spot once with a plain attack (skill 0): a fake turns at once and is remembered as spent; a real sigil does not move, then takes the class skill. Bowman and pirate spots are always real. Stage 4 needs no movement skill | 1b, 3 |
| C4 stage 4 | The stirge plates need 5 members standing on them at once (touch packets); bots send none. Plates stay optional, and without both script fixes they do nothing anyway | 4 |
| **Question 4 (b)** | The stirge fix is two changes: `killAllMonsters()` with no argument, and `rm.getEventInstance()` inside `action()` | 4 |
| B6 / C6 stage 5 | Confirmed: every relic has standing spots whose drop lands inside, reachable at base from the hall and every room return. Drop targets must come from `Reactor.getArea` (the warrior area is 300-500 px right of its picture) and be checked with `predictDropLanding` | 1c |
| C6 warrior room | Confirmed at base (rope climb) | 1d |
| C1 road | Confirmed: the Valley 1, Valley 2 and Lower Ascent climbs work at base without the Marker; C1 can author them as in-map legs (Valley 1 `CM1_AA` to `CM1_E`; Valley 2 `CM2_A` to `CM2_F`; Lower Ascent `CM3_1`, the `CM3_A1` in-map portal, `CM3_2`). Valley 1 and 2 force base stats on bots (MOVEMENTSKILLS), which is enough | 1e |
| **C3 lineup rule (Question 5)** | Count a member toward an archetype only if it owns a sigil skill at level 1 or more (the B3 skill filter). With the MapleRoyals builds a Crusader or White Knight below about Lv100 and an Outlaw below about Lv96 do not count; the desk says why. An alternative is a build change (one early point in Shout, Charged Blow or Flash Fist); that is your call, not part of this plan yet | 2 |
| C9 | Before a live run, read the crews' real sigil-skill levels with the skill probe (the leveled crews are in the database) | 2 |
| C1 or C2 quest mirror | Risk 5 is real but harmless: no failure, a second keystone and the quest EXP again. Make the mirror skip a companion whose quest is already COMPLETED and not repeatable (start and complete paths) | 5 |

Phases whose size does not change: C3, C7, C8, C10. C5 gets smaller (no human fallback in the usual lineup, no
jump-quest skill work if Haste is used) but gains the CANNOTJUMPDOWN and coincident-foothold graph work; it stays L
until the trap-damage question is settled.

## How the probes were run

Copy the three `.java` files from [cwkpq-c0/](cwkpq-c0/) into `src/test/java` (packages
`server.agents.capabilities.navigation` and `scripting`) and run each alone, never with the suite (the nav probes
switch the movement-skill modes in the in-memory tuning before the config class loads):

```
JAVA_TOOL_OPTIONS=-Xmx5g ./mvnw -o test -Dtest=CwkpqC0NavigationProbe -Dsurefire.failIfNoSpecifiedTests=false
```

`CwkpqC0NavigationProbe` writes `target/cwkpq-c0-nav.txt`, `CwkpqC0StageThreeProbe` writes
`target/cwkpq-c0-stage3.txt` (with `-Dcwkpq.c0.short=true` it runs four profiles with the portal-floor targets and writes `target/cwkpq-c0-stage3-floor.txt`), `CwkpqC0StirgeScriptProbe` prints its lines. The Python scripts run with the stdlib
(`py -I reactor_canvas_cmp.py <repo>/tools/wz-patcher "<client>/Reactor.wz"`, `py -I sp_sigils.py
<repo>/src/main/resources/agents/profiles/mapleroyals-optimal-2026-sp-build-profiles.json`). Graph builds take 1-3
minutes per map and profile.

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
- A companion that dies says one line ("ugh, i'm down. heading back from leafre!"), stands in Leafre for 5 s, is moved
  to the cave entrance, walks up to the Mark and talks to it. The Mark warps it back to 240060000, and it walks through
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

1. **No restock in Leafre on re-entry.** The plan says the companion restocks at the Leafre store before it is moved
   back. The shared restock rule lets a following companion restock only on its owner's map
   (AgentSupplyReadinessRuntime.free), and its owner is in the cave. Changing that rule touches every restock path, so
   a re-entering companion goes back in with what it carries. Open item 1.
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

## Tests

- `MarkOfTheSquadHookScriptTest`: the Mark equals Adobis with the Mark's names, no leader item, and the key `horntail`.
- `AgentBossStageFightTest`: the shipped rules; a member fights the head on a pre-head map; once it falls, the member
  walks to next00 and takes it at most every interval; the second pre-head map too; on the altar ranged members take
  head A and melee members the legs; nothing up there means hold; after the clear, hold.
- `AgentBossDeathWalkTest`: a REENTERING Horntail member stands in Leafre, is moved once to 240050400 and walks up to
  the Mark; away from Leafre it walks to Leafre.
- `AgentBossRunPartyEventTest`: a member on 240060000 is adopted and fights; one on the recruit map is not.
- `AgentPartyBossesTest`: the Horntail row against HorntailBattle.js.
- Updated pins: keys, catalog list, death modes, shipped scope types, standards; the "unlisted boss" examples move to
  Pink Bean and Chaos Horntail.

Full suite on ebba44486: 13,454 tests, 4 failures, 37 skipped (11:04 min). The 4 failures were the standards pins
in the two supply tests; 989eea669 fixes them, and the supply tests pass (120).

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
13. Never use `!killall`. It completes the fight.
14. Repeat with the bench allies as stage 2, then with ohboy as stage 3. For stage 3, run `!warphere ohboy` from
    Bossfriend on 240050400.

## Open items

1. Restock in Leafre on re-entry (change 1 above).
2. The scaled standard (850/850, 7000) waits for Questions 5 and 6.

## Risks

- Unconfirmed: whether the crew clears every part within the 120-minute limit.
- The region decode past region 15 may be biased (G1).
- Mob mist does no server damage, which is an immersion gap.
- Potions may run out before the end, because the standard is capped by the Use tab.

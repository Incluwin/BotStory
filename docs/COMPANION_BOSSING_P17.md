# P17: Scarlion and Targa

> Built 2026-10-12 on branch `feat/companion-bossing-p17`, on top of P16. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P17. Not deployed, not live-tested yet. The `scarga` key ships off.

## Result

An owner can now sign their companions up for Scarlion and Targa at Aldol, as at Adobis for Zakum. A companion that
dies in the fight revives in Kampung, walks back to Aldol on foot, and talks its way back in. With `scarga` off (as
shipped), Aldol's window and the fight are stock.

The same branch also fixes the boss-run lines for every boss: the generic lines named Balrog and Mu Young, so a
Papulatus, Latanica or Neo City companion would have said "sorry, balrog got me". They are now boss-neutral. Easy and
Normal Balrog keep their old lines word for word.

## What the player sees (with the key on)

Example: `scarga` on, the owner at Aldol (551030100) with 5 companions and the keys (4032246).

- Aldol offers the owner "My party will register too." after they create or join the expedition, and "Call allies
  from my bench..." when `COMPANION_BOSS_ALLY_TYPES` also lists `scarga`. The expedition needs 6 or more.
- Inside, the companions hit Targa's forms first, then Scarlion's, then the adds.
- A companion that dies says one line ("ugh, i'm down. running back from kampung!"), walks 7 maps from Kampung to
  Aldol, and talks to him. Aldol warps it back into the fight. If it is not back in 5 minutes, or the bosses fall
  first, it waits outside instead.
- After the clear (or before it, if the owner leaves), the companions leave by 9201134.
- On the way, a short companion buys at Trend Zone Metropolis (550000000). At Aldol's map it makes no shop trip: the
  store is 11 maps away.

## Keys and standards

| Key | Boss | Recruit map, battle map | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `scarga` | Scarlion and Targa | 551030100, 551030200 | 600/500, 5000 |

The standard is above the carry limits, as Zakum's is: a visit raises them to the standard.

## Changes from the plan

1. **No `BOSS_IDS` entries for the adds.** The plan lists 9420545, 9420550, 9420531 and 9420532 there. 9420531 and
   9420532 also live on the field map 551000200, where `BOSS_IDS` (always on) would change them at once. Instead, a
   non-boss add is guarded inside the event of a listed expedition type whose open companion run's rules name it
   (`AgentBossRunRegistry.namedPart`). Zakum's rules name no part, so Zakum is unchanged.
2. **The re-entry walk is the agent's own.** P12 G6 left "the host walks it back" open. The walk runs in
   `AgentBossDeathWalk` before follow (follow would hold it in place while its owner is inside), and the run's own tick
   talks to Aldol.
3. **Boss-neutral generic lines** (above). Not in the plan; found while adding Scarga's lines.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts `scarga`.

`agent-engine.yaml`:

- `AgentBossScope.EXPEDITION_TYPES` changes from `ZAKUM` to `ZAKUM,SCARGA`. It acts only when an Agent hits a part in
  a Scarga event, and Agents get there only with the key on.
- `scarga=600/500` joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `scarga=5000` joins `AMMO_STANDARDS`.
- New: `AgentBossRunSession.REENTER_TALK_EVERY_MS: '10000'`.

boss-rules.json: the `scarga` row (`targetPriority` Targa 9420542-9420544, then Scarlion 9420547-9420549; `adds`
9420545, 9420550, 9420531, 9420532).

No database change.

## How it is built

- **Aldol's script.** It was the same stock expedition script as Adobis's, so it takes the five P8 hooks with the key
  `scarga`. `AldolHookScriptTest` checks that it stays Adobis's twin with Aldol's names, item and event.
- **Run row.** `AgentBossRunCatalog.SCARGA`: expedition SCARGA, no join choices, the priority fight, exit NPC 9201134
  before and after the clear, owner leaving takes the party, `DeathMode.REENTER`.
- **Entrance row.** `BossEntrance.SCARGA`: companions wait beside Aldol (1693,353), approach store at 550000000,
  allies in and out by `west00`, trips out of the recruit map held.
- **Re-entry.** `AgentBossDeathWalk`: a REENTERING member walks to the recruit map, then up to Aldol, once per death,
  and stops within `EXIT_REACH_PX`. `AgentBossRunSession.talkBackIn`: a REENTERING member alive beside Aldol runs his
  script at most every `REENTER_TALK_EVERY_MS`; the stock in-progress branch registers a listed member while `canJoin`
  is 1 (stock never clears it). `CompanionBossRuns.reentering` says the REENTER line once per death.
- **Lines.** `BossRunLines.GENERIC` is boss-neutral. `BY_BOSS` gives Easy and Normal Balrog the old lines, Papulatus
  a line about the gate, and Scarga its own lines.

## Tests

- `AldolHookScriptTest`: Aldol equals Adobis with Aldol's names, item, event and the key `scarga`.
- `AgentPartyBossesTest`: the Scarga row against ScargaBattle.js (maps, Lv100, NPCs, REENTER, adds).
- `AgentBossScopeScargaTest`: boss forms guarded in a Scarga event; adds only with an open run; nothing on the field
  map; Zakum unchanged.
- `AgentBossDeathWalkTest`: a REENTERING member walks from Kampung, up to Aldol, stops beside him, and walks again
  after a new death.
- `AgentBossRunReenterTest`: beside Aldol it talks back in at most every interval; out of reach it does not.
- `BossDeathWalkRouteTest` (was `PapulatusGateRouteTest`): Kampung to Aldol's map on foot, as well as Ludibrium to the
  Papulatus gate.
- `BossRunLinesTellTest`: the generic lines name no boss or place; Easy and Normal Balrog keep their old lines.
- Updated pins: keys, catalog list, death modes, shipped scope types, standards.

Full suite on 22818d3b6: 13,442 tests, 0 failures, 37 skipped (11:21 min).

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `scarga`. For the ally stage, set `COMPANION_BOSS_ALLY_TYPES` to
   include `scarga`.
2. Restart with `start-maple-stack -RestartServer`.
3. Check that each bench ally is still Lv160 from P8. If one is lower, raise it.
4. Run `!resetboss <name> SCARGA` for each character.
5. Run `!item 4032246 2` as Bossfriend.
6. Run `!givemeso <name> 2000000` for each companion. The 600/500 standard costs about 1.54M.
7. Run `!warp 550000000`. Check that short companions buy there.
8. Run `!warp 551030100`.
9. Start the client memory gate, stage 1: owner plus 5.
10. Create the expedition at Aldol and register the party.
11. Start, then summon Targa.
12. Watch immunity stop the companions or make them switch targets.
13. Run `!hpmp Ingrid 0`. Check the `Boss death walk` and `talks back in` lines: she walks from Kampung, talks to
    Aldol and is back inside.
14. Read the "PQ potions ... pq=scarga" lines. They decide Questions 5 and 6 for Horntail and Pink Bean.
15. Repeat with the bench allies as stage 2 of the memory gate.

## Risks

- Unconfirmed: whether 600M HP can be cleared in 60 minutes.
- Unconfirmed: whether Aldol's map's Galloperas slow the walk back up to him.
- The re-entry talk relies on the stock `canJoin` staying 1 and on the expedition still listing the member after its
  death.

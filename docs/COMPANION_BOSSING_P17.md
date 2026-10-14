# P17: Scarlion and Targa

> Built 2026-10-12 on branch `feat/companion-bossing-p17`, on top of P16. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P17. Deployed keys-off; live-tested 2026-10-13 (below). The `scarga` key ships off.

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
| `scarga` | Scarlion and Targa | 551030100, 551030200 | 900/500, 5000 (HP was 600 until 2026-10-14) |

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
- `scarga=900/500` (600/500 until the live run) joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `scarga=5000` joins `AMMO_STANDARDS`.
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
6. Run `!givemeso <name> 3000000` for each companion. The 900/500 standard costs more than the 1.54M the 600/500 one did.
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

## Live result 2026-10-13 21:42-23:16 (int/p9bg fdf2ab04e, temporary keys `scarga` and `field_temple`)

Crew B (Brannock and Garrick Heroes, Ilse Bishop, Fenna Bowmaster, Corvin Dark Knight) with owner ohboy (Night Lord).
Each companion wore a `!bossgear` kit with passed 10% scrolls and read its job's skill and mastery books (90 reads at
Lv160, 25 more at Lv200; failed books handed out again, as a player buys another). No ERROR lines in either run.

**Run 1, Lv160 (session 1, 22:08-22:26): not cleared, a measurement.** Targa's first form (60M) took 9.5 min (about
6.3M a minute); with 540M left and 43 min on the clock the run could not clear, and the level set is refused inside an
event instance ("nobody was set: ohboy is in an event instance"). ohboy left by the exit NPC; the companions left with
him (OWNER_LEFT) and were moved out 30 s later.

**Run 2, Lv200 (session 2, 22:34-23:14): cleared.** "SCARGA Expedition with leader ohboy finished after 35 Minutes and
34 Seconds". Targa spawned 22:36:40 and died 22:49:50 (forms about 2, 3 and 7 min); Scarlion spawned about 22:55 and the
run logged "Boss run cleared: session=2 boss=scarga inside=4" at 23:10:59 (forms about 4.5, 3.5 and 7.5 min). Fighting
time about 29 min. The fastest form fell at about 60M a minute (Targa's first).

| Step | Result |
|---|---|
| 6-7. Mesos; short companions buy at Trend Zone Metropolis | PASS: all five set off "for scarga" and came back with 600-764 HP and 500-800 MP potions. |
| 8-10. Aldol: create, "My party will register too." | PASS: "Boss run enlist ... accepted=[338, 9, 336, 878, 835]", all five registered in 31 s. |
| 11. Start, summon Targa | PASS: the key dropped inside reactor 5511000's area (x -572..-446) summons Targa; a key at x -575 does not (run 1's keys landed there because ohboy slid on after walking, so run 1 used `!spawn 9420542`). Scarlion by a key at x -203. |
| 12. Immunity | Not observed: no immunity line in either run. 57 stuns, each recovered from (the Snowman fix). |
| 13. A dead companion walks back from Kampung and talks back in | PASS for Fenna (died 23:08:37, walk 6 hops in 1 min 40 s, "talks back in" and "back inside" 23:10:22). FAIL for Corvin, both runs: "gives up re-entering" after the 5-minute limit. Run 1 he looped on the 15 px stair steps at x about -860 on 551000100; run 2 he reached 551020000 (hop 5 of 6). |
| 14. "PQ potions ... pq=scarga" | Run 2: Garrick 768 > 112 HP potions, Brannock 746 > 367, Fenna 600 > 504, Ilse 614 > 619 (MP 647 > 570), Corvin out most of the fight. |
| After the clear | PASS: ohboy left through 9201134 ("You guys defeated both Scarlion and Targa!"); the four inside left with him ("reason=EXITED") and were moved out; Corvin rejoined on Aldol's map. |

Findings:
- Lv160 is too weak for Scarga in 60 min with five companions; Lv200 (the server's cap) clears it in about 30 min.
- The 600 HP standard is thin for a front-line Hero over a 30-minute fight (Garrick ended with 112). User decision
  2026-10-14: raised to 900.
- Corvin's walk back is slower than the 5-minute re-entry limit; Fenna's took 1 min 40 s. User decision 2026-10-14:
  `REENTER_TIMEOUT_MS` 600000 (10 min).
- Garrick, loaded as a bench REST slice at login and then called, arrived frozen (mode BACKGROUND_ABSTRACT, TOWN_LIFE
  ledger blocked, mailbox 5); benching and calling him again fixed it. Handed to a separate session.
- Leafre restock for `field_temple` (the field standard, P16 follow-up): PASS, four companions set off "for a
  field_temple" on arrival and bought to 500 HP potions.

Video: docs/agents/evidence/video/p17-scarga-2026-10-13.mp4 (run 1) and p17-scarga-lv200-2026-10-13.mp4 (run 2,
655 MB), not in git.

## Risks

- Unconfirmed: whether 600M HP can be cleared in 60 minutes.
- Unconfirmed: whether Aldol's map's Galloperas slow the walk back up to him.
- The re-entry talk relies on the stock `canJoin` staying 1 and on the expedition still listing the member after its
  death.

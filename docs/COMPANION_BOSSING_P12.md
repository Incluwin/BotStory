# P12: Shared boss groundwork, the remainder

> Built 2026-10-12 on branch `feat/companion-bossing-p12`, on top of P11 (`feat/companion-bossing-p11`). Plan:
> docs/COMPANION_BOSSING_PLAN.md, section P12. Not deployed, not live-tested yet. P12 adds no boss key, so with the
> shipped switches nothing a player sees changes, except the two human-facing changes listed below.

## Result

P12 builds the shared rules that P13 to P20 need, behind boss keys that later phases add. Each item has unit tests.
Live behaviour changes in these places with every new key off (the list was completed after the deploy review of
2026-10-12):

1. A companion's card shows its boss records ("Zakum: cleared 1 of 3") once it has three runs of a boss.
2. Inside a companion boss run, boss kills no longer give friendship BOSS points each. A cleared run gives its owner
   BOSS points once with each Resident companion that went in (decision 23). After the review, a friend who hit one
   of the run's bosses also gets them once, with the companions in their party (917ca04c2). This changes Easy Balrog
   and Zakum runs now.
3. A never-target part (Normal Balrog's helpers and seal, Zakum's fake body) is now no primary target either. Before,
   only secondary and projectile picks skipped it. Easy Balrog lists none, so its runs do not change here.
4. In Easy Balrog runs (its key is on live), companions stop hitting a part that is immune to or reflects their damage
   type (G4), handle stun, seduce, confuse and banish (G5: the claw's attack2 confuses), and do not summon (G11).
5. Any mob dispel makes the dispelled Agent rebuff, everywhere (G5 dispel notice).
6. At the Papulatus portal, a companion that is not on its party leader's map is neither checked nor logged for the
   daily quota (G7), for every party.

## Deploy review fixes (2026-10-12)

- `AgentBossRules.fieldKeyOf` scanned all rows with boxed `List.contains`, and the combat candidate filter called it
  for every hostile monster before the distance filter. It now binary-searches a sorted int index built with the
  catalog, and the candidate filter runs the boss checks after the distance and objective filters (4fd036d9b).
- `BOSS_DESK_MAPS` id ranges now work in the desk lookup, as the validator allowed (4fd036d9b).
- Friends who hit a run's boss get its once-per-run BOSS points (917ca04c2, item 2 above).

## What each item does

| Item | What it does | Acts on today's bosses? |
|---|---|---|
| G1 field scope | A boss listed under a field group in boss-rules.json, on a map with no event, mirrors its attacks onto every Agent in range (citizens too) while its field key is on. `ATTACK_INTERVAL_FLOOR_MS` spaces the Agents' attacks beside it. | No: no field group ships (P13 adds them). |
| G1 blast step | At a self-destruct kill, the mirror takes the bomb's attack1 box. `BLAST_MODE` ships `OBSERVE`: it logs whom the box holds and hits nobody. | Only in a run or the GM trial. |
| G2 case 4 | A listed boss id inside an event with an open companion run is guarded from control steal. The same id in an all-human event is not. | No: the boss id list ships `none`. |
| G2 revived form | A revived form of a guarded part keeps the human controller its spawn picked. | No (guard off for current ids). |
| G3 catalog | boss-rules.json holds each boss's never-target list, marker items, fakes flag and target priority. Zakum's Eye and fakes flag moved there. Horntail's display parts and Pink Bean's statues are listed. | Yes, same values as before. |
| G3 priority fight | `AgentBossPriorityFight`: grind the first live part of the priority list, fall back to the rest. | No: P14 and P19 use it. |
| G4 | In companion boss scope, a member never hits a monster immune to its damage type or reflecting it (mob skills 140-145). | Yes, inside Easy, Normal and Zakum runs. |
| G5 | Stun, seduce, confuse and banish act on members in companion boss scope. GM6 `!debuff <state> <companion>`. | Yes, inside runs. |
| G6 | Re-enter mode (Scarga, Horntail) and revive-inside mode (Pink Bean). | No: every current boss waits outside. |
| G7 | Party-instance runs keyed by the party's event, the owners cap per event, the portal hook. | No: P14 and P15 add the portal calls. |
| G8 | Papulatus portal: a companion that is offline or not on the leader's map is not checked or logged against the quota. | Only at the Papulatus portal. Players are unchanged. |
| G9 | Boss desk trips (7 hops, way back checked), the trip-map flag (ships empty), a store NPC per boss row. | No: the flag ships empty. |
| G10 | NO_BOSS acts in party-instance runs (test only: adoption already seats the member). | No. |
| G11 | No summons for a REGISTERED member, and for a companion beside its owner on a listed boss's recruit map, where the map forbids summons. | Yes: Zakum's room (211042400) for registered members. |
| G12 | Boss records load at boot and show on the card. BOSS points once per run. | Yes. |

Boss events already skip the PQ-clear hook (`BOSS_EVENT_SCRIPTS`, commit c0836b935, decision 2). G12 did not
change that.

## Config

`agent-engine.yaml` (new keys):

| Key | Value | Why |
|---|---|---|
| `AgentSupplyReadiness.BOSS_DESK_MAPS` | `220080000` | The boss gates where the desk radius applies (Papulatus). Trips there still need the trip-map flag. |
| `AgentBossRunSession.REENTER_TIMEOUT_MS` | `300000` | How long a re-entering member may take to walk back in (5 min). |
| `AgentBossAttackMirror.BLAST_MODE` | `OBSERVE` | Log the self-destruct blast only, until P15 compares it with real hits. |
| `AgentBossDisabledState.CONFUSED_WALK_PX` | `200` | How far a confused member walks the wrong way. |

`config.yaml` (living_world, new key): `COMPANION_RESTOCK_TRIP_MAPS: ""`. A recruit map listed here lets companions
leave it for a shop. P15 lists the Papulatus gate.

boss-rules.json gains rows for `easy_balrog` (label only), `zakum`, `horntail` and `pinkbean`.

No database change.

## Tests

Full suite on d8151abb3: 13,388 tests, 2 failures, 37 skipped (10:44 min). Both failures came from P12 and are fixed in
042d029da: a yaml key the config boundary test could not see, and a test that expected no Easy Balrog rules row. Both
classes pass after the fix.

New test classes: `AgentBossRunPartyEventTest`, `CompanionBossRunsPartyEventTest`, `AgentBossRulesCatalogTest`,
`AgentBossPriorityFightTest`, `AgentCombatCandidateProviderTest`, `CompanionBossRunsSummonGateTest`,
`CompanionBossRunPointsTest`, `BossMirrorWzGeometryTest` (27 Pianus, Horntail and Pink Bean ids against Mob.wz), and
tests for G1, G2, G4, G5, G6 and G8 in the existing boss test classes.

The plan's KPQ and EPQ regression checks:

- The G2 guard never covers the PQ boss ids (P8 step 4 test, still green).
- EPQ's Poison Golem forms stay on today's path (G2 revived-form test).
- An Agent outside companion boss scope still attacks the EPQ stone bug while it is immune (G4 test).
- Confuse from 9300182 changes nothing outside companion boss scope (G5 test).
- KPQ, LPQ and HPQ Agents still summon (`AgentBossRunNoSummonTest`).
- PQ clears still call `partyQuestCleared` (`BossClearsNotPartyQuestsTest`).
- BOSS points for a boss killed outside a companion run still count per kill (`CompanionBossRunPointsTest`).

## GM spot check (from the plan, not done yet)

Crew A on its Lv160 rung. No restart needed after deploy.

1. Run `!warp 180000000`. Stay visible: do not use `!hide`.
2. Run `!bossmirror trial on`. The trial guards every part on your map and mirrors hits onto your companions.
3. Run `!papulatus` (GM3). It spawns a free walking Clock.
4. Bring one companion and hit the Clock together.
5. Run `!botcfg physicsstatus` (GM6). Check that it shows no acquisition.
6. Watch the companion lose HP from mirrored Clock attacks.
7. Run `!debuff STUN <companion>`. Check that the companion stands still.
8. Run `!debuff BANISH <companion>`. Check that it plans again from the spawn point.
9. Run `!killall`, then `!bossmirror trial off`.

## Risks

- Human players' boss clears inside companion runs now give BOSS points once per run, not per kill. A boss with
  several boss=1 parts now gives one award a run, not one a part. A friend gets them only by hitting one of the
  run's bosses, and an owner only when online at the run's end.
- Unconfirmed: the v83 pOption region format. P8's calibration counts answer it for Zakum first.
- Blocking physics on mobile field bosses removes bot knockback, which changes how solo bots fight them (P13).

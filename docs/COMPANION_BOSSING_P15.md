# P15: Papulatus

> Built 2026-10-12 on branch `feat/companion-bossing-p15`, on top of P14. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P15. Not deployed, not live-tested yet. The `papulatus` key ships off.

## Result

An owner can now take their own party companions into Papulatus. The owner enters as party leader through the stock
portal on the gate (220080000). The companions inside then fight as a companion boss run. With `papulatus` off (as
shipped), the fight is stock and nothing below happens.

## What the player sees (with the key on)

Example: `papulatus` on, the owner leading a party with companions, the Piece of Cracked Dimension in the bag.

- A companion with no Papulatus entry left today (2 a day) stays on the map before the gate as the owner walks onto
  it, and says one line ("already went in today. i'll wait out here." or another quota line). The stock portal
  check then never names it, and the rest of the party goes in. It follows again once the owner leaves the gate.
- Inside, the companions hit the Clock and Papulatus first (8500001, 8500002), then the bombs and the Time Sphere.
  They never hit the fake Low Darkstars.
- The Clock's lightning (attack3) strikes a companion only where a bolt lands, not across the whole map.
- A companion that dies revives in Ludibrium, says one line, walks back to the gate on foot (12 maps), and waits
  there for its owner.
- When the owner leaves through NPC 2041025 on the battle map, the companions leave too.
- A companion short of the Papulatus standard at the gate makes one shop trip to the Path of Time (6 maps each way)
  and comes back. With the key off, the gate keeps its "no shop here" line.

## Keys and standards

| Key | Boss | Gate, battle map | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `papulatus` | Papulatus | 220080000, 220080001 | 450/300, 2500 (HP was 300 until the first live run) |

MP equals HP because attack1 is deadly: it sets members to 1 HP and 1 MP.

## Changes from the plan

1. **The hook is in the event script, not the portal.** As in P14: `PapulatusBattle.js` `afterSetup` calls
   `LivingWorldHooks.bossPartyEntered(eim, 'papulatus')` after it closes the gate reactors.
2. **No `BOSS_IDS` entries.** As in P14: the parts are guarded only inside an event with an open Papulatus run
   (`partyBossPart`, over the boss-rules.json row).
3. **A capped companion is held as the owner arrives, not walked off.** The plan says it steps off the gate before
   the owner enters. P15 keeps it on the map before the gate instead, as the owner walks on. One gap remains: a
   capped companion that already stands on the gate (it came out of an earlier run there) is not walked off. The
   portal then refuses the party and names it, and the owner can bench it.
4. **Random spots are opt-in per boss.** WZ marks random-spot attacks on 26 mobs, Zakum's body attack2 and arm
   attack2 among them. Zakum's mirror is live, so P15 changes nothing there. Only a boss whose rules set
   `randomSpots` (Papulatus) uses the new rule. The other bosses can opt in after a live fight checks it.
5. **The walk back is general.** Any boss rules row can name a `deathWaitMap`. Only Papulatus does.

## Config

`config.yaml` (living_world):

- `COMPANION_BOSS_ENABLED_TYPES` accepts `papulatus`.
- `COMPANION_RESTOCK_TRIP_MAPS` changes from `""` to `"220080000"`. The trip goes only while the key lists
  `papulatus`, so nothing changes while it is off.

`agent-engine.yaml`:

- `papulatus=450/300` (300/300 until 2026-10-13) joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `papulatus=2500` joins `AMMO_STANDARDS`.
- New: `AgentBossDeathWalk.TIMEOUT_MS: '600000'` (10 minutes for the 12-map walk back).
- New: `AgentBossAttackMirror.RANDOM_SPOT_HALF_WIDTH_PX: '35'` (half the width of the lightning frames).

boss-rules.json: the `papulatus` row (`targetPriority` 8500001, 8500002; `adds` 8500000, 8500003, 8500004;
`neverTargetFakes`; `deathWaitMap` 220080000; `randomSpots`).

No database change.

## How it is built

- **Run catalog.** `AgentBossRunCatalog.PAPULATUS` is a party row: no expedition, the priority fight, WAIT_OUTSIDE
  deaths, exit NPC 2041025 before and after the clear, and the owner leaving takes the party out.
- **Daily cap (G8).** `RosterService.ownerEnteredMap` asks `CompanionBossRuns.partyGateKeyAt` whether the map is a
  listed party boss's gate. `CompanionBossGateCap` then holds each of the owner's following companions. On
  companion-ops it reads `ExpeditionBossLog.attemptBossByName(id, "PAPULATUS", false)`: a free one is let go at
  once, a capped one says the quota line and stays held. `CompanionFollowGuard` keeps a held companion off follow
  until its owner leaves the gate. A boss with no entry log (Latanica, Neo City) holds nobody past that read.
- **Walk back (decision 9).** `AgentBossDeathWalk` runs in `AgentTickPreflightRuntime`, before follow, as a supply
  trip does. For a member down outside (out DIED) whose owner is still in the run's event, it calls `travelTo` to the
  rules row's `deathWaitMap` each tick and spends the tick. It stops on the gate, on NO_ROUTE, or after
  `TIMEOUT_MS`, once per run.
- **Desk trip (decision 10).** `CompanionRestock.tripsFromGate`: a gate listed in `COMPANION_RESTOCK_TRIP_MAPS`
  sends a desk trip only while the switch lists a boss whose standard a companion keeps there.
- **Lightning spots.** `ServerMobActionCatalog` reads WZ `info/effect0/randomPos` and `effectDistance` into
  `OrdinaryAttack.randomSpotSpacing` (the Clock's attack3: 300). The mirror picks one random phase per accepted
  attack for a boss whose rules set `randomSpots`. At impact, `BossMirrorGeometry.onRandomSpot` strikes a member in
  the box only within `RANDOM_SPOT_HALF_WIDTH_PX` of a spot: spots stand the spacing apart from the box's left edge
  plus the phase. The client picks the real spots and never reports them, so this approximates them.

## Tests

- `AgentBossDeathWalkTest`: walks from Ludibrium and spends the tick; stops on the gate (once per run); not after the
  owner left; not while dead; stops on no route and after the timeout; other bosses and inside members never walk.
- `PapulatusGateRouteTest`: the world portal graph walks Ludibrium to the gate in 12 hops, each a portal.
- `CompanionBossGateCapTest`: a capped companion is held with one quota line until the owner leaves the gate, a free
  one follows; a party boss gate holds only while its key is on.
- `BossMirrorGeometryTest`: the spot rule both ways and for noFlip; spacing 0 means no spots; the Clock's 300 and
  Zakum's 100 from WZ.
- `AgentBossAttackMirrorTest`: the Clock's lightning strikes 7 or 8 of 30 members spread over one spacing; Zakum's
  attack2 still strikes all 30.
- `CompanionRestockTest`: the gate's desk trip only while `papulatus` is on.
- `AgentPartyBossesTest` and `PartyBossScriptsTest` now include PapulatusBattle (Graal runs its `afterSetup`).
- Updated pins: the run catalog list, party rows.

Full suite on bfa122330: 13,431 tests, 3 failures, 37 skipped (10:31 min). The three were pins of the standards
strings. 9da919242 fixes them and also adds `papulatus` to the keys the switch accepts (it was missing, so setting it would
have failed config validation), with a test that every boss run and field group has an accepted key.

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `papulatus`. For the two-owner check, set
   `COMPANION_BOSS_OWNERS_MAX` to 2.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!resetboss <name> PAPULATUS` for each character. It works only for online characters.
4. Run `!item 4031179` as Bossfriend.
5. From Bossfriend, run `!warp 220080000`.
6. For the two-owner check, run `!warphere ohboy` from Bossfriend. His companions follow him.
7. Start the memory logger as a watch.
8. Form the party, enter, then drop the piece from the platform (y=-538, x -148 to -14).
9. Watch deadly attack1 set companions to 1 HP and 1 MP, and watch them drink.
10. Watch the Clock's lightning (attack3). Only some companions should lose HP per bolt, not every one in the room.
11. Check the blast log when bombs self-destruct near companions.
12. Run `!hpmp Sorrel 0`. Check the `Boss death walk` lines: he walks from Ludibrium and arrives at 220080000.
13. Leave through 2041025. Check that the companions come out too.
14. Check that the boss log has an entry for each companion that entered, and none for one that stayed out.
15. For the cap: run Papulatus twice, then walk the owner off the gate and back on. A companion that used both
    entries stays before the gate with one line.

## Live result 2026-10-13 15:31-16:05 (int/p9bb bfa727296, temporary key papulatus)

Owner ohboy (Lv102 Hermit, gm 0) with crew B (Lv100). Bossfriend gave the piece and warps and healed ohboy through the
GM queue. 0 ERROR lines. Key reverted, committed config live at 16:05. Video of the whole run (18.5 min, from the gate
to the exit): `docs/agents/evidence/video/p15-papulatus-2026-10-13.mp4` (local, not in git).

- Shop trip: Fenna, Corvin and Ilse were short of 300 and topped up on the way to the gate ("back, all stocked up").
  PASS.
- Steps 3-8: `!resetboss` for all six; the party entered (run opened 15:45:51, 5 admitted); the piece on the reactor
  (x -128 to -120 on the y=-538 platform) woke the Clock. PASS.
- Fight: cleared at 15:59:15, about 10 min after the Clock woke; Clock 23M HP, Papulatus 1.59M. PASS.
- Mirror: applied=63 in the first minute; no calibration miss for 8500001/8500002 in the whole run (the P14 body
  fix). Step 10 (lightning only near its spots) and step 11 (blast log) not checked separately: no "Boss mirror blast"
  line was logged.
- Step 9: deadly attack and drinking: companions drank 70-300 HP potions each. Finding: Corvin (Dragon Knight, front
  line) used all 300 and died with none left at 15:59:15, as the boss died. The 300 standard is thin for a 10-minute
  fight at this level.
- Step 12: FAIL. `!hpmp Garrick 0` at 15:50:49: down, out=DIED, line "ugh, i'm down. i'll wait at the gate.", and
  "Boss death walk ... from map=220000000 to map=220080000". He then stood at his revive spot (-333,102) in Ludibrium
  for the whole walk and it "timed out" at 16:00:54 (TIMEOUT_MS 10 min). The route exists (12 hops, first portal
  tower00 at -96,29 toward 220010500); after the time-out he walked to that portal at once. Corvin's walk after his
  death did not move either before the run ended. Cause not found yet.
- Step 13: after Machine Apparatus, the three members still inside came out (EXITED) and the run ended. PASS.
- Step 14: bosslog_daily has PAPULATUS at 15:45:51 for ohboy and all five companions. PASS (no member stayed out, so
  the "none for one that stayed out" half was not exercised).
- Step 15 (daily cap after two runs): not run.
- Client memory: ohboy's client peaked at 3,782 MB virtual (of 4,096).

Driving notes: the gate portal (in00 -295,-632) is at the top of the stairs (one jump from the floor at x -290);
the drop platform is reached from the right by the steps at y -452 (x 100-151) and -504 (x 36-87), jump from each
step's left edge.

## Risks

- Unconfirmed: the spot width (35 px) and the even spot spacing are guesses from the effect frames. Retune
  `RANDOM_SPOT_HALF_WIDTH_PX` from the live `Boss mirror` lines.
- Up to about 80 bombs, the map cap (MobSkill.java:320), with PAD 700-1000.
- Unconfirmed: High Darkstars may pull companions off the floor.
- Unconfirmed: whether melee companions reach the flying phase.
- A capped companion already on the gate is not walked off (change 3).

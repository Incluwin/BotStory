# Background attack cadence: fix and economy impact (2026-11-09)

Branch `fix/background-attack-cadence`, rebased on 2026-11-16 onto `feat/companion-manager` 5d9fcfc58 (which carries PvP
V0-V3). Off by default. Not deployed.

> **Since 2026-11-18 (docs/ECONOMY_PLAN.md 9 R4):** merged into the main line with the touch-cooldown fix (R3). The
> on/off switch `AgentCombatActionLockRuntime.BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED` below is gone. One setting,
> `server.agents.capabilities.combat.AgentBackgroundCombatClock.STEPS` (1-5; the ramp step is in ECONOMY_PLAN 9.5), drives the attack lock and
> the touch-hit cooldown together; "on" below means 5. The economy room this file asks for is ECONOMY_PLAN section 9.

## 1. The bug and the fix

- On a map no player sees, an Agent runs `BACKGROUND_ACTIVE` and is ticked every 250 ms
  (`AgentSchedulerConfig.DEFAULT_BACKGROUND_ACTIVE_TICK_MS`).
- Movement makes up for that with `AgentMovementSubstepPolicy`: 5 physics steps a tick.
- The attack lock did not. `AgentCombatActionLockRuntime.tickActionLock` took one 50 ms movement tick off the attack
  lock (or the move window) per scheduled tick, so a 700 ms swing held the next one for 3.5 s.
- **Measured** in the PvP practice duels of 2026-11-09 (`logs/pvp/duels*.csv`, 255 rows with over 5 s engaged):

  | Duel map | Rows | Swings a second | Deferred thinks per swing |
  |---|---|---|---|
  | Henesys 100000000, apparently watched (about 1 deferral a swing is the full-rate pattern) | 56 | 0.83 | 0.99 |
  | Henesys, the other rows | 4 | 0.52 | 1.09 |
  | The 10 other maps (unwatched) | 195 | 0.20-0.30 a map, mean 0.26 | 4.7-8.4 |

  Engaged, a bot swings about 3x as often at full rate as in the background (0.83 / 0.26).
- **The fix**, behind `server.agents.capabilities.combat.AgentCombatActionLockRuntime.BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED`
  (`agent-engine.yaml`, `'false'`):
  - On, the lock runs as many 50 ms countdown steps a tick as movement runs substeps: 5 in `BACKGROUND_ACTIVE`, 1 in
    every other mode.
  - Each step is the foreground step: lock first, then the move window once the lock is clear. Time left in a tick after
    the lock clears goes on to the move window.
  - Movement, foreground timing and PvP (duels already hold full rate, `requireFullRateSimulation`) are unchanged.
- **Rounding.** The next swing comes on the first 250 ms tick at or after the animation ends: 700 ms gives 750, 600 or 540
  gives 750, and 810 gives 1,000. Foreground rounds to 50 ms.
  - Carrying the left-over time into the next lock would remove the rounding. It would also make some gaps shorter than
    the animation, so it was not done.
- Tests: `AgentCombatActionLockRuntimeTest` (10). Every animation from 300 to 2,000 ms clears within one background tick
  of its length. Off, a 700 ms lock holds 3,500 ms. Foreground holds exactly 700 ms either way. Left-over time carries
  over to the move window.

## 2. Other modes and simulations checked

- **`BACKGROUND_ABSTRACT`**: no change needed.
  - `AgentAbstractTickRuntime.tick` runs TownLife only, with no combat and no lock countdown.
  - A lock set before an abstract stint stays frozen until the Agent is active again. At most one swing is late.
  - `AgentBackgroundOutcomeLedger` works on wall-clock heartbeats and reconciliation windows, with no tick-rate
    assumption.
- **`LifeCurve` / `ProgressionLedger`**: not affected by tick rate.
  - Offline citizens follow an age curve (0.5 + L/20 hours a level, 1.2 hours a day). It is unchanged.
  - Loaded citizens level by fighting, and the ledger never lowers a level they earned. So loaded grinders pull further
    ahead of their curve. At Lv 70 the curve already allows 4.0 h a level, against a measured 2.0.
- **`companion_yield` (`BenchYieldModel`) does assume the slow rate.** It holds 21,000 field hours measured mostly on
  unwatched citizens and benched companions.
  - Each row is a running sample-weighted mean with no decay. Full-rate slices move it slowly: after as many new hours
    as old, a row reads (1 + k) / 2 of its old rate, where k is the speed-up below. Every reader lags for weeks.
  - `BenchLedger` offline FARM credit (EXP and the meso haul) is shrunk toward these rows. It will rise as they relearn:
    a second, delayed rise in income.
  - `BenchDutyChooser` and `CitizenSpots` rank camps by these rows. A camp measured after the switch looks better than
    one measured before it.
  - PvP grind-minutes (`docs/PVP_V0_RESULTS.md` V0.6) are a frozen copy of these rows in
    `scripts/config/pvp/grind-minutes.yaml`, read by `server.pvp.PvpGrindMinutes`. They size the rival-challenge rewards,
    which are on in the main line. After the fix, a GMin reward is worth about half a real grinding minute until that file
    is measured again.

## 3. Expected economy impact

**Speed-up of an unwatched grinder (k):**
- Engaged, the bot swings about 2.9x as often (0.83 full rate, about 0.77 with 250 ms rounding, against 0.26).
- At the 10x EXP rate, the 70s band's 824 k EXP an hour is about one kill every 9-18 s. Today that is mostly 3.5 s lock
  holds, so swinging takes most of a grinder's field time.
- Walking, looting, target search (see 5.1), sell trips and crowded maps all hold k below 2.9.
- **Estimate: k is about 2 (range 1.5-3) for kills, EXP and loot an hour.** Quest, PQ and boss income is unchanged: those
  already run at full rate.

**Mesos:**

| | Value | Source |
|---|---|---|
| Faucets that scale with kills | ~1.13 B a day: MOB_DROP 222.7 M + NPC_SALE 910.5 M | `economy_flow_daily` 2026-11-07 |
| Share of that from unwatched grinders (s) | about 0.9 | ~2 humans a week log in. Companions with their owner and PQ/boss runs already run at full rate |
| Organic net today | 0.40 B a day on average (11-02 to 11-08), about 3.5 % of the day's supply (12.8 B on 11-08). It fell from 6.1 % to 1.0 % over the week | daily closes in the server log |
| Controller target | 1 % a day | `economy.CONTROLLER_TARGET_GROWTH` |
| Controller levers | **pinned since 11-01**: pickup 0.30 (`PICKUP_MIN`), luxury 0.50 (`LUXURY_MAX`) | `economy controller` log lines |

- **Added income is F × s × (k − 1)**, where F is the kill faucets above: about **+0.5 / +1.0 / +2.0 B a day** at
  k = 1.5 / 2 / 3.
- Sinks that grow with swings are small. Ammo grows about as fast as swings do. Potions grow less, because touch hits are
  held back by a cooldown with the same bug (5.2). Citizen NPC purchases were 28-75 M a day in all, so they offset tens
  of millions, not billions.
- **Organic growth would be about 7-19 % a day, central 11 %**, against the 1 % target. That is the level of 10-29 to
  10-31 (9-19 %), before the controller's levers took hold.
  - From the 11-08 close (1.04 %), the central case is still about 9 % a day.
- **The controller cannot absorb it.**
  - Pickup and luxury are already at their bounds. The buy-budget scale only moves meso between holders.
  - Citizens' NPC sales scale with the pickup share. At k = 2 they become about 1.73 B a day.
  - To hold today's growth through pickup alone, `CONTROLLER_PICKUP_MIN` would have to fall to about 0.12. To reach the
    1 % target it would have to fall to about 0.08: citizens walking past about 90 % of equip drops.

**EXP and levels:**
- Unwatched grinders' EXP an hour rises about k-fold. V0.6's 1.1-2.9 hours a level becomes about 0.6-1.5.
- Loaded citizens reach their gear bands and job levels about twice as fast. New gear bands push out loadout pieces that
  get vendored, which adds a little more NPC_SALE.

## 4. Before the switch goes on

1. **Give the economy room first.** The options, for the user to choose:
   - lower `CONTROLLER_PICKUP_MIN` (a config change; that lever already exists);
   - add a kill-income lever for the controller, such as a citizen-only meso and drop scale (this bends "bots play by
     player rules", ECONOMY_PLAN section 4);
   - land the rate-aware sinks ECONOMY_PLAN names after E1-E4.
2. **Reset or re-key `companion_yield`** (companion and citizen rows), or scale its rows by the measured k. Otherwise
   bench credit, camp choice and grind-minutes keep the slow-rate numbers for weeks.
3. **Measure it live for one day**, with a restart coordinated with the other sessions:
   - EXP an hour from citizen TRAIN stints per band, before and after;
   - MOB_DROP and NPC_SALE per day;
   - the next daily close's growth.
   - Turn the switch off again if growth goes over about 5 % a day.
4. **Re-run PvP V0.6** on full-rate numbers and rewrite `scripts/config/pvp/grind-minutes.yaml`, so the rival-challenge
   rewards keep their intended size.

## 5. Same kind of bug, left alone (follow-ups)

1. **AI pass.** `AgentTickCadenceStateRuntime.consumeAiTick` adds `MOVEMENT_TICK_MS` (50) a scheduled tick against
   `AI_TICK_MS` (100). In `BACKGROUND_ACTIVE` the AI pass runs every 500 ms, not every tick (250 ms).
   - That pass covers target search after a kill, support heals, buffs and buff potions.
   - A swing at a committed target does not wait for it.
2. **Touch cooldown.** `AgentCombatDamageRuntime.tickMobDamage` counts the touch-damage cooldown (`MOB_HIT_COOLDOWN_MS`
   1,500) down by one 50 ms step a tick. In the background a hit protects for about 7.5 s, so unwatched citizens take up
   to 5x fewer touch hits. They drink fewer potions (a sink) and die less.
   - Fixing it with this switch would add potion spending, which helps the economy, and deaths, which slow grinding.
3. **Physics during a swing.** `AgentActionLockPhysicsService.tickActionLocked` runs one 50 ms physics step a tick while
   the attack lock is held, so background Agents fall and get knocked back in slow motion mid-swing.
   - This is movement, so it was left as is. Shorter locks make it rarer.
4. **Spawn stagger.** `consumeSkipDelay` counts the spawn stagger down by `MOVEMENT_TICK_MS` a tick, so up to 500 ms
   becomes up to 2.5 s in the background. Harmless.

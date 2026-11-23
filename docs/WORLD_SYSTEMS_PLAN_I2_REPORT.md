# I2 learned prices: report (2026-11-23)

Branch `feat/world-prices` on `feat/companion-manager` 3d04e47df. Switch `population.PRICE_LEARNING_ENABLED`, shipped
**off**. No schema change, no new timer, no new meso faucet.

## What was built

- **I2.1 shared UCB.** `population/Ucb.java` holds the bonus `explore x sqrt(ln(1 + N) / (1 + n))`, the best index and
  the `exp((score - best) / temperature)` weighted draw. `CitizenSpots` (draw, weights, bonus) and `InviteBandit`
  (optimistic) call it. `UcbCharacterizationTest` keeps the pre-lift code verbatim and checks both users against it,
  draw for draw on the same seed, plus the same random numbers consumed.
- **I2.1 ask arms.** `PriceArms` is one lever: multiplier arms per key, each estimated as
  `(sum + k x prior(m)) / (n + k)`, picked by `Ucb`. `PriceLearning` holds three levers:
  - ASK: per item class (POTION 200-202, ORE 401/402/4004/4005, SCROLL 204, EQUIP 1xxxxxx, OTHER). Arms 0.85-1.30 in
    steps of 0.05. Prior `m x PRICE_PRIOR_SELL_RATE` inside today's 0.9-1.2, 0 outside. Reward per bundle listed:
    bundle price / index value if it sold while the stall was open, else 0.
  - OFFER (I2.2): SUPPLY requests. Arms 1.00-1.30, prior `PRICE_OFFER_PRIOR_REWARD` within 0.05 of
    NEEDS_SUPPLY_PREMIUM. Reward when the request ends: `filled share x (1 / (1 + hours / PRICE_OFFER_HALF_HOURS) -
    PRICE_OFFER_COST x (premium - 1))`. A request keeps its arm while open, because NeedLedger.reconcile keeps an open
    request's offer.
  - BENCH (I2.2): a live bench STALL's starting ask per item class, before its own markdown. Arms 0.85-1.30, prior the
    index (1.0). Rewarded like ASK, on slices that ran their whole length or sold out.
- **Hard floors.** A learned ask is never under what an NPC shop pays for the bundle (`Market.ask` keeps
  `max(npcPays, ...)`), whatever the index. `nudge` never takes the index under `npcUnit`. An item with no NPC price
  (shop price 0 or -1: untradeable or cash items) has floor 1. The bench start with the 10-25 markdown on top never
  goes under QUICK_SALE 0.85 x index or the NPC price (`BenchStallForm.stallAsk` / `listingPrice`, tested over every
  arm, step and unsold count).
- **Index trajectory** (`PriceIndexTrajectoryTest`). Setup: real Market paths, seed index 1000, NPC floor 400. Browsing
  citizens pay up to 0.9 x the current index and make up a share b of buyers; the rest pay a fixed 1100. 4000 rounds x
  5 seeds. Settled means (rounds 3000-4000), off/on:

  | b | off | on |
  |---|---|---|
  | 0 | 960 | 1030 |
  | 0.1 | 946 | 951 |
  | 0.3 | 742 | 725 |
  | 0.5 | 405 | 458 |
  | 0.7 | 401 | 415 |

  - Asserted: the index is never under the floor, and the settled mean on >= 0.9 x off's. The worst gap seen was -2 %;
    the margin covers seed noise.
  - The fall to the floor at b >= 0.5 happens with the learner off too: it is today's unsold step-down. The learner
    adds deeper transient dips while it explores (min 593 vs 855 at b = 0.1).
- **Off switch.** Off: `Market.newAsks()` is null, `ask()` is `0.9 + random.nextDouble() x 0.3` on the same Random, the
  premium is NEEDS_SUPPLY_PREMIUM, and the bench start is 1.0. Nothing is booked or stored.
  `PriceLearningSimulationTest.offTheStallPricesAreTodaysDrawForDrawOnTheSameSeed` pins this.
- **Simulation.** Synthetic buyers with known reservation prices:
  - Ore: 60 % pay up to the index, 40 % up to 1.25x. The learner picks 1.00, and sell-through rises by more than 20
    points over the fixed rule, at a higher take per bundle listed.
  - Hat: 90 % of gear errands pay 1.30x. The learner picks 1.30 for EQUIP, so each class learns its own price.
  - Offers, with fill odds rising with the premium: the learner converges to 1.25, the best trade-off.

## Where arm statistics live

Rows sit in `companion_yield` with `source = 'price'` (`LivingStore.YIELD_PRICE`), beside the citizens' spot rows
(`source = 'citizen'`):

| Column | Holds |
|---|---|
| job_branch | lever and key, e.g. `ASK_EQUIP`, `OFFER_SUPPLY`, `BENCH_ORE` (at most 16 characters) |
| level_band | the arm's index |
| camp_map_id | 0 |
| samples | rewards counted |
| exp_per_hour | their mean |
| meso_per_hour | the multiplier, for reading only |

The rows are read on first use, not at boot. Until then, and for a minute after a failed read, the fixed rule holds.
Writes go behind on companion-ops. Rollback `043-owner-habits-down.sql` already deletes non-companion rows. Changeset 68
stays free.

The column reuse is documented on `LivingStore.YIELD_PRICE`, not in the table's SQL file. Editing `033` or `043` would
change applied changesets' checksums.

`CompanionYieldPriceRowsTest` proves that no other reader sees these rows:
- `companionYields`/`citizenYields` (BenchYieldModel, citizen spot learning) read `WHERE source = ?` with their own
  source.
- Nothing else in `src/main` touches the table.
- The primary key includes `source`, so a price row never overwrites another source's row.

Outside the server, the live check `B27-bench-yield.check` filters `source = 'companion'`. ECONOMY_PLAN R5 now says to
spare these rows, and the same test pins that sentence.

`/runtime/stats/market` now ends with `"prices":{"on":..,"loaded":..,"ask":{CLASS:{"best":m,"arms":[{"m","n","mean"}..]}},"offer":{..},"bench":{..}}`.

## Board-offer hook (`NeedStore.update(Connection, Need)`)

After `executeUpdate`, for an ended row only (not OPEN or ESCROW):
`NeedDerivation.closed(need, need.closedAtMs(), autoCommit(con))`. There is also a private `autoCommit(con)` helper
that reads `con.getAutoCommit()`, false when unsure.

- **Rollback.** An end written inside a transaction (`LiveNeedHands` hand-ins and `takeEscrow`, `committed = false`)
  waits in `PriceLearning.pending`. The market tick books it once the ledger shows it held (gone from `live`, or live
  in an ended state). If the request is still OPEN or ESCROW in the ledger, the transaction rolled back: the end is
  dropped and its arm goes back.
- **Once.** Booked need ids are remembered (the last 10,000). A row written again (thanks, a retry) books nothing. A
  request created more than 60 s before its arm was picked (an older request, say after a restart) books nothing and
  cannot take a newer request's arm.
- **Never throws.** `NeedOffers` catches and logs any learner failure, and the learner does no I/O on the caller's
  connection. Pinned by `NeedOffersTest` and `PriceLearningOffersTest`.
- **Tuning values.** PRICE_OFFER_HALF_HOURS = 6 (a quarter of the 24 h maximum request life). PRICE_OFFER_COST = 1.0,
  so each point of premium costs a point of reward; with that, 1.30 never beats 1.15 unless it fills much faster. They
  are config keys only because the reward scale is a guess to tune live.

## Hunks in the hot files

- `Market.java` (one writer this wave):
  - the `prices` field, built and installed in the constructor;
  - `statsJson` gains `"prices"`;
  - a `prices()` accessor;
  - `openStall` keeps `session.stallAsks`;
  - `Stock` gains `asks`;
  - `stock()` calls the new `ask(itemId, unit, perBundle, bundles, asks)` in place of the inline
    `0.9 + rand x 0.3`, plus `newAsks()`;
  - `closeStall` counts the unsold bundles per item and calls the new `settleAsks`.

  The buy side (`BUY_BELOW_INDEX`), the index step, `UNSOLD_STEP_DOWN` and `nudge` are untouched (D16).
- `CitizenSpots.java`: `draw` and `weights` delegate to `Ucb` through a small `ucbs()` helper, and the bonus line calls
  `Ucb.bonus`. No behaviour change.
- `BenchLedger.java`: **not touched** (see decisions).
- `NeedDerivation.java`: `supply()` asks `NeedOffers.premium(citizenId, itemId, premium)`, plus a new static
  `closed(Need, nowMs)` and doc lines.
- `NeedLedger.java` and `NeedScreens.java`: not touched. The end-of-request hook is one `if` in `NeedStore.update`,
  plus the `autoCommit` helper above `bindMutable`. N6 may edit NeedStore too.
- `Market.tick`: after `learnSales()`, `prices.confirmPending(population.needs()::need)` when offers are pending.
- Also touched: `InviteBandit` (bonus via Ucb), `BenchStallForm` (learned start in `asks`, `settleLearned`,
  `SOLD_OUT` constant, `Stall` made package-visible for its test), `CitizenSession` (`stallAsks`), `LivingStore`
  (`priceArms`, `savePriceArm`), `PopulationConfig` (8 keys plus validation), `config.yaml` (one block after
  CITIZEN_SPOT_MIN_MS), and `GearErrandsTest` (the pinned statsJson gains the `prices` object).

## Decisions the plan did not make

1. **The reward is revenue in index units** (ask / index when sold, else 0). The plan's literal "margin over index"
   (ask/index - 1) would score every arm below 1.0 under an unsold bundle. Browsing citizens pay at most 0.9 x index,
   so sell-through could not then rise.
2. **The bench lever is the live stall's starting ask, not BenchLedger's 1.8 offline markup.** The offline stall has no
   buyer, so it gives no signal, and `BENCH_OFFLINE_STALL_AT_NPC_PRICE=true` already pins it to 1.0. Learning a markup
   there would mint meso.
3. **Offer reward:** filled share times a speed term, less the premium paid. Without the cost term the learner would
   just raise every offer to 1.30.
4. **The request-end hook sits in `NeedStore.update`, not `NeedLedger.close()`.** Some MET paths (`takeEscrow`,
   `LiveNeedHands`) never pass through close(), and NeedLedger belongs to N6. A MET request updated twice (MET, then
   thanked) is booked once.
5. **PRICE_EXPLORE defaults to 0.6.** At 0.2, an arm outside today's range (prior 0) was never tried against rewards
   near 1.0; the simulation caught it.
6. **Arms are picked per item per stall,** so several stacks of one item share an arm and their bundles add up.
7. **Known gaps:**
   - A stall whose citizen is gone at close books nothing.
   - A SUPPLY request open across a restart books nothing; the 60 s pick guard skips it.
   - An end that rolled back, if its request then ends otherwise before the next market tick, books that later end once.

## Expected effect on the CPI basket

`PriceBasket` prices each basket item from:
1. the day's player-shop sales (at least CPI_MIN_SALES of them), else
2. the median Free Market listing, else
3. the index.

Learned asks move (1) and (2) directly, by the shift in mean ask multiplier against today's ~1.05: at most
0.85 / 1.05 = -19 % to 1.30 / 1.05 = +24 % per item, if a class settles at an end arm. The index itself settles about
where it does today (above).

Expect a level step in the CPI in the first days as arms concentrate. It is not inflation or deflation, and it shows
in `cpi_change`. The controller's budget lever reads CPI, so treat the first week's cpi_change as a re-basing and judge
by the second week.

## Watching the switch (the guard)

Turn it on only after a baseline week (EconomyController closes in `economy_controller_daily`, `/runtime/stats/controller`).

**Read for 7 days after:**
- `growth` against CONTROLLER_TARGET_GROWTH and the week before.
- `budget_scale`, `pickup_share` and `luxury_rate`, in case a lever is pushed to a bound.
- `cpi_change`.
- `/runtime/stats/meso-flows`: NPC_SALE and PLAYER_SHOP_SALE.
- `/runtime/stats/market` → `prices`.

**Expected:**
- Growth is unchanged, because asks only move meso.
- PLAYER_SHOP_SALE rises.
- NPC_SALE may fall slightly, since what sells at a stall is not vendored; that is a faucet getting smaller, not bigger.
- ASK arms concentrate.

**Turn it off if any of these happen:**
- The 7-day mean growth moves more than 0.3 points a day off the week before, or the controller has to pin a lever
  that was free.
- cpi_change falls faster than the week before. This is the index chasing learned low asks: sales feed the index, and
  only the buy side is fixed.
- Stall sell-through over the week does not rise at an equal or better take. Compare the arms' means with the week
  before's journal of PLAYER_SHOP_SALE against listings.

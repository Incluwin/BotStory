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

`/runtime/stats/market` now ends with `"prices":{"on":..,"loaded":..,"ask":{CLASS:{"best":m,"arms":[{"m","n","mean"}..]}},"offer":{..},"bench":{..}}`.

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
- `NeedLedger.java` and `NeedScreens.java`: not touched. The end-of-request hook is one `if` in `NeedStore.update`.
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
   - After a restart, an open SUPPLY request gets a fresh arm entry, so the arm it is credited to may differ from the
     offer it was actually posted at.

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

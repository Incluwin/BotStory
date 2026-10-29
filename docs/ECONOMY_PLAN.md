# One economy: audit, consolidation and anti-inflation plan (2026-10-28)

User request (2026-10-28): audit the economic implementation with the goal of consolidating the systems, building
features and tuning behaviour into a functional, lifelike game economy, keeping inflation (MapleStory's biggest economic
problem) in mind.

**Headline.** The server's money is almost entirely bot money, and bots mint it far faster than anything removes it.
- 22.4 B mesos sit in character purses; citizens hold 22.0 B of it (98.5 %, 22.7 M each on average, against the
  ~1 M their own code calls normal for their level). Humans and other characters hold 275 M.
- Citizens sold 0.6-1.2 B mesos a day of looted gear to NPC shops over the last week and bought 28-75 M a day back:
  NPC buys remove about 5 % of what NPC sales create. Mob meso drops, which the journal does not see, come on top.
- There are four stall paths, four price sources, three ways to buy from a player and two ledgers. Two of the four
  economy systems are off, and one of those is broken in ways that would hurt if it were switched on.
- Almost no real trade happens: 3 player-shop sales ever, and only 2 human characters logged in during the last week.
  The price index has 1,963 items, of which 491 have ever moved, and those moves come from citizens' own stall
  closings, not from buyers.

So the job is less "fight player inflation" than "give the bot population a closed, governed money supply, then let
humans trade into a market whose prices stay put". The user's decisions on the open questions are in section 7.

## 1. What exists

| System | Where | Live? | What it does | Verdict |
|---|---|---|---|---|
| Base Cosmic economy | `MapleMap`, `Shop`, `Trade`, `PlayerShop`, `HiredMerchant`, `FredrickProcessor`, NPC scripts | ON | Mob mesos, NPC buy/sell, trade tax, merchants, Fredrick | Keep; tune (section 7) |
| Population market (citizens) | `server/living/population/Market.java` | ON (`population.MARKET_ENABLED: true`, 30 stalls, 200 k/day buy budget) | Price index (`living_price_index`), citizen stalls, citizens buying from human and companion stalls | **Becomes the one market** (section 4) |
| Bench duties (companions) | `server/living/roster/BenchLedger.java`, `BenchStallForm.java`, `BenchSessions.java` | ON | Offline farm/sell/stall credit; live stalls priced from the index | Keep; fix the minting (E1) |
| Agent economy engine | `server/agents/economy/**`, `economy-engine.yaml`, Postgres `cosmic_economy` | Only on GM6 `!economy start` / `!commerce start`; the MySQL journal half is always on | Scenario runs of agents trading in the FM, double-entry evidence ledger | Keep as an offline scenario tool; it reads the one index (E2) |
| Old bot merchant economy | `server/bot/**`, `bot_economy:` in config.yaml | OFF (`ENABLED: false`) | Virtual hunters, bot hired merchants, treasury | **Retire** (E2): broken (below) |
| SoloMapling FM | `soloMapling/FreeMarket/**` | OFF (`ENABLE_SOLO_MAPLING: false`) | Fake hired merchants, chat sellers | Leave off; mine its GMS-era price tables as an index seed |

Broken or risky pieces found on the way (cite these when the phase touches them):
- `bot_economy` shops would spawn **empty** (`BotMerchantManager.stockFromInventory`, `:271-317`, never calls
  `HiredMerchant.addItem`) at Point(0,0); closing or rotating only updates the DB, so ghost merchants stay on the map
  and their bots are never eligible again (`:105-127`, `:381-401`); nothing writes `bot_sales_ledger`;
  `collectPhase` credits then zeroes in two steps (`:76-77`); `BotPricingService` has no callers.
- SoloMapling `botBuy` pays the seller without debiting the bot (`HiredMerchant.java:907`, `PlayerShop.java:771`):
  a pure faucet the day anyone switches SoloMapling on.
- `economy_transaction_journal` / `_outbox` grow without pruning (266 k journal rows since 09-21); the outbox relay
  never sets `published_at` on a failed row, so a failed row is re-polled first forever (`JdbcCosmicOutboxSource.java:55-58`).
- `MapleMap.java:750-752` turns a meso overflow into a 2.1 B drop (`Integer.MAX_VALUE`) instead of clamping.

## 2. Where mesos come from (faucets)

| Faucet | Where | Size today | Journaled? |
|---|---|---|---|
| **Citizens selling loot to NPCs** | `Shop.sell`, priced by WZ `price` | **0.6-1.2 B/day** (10-20 to 10-27), almost all equips: gloves, hats, overalls at 125-310 k each | yes (`SHOP_SELL`) |
| Mob meso drops | `MapleMap.java:731-772` | Chance x `drop_rate` (3), then amount x `meso_rate` (3): every 40 % meso row becomes a certain drop, **~7.5x vanilla mesos a kill**, auto-picked up so none expire. Drop coupons also set `mesoCoupon` (`Character.java:7946-7953`): 6x meso rate | no |
| Citizen pocket-money top-up | `ProgressionLedger.java:71-74` | On every load, a purse under half of 2000·L^1.5 is topped up to it (Lv 70: 1.17 M), with no daily cap | no |
| Bench offline FARM_MESO | `BenchLedger.java:166-180` | ~1,782·L mesos/h of simulated kills, 16 h/day per owner character | haul only |
| **Bench offline STALL** | `BenchLedger.java:211-215`, `sellHaul :487-503` | Turns the haul into mesos at **1.8x the NPC price with no buyer** | no |
| Quest, event, reactor mesos | `MesoAction.java:54-63`, `EventInstanceManager:220-237`, `ReactorActionManager:173-195` | x meso rate (`USE_QUEST_RATE: false`, so quests use the 3x meso rate) | no |
| Free citizen gear per level band | `CitizenGear.java:87-101`, `ResidentShaper.java:131-146` | 1 equip a slot a band; displaced loadout pieces stay in the bag and can be stalled or vendored | no |
| GM grants | `!meso`, `!givems`, MapleEdit | uncapped | no |

## 3. Where mesos go (sinks)

| Sink | Where | Size / note |
|---|---|---|
| NPC purchases and recharges | `Shop.java:101-170, 353-371` | **28-75 M/day** by citizens; ~5 % of their NPC sales |
| Trade / shop / merchant tax | `Trade.getFee` (`Trade.java:101-116`) | 0 % under 100 k, 0.8 % to 6 % above. Almost every early-game sale is untaxed |
| Fredrick | `FredrickProcessor.java:346-349` | 1 %/day on stored merchant mesos |
| NPC services | salons 1 M, player NPC 7 M, guild 1.5 M + 5 M emblem, divorce 500 k, taxis | Flat nominal prices; citizens found guilds for free (`SocialFabric.java:267`) |
| Hire fee, contracts, bench upkeep | `HiredHands.java:290-315`, `ContractService`, `BenchLedger:219-224` | Small; upkeep comes out of the haul |
| Int overflow | `Character.java:4222-4226` | Anything above 2,147,483,647 silently vanishes |
| Retirement | `Churn.java:251-266` | Retired citizens' purses freeze in the DB (a de-facto sink, never counted) |

Missing against real v83: MTS fees (MTS off), meso lost on the floor (auto-pickup), a meso-priced shop permit
(it is a cash item here). No sink scales with rates or wealth.

## 4. Target: one economy

Six rules everything below follows.

1. **One ledger.** Every meso created or destroyed is booked at its source to a named account (MOB_DROP, NPC_SALE,
   QUEST, EVENT, TOP_UP, BENCH_SIM, GM_GRANT; NPC_PURCHASE, TAX, FREDRICK, SERVICE, ...) and a holder kind (human,
   citizen, companion, GM). Itemised rows stay for trades and shop sales (the existing MySQL
   `economy_transaction_journal`); high-volume flows (mob drops) are summed in memory and flushed a minute at a time
   into a daily flow table. The Postgres engine stays for scenario runs only.
2. **Bots play by player rules.** A bot gets mesos the way a player does: killing, vendoring, quests, selling to
   someone. No top-ups, no sales without a buyer, no free gear that it can then sell.
3. **Bots spend like players.** Their wealth leaves through the same doors a player's does: consumables, gear bought
   from the market, scrolls (which also destroy items), NPC services, tax. That spending is also the demand that gives
   human sellers a market.
4. **One market.** Every stall (citizen, companion, agent) goes through `AgentFreeMarketStallService` +
   `FreeMarketStorePlacementService`; every bot purchase through `AgentFreeMarketBuyerService` (the real
   `PlayerShop.buy`, so the tax applies); bot-to-bot trades are allowed, journaled and taxed.
5. **One price index, learned from trades.** `Market`'s index stays the canonical price, but it learns from executed
   sales (every PlayerShop / hired merchant sale and priced trade, human or bot) with an outlier-robust estimate
   (median of recent sales blended into the EMA), not from its own stall closings. Asks are a weak signal. Seeds come
   from the SoloMapling GMS-era price tables where they exist, else the NPC price multiple.
6. **A governed money supply.** A daily close computes supply by pool and holder, flows by account, a fixed-basket
   price index (CPI) from executed prices, and velocity. A controller nudges bot-side levers (vendor propensity,
   spending propensity, buy budgets) inside bounds to hold CPI flat and supply growth near population growth.
   Human-side levers are config the user sets.

## 5. Phases

### E0. See the money (telemetry first; no behaviour change)
- Daily close table `economy_supply_daily`: mesos in `characters.meso`, `storages.meso`, `characters.MerchantMesos`,
  live hired merchants, `dueypackages.Mesos`, bench haul mesos (`living_roster.haul_meso`); split human / citizen (active vs retired) / companion / GM.
- Flow accounts at each faucet and sink in sections 2-3 (in-memory counters, 1-minute flush, `economy_flow_daily`).
- CPI: a fixed basket of ~40 goods a player cares about (pots, scrolls 60/10 %, stars, ores, popular equips by level
  band), priced from executed sales, with listing medians as a fallback.
- `/runtime/stats/meso-supply` grows the pool and holder split; a MapleEdit page charts supply, flows and CPI.
- Prune the journal and outbox (keep 90 days); fix the outbox head-of-line block.
- **Done when** a day's close balances: opening supply + faucets - sinks = closing supply, within autosave lag.

### E1. Stop the printers (bot side, plus the decided D2/D3 meso changes that players feel)
- Citizen top-up: one-time pocket money at a citizen's first load only, then nothing.
- Bench offline STALL: no sale without a buyer. Offline it sells at the NPC price like SELL (or holds the stock for its
  next live slice); the 1.8x markup only applies to real sales.
- Bench offline FARM_MESO: keep (it stands for kills the companion would make) but book it as BENCH_SIM and calibrate it
  to what `BenchYieldModel` measures live.
- Citizen guilds pay the 1.5 M founding fee like players.
- Free gear: a displaced loadout piece is not sellable (it goes back to the wardrobe pool, not the bag).
- D1: one-time reset of every citizen purse to a level-shaped spread (section 7).
- D2: the meso drop chance stops being multiplied by `drop_rate` (`MapleMap.java:733`, meso rows only).
- D3: drop coupons stop setting `mesoCoupon` (`Character.java:7948`).

### E2. Consolidate (one market, one index, one ledger)
- Retire `server/bot/**` (timers in `Server.java:1237-1249`, `!boteconomy`, its tables after an export).
- The index learns from executed trades (rule 5); bench-stall sales stop being excluded once nothing can mint through them.
- The agent engine's private valuations read the index as their prior.
- Bench live stalls, citizen stalls and agent stalls share one stall/placement/buyer path. They mostly do already:
  the bench form opens through `AgentFreeMarketStallCapability` (walk to a reserved spot, then open), and citizens walk
  to theirs since f0452f9d0; the citizen path can move onto the same capability.
- SoloMapling stays off; its price YAMLs seed the index; its `botBuy` faucet gets a guard or a comment where it lives.

**Built (10-28, branch `feat/economy-e2`):**
- D5: `server/bot/**`, its five timers, `!boteconomy`, `BotEconomyConfig`, the `bot_economy` block, the `bot-*.yml`
  seeds and `scripts/bot-economy` are gone; changeset 56 drops the 19 tables (3 default rows, dumped first to
  `FriendServer/exports/bot-economy/db/bot-economy-tables-2026-10-28.sql`). `bot_flag` columns stay.
- Every player-shop and hired-merchant sale (any seller, any buyer) reaches `Market` through
  `LivingWorldHooks.shopSale`: queued on the buyer's thread, learned on the market tick as the median of the item's
  last 7 sale prices, held within 3x of the index, then the 8 % moving-average step. Sales between one account's
  characters teach nothing. A citizen closing its stall no longer counts as a sale: sold-out lines were learned as they
  sold, and an unsold line still nudges the price down without counting as an observation.
- **Kept, unlike the bullet above:** bench-stall sales stay excluded. The reason was never minting: a bench stall prices
  at 0.85x the index, so its sales would pull the index down every time, and a median does not stop that.
- `MarketPricePrior` (installed by the population) is the agent engine's catalog anchor where the index knows a price.
- Seeds: basket, then the SoloMapling tables at v83 (`PriceSeeds`), then the NPC multiple; an index row no sale ever
  moved takes the new seed at start.
- `botBuy` books what it creates to a `SOLO_MAPLING` ledger account.
- **Not done:** moving the citizen stall onto `AgentFreeMarketStallCapability`. Citizens already use the same stall
  service and placement service and walk to their spot; the move is churn on code fixed this morning, for no change in
  behaviour. D6's buyer rule (citizens buying from citizens) is E3.

### E3. A market that looks alive (features)
- **List or vendor.** Citizens vendor junk and list what is worth more in the market than at the NPC (index vs NPC
  price, stall room, personality). This cuts the biggest faucet and fills the FM with real goods.
- **Buy, don't receive, gear.** `CitizenGear` upgrades become shopping: the citizen looks for its level band's piece in
  stalls (any seller, human first) within a budget, and falls back to the old grant only when the market has none
  (logged, so we see the gap). This is the demand side humans can sell into.
- **Scroll their gear.** Citizens buy and use scrolls on their own equips; failures destroy items (an item sink, and
  meso through the scrolls they buy).
- **Reprice.** Unsold listings mark down, sold-out lines mark up; prices round like players' ("1.2m", "450k").
- **Talk about real listings.** "S>"/"B>" chatter quotes live stalls and needs; the needs board (`population.NEEDS_ENABLED`, off)
  becomes the citizens' buy list.

**List or vendor, built (10-28, branch `feat/economy-e3`).** Measured first (journal, last day): 99.6 % of citizen NPC
sales by value are equips (912 M of 914 M), 95 % of that equips that sell for 50 k or more, each item from 15-28
different citizens (plain loot at 3x drops). Merchants (273 of 730 active citizens) made 31 % of it.
- Agents ask an installed `AgentStallHolds` rule before selling trash equips; the population's rule keeps, for a merchant,
  the best `MARKET_HOLD_MERCHANT` (8) equips the index values at `MARKET_HOLD_OVER_NPC` (1.5x) their shop price or more,
  ranked by how much more; better loot pushes out worse and the rest is vendored. Others keep `MARKET_HOLD_OTHERS` (0).
  Kill switch `population.MARKET_HOLD_FOR_STALL`.
- A stall lists its most valuable stacks first (index price x quantity), so held equips lead, not bag order.
- An unsold line at close pulls the index toward 0.9x min(ask, index). Before, it pulled toward 0.97x the ask, and
  asks average 1.05x the index, so unsold goods pushed their own price up; now gear nobody buys drifts back under
  1.5x its shop price (about 60 unsold closes) and is vendored again.
- No player price goes under what an NPC shop pays (`Market.npcPays`, mirroring Shop's sell): the index stops there,
  and citizen and bench stall asks are floored at the shop's price for the bundle, so nobody can buy at a stall and
  vendor at a profit. The non-equip seed was `getUnitPrice` x 4, which is a star's or bullet's unit price and 0 for
  everything else, so 899 of 2,475 rows sat at the 10-meso minimum on 10-28 (345 of them with "sales" that were old
  stall closes). It is now 4x the NPC price, and at start any row under its NPC price takes a fresh seed.
- Scrolls the player price tables leave out (`ScrollSeeds`): shops pay 1 meso for most, so 190 kinds still sat at
  10. A scroll now seeds at its expected gain (success rate x its WZ bonuses, weighting attack 1, main stats 0.6,
  accuracy / avoid / speed / jump 0.2, DEF 0.05, HP / MP 0.005 a point) times the median mesos per expected point of
  the scrolls the tables price, so it follows their level. At start a row still at the old 10-meso minimum takes a
  fresh seed whatever its sale count says (those counts were stall closes and buys off 10-meso listings).
- **What it does not do alone:** held equips only leave a bag by selling. With few humans buying, the steady-state
  vendoring rate returns once holds fill; the lasting cut needs buyers, which are the next two bullets above (citizens
  buying their gear from stalls) and D6 (citizens buying from citizens).

**Buyers, built (10-28, branch `feat/economy-e3-demand`).**
- D6: browsing citizens buy at other citizens' stalls too, players' stalls first (`MARKET_BUY_FROM_CITIZENS`). The
  ordinary browse rule (under 0.9x the index) rarely fires there, since citizen stalls ask 0.9-1.2x; gear errands are what
  buy from them. Every such sale is the real `PlayerShop.buy`: journaled, taxed from 100 k, learned by the index.
- Buy, don't receive, gear (`GearErrands`, `MARKET_BUY_GEAR`): when `CitizenGear` would hand a citizen a band upgrade,
  the slot becomes an errand instead; in the Free Market it buys any listing that fits the slot, band, job, gender and
  stats (`GearPicks.fitsSlot`) and beats what it wears, at up to `MARKET_GEAR_OVER_INDEX` (1.3x) the index, and wears it
  (the displaced piece goes as before). An errand pulls the citizen to the market (the needs-board weight bump) and from
  the entrance to the room selling a fit. After `MARKET_GEAR_ERRAND_HOURS` (24) with no buy, or when it cannot pay the
  pick's index price, the piece is handed over as before and the gap is counted (`gearErrands: ... handedOver=`) and
  logged. With the needs board on, its GEAR wants keep this role instead. Errands are kept in `living_gear_errand`
  (changeset 57), so a restart keeps each deadline instead of reopening every errand with a fresh day; rows a week past
  their deadline (citizens that never loaded again) are pruned at start.
- `/runtime/stats/market` (and MapleEdit's stats page, "Citizen market") shows stalls open, purchases and the gear
  errands open / opened / bought / handed over since boot.

**Scrolling, built (10-29, branch `feat/economy-scrolling`).** Measured first: citizens held 34,368 scrolls in 760
bags (gloves attack, weapon attack and magic attack ones lead, 500-1,000 of each). Shop visits sell only equips and
unusable ammo, so looted scrolls were never used or sold; only merchants' stalls listed some.
- `CitizenScrolling`: a loaded citizen training, in town, browsing or AFK, its stall shut, no hired hand or persona, uses
  the scroll from its bag worth most to its job on a worn piece with upgrade slots left, about one each eight minutes
  (a 1-in-4 roll at each two-minute market tick), up to `SCROLLS_PER_DAY` (8). Worth is points of its attack: its
  attack 1, main stat 0.6, second stat 0.3, accuracy 0.2 (a mage's 0.05), avoid / speed / jump 0.05, DEF 0.03, HP / MP
  0.005; a scroll is used at `SCROLL_MIN_VALUE` (0.25), so most defence-only scrolls stay in the bag (a 10 % one that
  adds a lot of defence and HP gets over it).
- Live 10-29 07:18 boot: 24 uses in the first four minutes (8 passed, 16 failed, many of them 10 % scrolls), 0 errors;
  a passed one is saved (iHateCookie's Devil's Sunrise: upgrade level 1, 6 slots left).
- Only scrolls that cannot destroy the piece: no dark (WZ `cursed` > 0), chaos, clean-slate or flag scrolls. The item
  sink is the scroll itself and the slot a fail spends; no citizen loses a worn piece to a boom.
- A browsing citizen with no usable scroll of its own buys one at a stall (players' first) worth `SCROLL_BUY_MIN_VALUE`
  (1, about one attack) to its job, for a worn piece with slots, up to `SCROLL_BUY_OVER_INDEX` (1.3x) the index and the
  meso it holds over `SCROLL_BUY_RESERVE` (1.5x) what its level should carry, outside the browse budget like a gear
  errand, and uses it at once. A merchant's stall leaves out the scrolls it would use itself.
- Every use is the real `ScrollTransactionService.apply`: the scroll is used up, the map sees the effect, nearby Agents
  react, the character is saved. `/runtime/stats/market` adds `scrolls` (used, bought, success, fail since boot), and
  MapleEdit's "Citizen market" card a row for them. Kill switch `population.MARKET_SCROLLING`.

### E4. The controller
- Daily: CPI drift and supply growth versus targets set the next day's bot vendor propensity, spending propensity and
  market buy budgets, clamped to bounds in config, with every adjustment logged and charted.
- Kill switch, and a shadow mode that only logs what it would set.

Built (2026-10-28; levers chosen by the user: pickup selectivity, luxury spending, buy budgets; live, not shadow):
- `EconomyController` hears each daily close (`EconomyLedger.onClose`): organic growth = the day's external net without
  the purse reset (RESET), over the supply; target `economy.CONTROLLER_TARGET_GROWTH` (1 % a day). Each setting goes to
  `economy_controller_daily` (changeset 58); a restart starts from the last applied one. `/runtime/stats/controller`.
- **Pickup selectivity** (`AgentLootPolicy.Selectivity`): a citizen (not a hired hand) walks past a share of monster
  equip drops, the same answer for the same drop, never inside an event instance or for a quest or cash equip. Its NPC
  sales scale with the share, so the share that would have held the day to target is share x (target - everything but
  those sales) / those sales; it moves toward that by at most `CONTROLLER_PICKUP_STEP`, never under `PICKUP_MIN` (0.3).
- **Luxury spending**: a daily chance (up to `LUXURY_MAX`) that a citizen over `LUXURY_OVER` x the meso its level should
  carry buys a luxury. It rises only while pickup cannot hold growth alone (the wanted share is under the least) and
  eases off before pickup rises again.
- **E4b, salon visits** (`CitizenSalons`): once a UTC day, with the lever's chance, a loaded citizen (no hired hand, no
  persona) from a region with a salon, holding 1 m over `LUXURY_OVER` x `Market.expectedMeso` for its level, makes a
  TOWN visit to its usual VIP stylist (Henesys or Kerning City for Victoria; Orbis for Orbis and El Nath; Ludibrium, Mu
  Lung, Showa, Amoria, New Leaf City). It walks up, takes a moment, pays 1 m (the salon script's coupon price, booked
  SERVICE) and gets one of the salon's VIP styles in its own colour or its own style in another colour, which players
  around it see; then it plans on. Moved on, logged out or short on the way: it pays nothing. `!citizens salon <name>`
  sends a loaded citizen now, whatever the lever, to test it.
- **Buy budgets**: a scale on `population.MARKET_BUY_BUDGET_PER_DAY` moved against the basket index's day-on-day change
  outside `CONTROLLER_CPI_BAND`, a step at most, within `BUDGET_MIN..MAX`. It only moves meso between holders.
- The basket's first close (10-28) priced 8 of its 33 items at the old 10-meso seed, so the 10-29 close's index jump is
  the price repair, not inflation; it moves budgets one step at most.
- A day closes at the first tick after its UTC midnight, so a server down across that midnight never closed it. At
  start the ledger closes the latest day with flows and no close (`EconomyLedger.catchUp`; the supply at boot is the
  one that day ended with, nothing moving meso while the server is down), and the controller sets from the latest
  close it never heard, when that close is yesterday's or the day before's (`EconomyController.start`).

### E5. Human-side tuning
D2 and D3 were decided on 10-28 and moved into E1; D4 is no. What is left here is anything the E0 numbers show after
E1-E4 run for a while (rate-aware sinks, meso-priced services players want).

## 6. How we will know it works
- Daily net supply growth (faucets - sinks) at or below population growth; bot share of supply falling towards a target.
- CPI within ±5 % a month once E3 is live.
- Sell-through: share of stall listings that sell within a day; citizens buying from humans every day.
- Each phase ships behind a switch, with the full split suite green and a live check like today's FM check.

## 7. Decisions (user, 2026-10-28)

Measured before deciding (10-minute live window, 12:14-12:24, 231 online bots, no humans): bot wallets grew 7.9 M,
about **1.1 B/day**: 0.55 B/day NPC shop net, 0.58 B/day everything else (mostly mob mesos). Against 22.4 B that is
~5 %/day. Wealth rises steeply with level (Lv 80-99 average 60.6 M, 37x `expectedMeso`); 417 active citizens are above
10x. Cap options priced on active citizens (20.0 B): 5x = 3.7 B, 10x = 6.8 B, 20x = 11.2 B, level-spread reset ~2.4 B.

- **D1, existing bot wealth: RESET to a level spread.** Every citizen (active and retired) gets a fresh level-shaped
  purse, lognormal around 3x `expectedMeso` (active total ~2.4 B from 20.0 B). One-time, logged per citizen, with the
  before/after totals booked to the ledger as a RESET account. Lands in E1.
- **D2, meso drop chance: YES, for everyone.** The meso row's chance is no longer multiplied by `drop_rate`; meso_rate
  stays the one meso multiplier (~-60 % mob mesos a kill). Moved into E1 now that it is decided.
- **D3, drop coupons: YES.** A drop coupon no longer sets `mesoCoupon`. Moved into E1.
- **D4, small-trade tax: NO.** Keep 0 % under 100 k (classic v83).
- **D5, `server/bot/**`: DELETE.** Export its tables to a SQL dump, drop them, remove the code, timers and `!boteconomy`. E2.
- **D6, bot-to-bot trades: YES, journaled and taxed.** Citizens shop at any stall, human first, through the real
  `PlayerShop.buy`; the index learns from those sales. E3 (the buyer rule) and E2 (the index learning from trades).

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
| Agent economy engine | `server/agents/economy/**`, `economy-engine.yaml`, Postgres `cosmic_economy` | Only on GM6 `!economy start` / `!commerce start`; the MySQL journal half is always on | Scenario runs of agents trading in the FM, double-entry evidence ledger | Keep as an offline scenario tool; it reads the one index (E2). **Deleted 2026-11-01** (section 8) |
| Old bot merchant economy | `server/bot/**`, `bot_economy:` in config.yaml | OFF (`ENABLED: false`) | Virtual hunters, bot hired merchants, treasury | **Retire** (E2): broken (below) |
| SoloMapling FM | `soloMapling/FreeMarket/**` | OFF (`ENABLE_SOLO_MAPLING: false`) | Fake hired merchants, chat sellers | Leave off; mine its GMS-era price tables as an index seed. **Deleted 2026-11-01**; the tables are `config/living/price-seeds/` (section 8) |

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
   into a daily flow table. The Postgres engine stays for scenario runs only (it was deleted on 2026-11-01, section 8).
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
  Gone with the engine on 2026-11-01.
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
- **Stalls list what errands want (10-29, branch `fix/errand-stall-stock`).** Live at 12:15: 1,020 errands open, 2
  bought in five hours. Not a lack of goods: merchants held 17,297 equips, and 997 of the errands fit at least one of
  them (71 pieces each on average). A stall's 8 lines were ranked by index price x quantity, so scroll stacks (seeded
  at about 1 M each since `ScrollSeeds`) took 68 % of them: about 2 equips a stall, rarely a wanted one. With ~5 stalls
  open at a time, the chance one held a piece for a given errand was 1.5 % (equips first by value would give 5 %).
  Now `GearErrands` notes what each citizen can wear when its gear is checked at a load, and a stall lists first the
  equips that fill the most open errands of loaded citizens (`GearErrands.demand`), then the rest by value: about
  30 % on the same bags. The stall-open log line counts the lines listed for errands.
- **Buyers go to the stall (10-29, branch `feat/errand-seek-stall`).** 24 minutes after the stalls fix, 13 of 16 new
  stalls listed 8 errand pieces, yet one errand was filled: a citizen chose its room only at the entrance, then bought
  only within 900 px of where it wandered (18 of 104 online citizens with errands were in the rooms). Now a citizen
  on a gear errand that bought nothing within reach looks over every room's stalls for a piece it can buy (its errand's
  offer, its purse, a seller it buys from, its own room first): in another room it goes there, in its own it walks
  beside the stall and stops wandering, and the next market tick buys. A stall it has not reached in 3 ticks is given
  up for that visit. Log lines: "goes to X's stall in Free Market room N for ...", "walks to X's stall for ...".
  Live 13:41: 10 errands filled in 40 minutes (5 with the stalls fix alone); all 19 give-ups were citizens with full
  equip bags, and all 7 buyers had room.
- **Citizens go to sell when their bag fills (10-29, branch `fix/citizen-sell-trips`).** 270 of 730 citizens had every
  equip slot full (63 % of those on gear errands), 85 % of it loot a shop visit sells (15,344 of 26,208 pieces for
  another job). They were online (267 of 270 in the last day) but had not sold for days (154 not in 7 days, 42 never):
  a citizen's only shop visits were supply trips, which start when its potions run low, and these carried 1,112
  potions on average (341 for the rest). Now a loaded citizen with `SELL_TRIP_FREE_SLOTS` (8) free equip slots or
  fewer goes, at its next plan, to the nearest potion shop it can walk to (the supply trips' pick,
  `AgentCareerShopCatalog.forSupply`) and asks the shopkeeper the sell-trash visit (`AgentShopService.requestSaleAt`,
  the owner command's visit without its lines): the same sale of trash equips and spare drops a supply visit makes.
  It is re-planned when the sale ends; at most one trip each `SELL_TRIP_EVERY_HOURS` (6). Kill switch
  `population.SELL_TRIPS_ENABLED`. Log line: "goes to sell its loot at map M (N free equip slots)".
  Live 15:55 (`55619552c`): 2,749 shop sales in 40 minutes (310 the hour before), full bags 280 -> 258, sellers now
  ~69 free slots; but 21 of 48 trips failed "never reached the shop NPC": asked from the arrival portal, the visit's
  approach (30 s + its route time) ran out at an unwatched Agent's background tick rate. A supply trip walks to the
  shopkeeper before its visit, so now a sell trip does too: it walks to the counter (as a salon visit does), asks
  within 60 px, or wherever it stands after a minute. Live 17:02 (`597247773`): 74 % of trips sold (54 % before),
  39 errands filled in 50 minutes, full bags 231 -> 204.
- **Kerning City's potion shop (10-29).** 7 of the 11 trips left that never reached a shopkeeper were into 103000002.
  Its shopkeeper (1051002) stands inside the counter's footholds at (-297,-79); shoppers stand on the floor below it
  (y -45), which the door (out00, (287,-67)) opens onto, while its portal 0 is a spawn point on the floor beneath the
  stairs (y 181). Supply trips come in by the door; an unwatched sell trip was put down at portal 0 and walked toward
  the shopkeeper's own spot, which led onto the shelves at that height (citizens left at (96,-88), (15,-152)). Now a
  sell trip comes into its shop by the portal its route enters by, watched or not (`PresenceScheduler.entersByRoute`),
  and walks to the shop visit's own place to stand (`AgentShopService.approachPoint`), worked out on its mailbox.

**Etc room, built (11-05, branch `fix/citizen-etc-room`).** Measured first: 571 of 730 active citizens had all 96 Etc
slots full (183 of 184 at levels 90-119), on a full tab 53 stacks of ores, materials and game pieces and 43 of monster
drops. Nothing empties the tab: a visit's ETC sale (`AgentQuestItemDispositionRuntime`) passes over any item missing from
the quest demand index (108 Victoria items, 28 of them drops), so in the 8 days to 11-05 it sold 32 kinds, every one
indexed, and no other drop. On 11-05 that was 33 citizens, 217 stacks and 0.33 M of the 670 M NPC_SALE (Etc is 0.16 % of
citizen sale mesos over those days, equips 99.8 %); stalls sold no Etc in 7 days. A full tab then takes no Etc at all
(`AgentLootEligibility.hasRoomFor` wants a free slot even for a drop that would stack): in one hour 135 of 144 full-tab
citizens that gained exp gained no Etc piece, against 19 of 21 with room (56 pieces and 0.76 new stacks an hour). Meso
drops and equips still land, so the loss is small: about 3 k mesos a training hour at NPC price, beside 111 k of purse
growth.
- `population.ETC_ROOM_FREE_SLOTS` (16; 0 = off): at every shop visit an unhired citizen sells the stacks
  `AgentEtcRoomPlan` lets go until that many slots are free, quietly, before it buys. No new trips: 561 of the 571 went
  to a shop within 8 days, 279 within a day. 16 slots is about 4 days of refill (0.76 stacks x 5.4 training hours a day).
- Cost, from the 11-05 bags: a first pass of 9,736 stacks and 88.7 M mesos (138 k a visit, 1 % of citizen purses), most
  of it in the first few days, then at most about 13 M a day (under 2 % of NPC_SALE; the refill rate comes from 21
  citizens in one hour, lower level than most).
- User decision 11-06: "no quest asks for" stays what the plan already means for CWKPQ and minigames: not in that index,
  42 stacks on a full tab. Counting every quest in Quest.wz would leave 3.6, and 73 full tabs nothing; citizens hold 32
  started and 3 finished quests between them. Log line: "sold its junk on map M: ... N etc stacks".

**Use room for potions, built (11-11, branch `fix/hp-potion-restock`).** Measured first: 36 of 219 active Lv30+
warriors, 9 of 45 pirates and 13 of 99 bowmen carried no HP potion, against 6 of 235 magicians. 116 of the warriors
had 95-96 of 96 Use slots (about 43 scroll stacks and 9 books each). A potion stack drunk to nothing frees its slot and
the next looted scroll takes it; the shop's room sale (10-13) sold only ammo for another weapon, so the warriors with
none left were refused their HP potions at every visit (454 refusals for 22 citizens in two days, "HP_POTION 0 -> 0").
MP potions they barely drink, so their MP stacks never empty and an MP top-up always finds its stack.
- When the ammo sale cannot free the slots its potions need, an unhired citizen's visit sells the looted scroll or book
  stack worth least by `Market.quote`, one per slot missing (`AgentUseRoomLoot`; never a book for a job of its line,
  a quest item or anything reserved). The readiness checks count those stacks as room. One stack a potion visit is a
  small dent in the hoard (34,368 scrolls, 10-29). Kill switch `population.USE_ROOM_SELLS_LOOT`.

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

## 8. Consolidation (2026-11-01)

User request (2026-11-01): "please consolidate". A review that day found four overlapping economy layers, only one of
which (the citizen `Market`) drove live play. Now there is one:

- **Prices and listings:** `server/living/population` (`Market`, `living_price_index`, `PriceSeeds`, `ScrollSeeds`,
  `EconomyController`, `CitizenSellTrips`). Unchanged; E0-E4, scrolling and sell trips run as before.
- **Money moves and the ledger:** `server/economy`, the transaction layer under every NPC shop, buyback, trade, player
  shop, hired merchant, cash-shop window, needs-board and companion-manager payment (`EconomyTransactionCoordinator`
  into `economy_transaction_journal`), with `ledger/` (meso flows, daily close, CPI, the controller's numbers). It was
  never a duplicate of the engine: the engine sat on top of it.

Deleted:
- The agent economy engine (`server/agents/economy`, 177 files and 67 tests from Kiwiism/cosmic_agents), its satellites
  (`agents/runtime/commerce`, `agents/observation/commerce`, `agents/simulation/activity`, `AgentEconomyRuntime`,
  `AgentCommerceControlRuntime`, the NPC-sale guard only it installed), GM6 `!economy` and `!commerce`,
  `economy-engine.yaml`, `config/economy/`, `economy-database/`, `economy-dashboard/`, `tools/economy/`, the
  `economy-db` compose profile and the engine runbooks in `docs/agents/economy-engine/`. The Postgres `cosmic_economy`
  data on disk was left alone. Future cosmic_agents economy changes have nothing to land in.
- What only the engine read in `server/economy`: the `economy_transaction_outbox` write on every commit (the table
  stays; its old rows drain on `JOURNAL_RETENTION_DAYS`), the per-commit mutation evidence that filled it, and the
  run attribution (`EconomyOperationContext`/`Metadata`, `EconomyTaxOverride`). Player-shop tax is `Trade.getFee`, as
  it already was in live play. Journal rows and their summaries are unchanged.
- SoloMapling's price data: the eleven GMS-era price tables `PriceSeeds` reads moved to `config/living/price-seeds/`
  (the seed map is the same 631 items) and the three non-price `itemConfig` files went. Docker copies `config/`, not
  `src/`, so Docker installs now get these seeds too. The `FreeMarket` and `itemPool` code, the two `botBuy` methods
  and `MesoAccount.SOLO_MAPLING` go in a follow-up once the SoloMapling port (`feat/solomapling-port`) is merged: until
  then the rest of SoloMapling still compiles against them.

Kept on purpose:
- `AgentFreeMarketBuyerService` moved to `server/agents/capabilities/shop`, beside `AgentFreeMarketStallService`:
  citizens buy from stalls through the real `PlayerShop.buy` as before.
- `AgentActivityKind.COMMERCE` and an inert commerce controller: bench stalls hand that kind back and PQ entry asks every
  kind for its controller. The director can no longer start a COMMERCE visit (it never could without an engine run).
- `src/main/resources/agents/catalogs/adaptive/victoria-quest-facts.json` (the adaptive quest catalog reads it).

Still open (E3/E2, not part of this): reprice sold-out lines and round asks the way players do, citizens talking about
real listings, and moving the citizen stall onto `AgentFreeMarketStallCapability`.

## 9. Room for full-speed unwatched fights (2026-11-17, plan; nothing built)

`fix/background-attack-cadence` (a1e7002a9, switch off, not merged) lets unwatched citizens swing at full speed instead of
about a third of it. `docs/BACKGROUND_ATTACK_CADENCE.md` (on that branch) estimates k, the rise in kills, EXP and loot an
hour, at about 2 (1.5-3), on about 90 % of kill income (s = 0.9). This section plans the room the economy needs first.
User: "Plan the room".

### 9.1 Where the economy stands (closes 11-08 to 11-17)
- Supply 12.84 B -> 13.95 B: about 0.98 % a day against the 1 % target (`CONTROLLER_TARGET_GROWTH`; 11-17 was +0.24 %).
- Pickup sat at its 0.30 floor all ten days; luxury switches between 0.4 and its 0.5 maximum; the buy-budget scale rose
  1.46 -> 1.58 (maximum 2.0) because the basket index fell 65.5 -> 58.1 (-11 %).
- So the controller holds growth at target only with two of its three levers at their bounds. The third only moves meso
  between holders.

### 9.2 What actually scales with kills
The raw accounts overstate it. Since the SoloMapling casino switched on (11-08), chip purchases are booked NPC_PURCHASE and
chip cash-outs NPC_SALE (`ChipCashier`), so both accounts carry casino volume:

| 11-17 (UTC day) | Account total | NPC shop part (journal) | Casino part |
|---|---|---|---|
| NPC_SALE | 1,153 M | 558 M vendoring (`SHOP_SELL`: equips 531 M, Etc 25 M) | ~595 M chip cash-outs |
| NPC_PURCHASE | 768 M | 142 M (`SHOP_BUY`, nearly all potions and food) | ~626 M chips, gacha, bar |

- The casino nets about -30 M a day (the house edge). Real vendoring ran 640-930 M a day from 11-07 to 11-14 (about
  0.7 B); MOB_DROP 0.11-0.23 B (11-08 to 11-17).
- Kill-scaling faucets F are therefore about **0.87 B a day**, not the 1.13 B of 11-07 or the 1.33 B the accounts show.
- Added income F x s x (k - 1): **+0.39 / +0.78 / +1.57 B a day** at k = 1.5 / 2 / 3, that is 2.8 / 5.6 / 11 % of the
  supply a day on top of today's ~1 %.
- Vendoring follows the pickup share almost proportionally: pickup 0.7 -> 0.3 took it from 1.47 B to about 0.7 B
  (10-30 to 11-03).
- **Found while planning:** `EconomyController.decide` sizes the pickup lever by `citizenNpcSale`, which now includes the
  chip cash-outs. It thinks the lever moves ~1.15 B when it moves ~0.56 B, so it under-corrects about twofold.

### 9.3 Steps
The plan keeps section 4's rules: no citizen-only meso or drop scale (rule 2); new spending goes through doors a player
uses (rule 3).

- **R1. Book the casino apart (small code change).** A new `MesoAccount.CASINO`, appended so ordinal-keyed tallies keep
  their slots, for chip purchases and cash-outs in `ChipCashier`. Gacha and bar spending stay NPC_PURCHASE: that is real
  spending. `citizenNpcSale` then counts only vendoring, and the meso-flows route and charts show the casino on its own.
  Test: an `EconomyController` case with casino flows.
- **R2. Widen the bounds (config).** `CONTROLLER_PICKUP_MIN` 0.30 -> 0.10 and `CONTROLLER_LUXURY_MAX` 0.5 -> 0.8. While
  growth is on target the controller does not move; it only uses the room when growth rises.
  - Cost: at pickup 0.15 a citizen walks past about 85 % of monster equip drops. It's the same answer per drop, and never
    in events or for quest or cash equips.
  - Rich citizens (over 3x their level's meso) visit a salon up to 4 days in 5.
  - Size at k = 2: pickup 0.15 holds vendoring near today's 0.56-0.7 B, absorbing about 0.5-0.6 B of the +0.78 B.
    Luxury 0.8 adds salon spending, today 0.24-0.85 B a day of SERVICE at 0.4-0.5. How much is unmeasured: it depends on
    how many citizens qualify.
- **R3. A sink that grows with fights (code, its own switch).** Unwatched touch hits run on the same slowed cooldown
  (`BACKGROUND_ATTACK_CADENCE.md` 5.2). So unwatched citizens take fewer hits and drink fewer potions than watched ones.
  Fixing it makes potion buying (142 M a day) grow with kills, as a player's does: about +0.1-0.2 B a day at k = 2. It
  also adds deaths, which slow grinding.
  - **Found while building (2026-11-18): R3 is R4's counterpart, not a sink of its own.** Today both clocks run about 5x
    slow in the background: a 700 ms swing holds 3.5 s and a 1.5 s touch cooldown 7.5 s. So an unwatched citizen takes
    about 0.5 hits a swing, as a watched one does (0.75 s / 1.5 s).
    - R3 alone would make that about 2.3 hits a swing, roughly 5x harder a kill.
    - R4 alone would make it about 0.1, 5x easier: fewer potions, fewer deaths, so even more income.
    - So R3's switch goes on with the cadence switch, at the same steps.
    - The +0.1-0.2 B above is measured against R4 alone. Against today it is about what potion buying already is a kill.
- **R4. Ramp, don't flip (small code change).** `BACKGROUND_SUBSTEP_COUNTDOWN_ENABLED` becomes `BACKGROUND_COUNTDOWN_STEPS`
  (1 = today, up to the 5 movement substeps; read at start, so a restart a step).
  - Steps 2 -> 3 -> 5, each held for at least one daily close.
  - Go on while growth stays at or under 2 % a day; step back above 5 % (the cadence doc's stop line).
  - Measure each step's k from TRAIN stints' EXP an hour.
- **R5. Re-key what learned the slow rate** (cadence doc section 4): scale `companion_yield` rows by the measured k, or
  reset them. After the last step, re-run PvP V0.6 and rewrite `scripts/config/pvp/grind-minutes.yaml`. Only rows of
  `source = 'companion'` and `source = 'citizen'` learned a kill rate: leave the `source = 'price'` rows (the learned
  price levers, WORLD_SYSTEMS_PLAN I2, whose job_branch and level_band are a lever key and an arm) as they are.

### 9.4 Projection and decisions
- At k = 2 after R1-R4: +0.78 B, less about 0.55 B (pickup 0.15) and about 0.15 B (potions), with up to ~0.3 B of luxury
  headroom left. That is about 1-1.7 % a day, inside the controller's reach.
- At k = 3 the floor (0.10) and luxury both end at their bounds. The ramp stops at the step where growth holds.
- Order: R1 + R2 together (one deploy), then R3, then R4 (merge the cadence fix with the step count and ramp), then R5.

Decisions for the user:

| | Question | Recommended |
|---|---|---|
| RD1 | Pickup floor 0.10 (citizens may walk past up to 90 % of equip drops), or 0.15? | 0.10, so the ramp has room |
| RD2 | Luxury maximum 0.8 now, or first add more luxuries (face and skin at the same stylists) so rich citizens don't restyle nearly every day? | 0.8 now; more luxuries later |
| RD3 | Book the casino apart (R1)? | Yes: without it the controller's pickup sizing stays wrong |
| RD4 | Use the touch-hit fix as the fight-scaled potion sink (R3)? | Yes |
| RD5 | Ramp in steps (R4), or turn the cadence fix fully on at once? | Ramp |

### 9.5 Status
- **R1 + R2 LIVE 2026-11-17 21:52** (user: "Yes, build R1 + R2"; RD1-RD3 as recommended): branch `feat/economy-room-r1r2`
  7ee57dab3, merged into the main line as 4db9b2916; full split suite 16,430 / 0 / 33.
  - R1: `MesoAccount.CASINO`; `ChipCashier` books chip purchases and cash-outs to it (`ChipCashierBuyTest`). Live: the
    first chips (2 purchases, 16 M) booked CASINO two minutes after the boot. Before 21:52, the 11-18 (UTC) day's
    NPC_SALE and NPC_PURCHASE still hold casino volume.
  - R2: `CONTROLLER_PICKUP_MIN` 0.10 and `CONTROLLER_LUXURY_MAX` 0.8 in config.yaml and `EconomyConfig`. The controller
    first uses them at the 11-18 close; with growth near target it is not expected to move far.
- **R3 built 2026-11-18, switch off** (user: "Build R3"): branch `feat/economy-room-r3` 92261c93d, merged into the main
  line as da449dbd1; full split suite 16,435 / 0 / 33.
  - `AgentCombatDamageRuntime.touchCooldownCountdown`; `AgentCommonTickRuntime` passes the movement substeps in.
  - The switch is `AgentCombatDamageRuntime.BACKGROUND_TOUCH_COOLDOWN_COUNTDOWN_ENABLED` in agent-engine.yaml, `'false'`.
  - Tests: `AgentCombatDamageRuntimeTouchCooldownTest` (5). A 1.5 s cooldown clears in 7.5 s in the background off and
    in 1.5 s on; foreground stays 1.5 s either way.
  - Not deployed: the switch is off, so the live server (4db9b2916) behaves the same.
  - A trial merge with `fix/background-attack-cadence` is clean. R4 should drive both switches from one step setting.
- **R4 built 2026-11-18; step 2 LIVE 14:23** (user: "Build R4", then "deploy step 2 when the baseline finishes").
  - `feat/economy-room-r4` 121ec77d3 merges `fix/background-attack-cadence`. `AgentBackgroundCombatClock.STEPS` (1-5)
    replaces both on/off switches and drives the attack lock and the touch-hit cooldown together.
    `AgentBackgroundCombatClockTest` checks hits a swing stay at the watched level at every step. Merged as c37039efc;
    full split suite at STEPS 1: 16,446 / 0 / 33.
  - Ramp step 2: `feat/economy-room-r4-step2` b11596ac0, merged as af27457b4; full split suite at STEPS 2:
    16,446 / 0 / 33. Live checkout detached at af27457b4, restarted 14:23 with nobody online, 0 errors.
  - Ramp steps are main-line commits, each with a full suite run: there is no live-only override of agent-engine.yaml.
  - **Measured** by citizens' MOB_DROP pickups (a fixed share of kills), about 248 citizens loaded in both:

    | Window (11-18, local) | Clock | Pickups | Per hour |
    |---|---|---|---|
    | 12:36-14:21 (two of three half-hours beside a suite run) | 1 | 10,524 | about 6,000 (5,490-6,780) |
    | 14:30-16:00, from 7 min after the restart | 2 | 21,013 | about 13,900 (11,570-15,200) |

  - So k is **about 2.3 at step 2**, more than the doubled clock alone. That is an upper bound: maps refill on a restart,
    so the first hours after one run hot, and the baseline was mid-session. At full speed k may be above the planned 2.
  - Today's CASINO shows cash-outs above purchases (563 M in, 522 M out): chips bought before 21:52 on 11-17 booked
    NPC_PURCHASE and were cashed afterwards as CASINO. It evens out from 11-19.
  - Next:
    - the 11-18 close (19:01 local) has about 4.6 h of step 2; the 11-19 close is the first full day;
    - step 3 only after the 11-19 close shows organic growth at or under 2 %;
    - back to 1 above 5 %;
    - the controller moves pickup 0.3 -> 0.15 -> 0.10 and luxury up a step a close on its own.
- **11-18 close (19:00:46; about 4.6 h at step 2): organic growth 2.91 %** (+418 M on 14.37 B), against 0.24 % on 11-17
  and about 1 % before. Between the gates, so **hold step 2**; the 11-19 close decides step 3.
  - Controller: pickup 0.30 -> 0.19 (the share it wanted, inside one step); luxury stays 0.4, since pickup alone can
    cover it. Budget x1.58 (basket -0.65 %, inside its band).
  - Citizens, 11-18 against 11-17:

    | Account | 11-17 | 11-18 |
    |---|---|---|
    | MOB_DROP | +179 M | +209 M |
    | real vendoring (journal) | +558 M | +646 M |
    | shop buying (journal) | -142 M | -154 M |
    | SERVICE (salons, 1 M each) | -531 M (531) | -313 M (313) |
    | casino, net | about -30 M | about +32 M |

  - The +385 M jump splits as:
    - salon spending down 218 M (luxury 0.5 -> 0.4 on 11-17's decision, and a restart at 14:23);
    - step 2 about +118 M (vendoring +88 M, MOB_DROP +30 M);
    - the casino about +62 M;
    - shops -12 M.
  - The casino line is transitional: chips bought before R1 (21:52 on 11-17) booked NPC_PURCHASE and were cashed as CASINO.
  - Step 2's +118 M in 4.6 h scales to about +0.6 B a full day before the levers (about +4 %). The cut to pickup 0.19
    takes about 0.25 B off vendoring. 0 ERROR lines since 14:22.
- **11-19 close (19:01:30; the first full day at step 2): organic growth 3.08 %** (+456 M on 14.82 B). Between the
  gates again, so **hold step 2**.
  - Another session merged UX fixes into the main line and restarted the live server at 06:00 on 7c70f43c7. That
    commit contains R1-R4 and step 2 (STEPS still 2). Loaded citizens ramped back from about 87 to 248 by noon, so the
    day mixes the night on the old process with a fresh boot.
  - Controller: pickup 0.19 -> **0.10 (the new floor)**; luxury 0.4 -> 0.5, since pickup can no longer hold growth
    alone. Budget x1.58 (basket -1.12 %).
  - Citizens:

    | Account | 11-10..11-16 average | 11-18 | 11-19 |
    |---|---|---|---|
    | MOB_DROP | +158 M | +209 M | +289 M |
    | real vendoring (journal) | about +700 M | +646 M | +653 M |
    | shop buying (journal) | about -140 M | -154 M | -213 M |
    | SERVICE (salons) | -476 M | -313 M | -267 M |
    | CASINO (clean from today) | - | +40 M | -1 M |

  - Kill pickups: 196,086 for the day, against 140,120 on 11-18 and about 116,400 a day for 11-10 to 11-16. Mesos per
    pickup were flat at 1,475.
  - That is about 8,170 an hour against the step-1 baseline of about 6,000 at 248 loaded: **k about 1.4-1.7 on a full
    day**, so the 2.3 measured right after the 14:23 restart was mostly refilled maps.
  - What the levers did:
    - Pickup at 0.19 held vendoring flat while kills rose about 40 %.
    - Shop buying (potions) rose by 59 M, the R3 sink at work.
    - Salon spending kept falling (531 -> 313 -> 267 visits).
  - Outlook for 11-20: pickup 0.10 takes about half off vendoring (roughly -0.3 B), and luxury at 0.5 adds some salon
    spending. That may bring growth to around the target.
  - With pickup at its floor, only luxury (up to 0.8) is left for step 3. 0 ERROR lines in today's log.
  - Gate: step 3 after a close at or under 2 %. The 11-20 close is the first with pickup at 0.10.
- **11-20 close (19:01:31; the first day with pickup at 0.10 and luxury 0.5): organic growth 2.39 %** (+362 M on 15.18 B),
  down from 3.08 %. Still between the gates, so **hold step 2**.
  - Controller: pickup stays 0.10 (floor); luxury 0.5 -> 0.6; budget x1.58 -> x1.63, because the basket fell 2.66 %
    (outside its band).
  - Live unchanged: 7c70f43c7, STEPS 2, no restart, 0 ERROR lines. About 196 citizens loaded on average (afternoons 248).
  - Citizens, 11-19 -> 11-20:

    | Account | 11-19 | 11-20 |
    |---|---|---|
    | real vendoring | +653 M | +415 M |
    | MOB_DROP | +289 M (196,086 pickups) | +281 M (178,339) |
    | shop buying | -213 M | -204 M |
    | salons | -267 M (267) | -216 M (216) |
    | CASINO | -1 M | **+88 M** |

  - **Pickup works:** 0.19 -> 0.10 cut vendoring 238 M (36 %), near the projected 0.3 B, on 9 % fewer kills.
  - **Without the casino the day was about 1.8 %**, under the gate. Two things push the other way:
    - **The casino paid citizens 88 M net**: chip cash-outs over purchases. It is a sink only if the house edge wins over
      time; two clean days so far (-1 M, +88 M). Watch it, and if it stays positive, check payouts against stakes
      (blackjack, dice, the drop game).
    - **Salon visits keep falling while the luxury chance rises**: 531 -> 313 -> 267 -> 216 a day, while luxury went
      0.4 -> 0.4 -> 0.5. The lever is not delivering. Is the pool of rich, loaded citizens near a salon shrinking, or
      are fewer TOWN visits planned now that grinding is faster? With pickup at its floor, luxury is the only lever left
      for step 3.
  - Decision before step 3: look at the salon visits (and the casino payouts) first, or take step 3 on the
    casino-adjusted 1.8 %. By the gate as written, step 2 holds.
- **Why salon visits "fell" (user: "Look into salons", 2026-11-20).** Mostly restarts, not the lever.
  - `CitizenSalons.planned` keeps "rolled today" in memory (`rolledDay`). Every restart lets each eligible citizen roll
    again the same UTC day. Visits complete almost every time once a citizen sets out (no walk-outs logged).

    | Local day | Restarts | Visits | Different citizens | Visits each |
    |---|---|---|---|---|
    | 11-12 | 4 | 464 | 323 | 1.44 |
    | 11-14 | 8 | 723 | 375 | 1.93 |
    | 11-16 | 7 | 417 | 290 | 1.44 |
    | 11-17 | 6 | 494 | 319 | 1.55 |
    | 11-18 | 1 | 269 | 218 | 1.23 |
    | 11-19 | 1 | 299 | 251 | 1.19 |
    | 11-20 (to 19:15) | 0 | 233 | 216 | 1.08 |

  - So the ~0.48 B a day of SERVICE in deploy-heavy weeks was inflated. The true salon sink at luxury 0.4-0.5 is about
    0.25-0.3 B a day. Part of the growth rise from 11-18 came from fewer restarts, not from step 2.
  - The pool is not shrinking:
    - 438 of 632 active citizens in salon regions qualify (purse over 1 M + 3x `Market.expectedMeso`). They hold 8.1 B.
    - 95 more qualify in regions with no salon (Rien, Malaysia, Singapore, Aquarium, Ereve, Ariant, Leafre, Ellin,
      Coke Town, Crimsonwood). They hold 2.9 B, averaging 25-45 M each, and no luxury reaches them.
  - The lever's ceiling is one visit a day per rich loaded citizen. At 0.8 that is about 350-400 visits (0.35-0.4 B):
    **only about +0.1-0.15 B over today**, too little for step 3 with pickup already at its floor.
  - Options before step 3:
    - (a) keep the daily roll across restarts (an honest, smaller sink);
    - (b) more luxuries at the same stylists, such as face and skin (VIP plastic surgery and skin care), each with its
      own daily roll, so the lever can absorb more;
    - (c) a salon trip or a local luxury for the 95 citizens in regions without one;
    - (d) wealth-scaled luxuries (a rich citizen spends more than 1 M).
- **Open:** the ramp (steps 3 and 5, gated on the closes above), then R5 (companion_yield, PvP grind-minutes).

### 9.6 More room for the luxury lever (built 2026-11-20, live 2026-11-21 05:09)

User: "Build a, b and c" (options from 9.5). Branch `feat/economy-luxury` 8eb82cbbc, merged into the main line as
85d2aa3d1. Full split suite 16,456 / 0 / 33.

- **(a) Rolls kept across restarts.** Each citizen's daily roll for each look is stored in `living_luxury_roll`
  (changeset 63, file 054), read once at the first roll after a start. A restart no longer rolls the same UTC day
  again. The table starts empty, so on the deploy day every citizen rolls once more.
- **(b) Face and skin.** Besides the 8 hair salons, `CitizenSalons.SALONS` now has 9 plastic surgeons and 7 skin-care
  shops, each 1,000,000 meso like their scripts' coupons. Each look (HAIR, FACE, SKIN) has its own daily roll at the
  luxury lever's chance; one plan sends a citizen to one stylist at most, so a rich citizen can make up to three visits
  a day. A face is one of the surgeon's VIP faces in the citizen's own eye colour (the scripts' rule); a skin is another
  tone from the shop's list. Showa and Amoria have no skin-care shop in the scripts.
- **(c) Regions without a stylist.** `CitizenSalons.NEXT` sends a citizen to the next region along the way players
  travel: Rien, Ereve, Ellin Forest, Coke Town and Singapore to Victoria; Malaysia to Singapore; Aquarium, Leafre and
  Ariant to Orbis; the Temple of Time to Leafre; Crimsonwood to New Leaf City. Singapore and Ariant have their own
  surgeon and skin-care shop, so only their hair visits travel.
- `!citizens salon <name> [hair|face|skin]` sends a loaded citizen to any of the three.
- Tests: `CitizenSalonsTest` (11, 6 new: three looks a day, kept rolls, far regions, faces, skins, every stylist's
  script list) and `LuxuryRollSchemaTest`.

**Projection.** Honest salon visits at luxury 0.5 were about 250 a day (9.5). At the 0.6 set for 11-21, three looks
and the far regions give roughly 850-950 visits, **about 0.8-1.0 B a day against about 0.25 B now**. On 11-20's
numbers (+362 M) the first full day would close near -2 %. The controller then lowers luxury 0.1 a close (about
0.15 B each), so growth should come back near target in about three closes, with luxury near 0.3. At the 0.8
maximum the lever reaches about 1.2-1.3 B a day, so step 3 has room. The money still leaves only as SERVICE, so no
new account is needed.

**Live 2026-11-21 05:09** (user: "Yes, deploy now"): the live checkout moved to d9bb538d0 with nobody logged in, and
the server restarted. Changeset 63 created `living_luxury_roll` at boot. In the first 7 minutes: 12 visits done (5 hair,
6 face, 1 skin), 0 left without a new look, 0 errors. They included Singapore's own surgeon and Ariant's own skin-care
shop. Rolls are being stored for all three looks. The 11-21 close (19:01) is the first with the new sink, for part of
the day; 11-22 is the first full day.

## 10. The price index ratchets down (found 2026-11-23 by WORLD_SYSTEMS_PLAN I2; guard built, off)

### 10.1 Mechanism
Each `living_price_index` row moves 8 % (`Market.INDEX_STEP`) toward one of two observations:
- **A sale:** the median of the item's last 7 sale prices, within 3x of the index.
- **A line still unsold when its stall closes:** `0.9 x min(ask, index)` (`UNSOLD_STEP_DOWN`). Each such close takes
  the index down about 0.8 %.

Nothing pulls the index back up except sales above it. Browsing citizens buy only under `0.9 x` the **current** index
(`BUY_BELOW_INDEX`). So:
- Stalls whose buyers are mostly browsers sell at or under 0.9 x index, or not at all.
- Both observations then sit under the index, and it falls geometrically.
- It stops only at the hard floor, what an NPC pays for one (`nudge` never goes under `npcUnit`).

Only buyers who value an item in absolute terms (humans, needs-board wants, gear errands up to 1.3x) hold it up.

### 10.2 Measured
`PriceIndexTrajectoryTest` runs the real Market paths: ask, then `sold` + `learnSales` or `unsold`. Setup: one item,
seed index 1000, NPC price 400. A share b of buyers are browsers (up to 0.9 x the current index); the rest pay a fixed
1100. 4000 stall rounds x 5 seeds. The table gives the settled mean index (rounds 3000-4000) under the fixed ask rule
(`0.9 + rand x 0.3`) and under I2's learned asks:

| b (browsers) | fixed rule | learned asks |
|---|---|---|
| 0 | 960 | 1029 |
| 0.1 | 946 | 944 |
| 0.3 | 742 | 703 |
| 0.5 | 405 (the floor) | 476 |
| 0.7 | 401 (the floor) | ~415 |

The fall to the floor at b >= 0.5 is today's rule, not the learner's.

### 10.3 Live, read-only (`/runtime/stats/cpi`, 11-23)
- **Trend:** CPI 100 (10-28) -> 67.5 (11-07) -> 58.1 (11-17) -> 54.8 (11-22). The 10-29 close read 941, the repaired
  10-minimum rows. That fits the 0.56 ratio over 30 closes.
- **Source:** 32 of 33 basket items are priced from the INDEX (one from listings, none from sales), so the CPI is the
  index itself.
- **Against the NPC price (WZ `price`):**
  - 1452022 sits on the floor (1.00x).
  - 2020014 is near it (1.48x).
  - The potions 2000004/5/6 and 2002004 are at 4-6x, their seed: they never moved.
  - Equips are at 2.5-500x.
  - Scrolls cannot reach a floor: their NPC price is 1.
- **Reading:** most of the basket is still falling toward the floor, not on it. The decline is this mechanism in
  progress. With scrolls (NPC price 1) it has no practical floor.

### 10.4 Candidate fixes (first two; measured in 10.5, where a third replaced them)
1. **Learn the index only from sales at or above 1.0 x index, and drop the unsold step-down,** or step it toward the
   index rather than under it.
   - Changes: an unsold line no longer lowers the index, so stalls that never sell no longer drag it down.
   - Cost: an item nobody wants stays priced high. Today's way back to the shop for unwanted gear (E3 list-or-vendor)
     then needs another signal, such as the listing's age.
2. **Score against a stable anchor:** the item's seed price (`PriceSeeds`/`ScrollSeeds`/4x NPC), not the moving
   index. Browsing citizens would buy under 0.9 x seed, and the unsold step-down would be capped at some share of the
   seed.
   - Changes: the index can still move, but buyers no longer follow it down, so it cannot chase itself.
   - Cost: real price discovery away from the seed is slower, and the seeds have to be right.

**Test first:** candidate 1. It is one rule in `Market.nudge`/`unsold`, and `PriceIndexTrajectoryTest` already
measures it.

### 10.5 Candidates measured (2026-11-23)
Same setup as 10.2. Cells: settled mean [lowest-highest index], sell-through. "learned" = PRICE_LEARNING_ENABLED.
| rule | b=0 | 0.1 | 0.3 | 0.5 | 0.7 |
|---|---|---|---|---|---|
| today, fixed | 960 [896-1018] 81% | 946 [855-1018] 78% | 742 [495-1000] 69% | 405 [400-992] 50% | 401 [400-992] 30% |
| today, learned | 1029 [929-1061] 96% | 944 [683-1057] 87% | 703 [400-1018] 74% | 476 [400-992] 55% | 410 [400-992] 31% |
| C1 fixed | 1076 [1000-1079] 44% | 1076 [1000-1082] 39% | 1074 [1000-1079] 31% | 1073 [1000-1078] 23% | 1068 [1000-1071] 14% |
| C1 learned | 1058 [1000-1058] 97% | 1054 [1000-1060] 89% | 1033 [1000-1052] 93% | 1021 [1000-1054] 94% | 1008 [1000-1015] 94% |
| C2 fixed | 960 [896-1018] 81% | 947 [858-1016] 79% | 908 [779-997] 77% | 829 [744-992] 81% | 804 [737-992] 81% |
| C2 learned | 1029 [929-1061] 96% | 919 [766-1056] 91% | 847 [615-1000] 94% | 844 [747-992] 96% | 845 [757-992] 96% |
| C3 2% fixed | 969 [916-1016] 78% | 959 [901-1018] 74% | 935 [847-1006] 64% | 877 [770-992] 50% | 771 [679-992] 30% |
| C3 2% learned | 1032 [944-1055] 96% | 1000 [879-1057] 88% | 764 [576-1012] 80% | 572 [569-992] 88% | 570 [569-992] 91% |
| C3 4% fixed | 974 [932-1020] 76% | 966 [914-1017] 71% | 948 [889-1011] 60% | 924 [852-1000] 48% | 877 [795-992] 30% |
| C3 4% learned | 1031 [947-1053] 97% | 1002 [915-1053] 88% | 835 [748-1024] 82% | 751 [748-993] 89% | 750 [748-992] 92% |

- C1: index learns only from sales at or over it; unsold closes do nothing.
- C2: browsers buy to 0.9 x seed; the unsold step never aims under 0.7 x seed.
- C3 (new): nudge as today, then every update also pulls the index a share (2/3/4%, in log terms) back to its seed.
- 1 % and 3 % for C3 are in price-drift/candidates-raw.txt, with every mix below.

Other mixes (fixed / learned):

| rule | never sells (b=1) | half browsers, half errands at 1.3x index | all errands | worth 500, b=0 | worth 2200, b=0 |
|---|---|---|---|---|---|
| today | 400 / 400 (floor) | 405 / 448 | 130 M / overflow | 436 / 466 | 1924 / 2059 |
| C1 | 1000, sells 0% / 96% | 608,974 / 1999 | 415 M / overflow | 1000, sells 0% / 0% | 2153 / 2116 |
| C2 | 787 / 842 | 829 / 844 | 130 M / overflow | 706, sells 0% / 0% | 1924 / 2059 |
| C3 2% | 642 / 570 | 881 / 572 | 1240 / 2084 | 641, sells 0% / 80% | 1240 / 1808 |
| C3 4% | 798 / 750 | 939 / 752 | 1114 / 1443 | 798, sells 0% / 0% | 1114 / 1523 |

**Today's rule also runs away upward.** Gear errands and scroll wants pay up to 1.3 x the current index, so with only
such buyers the index grows without limit, and C1 does it with half of them too. No citizen offer is absolute: browsers
0.9 x, board wants 1.0 x, errands and scrolls 1.3 x the index. Only an anchor outside the index bounds both ways.

### 10.6 Built: the anchor pull (C3), off
`PRICE_INDEX_DRIFT_GUARD_ENABLED` (false), `PRICE_INDEX_ANCHOR_PULL` (0.04). `Market.nudge` multiplies the stepped index
by `(anchorPrice / current) ^ PULL`: each update goes 4 % of the way, in log terms, back to the item's seed price. The
anchor is `Market.anchorPrice`, one accessor (the seed today), so another anchor is a change in one place. The pull is
per observation, so how often an item is listed does not change where it settles.
- **Band** (`PriceIndexDriftGuardTest`): 0.65 to 1.0 x what absolute buyers pay for browser shares 0 to 0.7, fixed and
  learned asks; 0.65 to 1.6 x the seed at every round with relative buyers only (50/50, errands only, browsers only).
  Over 1.0 x nobody absolute buys; the low end is the lowest settled mean measured (750 of 1100 = 0.68) with room for
  seed noise. On today's rule the same test fails (405, the floor, at b=0.5; over 100 x the seed with errands only).
- **Off** is today's rule exactly, 4000 rounds against a copy of the old arithmetic.
- **With the rest:** the NPC floor still bounds it (test). The bench markdown and its 0.85 floor are shares of the index
  and bench sales still do not move it. The buy-budget lever scales what browsers may spend and reads nothing of the
  pull. Asks only move meso between holders: no meso is made.
- **Cost:** an item worth half its seed holds at 0.75-0.8 of seed and does not sell (simulation); a lower PULL lets it
  fall further (2 %: 0.64 fixed, 0.57 learned) and also lets absolute demand over the seed pull further.
- **Scrolls and NPC price 0 or 1:** floor today: `max(1, NPC price)`, so 1 meso for a scroll, and 1 for an item with no
  NPC price. With the pull a never-sold one settles near 0.8 of its seed (fixed asks): that is its floor in effect. No
  separate hard floor was added. Prices of a few mesos do not move: rounding swallows 4 % of 5.

### 10.7 What happens to stock that does not sell
| Stock | Today | Guard on |
|---|---|---|
| Merchants' equips (holds: best 8 at >= 1.5 x NPC) | relisted, index falls, then vendored once under 1.5 x | held and relisted; only a row under 1.5 x goes to the shop. 65 of the 1,630 equip rows with an NPC price are under 1.5 x today, 7 would be at 0.8 seed or more |
| Other citizens' equips | vendored at the next shop visit (MARKET_HOLD_OTHERS 0) | the same |
| Scrolls | relisted for ever, index falls for ever: no shop visit sells them, except the cheapest looted stacks when a full Use tab leaves no room for potions | relisted for ever at about 0.8 seed; the same exception |
| ETC | stalls list it; the shop visit also sells surplus ETC, whatever the index says | the same |
The fates are from the code; the simulation does not model vendoring. Fewer vended equips is less minted meso.

### 10.8 The scroll glut (read-only, 2026-11-23)
**Corrected by 10.13 (2026-11-24):** the 18 uses are only scrolls handed in at the needs board; citizens use scrolls from
their own bags daily. And buyers do exist: about 341 of 728 citizens have an open slot and no usable scroll of their own.
- **Held:** active citizens carry 53,417 scrolls (about 72 each), retired ones 4,601. 339 scroll ids, 3,470 drop rows on
  645 monsters. Citizens loot them and have almost no other way out: 18 scrolls were ever used on gear.
- **Listed:** since 11-09, 126 citizens opened 4,556 stalls holding 19,270 scroll lines (the same stock relisted). Sold:
  65 bundles.
- **Nobody buys because:**
  - 7,997 of the lines are cursed (dark) scrolls and 2,405 chaos, clean-slate or flag scrolls: no citizen uses or buys
    either (`CitizenScrolling.safe`). That is 54 % of the lines.
  - 3,586 more are worth under 1.0 point to any job (`SCROLL_BUY_MIN_VALUE`). All 65 sales were from the other 5,282.
  - A want needs a worn piece with slots and no usable scroll in the citizen's own bag; with 72 in it, few have one
    (inferred from the rule, not counted).
  - Money is not it: 715 of 746 active citizens have more spare meso than the median useful scroll's price (440 k).
    Browsers buy only under 0.9 x index and stalls ask 0.9 or more.
- **Options (none built):** (a) holders vendor or use surplus scrolls instead of relisting (an NPC pays 1, so this makes
  almost no meso); (b) list fewer: leave out scrolls no citizen buys, or cap scroll lines a stall; (c) let citizens use
  more (value and daily limits); (d) lower the seeds: cursed and chaos scrolls have table prices only humans would pay.
  (b) is the smallest change and lowers the unsold-close count, so it slows the drift on today's rule too.

### 10.9 Recovery options (the user chose the one-shot: 10.11; no live row was changed)
Index / seed, live 11-23, geometric mean (rows; rows under half their seed): scrolls 0.73 (277; 43; the 75 with sales
0.38), weapons 0.85 (382; 39), armour 0.93 (1,510; 49), ETC 0.95 (502; 15), other use 0.99 (183; 1), stars 1.00 (14),
all 0.91 (2,873; 147). Rows that moved since 11-09: 0.69 (449). Of 147 rows under half, 59 had no stall line since 11-09,
so nothing pulls them back by itself.

CPI projection, 53 now, from each basket item's own rate of closes since 11-09, fixed asks (day 1 / 7 / 14 / 30):

| | rows left alone | one-shot: rows under 0.5 seed to 0.8 seed | gradual: to 0.8 seed over 14 closes |
|---|---|---|---|
| today's rule | 53 / 49 / 45 / 37 | 94 / 87 / 80 / 66 | 55 / 68 / 96 / 79 |
| pull 2 % | 55 / 63 / 68 / 74 | 94 / 92 / 91 / 90 | 57 / 77 / 96 / 92 |
| pull 4 % | 57 / 71 / 78 / 83 | 95 / 95 / 95 / 95 | 60 / 83 / 96 / 96 |

- **Controller** (buy budget 1.70 now): the one-shot is one close of about +78 %, one capped step to 1.45, then quiet;
  4 % alone steps down to 1.25 by day 7; gradual to 0.96. Pickup and luxury follow supply, not CPI.
- **Items held at old asks.** A citizen's stall prices its lines when it opens and keeps those asks until it closes.
  - *One-shot at a restart:* every stall is closed, so none keeps an old ask; needs-board offers already posted keep
    their old price until they end, and so do humans' own shops.
  - *Rule alone, gradual:* an open stall trails the index by what it moved since opening (a close's worth, a few percent a
    day at first). A human can buy the cheap lines; nothing resells them at a set profit, as no citizen's buying limit
    changes (0.9 x index).
- **Recommendation:** turn the guard on with the one-shot (rows under 0.5 seed to 0.8 seed, the level the guard itself
  holds), run and logged once at a restart like `CitizenPurseReset`. The rule alone leaves the 59 rows with no lines where
  they are and takes over a month to reach 83. Not built.
- **Projection limits:** calibrated on closes that already moved prices; 2049100's listed lines overstate its closes.
  2040804, 2044501, 2041013 and 2043301 fell as closes x the mean unsold step predicts.

### 10.10 Check
`tools/checks/checks/price-index-drift` (outside the repo): the CPI after the switch, read from a fresh dump of the live
closes. Working: no new low after day 2, by day 7 CPI at or over day 0. Off: CPI over 1.5 x day 0 with no one-shot, or a
close moving more than 20 % outside the one-shot's, or a basket item over 1.6 x the most it ever cost before.

### 10.11 The one-time reseed (user 2026-11-24: "Yes, seed anchor 4%", "do one time reseed"; built, off)
`PriceIndexReseed` runs at start before any citizen loads, after `reseedUnmoved`, when `PRICE_INDEX_RESEED_ENABLED` and
the drift guard are both on (without the guard it refuses and logs). Rows under `PRICE_INDEX_RESEED_BELOW` (0.5) x seed go
to `PRICE_INDEX_RESEED_TO` (0.8) x it, never under the NPC price. A seed at the 10-meso fallback counts as none.
- **Once, undoable:** old prices and a marker (item 0) go to `living_price_reseed` (changeset 70, SQL 061) in one
  transaction with the new ones. While a row is there it never runs again; an unreadable table also means no. The undo
  statement is in the down file.
- **Samples stay.** At 0 the next start's `reseedUnmoved` would set the row to its full seed, over what the market taught
  it. (That rule also means a row no sale ever moved goes back to full seed at every start today.)
- **Dry run:** GET `/runtime/stats/price-reseed`: per class, total, ten largest moves; changes nothing.

**Check stack run, fresh live dump 2026-11-24.** Dry run: 138 of 2,874 rows (EQUIP 83, SCROLL 39, ORE 11, OTHER 5), 415
with no seed. Real run, next start: the same 138; every backup row matches the price before, every new price is 0.8 x
seed and at or over the NPC price, samples kept, no other row changed, the dry run then says 0. CPI from the index:
56.1 -> 95.2 (13 of 33 basket items moved); last close 53.5. Index / seed: scrolls 0.77 -> 0.94, weapons 0.87 -> 0.95,
armour 0.93 -> 0.96, ETC 0.95 -> 0.98; no row of these classes is under half its seed after. The undo restored 138 rows
(in a rolled-back transaction). A second start was not run on the stack; the once-only rule is tested with a stand-in.

**What reads the index then.**
- *Citizen and bench stalls:* both close at a restart and price from the index as they open, so none keeps an old ask.
- *Gear errands:* offers are read live (1.3 x index). An errand whose piece now costs more than the purse waits out its
  24 h and is handed over as before; new ones the purse cannot cover are handed over at once.
- *Browse budget:* 200 k x its scale a day is now under more items' price, so browsers buy fewer of those.
- *Board offers* posted before keep their price until they end (24-48 h).
- *Controller:* the close of the reseed's UTC day takes no step on any lever and logs why (`EconomyController.setting`,
  one read of the marker's date); its row keeps the growth and basket change it read. The next close is read against the
  reseed-day basket. A missed day before it, closed at the same start, has the old basket and is read as ever.
- *A6* (last close over 30 closes back, 0.95 to 1.05): about 0.95 until 11-27, 0.10 on 11-28 (the 941 close of 10-29),
  then over 1.05 and up to about 1.8 for a month. Judge by 10.10 with day 0 = the first close after the reseed.

**Where the 138 targets come from** (check stack run 2026-11-24):

| Seed source | Rows | Meso added to the index | A real price? |
|---|---|---|---|
| PriceSeeds table | 81 (28 weapons, 28 armour, 15 ETC, 9 scrolls, 1 potion) | 46.7 M | a GMS-era player price, but mostly one per level band and class, not per item |
| ScrollSeeds formula | 23 scrolls | 20.1 M | an estimate: expected stat points x the table scrolls' median price a point (their 10th to 90th percentile spans 0.4 M to 4.0 M a point) |
| NPC price x 2.5 | 27 (21 armour, 6 weapons) | 2.6 M | no: the fallback for an equip no table lists; 16 of the 27 sit exactly on the NPC price now |
| Market basket | 7 scrolls | 5.8 M | hand set in market.yaml |

The 4,000,000 is no cap and no default: it is the tables' one price for a Lv 70 weapon (warrior.yaml, bowman.yaml,
mage.yaml and thief.yaml list 23 ids at it). The eight are Lv 68-70 with NPC prices of 250-300 k, so seed/NPC is 13 to 16.
The first sales (10-28 to 10-30) were 2.8-4.6 M, but those buyers pay a share of the index, which was then the seed, so they do
not confirm it. 2040513 is a table entry of its own (3 M); 2044312 is a formula price (2.8 M, a 60 % spear accuracy
scroll).

**Decision (user 2026-11-24: "Yes, option D"), built.** Table and basket rows go to 0.8 x seed; formula rows to
`PRICE_INDEX_RESEED_FORMULA_TO` (0.5) x seed; rows with only an NPC-multiple seed are skipped (not in the backup) and left
to the guard. A row over its target is never lowered. The dry run lists every move with its source and multiple, the count
per source and the skipped items. Dry run on a check stack, dump of 11-24 12:27: 81 + 7 at 0.8, 23 at 0.5, 27 skipped, 111 moves; projected CPI 56.1 ->
91.9 at the reseed, 91.5 on day 7, 91.8 on day 30 (projected). The 138-row run and the 95.2 above are from before this decision.

### 10.12 The formula's scroll seeds (user 2026-11-24: "fix the suspect scroll seeds"; branch fix/scroll-seeds)
**Cause.** `ScrollSeeds` priced every scroll no table lists at one rate, the median meso per expected point of all 92 table
scrolls. Their rates depend on the kind of bonus and the success rate (0.17 M to 10 M a point): the tables put accuracy
and avoid scrolls at 100 k flat, weapon attack at about 1.1 M a point (60 %) and 0.8 M (10 %), stats at about 2.2 M. A
weapon accuracy scroll (+3 ACC, +2 DEX, +1 PAD) got 1.68 points x 1.67 M = 2.8 M beside 1.5 M for a 60 % attack scroll.

**Fix (the formula, no hand-set row).** A scroll takes the median rate of its peers: table scrolls of the same kind of
bonus (its largest), then weapon or armour as it is, then dark or not, then the nearest success rate. The seed stays
within 2 x of the range those peers' prices span. Judgments, not prices: the stat weights (unchanged), the order of the
peers, HP and MP counting a tenth in choosing the kind, and the factor 2. Every price comes from the tables themselves.

**Audit** (633 formula scrolls, 210 with a live row). Suspect = the old seed is more than 2 x off the new one: 231, 79 of
them live. `ScrollSeedAuditTest` holds every formula seed to the 2 x bound with peers worked out apart from the formula;
it fails on the old one. Full live list: shared notes price-drift/scroll-live-effect.csv.

| Live suspects | Rows | Old seed | New seed | Table peers |
|---|---|---|---|---|
| weapon accuracy 60 % | 9 | 2.8 M | 200 k | gloves and shoes DEX 60 %, 100 k each |
| weapon accuracy 10 % | 9 | 967 k | 200 k | gloves and shoes DEX 10 %, 100 k |
| weapon accuracy 30 % dark | 6 | 2.9 M | 290 k | dark gloves DEX 30 %, 150 k |
| weapon accuracy 100 % | 8 | 333 k | 50 k | gloves and shoes DEX 60 % |
| armour accuracy / avoid | 8 | 333 k - 1 M | 50 k - 133 k | gloves and shoes DEX |
| weapon attack 10 % | 14 | 1.03 - 1.14 M | 499 - 551 k | dagger 550 k, claw 500 k |
| weapon attack 30 % dark | 5 | 3.1 - 3.4 M | 1.1 M | dagger and claw 10 % (no dark weapon scroll is listed) |
| jump / speed (pet, bottom, shoes) | 6 | 333 k - 1.08 M | 83 k - 271 k | shoes and pet speed and jump 60 %, 100 k to 1 M |
| Balrog defence 5 % and 30 % | 2 | 0.5 - 1.2 M | 200 k | DEF 10 %, 100 k |
| HP and MP | 12 | 25 - 88 k | 125 - 200 k | HP scrolls 100 k and 150 k |

The other 131 live formula rows change by 0.65 to 2.0 x (median 1.05). The HP seeds go up because the tables price every
junk scroll at 100 k or more; that is a floor of the tables', not evidence of demand.
**Left alone:** the basket's hand-set 60 % weapon attack prices (sword 250 k, axe 150 k) sit under the new 10 % ones
(551 k), which follow the listed dagger and claw. One of the two is off; correcting it means a judgment on the basket.

**Live effect** (read-only, after the reseed). 166 of the 210 live formula rows have no sale: the next start sets them to
their new seed by itself (`reseedUnmoved`), 55 of them suspects. 24 suspects have sales and walk: the guard takes them to
about 0.8 x the new seed at about 4 % a stall close. Eight attack 10 % rows are within about 10 closes. At the listing rates
since 11-09 the six accuracy 60 % rows need 8 to 62 days, the five accuracy 10 % rows 9 to 103, the two pet rows 17 to 18,
two attack 10 % rows 93 and 109 (hardly listed), and 2043702 is not listed at all. 2043002 is the one basket item: the CPI loses about 1 %.

**Options** (a user decision; none built): (a) nothing, as above; (b) a second one-shot for the suspects that have sales
and sit over 1.5 x their new seed (15 rows), set to the new seed, kept and marked as the first was; (c) sample counts of those rows
to 0, so the next start reseeds them: the same effect with no backup row, so worse. (b) is recommended only because the
accuracy scrolls would otherwise ask 5 to 10 x too much for weeks; the rest needs nothing.

**With the glut (10.8).** A stall lists its eight dearest stacks (index x quantity), so an overpriced scroll takes a line
from something that could sell: the suspects are 31 % of the scroll lines since 11-09 (2044112 alone 41 a day, and no sale
the index learned). Corrected seeds drop them down the list. What the 10.8 options would cut of the 19,270 lines: leaving
out scrolls no citizen uses or buys (cursed, chaos, flag) 54 %; those and the ones worth under 1 point to any job 73 %; a
cap of two scroll lines a stall 55 %. A scroll no citizen wants may still be what a player wants, so a full filter
also takes those out of players' reach; the cap does not. Vendoring surplus scrolls makes 1 meso each. None of these adds
buyers; 10.13 has the buyers.

### 10.13 The scroll oversupply: facts and options (2026-11-24; nothing built, for the user to decide)
**Facts.** Read-only from live, 2026-11-24 about 14:35 local; dates are local. M = measured, I = inferred. Live restarted at
13:18 with the guard and reseed on, so "since restart" is 75 minutes, too short to judge.

| Question | Answer | Source, range |
|---|---|---|
| Listed | 19,797 scroll lines on 4,664 stalls (1,270 a day), 53 % of all lines; a restocked stack counts each time (M) | economy_transaction_journal, PLAYER_SHOP_LIST, 11-09 to 11-24 |
| Sold | 71 bundles, 60.6 M meso, 4.6 a day; 6 of them since the restart (M) | the same, PLAYER_SHOP_SALE |
| Listed per sale | about 280 (M) | |
| Who lists | 130 citizens, all with the merchant flag (288 of 746 active have it). No bench companion: living_stall_stock is empty of scrolls (M) | journal, living_citizen |
| In bags | active citizens 53,769 (median 72 each, 38 stacks); retired 4,745; merchants hold 20,921 of it (M) | inventoryitems, now |
| Where from | monster drops: 3,470 drop rows on 645 monsters; 20 monsters carry 59 % of the summed chance. 39 % of the chance is cursed, chaos or flag scrolls, 41 % ones useful to some job, 20 % under 1 point to any (M; not weighted by kills, which are not recorded) | drop_data |
| Entering a day | bags grew about 370 in 17.5 hours (M, two reads); with uses and vendoring about 1,000 a day (I) | inventoryitems |
| Used on gear | 26 in 75 minutes since the restart (M), a few hundred a day (I, short sample). Older uses are not kept: the log is replaced at a restart | cosmic-log |
| Vendored | 721 sales, 965 scrolls, 965 meso since 11-16, 1 meso each, only when a full Use tab leaves no room for potions (M) | journal, SHOP_SELL |
| Worth to holder | 30,720 under 0.25 points to the holder's job; 13,593 worth something but no open piece fits; 6,762 cursed, chaos or flag; 2,007 the holder would use (M on saved bags, by the CitizenScrolling rules; fit by category) | inventoryitems, inventoryequipment |
| Useful to another | 25,986 of them are worth 1 point or more to some other citizen's job but not to the holder's gear now (M, same rules) | |
| Would buy | 728 citizens not hired or personas: all have an open slot (30,416 slots). 387 hold a scroll they would use, so they do not buy; 341 do not, and 322 of those can pay for a scroll that was listed since 11-09 (M, same rules) | |
| Board | 4.7 k SCROLL wants expired and 19 were filled since 11-01; 352 are open. BOUGHT (2.6 k) means "no longer wanted", not a sale (M) | living_need |
| Gear errands | none are open and they do not cover scrolls (M) | living_gear_errand |
| Prices | sellers ask 0.9 to 1.2 x the index; a scroll want pays up to 1.3 x, a board want 1.0 x, a browser under 0.9 x (M, the rules) | code |
| Humans | 1,102 scrolls in 300 stacks off citizens (M); their purchases are not told apart from citizens' here | inventoryitems |
| CPI | 17 of the 33 basket items priced are scrolls | economy_cpi_daily |

**Mechanism.** Every citizen picks up every scroll, though nearly all are for another job or another piece, and nearly the only ways
out are its own gear, a stall, or (with a full Use tab) the shop for 1 meso. A stall lists its eight dearest stacks, so
scrolls take half of all lines, again at each opening, and a buyer buys only what is at a stall within reach of where it
happens to browse: of about 322 willing buyers almost none meets the scroll it wants. Each unsold close used to lower the index,
and half the CPI basket is scrolls, so the glut was the CPI's fall; with the guard the prices hold near 0.8 x seed and no longer follow
the glut, but nothing clears it.

**Options.** None mints meso: stalls move it between citizens (a sale's tax takes some out), and an NPC pays 1 meso a
scroll (all 58 k held are worth 58 k meso). The controller: pickup share is at its minimum (0.1), luxury 0.3 of 0.8, buy
budget 1.70 of 2.0; none of them touches scrolls. Effects marked I are estimates.

| Option | What changes | A player notices | Listed per sale (280 now) | Build | Risk |
|---|---|---|---|---|---|
| (a) nothing beyond the guard | prices hold; the glut stays | stalls half scrolls, few sales | about 280 | none | bags fill: slots for potions |
| (b) pick up less: citizens leave cursed, chaos and flag scrolls, and those worth under 1 point to any job | 39 to 59 % less enters; what is held stays | more scrolls on the ground in hunting maps | no change soon; bags shrink over weeks (I) | small: one rule beside the equip pickup rule | players may read it as odd; take the scrolls they could use |
| (b2) lower drop rates | less enters for everyone, players too | fewer scrolls | as (b) | config | changes the player's game; not advised |
| (c1) scroll errands: a citizen with an open slot, no usable scroll and the meso goes to a stall that has one, as gear errands do | real buyers reach real stalls; the scroll is used at once | citizens walking to stalls and buying; players' own stalls sell too | from 1,270 lines a day for 4.6 sales to perhaps 20 to 60 sales a day: 20 to 60 (I) | medium: reuse gear errands' room search and walk | 322 at most a day at first; FM crowding; prices near 0.8 x seed may be dear to them |
| (c2) citizens use more (lower value bar, more a day) | the 2,007 held usable go faster | little | small (I) | config | small: they are only 4 % of the stock |
| (d) sink: after N unsold closes, or a cap a kind, vendor the surplus scroll | stock of unwanted ones falls | none | no direct change; fewer lines a stall (I) | small to medium: a counter per stack or kind | a scroll a player may have wanted is gone |
| (e1) at most 2 scroll lines a stall | 8,960 lines instead of 19,797 (M, recount of the same stalls); the rest goes to equips, ETC and stars | stalls with mixed goods | 126 at the same sales | small: one filter in stall selection | less chance of a given scroll at a stall |
| (e2) do not list scrolls no citizen uses or buys | 5,522 lines (M) | scrolls only players want vanish from stalls | 78 at the same sales | small | takes cursed and chaos scrolls out of players' reach |
| (f) rank stall lines by what sold lately, not by price | scrolls that sell come first | better stalls | between e1 and e2 (I) | medium | 71 sales in two weeks is little to learn from |

**Which combine.** (e1) with (c1): fewer, more varied lines, and buyers who walk to them; (b) for the cursed, chaos and flag
scrolls adds a slow drain on the source. (d) after them if bags do not shrink. Not (e2) with (c1): an errand cannot buy
what is not listed, and cursed scrolls are what players may want.

**Recommendation: (c1) and (e1), then (b) for cursed, chaos and flag scrolls.** The fault is not that nobody wants scrolls but
that the buyers (322) and the goods (26 k) never meet, and (c1) fixes that while its scrolls are destroyed in use; (e1)
gives the other goods half their shelf back at the cost of one filter, and (b) stops the 39 % no citizen can use from entering.

**Measure for a week after.** Scroll bundles sold a day (4.6 now; working: 15 or more), listed per sale (280; under 80),
scrolls used a day, total held by active citizens (53.8 k; flat or falling), other goods' lines and sales (no fall), median
price of sold scrolls over the index (inside 0.8 to 1.3), the CPI (no close over 5 %), meso supply (no change beyond tax).
Turn it off: used scrolls rise but sales do not, or FM rooms fill with citizens who buy nothing.

**Limits.** Bags are the saved ones (a logged-in citizen's may lag). Usefulness is by the CitizenScrolling rules and by
equip category, not each scroll's own required-item list. Open slots ignore locked or cash gear. The 1,000 a day and the
few hundred uses rest on one day's reads.


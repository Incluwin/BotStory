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
humans trade into a market whose prices stay put". Human-side rates come last and are the user's call (section 7).

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

### E1. Stop the printers (bot side; humans feel nothing)
- Citizen top-up: one-time pocket money at a citizen's first load only, then nothing.
- Bench offline STALL: no sale without a buyer. Offline it sells at the NPC price like SELL (or holds the stock for its
  next live slice); the 1.8x markup only applies to real sales.
- Bench offline FARM_MESO: keep (it stands for kills the companion would make) but book it as BENCH_SIM and calibrate it
  to what `BenchYieldModel` measures live.
- Citizen guilds pay the 1.5 M founding fee like players.
- Free gear: a displaced loadout piece is not sellable (it goes back to the wardrobe pool, not the bag).
- **Decision D1**: what to do with the 22 B already in citizen purses (section 7).

### E2. Consolidate (one market, one index, one ledger)
- Retire `server/bot/**` (timers in `Server.java:1237-1249`, `!boteconomy`, its tables after an export).
- The index learns from executed trades (rule 5); bench-stall sales stop being excluded once nothing can mint through them.
- The agent engine's private valuations read the index as their prior.
- Bench live stalls, citizen stalls and agent stalls share one stall/placement/buyer path. They mostly do already:
  the bench form opens through `AgentFreeMarketStallCapability` (walk to a reserved spot, then open), and citizens walk
  to theirs since f0452f9d0; the citizen path can move onto the same capability.
- SoloMapling stays off; its price YAMLs seed the index; its `botBuy` faucet gets a guard or a comment where it lives.

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

### E4. The controller
- Daily: CPI drift and supply growth versus targets set the next day's bot vendor propensity, spending propensity and
  market buy budgets, clamped to bounds in config, with every adjustment logged and charted.
- Kill switch, and a shadow mode that only logs what it would set.

### E5. Human-side tuning (after the user's decisions in section 7)

## 6. How we will know it works
- Daily net supply growth (faucets - sinks) at or below population growth; bot share of supply falling towards a target.
- CPI within ±5 % a month once E3 is live.
- Sell-through: share of stall listings that sell within a day; citizens buying from humans every day.
- Each phase ships behind a switch, with the full split suite green and a live check like today's FM check.

## 7. Decisions for the user
- **D1, existing bot wealth (22 B).** (a) one-time reset of citizen purses to a level-shaped spread (lognormal around
  2-5x `expectedMeso`); (b) leave it and let E3/E4 spending drain it over weeks; (c) leave it untouched. Recommended: (a);
  nobody sees a citizen's purse, only what it does with it, and (b) would pour 22 B into whatever humans sell.
- **D2, meso drop chance.** Stop multiplying the meso row's chance by `drop_rate` (meso_rate 3 stays the one meso
  multiplier): about -60 % mob mesos for everyone, bots included. Recommended.
- **D3, drop coupons.** Stop them doubling mesos (`mesoCoupon = maxDropRate`). Recommended.
- **D4, small-trade tax.** Tax trades and shop sales of 10 k-100 k at 0.8 % (today 0 %). Optional.
- **D5, retire `server/bot/**` code outright** rather than leaving it off. Recommended.
- **D6, bot-to-bot trades.** Allow citizens to buy from other citizens' stalls (journaled and taxed) so prices have
  real discovery while humans are few. Recommended.

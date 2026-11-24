# N8 professions and commissions: report (feat/world-professions, 2026-11-23)

Switches, all off: `PROFESSIONS_ENABLED`, `COMMISSIONS_ENABLED`, `BUY_ORDERS_ENABLED`. Changeset 69, SQL `060`.

## Decisions the plan did not make

1. **Recipes by running the scripts.** `RecipeTool` runs each forging NPC script in GraalJS against a stand-in NPC that has
   everything, walks every menu and records the order, so amounts worked out late (arrows, screw packs) are right. 347
   recipes: 305 NPC orders and 42 Maker gems (86 smith, 213 tailor, 48 alchemist).
2. **What is left out of recipes.yaml.** An outcome by chance (stimulators, Mu Lung scrolls), a quest-gated script
   (9201095), an order the stock NPC makes free (no fee is under nothing), quest, cash and untradeable items, and a recipe
   whose result an NPC shop buys for more than its materials' shop price and the lowest fee (162 NPC orders and 772 Maker
   equips): a lower fee on those would widen a meso print. This takes out most forged weapons over level 35 and every
   Maker equip. Lifting it needs the user's yes (it is a faucet question).
3. **The crafter's fee** is 70 % of the stock one (the cheapest NPC's), 20 % less for a friend (validated at 0..20 %, what
   the print rule was computed for).
4. **Bows, guns and common pieces** are the smith's; the Maker's stat gems are the alchemist's (stock potions alone are
   six). Branches: see `RecipeTool.profession`.
5. **Escrow.** Type 14 (`NEED_ESCROW`) does not fit: it is one set per citizen, replaced whole and handed to the citizen at
   its next load. Added `ItemFactory.COMMISSION(15)`, keyed by 1,500,000,000 + the order's id: character deletion removes
   every inventoryitems row of a character id whatever its type (it can take a Duey package of the same number today).
6. **Held meso** (fees, players' buy orders) is in a new `MesoSupply.Pool.ESCROW`, so the daily residual stays 0;
   `MesoAccount.COMMISSION` is internal and the last constant.
7. **The fee moves at delivery**, not at the order; the materials stay whole until the commit that delivers.
8. **Online** includes a session with no character loaded (over ONLINE_MAX): it works; delivery waits for a loaded one.
9. **Refunds** by Duey when the crafter is hired, retired or loses its trade, after 48 h waiting
   (`COMMISSION_REFUND_HOURS`), and after three times that plus the work once started.
10. **Double-click** opens a crafter's window only in a town or the Free Market and only while the player carries what it
    works with; otherwise the stock character info stays.
11. **SCROLLER**: safe scrolls only (CitizenScrolling's rule: no cursed, chaos or clean slate), the player's own roll, fee
    150 meso per level of the piece (no stock NPC to compare with).
12. **GEAR requests**: an idle crafter in town makes the piece from its own bag, once the request is as old as the work
    takes, for the request's own offer, in one COMMISSION transaction (escrow and stored purse when the buyer is away).
13. **A crafter's BUY** asks 0.9 x index (the same share a stall buyer pays at most), never under the NPC price, from a new
    PriceLearning lever BUY (0.80 to 1.10) while PRICE_LEARNING_ENABLED is on. `Market.BUY_BELOW_INDEX` is untouched (D16),
    and no board sale moves the price index.
14. **A player's buy order**: materials of the recipes only, `citizen_id` 0, the whole price held at the post, at most 200
    units and 3 orders, 7 days. A citizen's reservation is max(NPC price, 0.9 x index); it sells in town or at the market,
    not from an open stall, and a crafter keeps its own trade's materials. Other players cannot fill one (the plan names
    citizens). Goods come by Duey, at most 100 a package.
15. **The prices page** rides BUY_ORDERS_ENABLED (the plan names three switches). Rows are read every 30 minutes on the
    minute lane, never on a click.
16. **Paper**: `market.commission` (a level 50+ equip or a 50k+ fee) and `market.month` (the month's biggest basket move of
    20 % or more, once a week). The week's moves are already PaperCompose's own `market.riser` / `market.faller`.
17. `!profession` also orders, cancels, posts and takes down, so C1-C5 have headless halves.

Forged fees were a meso sink at the stock NPC and become a transfer: a commission removes that much sink. With three
orders a player this is small beside 1.2 B/day of vendoring, but C7 watches it.

## Hot-file hunks (vs 65f47211e)

- **Market.java**: 4 lines in `openStall` after `description` is built (`professions().stallTitle`). Nothing near the stall
  cap or the ask.
- **NeedScreens.java**: 3 options in `board()` before the menu; in `inPerson` the commission option and the `only == null`
  branch; `give` asks how many for BUY too.
- **NeedKind.java**: `BUY` after `BOUNTY` (`item()` is true for it).
- **NeedLedger.java**: `forWords` BUY case; one line in `derive` after `bounties.derive`; a "crafters (N8)" block before
  the market section (`openBuys`, `closeBuy`, `madeBy`, `make`).
- **NeedStore.java**: COLUMNS + `poster_character_id`; `live` and `endedSince` skip players' orders; `read`'s last
  argument; a "players' buy orders" block before the escrow section.
- **Need.java**: last component `posterId`, with the old 17-argument constructor kept. **LiveNeedHands.java**: BUY in
  `use`. **NeedOffers.java**: `Learner.buyShare` (default) and `buyShare`.
- **Population.java**: 4 fields, 4 constructions before `clears`, one minute-lane line after `camps.minute`, 4 accessors
  and `tradesMinute` before `needs()`.
- **config.yaml / PopulationConfig.java**: after `FIELD_BOSS_ROLLED_KILLS_PER_DAY`. **changelog-tables.xml**: 69 at the end.
- Others: ChatterEngine (filter line, `tradeAllows`, smega speakers), PopulationLoader (3 triggers), PriceLearning (BUY
  lever), PaperCatalogTest.FILED, paper.yaml, chatter/needs.yaml, CommandsExecutor, ItemFactory, DueyProcessor
  (`dueyCreatePackage(Connection, ...)`), EconomyOperationKind, MesoAccount, MesoSupply, MesoFlowsTest, and the two pinned
  price JSONs (GearErrandsTest, PriceLearningSimulationTest gain `"buy":{}`).

## Live checks

`MapleServer\tools\checks\checks\n8-professions` (C1-C7), written; see its README for what ran.

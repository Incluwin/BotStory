# Live bench GATHER / SELL / STALL: state, gap and plan (2026-10-25)

Feature-gap item from the 2026-10-24 review: "gather, sell and stall only ever run on the ledger". That claim came
from the pre-P5 living-world notes (written before 2026-09-29) and is out of date.

**The gap is much smaller than "they never run live".** Since P5 (2026-09-29) all three duties have a live form.
Each form is registered at boot and on by default, under the same pools and budget as the field grinders.
- STALL has run live without a break since 2026-09-29: 346 slices, all of them Ilse's.
- GATHER and SELL have never gone live, because no row has ever been able to: no owner has assigned GATHER, and the
  only SELL row has had an empty haul the whole time.

What is actually wrong is narrower:
1. GATHER's live form stays at the companion's camp. When no dropper of the needed item lives there, it grinds
   whatever does live there and gathers nothing, while the ledger credits the item from a dropper anywhere.
2. A live STALL sells only to humans. Browsing citizens buy only under 0.9x the index, and STALL lists at 1.0x. Ilse has
   run 346 stall slices (up to 2 h each) on one SQL-seeded stock and has made one sale, the 09-29 test purchase.
3. SELL and the citizen-buyer path have never been seen working live. Across every logged day, no citizen has bought
   from any stall.

## 1. Evidence

### 1.1 Every duty has a live form, dispatched by one dispatcher

| Duty | Live form | Registered | Pool (`Form.pool()`) |
|---|---|---|---|
| TRAIN, FARM_MESO, FARM_ITEM | `BenchSessions` | `BenchDutyDispatcher.java:319-321` (constructor) | BENCH_LIVE (`BenchSessions.java:45-47`) |
| **GATHER** | `BenchGatherForm` | `LivingWorld.java:138` | BENCH_LIVE (`BenchGatherForm.java:58-61`) |
| **STALL** | `BenchStallForm` | `LivingWorld.java:139` | COMMERCE (`BenchStallForm.java:164-167`) |
| **SELL** | `BenchStallForm`, the same instance | `LivingWorld.java:140` | COMMERCE; it differs from STALL only in price (`QUICK_SALE` 0.85 x index, `BenchStallForm.java:71,417`) and title (`:446-457`) |
| REST | `BenchRestForm` | `LivingWorld.java:141` | BENCH_TOWN |

`BenchDutyDispatcher.startDue` (`BenchDutyDispatcher.java:1012-1067`) runs every `TICK_MS` = 2 min
(`:105`, timer at `LivingWorld.java:278-279`). It walks every bench-governed row with a form, owners online first and
then the longest-waiting row (`order`, `:418-445`). It skips a row whose pool is full server-wide or for its owner
(`:1051-1054`), takes the lease and the budget admission, and loads the character (`begin`, `:1093-1191`). A row stays
in the ledger only when its form's `mapFor` answers 0: nothing to do, or no room.

### 1.2 Pools, caps and defaults: on by default, with no off switch beyond the pool caps

| Pool | Server cap | Per owner | Slice | Shipped value |
|---|---|---|---|---|
| BENCH_LIVE (GATHER, shared with TRAIN/FARM) | `min(BENCH_LIVE_MAX, BENCH_LIVE_GLOBAL)` = min(4, 8) = **4** (`BenchDutyDispatcher.java:376-383`) | `BENCH_LIVE_PER_OWNER` **2** | `BENCH_LIVE_SLICE_MS` 15 min | `config.yaml:798-799, 827-828` |
| COMMERCE (STALL + SELL) | `COMMERCE_LIVE_MAX` **2** | `COMMERCE_PER_OWNER` **1** | `COMMERCE_SLICE_MS` 2 h (4 h at most while watched, `timeUp` `:401-411`) | `config.yaml:845-847` |

`CompanionBudget.decide` (`CompanionBudget.java:131-153`) applies the same caps, plus the 300-Agent ceiling and the
2 s per-owner load gap. The config comment (`config.yaml:832-836`) holds the town and stall pools at 4 and 2 "until
the P5 soak ... measures scheduler queue-lag p95 under 100 ms". The P5 soak did measure that: p95 55 ms
(`docs/BOT_MANAGEMENT_P0_RESULTS.md:561`), but at a light 14-52 agents. Nothing turns GATHER, SELL or STALL off on its
own. Setting `BENCH_LIVE_MAX` or `COMMERCE_LIVE_MAX` to 0 turns off the whole pool.

The ledger runs every `BENCH_LEDGER_INTERVAL_MS` = 10 min. The dispatcher's first look comes 2 min after boot, the
ledger's 10 min after (`LivingWorld.java:268-279`). While a row is live, the ledger only moves its clock
(`BenchLedger.java:112-124`), so nothing is credited twice.

### 1.3 Has each duty ever run live?

Sources: all 292 archived server logs plus the current `logs/cosmic-log.log` (2026-09-25 to 2026-10-25, 1.13 M
lines), the live DB's `living_event_log` and `living_roster` (read-only queries), and the P5 results.

`bench slice: <name> <duty> live at <map>` lines (`BenchDutyDispatcher.java:1179`), by duty:

| train | farm meso | rest | **run a stall** | **gather for your quests** | **sell the haul** | restock |
|---|---|---|---|---|---|---|
| 5,293 | 850 | 564 | **346** | **0** | **0** | 6 |

**STALL: live, every slice, but no sales.**
- 346 `bench.stall` events and 345 `bench.stall-open` events, 2026-09-29 22:25 to 2026-10-25 10:50. All are Ilse's
  (owner ohboy), in FM room 4 (910000004).
- The P5 live check passed on 2026-09-29 (`docs/BOT_MANAGEMENT_P0_RESULTS.md:554`): ohboy bought 40 Orange Mushroom
  Caps from her.
- Since then, every `bench.stall` reads `stocked {4000000=60, 4000019=100} in room 910000004`. That is the stock the
  test put in her haul by SQL (`P0_RESULTS.md:580`), cycling haul -> stall -> haul every slice.
- `bench.stall-close` reads "slice elapsed; sold {}" 188 times, "server shutdown; sold {}" 142 times and
  "called by owner; sold {}" once. The one "sold {4000001=40}" is the 09-29 test purchase. There is exactly one
  `bench.stall-sale` event, at 2026-09-29 22:29.
- **Zero** `citizen ... bought ... from ...` lines (`Market.java:348`) in any log: no citizen has ever bought from any
  stall, human or companion.
- Why: `Market.shop` buys a listing only when its unit price is at most `BUY_BELOW_INDEX` = 0.9 x the index
  (`Market.java:40, 337`). STALL lists at 1.0 x (`BenchStallForm.java:417`), so only a human can ever buy from it. Her
  blue snail shells, at index 117, would need to be listed at 105 or less.

**SELL: never live, because it never had anything to sell.**
- One SELL row has ever existed: Hadrik, owner Keepfriend, assigned "sell the haul" 2026-10-22 07:04:20. It was
  called into the party at 07:07 and stayed in the party until it was parked on the bench as SELL on 2026-10-24
  06:48. Since then its haul has been empty, because it was a trainee and TRAIN harvests nothing
  (`BenchSessions.java:116-119`).
- `BenchStallForm.mapFor` answers 0 when there is nothing to sell (`:184-186`), and the ledger's SELL has nothing to
  sell either. Both behave correctly.
- SELL is not among the P5 live checks (`docs/BOT_MANAGEMENT_PLAN.md:786-790`, results `P0_RESULTS.md:550-561`).
- The FM quick-sale price (0.85 x index) sits under the citizens' line, so a SELL stall should sell to browsing
  citizens. That has never been observed (see the zero purchases above).

**GATHER: never live, because no owner has assigned it.**
- `living_roster` has 0 GATHER rows. `living_event_log` has 0 `roster.assign` events reading "gather". Of the 5
  `roster.assign` events in total, one reads "run a stall" (Ilse) and one "sell the haul" (Hadrik).
- No test owner (ohboy, Crewfriend, Keepfriend, Pqfriend, Bossfriend) has an active living quest, so a GATHER row
  would have nothing to gather and its form would answer 0 (`BenchGatherForm.java:65-72`).
- It is not among the P5 live checks.
- Ruled out: quest-gated drops. All 114 items that living quests ask for in a COLLECT_ITEM step drop ungated
  (`drop_data` has 366 rows for them with `questid` = 0 and none gated), so a live companion can pick them up without
  holding the quest.
- "Your call" never picks GATHER, SELL or STALL: its arms are TRAIN, FARM_MESO and REST (`BenchDutyChooser.java:136-148`).
  So these duties run only when an owner assigns them.

### 1.4 Defects found while tracing (independent of whether the duties were used)

**D-1. A live GATHER at a camp with no dropper gathers nothing.**
- `BenchGatherForm.mapFor` always loads the companion at `fieldCamp` (`:71`).
- `targets()` keeps only the droppers that spawn on that map (`:176-192`). With none there, the target set is empty,
  and `AgentFieldIntent.acceptsMob` then accepts every mob (`AgentFieldIntent.java`, `acceptsMob`). The companion
  grinds the camp's mobs for the full slice. Its `needs` never shrink, so the slice only ever ends by time.
- The ledger credits GATHER from "the best fitting [dropper] with any field site ... its visit goes to the droppers"
  (`FarmSites.creditSource`, `FarmSites.java:100-127`, `questDrops` = true for GATHER at `BenchLedger.java:442`).
- So the ledger promises a trip to the droppers that the live form never makes. A player who walks to the camp sees
  their companion "gathering" mobs that cannot drop the item.

**D-2. A live STALL is worth less than the ledger's STALL, and holds a stall slot indefinitely.**
- Live, it sells only to humans (see 1.3). Not live, the ledger's STALL sells 25% of the haul every tick at 1.8 x the
  NPC price (`BenchLedger.java:195-200, 50-51`).
- An unsold stall never "sells out", so it rotates back in every 2 h and holds one of the server's two COMMERCE slots.
  Ilse has held one since 09-29.

**D-3. Card wording for a gatherer away from its camp.** Today this case cannot happen. It appears once D-1 is fixed,
because the companion is then live on a map other than its camp.
- The card shows "(live)" beside "gather for your quests @ <camp>" (`CompanionCardScreens.java:349-362`).
- Fixing D-1 moves the companion off its camp, so the card must name where it is.

**Not defects:**
- SELL is a one-shot duty by design. Live, it sells to citizens and humans at 0.85 x index and ends "sold out". With
  no COMMERCE slot free, the ledger sells the whole haul at the NPC price on its next 10-minute tick
  (`BenchLedger.java:189-194, 467-483`). That is the intended fallback.
- At boot, the dispatcher's first look (2 min) comes before the ledger's (10 min), so a restart never liquidates a
  SELL haul before the live form has its turn.

## 2. Design

The changes are small, each sits behind its own config switch, and the pools, caps, budget and ledger stay as they are.

### 2.1 GATHER goes to the best map for what it needs (D-1, D-3). Decided: Q1 (a), "best map, not nearest"

- `BenchGatherForm.mapFor`: when the camp field has a dropper of any needed item, the camp, as today. The owner's
  camp is respected.
  - Otherwise, with server knowledge loaded (`Decisions.active()`), the **best** reachable field for the needed items.
  - Candidates are every open field site of every source `FarmSites` would let it farm (the same rules as
    `FarmSites.plan` / `creditSource` with `questDrops`): wild, not a PQ or boss mob, not more than
    `TRAINING_LEVELS_ABOVE` over its level, an open non-town field, reachable from its camp.
  - Each map is scored by how much of the items a round of kills there brings. For every needed item, that is the
    drop chance of each dropper there times the dropper's spawn points on that map (`MapFact.mobs` / `mobCounts`),
    summed over the needed items. A gatherer fights only the droppers on its map, so more of the best-dropping mob
    means more of the item.
  - Ties go to the nearer map, then the lower map id.
  - Skipped, which is the learned part:
    - maps the bench has measured as deadly for its kind (`RosterService.roughCampFor`);
    - maps where it died in 2 of its own last 3 slices there. This is `BenchDutyDispatcher.diesAt`, the same
      `campRecord` over its bench events that moves a grinder off a rough camp. It is needed because GATHER slices
      record no yields, so the kind-level model never learns from a gatherer's deaths.
  - Also skipped: fields closed in `FieldCatalog`, and maps that do not load.
  - New `FarmSites.gatherSites(k, itemId, level, anchor, questDrops)` returns map id -> score for every reachable
    site. `FarmSites.anyMonsterDrops` says when a global drop makes any field do. `FarmSites.plan` (nearest first,
    for chat and FARM_ITEM) is unchanged.
  - When the items are a global drop of the camp's continent, the camp will do.
  - With no site anywhere it answers 0, and the ledger keeps the row. The ledger already notes "gather stalled" when
    nothing it can farm drops the item. Without knowledge, or with the switch off, the camp, as before.
  - Follow-up, not in this change: a per-item learned rate. Today GATHER slices record no yields (`record`,
    `BenchDutyDispatcher.java:1313-1323`), so the score is the knowledge estimate filtered by learned deadliness.
- Everything else stays as it is:
  - The visit's targets are still the droppers spawning on the loaded map, which is now non-empty by construction.
  - The end rules (`check`) are unchanged: "left its camp" compares with the slice's own map.
  - The death and revive rule, shop resupply, rough-camp check, yield exclusion and harvest are unchanged.
- Card: a GATHER slice live on a map other than its row's camp reads "gathering at <place>" on its own line, instead
  of "(live)" after the duty line, so the owner knows where to walk (`CompanionCardScreens.liveState`).
- Kill switch: `BENCH_GATHER_AT_DROPPERS` (default true). false keeps today's camp-only behaviour.
- Tests:
  - `BenchGatherFormTest`:
    - With no dropper at its camp, it loads at the best field (3x the score, 9 hops) rather than the nearest (2
      hops), and the visit targets the droppers there.
    - A dropper at its camp keeps it there, and a tie goes to the nearer field.
    - A field its kind dies at, or where it died itself 2 of its last 3 times, is passed over.
    - With nowhere to go, the ledger keeps it; a global drop keeps its camp.
    - The switch off keeps its camp.
    - The existing tests run with knowledge pinned off.
  - `FarmSitesTest`, on the real knowledge file:
    - Each snail-shell site's score is snail chance x snail spawn points there.
    - Only reachable Victoria fields count.
    - Too-strong, PQ-only, script-only and global items rate no field, and `anyMonsterDrops` is true for a global
      drop.
  - `CompanionCardScreensTest`: away from its camp the card reads "gathering at Ant Tunnel II"; at its camp, "(live)".

### 2.2 A STALL marks down what does not sell (D-2). Decided: Q2 (a)

- `BenchStallForm.listings`: STALL's price factor per item is
  `max(QUICK_SALE, 1.0 - BENCH_STALL_MARKDOWN_STEP x unsoldSlices(item))`.
  - `unsoldSlices` counts the companion's own last stall slices, newest first. It counts each slice that stocked the
    item, ran its full length ("slice elapsed") and sold none of it. It stops at the first slice that sold some of
    the item.
  - Slices ended by a shutdown, a call, a duty change or a failed open neither count nor reset.
- The count comes from its own event history: the "bench.stall stocked {...}" event, paired with the
  "bench.stall-close <reason>; sold {...}" that follows it. It is read through `LivingStore.personaEvents`, the same
  way `BenchDutyDispatcher.campRecord` reads a camp's record. So it survives restarts and needs no schema change.
- This is a seller learning its price from what happened, not a fixed discount. Each slice that sells nothing knocks
  5% off the next one's price. A slice that sells some of an item brings that item back to the index the next time.
- The floor is SELL's `QUICK_SALE` (0.85), which is under the citizens' 0.9 line. After three dead slices, a STALL
  item is priced where browsing citizens buy. SELL stays the duty that sells fast from the start.
- Market sales by bench stalls already stay out of the price index (`Market.bought`, `Market.java:319-324`), so
  markdowns never drag the index down.
- The "bench.stall" event detail adds the ask, e.g. `stocked {...} in room N at {4000000=85%}`, so the live check can
  read it.
- Kill switch: `BENCH_STALL_MARKDOWN_STEP` (default 0.05). 0 keeps today's flat index price.
- The history read is its last 60 stall events (`STALL_HISTORY_EVENTS`), so about 30 slices, one indexed query per
  stall slice begun (every 2 h at most).
- Tests (`BenchStallFormTest`), through real slices:
  - The first stall at the index, then 95%, 90% and 85%, and never under 85%. The "bench.stall" event carries the
    ask.
  - A sale puts that item back at the index while the unsold item stays marked down.
  - `unsoldSlices` on its own: shutdown-ended and unclosed slices neither count nor reset.
  - Step 0: always the index.
  - SELL at 0.85 is the existing `sellPricesUnderTheIndexAndAPersonaNamesItsOwnStall`.
- Effect on the live server: Ilse's last 30 or so slices sold nothing, so her next stall lists at 0.85 x index.

### 2.3 SELL: no code change

Its form already works in unit tests (`BenchStallFormTest.sellPricesUnderTheIndexAndAPersonaNamesItsOwnStall` and
the others). What it lacks is a live run with something to sell (live check L3).

Considered and not recommended: making SELL an NPC-shop errand (the agents' own sell-trash visit,
`AgentShopService.requestSellTrashVisit`). It would match the ledger's NPC prices, but it is over in seconds, so
nobody would ever see it. The plan row (`BOT_MANAGEMENT_PLAN.md:296`) and decision 12 (a) already put SELL in the FM.

### 2.4 Deliberately unchanged

- Pool caps: BENCH_LIVE 4 and COMMERCE 2 server-wide. The config says to raise them only from a soak, and the P5 soak
  ran at light load. Raising them is a follow-up after a soak at evening load.
- "Your call" duty arms.
- Ledger rates.
- Assignment UIs: the Crew window already offers GATHER, SELL and STALL (`CompanionCrewScreens.java:84-85`), and so do
  the whisper phrases (`ResidentDialogue.java:910-912`).

## 3. Decisions (user, 2026-10-25)

- **Q1. Where does a gatherer go when its camp has no dropper?**
  - Offered: (a) the nearest field where a dropper lives, which the ledger already credits, with the card naming the
    place; or (b) live only at its camp.
  - **Answer:** (a), "but some maps are better than others so instead of nearest, look for the best map for that
    item". Section 2.1 implements that.
  - The owner's camp still wins when a dropper lives there. "Best" is the drops an hour (chance x the dropper's
    spawn points), skipping maps measured as deadly for its kind.
- **Q2. How does a STALL companion sell when no human comes by?**
  - Offered: (a) learned markdown from its own unsold slices, down to SELL's 0.85 x index; or (b) keep the index price.
  - **Answer:** (a), the learned markdown.

## 4. Live checks (for the coordinator / opq-live; not run by this session)

Server on the merged build. Use GM commands to set up, then watch.

- **L1. GATHER away from camp (Q1 a).**
  - Setup: Crewfriend accepts a Resident quest with a COLLECT_ITEM step whose item no mob at Dorran's camp drops.
    Bench Dorran and set "Gather for your quests".
  - Expect within 2 min:
    - Log: `bench slice: Dorran gather for your quests live at <map>`, where <map> is the field with the most
      spawns of the best-dropping mob for that item (check it against `drop_data` and the map's life), not merely
      the nearest one.
    - Card: "gathering at <place>".
  - Walk there: Dorran fights the droppers, the haul gains the item (`bench.harvest`), and the slice ends
    "gathered what your quests need" once the quest has enough.
- **L2. GATHER at a camp with a dropper.** The card shows "(live)" and the log shows its camp map.
- **L3. SELL.**
  - Setup: give a benched companion haul stock, either from a FARM_ITEM slice or 3 ETC stacks by SQL as on 09-29.
    Set "Sell the haul".
  - Expect:
    - Within 2 min, the card reads "setting up a stall in FM room N", then "selling the haul in FM room N".
    - Within about 30 min, at least one `citizen ... bought ... from <companion>` log line. That would be the first
      citizen purchase on record.
    - `bench.stall-sale` events, with the haul's meso growing.
    - The slice ends "sold out", or "slice elapsed" with the rest back in the haul.
- **L4. STALL markdown (Q2 a).**
  - Ilse's next slice: the `bench.stall` detail ends `at {4000000=85%, 4000019=85%}`.
  - A citizen buys within the slice: `bench.stall-sale`, "sold {...}" on the close.
  - The next slice lists that item at 100%.
- **L5. Kill switches.**
  - `BENCH_GATHER_AT_DROPPERS: false` puts L1's gatherer back at its camp.
  - `BENCH_STALL_MARKDOWN_STEP: 0` lists Ilse at 100%.
- **Watch:** at most 2 COMMERCE and 4 BENCH_LIVE slices at once, `/runtime/budget` queue lag unchanged.

## 5. Not verified until a live check

- That citizens actually buy from a 0.85 x stall. The buyer path (`Market.shop`, `AgentFreeMarketBuyerService.buy`)
  has never fired on this server.
- That a dropper site chosen by `FarmSites` is one the companion survives. The rough-camp record is per camp and does
  not cover the site.
- How often a gatherer's site is far from its camp in practice. With no quest content assigned to test owners, there
  has been no sample.

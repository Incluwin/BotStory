# Bots with wants (plan)

Written 2026-10-26 against `feat/companion-manager` a435dc14b (live). It covers the ten ideas from the 10-26 bot-concept
review: two changes to the concept (C1, C2) and eight features (F1-F8). Each section gives what exists (with evidence),
the design, phases with a "done when", and live checks. Sections 11-13 cover order, sessions, allocations, and the
decisions the user still has to make.

| Id | Piece | One line | Size |
|---|---|---|---|
| C1 | Standing | One relationship ladder for every bot, so a befriended citizen can grow into a companion | M |
| C2 | Tell audit | Measure how bot-like the bots look, per build | S-M |
| F1 | Needs board | Citizens ask for real things they lack and pay from their own purse | L |
| F2 | Learning | Companions pick up their owner's habits; citizens learn where to train | M |
| F3 | Letters | A digest of what happened while you were away, by in-game note and Duey | S-M |
| F4 | Mentor + apprentice | A guide for new characters; adopt a beginner and pick its job | L |
| F5 | Town classics | Omok and match cards, jump quests, party megaphones | L |
| F6 | Story arcs | Personal companion arcs ending in scenes you attend; crew relationships | XL |
| F7 | Rival crew | A named bot party near your level that races your clears | M |
| F8 | Small imperfections | Human-looking slips with hard caps: no extra deaths, no long idling | S-M |

## 0. Rules for every piece

- **Immersion test.** Every new line or behaviour must pass "would a player notice this is a bot?" Each live check below
  includes a believability pass.
- **Learn, don't script.** When a choice can be measured (EXP/h, fulfil rate, damage), measure it and let a bandit or
  learner choose. Hand-set values are only priors.
- **No client files.** The client patch is held. Nothing here needs Quest.wz, Npc.wz or String.wz. The building blocks
  are stock NPC talk (`LivingTalk`), stock NPC templates (Bulletin Board 9040011), notes (memos), Duey, chat and
  megaphone notices. Where a client entry would help (story-arc quest-log entries), the piece ships without it and lists
  the client follow-up.
- **Jev cost stays flat.** New lines come from templated YAML catalogs (the `chatter/*.yaml` style). There are no new
  per-tick model calls.
- **Kill switches.** Each feature gets a `*_ENABLED` switch, off until its live checks pass. Then it goes on by default,
  following the 10-19 default-on rule.
- **Scheduler budget.** New periodic work rides existing ticks: the population social tick, companion-ops, and the
  ChatterEngine 20 s tick. There are no new per-Agent timers.
- **Config.** New keys go in `config.yaml` with `# Technical:` notes of at least 100 characters. Run
  `AgentConfigurationBoundaryTest` and the lints targeted before the full suite; this is the GPQ lesson.
- **Testing.**
  - Full suite: `tools\suite\run-split-suite.ps1 -Worktree <wt>`; baseline 15,348/0/37.
  - Live checks use GM commands (warp, level, items) on the real client through cua, with test accounts ohboyohboy and
    crewfriend24.
  - Each player-facing piece gets a short clip for sign-off.

---

## C1. Standing: one relationship ladder for every bot

### What exists

| Piece | Where | How it works | The gap |
|---|---|---|---|
| Resident friendship | `living_bond` (`029-living-world.sql:26-42`); `BondService.java:52-63`; `Friendship.java:29-65` | Bond points never fade. Stages ACQUAINTED/TRUSTED/SWORN come at 25/75/150 points, and a Lv 1-10 meter (`FRIENDSHIP_LEVEL_POINTS`) runs over the same points. Points come from quests, fame, gifts, PQs and bosses (`Friendship.java:95-220`). | Residents only: `residentOf` returns null for hires (`Friendship.java:83-89`), and about 30 `isHire` guards back that up. |
| Citizen warmth | `PersonMemory.java:19-113`, columns on `living_acquaintance` | Warmth runs -100..100 and halves every 14 days. Thresholds: WARM 15, FOND 40, COOL -10, COLD -30. It gates party invites and grudges (`CitizenInviteDecision.java:64-75`) and greetings. | It plays no part in hiring (`staysOn` ignores it, `CitizenInviteDecision.java:176-184`). |
| Hire | `HireOffers.java:122-191`, `HiredHands.java:130-136,169-315` | The offer comes after 10 party minutes plus a `staysOn` roll. The fee is 1,000 × level. The synthetic persona `hire:<cid>` is not recruitable. | A hire has no bond, friendship, gifts, quests, arcs, kit or wedding. Once hired, the citizen leaves the population (`PresenceScheduler.java:620-628`), so nothing more is remembered and its warmth fades. |
| Engine relationship memory | `SocialPostgresDataSource.java:14` | Off live (`SOCIAL_DB_ENABLED` is false). Its only reader is `AgentSocialDialogueRequestFactory.java:110`. | Not part of this plan. |

So there are three stores and two scales. A citizen you have played with for weeks tops out as a hire that has no
friendship at all.

### Design

- **`Standing`** (pure, `server.living.standing`) is a view, not a fourth store. It places any (player, bot) pair on one
  ladder, **STRANGER → REGULAR → FRIEND → CLOSE**, computed from the source that kind of bot already has:

  | Rung | Resident (friendship level) | Citizen (warmth) | Hire (friendship level, from C1.2) |
  |---|---|---|---|
  | STRANGER | Lv 1 | < 15 | — |
  | REGULAR | Lv 2-3 | 15-39 | Lv 1-3 |
  | FRIEND | Lv 4-6 | ≥ 40 | Lv 4-6 |
  | CLOSE | Lv 7+ | — (a citizen reaches CLOSE only after being hired) | Lv 7+ |

- **Hires join the friendship engine.** At hire time the citizen gets a real `living_bond` row keyed `hire:<cid>`
  (`persona_id` is already VARCHAR(64)), seeded once from its warmth: 15 → 25 points (Lv 2), 40 → 75 points (Lv 4). From
  then on the same sources (PQ, boss, fame, gifts) add points. Lift the `residentOf` hire filter for points, gifts and
  level announcements only. Authored content (quests, arcs, ResidentKit, portraits) stays Residents-only, because hires
  have none.
- **Hire offers follow standing.** Replace "10 party minutes + roll" with: an offer at REGULAR or above plus some party
  time, a fee discount at FRIEND, and never under a grudge (≤ -30). The offer cooldowns move from memory into
  `living_event_log` (`hire.offer` already exists) so a restart does not reset them.
- **Memory keeps running while hired.** A hire's PQ/boss runs with its owner also write PersonMemory episodes. When a hire
  is released back to the population, its warmth is set from its friendship level, so it greets its old owner as a
  friend.
- **Perks per rung for hires**, generic rather than authored:
  - Lv 4: buddy, as `CompanionBuddies.java:88` already does.
  - Lv 7: wage discount.
  - Lv 10: permanent, like `PERMANENT_AT_SWORN` (`ContractService.java:73-79`).
- The companion card and Crew window show "Friendship Lv n" for hires. `!standing <bot>` (GM) shows the rung and the raw
  numbers.

### Phases

- **C1.1** `Standing` view, mapping table and `!standing`. Read-only. *Done when* mapping tests pass and `!standing`
  works on a Resident, a citizen and a hire.
- **C1.2** Hires get bond rows (seeded from warmth) and earn friendship points, gifts and announcements. Audit every
  `isHire` site: keep or lift each one, with a reason, in the guide.
- **C1.3** Standing-based hire offers; release sets warmth.
- **C1.4** Rung perks; card and Crew window display.
- *Done when* a citizen you party with on several days reaches FRIEND, offers to join, and climbs Lv 1-10 as a hire from
  the same events as a Resident.

### Live checks

L1 party with a citizen until REGULAR and get the offer. L2 hire it, clear a PQ, see the friendship point line. L3 gift a
hire. L4 release it, then meet it again and get the warm greeting.

---

## C2. Tell audit: measure how bot-like the bots look

### What exists

- **Delays:**
  - Combat reaction delay: a per-bot baseline plus jitter, capped at 500 ms (`AgentCombatBehaviorRuntime.java:29-52`).
  - Chat reply: think time plus typing ±25% (`ContentChat.java:247-255`).
  - Chatter: a cooldown and a per-map budget (`ChatterEngine.java:36-39,318-321,387-391`).
- **Presence:** citizen sessions last 30-480 min and end; 15% of re-plans go AFK for 5-15 min
  (`PresenceScheduler.java:401-404,917-927`).
- **Spread:** an idle wander of ±120-520 px (`:850-867`).
- **Data and counters:** `/runtime/positions` gives raw x/y; `AgentBehaviorTelemetry` keeps counts only;
  `AgentPerformanceMonitor` covers CPU.
- **Not measured at all:** the reaction distribution, route repetition, chat cadence, stacking, online share, and the
  points where bots drink potions and recast buffs.

### Design

`TellAudit` (`server.living.audit`) adds collectors at existing chokepoints and folds them every hour. Each metric gets a
"looks human" band, and an out-of-band value is a tell.

| Metric | Collector | Tell when |
|---|---|---|
| Reaction time (combat, chat reply) | where `responseReady` decides; `ContentChat` reply | coefficient of variation < 0.25, or a minimum under 150 ms |
| Route repetition | hash of the walked edge list per (bot, from, to) in `AgentMapWalk` | more than 60% identical repeats of a route |
| Chat cadence | per-speaker gaps between lines in `ChatterEngine` | low-variance gaps; the same line twice on one map within 10 min |
| Stacking | grid clusters from positions | 3 or more idle bots within 30 px on one map |
| Presence | `book()` online minutes | online more than 20 h a day over 7 days; AFK on a field over 2 min |
| Exact thresholds | autopot drink HP%, buff recast % | more than 80% of drinks or recasts at one exact value |
| Deaths | death hook, per class and activity | used as F8's guard, not as a tell |

The data is exposed at `/runtime/tells` (admin port JSON: values, band, red/amber/green), and an hourly snapshot goes to
`living_event_log` as `audit.tells` with the build sha. `tools\tells-report.ps1` compares two builds. No Jev in v1
(decision D17).

### Phases

- **C2.1** Reaction, chat, stacking and presence collectors plus the endpoint.
- **C2.2** Route hashes plus potion and buff distributions.
- **C2.3** Hourly snapshots plus the build-compare report.
- **C2.4** A 24 h live baseline with a ranked tell list.
- *Done when* the baseline exists. It feeds F8's targets and every later before/after.

---

## F1. Needs board: citizens ask for real things

### What exists

- **Market** (`Market.java`):
  - Citizens sell what they looted from real stalls, priced from `living_price_index`, which is seeded from the
    `market.yaml` basket (`:71-122,194-247`).
  - Browsers buy from human `PlayerShop`s when the price is under 90% of the index (`:312-366`).
  - Wallets are topped up to 2000·lvl^1.5 (`ProgressionLedger.java:69-72`).
  - The "B> {item}" chatter picks a random basket item, so it is flavour only (`ChatterEngine.java:429`).
- **Real needs that can be computed:**
  - `AgentSupplyReadiness.shortfall`: HP/MP points, ammo, cure and missing mesos, before a boss
    (`AgentSupplyReadiness.java:85,132`, used at `CitizenBossDoor.java:145-153`).
  - `CitizenGear.upgradeFor`: a concrete equip id, today handed over free at load (`:96`).
  - `AgentQuestItemDemandRuntime.forecast`: stock-quest item ids and counts; citizens never use it.
  - Citizen job advancement needs no items (`CitizenJobErrands.java:223`).
- **Quests are YAML-only and tied to Personas** (`LivingContent.java:34,165`; `QuestLedger.java:62-75`). Double-clicking
  a citizen offers nothing (`ResidentDialogue.java:554-558`).
- **Channels:**
  - `ChatterEngine.whisper` (`:544`).
  - Megaphone: 4/h live (`config.yaml:1169`).
  - Map chat: 18/min (`:1167`).
  - Human whispers to a citizen are ignored (`ResidentDialogue.java:217-241`).
- **Hand-over:**
  - There is no owner-less trade (`AgentManualTradeTickService.java:30`).
  - DELIVER and GIVE_ITEM destroy the item.
  - `EconomyTransactionCoordinator` (COMPANION_GIFT) moves mesos atomically, even with the bot offline
    (`CompanionManager.java:697-709`).

### Design

- **`NeedLedger`** (`server.living.population.needs`, table `living_need`). Each citizen has at most two open needs,
  derived from real state when it plans:

  | Kind | Source | Example |
  |---|---|---|
  | SUPPLY | `shortfall` before a boss/PQ session, converted to the potion item it actually buys | "need 120 White Potions before Balrog" |
  | SCROLL | the weapon/glove scroll from the basket for its own gear, priced by `Market.price` | "B> 60% glove att, paying 950k" |
  | GEAR | a share of `CitizenGear.upgradeFor` turns from a free hand-out into a want with a deadline (D4) | "looking for a Dark Snipe" |
  | PARTY | a citizen PQ or boss run short a role (hooks `CitizenPartyQuests`) | "need a cleric for Balrog" |
  | QUEST_ETC | phase 5, only if citizens run stock quests (forecast) | "short 30 Jr. Necki skins" |

- **Posting**, in order of reach. Every line names the real item, count and price:
  - map chat, which replaces the random-basket "B>" item;
  - a whisper to a REGULAR-or-better human on the same map, at most once per hour per human;
  - a share of the megaphone cap.
- **Request boards.** Stock Bulletin Board NPCs (9040011) are spawned server-side at the FM entrance and in the main
  towns, the way `!npc` spawns a template, so no client change is needed. Clicking one opens a `LivingTalk` menu: browse
  (sorted by distance and standing), details, claim, "my requests", records (F7). Players also get `@requests` in chat.
  There is no client quest log; the board is the log.
- **Fulfilment:**
  - **In person:** double-clicking the citizen (new `Relation.Citizen` branch at `ResidentDialogue.java:554`) offers "I
    have your <item>". The item **moves into the citizen's inventory** (it is not destroyed; the citizen equips or uses
    it). Payment comes from its own mesos via `EconomyTransactionCoordinator`, and PersonMemory gets a new `FULFILLED`
    episode (about +8 warmth, more than HELPED).
  - **At a board,** for an offline citizen: the item goes into escrow and the citizen takes it on its next load. Payment
    is immediate; warmth is smaller, and the citizen whispers thanks when you are next both online.
- **The market follows needs.** A citizen with an open GEAR or SCROLL need also browses human shops for exactly that item
  at up to its offer price, so a human's FM shop gets real buyers.
- **Expiry and limits:**
  - A need ends when it is met, when the citizen buys the item elsewhere, or after 24-48 h.
  - A player holds at most 3 claims, each for 60 min.
  - Warmth from the same citizen counts once a day.
  - Price is capped by the index; for equips, base or better is accepted.
- **Lines** come from `chatter/needs.yaml`. No Jev.

### Phases

- **F1.1** NeedLedger with SUPPLY, SCROLL and PARTY from real state; real items in map chat and megaphones; `!needs <citizen|map>` (GM).
- **F1.2** Board NPC placements, `LivingTalk` menus, claims, and `@requests`.
- **F1.3** In-person fulfilment: the citizen double-click branch, item into its inventory, payment, the FULFILLED
  episode.
- **F1.4** Board escrow for offline citizens; market buying toward needs.
- **F1.5** GEAR wants (D4); QUEST_ETC only if D4b is yes.
- *Done when*, in one live hour, the FM and towns show real needs, a human fulfils one in person and one at a board, is
  paid from the citizen's purse, the citizen's warmth rises, and the citizen equips or uses the item.

### Live checks

L1 `!needs` matches what the citizen lacks. L2 the board lists, claims and expires. L3 in-person hand-over (inventory
before and after, mesos moved, warmth). L4 escrow plus the next-load pickup. L5 a human FM shop sells to a citizen with a
matching need. L6 believability pass on the lines.

---

## F2. Learning: companions pick up your habits; citizens learn where to train

### What exists

- **Combat learner:** an SMDP Q-learner over skills (`server/agents/capabilities/combat/learning/`, modes
  LIVE/LEARN/FROZEN/OFF). LEARN runs only in the arena, or on a bench with no human present
  (`BenchCombatLearning.java:44-56`).
- **Companion camps:** a UCB bandit (`BenchDutyChooser.java:95-174`) over measured `BenchYieldModel` rates
  (`companion_yield`, key = branch, level/10, map), shrunk toward hand priors (`BenchYieldModel.java:33-38,97-119`).
- **Citizen training spot:** a uniform random pick among the `FieldCatalog.trainingField` candidates
  (`FieldCatalog.java:138-181`, random at `:180`). No EXP/h memory.
- **Potions, loot, buffs:**
  - Autopot at 0.7 HP / 0.5 MP (`agent-engine.yaml:5591,5607`).
  - The owner sets potion % and loot mode in `CompanionSettings.java:26-87`; modes ALL/OWN_KILLS/QUEST_ONLY/NONE
    (`AgentLootPolicy.java:22-38`).
  - Buffs recast at exactly 90% (`AgentCombatSupportPolicy.java:182-184`).
- **Human hooks** in `LivingWorldHooks`:
  - **Exist:** `mapEntered`, `looted`, `itemGained`, `levelUp`, `playerDied`, `mobKilled`, `chat`.
  - **Missing:** skill use, potion use (with HP% at the drink), SP spend, item drop, NPC sell, drops left on the
    ground.

### Design

- **F2a Citizens learn where to train.**
  - Loaded TRAIN sessions book EXP/h per (branch, level/10, map), reusing the `BenchYieldModel` key and shrink, with
    rows tagged `source=citizen`. Citizen data then also sharpens companions' priors.
  - Replace the uniform pick with weighted UCB plus a crowding term (fewer bots on a map scores higher), keeping the
    capacity filter and the 60% human-map preference.
  - Abstract (unloaded) citizens do not book anything.
- **F2b `OwnerHabits`** (per human, persisted) is fed by new hooks:

  | Habit | Hook | Feeds |
  |---|---|---|
  | Potion point | `UseItemHandler`, `PetAutopotProcessor` (HP%/MP% at each drink) | the default potion threshold in `CompanionSettings` when the owner set none; floor 0.45 HP, never applied in boss content |
  | Loot taste | `looted` plus drops that expired near the owner untouched | a new `AgentLootPolicy` rule `LIKE_OWNER`: skip item categories the owner always skips (never derives NONE) |
  | Selling | `NPCShopHandler` sells | companions vendor those items on resupply instead of hauling them |
  | Training maps | `mapEntered` + `mobKilled` per level band | `BenchDutyChooser.camps()` candidates and a prior bonus ("I'll camp where you train") |
  | Skills | skill-use hook, only when owner and companion share a job | a small prior bump in the combat learner; measured values still win |

- **Visible and resettable.** A companion card page reads "Learned from you: drinks at ~55% HP, skips ores, camps where
  you train", with "forget my habits" (D7). Each habit is mentioned once, in voice: "noticed you never pick up arrows, so
  I'll leave them too."

### Phases

- **F2.1** Citizen spot learning (no new hooks).
- **F2.2** New owner hooks.
- **F2.3** The `OwnerHabits` model, persistence (changeset 52), and `!habits <player>`.
- **F2.4** Wire it into settings defaults, `LIKE_OWNER`, camps and the combat prior; the card page and lines.
- *Done when* citizens' training maps spread by measured yield (fewer crowded maps in C2 stacking), and a companion's
  potion point, loot skips and camp match a test owner's behaviour after one session.

---

## F3. Letters: what happened while you were away

### What exists

- **Login line:** "While you were away" covers only camp moves and going home (`RosterService.java:2321-2370`), using the
  `untoldEvents` marker pattern (`LivingStore.java:1897`).
- **`living_event_log` persists,** with kinds such as `haul.duey`, `roster.release`, `bench.camp-moved`, `contract.*`,
  `job.advanced`, `friend.pq` and `friend.boss`.
- **`BenchReport`** (items, EXP, level-ups, deaths, camp) is **memory only** and lost on restart (`BenchReport.java:12-19`).
  The login restore takes it and says nothing (`RosterService.java:2301`).
- **Banked EXP** turns into levels only when the companion is called (`BenchLedger.java:542`).
- **Notes:**
  - `from`/`to` are VARCHAR(13); the message is TEXT (`014-gift.sql:12-22`).
  - `NoteService.sendNormal` has no length or receiver check (`NoteService.java:40-43,84-98`).
  - The client shows notes at login (`PlayerLoggedinHandler.java:334`) or live via `show()`. The living login hook runs
    before that, and asynchronously, so it needs its own `show()`.
  - `NoteService` is built in `Server.java:1118` with no getter, and nothing in living uses notes.
- **Duey:**
  - `dueyCreatePackage` always passes a null message; the private `createPackage` takes one (`DueyProcessor.java:223-255,507-512`).
  - Message is VARCHAR(200), one item per package, 30-day expiry.
  - Roster mail sends no live notice.
- **Two tells in `sendIt`** (`RosterService.java:2157-2168`):
  - The haul keeps only id + count, so every mailed equip arrives with flat base stats (`getEquipById`).
  - A money-only parcel is wrapped around a Snail Shell (4000000).

### Design

- **Persist what bench work produced.** `reportHaul` and `reportSlice` also write `bench.report` rows (JSON detail) to
  `living_event_log`. The login restore stops throwing the report away; the digest consumes it.
- **Digest builder** at owner login, covering everything since the last digest:
  - **Per companion:**
    - EXP banked, shown as "ready for Lv N" because levels are projected.
    - Notable drops, by a rarity rule: equips within 10 levels of the owner, scrolls, and items in the top decile of the
      price index.
    - Job advances, camp moves, parcels sent.
    - Deaths as a soft count ("took a tumble twice").
  - **Citizens:**
    - FRIEND-or-better citizens who asked about you. New: such a citizen sometimes asks after an offline human in
      chatter ("anyone seen Ann?"), recorded as `citizen.asked`.
    - Needs fulfilled through escrow (F1).
    - Rival news (F7).
- **Delivery:**
  - One note per companion with news, sent as the companion, in its voice (templates per dialogue style profile), then
    `noteService.show(owner)` after the hook.
  - At most 5 notes per login; the rest fold into one crew letter. The 100-unread cap is respected.
  - The pink login line becomes a one-line summary.
  - Notes use the normal kind, not the fame kind.
- **Gifts by Duey.** A FRIEND-or-better companion sometimes mails a small gift (a scroll it found, a potion bundle) with
  a message, taken from its own haul share and never minted. Capped weekly. Add a public `createPackage` variant that
  takes a message, and send the live Duey notice when the owner is online.
- **Fix the two tells.**
  - Mailed equips get drop-like random stats (`randomizeStats`, as a real drop would).
  - Money-only parcels need no item, if Duey accepts a null item (check); otherwise the money goes with the first real
    item or through the bank.

### Phases

- **F3.0** Fix the equip stats and the meso-only parcel.
- **F3.1** Persist the bench report, plus `citizen.asked`.
- **F3.2** Digest, notes, `NoteService` access and `show()`.
- **F3.3** Duey gifts with messages, plus the live notice.
- **F3.4** Voice templates.
- *Done when* logging in after a night away shows a short pink summary plus notes from each companion that did something,
  and a restart in between loses nothing.

---

## F4. Mentor for new characters, and apprentices

### What exists

- **Character creation:** `CreateCharHandler.java:51-52` allows Adventurers only; `BeginnerCreator.java:46` starts them
  in Mushroom Town. There is **no first-login or new-character hook** (`newcomer` only means "loaded this session",
  `PlayerLoggedinHandler.java:155-159`).
- **Companions:**
  - `!grantcompanion` → `RosterService.grant` benches the persona at home and shapes it at its first call
    (`ResidentRuntime.java:494-531`).
  - `beginEscort` makes a temporary follower (`RosterService.java:2203-2232`).
  - Companions **follow, never lead** (`PortalFollow.java:13-16`).
  - `RecallTravel` knows only Victoria roads. Maple Island navigation exists only in the agents layer
    (`AgentLithHarborArrivalRouteRuntime.java:13-25`).
- **Answers:** `ContentAnswers` answers TRAIN_WHERE, JOB_ADVANCE, BUILD and HOW_TO from data with no Jev
  (`ContentAnswers.java:36-48`). OPINION questions go to the model.
- **Hires:**
  - A hire's branch is set by typing its name in chat, which changes the job instantly with no errand
    (`AgentChatJobAdvancementFlow.java:12-23`).
  - AP and SP builds can be chosen (`AgentBuildService.java:45-81,229-240`).
  - Hires drop their career and get no job errands (`ProgressionLedger.java:51-53`, `JobErrands.java:179-183`).

### Design

- **New-character hook.** The first login of a character that has never logged in is recorded in `living_event_log` as
  `mentor.offered`, so it is offered once.
- **The offer:** a `LivingTalk` "A traveller offers to show you around". Declining is final for that character (D8).
- **Mentors:** a small authored pool of 3-5 mentor personas. Each is shaped to stay 3-5 levels above the newcomer, has
  zero EXP share and QUEST_ONLY loot, so it never takes the newcomer's progress, and holds a party seat but no roster
  slot.
- **Teaching:**
  - Deterministic tips at milestones (the first potion at low HP, the first death, Lv 8 job choice, Lv 10 advancement,
    reaching Victoria), from `ContentAnswers`.
  - Questions get the existing knowledge answers.
  - Tips have a cap and a "quiet please" verb.
- **Lead behaviour (new):** the mentor walks ahead to the next goal (portal or NPC), waits on the far side, and comes
  back if not followed within ~20 s. Routes:
  - Mushroom Town → Amherst → Southperry → ship to Lith Harbor, with the mentor riding along, via the agents' arrival
    route.
  - On Victoria, to the instructor town for the job the player picks after the mentor explains the options.
- **Graduation** at 2nd job (D9): a goodbye scene, a gift, and the mentor returns to its home town at FRIEND standing (C1).
- **Apprentice:**
  - A beginner citizen (Lv ≤ 10) can be adopted at REGULAR standing, or it asks for a mentor through an F1 PARTY need.
  - It becomes a hire flagged APPRENTICE.
  - At each advancement the owner picks the branch in a `LivingTalk` menu and an AP/SP preset from `AgentBuildService`.
  - Advancement goes through a real instructor errand (add an apprentice path to `JobErrands`) instead of the instant
    chat change.
  - It graduates at 2nd/3rd job into a normal companion, keeping its friendship (C1).

### Phases

- **F4.1** Hook, offer, mentor pool, shaping to the newcomer.
- **F4.2** Milestone tips and mute.
- **F4.3** Lead behaviour, Maple Island route, ship crossing, first-job escort.
- **F4.4** Graduation.
- **F4.5** Apprentice: adoption, job and build menus, errand-based advancement.
- *Done when* a fresh character is guided from Mushroom Town to its first-job instructor without typing anything, and an
  apprentice reaches 2nd job on the branch its owner chose, through the instructor.

**Risk:** companion travel on Maple Island and the ship are unproven (F4.3). Prove them in the movement lab before any
live run.

---

## F5. Town classics: minigames, jump quests, party megaphones

### What exists

- **Minigames** (stock Cosmic):
  - The rules are inline in `PlayerInteractionHandler.java`: create omok `:167-200` (item 4080000+), match cards
    `:201-241` (4080100), visit `:349-364`, ready/start `:443-460`, moves `:505-534`. State lives in `MiniGame.java`;
    records are in `characters` (`omokwins` and the like).
  - The server keeps no turn state.
  - **FM maps forbid minigames** (fieldLimit includes `CANNOTMINIGAME` 0x80); Henesys allows them.
  - Omok win detection has a known bug (`MiniGame.java:436-438`, Cosmic PR #294 in `COSMIC_PR_APPLICABILITY_TODO.md:47`).
  - A bot can play entirely server-side through the same calls. There is no observer hook, and `getCardId` exposes the
    whole deck.
- **Jump quests:**
  - Maps: Pet-Walking Road 100000202, Forest of Patience 101000100-104, Deep Forest 105040310-316, Shumi
    103000900-909, Breath of Lava 280020000/001.
  - No start-to-top route is proven on any of them. Pet-Walking Road is already a nav regression map.
  - Trap damage is client-side only.
  - A fall below the map teleports the bot to its entry portal (a CRITICAL recovery, `AgentAirbornePhysicsService.java:106-180`).
  - The old SoloMapling `HenesysJQBot` replays recordings, with fail variants, for 100000202 only.
- **PQ recruiting** uses map chat ("lf{need}m {pq}" every 90 s), guild calls and lobby invites, never megaphones
  (`CitizenPartyQuests.java:60-67,392-511`). Megaphones carry only recruit/sell content and use no item
  (`ChatterEngine.java:481-485,584`).
- **GM events** (Ola, Fitness, OX, Snowball, Coconut) exist with no agent code.

### Design

- **F5a Minigames.**
  - Extract a `MiniGameRules` service (create, visit, ready, start, move, select card, give up, tie, close) that both
    the handler and bots call, pinned first by characterization tests. Add an observer hook on `MiniGame`. Apply PR #294
    (D10).
  - New citizen activity **PLAY**, with a cohort weight, in towns that allow minigames (Henesys, Ludibrium, Kerning,
    Ellinia, Perion), never the FM. Two citizens open a room and play each other, and the room balloon is visible.
  - A human can sit in an open room or open one near PLAY citizens; a citizen accepts, readies and plays.
  - **Omok AI:** threat-based search with a per-citizen strength knob plus noise; strong players are rare.
  - **Match cards:** a memory model with per-bot recall and forgetting, never the full-deck knowledge.
  - Think time is 1-8 s with spread. "gg" lines; a citizen leaves after a few games. Records count as stock.
  - PLAY citizens get real omok and match-card items from a wardrobe pool.
- **F5b Jump quests.**
  - Start with Pet-Walking Road and Forest of Patience, then Shumi. Breath of Lava stays out (traps, D11).
  - Each route is proven start-to-top in `BotMovementSimulationLabTest`.
  - An execution-noise knob jitters jump launch timing and position, so failures are real drops to a lower foothold,
    **never below the map**. Tune per citizen to roughly 30-70% success per stage.
  - Retries are capped, then a give-up line. The reward comes through the stock NPC/quest where its script works for
    bots.
  - The SoloMapling recordings stay as a fallback only.
- **F5c Party megaphones.**
  - When a citizen PQ party stays short past N minutes, its leader sends "LF 1 more KPQ 21-30, whisper Ann". It shares
    the megaphone cap and **uses up a real megaphone item**. Megaphones are cash items no NPC sells, so recruiting
    citizens carry a small stock granted with their supplies, and the count is real.
  - Human whisper intake for citizens ("me", "inv", "can I join") goes to the recruiting leader, who invites. This also
    fixes the "citizens ignore whispers" tell.
- **F5d** (later): bot-hosted Ola/Fitness rounds with citizen entrants, once F5b's navigation is proven.

### Phases

- **F5.1** `MiniGameRules` extraction, hook, PR #294.
- **F5.2** Citizen-vs-citizen omok in towns.
- **F5.3** Human challenges and match cards.
- **F5.4** LF megaphones and citizen whisper intake.
- **F5.5** JQ on Pet-Walking Road and Forest of Patience with believable falls.
- **F5.6** Shumi and bot-hosted events (optional).
- *Done when* a human can walk into Henesys, see citizens playing omok, sit down and play one, and watch a citizen fail
  and finish a jump quest without any teleport.

**Hardest risk:** reliable JQ completion with believable failure and no below-map recovery in sight of players (F5.5).

---

## F6. Companion story arcs and crew relationships

### What exists

- **Arcs and rumors:**
  - Arcs are **world-global, one per persona** (`ArcService.java:15-60`).
  - Rumors are multi-speaker map chat from materialized Residents, skipping recruited ones
    (`RumorScheduler.java:142-148,154-224`).
- **Quests:**
  - Step types: TALK, KILL_MOBS, COLLECT_ITEM, DELIVER, VISIT_MAP, TALK_TO, HEAR_RUMOR, ESCORT, HUNT_WITH, PAY,
    GIVE_ITEM, REACH_LEVEL, WAIT, PARTY_QUEST_CLEAR, KILL_BOSS (`Content.java:24-27`).
  - Requirements: `level`, `bondStage`, `flags`, `notFlags`, `questsCompleted`, `friendship` (`ContentLoader.java:300-308`).
  - There is no warp, map effect, mob spawn, instance or duel step.
- **Job errands:** the companion goes alone; there is no attend mode (`JobErrands.java:348-382`).
- **Companion talk:** companions speak only fixed owner-facing lines. There is **no companion-to-companion chatter**.
- **Relationships:** `Persona.relationships` (RIVALS, OWES, BLAMES) is authored (e.g. `personas/henesys.yaml:22-24`) but
  only `ContentLint.java:107` reads it. Spouses become stock marriage rows (`LoreCouples.java`).

### Design

- **Personal arcs** are per (player, companion), not world-global. State lives in `living_bond.flags`; chapters are
  quests gated by `requires.friendship` and flags.
  - Each recruitable gets a 3-chapter arc that unlocks at friendship Lv 3, 6 and 9.
  - The capstone is a scene.
  - Quest-log ids 31600-31999 are reserved now and mirrored once the client patch ships.
- **SCENE step type:**
  1. The player and companion enter a private instance of an authored map (event-instance pattern).
  2. Multi-speaker lines from the companion and authored others.
  3. Optional effects, only from a vetted safe list. Some avatar effects crash the client; see the QuestAlert note in
     the guide.
  4. An optional fight: authored mobs, or a **rival challenge**. The companion and a rival persona race to defeat a
     summoned foe, and the player helps. There is no PvP primitive, so the duel is a race (D14).
- **Attendable job errands.** Before a 3rd or 4th job errand, the companion asks "come with me?". If the owner says yes,
  the companion leads them (F4.3 lead behaviour) to the instructor and advances in front of them with lines and a stock
  effect. Its capstone fires if friendship is 9 or higher. If the owner says no, it goes alone as today.
- **Crew ties.**
  - `CrewTies` keeps per-pair affinity inside one roster: shared PQ/boss clears add to it, and seeds come from
    `Persona.relationships` and spouses, which are finally used at runtime.
  - **Banter:** two-line call/answer exchanges in party chat at calm moments (in town, walking, after a clear), with
    variants by tie. Rivals tease using real damage numbers from `companion_performance`.
  - At most one exchange per 10 min of party time, with a mute verb.
  - Two rivals in one crew can unlock a rival-challenge chapter.
- **Content.** Pilot 3 companions (D15), then author the rest with an author+review workflow, as friendship was done.
  Lint: SCENE maps and mobs exist, line lengths, every chapter is reachable.

### Phases

- **F6.1** Per-player arc state, unlocks, and dialogue-only chapters 1-2 for 3 pilots.
- **F6.2** SCENE step: private instance, lines, mobs, safe effects.
- **F6.3** Attendable job errands (needs F4.3).
- **F6.4** `CrewTies`, the banter catalog, runtime relationships.
- **F6.5** Content for all recruitables.
- **F6.6** Rival challenge scenes.
- *Done when* a pilot companion's arc runs from Lv 3 to its capstone scene with the player present, and a crew of two
  rivals trades real-number banter in party chat.

---

## F7. Rival crew

### What exists

- **Rankings:** level-only top 50, citizens included (`Server.java:860+`, `RankingLoginTask.java:51`). Guild ranking by
  GP, with the bot guilds included.
- **Citizen bosses:** citizen boss runs are on, but limited to `easy_balrog` and `balrog_normal`, with
  `BOSS_RUNS_MAX: 1` (`config.yaml:1256-1259`). Zakum and up need a human.
- **Clear records:**
  - Citizen clears live in journal lines and an in-memory tally (`CitizenPartyQuests.java:822-850`).
  - Companion clears persist (`companion_performance`).
  - **Human boss clears are recorded nowhere** (`ExpeditionBossLog` only enforces entry limits).
- **Circles** of 3-5 buddies form by cohort, not level (`SocialFabric.formCircles:504`).

### Design

- **`living_clear`:** one record per PQ or boss clear by anyone (humans, companions, citizens), with members, duration
  and time. New hooks at boss death and PQ completion. It feeds a **Records** page on the F1 boards (first clear and
  fastest clear per PQ/boss) and the F3 letters.
- **The rival crew:** for each human main character, a named crew of 4-5 existing citizens within ±5 levels, with its own
  name and bot guild.
  - **No EXP steering** (D12): membership refreshes as levels drift, and a member who falls behind "leaves the crew"
    with a line.
- **Contact:**
  - Passing banter when you share a map.
  - A megaphone when they beat one of your times; a whispered congratulation when you beat theirs.
  - **Help requests:** for content they cannot do alone (Zakum and up), they post an F1 PARTY need inviting you as a
    guest, and the run uses the citizen expedition engine.

### Phases

- **F7.1** `living_clear`, hooks, and the records page.
- **F7.2** Crew formation, names and guild.
- **F7.3** Record reactions, plus letters.
- **F7.4** Help requests through F1.
- *Done when* a human's Balrog time appears on the records board, their rival crew reacts when it beats or loses to it,
  and the crew asks the human along for a run it cannot do alone.

---

## F8. Small imperfections, with hard caps

User constraint, 2026-10-26: **no severe mistakes, so no frequent deaths and no long inactivity.**

### What exists

- **Autopot** fires immediately on any stat loss (`Character.java:10824-10874`), with a 1 s backstop
  (`AgentPotionService.java:339-355`).
- **Buffs** recast at exactly 90%.
- **Reaction jitter** is capped at 500 ms.
- **Citizen AFK:** 15% of re-plans go AFK for **5-15 min on any activity, training fields included**
  (`PresenceScheduler.java:917-927`). That is already longer than the constraint allows outside town.
- **Deaths:** a bot returns to town after 5 s. No bot asks for or casts Resurrection.

### Design

One `Imperfections` gate owns every slip. It enforces:
- a per-bot hourly budget;
- excluded contexts: boss maps, event instances and PQs, the owner in danger, low HP, party content;
- a kill switch;
- C2 metrics before and after.

| Slip | Rule | Cap |
|---|---|---|
| Personal potion point | each bot drinks within its own 60-75% band instead of exactly 70% | never below the 0.45 floor; boss settings untouched |
| Late potion | p≈0.15, a non-critical drink waits 0.3-1.5 s | only while HP stays ≥ 50% and no mob in range can hit for more than (HP − 40%); the 1 s backstop stays |
| Buff timing | recast anywhere in 85-100% of duration; rarely (p≈0.05) a non-critical buff lapses ≤ 10 s | never survival buffs, never in boss or PQ |
| Brief AFK, companions | in town only, while the owner is idle in town | ≤ 90 s, at most once an hour, never in party content |
| AFK, citizens (changes today's rule, D6) | the long 5-15 min AFK stays in town/FM, where AFK is normal | on fields: a pause of ≤ 2 min ("brb water") |
| Fumbles | a missed rope grab in town, walking past an NPC and turning back, a chat typo then "*fix" | town only; never in combat |
| Loot slip | now and then leaves a low-value drop | never quest items, equips, or anything above the price-index median |
| "rez pls?" | after a real death, if a party member has Resurrection; a bishop bot casts it for real | the wait is capped at 10 s, then the respawn runs as today |

**Acceptance (D16):** deaths per hour per class stay within noise of the C2 baseline; no AFK outside town or FM runs past 2
min; nothing fires in boss or PQ content.

### Phases

- **F8.1** The gate, caps and metrics (needs C2.1).
- **F8.2** Potion band and late potion.
- **F8.3** Buff variance.
- **F8.4** AFK rules.
- **F8.5** Fumbles and typos.
- **F8.6** "rez pls" and bishop Resurrection.

---

## 11. Order and dependencies

- **C2 first**, because it is the measuring stick. F8 waits for C2.1.
- **C1 before** F4.4/F4.5 (graduation carries friendship) and F6 (hire friendship, arc gates).
- **F1 before** F7.4 (help requests are PARTY needs) and F4.5 (the "looking for a mentor" need). F1 also hosts F7's
  records page.
- **F4.3 lead behaviour before** F6.3 (attendable errands).
- **F2, F3 and F5** stand alone.

| Wave | Pieces (one session each, own worktree off the then-current main line) |
|---|---|
| A | C2, F3, F2, F5a (minigames) |
| B | C1, F1, F8 (after C2 merges) |
| C | F4, F7, F5b-c (JQ, megaphones) |
| D | F6 (content-heavy; pilots first) |

**Hot files** (one writer at a time; later waves rebase):
- `ChatterEngine.java`: C2, F1, F5c, F7
- `ResidentDialogue.java`: F1, F4
- `RosterService.java`: F3, C1, F4
- `PresenceScheduler.java`: F2a, F5, F8
- `LivingWorldHooks.java`: F2, F3, F7
- `PlayerInteractionHandler.java`: F5a only
- `config.yaml` (separate sections) and `changelog-tables.xml`: all

## 12. Allocations

- **Changesets** (renumber at merge if needed, as before):

  | Changeset | SQL file | Piece | Contents |
  |---|---|---|---|
  | 51 | `042` | F1 | `living_need` + escrow |
  | 52 | `043` | F2 | `living_owner_habit` + a source column on `companion_yield` |
  | 53 | `044` | F7 | `living_clear` + `living_rival` |
  | 54 | `045` | F4 | roster `role` (MENTOR/APPRENTICE) |

  C1, C2, F3, F5, F6 and F8 need no schema change: they use `living_bond`, `living_bond.flags` and `living_event_log`.
- **Quest-log ids:** F6 31600-31999 (reserved, mirrored with the client patch). The Residents sessions hold 30800-31449
  and 32000-32149, and iTCG/Masteria holds 31450-31599. Free after that: 32150-32767 (agreed with the feature-gap
  coordinator 2026-10-27).
- **Other coordinator:** its sessions take changesets from 56 / file 047 up. Each coordinator tells the other before it
  merges onto `feat/companion-manager` or restarts the live server.
- **Kill switches:** `TELL_AUDIT_ENABLED`, `HIRE_FRIENDSHIP_ENABLED`, `NEEDS_ENABLED`, `CITIZEN_SPOT_LEARNING_ENABLED`,
  `OWNER_HABITS_ENABLED`, `LETTERS_ENABLED`, `MENTOR_ENABLED`, `APPRENTICE_ENABLED`, `MINIGAMES_ENABLED`, `JQ_ENABLED`,
  `LF_SMEGA_ENABLED`, `PERSONAL_ARCS_ENABLED`, `CREW_BANTER_ENABLED`, `RIVALS_ENABLED`, `IMPERFECTIONS_ENABLED`.

## 13. Decisions (accepted 2026-10-26)

The user accepted every recommended default on 2026-10-26 ("Accept the recommended defaults for D1-D17 and start wave
A"). The "Recommended" column below is now the decision.

| # | Decision | Recommended |
|---|---|---|
| D1 | Hires join the Lv 1-10 friendship meter, with generic perks; authored content stays Residents-only | yes |
| D2 | Hire offers gated by standing REGULAR (warmth ≥ 15) instead of 10 party minutes; fee discount at FRIEND | yes, no free hires |
| D3 | Hires can marry | not in v1 |
| D4 | 30% of citizens' free gear upgrades become wants with a 48 h deadline, then granted as today | yes |
| D4b | Citizens run selected stock quests so ETC needs are real | later, after F1.4 |
| D5 | Needs fulfilled both in person and through board escrow | both |
| D6 | Citizen AFK on fields shortened to ≤ 2 min; 5-15 min AFK only in town/FM | yes |
| D7 | Owner habits shown on the companion card and resettable | yes |
| D8 | Mentor offered once, automatically, at a new character's first login | yes |
| D9 | Mentor graduates at 2nd job | 2nd job |
| D10 | Apply the omok win-detection fix (Cosmic PR #294) for everyone | yes |
| D11 | JQ v1 = Pet-Walking Road + Forest of Patience; Shumi later; Breath of Lava out | yes |
| D12 | Rival crew per human main, from matching citizens, no EXP steering | yes |
| D13 | Human clears appear on a public records board | yes |
| D14 | Capstone "duel" staged as a race in a private instance, not PvP | yes |
| D15 | Story arcs: pilot 3 companions, then the rest by workflow | yes |
| D16 | Imperfections may not raise deaths: per-class death rate within baseline noise | yes |
| D17 | A Jev "sounds like a bot" judge on sampled chat (≤ 20 calls/day) | not in v1 |

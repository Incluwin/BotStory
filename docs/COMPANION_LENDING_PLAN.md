# Companion Lending and Contract Loot Terms

> Status: plan, 2026-10-25, branch `feat/companion-lending` cut from `ff6fbe48a`. Two gaps from
> BOT_MANAGEMENT_PLAN.md "Out of scope / later" (lines 1061 and 1068): lending a companion to a friend, and enforcing
> the contract terms `lootShare` / `ownDropsPercent`. Paths are relative to `FriendServer/BotStory Server`. "Decisions"
> lists what the user chose; until then the recommendations are what gets built.

## 1. Summary

- **Lending.** From its card an owner lends a companion out with them to a friend (a mutual buddy or a guild mate) on
  the same map, for 30, 60 or 120 minutes. The friend answers Yes/No in a stock NPC window. The companion walks over and
  joins the friend's party on a guest seat charged to the friend, and follows and fights with them. The friend
  directs it from its card (double-click): stick with me, wait here, hunt here, send it back. The party window works
  too: expel sends it home, invite brings it back. It comes home when the time is up, when its owner calls it back from
  their card, when either player logs off or changes channel, when the friend's party has no seat for it, or when
  either deletes the character.
  - **Ownership stays where it was.** The roster row stays the owner's, written BENCHED while the loan runs, so a crash
    or restart leaves it home on the bench. The loan itself lives in memory, like CompanionLeases.
  - **The friend never gets `mayControl`.** Whispers and typed party commands from the friend still do not reach the
    companion. Every property surface (trade, haul, gear, wages, settings, duties, release, job, build) stays
    owner-only. What the friend gets is a narrower, host-only "may direct" that only the card's field orders and the
    party window use. A new claim-guard seam keeps the friend's typed chat away from an Agent they lead only on loan.
- **Loot terms.** A contracted companion's loot follows its contract:
  - `OWN_DROPS p`: it keeps p% of what its own kills drop. Everything else it picks up is the owner's share.
  - `NONE` and `LEADER`: it keeps no cut.
  - In every case it first keeps its supply purse topped up (upkeep first, LIVING_RPG_PLAN.md:680).
  - The owner's share moves out of its bag and purse into its haul, the ledger the owner already collects from: hand
    over, Duey or bank.
  - The card says the terms, and the haul page shows the share.
  - A contract without the fields behaves as today: no split.
- **Both are behind kill switches**, on by default: `COMPANION_LENDING_ENABLED` and `COMPANION_LOOT_TERMS_ENABLED`.

## 2. Current state (verified in source)

### Ownership and the claim guard
- `AgentClaimGuard` (server/agents/auth/AgentClaimGuard.java) is the per-Agent seam: `mayControl` (:14),
  `mayLoad` (:20), `isClaimed` (:25), `refused` (:31). With no rule installed, everything is allowed (:78-91).
- `CompanionDirectory` is the installed rule (server/living/roster/CompanionDirectory.java:31).
  - `mayControl` (:156-172): administrators, or the roster owner while no live bench slice holds the companion.
    Residents and unhired citizens: nobody.
  - `mayLoad` (:180-192): only the owner, holding the COMPANION lease.
  - `isClaimed` (:196): every persona and citizen.
- `CompanionLeases` (server/living/roster/CompanionLeases.java) holds one lease per character: `Kind` (:16),
  `tryAcquire` (:27-31). It is in memory only.
- **Whispers.** The whisper bridge resolves a whispered Agent only through `mayControl`
  (server/agents/integration/cosmic/CosmicAgentWhisperCommandBridge.java:72-82). The line then reaches every chat flow
  through `AgentChatRouteCoordinator.handleWhisperChat` (:171). That includes property flows: AgentChatTransferFlow,
  AgentChatEquipmentFlow, AgentChatRespecFlow, AgentChatBuildFlow, AgentChatJobAdvancementFlow, and "give me
  <items>/mesos" (AgentInventoryTransferService.java:223-234 → AgentMesoTradeService).
- **Party and map chat** is gated only by `mayOperate` (AgentChatRouteCoordinator.java:48-56). The line goes to
  **every Agent whose runtime leader is the speaker** (`entriesByLeader.get(leader.getId())`, :80 and the hooks
  :101-166). The claim guard is asked only for named transfer and dismiss (:117-121, :135-146, :311-322).
  - **Consequence:** whoever is a companion's runtime leader can type commands to it. Making a friend its leader
    without a new gate would hand them respec, equipment, trade and "give me mesos".
- A claimed Agent's leader is never moved by chat (AgentLeaderTransferCoordinator.java:57-61). The in-place
  re-registration pattern used by a plain transfer (unregister, cancel tasks, stop, register under the new leader) is
  AgentTransferService.java:119-131.
- Loading a companion (RosterService.java:1237-1307) admits the owner's budget, takes the COMPANION lease
  (`takeCharacter`, :1309-1328), spawns, joins the owner's party, then writes ACTIVE.

### Party seats
- `CompanionSlots` is the only seat authority. `Kind.GUEST` (:52) is a seat charged to the human who invited a
  citizen.
  - The seat gate `admitsJoin` (:366-389) admits a bot into a party with a human only on a reservation charged to a
    human in it.
  - `view` (:420-451) charges a GUEST to its host for fair share. A guest steps out first (`KEEP_ORDER`, :501).
  - `onPartyChanged` (:328-357) gives a GUEST seat back when its holder leaves, is expelled or the party disbands.
- `CompanionPartySync`:
  - `joined` (:310-325) logs drift and pulls out any companion that joins a party without its owner.
  - `left` (:327-372) and `loggedOnOrOff` (:428-445) make the guests of a host who leaves or logs off leave (through
    `Crew.guestLeaves`).
  - `rebalance` and make-room step guests out first (`stepOut`, :552-570).
- `LiveCrew.hostOf` (:64-67) reads the GUEST reservation keyed by the character's slot key, which is its persona id
  for a companion. `guestLeaves` (:204-216) whispers and leaves the party.
- `CompanionDirectory.isCompanionParty` (:119-147) is false when any member is another player's companion. That
  would refuse a friend's accept-merge while they have a borrowed companion.
- `CompanionPartyRouter.invite` (:40-54) denies an invite of another player's companion (stock 23).

### Lifecycle hooks and drift
- `LivingWorldHooks`: logout (:227-254), channel change (:256-270), Cash Shop (:272-287), character deleted
  (:289-301), party changed on companion-ops (:587-608), map entered (:159-181), double-click (:497).
- The roster's states are in LivingStore.java:86: ACTIVE, BENCHED, AWAY, RELEASED, TRAVELLING, HOLD, PARKED. Legal
  moves are in CompanionSeatState.java:34-47. `BenchSessions.benchGoverned` (:40-42) is BENCHED or AWAY.
- The 60 s auditor (CompanionAuditor) repairs drift toward the roster. Its `unseat` (RosterService.java:3201-3224)
  would take a BENCHED row's loaded companion out of the owner's party. That breaks a loan inside a party the owner
  shares with the friend. CompanionDriftReport would also flag a lent companion led by the friend as
  `UNROSTERED_AGENT` under the friend.
- **Follow:**
  - Portal-true follow and the follow guards answer only for the roster owner (CompanionFollowGuard.java:61-72;
    RosterService.ownerEnteredMap :3078-3083).
  - Anyone else's follower comes in beside its leader, as plain Agents always have.
  - The follow instance guard (P1) keeps an unregistered Agent out of an event instance's map copy.
- The companion's settings (potion thresholds, loot policy, formation) apply only while its runtime leader is its
  owner (CompanionSettingsApplier.java:404-414, :510-513). Under anyone else it would loot with no policy (ALL).
- Budget: `ownerCompanions` counts Agents by runtime leader (CompanionBudget.java `usage`). `admitInPlace` checks the
  pools for an adoption that loads nothing.
- **PQ desks:** `CompanionPartyQuestService.plan` builds a candidate for every party bot (:583-596). At Yes,
  `prepareEntry` holds every extra through `RosterService.hold(owner, …)` (:1041-1080). A companion whose row is
  BENCHED fails that hold, so a borrowed companion in the party would block every run until handled.

### Loot today
- The terms are parsed and never read.
  - Enum: `LootShare { OWN_DROPS, NONE, LEADER }` (server/living/config/Content.java:31).
  - Record: `Terms(…, lootShare, …, ownDropsPercent)` (:191-192).
  - Defaults: `ContentLoader.contract` (:449-456) defaults to `OWN_DROPS` and 10 when either field is absent. So
    today the record cannot tell "absent" from "authored".
  - No reader exists in src/main, and ContentLint has no checks for them.
- **Content.** All 44 contracts author `lootShare`:
  - 42 are `OWN_DROPS`, with 10% (36), 15% (4) or 20% (2).
  - `ilse-house-call` is `NONE` (henesys.yaml:43).
  - `jinx-a-name-you-heard` is `LEADER` (kerning-city.yaml:27).
  - Hired citizens work under `hired-hand` (OWN_DROPS 10).
- **Meaning as authored** (docs/LIVING_RPG_PLAN.md:212, :677-683):
  - Upkeep is paid first.
  - `NONE`: the companion keeps nothing beyond upkeep.
  - `OWN_DROPS`: it keeps a declared cut, default 10%, spent on its own gear.
  - `LEADER`: everything goes to the owner.
  - The split also applies to the bench haul.
- **Pickup:**
  - `AgentPassiveLootService.tickPassiveLoot` (:53-105) → `Character.pickupItem`.
  - Items go into the bot's bag. Floor meso goes to its purse, split with the party on the map
    (Character.java:2571-2583).
  - Mob meso never reaches the floor: `MapleMap.dropFromMonster` credits the top damager's party on the map at the kill
    (MapleMap.java:758-773).
  - Every grant reports `LivingWorldHooks.looted(chr, itemId, qty, meso)` (:143-156). The hook carries no "whose kill"
    flag. A drop's `MapItem.getOwnerId()` is its top damager.
- **Haul:**
  - A live bench slice moves pickups from bag and purse into the haul (`BenchSessions.harvest`, :116-153, using
    HarvestDiff). TRAIN keeps everything.
  - The offline ledger credits FARM_MESO / FARM_ITEM / GATHER into the haul and takes upkeep from it
    (BenchLedger.accrue :95-215).
  - The owner collects with `handOver` / `sendIt` / `bankIt` (RosterService.java:2054-2143), from the card's
    "Your haul..." (ui/CompanionCardScreens.java:1119-1160).
  - Out with its owner, a companion keeps 100% of what it loots and pays for its own potions from it
    (CompanionRestock).

## 3. Lending

### 3.1 Who, what, where

| Rule | Value |
| --- | --- |
| Lender | The roster owner of the companion (`cachedOwnerOf == owner`). Administrators get no override: an admin lends only their own. |
| Companion | ACTIVE, loaded, on the owner's map and channel, led by the owner, in the owner's party, and not committed to a run (`Commitments`). Not in flight, not leaving, not at a ticket, party or keystone gate, not resting after a field boss. Not already on loan. Recruited Residents and hired citizens alike. |
| Borrower | An online human other than the owner, on the same map and channel. A **mutual buddy** (each lists the other, visible, not pending) or in the **same guild** (guild id ≠ 0). Not an Agent. Not inside an event instance. Holding fewer than `COMPANION_LOANS_PER_BORROWER` loans (default 1). Has a free seat (`CompanionSlots.free(borrower) ≥ 1`). |
| Owner limit | At most `COMPANION_LOANS_PER_OWNER` companions out on loan at once (default 2). |
| Length | 30, 60 or 120 minutes, capped by `COMPANION_LOAN_MAX_MINUTES` (default 120). |
| Offer | The friend's Yes/No stays open `COMPANION_LOAN_OFFER_MS` (default 60 s). One offer per companion and per friend at a time. |

### 3.2 Lifecycle

```
             Lend... (owner's card)         Yes (friend's window)
  ACTIVE  ─────────────────────────▶ OFFERED ─────────────────────▶ OUT ──┐
  (owner's)   checks 3.1, opens the         recheck everything,           │ end: time up, recall, logout,
              friend's window               hand over in place            │ channel/CS, no seat, expel,
                 │ No / timeout / busy                                    │ deleted, kill switch
                 ▼                                                        ▼
              nothing changes                                     RETURNING ──▶ home
```

**Hand-over (on companion-ops, one step, rolled back on any failure):**
1. Recheck 3.1. Admit the friend's budget in place (`admitInPlace(COMPANION, friend)`).
2. Move the lease atomically from (COMPANION, owner) to the new (LOAN, friend) (`CompanionLeases.transfer`). From here
   no roster load, bench slice, Resident tick or citizen session can take the character.
3. Reserve a GUEST seat keyed by the persona id, charged to the friend.
4. The companion leaves the owner's party (unless the friend is in that same party) under the loan's cause token
   `companion.loan`, so the sync takes it as an echo.
5. Write the owner's row ACTIVE → BENCHED. The owner's seat frees.
6. Re-register the Agent under the friend in place, with no despawn (the AgentTransferService pattern behind a new
   `AgentInteractionRuntime.handOverAgent`). It joins the friend's party, made when they have none.
7. Lines: it tells its owner in its own voice ("I'll go along with Ohboy for an hour. Back soon.") and greets the
   friend in party chat.

Any failure restores the lease, the seat and the row (ACTIVE in the owner's party), and the companion explains.

**While OUT:**
- It follows the friend, walks through portals behind them (portal-true follow extended to a borrower), and fights.
- It keeps its owner's potion thresholds and formation.
- **It loots only what its own kills drop** (loot policy OWN_KILLS while on loan; an owner's NONE or QUEST_ONLY stays
  as strict). So it never takes the friend's drops, and what it loots follows its contract with its **owner** (4.2).
- The friend's mob meso shares reach it as they reach any party member (the stock split). Those are not its own
  kills, so they go to its owner's share.

**Return:**
- **Owner on the same map and channel with a free seat:** it rejoins the owner's party, ACTIVE, with no blink (the
  lease and leader move back the same way). It says what it did ("Back. Ohboy and I cleared a few Wild Boars.").
- **Otherwise:** it walks out through the nearest portal and is unloaded (the roster's walk-out and unload). The row is
  already BENCHED, and its owner hears it in a whisper when online.
- The lease (LOAN) goes only once it has logged off, as `RosterService.unload` does.

**End conditions:**

| Event | Result |
| --- | --- |
| Time up | Return. It warns the friend at 5 minutes and at 1 minute left. |
| Owner's card "Call <name> back from <friend>" | Return. |
| Friend's card "Head back to <owner>" | Return. |
| Friend expels it (party window) | Return. |
| It must step out of the friend's party (fair share after a human joins, make-room, a merge) | It tries to rejoin the friend once the change settles. With no seat, it returns. |
| Friend leaves or is expelled from the party, or the party disbands | It re-forms around the friend (rejoins, a party made when needed). |
| Friend logs off, changes channel or enters the Cash Shop | Return. |
| Owner logs off | Return: it walks out, its owner is gone. |
| Owner releases it, or either character is deleted | Return first, then the release or deletion runs as before. |
| Kill switch turned off (config reload) | Every loan returns on the next 10 s sweep. |
| Restart or crash | Nothing to do: the row is BENCHED on disk and the Agent is gone. |

**Runs (recommended: decision D2 b):**
- When the friend's party goes into a PQ or boss through a desk, the borrowed companion steps out at Yes, as an extra
  does. It waits beside the entrance and rejoins when the friend comes back out to an open map.
- The desks list it as waiting ("on loan from Crewfriend: waits outside").
- It is never put in a lineup and never committed, so no run's rewards or commitments involve it.

### 3.3 Security rules (each with a test)
1. **No new `mayControl`.** `CompanionDirectory.mayControl(friend, companion)` stays false. So whispers stay refused
   (it answers the friend once a minute, in character, "I'm on loan from Crewfriend: wave me over if you need me"),
   and so do named transfer and dismiss, boss-run membership (AgentBossRunService:187) and lobby invites.
2. **No `mayLoad`.** Spawn, recruit, `!grantcompanion` and party pulls by the friend stay refused. The LOAN lease is
   not a COMPANION lease.
3. **Typed chat cannot reach it.** New `AgentClaimGuard.Rule.mayCommandByChat(actor, agentId)` (default true).
   - `CompanionDirectory` answers false for a companion on loan, unless the actor administers.
   - `AgentChatRouteCoordinator.handleChat` drops those entries from the speaker's list before any flow runs. That
     covers recruit, transfer, gather, formation, dismiss, targeted and untargeted lines.
   - Pending offers (respec, upgrade) are cleared by the re-registration.
4. **"May direct" is host-only.** `CompanionLoans.mayDirect(friend, characterId)` is true only for the live loan's
   borrower. It is used only by the borrowed card's orders (follow, wait, hunt here, head back) and the party router's
   re-seat.
5. **Owner-only surfaces stay owner-only.** Each one is keyed by the actor's own roster row (`roster(owner)`,
   `entry(owner, persona)`), so the friend has no row to act on. Tests pin the friend's refusal for:
   - the card's haul, wages, trade, gear, settings, duty, camp, swap, release and level tools;
   - the chat verbs (`CompanionChatVerbs`, `ResidentDialogue` hand over / send / bank).
6. **The owner cannot command it mid-loan either.** Their card offers only "Call back".
   - `mayControl(owner)` is false while the friend leads it: a new loan check alongside the bench-live one.
   - So no two humans give orders at once.
   - The owner's call, swap-in, duty change, camp move, release and Lakelis or boss bench calls are refused with the
     loan's line until it returns. All of them check the loan or fail on the LOAN lease.
7. **No duplication.**
   - The companion is one loaded character throughout. Nothing is copied or created: no item, meso or row.
   - The lease transfer is a single atomic replace.
   - Its bag and purse never move between the players.
   - The bench ledger and bench slices skip a persona on loan (no abstract EXP or haul while it is really out). The
     auditor skips it too.
8. **No way to keep it.**
   - The loan is in memory and bound to both sessions. Every exit (3.2 end conditions) returns it.
   - A 10 s sweep on companion-ops also returns any loan whose friend or owner is offline, whose time is up, or whose
     Agent is gone.
   - The friend can never release, dismiss, transfer or log it out.
9. **Ownership checks on every new path.** Every new path that lets a non-owner direct a companion gets a test that a
   third player, the owner's other friends and an administrator without the loan are refused:
   - lend;
   - accept;
   - the borrowed card's four orders;
   - the router's re-seat.

### 3.4 Stock UI
- **Owner's party card:** "Lend to a friend..." appears when 3.1 holds. It opens a picker of eligible friends on the
  map (names, "buddy" / "guild"), then the length (30 / 60 / 120 min), then a confirmation. The companion answers in
  its voice.
- **Friend's window:** a LivingTalk Yes/No with the companion's portrait: "Crewfriend would lend you Rolly for an hour.
  Rolly will follow you and fight alongside you, and keeps what Rolly's own kills drop. Take Rolly along?"
  - It opens only when no window is open on the friend's client (StockWindowGuard). Otherwise the owner hears "Ohboy
    looks busy right now."
- **Borrowed card** (the friend double-clicks it, ResidentDialogue routing):
  - Header: "Rolly, on loan from Crewfriend (42 min left)".
  - Options: Stick with me / Wait here / Hunt here while I'm busy / Head back to Crewfriend.
- **Owner's bench card while lent:** "out with Ohboy (42 min left)", plus "Call Rolly back from Ohboy". Other options
  are hidden while lent.
- The Crew window rows show "on loan to Ohboy" for the owner. The native Crew window gets the same text through
  CompanionView.
- **Party window:** the friend's expel sends it home. The friend's invite of a borrowed companion that stepped out
  re-seats it (`Relation.BorrowedCompanion` in the router). Anyone else's invite still gets stock 23.

## 4. Contract loot terms

### 4.1 Which contracts split
- `ContentLoader` keeps "absent" absent:
  - a missing `lootShare` parses to null, so no split (behaves as today);
  - a missing `ownDropsPercent` with `OWN_DROPS` takes the authored default 10.
- `Terms.splitsLoot()` is `lootShare != null`.
- **ContentLint errors:**
  - `ownDropsPercent` outside 0-100;
  - `ownDropsPercent` with `NONE` or `LEADER`;
  - `ownDropsPercent` without `lootShare`.
- **Which contract a companion has:** a persona's own (`ContractService.contractOf`), `hired-hand` for a `hire:<cid>`.
  A granted companion with no contract has no terms, so no split.
- Terms apply in every `ROSTER_CONTRACT_MODE`. They are not end rules, just as wages keep working in every mode.

### 4.2 The math
The cut is taken in meso: what the authored terms say the companion spends "on its own gear over time"
(LIVING_RPG_PLAN.md:681-682). For one companion, per meso pickup of `amount`, where `own` means its own kill:

```
upkeep  = min(amount, max(0, purseFloor - purseBefore))   purseFloor = COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL x level
rest    = amount - upkeep                                 purseBefore leaves out meso it owes and has not handed over
keptPct = OWN_DROPS: own ? p : 0     NONE: 0     LEADER: 0
owed    = floor(rest x (100 - keptPct) / 100 + carry)     carry = that sum's fraction, per companion, in memory
kept    = amount - owed
```

- **Items** it picks up are all its owner's share, with three exceptions it keeps whole:
  - its supplies: recovery potions and ammo (AgentUseItemClassificationPolicy, ids 206/207/233);
  - quest items (its own errands pick up keys and proofs);
  - equips. The haul ledger stores an item id and a count, so an equip moved through it would lose its rolled stats.
    An equip stays in its bag (it may wear it), and its owner can still ask for it with "Let's trade".
- **The carry** keeps the owner's share exact to the meso over many pickups. A restart loses only a fraction.
- **"Own kill":**
  - **Mob meso at the kill:** the top damager's own share (MapleMap :761-773, `pm == chr`).
  - **Floor items and floor meso:** `mapitem.getOwnerId() == companion.getId()`.
  - **Party meso shares** from anyone else's kill are not own.
  - The hook gains an `ownKill` flag at all seven call sites (MapleMap :767, :772; Character :2514, :2577, :2582,
    :2594, :2607). MesoLootHookBoundaryTest keeps pinning one hook per grant.
- **Upkeep first.** The purse floor is what it already spends on potions.
  - `OWN_DROPS 10` at Lv30: it keeps meso until it holds 30,000. Above that, it keeps 10% of its own-kill meso, and
    the rest goes to the owner's share.
  - `LEADER` depends on decision D4.

### 4.3 Where it applies
- **Out with its owner** (runtime leader = roster owner), **and on loan** (the share is still the owner's): a
  `LootTerms` tally on companion-ops records the owed amounts per companion from the `looted` hook. Every 60 s, and at
  unload, logout, loan return or a haul page open, the tally is settled:
  - `gainMeso(-min(owed, purse))` into `haulMeso`;
  - the owed item units are taken from the bag (`takeFromBag`, as BenchSessions.harvest does) into
    `living_haul_item`;
  - what it already used or sold of a share (a potion it drank) is forgiven, never charged.
  - So the physical item leaves the bot before the ledger shows it: never in both places.
- **Live bench slice (FARM_MESO, FARM_ITEM, GATHER, SELL):** `BenchSessions.harvest` already moves the whole harvest
  into the haul. With terms, it moves only the owed part of the meso; the cut stays in its purse. Items stay whole in
  the haul, as before: they are what its owner set it to farm. Bench drops are all its own kills, and bench upkeep is
  already paid from the haul (BenchLedger), so no purse floor applies there.
- **Offline ledger (BenchLedger.accrue):** credits only the owed part of FARM_MESO into the haul, and puts the cut in
  the companion's purse (`LivingStore.addCharacterMeso`; the companion is not loaded then). Farmed and gathered items
  go whole into the haul, as before.
- **TRAIN duty** keeps everything, as today (its own time).
- **Boss runs** keep their own rules: `leaveBossDrops`, and the boss drop owner reassignment (LivingWorldHooks
  .bossDropOwner).

### 4.4 Visible and fair
- **Card header:** "Terms: keeps 10% of its own drops" (or "keeps no cut" / "everything is yours").
- **"Your haul..."** shows "your share so far: 12,400 meso, 31 items" and the meso it kept as upkeep or cut.
- On a hand-over it says what it kept: "Kept my 10% of what I took down, 1,830 meso, as agreed."
- Every settle writes a `companion.loot_share` event: owed, kept, upkeep, and the terms.

## 5. Config (config.yaml `living_world:`)

| Key | Default | Meaning |
| --- | --- | --- |
| `COMPANION_LENDING_ENABLED` | true | Kill switch. Off: no new offers, and every loan returns on the next sweep. |
| `COMPANION_LOANS_PER_BORROWER` | 1 | Loans a friend may hold at once. |
| `COMPANION_LOANS_PER_OWNER` | 2 | Companions an owner may have out on loan at once. |
| `COMPANION_LOAN_MAX_MINUTES` | 120 | The longest length offered (30, 60, 120 ≤ max). |
| `COMPANION_LOAN_OFFER_MS` | 60000 | How long the friend's Yes/No stays open. |
| `COMPANION_LOOT_TERMS_ENABLED` | true | Kill switch. Off: no split anywhere (as today). |
| `COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL` | 1000 | The upkeep purse floor per level. |
| `COMPANION_LOOT_SETTLE_MS` | 60000 | How often a tally is settled into the haul. |

All keys are new, so no shipped default changes. LivingWorldConfig tests pin the parse, and
config.AgentConfigurationBoundaryTest is run.

## 6. Code plan

| Piece | Where |
| --- | --- |
| `CompanionLoans` (loan records, offers, hand-over, return, sweep, `mayDirect`, party and lifecycle reactions) | NEW server/living/roster/CompanionLoans.java |
| `LoanLines` (in-character lines) | NEW server/living/roster/LoanLines.java |
| `CompanionLeases.Kind.LOAN`, `transfer(id, from, to)` | CompanionLeases |
| `Rule.mayCommandByChat`, `AgentClaimGuard.mayCommandByChat` | AgentClaimGuard |
| Filter the speaker's entries | AgentChatRouteCoordinator.handleChat |
| `handOverAgent(agent, fromLeaderId, toLeader)` | AgentInteractionRuntime / AgentLifecycleCommandCoordinator |
| `Relation.BorrowedCompanion`; `mayControl` and `mayCommandByChat` loan checks; `isCompanionParty` counts a borrowed companion as its borrower's | CompanionDirectory (+ every Relation switch) |
| Owner-side gates: call, swap, delegate, assign, setCamp, release, bench calls, ledger, slices, auditor | RosterService, BenchLedger, BenchDutyDispatcher, CompanionAuditor |
| Loan-aware guest leave and joined guard | LiveCrew.guestLeaves, CompanionPartySync.joined |
| Loot policy and settings while on loan; portal-true follow for a borrower | CompanionSettingsApplier, CompanionFollowGuard, RosterService.ownerEnteredMap |
| PQ and boss desks: a borrowed companion waits outside | CompanionPartyQuestService.plan/prepareEntry, CompanionBossRuns |
| UI: lend picker, friend's window, borrowed card, owner's lent card, Crew rows | ui/CompanionCardScreens, ui/CompanionMenus, CompanionView |
| Hooks: logout, channel, CS, deleted, party, map entered, click | LivingWorldHooks, ResidentDialogue |
| `LootTerms` (terms lookup, split math, tally, settle) | NEW server/living/roster/LootTerms.java |
| `ownKill` on the looted hook | LivingWorldHooks, Character, MapleMap |
| Terms parse, presence and lint | ContentLoader, Content.Terms, ContentLint |
| Bench split | BenchSessions.harvest, BenchLedger.accrue |
| Haul page and card lines | ui/CompanionCardScreens, RosterService.handOver |

## 7. Tests
- **CompanionLoansTest** (fakes over LivingWorld, as CompanionDirectoryTest):
  - offer checks (buddy, guild, mutual-only, same map, channel, seat, limits);
  - accept rechecks;
  - the hand-over order and its rollback at each step;
  - every end condition;
  - the sweep;
  - the kill switch;
  - no row other than ACTIVE → BENCHED (and back on return) is written.
- **LoanOwnershipTest** (one per new path, rule 3.3.9), each refused for a third player, the owner's other buddy and
  an admin without the loan:
  - lend;
  - accept by anyone but the named friend;
  - borrowed-card orders;
  - router re-seat.
  - Also: the owner's commands refused mid-loan.
  - Also: owner-only surfaces refused to the friend (haul, wages, trade, gear, settings, duty, camp, swap, release,
    level, chat verbs).
- **ClaimGuardBoundaryTest:**
  - `mayCommandByChat` defaults to true with no rule;
  - chat ingress filters a lent Agent from the borrower's entries;
  - the whisper bridge still refuses the borrower.
  - **AgentClaimGuardTest:** the new seam.
- **CompanionLeasesTest:** `transfer` is atomic, refuses a wrong holder, and leaves no gap.
- **CompanionSlotsTest / CompanionPartySyncTest:**
  - the borrowed guest is charged to the friend;
  - it steps out first;
  - it rejoins after a merge;
  - it returns with no seat;
  - the borrower's leave or disband re-forms it;
  - `joined` does not pull a guest.
- **CompanionAuditorTest:** a lent row is neither unseated nor reported.
- **LootTermsTest:**
  - the split math (upkeep floor, OWN_DROPS own vs not own, NONE, LEADER, carry exactness over 1,000 pickups, a single
    equip);
  - supplies and quest items kept;
  - settling takes only what is in the bag;
  - no-terms → as today;
  - the kill switch.
- **ContentLoaderTest / ContentLint test:** absent vs authored, the default 10, the lint errors, and all 44 shipped
  contracts parse with their terms.
- **MesoLootHookBoundaryTest:** still one hook per grant, now with `ownKill`.
- **BenchSessions / BenchLedger tests:** the split of a harvest and an offline credit, and TRAIN unchanged.
- **UI tests** (CompanionCardScreensTest, CompanionMenusTest, CharacterClickRoutingTest, PartyInviteRoutingTest): the
  options and text above.
- **Before the full suite:** ClaimGuardBoundaryTest, config.AgentConfigurationBoundaryTest and the content lint,
  targeted.

## 8. Live checks (two clients; not run by this session)
Accounts: owner **Crewfriend** (the main test account) and friend **ohboy** (ohboyohboy, Lv15, Lith Harbor; password in
`MapleServer/tools/cua/test-accounts.local.txt`). Mind the rememberpw registry gotcha (BOT_MANAGEMENT_P0_RESULTS P1).
Make them buddies (or put both in one guild) and give Crewfriend one companion out with them. Put both on one map,
with ohboy partyless.

| # | Check | Pass when |
| --- | --- | --- |
| L1 | Crewfriend: card → "Lend to a friend..." → ohboy → 30 min | ohboy gets the Yes/No with the portrait. Crewfriend's client is unaffected. |
| L2 | ohboy: Yes | The companion walks over with no blink, joins ohboy's party (new party), greets in party chat, and leaves Crewfriend's party. Crewfriend's Crew window shows "on loan to ohboy". |
| L3 | ohboy walks two maps, fights | It follows through portals and fights, and it picks up only its own kills' drops. |
| L4 | ohboy whispers it "give me mesos", types "respec" in party chat | Nothing happens. One in-character whisper back. |
| L5 | ohboy double-clicks it | The borrowed card shows "on loan from Crewfriend (n min left)" with the four options. Wait here and Stick with me work. |
| L6 | ohboy expels it | It says goodbye and walks out. Crewfriend's bench card shows it home. Calling it works. |
| L7 | Lend again; Crewfriend: "Call back from ohboy" | It returns. Same map: it rejoins Crewfriend's party with no blink. |
| L8 | Lend again; ohboy logs off | It walks out. Crewfriend is told. |
| L9 | Lend again; Crewfriend logs off | It walks out. ohboy's party shows it gone. On Crewfriend's next login it is on the bench. |
| L10 | Lend, wait out 30 min (or `COMPANION_LOAN_MAX_MINUTES` lowered on a test box) | It warns at 5 min and 1 min, then returns. |
| L11 | Loot terms: Crewfriend grinds 10 minutes with a contracted companion, opens "Your haul..." | Its meso and ETC share is in the haul. The card shows the terms. Hand it over: the owner gets the share, and it says what it kept. |
| L12 | A third player (not a buddy) is on the map | Crewfriend's picker does not list them. Their invite of the borrowed companion gets stock 23. |
| L13 | ohboy at Lakelis with the borrowed companion (Lv21+ setup) | It waits outside, listed as on loan, and rejoins after the run. |

Run `/runtime/companions/audit` after each step: 0 drift.

## 9. Decisions for the user

**Taken 2026-10-25:** the user chose the recommendation on all four: D1 (a) in person, D2 (b) waits outside, D3 (a)
into the haul, D4 (a) NONE and LEADER the same for now.

- **D1, where a loan can start.** (a) In person: all three on one map, and it walks over. (b) Anywhere on the channel:
  it travels to the friend with the call ETA. **Recommended: (a)**. It hands over with no load and no travel path for
  a non-owner. (b) can follow.
- **D2, runs during a loan.** (a) The loan ends when the friend's party goes into a run. (b) It waits outside and
  rejoins after. (c) It goes in as the friend's crew, which needs per-desk work for lineups, commitments and rewards.
  **Recommended: (b).**
- **D3, where the owner's share goes.** (a) Into the companion's haul: the owner collects with hand over, Duey or bank,
  as bench loot. (b) Straight to the owner at the pickup: meso to the purse, items to the bags when there is room.
  **Recommended: (a)**. It uses the existing collection, never teleports items, and stays fair when the owner's bags
  are full.
- **D4, NONE vs LEADER.** (a) Both keep only the upkeep purse and no cut, so they differ only in name for now. (b)
  LEADER keeps not even the purse, and its owner supplies it (it asks when short). **Recommended: (a)**. (b) risks the
  supply-trip loops fixed on 10-24 for a companion with an empty purse.

## 10. Out of scope
- Lending across channels or maps (D1 b).
- Borrowed companions inside runs (D2 c).
- Lending a seat rather than a companion (BOT_MANAGEMENT_PLAN decision 14, P9).
- Item-level loot preferences beyond the terms.
- Retro-splitting loot a companion already holds when this ships.

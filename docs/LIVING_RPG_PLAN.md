# Living RPG plan: resident bots, quest lines, character arcs

Status: proposal, 2026-09-20. FriendServer-specific unless a section says "upstream".

## 0. Goal and shape

Turn the bot population into a living cast. Every bot is a **Resident**: it lives somewhere,
has a routine, talks in its own voice, and carries at least one quest line. Some Residents
are also **Adventurers**: under a condition the player can satisfy (meso, gear, kills, a quest
chain, a secret learned from another Resident, a rare item) they join the player's party and
fight alongside them using the existing companion runtime, then go home when dismissed or
when their contract ends. Not every Resident can be recruited; many are quest-line only.

Everything a Resident does on its own is scripted and free. Only what a *player says* to a
Resident in free-form text costs money (TypeSafe Jev), and that is bounded per player, not
per bot. Population size is therefore a CPU and content question, not an API-cost question.

Non-goals for this plan: generative dialogue (Jev cannot generate text and we do not want an
LLM writing lore live), PvP between bots and players, bots trading real player items outside
the existing trade capability, and changing the game client beyond WZ data patches.

## 1. What already exists (build on it, do not duplicate it)

| Need | Existing piece | Where |
|---|---|---|
| Ambient town population, activities (REST, SOCIALIZE, STROLL, BROWSE, SHOW_OFF), per-town pools, unmaterialized standby | TownLife | `server.agents.capabilities.townlife.*`, `resources/agents/town-life/{lith-harbor,henesys,kerning-city,perion,ellinia,nautilus,sleepywood}.json`, `ambient-deployment.json` (pool 28, 50% active), `docs/agents/TOWN_LIFE_ARCHITECTURE.md` |
| Keeping a named roster of bots alive | Population runtime | `server.agents.population.*`, `!agentpop on/add/remove/crew/multiplier`, store `.runtime/agents/population.json` |
| Voice and temperament | Personality + dialogue style | `resources/agents/profiles/personality-profiles.json` (activity, patience, expressiveness, curiosity, sociability, riskTolerance, routinePreference), `resources/agents/social/dialogue-style-profiles.json`, `generic-dialogue-catalog.json` (intent -> lines per style) |
| Who this bot knows and how it feels about them | Social memory | `server.agents.social.memory.SocialRelationshipMemory` (familiarity, trust, affinity, annoyance, interactionCount, summary) per counterparty |
| Scripted multi-step goals with fact-based gates | Plan cards | `resources/agents/plans/*.plan.json` (`entryCriteria`/`exitCriteria` on facts like `character.level`, `quest.2336`), `server.agents.plans.*` step executors |
| Bots doing real game quests, NPC interaction, reward choice | Quest capability | `server.agents.capabilities.quest.*`, `capabilities.npc.*`, `progression.questcatalog.*`, `resources/agents/catalogs/victoria-quest-facts.json` |
| Companion control: follow, grind, farm, stop, formation, supplies, buffs, trade, PQ | Companion runtime | `AgentChatOrchestrator` flows, `AgentMovementRuntime`, party quests |
| Recruit / dismiss / join party | Command services | `AgentRecruitCommandService` (`recruit|adopt|hire|claim <name>`), `AgentDismissCommandService`, `AgentPartyLifecycleService.joinAgentToLeaderParty`, `AgentLifecycleCommandCoordinator.recruitAgent` |
| Who may command bots | Authority | `AgentAuthorityService` (name allowlists: administrator/operator/observer) |
| Free-form chat -> canonical command, cost meter, projection | TypeSafe Jev | `capabilities.dialogue.jev.*`, `integration.typesafe.*`, `!typesafe`, `tools/typesafe-cost.bat`, `docs/agents/AGENT_TYPESAFE_JEV_JUDGMENTS.md` |
| Equipment and level bands | Field data + autoequip | `resources/agents/field/victoria-level0-25-equipment.json`, `AgentActiveModeRuntime.autoEquip...` |
| Merchant bots, FM stalls, price/rarity economy | SoloMapling + economy engine | `server.bots.*`, `scripts/config/bot-*.yml`, `bot_*` tables |
| WZ editing and client patching | MapleEdit + patch server | `MapleEdit/` (WZ browser, deploy), `patch-server/serve.py` |
| Audit trail of what a bot did and why | Journey | `server.agents.journey.*`, `logs/`, `resources/agents/journey/*.schema.json` |

Two things are *not* there and are the heart of this plan: bots as **quest givers to players**
(upstream's "NPC quest capability" is bots doing quests, not giving them), and **ordinary
players gaining command of a bot** (authority is a GM name allowlist today).

## 2. Vocabulary

- **Persona** - the authored identity of one bot: name, appearance, loadout, strength tier,
  personality/style ids, home town and routine, relationships, quest lines, arc, contract.
- **Resident** - the passive runtime state. Lives in its home town (TownLife), follows a
  routine, talks when spoken to, offers and grades quests, performs scripted dialogues with
  other Residents.
- **Adventurer** - the active runtime state. Registered as a companion of one player (leader),
  in the leader's party, obeying the existing companion commands.
- **Quest line** - an ordered set of quests one Persona offers. Short (1-2), medium (3-5),
  long (6-10 steps). Steps are deterministic game facts.
- **Arc** - the Persona's own story: chapters that change what it says, where it stands, what
  it offers, and whether it can be recruited. Arcs advance on world flags, calendar, or player
  actions.
- **Bond** - the per-(player, persona) relationship record: stage, points, flags learned,
  quests done, contract state. Built on `SocialRelationshipMemory`.
- **Contract** - the recruitment terms: what unlocks the offer, what the player pays or
  proves, how long the Adventurer stays, what ends it.
- **Rumor** - a scripted Resident-to-Resident dialogue (the "hidden dialogue") performed in
  public at a scheduled time and place; hearing it sets a flag on nearby players' bonds.

## 3. Lifecycle

```text
                 +------------------------------------------------------------+
                 |                        RESIDENT                            |
  spawn/roster ->|  routine in home town (TownLife activities + custom spots)  |
                 |  talks to players, offers quests, performs rumors           |
                 +-----------+----------------------------+-------------------+
                             | bond stage reaches          | contract fulfilled
                             | "trusted" and persona       | (pay/prove) and
                             | has a contract              | player says join
                             v                            v
                    +----------------+           +------------------------+
                    | OFFER          |  yes ---> | ADVENTURER             |
                    | states terms   |           | companion of leader,   |
                    | (once per day) |  no/timeout| in party, follows/    |
                    +----------------+  -> RESIDENT| grinds/farms/PQ      |
                                                  +-----+------------+----+
                                                        | dismiss,   | contract end,
                                                        | leader     | leader offline
                                                        | offline    | > N min, death
                                                        v            | rule, betrayal
                                                  +------------------v-----+
                                                  | RETURNING              |
                                                  | walks/teleports home,  |
                                                  | bond updated (memory)  |
                                                  +-----------+------------+
                                                              v
                                                           RESIDENT
```

Quest-only Personas never leave RESIDENT. Persistence: persona state, bonds, ledgers and
world flags live in MySQL (Liquibase changesets), not in `.runtime` files, because they must
survive restarts and be visible to MapleEdit.

## 4. Content model (data-driven, authored as YAML)

All content under `scripts/config/living-world/` (same convention as `set-items.yml`), loaded
at boot and by `!world reload`, validated by a CLI (`tools/living-world-lint.bat`) that runs the
same checks as the loader plus cross-references (map ids exist, item ids exist, mob ids exist,
referenced personas exist, no dangling flags).

### 4.1 Persona

```yaml
# scripts/config/living-world/personas/perion/garrick.yaml
id: garrick
name: Garrick            # character name (the bot account is created on first spawn, like !spawnbot)
title: "Deserter of Perion"
job: WARRIOR             # Cosmic job id or name; level band below
level: { min: 28, max: 32 }
strength: VETERAN        # ROOKIE | SEASONED | VETERAN | ELITE -> equipment tier + stat rolls
loadout: perion-warrior-l30   # key into living-world/loadouts.yaml (equip ids, potions)
personality: efficient-v1     # existing personality profile id
dialogueStyle: quiet-practical-v1
home:
  town: perion               # existing town-life profileId
  routine:                   # slots by server hour; spots are town-profile venue/hotspot ids or x,y
    - { from: 6,  to: 11, spot: "venue:weapon-shop-front", activity: LINGER }
    - { from: 11, to: 18, spot: "hotspot:cliff-ledge",      activity: SHOW_OFF }
    - { from: 18, to: 24, spot: "rest:campfire-2",          activity: REST }
    - { from: 0,  to: 6,  materialize: false }              # unmaterialized: no CPU
recruitable: true
contract: garrick-blood-debt      # living-world/contracts/*.yaml
questLines: [garrick-lost-badge, garrick-boar-cull]
arc: garrick-redemption
relationships:
  - { persona: dances-with-balrog-priest, kind: RESENTS }
  - { persona: perion-smith-yona,        kind: OWES }
rumors: [perion-campfire-confession]
dialogue:                        # deterministic lines by intent; style variants optional
  greet: ["what.", "you again.", "make it quick"]
  idle:  ["(sharpens a blade)", "cold up here."]
  questOffer.garrick-lost-badge: ["lost my unit badge near the boars. bring it and we talk."]
  refuse: ["not interested."]
```

### 4.2 Quest line

```yaml
# scripts/config/living-world/quests/perion/garrick-lost-badge.yaml
id: garrick-lost-badge
persona: garrick
length: SHORT
repeatable: false
requires:
  level: { min: 20 }
  bondStage: { min: MET }            # MET < ACQUAINTED < TRUSTED < SWORN
  flags: []                          # world/bond flags that must be set
  notFlags: [garrick.badge.returned]
steps:
  - id: talk
    type: TALK                       # player addresses the persona; any phrasing -> deterministic first, Jev on miss
    say: ["lost my unit badge near the boars. bring it and we talk."]
  - id: collect
    type: COLLECT_ITEM
    itemId: 4031013                  # a badge (custom drop rule below)
    count: 1
    dropFrom: { mobIds: [2220000], chance: 0.15 }   # extra drop registered only while step active
  - id: return
    type: DELIVER
    to: garrick
    take: { itemId: 4031013, count: 1 }
    say: ["...huh. didn't think you'd bother."]
rewards:
  meso: 8000
  exp: 1200
  items: [{ itemId: 2000002, count: 20 }]
  bondPoints: 25
  flags: [garrick.badge.returned]
onComplete:
  arcAdvance: { arc: garrick-redemption, to: chapter-2 }
```

Step types (all deterministic, all fed by existing server events):
`TALK`, `KILL_MOBS(mobIds,count,mapIds?)`, `COLLECT_ITEM`, `DELIVER(to persona|npc)`,
`VISIT_MAP`, `TALK_TO(persona)` (cross-persona, unlocks hidden lines),
`HEAR_RUMOR(rumorId)`, `ESCORT(persona, fromMap, toMap)` (persona follows player as a
temporary Adventurer with no contract), `PAY(meso)`, `GIVE_ITEM`, `REACH_LEVEL`, `WAIT(hours)`,
`PARTY_QUEST_CLEAR(kpq|lpq|...)`, `KILL_BOSS`. Each step type maps to one listener in the
ledger runtime (section 5.3).

### 4.3 Contract

```yaml
# scripts/config/living-world/contracts/garrick-blood-debt.yaml
id: garrick-blood-debt
offerWhen:
  bondStage: TRUSTED
  flags: [garrick.badge.returned, garrick.heard-confession]
  arcChapterAtLeast: { arc: garrick-redemption, chapter: chapter-3 }
price:                              # any subset; ALL listed must be met unless `anyOf`
  meso: 250000
  items: [{ itemId: 1302020, count: 1, keep: true }]   # keep: true -> he equips it
  proof: { killMobs: { mobIds: [3210800], count: 30 } } # counted from offer time
terms:
  durationHours: 6                  # 0 = until dismissed
  maxLevelGap: 15                   # leaves if leader outlevels him by more
  lootShare: OWN_DROPS              # OWN_DROPS | NONE | LEADER
  leavesOn: [LEADER_OFFLINE_20M, DISMISS, LEADER_DEATH_3X, CONTRACT_END]
  cooldownHoursAfterLeave: 12
  rejoinPrice: { meso: 50000 }      # cheaper second time
```

Price variants the user asked for, all expressible: meso only; equipment (kept or just
shown); kill count; a quest chain (`offerWhen.flags` set by the chain's last quest); hidden
dialogue (`HEAR_RUMOR` flag); special item (`items` with `keep: false` = proof of ownership).

### 4.4 Arc

```yaml
# scripts/config/living-world/arcs/garrick-redemption.yaml
id: garrick-redemption
persona: garrick
chapters:
  - id: chapter-1
    title: "The cliff"
    dialogueOverrides: { greet: ["what.", "make it quick"] }
  - id: chapter-2
    title: "A badge returned"
    enterWhen: { flags: [garrick.badge.returned] }       # per player? no: arcs are WORLD state,
    dialogueOverrides: { greet: ["you again. still here."] } # first player to trigger advances it
    routineOverride: [{ from: 6, to: 24, spot: "venue:weapon-shop-front", activity: LINGER }]
    unlocks: { questLines: [garrick-boar-cull], rumors: [perion-campfire-confession] }
  - id: chapter-3
    title: "Confession"
    enterWhen: { rumorPerformed: perion-campfire-confession, times: 1 }
    unlocks: { contract: garrick-blood-debt }
  - id: chapter-4
    title: "Gone hunting"
    enterWhen: { adventurerHoursTotal: 20 }              # he has fought alongside players 20 h total
    recruitable: false                                   # retires; new persona may take the spot
    dialogueOverrides: { greet: ["done fighting. go on."] }
```

Arcs are world-level (shared by all players) so the town visibly changes; bonds are per
player. A per-player flavour is achieved by bond flags, not by forking the arc.

### 4.5 Rumor (hidden dialogue)

```yaml
# scripts/config/living-world/rumors/perion-campfire-confession.yaml
id: perion-campfire-confession
participants: [garrick, perion-smith-yona]
where: { town: perion, spot: "rest:campfire-2" }
when: { serverHours: [21, 22], everyDays: 1, requiresArc: { arc: garrick-redemption, chapterAtLeast: chapter-2 } }
lines:                         # performed as MAP chat with 2-5 s pacing, personas walk to the spot first
  - { by: perion-smith-yona, text: "you never told them why you left the guard." }
  - { by: garrick,           text: "and i won't. not until someone earns it." }
  - { by: perion-smith-yona, text: "the boar badge. that's how you'd know." }
setsFlagOnListeners: garrick.heard-confession     # players within chat range at the time
```

## 5. Runtime architecture

### 5.1 Placement and routine (Resident mode)

- New `server.agents.capabilities.resident` (upstream-shaped, FriendServer content):
  `AgentResidentRuntime` owns one Resident session per Persona: materialize in the home town
  through the TownLife admission API with a caller-owned `requestId`, choose the routine slot
  for the current server hour, request the matching activity at the named spot, re-plan on
  slot change. Slots with `materialize: false` release the session (TownLife `UNMATERIALIZED`
  standby already exists) so a sleeping town costs nothing.
- Population: Personas are added to the population roster automatically at boot (`!agentpop`
  roster entries tagged `resident:<personaId>`) so the reconciler keeps them alive; the
  ambient pool (`ambient-deployment.json`) stays for nameless filler.
- Presence LOD: when a town has no players for `RESIDENT_SLEEP_AFTER_MS` (say 5 min), all its
  Residents go to standby regardless of routine; the first player entering wakes them in
  their current slot spot (looks like they were always there). This alone keeps 60+ Residents
  cheap on the central scheduler.
- Rumors: `AgentRumorScheduler` (TimerManager, low priority) picks due rumors, checks the arc
  gate and that at least one player is in the map (no audience, no performance), moves the
  participants to the spot via the movement capability, then plays lines with pacing through
  the dialogue transport (`MAP` channel). Listeners are resolved once at the last line.

### 5.2 Talking to a Resident

Chat from a *non-owner* player currently goes to the social small-talk path. Add a routing
layer in `AgentChatRouteCoordinator` for Residents:

1. **Deterministic first** (`AgentResidentDialogueClassifier`): greetings, `quest`/`work`/
   `job`, `accept`, `done`/`here`/`finished`, `join`/`come with me`/`hire`, `bye`, `who are
   you`, persona-specific keywords declared in the quest YAML (`say` lines are also matched as
   acceptance phrases), item hand-over via the existing trade window.
2. **Jev on a miss** (`AgentResidentIntentJudge`, same pattern as `AgentChatIntentJudge`):
   one Choice question over the small closed set {ASK_QUEST, ACCEPT, TURN_IN, ASK_JOIN,
   ASK_ABOUT(persona/rumor topic), GOODBYE, SMALL_TALK, NOT_FOR_ME}, plus `addressed_to_bot`.
   State: the Persona's current chapter summary, offered quest titles, bond stage, last two
   turns. Budget ~1.2k tokens (much smaller than the companion command state; no bot combat
   status needed). ACT >= 0.75, ASK 0.45-0.75 ("you asking about the badge?"), else scripted
   `idle` line. SHADOW first, like everything else.
3. **Replies** go through `AgentDialogueTransportRuntime` (now enabled) as MAP chat bubbles;
   long quest text uses a whisper to the player so the town is not spammed. Optional later:
   open a real NPC dialogue window using a proxy NPC id per Persona portrait
   (`NPCConversationManager` with the Resident's lines) - the client renders any NPC id we
   send, so a small set of "portrait" NPCs gives quest text the familiar UI without client
   changes.

### 5.3 Quest ledger (players' progress on Persona quests)

New `server.living.quest` (FriendServer package, server-side, no WZ dependency):

- Tables (Liquibase `029-living-world.sql`): `living_persona_state` (personaId, arcChapter,
  worldFlags json, updatedAt), `living_bond` (characterId, personaId, stage, points, flags
  json, contractState, adventurerSeconds, lastSeenAt), `living_quest_state` (characterId,
  questId, stepIndex, progress json, startedAt, completedAt, repeats),
  `living_event_log` (append-only audit: who advanced what and why).
- `LivingQuestLedger` subscribes to existing server events: mob kills (`Character`/`MapleMap`
  kill hooks where quest kill credit already happens), item gain/loss (inventory listeners),
  map enter (`changeMap`), party quest clears, boss kills, trade completion, chat (TALK /
  TALK_TO / DELIVER via the Resident dialogue layer), rumor performance (HEAR_RUMOR).
  Each event is matched against active steps of that player only (indexed by type), so cost
  is O(active steps of online players).
- Rewards through existing APIs (`gainMeso`, `gainExp`, `InventoryManipulator.addById`,
  fame). Custom temporary drops (`dropFrom`) are added as a per-step drop overlay checked in
  the drop path only while the step is active for a player in the map.
- Client visibility, phase 1: chat + whisper summaries and `@quests` (player command) listing
  active Persona quests and steps. Phase 2: allocate a custom quest id range (e.g. 60000+) and
  ship `Quest.wz` entries (name, description, step text) through MapleEdit's WZ writer and the
  patch server, then mirror ledger state into `QuestStatus` so the in-game quest log shows
  them. `Quest.getInstance(id)` already tolerates ids without requirement data, so the
  server side is safe; the WZ patch is what makes the client render names.

### 5.4 Bonds, contracts and authority

- Bond stages from points: MET (talked once) -> ACQUAINTED (25) -> TRUSTED (75) -> SWORN
  (150). Points come from quest rewards (`bondPoints`), gifts (declared accepted gift items
  per persona), fighting together (per Adventurer hour), and decay slowly for annoyance
  (spamming, dismissing mid-contract). Stored in `living_bond`, mirrored into
  `SocialRelationshipMemory` (familiarity/trust/affinity) so the existing small-talk style
  already warms up.
- Offer: when `offerWhen` holds and the player says `join` (or Jev ASK_JOIN), the Resident
  states the price in its voice from a template ("250k and that sword, and thirty of the
  fire boars dead first") and opens a `ContractNegotiation` with a 10-minute window: meso via
  the existing trade window, items likewise (`keep: true` items are equipped through
  autoequip), kill proofs counted by the ledger. Haggling is deterministic (fixed
  `rejoinPrice`, bond-stage discounts); a Jev `offer-reply` judgment already exists in the
  trade capability if we want free-form accept/decline reading.
- Authority: extend `AgentAuthorityService` with **bond grants**: `mayOperate(actor, agent)`
  becomes true when `living_bond.contractState = ACTIVE` for (actor, agent). Existing name
  allowlists stay for GMs. All companion command paths already take the leader; the route
  coordinator's `mayOperate(leader)` check becomes per-agent. This is the single upstream-
  facing change with the widest blast radius; do it first and cover it with tests.
- Becoming an Adventurer reuses `AgentLifecycleCommandCoordinator.recruitAgent` +
  `AgentPartyLifecycleService.joinAgentToLeaderParty` on the *same* character (the Resident
  session is drained through TownLife's graceful exit first). Leaving reverses it and starts a
  `RETURNING` objective: walk to a taxi/portal if on Victoria, otherwise teleport home after
  30 s out of the player's sight, then re-admit to TownLife.

### 5.5 Strength and equipment

- `strength` tiers map to level offsets and a loadout key. Loadouts are lists of equip ids
  with optional stat-roll rules (reuse SoloMapling's `bot-price-classes`/rarity rollers for
  stat variance) in `living-world/loadouts.yaml`. Applied on first spawn and re-applied when
  an arc chapter changes the loadout (a Persona can visibly gear up over its arc).
- Adventurer level follows the Persona band, not the player; `maxLevelGap` ends contracts
  cleanly instead of scaling stats. Upstream's progression plans can be attached to
  Adventurers later so they level from real fights (they already gain EXP in combat).

### 5.6 Tooling

- `tools/living-world-lint.bat`: schema + cross-reference validation, prints a content
  census (personas per town, quests by length, recruitables, flags never set/never read).
- `!world` GM command: `reload`, `status <persona>`, `flag set|clear`, `arc <persona> <chapter>`,
  `bond <player> <persona> <stage>`, `rumor play <id>`, `spawn <persona>`, `sleep|wake <town>`.
- `@quests`, `@bond <persona>` player commands.
- Journey events for every ledger transition so `logs/` explains any dispute.
- MapleEdit page later: browse personas/quests/arcs, edit YAML with validation, deploy.

## 6. TypeSafe (Jev) usage and cost policy

Rule (from `AGENT_TYPESAFE_JEV_JUDGMENTS.md`): code owns rules and state; Jev answers one
typed question on deterministic misses; every judgment is metered by kind.

| Interaction | Mechanism | Jev kind | Tokens/req |
|---|---|---|---|
| Resident routines, rumors, arcs, quest grading, rewards | deterministic | none | 0 |
| Player says an exact phrase (`quest`, `accept`, `join`, `follow me`) | regex | none | 0 |
| Player free-form to a Resident ("got anything for me?") | miss -> Choice | `resident-intent` (new) | ~1.2k |
| Player free-form command to an Adventurer | existing | `chat-intent` | ~3.5k |
| Free-form accept/decline of a contract price | existing judge | `offer-reply` | ~0.6k |
| Player names things / reports | existing | `name-screen`, `report-triage` | ~0.5-0.75k |
| Which Resident should perform a rumor for these players (optional) | Director ranking | `director-select` | ~0.7k |

Guardrails (all config, all already metered by `JevUsageMeter`):
- per-player daily judgment budget (`RESIDENT_JEV_PER_PLAYER_PER_DAY`, default 60) -> after
  that, Residents only understand exact phrases and say so ("speak plainly, friend");
- per-Persona cooldown of 3 s between judgments; ASK results are cached per (player, persona,
  normalized message) for 10 minutes so repeating a phrase is free;
- `resident-intent` state capped at 1.5k tokens (no combat status, two prior turns);
- circuit breaker and `!typesafe` unchanged; a new `[typesafe-usage]` line per kind already
  lands in `logs/typesafe-usage.csv`.

Cost model (measured 2026-09-20 prices: $0.042 per million input tokens, output free):

| Players/day | Free-form lines per player/day | Resident (1.2k) | Adventurer cmds (3.5k, 25% misses of 40) | $/day | $/month |
|---|---|---|---|---|---|
| 5 | 30 | 180k tok | 175k tok | $0.015 | $0.45 |
| 20 | 30 | 720k | 700k | $0.06 | $1.79 |
| 50 | 40 | 2.4M | 1.75M | $0.17 | $5.23 |
| 200 | 40 | 9.6M | 7.0M | $0.70 | $20.9 |

Bot count does not appear in the table: 60 or 600 Residents cost the same API money. Run
`tools\typesafe-cost.bat --bots <adventurers> --commands-per-bot-hour 6 --hours-online 12 --live`
after adding the `resident-intent` sample to `JevRequestSamples` to refresh the numbers.

## 7. Population sizing (CPU and content, not API)

Recommended first world (Victoria Island, the 7 towns with TownLife profiles):

| Town | Residents | Recruitable | Story arcs | Notes |
|---|---|---|---|---|
| Lith Harbor | 8 | 2 | 1 | newcomers' quests (L8-15), a sailor who joins for meso |
| Henesys | 10 | 3 | 2 | hub; hunter joins after a kill proof; merchant with long chain |
| Ellinia | 8 | 2 | 2 | archivist: long chain + hidden dialogue -> mage joins |
| Perion | 8 | 2 | 1 | Garrick example above; smith quest-only |
| Kerning City | 9 | 3 | 2 | courier: short chain -> thief joins for meso; fence with rumors |
| Nautilus | 6 | 1 | 1 | pirate deserter joins for a special item |
| Sleepywood | 5 | 2 | 1 | dungeon guide; escort quest |
| **Total** | **54** | **15** | **10** | plus ~28 nameless ambient pool already configured |

Content volume that implies: 54 Personas, ~110 quests (each Persona 1-3; lengths 60% short,
30% medium, 10% long), 10 arcs of 3-5 chapters (~40 chapters), ~20 rumors, 15 contracts,
~15 loadouts. Author in three waves (Lith+Henesys, then Ellinia+Perion+Kerning, then
Nautilus+Sleepywood); each wave is shippable.

CPU: upstream validated 100-agent cohorts on the central scheduler
(`MAPLE_ISLAND_REALISM_AND_100_AGENT_VALIDATION_PLAN.md`); Residents in standby cost nothing,
and at most one town is usually awake per player. Watch `Scale health` lines and
`!agentscheduler status`; the tick-time warning already fires at 500 ms.

## 8. World-building guide (how content gets written)

- One theme per town, one tension per arc, one secret per rumor. Themes: Lith (arrivals and
  debts), Henesys (hunters and the guild that failed), Ellinia (what the archive hides),
  Perion (the guard that deserted), Kerning (couriers, fences, the sewer), Nautilus (a crew
  that lost its captain), Sleepywood (the dungeon's toll).
- Voice comes from `dialogueStyle` + 6-10 handwritten lines per Persona per intent; the
  generic catalog fills gaps. Keep lines short (chat bubble width), lowercase for casual
  styles, no lore dumps in bubbles - long text goes to whispers or the NPC window.
- Recruitable Personas should be *earned* by different currencies so players with different
  play styles get different companions: meso (grinders), gear (collectors), kill proofs
  (fighters), quest chains (explorers), rumors (social players), special items (bosses/PQ).
- Every long chain must pay out at chapter boundaries (bond points + items) so an abandoned
  chain still felt worth it.
- Authoring is Claude-assisted from these schemas and per-town briefs; Jev is not used for
  writing. The lint tool is the gate, and every Persona ships with a smoke script
  (`tests/living-world/<persona>.smoke.yaml`: talk, accept, fake the step events, complete,
  join, dismiss) run by an integration test against an embedded map.

## 9. Roadmap

**P0 - Groundwork (1 week)**
- Bond-scoped authority in `AgentAuthorityService` + tests; route coordinator uses it.
- `living_*` Liquibase tables; `LivingWorldConfig` loader + lint tool with the schemas above.
- `AgentResidentRuntime` skeleton: spawn a Persona into a town, follow routine slots,
  standby when no players. `!world spawn/status/sleep/wake`.
- Verify: 8 Residents in Lith Harbor for 2 hours, no scheduler warnings, standby when empty.

**P1 - Quests in one town (1-2 weeks)**
- Resident dialogue classifier + `resident-intent` judge in SHADOW; whisper transport for
  quest text; `@quests`.
- Ledger listeners: TALK, KILL_MOBS, COLLECT_ITEM, DELIVER, VISIT_MAP; rewards; audit log.
- Content wave 1 (Lith Harbor + Henesys: 18 Personas, ~35 quests).
- Verify: smoke scripts pass; a new character can finish two chains end to end; shadow log
  of `resident-intent` reviewed and thresholds set; go LIVE.

**P2 - Recruitment (1-2 weeks)**
- Contracts: offer flow, price collection via trade window, kill proofs, join through
  `recruitAgent` + party join, leave/return objective, cooldown and rejoin price.
- Loadouts and strength tiers; arc-driven loadout changes.
- Verify: Garrick-style contract (meso + kept item + kill proof) joins, fights, leaves on
  `LEADER_OFFLINE_20M`, returns to Perion, bond memory persists across restart.

**P3 - Arcs and rumors (1 week + content)**
- World-level arc state, chapter transitions, dialogue/routine/unlock overrides.
- Rumor scheduler with audience check and `HEAR_RUMOR` flags.
- Content wave 2 (Ellinia, Perion, Kerning).

**P4 - Scale and polish (ongoing)**
- Content wave 3 (Nautilus, Sleepywood); Quest.wz patch for the in-game quest log; proxy-NPC
  dialogue window; MapleEdit living-world page; optional Director ranking for rumors.
- Upstream: propose the generic parts (resident mode on TownLife, bond-scoped authority,
  quest ledger interfaces, `resident-intent` judge) to cosmic_agents as a follow-up to
  `feat/typesafe-jev`; keep Victoria content FriendServer-only.

## 10. Risks and open questions

- **Bot chat is the only UI.** Until the proxy-NPC window or Quest.wz patch lands, quest text
  lives in bubbles and whispers; keep steps simple and legible.
- **Party rules.** Bots joining parties interacts with party quests and EXP split; the
  companion runtime already handles bots in parties, but contracts should start outside PQ
  maps and `PARTY_QUEST_CLEAR` steps must use the existing autonomous PQ flows.
- **Griefing.** Players can spam a Resident; the per-player Jev budget and annoyance decay
  cover cost and tone, but rumors need an audience check so nobody can farm flags by camping.
- **World arcs are shared.** The first player to trigger a chapter changes the town for
  everyone; decide per arc whether that is the point (recommended) or whether the chapter
  should require N distinct players.
- **Naming collisions.** Persona names occupy real character names; reserve them (name screen
  already exists) and create backing accounts like `!spawnbot` does.
- **Restart behaviour.** Residents respawn from the roster; Adventurers mid-contract should be
  re-registered to their leader on login if the contract is still active, otherwise the
  contract ends with a refund rule (`refundOnServerRestart: PARTIAL`).

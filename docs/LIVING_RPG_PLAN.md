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
- **Roster** - the set of Personas a player has recruited (section 11). At most 5 are
  **active** (in the party, the 6-member cap minus humans); the rest are **benched** at a
  **camp** (home town or a training map) where they keep levelling.

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
                             v                             v
                    +----------------+           +---------------------------+
                    | OFFER          |  yes ---> | ROSTER (owned by player)  |
                    | states terms   |           |                           |
                    | (once per day) |  no/      |  ACTIVE  <--call/bench--> BENCHED
                    +----------------+  timeout  |  in party, follows/       at camp: trains
                                       -> RESIDENT| grinds/farms/PQ          (ledger / abstract)
                                                  +------+--------------+----+
                                                         | release,     | contract end,
                                                         | dismiss      | bench tolerance
                                                         |              | exceeded, owner
                                                         v              | gone, betrayal
                                                  +-------------------v----+
                                                  | RETURNING              |
                                                  | walks/teleports home,  |
                                                  | bond updated (memory)  |
                                                  +-----------+------------+
                                                              v
                                                           RESIDENT
```

Quest-only Personas never leave RESIDENT. Persistence: persona state, bonds, roster, ledgers
and world flags live in MySQL (Liquibase changesets), not in `.runtime` files, because they
must survive restarts and be visible to MapleEdit.

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
temporary Adventurer with no contract), `HUNT_WITH(persona, mobIds, count)` (kills count only
while that Persona is active in the party - the quest giver fighting beside you),
`PAY(meso)`, `GIVE_ITEM`, `REACH_LEVEL`, `WAIT(hours)`, `PARTY_QUEST_CLEAR(kpq|lpq|...)`,
`KILL_BOSS`. Any kill/collect step also accepts `with: <persona>` as a presence requirement.
Each step type maps to one listener in the ledger runtime (section 5.3).

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
  lootShare: OWN_DROPS              # OWN_DROPS (keeps a cut, default 10%) | NONE | LEADER; also splits the bench haul (11.5)
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
- **No bench self-service.** A Persona never works its own quests *from the bench*: the
  offline ledger and background sessions of Persona X credit nothing to X's quests; `GATHER`
  and `FARM_ITEM` skip or refuse X's own targets; `COLLECT_ITEM` and `DELIVER` steps of X's
  quests ignore items that arrived from X's haul (the ledger keeps a 24 h provenance record
  of haul transfers per persona and item). What X does **in the owner's active party** does
  count - kills it lands beside the player credit its own `KILL_MOBS` step like any party
  member's - so a Persona can hand out a quest it then helps with. Authors can make that the
  point: a step with `with: <persona>` (or the `HUNT_WITH` step type) only advances while that
  Persona is active in the party at the moment of the kill or pickup ("come cull the boars
  with me, I want to see it done"). Other companions contribute normally either way: Leroy's
  party kill credits Garrick's boar cull, and Leroy can gather Garrick's badge from the bench.
  The quest giver being in the owner's party changes nothing else - it still offers, comments
  on and grades its quests.
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

Bot count does not appear in the table: 60 or 600 Residents cost the same API money. The
miss rate itself falls over time: every confident ACT becomes a learned phrase (section 12.3),
so a phrase costs money at most once per server, not once per player. Run
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
- Phrase packs (section 12): move the existing regexes into `command-phrases.yaml`, add the
  filler/tail normalizer and the learned-phrase store; ship the first 40 new commands.
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
- Roster and bench (section 11): `living_roster`, party-slot accounting, `bench`/`call`/`swap`,
  camps with the offline bench ledger (TRAIN first, then FARM_MESO / FARM_ITEM / GATHER / SELL
  / STALL), the haul with trade-window, Duey and storage collection, apprentice EXP bonus.
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

## 11. Party cap, roster and bench (the Pokemon model)

MapleStory parties hold 6 characters. One player therefore fields at most 5 Adventurers at a
time; two players in one party share the 4 remaining slots. Recruitment is not limited by
that: a player owns a **roster** of recruited companions and decides who is *active* (in the
party) and who is *benched*. Benched companions keep living and keep levelling.

### 11.1 Roster

- `living_roster` (characterId, personaId, recruitedAt, state ACTIVE|BENCHED|AWAY|RELEASED,
  campMapId, campMode, accruedExp, lastMaterializedAt, wagesPaidUntil). One row per
  (player, persona); the contract row stays the authority on whether the bond is still valid.
- `ROSTER_MAX` per player (default 10; can be raised by an arc reward or a purchasable
  "guild charter"). Recruiting past the cap requires releasing someone; released Personas go
  home as Residents with the bond memory intact (they remember being dropped: small trust
  penalty, cheaper rejoin only after cooldown).
- A Persona can belong to at most one player's roster at a time (they are real characters;
  decision confirmed 2026-09-20). Popular Personas are therefore contested: the contract's `cooldownHoursAfterLeave` plus a
  world flag `persona.<id>.employedBy` make the town say "Garrick left with someone last
  night". Quest-only Residents never enter a roster.
- Two humans in one party: each commands only their own companions (bond authority is per
  pair). Party slots are first come first served; the party leader can ask another human's
  companion to step out (`Leroy, make room`), which the companion obeys only if *its* owner is
  also in the party and has not said `Leroy, stay in`. Simple rule, no negotiation logic.

### 11.2 Active party management

Commands (all deterministic, see section 12 for phrasing):

| Intent | Effect |
|---|---|
| `call <name>` / `<name> come` | benched -> travelling -> joins party when it arrives (see 11.3) |
| `bench <name>` / `<name> take a break` | leaves party, goes to its camp |
| `swap <a> for <b>` | bench a, call b, keeping the slot |
| `roster` / `who do i have` | whisper list: name, level, state, ETA/camp |
| `<name> camp here` / `camp at <town>` | sets the bench camp for that companion |
| `everyone bench` / `call the usual` | bench all / call the last active set |
| `<name> wait here` | AWAY state: stays on this map idle, keeps the slot reserved for 10 min |

Slot accounting lives in `AgentRosterService`: `freeSlots = 6 - humans - activeBots`. A `call`
with no free slot is refused with the reason in the companion's voice, unless the owner names
who to swap.

### 11.3 Recall and travel

Realistic by default: a called companion travels from its camp with the existing travel and
navigation capabilities (portals, taxis, ships), unmaterialized and ETA-based when no player
can see it (`BACKGROUND_ABSTRACT` route ETA), materializing when it reaches a map with the
player or another human. The owner gets `on my way, ~3 min` and `here` whispers. Instant
recall exists as a consumable **whistle** (shop item, moderate meso price) or as a
`SWORN`-bond perk, so the world stays believable but nobody is forced to wait when they do
not want to. Camps are the companion's home town by default; the owner can set a training
map as camp, which is what makes benched training visible (11.4).

### 11.4 Benched companions keep pace (and earn)

A benched companion always has an **assignment**: `TRAIN` (levels, below), or one of the
economic modes in 11.5. Whatever the assignment, it runs through the same three layers,
cheapest first:

1. **Offline ledger** (`AgentBenchLedger`, no character online): each benched companion has
   an assignment (camp map + mode + target). Every 10 minutes the ledger credits the
   assignment's outcome; for `TRAIN` that is EXP from a per-level model: `expPerHour(level) = fieldExpRate(campMap, level) * efficiency`
   where `fieldExpRate` comes from the existing field capacity and training catalogs
   (`resources/agents/field/victoria-field-capacities.json`, `VICTORIA_LEVEL15_30_TRAINING_CATALOG.md`)
   and `efficiency` from strength tier and gear. Deaths and potion use are modelled as a meso
   upkeep drawn from the companion's own purse, then from `wages` the owner prepaid; an unpaid
   companion trains at half efficiency and says so. The ledger only accrues up to the **pace
   target** (below).
2. **Background-abstract sessions** when the server has headroom and no players are in the
   camp map: materialize the companion in `BACKGROUND_ABSTRACT` mode (the mode, policy and
   outcome ledger exist in `server.agents.runtime.simulation`) and let the existing adaptive
   hunting plan run for a bounded slice (real drops, real potion consumption, real EXP). The
   ledger is reconciled against the real outcome (`AgentBackgroundOutcomeReconciler`) so
   layer 1 never double-credits.
3. **Presentation** whenever a human is in the camp map: the companion is simply there,
   fighting, and the player can watch, trade, or talk to it. Entering the map is the Pokemon
   "visit the daycare" moment.

**Pace target.** A companion's target level is `ownerLevel - paceGap` where `paceGap` starts
at 3 and shrinks with bond stage (SWORN: 0). Benched accrual stops at the target so a
companion never overtakes its owner while benched; it also never falls more than `maxLag`
(default 8) behind, because below that the ledger applies a catch-up multiplier of up to 3x.
Both knobs are per Persona (a veteran mercenary may refuse to be outlevelled; a rookie is
always catching up). This is the "gets more EXP as the player progresses" behaviour expressed
as a visible rule instead of a hidden buff.

**In-party EXP.** Active companions receive normal party EXP shares (Cosmic already splits
EXP across party members in range and counts bots). On top, an **apprentice bonus** multiplies a
companion's kill EXP by `1 + 0.15 * min(6, ownerLevel - botLevel)` while it is at least 2
levels below its owner. Levels, job advancement and SP allocation reuse the existing
progression runtime and build profiles, so a companion's growth is real character growth.

**Contracts and the bench.** Contract duration counts party time only. Bench time is free, but
each Persona declares `benchTolerance` (days). Past it the companion quits the roster and
returns to Resident with a `walked-out` memory; some Personas set it to `0` ("I don't wait
around"), which is a deliberate difference between a loyal squire and a mercenary.

**Restart.** Roster state is DB-backed; benched companions need no process state at all.
Active companions are re-registered to their owner when the owner logs in, or benched if the
owner is offline for `LEADER_OFFLINE_20M`.

### 11.5 Economic assignments: the bench farms too

Companions are not only levelling on the bench; the owner can put them to work. Assignments
(set by command, section 12.2, or from the roster whisper):

| Assignment | What the companion does | Outcome credited |
|---|---|---|
| `TRAIN` | fights at the camp for EXP (11.4) | EXP up to the pace target, incidental drops |
| `FARM_MESO` | hunts the best meso-per-hour mobs for its level near the camp | meso |
| `FARM_ITEM <item> [n]` | hunts mobs that drop the item (real drop tables) until `n` or told to stop | the item, other drops |
| `GATHER` | farms whatever the owner's *active Persona quests* need (`COLLECT_ITEM` steps), except quests given by this Persona itself (see "no bench self-service" in 5.3) | quest items |
| `SELL` | walks to a town, sells the haul's junk at NPC prices, banks the meso | meso |
| `STALL` | sets up a Free Market stall (existing `AgentFreeMarketStallCapability`) and sells the haul at market prices | meso, slowly, more of it |
| `REST` | does nothing; no upkeep, no gain | - |

`FARM_ITEM` is refused, in the companion's voice ("that one's your errand, not mine"), when
the item is what one of *its own* active quests asks the owner to collect.

**Outcome model.** The offline ledger simulates `killsPerHour(level, campMap)` from the field
capacity catalog, rolls real drop tables through `MonsterInformationProvider` at the server's
own drop and meso rates, then applies a global **bench efficiency** (`BENCH_FARM_EFFICIENCY`,
default 0.6) so live play with the companion is always better than parking it. Excluded
offline: boss drops, event and cash items, anything on the SoloMapling hard-exclusion list.
When a bounded `BACKGROUND_ABSTRACT` session runs instead (layer 2), the outcome is real and
the ledger reconciles against it, exactly as for EXP.

**The haul.** Farmed items and meso go to a per-companion `living_haul` (the companion's real
inventory is the physical form; the haul row is the ledger view). Capacity is the
companion's actual slots, so the owner has to collect or the companion has to `SELL`; this is
what makes farming an activity instead of a faucet. Meso upkeep (potions, deaths, taxis) is
paid out of the haul first, and the contract's `lootShare` term decides the split: `NONE`
(the companion keeps nothing beyond upkeep), `OWN_DROPS` (it keeps a declared cut, default
10%, which it spends on its own gear over time - visible growth), `LEADER` (everything goes to
the owner).

**Collecting.** Three ways, all existing mechanics:
- meet the companion (call it, or visit its camp) and say `hand it over`: the trade window
  opens with the haul pre-loaded, the owner accepts;
- `send it`: the companion mails the haul through **Duey** (`DueyPackage` exists) for the
  normal Duey fee, so the owner picks it up in any town - the Pokemon "item delivered" moment;
- `bank it`: meso only, deposited to the owner's storage via the storage capability when the
  companion is in a town (fee-free, items excluded to keep storage as the player's own space).

**Guardrails.** Per-account bench farming is capped by `BENCH_FARM_HOURS_PER_DAY` (default 16)
and the haul per companion by its slots; farmed item volume flows through the same saturation
and rarity signals the SoloMapling economy already tracks (`bot_scroll_demand_cache`,
saturation multipliers), so a hundred benched companions farming the same drop depress its
yield the way merchant bots already do. `!world haul <player>` shows every companion's ledger
and the reconciliation log explains any credit.

## 12. Command surface: deterministic first, Jev last

The classifiers today match a *whole* normalized message against ~80 regexes with fixed tails
(`wait (here|up|for me|a sec)`), which is why `wait there`, `stop attacking` and `go attack`
missed last night and cost a judgment each. Expanding the surface has three parts.

### 12.1 Phrase packs replace inline regexes

`scripts/config/living-world/command-phrases.yaml` declares every canonical command with
its synonyms, argument slots and tolerated tails; a compiler turns it into the same
`Pattern`s the classifiers use, and the classifiers become thin lookups. Example:

```yaml
- canonical: stop
  aliases: [stop, stay, wait, hold, halt, freeze, chill, idle, park, "stand still", "don't move"]
  tails: [here, there, put, up, "for me", "a sec", "a moment", "a bit", moving, attacking, fighting, "right there"]
- canonical: grind
  aliases: [grind, farm, hunt, train, "kill stuff", "kill mobs", attack, fight, "get exp", "go to work"]
  prefixes: [go, start, begin, "let's", "time to", "you can", "keep"]
  tails: [here, around, "around here", "over there", "for a while", "till i'm back", now]
- canonical: focus
  aliases: [focus, target, prioritize, "go for", "kill the", "attack the"]
  slots: { mob: mobName }            # resolved against the map's live mob names, then Mob.wz names
```

Normalization (shared by every pack) strips the addressed name wherever it appears, the
existing leading fillers (`please|pls|hey|yo`), and a larger filler vocabulary
(`can you`, `could you`, `would you`, `just`, `go`, `now`, `ok`, `alright`, `dude`, `bro`,
`buddy`, emoji, repeated letters `plsss`, common typos via a small edit-distance table for the
alias words only). Matching is then `prefix? alias tail*` with unknown trailing tokens allowed
up to two, so `stop attacking` and `wait there` are deterministic hits and `get on the rope`
still misses (no alias). The compatibility test that pins canonical phrases to predicates
stays and gains one case per alias.

### 12.2 Many more commands

Target: from ~25 movement/info commands to ~150 canonical commands, each with 5-15 aliases.
Groups and examples (arguments in angle brackets are slot-filled deterministically):

- **Movement**: follow, stop, come, `go to <portal|npc|map>`, `meet me at <map>`, `return to
  town`, `wait at <spot>`, `take the taxi to <town>`, `stay close`, `keep distance`, `left/right
  of me`, `spread out`, `line up`, `lead the way to <map>` (navigation graph route).
- **Combat**: attack, `focus <mob>`, `avoid <mob>`, `only <mob>`, `use <skill>`, `stop using
  <skill>`, `hold aggro`, `kite`, `melee only`, `ranged only`, `protect me`, `retreat under
  <n>% hp`, `finish them`, `don't attack`, `attack on my mark`, `pull to me`.
- **Supplies and loot**: `buy pots`, `restock`, `share pots`, `pot at <n>%`, `sell junk`,
  `loot all|none|equips|rares|meso`, `give me the drops`, `keep the drops`, `hand over <item>`.
- **Gear and build**: `equip <item>`, `unequip <slot>`, `autoequip`, `show gear`, `compare
  <item>`, `put sp in <skill>`, `build <preset>`, `job advance`.
- **Party, roster, contracts** (section 11): call, bench, swap, roster, camp, `renew`,
  `extend a day`, `how long left`, `pay <n>`.
- **Bench assignments** (11.5): `train`, `farm meso`, `farm <item> [n]`, `gather for my quests`,
  `go sell`, `set up a stall`, `rest`, `what did you get`, `hand it over`, `send it`, `bank it`.
- **Progression**: `train here till <level>`, `train till i'm back`, `stay at this level`,
  `what level are you`, `how much exp`.
- **Town tasks**: `go sell`, `go bank`, `go heal`, `wait in town`, `buy <item> for me`
  (trusted only), `check the fm for <item>`.
- **Social / presentation**: greet, thanks, `good job`, emotes (`dance`, `sit`, `wave`,
  `cheer`), `quiet` / `talk more` (adjusts reply frequency), `what are you doing`.
- **Info**: hp, mp, level, exp, meso, inventory, slots, potions, scrolls, skills, stats,
  range, position (`where are you`), `what's your job`, `who are you` (persona blurb).
- **Resident dialogue** (section 5.2): quest, accept, done, join, bye, `who is <persona>`,
  `tell me about <topic>`.

Every new canonical needs an executor; most map onto existing capability calls (movement
modes, combat policy toggles, supplies, trade), so the work is mostly wiring plus a test per
command. Commands the runtime cannot honour yet are declared with `status: planned` so the
bot answers "can't do that yet" deterministically instead of falling to Jev.

### 12.3 Learned phrases (Jev teaches the regexes)

When the intent judge returns ACT with confidence >= 0.90 and the re-dispatch succeeds, the
normalized message is stored in `living_learned_phrase` (normalizedText, canonical, hits,
firstSeen, source=JEV|GM, approved). Learned phrases are loaded into the matcher ahead of the
packs, so `wait there` costs one judgment on the whole server, ever. Entries with fewer than
3 hits expire after 30 days; GMs review with `!phrases pending|approve|reject|export`, and the
export feeds back into `command-phrases.yaml` for the next release (and upstream).

### 12.4 What is left for Jev

With packs and learned phrases in place, Jev sees only genuinely new constructions, sentences
with two intents, and Resident conversation. Measured last night the companion state is
~3.5k tokens; it should shrink to ~2k by dropping sections the intent question does not need
(the state builder already bands numbers; the mob list and full inventory can go). Expected
effect on section 6: miss rate 25% -> under 10% within the first week of play, then a slow
decay as phrases are learned. `!typesafe` already shows the per-kind request count, so the
miss rate is observable directly (judgments / commands).

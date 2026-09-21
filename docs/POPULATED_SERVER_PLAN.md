# The populated server: making FriendStory feel like official MapleStory

Companion to `docs/LIVING_RPG_PLAN.md` (hand-authored Residents, quests, companions) and the
Agent engine docs under `docs/agents/`. This plan covers the other half of the goal: hundreds to
thousands of bots across the whole world that read as real players, with chat that makes each one
feel like a person, without ballooning API cost or melting the server.

Status: plan written 2026-09-21; first cut of every phase built the same day as
`server.living.population.*` (see `docs/POPULATION_GUIDE.md` for what exists, how to run it and
what is still open). Sizing numbers below remain hypotheses until the soak run in section 9.

## 0. Goal and shape

What made a v83 server feel inhabited was not clever conversation. It was six things, roughly in
order of how much they mattered:

1. **Persistence.** The same names for months, and they changed: levels, jobs, gear, guild tags.
2. **Social structure.** Guilds, buddy lists, parties forming in lobbies, fame, whispers.
3. **Chatter with context.** `S> zhelm 30m`, `R>2 kpq 21+`, `cc plz`, `gratz`, `f` - short lines
   that referenced what was actually happening on that map, right then.
4. **Presence rhythm.** Peak evenings, dead mornings, weekend PQ lines, people going AFK in town.
5. **Economy you could join.** Free Market rows, price talk, someone buying what you were selling.
6. **Imperfection.** Typos, ignoring you, "brb", arguments about KS, misclicked trades.

Design principles that follow:

- **Persistence over cleverness.** A regular who greets you by name at 9pm every night is worth
  fifty anonymous grinders and costs nothing.
- **Templated over generated.** Almost all chat in official MapleStory was formulaic. Ambient and
  reactive chatter is templates plus a voice; generation is reserved for a human talking directly
  to a bot.
- **Cost stays flat as population grows.** Population size drives CPU, not API spend. API spend is
  bounded by how many humans are talking to bots, which is bounded by how many humans you have.
- **Fidelity follows perception.** Only bots a human can see run at full fidelity; everyone else is
  cheap. This is already how the Agent engine works (`AgentSimulationMode`).
- **Measure before scaling.** The engine's 2000-agent target is a design goal with a central
  scheduler behind flags and legacy per-agent scheduling as the production default. Our ceiling on
  this machine is unknown until the soak harness runs.

## 1. What already exists (build on it, do not duplicate it)

| Need | Exists | Where |
|---|---|---|
| Managed population roster and reconciler | yes, file store, target = managed count x multiplier, KPQ/HPQ population runtimes | `server.agents.population.*` (`AgentPopulationRecord(characterId, name, crewId)`, `AgentPopulationCurve`, `AgentPopulationAdminService`) |
| Ambient town population | yes, per-town profiles with admission caps (Henesys `maxAmbientAgents` 36), activities REST/SOCIALIZE/..., standby `UNMATERIALIZED` | `resources/agents/town-life/*.json`, `AgentTownLifeRuntime` |
| Field capacities (how many grinders a map holds) | Victoria only | `resources/agents/field/victoria-field-capacities.json` |
| Personality | traits 0-100 (activity, patience, expressiveness, curiosity, sociability, riskTolerance, routinePreference), persisted assignments | `server.agents.personality.*`, `profiles/personality-profiles.json`, `docs/agents/AGENT_PERSONALITY_PRESENTATION.md` |
| Dialogue voice | style profiles (formality, verbosity, slang, lowercase, emoticon frequencies, tone instruction) and a per-style line catalog by intent | `resources/agents/social/dialogue-style-profiles.json`, `generic-dialogue-catalog.json` |
| Generated small talk | Ollama provider (default `qwen3.5:9b-q4_K_M`), deterministic fallback, modes `DETERMINISTIC_ONLY` / `DIALOGUE_ONLY`, relationship memory per counterparty | `server.agents.social.conversation.AgentSocialDialogueRuntime`, `social.memory.*`, env `SOCIAL_*` |
| Typed judgments | TypeSafe Jev, metered per kind, per-player budgets for Residents | `server.agents.integration.typesafe.*`, `docs/agents/AGENT_TYPESAFE_JEV_JUDGMENTS.md` |
| Director (action proposals) | Ollama and TypeSafe providers, assisted/autonomous rollout flags | `AGENT_WORLD_DIRECTOR_*` in `agent-engine.yaml` |
| Movement and interaction realism | approach variation, dialogue-length delay, profile variation | `docs/agents/INTERACTION_REALISM_POLICY.md` |
| Simulation fidelity | PRESENTATION / BACKGROUND_ACTIVE / BACKGROUND_ABSTRACT (abstract only for town life) | `server.agents.runtime.simulation.*` |
| Scheduler and scale | central scheduler phases 0-7 behind flags; soak harness spec (`!soak agents start <count> <scenario>`) | `docs/agents/AGENT_ENGINE_SCALING_TRACK.md`, `AGENT_SOAK_TEST_IMPLEMENTATION_SPEC.md` |
| Economy | FM stall registry, room allocator, item valuation, negotiation sessions, trade gateway | `server.agents.economy.*`, `docs/agents/FREE_MARKET_SPACE_AND_STALL_CAPABILITY.md` |
| Party quests | KPQ, LPQ, LMPQ, OPQ, PPQ, HPQ, EPQ automation; Balrog expedition | `server.agents.capabilities.partyquest.*`, `expedition.*` |
| Hand-authored people | 54 Residents with routines, quests, contracts, arcs, rumors; companions, roster, bench | `server.living.*`, `docs/LIVING_WORLD_GUIDE.md` |
| Core game systems to drive | guilds and alliances, buddy lists, parties, megaphones, hired merchants and player shops, rankings, channels (config: 3) | `net.server.guild.*`, `client.BuddyList`, `net.server.world.Party`, `UseCashItemHandler` (megaphone cases 1/2/6/7), `server.maps.HiredMerchant`/`PlayerShop`, `Server.loadPlayerRankingFromDB` |

What is missing, in one line each: bots have no life cycle (they are spawned at a level and stay
there), no schedules, no guilds or buddies, no chatter that is not a reply, no journal to ground
conversation in, one channel, Victoria only, and no measured ceiling.

## 2. Vocabulary

- **Citizen**: a generated population bot with a persistent life: account, character, cohort,
  schedule, progression, voice, social ties. Residents (living world) are hand-authored citizens
  with quests; everything here applies to them too.
- **Cohort**: a group of citizens sharing a time zone and play pattern (`evening-na`, `weekend-eu`,
  `nightowl`, `student-afternoon`). Cohorts drive who is online when.
- **Presence schedule**: a citizen's weekly login pattern derived from its cohort plus noise.
- **Circle**: 2-6 citizens who know each other: they party, chat, and log in around the same time.
- **Voice pack**: how a citizen types: style profile plus typo rate, abbreviations, emoticons,
  capitalisation, pet phrases. Deterministic transform applied to every line it says.
- **Chatter event**: something in the world that may make nearby citizens say a templated line
  (a level-up, a rare drop, a death, a boss spawn, a price, a lobby forming).
- **Journal**: the last day of a citizen's real events (maps trained, kills, drops, deaths,
  parties, trades) kept for grounding conversation.
- **Persona card**: the compact description of a citizen handed to a model: name, job, level,
  guild, home town, this week's goal, voice, three memories about the human it is talking to.
- **Fidelity tier**: PRESENTATION (a human can see it), BACKGROUND_ACTIVE (real simulation,
  reduced cadence), ABSTRACT/ledger (no character online; progress modelled).
- **Perception radius**: the set of maps humans are currently in, plus adjacent maps they might
  enter within a few seconds; the only places PRESENTATION is spent.

## 3. Phase A: the presence model (citizens exist over time)

Everything else hangs on named people who exist over months. This phase replaces "spawn N bots
at level L" with a registry of citizens who have lives.

### 3.1 Citizen registry

Extends the population roster (`AgentPopulationRecord` today holds id, name, crew) into a
`living_citizen` table (or a new file store, but SQL is better for thousands):

```yaml
citizen:
  characterId: 1234
  cohort: evening-na            # drives schedule
  circle: 17                    # friend group id, nullable
  guild: 5                      # guild id, nullable
  homeRegion: victoria          # where it lives and trains
  careerPath: warrior-fighter   # existing build/progression profiles
  levelTarget: 47               # where the life curve says it should be this week
  personality: relaxed-v1       # existing profile id
  voice: casual-typos-v1        # voice pack id (section 5.3)
  joinedAt: 2026-09-01
  retiresAt: 2027-02-14         # churn: quits some day; a new citizen is born
  flags: [afk-in-fm, pq-regular, famer]
```

Names come from a generator seeded per cohort (era-appropriate: `xXDarkKnightXx`,
`iLoveCookies`, `Sorabi`, `NightLordSam`) and are screened with the existing Jev name screen
so nothing impersonates staff or hides a slur. Names must never collide with real players; the
provisioning path already locks bot accounts against interactive login.

### 3.2 Presence scheduler

Cohorts define hourly online probability across the week; each citizen gets a personal offset and
session-length distribution. Every sweep the scheduler picks who should be online, logs them in
(materialize or ledger, see 3.4) and logs them out with a small "gtg"/"night" chance in whatever
chat they are in. Examples:

| Cohort | Pattern |
|---|---|
| `evening-na` | 18:00-24:00 ET weekdays, all afternoon weekends, 60-180 min sessions |
| `student-afternoon` | 15:00-19:00, short sessions, high PQ rate |
| `nightowl` | 23:00-04:00, long sessions, FM merchants |
| `weekend-eu` | offsets +6h, Saturdays heavy |

Server-wide shape: a peak/trough curve (for example 10-20% of citizens online at peak, 2-4% at
4am) so the world breathes. `ROUTINE_ZONE` from the living world is reused for wall-clock.

### 3.3 Where a citizen goes when online

A daily plan per session drawn from personality and level: train (field chosen from field
capacities by level band, the way the bench chooses camps), town errands (existing TownLife
activities), PQ lobby (KPQ at 21-30, LPQ 35-50, OPQ 51-70), FM (sell/stall/browse), AFK in town,
travel (ships and taxis; the existing recall travel model gives ETAs when unobserved). Plans
prefer maps in the perception radius when a human is around so the world looks fuller where it is
looked at, within per-map caps (3.6).

### 3.4 Progression: real levels over weeks

A citizen levels for real when it is in PRESENTATION or BACKGROUND_ACTIVE (it is actually
fighting), and through a ledger when nobody is online to see it (the bench ledger's
`expPerHour(level) x efficiency` model, generalised: `AgentFairnessBudgetService` in the
optimisation doc describes the same cap). Job advancements run through the existing legal
`changeJob` chain (the shaper already does this), SP through build profiles, gear through the
equipment service plus purchases (section 7). The life curve is slow on purpose: a citizen gains
roughly what a casual player gains, so the server's level distribution drifts realistically and
the rank board (`Server.loadPlayerRankingFromDB` reads the `characters` table, so bots appear
automatically) changes week to week.

### 3.5 Churn

Citizens retire (stop logging in, then are deleted from the roster after a grace period) and new
low-level citizens are born so the population shape holds. Retirement is announced the way
players did it: a last "quitting, giving away stuff" in town, maybe an item drop to a buddy.

### 3.6 Channels and caps

- Distribute citizens across channels (`channels: 3` today; consider 5-10) with the "ch1 is
  crowded" bias real servers had. Citizens change channel occasionally (`cc plz` works both ways).
- Per-map visible cap from the client: the v83 client degrades past roughly 50-70 characters on
  one map; target at most ~40 bots visible on any map a human is in, fewer on small maps. Town
  profiles already carry `maxAmbientAgents`; fields carry recommended maxima.
- Perception radius drives fidelity: `MapGateway.isObservedByPlayer` today; extend to adjacent
  maps so bots are already there when a player walks in, not spawning on arrival.

Sizing hypothesis to validate in Phase G: 3000 citizens, 300-600 online at peak, 40-80 in
PRESENTATION, the rest BACKGROUND_ACTIVE or ledger.

## 4. Phase B: social fabric

Grep shows essentially no guild or buddy support in `server.agents`. This is MapleStory's soul.

### 4.1 Guilds

- Bot guilds created through the real guild system (`net.server.guild.Guild`) with a bot leader,
  a name and emblem from a generator, a level range and a recruiting stance (`open`, `invite`,
  `closed`). Membership follows cohorts and circles so guildmates are online together.
- Guild chat chatter (section 5) at low density: greetings on login, "anyone up for kpq",
  reactions to a guildmate's level-up.
- Recruiting behaviour: a recruiter posts smegas on a rate limit (`Recruiting lv30+ for Legacy,
  active and friendly!`), invites citizens who match, and invites a human who has been around a
  guildmate for a while and is in range (the invite is a real invite; the human can decline).
- Guild BBS posts from templates; guild rivalries feed rumor content (living world
  `RumorScheduler` gets a `guild.<id>.rivalry` source).
- Alliances later.

### 4.2 Buddies

- Circles buddy each other at birth; citizens buddy whoever they party with more than N times
  (bot or human). A buddy request to a human is sent through the normal `BuddyList.addBuddyRequest`
  path, once, after enough shared time; never spammed.
- Buddy chat: "ur on!" on login, "gn" on logout, occasional check-ins; buddies whisper each other.

### 4.3 Circles and parties

- Circles log in near each other, meet in town, form real parties (`Party`) and train or PQ
  together, using the existing PQ automation for KPQ/LPQ/OPQ. Lobby chatter ("R>2 for kpq 21+",
  "lf> cleric") comes from the party-forming state, and a human of the right level is invited
  when a slot is open.
- Party chat during training is templated: "cc?", "ks", "hp", "brb", "gratz".

### 4.4 Fame and whispers

- Famers in Henesys (`fame me back?`), fame given to buddies and guildmates on their schedule.
- Whispers between citizens are invisible to humans but keep the social graph "warm" (they update
  relationship memory); whispers to humans are rare and grounded ("hey you were at pig beach
  earlier right?").

## 5. Phase C: the chatter engine (zero-cost chat)

Most of what people typed was formulaic. This engine makes citizens talk constantly at zero API
cost, in their own voice, about what is happening.

### 5.1 Sources of chatter events

| Source | Examples |
|---|---|
| Map context (on entering / dwelling) | FM: `S> zhelm 30m`, `B> 10% scrolls`; Henesys: `how do i get to ellinia`, `where is the job advancement npc`; Kerning: `R> kpq 21+`; training map: `cc plz`, `ks pls no`, `this map is mine`, `lf> HS` |
| Player events (any character in range) | level-up -> `gratz`/`ding`/`grats`; death -> `f`/`rip`; rare drop -> `omg`/`nice`; job advance -> `congrats on 2nd job` |
| Own events (journal) | own level-up -> `finally 30`; own death -> `ugh lost 5%`; a good drop -> `just got a maple sword` |
| Boss / spawn | Mushmom / Balrog / Zakum spawn or kill -> panic, brag, recruiting |
| Economy (section 7) | price talk tied to the price index: `zhelm is like 25m now` |
| Social | guild recruiting, party forming, buddy greetings, fame requests |
| Time | `gtg`, `brb`, `back`, `night guys`, `server lag?` on tick lag |

### 5.2 Selection: who speaks, how often

- Each event proposes lines to citizens in range; a citizen speaks with probability from its
  `sociability` and `expressiveness` traits, minus fatigue (cooldown per citizen per channel) and
  minus map density (a chatter budget per map, e.g. one line per 3-5 s in a crowded Henesys, less
  on a quiet field). Real maps were not spam walls; silence is realistic.
- Deterministic selection: no model call. Jev can rank "which of these five lines fits this
  moment" for high-visibility moments (a human's level-up) at ~0.5k tokens, optional.
- A citizen never reacts to its own templated lines, never to two events within its cooldown, and
  never repeats a template within N uses (per-citizen recent-line memory).

### 5.3 Voice packs

A voice pack is a deterministic transform over any line the citizen says (templated or
generated), keyed on the existing dialogue style profile:

```yaml
voice:
  id: casual-typos-v1
  style: friendly-casual-v1       # dialogue-style-profiles.json
  lowercase: 0.9                  # probability the line is lowercased
  typoRate: 0.03                  # per word: swap/drop a letter, fixed with a "*word" 30% of the time
  abbreviations: { you: u, are: r, please: pls, thanks: ty, anyone: ne1, because: cuz }
  abbreviationRate: 0.6
  emoticons: ["^^", "xD", ":)", "lol"]
  emoticonRate: 0.25
  petPhrases: ["ngl", "tbh"]
  delayMsPerChar: 55              # typing delay before the line appears (realism policy)
  ignoreRate: 0.15                # chance to not answer a direct question at all
```

The transform runs server-side on the line text before `AgentDialogueTransportRuntime`. The same
pack applies to generated text, so a model reply and a template reply from the same citizen look
alike.

### 5.4 Content packs

`scripts/config/population/chatter/*.yaml`: intents with contexts, weights and templates with
slots (`{item}`, `{price}`, `{map}`, `{name}`, `{level}`, `{guild}`), per style variants, linted
like the living world content (`ContentLint` pattern) with a "no line may contain" list and an
ASCII rule. Era-appropriate vocabulary is a content problem, not a code problem; the pack is
where the flavour lives and MapleEdit's Living World page edits it.

### 5.5 Channels the engine writes to

Map chat, party chat, guild chat, buddy chat, whispers, and megaphones (with hard rate limits per
citizen and server-wide; megaphones are the one place spam is not realistic). All of these are
existing packet paths; the transport runtime grows one method per channel.

## 6. Phase D: conversation that feels like a person (bounded generation)

Generation is reserved for a human addressing a bot. The building blocks exist
(`AgentSocialDialogueRuntime` with Ollama, `DialogueFallbackCatalog`, relationship memory,
sanitizer, dialogue-length delay). What is missing is grounding and tiering.

### 6.1 The four tiers

| Tier | Trigger | Mechanism | Cost |
|---|---|---|---|
| 0 silence | most chat near a bot | nothing; `ignoreRate` from the voice pack even when addressed | 0 |
| 1 template | chatter events, exact phrases, known questions ("where is X" -> map knowledge) | chatter engine, phrase packs, `DialogueFallbackCatalog` | 0 |
| 2 judgment | ambiguous line near several bots | Jev: addressed-to-me?, intent, which citizen should answer (one fan-out request) | ~0.5-1k tokens, ~$0.04/M |
| 3 generation | a human talking to this citizen | persona card + journal + memory -> model -> voice pack -> delay | local Ollama: 0; hosted small model: budgeted |

Tier 3 is entered only when tier 2 says the human is talking to this bot, the bot's cooldown and
the player's daily budget allow it, and nobody else in the circle already answered.

### 6.2 Grounding is the realism lever

A 9B model with a good card beats a frontier model with none. The request factory
(`AgentSocialDialogueRequestFactory`) is extended to assemble, under a token budget:

1. **Persona card** (about 120 tokens): name, job and level, guild, home town, cohort ("plays
   evenings"), this week's goal ("trying to hit 35 for LPQ"), personality traits in words, voice
   tone instruction (already in the style profile).
2. **Journal** (about 150 tokens): the last session's real events from the Agent event stream and
   `living_event_log`: "trained at pig beach 40 min, died once to a boar, got a maple shield,
   partied with Sorabi and Kev, sold 200 pig ribbons for 60k".
3. **Relationship memory** (exists: `SocialRelationshipMemory`): last three interactions with this
   human, their name, what they asked, whether they were nice.
4. **Scene** (about 60 tokens): map name, who else is here, time of day, what the bot is doing.
5. **Recent lines** (the last 4-6 turns).
6. **Rules**: stay in character, one or two short lines, never claim to be an AI, never give
   real-world information, no item or meso promises the code did not make (offers go through the
   trade gateway, never through text).

Answers pass through the sanitizer, the voice pack, then the typing delay. If the model is slow
(> timeout) the fallback catalog answers and the generated line is dropped, not queued late.

### 6.3 Budgets and knobs

- Per human per day: N generated turns (like `JEV_PER_PLAYER_PER_DAY`), then citizens fall back to
  tier 1 with an in-character excuse ("gtg, ttyl") rather than silence.
- Per citizen: cooldown between generated replies; at most one open conversation.
- Global: concurrent generation cap sized to the Ollama box (one 9B model answers one request at a
  time comfortably on a consumer GPU; the queue is bounded and drops to fallback).
- Provider choice: Ollama by default (free, private); a hosted small model as an optional provider
  behind the same `DialogueProvider` interface when the local box is busy or absent. Every request
  carries a usage kind into a meter next to `JevUsageMeter` so cost is visible on `!typesafe`-style
  reports.

Cost picture (estimates, to be measured): with the persona card and journal a turn is ~1.2-1.8k
input tokens and ~40 output. Fifty human conversations a day of ten turns is ~750k input tokens:
zero on Ollama, on the order of a dollar a day on a hosted small model at current small-model
pricing. Ambient chatter for 3000 citizens: zero. Jev judgments scale with human lines near bots,
not with population.

### 6.4 Consistency across days

Citizens remember: relationship memory is persisted already; add a per-citizen "life summary"
refreshed weekly from the journal (`level 31 -> 38, joined Legacy, main training spot pig beach,
friends Sorabi and Kev`) so a citizen you met two weeks ago still knows who it is and who you are.

## 7. Phase E: an economy you can join

The market code exists (stalls, room allocator, valuation, negotiation) but is not a felt system.

- **FM rows populated** on the nightowl and merchant cohorts: stalls with prices from
  `AgentItemValuationService` plus a server price index that drifts with what actually sells.
- **Buying from humans**: citizens browse player shops and hired merchants and buy things priced
  under the index (bounded meso per citizen per day), so a human's shop sells.
- **Requests**: `B> 60% glove att scrolls` in FM chat is a real intent the human can fulfil via
  trade; the negotiation session already models offers and counters.
- **Price talk**: chatter templates read the index, so "zhelm is like 25m now" is true.
- **Merchant careers**: some citizens stop training and become merchants (a life-curve branch),
  which is what happened on real servers.

## 8. Phase F: the whole world

- TownLife profiles for Orbis, Ellinia-Orbis ship, Ludibrium, El Nath, Aquarium, Ariant (the
  Ariant questline plan exists), Nautilus is done.
- Field capacities beyond Victoria (the observation harness that produced the Victoria catalog
  can be rerun per region).
- PQ rotations by level band (LPQ, OPQ, LMPQ exist) so lobbies are populated at the right hours;
  boss expeditions (Balrog exists; Zakum needs the prequest chain) recruited via smega.
- Living world content for the new regions: Residents, quests, rumors in each town, so the
  hand-authored layer travels with the population.
- Server "news": generalise `RumorScheduler` sources so citizens gossip about real events (first
  citizen to 70, a guild that cleared Zakum, a human who died to Mushmom three times).

## 9. Phase G: scale and fidelity (measure, then turn knobs)

Order of work:

1. Run the soak harness on this machine (`!soak agents start <count> <scenario>`, CSV under
   `logs/soak/`) at 100 / 250 / 500 / 1000 mixed grinders and town bots, legacy scheduler. Record
   tick p95, GC, heap, DB write rate, and client frame rate on a crowded map.
2. Turn on `CENTRAL_SHARDED` and the simulation-tier phase (7) in staging, repeat.
3. Set the citizen totals from the numbers, not the other way round. The 3000/300-600/40-80
   figures in 3.6 are hypotheses.

Levers, cheapest first: perception-radius fidelity (already there), per-map caps, cohort curve
(fewer online at once), ledger instead of BACKGROUND_ACTIVE for citizens more than one map away
from any human, batched character saves, fewer channels per world.

Client limits are independent of server headroom: keep visible bots per map under the client's
comfort line, spread across channels, and keep town bots mostly still (chairs, standing) since
movement packets are what cost the client.

## 10. Cost and budget policy

| Layer | Driver | Bound |
|---|---|---|
| CPU / memory | citizens online x fidelity | soak-measured caps, cohort curve |
| DB | character saves, journals, chatter cooldown state | batch writes; journals in memory with hourly flush |
| Jev | human lines near bots, optional line ranking | per-player daily budget, metered per kind |
| Generation | human <-> bot conversations | per-player and per-citizen budgets, global concurrency, Ollama first |
| Content | templates, names, voice packs, guild names | authored once, linted, edited in MapleEdit |

Nothing in phases A-C, E and F spends API money. Phase D is the only paid layer and it is
bounded by humans, not by population.

## 11. Tooling and observability

- MapleEdit: extend the Living World page with a Population tab (citizens online by cohort,
  fidelity tiers, per-map counts, chatter rate, generation queue, spend), and chatter/voice pack
  editing with lint.
- Commands: `!citizens status|cohort <id>|online|retire <name>|born <cohort>`, `!chatter
  rate|mute <map>|test <intent>`, `!voice preview <pack> "<line>"`.
- AdminControlPort routes mirroring the above for the dashboard.
- Logs: one line per generated turn with kind, tokens, latency, provider; chatter counters per
  map per minute; presence sweeps.

## 12. Roadmap

| Phase | Deliverable | Acceptance |
|---|---|---|
| A presence | citizen registry, cohorts, presence scheduler, life-curve progression, churn, channel spread, rank board | 500 citizens with schedules; online count follows the cohort curve for a week; levels drift; `!citizens status` shows it |
| C chatter | chatter events, selection with density budgets, voice packs, content packs, all chat channels | walk Henesys/Kerning/FM at peak and read 20 lines; none repeats within a session; a level-up gets a `gratz` from at most two nearby citizens |
| B social | guilds with recruiting and guild chat, buddies, circles forming real parties and PQs, fame | a human of level 22 gets a KPQ invite from a forming bot party; a guild invite after an evening near guildmates |
| D conversation | persona cards, journals, tiering, budgets, provider meter, life summaries | ask a citizen "how's it going" twice a week apart; both answers reference real events and remember you |
| E economy | FM rows, price index, bots buying from human shops, requests | a human's stall sells something to a citizen within an evening; price talk matches the index |
| G scale | soak numbers, scheduler rollout, caps set from data | tick p95 within budget at the chosen citizen total with a human on a crowded map |
| F world | Ossyria profiles and capacities, PQ rotations, expeditions, regional Residents | Orbis and Ludibrium feel as populated as Henesys at the same hour |

A and C first: they are cheap and they deliver most of the felt effect. B rides on A's cohorts and
circles. D is last among the core phases because it is the only one that costs money and it is
worth far less without A-C making the people real in the first place.

## 13. Risks and open questions

- **Uncanny repetition.** Templates get recognised fast. Mitigations: large packs, per-citizen
  recent-line memory, voice packs, low density. Count distinct lines per hour in the soak.
- **Client load.** More than the server, the v83 client bounds visible bots per map. Measure with
  a real client on a crowded Henesys before setting caps.
- **DB growth.** Journals and chatter state must stay in memory with bounded flushes; character
  saves for hundreds of online bots need batching (the engine's persistence sections).
- **Names and moderation.** Generated names go through the Jev name screen; ambient chat is
  template-only, so nothing unreviewed is ever said at scale; generated replies pass the sanitizer
  and never offer items or meso.
- **Model availability.** Ollama on the host competes with the server for CPU/GPU; the generation
  cap and fallback catalog make a slow or absent model invisible to players.
- **Real-player interplay.** Citizens must yield: never KS a human on purpose, never take a human's
  FM room, never fill a party a human is trying to fill. The realism policy already has "player
  first" rules for interaction; extend them to economy and lobbies.
- **Authority.** Humans command their own companions only (operator grant); citizens ignore
  commands from strangers except social ones.
- **Time.** Content volume (chatter packs, names, guild names, voices) is the long pole, and it is
  authoring work in MapleEdit, not engineering.

# TypeSafe Jev in the FriendServer Cosmic server — exploration

Date: 2026-09-19. Sources: TypeSafe live docs (`docs.typesafe.ai`, jev-1.13.0) and a survey of
this tree after the cosmic_agents merge (`docs/COSMIC_AGENTS_MERGE_2026-09-19.md`).

## 1. What Jev is, in terms that matter for a game server

Jev is a "System One" model: you POST a JSON **state** plus a map of typed **questions** to
`https://api.typesafe.ai/v1/systemone` and get typed answers back — no free text.

| Primitive | Answer | Use it for |
|---|---|---|
| **Choice** | one option from a closed set + probability per option + confidence | routing / classification |
| **Noul** | probability that a yes/no statement holds | gates, filters, "is this addressed to me?" |
| **Score** | probability-weighted position on ordered, described levels | severity, degree, ranking candidates |

Facts that shape the design (all from the docs, verified today):

* **~100 ms** typical response. All questions in one request run in parallel against the state,
  so ask everything about a message in *one* call (the "speculative fan-out" pattern).
* **$0.042 per million input tokens**, output free. A chat-command request with ~30 intents is
  roughly 1.5–2k tokens → ~$0.0001. 10,000 bot commands a day ≈ $1/day worst case.
* Limits: 64k tokens per request, 32k for state; 1,200 requests/min, 250k tokens/s (dynamic).
* **Text only, no generation.** Jev never writes a sentence. Bot *replies* stay canned or Ollama.
* Known jagged edges (jev-1.13): literal reading, **no arithmetic or counting**, no date/time
  ordering, weaker on double negatives, accuracy drops with irrelevant state, **not adversarial
  by default** (player text can steer it), no structural invariants between questions.
* No Java SDK — Python/JS only — but the HTTP contract is small. The tree already has Jackson
  (`pom.xml`, from cosmic_agents) and a `java.net.http` client pattern in
  `server.agents.integration.ollama.OllamaDialogueProvider` to mirror.

Design consequence: **code owns every rule, number, and side effect; Jev only answers "what did
the human mean / is this the case / how severe is this" over text we already have.** Anything
numeric (prices, rarity, spawn math, combat, pathing) is a non-fit and stays where it is.

## 2. Where this server currently fakes semantic judgment

The survey found these seams — places where code approximates language understanding with
regexes, bag-of-words, or a local LLM whose output has to be parsed:

| Seam | Today | Size |
|---|---|---|
| Companion chat commands (`server.agents.capabilities.dialogue.*Classifier`) | **104 compiled regexes**, `isGrindCommand(String)`-style booleans, Levenshtein "did you mean" fallback | 1,086 lines |
| Director panel operator prompts (`AgentDirectorChatService`) | Ollama asked to *rank* training maps / proposals; falls back to catalog order when its text doesn't parse | — |
| Human chat during party quests (`AgentKpqHumanDialoguePolicy.asksForCouponCount`, lobby runtime) | word-set heuristics (`"how many"` + `coupon`/`ticket`) | small, per-PQ |
| Agent ↔ player trade replies (`AgentPendingOfferChatRouteService`, `CosmicPublicTradeNegotiator`) | accept/decline/counter parsing | — |
| SoloMapling social bots (`SocialCommands.BotDialogue(List<String>)`, `BotDialogueHandler`) | canned lines, no understanding of what players say to them | — |
| Character names (`Character.BLOCKED_NAMES`, `canCreateChar`) | substring blocklist (~40 words; `"gay"` also blocks *Gaylord*) | — |
| Player reports (`ReportHandler`) | stored + GM notice, no triage | — |
| Chat (`ChatLogger`, `USE_ENABLE_CHAT_LOG`) | logged, never judged | — |
| MapleEdit GM command queue (`AdminServiceBot`, `GmTestQueueWatcher`) | exact GM command strings | — |

## 3. Use cases, ranked

### Tier 1 — clear fit, do first

#### A. Companion command understanding (intent routing)
The single best fit in the codebase. Today "grind here", "farm this spot", "kill stuff around
here pls" hit or miss 104 regexes; misses fall to a typo suggester or get treated as chat.

Design (function-calling cookbook shape):

* **State**: `{ "message": "<text>", "speaker": "<leader>", "bots_present": ["Jason","Leroy"],
  "in_party": true, "map": "Henesys Hunting Ground I" }` — small and scoped.
* **`intent` (Choice)** over the closed command set from `README_SERVER_BOTS.md`: `follow`,
  `stop`, `move_here`, `grind`, `patrol`, `sentry`, `fidget`, `formation`, `info_query`,
  `support_toggle`, `supply_request`, `fame`, `gear_query`, `trade_or_give`, `drop`,
  `sell_trash`, `respec`, `recruit`, `dismiss`, `transfer`, `help`, **`chat_not_a_command`**.
  Every option gets the same contrastive fields (`what` / `not_for` / `examples`) as the docs
  recommend; the last option is the mandatory "no match" outcome.
* **Speculative argument questions in the same request** (only read when relevant):
  `info_topic` (Choice: inventory/slots/stats/range/build/skills/mesos/speed/exp/scrolls/pots/
  buffs), `item_category` (Choice: scrolls/pots/buff/ammo/equips/trash/use/etc/mesos/named),
  `toggle_state` (Choice: on/off/cheap/max), `formation_mode`, `equip_slot`,
  `amount_present` (Noul), `addressed_to_all_bots` (Noul).
* **Code keeps**: target resolution (`resolveTargetedAgent` name-prefix/slot logic is
  deterministic), meso amount parsing (`10k`, `1.5m` — Jev can't do numbers; use the regex
  candidates and, if several, a Choice to pick *which* candidate is the amount), permission and
  ownership checks, cooldowns, the actual dispatch.
* **Confidence-gated routing**: regex hit → act immediately (zero latency, unchanged). Regex
  miss → Jev. `confidence ≥ T_act` → act; `T_ask ≤ confidence < T_act` → reply "did you mean
  *grind*?" through the existing typo-suggester reply path; below → treat as chat. Thresholds
  live in `agent-engine.yaml`, tuned on real logs, not guessed.
* **Rollout**: shadow mode first — Jev is asked on *every* command, its answer is logged next
  to the regex result, nothing changes in-game. Measure agreement and the confidence
  distribution for a week of play, then go live on the miss path only.

Why it is worth it: fewer "bot ignored me" moments without growing the regex pile; each new
phrasing is a criteria edit, not a pattern; misfires are visible as probabilities in the log.

#### B. Director panel: replace Ollama *ranking* with Jev
`AgentDirectorChatService` asks Ollama to rank candidate training maps for an operator prompt
and parses the text back; when it fails it silently uses catalog order. That is "select from
candidates", which is exactly what Jev does and Ollama does badly:

* One **Score** per candidate ("how well does this map fit the operator's request", levels:
  *poor fit / partial / good / ideal*) in a single request → rank by score, break ties by
  catalog rank in code. Or a **Choice** over candidate ids when only one is wanted.
* `is_training_map_question` (Noul) and `wants_resupply` (Noul) replace the
  `prompt.contains("supply")` keyword branches.
* Keep Ollama only where prose is produced (the friendly explanation). Result: ~100 ms
  instead of seconds, no 2–10 GB local model requirement for the Director, and a real
  confidence to show the operator.

#### C. Understanding human players inside party quests
`asksForCouponCount` is a word-set heuristic gated by phase. Generalize: per PQ phase, one
request with a handful of **Nouls** — *asks how many coupons/tickets are needed*, *asks the
agents to wait*, *asks who has an item*, *says they are ready / go*, *asks for the answer to the
stage puzzle*, *is talking to the agents at all*. Phase gating, cooldowns and the canned
answers stay in `AgentKpqHumanDialogueRuntime`. Same pattern for the lobby runtime and later
LPQ/OPQ/EPQ. Already asynchronous with humanlike response delays, so 100 ms is invisible.

### Tier 2 — good fit, needs a policy decision from you

#### D. Moderation and safety (guardrails cookbook)
* **Character names**: replace/augment `BLOCKED_NAMES` substring matching with Nouls —
  *impersonates staff (GM/admin/Nexon)*, *is a slur or sexual term including leetspeak or
  spacing tricks*, *is an ordinary name that merely contains a blocked substring*. Code
  keeps the hard blocklist for staff names and uses Jev to (a) unblock false positives like
  *Gaylord* and (b) catch `Adm1n`. Creation is a one-off call; cost is nil.
* **Player reports**: on `ReportHandler`, Score **severity** over the report description plus
  the attached chatlog (levels: *no issue / rude / harassment / RMT-advertising / hacking claim*)
  and a Choice **category**. High severity → GM notice is highlighted; low + confident → filed
  quietly. Humans still decide; Jev triages.
* **Live chat guardrails**: optional, *off by default*. Nouls for *RMT / real-money
  advertising*, *targeted harassment*, *sharing personal information*, with a Score for
  severity, thresholded in code to warn / auto-mute / notify GMs. Two cautions from the
  docs: Jev "does not treat data as hostile by default" (players can try to steer it), and
  criteria must be literal. Run it in shadow mode against `ChatLogger` output first.

Privacy: these send player text to a third-party API. On a friends-only server that is
probably acceptable, but it should be a config flag (`ENABLE_TYPESAFE_CHAT_JUDGMENTS`) and
documented in `RUNBOOK.md`, and names/HWIDs never need to be in the state.

#### E. Agent ↔ player trade negotiation
Player replies to a bot's offer ("nah 150k", "sure", "wait what item", "lol no") are a
five-way Choice — *accept / decline / counter-offer / question / unrelated* — plus the
pre-parsed-value pattern for the counter amount (regex finds number candidates, Jev picks
the one that is the price). Fits `AgentPendingOfferChatRouteService` directly.

#### F. SoloMapling social bots that notice you
The ambient bots have canned dialogue lists but no notion of being spoken to. Two cheap
questions per nearby-player message: `addressed_to_this_bot` (Noul) and `tone` (Score:
*hostile / neutral / friendly*). Code then **selects** the best matching canned line from the
existing `List<String>` (a Choice over line ids — "select instead of generate"), or ignores
the message. No text generation, so no Ollama. This is pure believability polish, but it is
also the cheapest way to make the town feel alive.

### Tier 3 — possible later

* **MapleEdit natural-language admin** → typed GM command via the existing queue
  (function-calling pattern, confidence gate + explicit confirmation for destructive commands).
* **Quest reward choice for agents** (`quest-reward-choice-policy.json`): a Choice given the
  agent's build; marginal, the catalog policy already covers it.

### Non-fits (do not try)
Bot economy pricing and rarity (`BotPricingService`, `derive-rarity.sql`), spawn density,
combat/hit math, pathfinding, autoban statistics, anything with times or counts — all numeric
or temporal, which the docs list as Jev weaknesses; they are also already correct in code.
Bot text replies — Jev does not generate; keep canned lines / Ollama.

## 4. Integration architecture

```
server.agents.integration.typesafe
  JevClient            java.net.http + Jackson; POST /v1/systemone; bearer key from env
                       TYPESAFE_API_KEY (never config.yaml — it is committed); timeout ~1.5 s;
                       retry once on 429/529 with backoff; circuit breaker -> fallback
  JevRequest/JevAnswer  small records for state + choice/score/noul questions and answers
  JevUsageMeter         input tokens, latency, error counts -> Server.diagnosticLines()
server.agents.capabilities.dialogue
  AgentChatIntentJudge  builds the fan-out request for a chat message, returns a typed Intent
                        with confidence; SHADOW / LIVE / OFF modes
```

Rules, straight from the docs and the constraints of this server:

1. **Never on the Netty packet thread.** `GeneralChatHandler` → `handleLeaderChat` is
   synchronous today. Judgments go through the existing `AgentChatMailboxDispatcher` /
   reply-queue path so a slow or dead API delays a bot reply instead of stalling a channel.
2. **Fallback is the current code.** If Jev is off, down, or unconfident, behaviour is exactly
   what it is today. Every Jev-backed decision has a config kill-switch.
3. **State stays small and scoped** — the message, who said it, what bots/candidates exist.
   No whole-character dumps (accuracy falls with irrelevant state).
4. **One request per event**, all questions together; read only the answers the chosen
   intent needs.
5. **Thresholds and weights in `agent-engine.yaml`**, with the value/recommended-range
   comments its lint test (`AgentConfigurationBoundaryTest`) expects. Never hardcode.
6. **Golden sets before live.** The regex classifiers have unit tests — those phrases *are* a
   labelled corpus. Add real lines from `ChatLogger`. Measure agreement and calibration in
   shadow mode; only then pick `T_act` / `T_ask`.
7. **Log probabilities, not just decisions**, so a bad call is diagnosable
   (`RuntimeFailureLogger` / diagnostics lines already exist for this).
8. **ASCII in `config.yaml`** (`CHARSET: US-ASCII`) — flags go there; anything richer goes in
   `agent-engine.yaml`.

Sample request for use case A (abridged):

```json
{
  "model": "jev-latest",
  "state": { "message": "leroy can u farm around here", "speaker": "ganre",
             "bots_present": ["Jason", "Leroy"], "map": "Henesys Hunting Ground I" },
  "questions": {
    "intent": { "type": "choice",
      "instructions": { "question": "Which companion command does `message` express?",
                        "inspect": "`message`" },
      "criteria": {
        "grind":  { "what": "hunt monsters anywhere on the map", "not_for": "staying in one spot",
                    "examples": ["grind", "go get exp", "kill mobs"] },
        "sentry": { "what": "stand at the current position and attack only what comes in range",
                    "not_for": "roaming", "examples": ["farm here", "camp", "post up"] },
        "patrol": { "what": "farm this platform and nearby platforms only",
                    "not_for": "the whole map", "examples": ["roam", "wander", "farm around here"] },
        "follow": { "what": "come to and stay with the speaker", "examples": ["follow me", "come"] },
        "chat_not_a_command": { "what": "small talk, questions to other players, anything that is not an instruction to a bot" }
      } },
    "addressed_to_all_bots": { "type": "noul",
      "instructions": "Is `message` meant for every bot rather than one named bot?" }
  }
}
```

Code then resolves "leroy" against `bots_present` (deterministic prefix match, already
implemented), reads `answers.intent.choice` and `.confidence`, and dispatches or asks.

## 5. Suggested first slice

1. `JevClient` + `JevUsageMeter` + config flags (`OFF` by default), ~200 lines, plus a test
   with a canned HTTP response. Needs a TypeSafe API key in the FriendServer `.env`.
2. `AgentChatIntentJudge` in **shadow mode** on the companion chat path: log
   `regex_result | jev_choice | confidence` for every command for a week of play.
3. Review the log together, set thresholds, flip the miss path to live.
4. Then B (Director ranking) — small, self-contained, immediately visible in the panel.

Everything else waits until (A) has proven the client, the thresholds, and the cost in this
server's real traffic.

## 6. Expanding the state: what the server can give Jev, and how

Jev has no memory between requests and no access to the game — **everything it knows about a
situation is the `state` we send with that request** (32k-token budget, processed once, all
questions read it in parallel). So "expanding the state" is the main lever we have for
quality. The docs' rules: group related material so the model can cross-reference it, name
fields descriptively so questions can point at them with backticked paths (`speaker.text`,
`bots[0].name`), put **policy text and history in the state too**, and keep out anything the
question does not need — accuracy measurably falls with irrelevant content.

### 6.1 What already exists in the server, and could be serialized

| Layer | Source in this tree | What it contributes |
|---|---|---|
| Utterance | `GeneralChatHandler`, `AgentQueuedMessage`, `ConversationTurn` (`server.agents.social.contracts`) | the message, channel (map/whisper/party), speaker, whether it was owner-directed, the **last N turns** in that channel |
| Speaker | `Character` | level, job name, GM level, party role, whether they own/lead the bot, relationship summary (`DialogueContextSnapshot.relationshipSummary`) |
| Agent self | journey snapshot schema (`agents/journey/schemas/journey-snapshot-v1`), `DialogueContextSnapshot`, `DialogueStyleSnapshot` | level/job, HP/MP **as bands**, mesos band, current `planId`/`objectiveType`/`careerStage`, activity summary, energy, persona/style, `publicFacts` |
| Field / situation | `AgentPerceptionSnapshot` (mobs, drops, peers, characters, real observers), `MapleMap`, `victoria-map-facts.json` (352 maps: name, street, mob levels, topology) | map name + kind (town / hunting / PQ stage), mob level range vs agent level, crowding, whether real players are watching, danger |
| Party quest | `AgentKpqSession` and siblings | phase, who is leader, who holds which coupon/ticket, what the stage needs |
| Evidence catalogs | `victoria-quest-facts.json` (185 quests), mob-drop facts, `npc-locations.json`, training catalogs, `quest-reward-choice-policy.json` | retrieved **per question** in code (RAG-style), never dumped whole |
| Economy | `StallOfferStore`, `bot_item_prices` / `bot_sales_ledger`, hired-merchant inventories | the standing offer, price band relative to market, item name and category |
| Events / history | `ObserverInterestService` (LEVEL_UP, QUEST_COMPLETE, JOB_ADVANCE, BOSS_DEFEAT, STUCK, ROUTE), `AgentDialogueIntentEvent`, our own past Jev answers | "what just happened" as short text, previous intent decisions in this conversation |
| Policy | config + agent-engine values, PQ rules, ownership rules | the *rules the judgment must respect*, as text, so Jev can cross-reference them |
| Moderation | `ChatLogger` window, `reports` rows, `BLOCKED_NAMES` | the recent chat window around a report, prior report categories (as words, not counts) |

Nothing here needs new data collection; it needs **serializers** from records that already exist.

### 6.2 Transformations the server must do before Jev sees anything

Jev reads text literally, cannot do arithmetic or time ordering, and does not know MapleStory
ids. So the server pre-digests:

* **Numbers → bands with words.** `hp: 143/510` becomes `"hp": "low (below 30%)"`;
  `mesos: 1_250_000` becomes `"mesos": "comfortable"`; mob level 41 vs agent 28 becomes
  `"mob_difficulty": "well above the agent's level"`. Keep the raw number only if a question
  genuinely needs to quote it.
* **Ids → names.** `mapId 104040000` → `"Henesys Hunting Ground I"`; `itemId 2000002` →
  `"Orange Potion"`; jobs → `"Fighter (2nd job Warrior)"`. `ItemInformationProvider`, map
  facts, and `GameConstants.getJobName` already do these lookups.
* **Coordinates → relations.** Never send `x/y`. Send `"speaker_position": "same platform,
  to the left"`, `"nearest_mobs": "3 Orange Mushrooms within attack range"`. The navigation
  graph and perception snapshot already know this.
* **Time → relative words.** `"last_command": "grind, about 40 seconds ago"`,
  `"waited": "long"`; the ordering decision is made in code first.
* **Lists → capped and ranked.** Top 5 candidates, 6 recent turns, 3 nearest mobs — relevance
  filtering is code's job (the docs suggest a Noul relevance pre-pass only when code cannot
  filter).
* **Player text → fenced.** Untrusted chat goes in one clearly named field
  (`"player_message"`), never spliced into instructions, with criteria saying to judge the
  message *as a message*. Jev "does not treat data as hostile by default".
* **Redaction.** No account ids, HWIDs, IPs, real names; character names only where the
  question needs to resolve a target.
* **Observed vs inferred.** Separate `observed` (facts from the server) from `inferred`
  (previous Jev answers, agent's own guesses), and stamp freshness (`"as_of": "just now"`),
  as the skill guidance recommends.

### 6.3 Assembling it: a contributor pipeline with a budget

```
JevStateBuilder
   .add(UtteranceContributor)      // always
   .add(SpeakerContributor)        // always, tiny
   .add(AgentSelfContributor)      // when a bot is the addressee
   .add(FieldContributor)          // when the intent could depend on surroundings
   .add(HistoryContributor(6))     // recent turns + previous decisions
   .add(EvidenceContributor(q))    // catalog rows retrieved for this question only
   .add(PolicyContributor)         // rules text relevant to the question set
   .build(budgetTokens = 3000)     // drops lowest-priority contributors first, logs what it dropped
```

Each contributor declares a priority and an estimated token cost; the builder enforces a
per-use-case budget (a chat command should stay near 1–3k tokens; a Director query with five
candidate maps can afford 6–8k). Token usage comes back in every response (`usage`), so the
meter can tune budgets against real numbers. Because Jev has no cross-request cache, stable
material (persona, style, policy) still costs tokens every time — keep those blocks short and
prefer references ("standard companion rules apply") only where the question does not
actually need the rule text.

### 6.4 What each use case gains from richer state

* **A. Companion commands** — add `recent_turns` (resolves "do it again", "him" → last
  named target), `bot.current_activity` ("grinding", "following", "in a trade") so *stop*
  vs *wait* vs *hold on* land right, `bot.supplies` bands so "need pots" is understood as a
  supply request, `speaker.is_owner` so non-owner chatter is classified as chat.
* **B. Director** — candidates as full `TrainingMapCandidate` rows (level window, terrain,
  recommended agent count, catalog rank) plus the requested level and count: Jev scores fit
  against evidence instead of guessing from map names.
* **C. Party quests** — `stage.phase`, `stage.needs` ("each member must report their coupon
  count"), `who_holds_what`, the speaker's role; the same Nouls then work across KPQ/LPQ/OPQ.
* **D. Moderation** — the ±10-line chat window around a report, the reporter/victim
  relationship in words, prior report *categories* (not counts), and the server's written
  chat policy as text so severity is judged against *our* rules.
* **E. Trade** — the standing offer (item name, quantity, asked price band vs market), the
  bot's minimum as a band, and the last two turns; counter-offers then classify cleanly.
* **F. Social bots** — `persona` from `DialogueStyleSnapshot`, `relationshipSummary`, what
  the bot is doing, and the candidate canned lines themselves (so a Choice can pick the best).

### 6.5 Limits that stay limits

Jev cannot see sprites, positions, or timing — only the text we derive from them — and it
will not remember a player between requests unless we send a summary. Long histories cost
tokens and dilute accuracy, so history belongs in code (a bounded per-conversation window, a
per-player relationship summary that *we* maintain) and is quoted, not replayed. Anything the
server can compute exactly should arrive as a computed word, never as raw data for Jev to
reason over.

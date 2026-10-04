# Server knowledge plan: bots that know this server

Status: steps 1-6 built, not deployed. Plan written 2026-10-01; steps 1-3 implemented and review-fixed 2026-10-01 on
branch `feat/server-knowledge` (worktree `FriendServer/server-knowledge`, based on `feat/companion-manager` @1c2331636),
uncommitted; steps 4-6 specified and built 2026-10-02 (sections 7-9) and review-fixed the same day in the same worktree,
uncommitted. All six deploy together after the merge into the live branch (9.10 and the deploy checklist in
"Implementation notes (steps 4-6)" at the end); open items are in the implementation notes.
FriendServer-specific. Builds on `docs/POPULATED_SERVER_PLAN.md` section 6 (tier 1, "known questions
-> map knowledge", and 6.2 "grounding is the realism lever") and `docs/LIVING_RPG_PLAN.md`.

## 0. Goal and shape

Players ask bots the same things they ask real players: *who drops steelies, where do I buy manas,
where is Athena, how do I get to Ludi, where should a level 35 sin train, how much are ilbis going
for, what level are drakes.* Today every bot answers from nothing. Citizens use a 12-row hand-written
directions table. Companions send the line through a Jev intent call (an API call to the TypeSafe Jev
model, N per line, where N is the number of companions) and then to Ollama with no content facts, so
the model invents stock-MapleStory answers. Residents say "couldn't tell you".

This server is not stock MapleStory, so even a correct wiki answer is often wrong here:

- 41 Skill.wz files are rebalanced (KuroMS) and their String.wz tooltips are stale.
- About 985 equips have zeroed secondary-stat requirements, and any class may wear any gear.
- There are 307 new equips.
- Rates are exp 10x (5x below level 30), meso/drop 3x. Quest EXP is 6x (3x below level 10).
- Drop changesets apply: the KPQ coupon is 80% and the EPQ poison 60%.
- Every character has a Hyper Teleport Rock, Perfect Pitch drops globally, and there is a Fusion Anvil.

The aim is for every bot to be able to answer those questions **truthfully for this server**, in its own
voice, at zero Jev/LLM cost for the common question shapes, and without becoming an encyclopedia: what a
bot knows should look like what a player of its level and home would know.

Shape:

```
 wz/ (String, Map, Mob, Item, Character, Quest)   live DB (drop_data, drop_data_global, shops, shopitems)
        |  KnowledgeExportMain (offline, seconds)          |  loaded at boot, rebuilt on drop/shop reload
        v                                                  v
 scripts/config/knowledge/server-knowledge.json  --> WorldKnowledge (compact, immutable, ~5 MB heap)
 scripts/config/knowledge/aliases.yaml, travel.yaml        |  NameIndex (names + slang), ServerRules (live World rates)
                                                           v
                     ContentQuestionDetector -> ContentAnswers (facts + short deterministic reply) -> KnowledgeHorizon
                                                           |
            +----------------------------------------------+----------------------------------+
            v                                              v                                  v
   citizens: Conversation tier 1.5             companions: social dialogue tier       residents: converse() before Jev
   (voice pack + typing delay)                 (skip the chat-intent Jev call)        + QuestLedger "where" text
                                       all: GroundingRegistry -> content.* facts for Ollama
```

Non-goals for steps 1-3: bots *acting* on the knowledge (step 5), journal/gossip-based personal
knowledge (step 4), fixing the PowerShell catalog exporters (section 9), and any client change.

## 1. What already exists (and what the research measured)

| Piece | State (measured 2026-09-27 .. 10-01) |
|---|---|
| PowerShell catalog exporters (`tools/catalog/Update-AllCatalogs.ps1`) | Ran in the worktree: **27 min, 435 MB of JSON** in `tmp/` (gitignored), 26/26 `CatalogFile` entries, verification INCOMPLETE with 1 warning (86 NPC interaction rows without a WZ placement). WZ-derived facts are right: skill numbers come from the edited Skill.wz, custom names are used, and the zeroed requirements are baked into Character.wz. **Drops and shops are wrong**: only seeds 101/102/152 are parsed, so global drops are missing, the KPQ coupon shows 200000 instead of the live 800000, the EPQ poison 200000 instead of 600000, and Teleport Rock is still listed in shop 1338. The item catalog has no requirements or stats, labels every 5xxxxxx item `pet`, and leaves out 136 unnamed custom equips. SQL is never fingerprinted (`$SqlRoot` default bug). |
| Java catalog layer `server.agents.catalog` (`AgentCatalogService`, `ServerKnowledgeCatalogQuery`) | Loading the full bundle takes **1.5-2.0 s and retains ~547 MB of heap** (npcFastIndexes alone 105 MB). It has no callers in `src/main` and is not usable on the live server. Its query shapes (item -> mob drop sources -> maps) are the model for the compact index. |
| Live content adapters | `CosmicEconomyCatalog` (economy only), `LivingStore.mobsDropping` (a live `SELECT` on `drop_data`), `MonsterInformationProvider`, `ShopFactory`, and the database console reload endpoints (`/internal/admin/cache/drops/reload`, `/shops/reload`). |
| Chat fact hook | `AgentSocialDialogueRequestFactory.Grounding` takes `facts(agentId, speakerId)` only, with no utterance and no map. It is a single global hook installed by population `Conversation`; companions and residents get nothing. Map and job reach Ollama as raw numbers. A fact key over 256 chars throws and the bot silently does not reply. Fact order is lost (`Map.copyOf`). |
| Content-grounded Q&A that works | Only the operator Director (`AgentDirectorDomainContextBuilder`: training-map questions, Victoria 15-30 catalog, honest "no catalog data" boundary, an allowed-set check on model output). |
| Name resolution | None on the input side. `ResidentDialogue.itemIdFor` and `ItemInformationProvider.getItemDataByName` scan all ~12.8k items per call, with no slang. ("steelies", "red pots" and "pig head" return nothing; "snail" returns a cash pet.) `ItemInformationProvider.itemNameCache` is checked but never filled. |
| Server rules | Researched in full (`tmp/knowledge-research/server-rules.md` in the worktree): where each rate and custom feature lives and how to read it live. World rates must be read from `World`, because GM commands and MapleEdit change them without a restart. |

Research reports (worktree `tmp/knowledge-research/`): `exporters.md`, `chat-seams.md`,
`names-slang.md`, `server-rules.md`, `aliases.draft.yaml` (230 slang entries, every id verified
against String.wz).

## 2. Constraints

- **Immersion is non-negotiable.** A level-8 citizen who knows Zakum's drop table reads as a bot. Answers go
  through the speaker's voice pack and typing delay. Knowledge is bounded by a horizon (section 6.4). Bots
  never say "config", "server", "bot", "GM" or rate multipliers unless they are a veteran voice asked directly.
- **Truth over coverage.** If the index cannot answer, the bot says so in character ("no clue, ask in
  henesys"); the model is never asked to fill a factual gap. Ollama gets the facts plus a rule to state
  only the facts it was given.
- **Cost stays flat or drops.** Deterministic tiers cost 0 Jev and 0 LLM. Companion content questions skip
  the chat-intent Jev call, saving N calls per line. Jev is only used for opinion questions or entity ties.
- **Thread budget.** Citizen and resident chat run on the Netty event loop, and grounding runs on the agent
  mailbox / central tick thread. Every lookup is an in-memory hash or array access (target < 50 µs per
  question); nothing touches the DB or WZ at question time.
- **Memory.** Target under 10 MB of heap for the whole knowledge layer (estimated 3-5 MB).
- **Shared tree.** Other sessions edit and build the main checkout. Work stays in the worktree until the user
  decides to merge and deploy. The patch is kept small in hot files (`config.yaml`, `Server.java`,
  `ResidentDialogue`).
- `config.yaml` is decoded as US-ASCII, so no UTF-8 punctuation goes in it.

## 3. Vocabulary

- **Knowledge file**: `scripts/config/knowledge/server-knowledge.json`, the committed, generated snapshot of
  WZ facts.
- **Live overlay**: the drop, global-drop and shop tables loaded from the DB at boot and rebuilt on reload.
- **Entity**: an (kind, id) pair; kinds are ITEM, MOB, MAP, NPC, QUEST, plus REGION and PQ from the alias file.
  Ids are always keyed with their kind (NPC 2043000 is Papulatus, item 2043000 is a scroll).
- **Content question**: a chat line the detector classifies into one of DROP_SOURCE, MOB_DROPS, BUY_WHERE,
  WHERE_IS, ROUTE, TRAIN_WHERE, PRICE, LEVEL_OF (deterministic), or OPINION (generation only).
- **Horizon**: the slice of the world a given bot plausibly knows.

## 4. Step 1: the knowledge file and the live overlay

### 4.1 `KnowledgeExportMain` (offline generator)

A Java `main` in `server.knowledge.export`, like `FieldCatalogMain`. It reads the WZ XML the server
itself loads (the `wz-data` skill grammar). It needs no DB, runs in seconds, and is wrapped by
`tools\knowledge-export.bat`. Output is `scripts/config/knowledge/server-knowledge.json` (target 1-3 MB,
committed, plain JSON for readable diffs) with:

| Section | Fields | Source |
|---|---|---|
| `sources` | for each input dir: path, fileCount, totalBytes, plus `generatedAt` and the exporter version | file listing |
| `maps` | id, name, street (both **trimmed**), region (from the id prefix and street), town flag, hub flag (`town && returnMap == id`), returnMap, mobs `[mobId, spawnCount]`, npcs `[npcId]`, exits `[toMapId]` (portals with a target other than 999999999) | `Map.wz/Map/Map*/*.img.xml`, `String.wz/Map.img.xml` |
| `mobs` | id, name, level, maxHp, exp (raw WZ), boss, undead, `revive[]` | `Mob.wz`, `String.wz/Mob.img.xml` |
| `items` | id, name, category (fixed: equip slot / use / setup / etc / cash / pet), reqLevel, reqJob mask, main-stat reqs, NPC sell price; includes the 136 unnamed custom equips under a placeholder flag so lint can see them | `Character.wz`, `Item.wz`, `String.wz/{Eqp,Consume,Etc,Ins,Cash,Pet}.img.xml` |
| `npcs` | id, name, maps (from map life), plus `scriptSpawns` | `Npc`/`String.wz`, map life |
| `quests` | id, name, start/complete NPC, minLevel, required items `[id,count]`, required mobs `[id,count]`, reward exp/meso | `Quest.wz/{QuestInfo,Check,Act}.img.xml` |
| `scriptSpawns` | mob id, script path, map (when the script is `npc/<npcId>.js`, from that NPC's placement), via-NPC id | literal `spawnMonster*(<id>` calls in `scripts/**/*.js`, about 107 ids |

Rules baked into the exporter:
- Names are trimmed; a 2+-space run collapses (String.wz stores "Mano " etc.).
- Mobs reached only through `revive` inherit the parent's spawn sites (Kid Snowman -> Angry -> Giant).
- No drops and no shops in the file. They come from the overlay, so the file never disagrees with the DB.

**Staleness check at boot**: compare `sources[].fileCount/totalBytes` with a cheap directory listing and log
`[knowledge] stale: <dir> changed since export, run tools\knowledge-export.bat`. The status shows in
`!knowledge status`. A test (`KnowledgeFileFreshnessTest`) re-runs the exporter in memory and fails on any
difference, so CI catches a forgotten regenerate.

### 4.2 Live overlay (`KnowledgeOverlayLoader`)

At boot, after the DB pool is up, three read-only `SELECT`s (each well under 100 ms):
- `drop_data`: 22k rows -> `item -> [(mob, chance, min, max, questId)]` and `mob -> [...]`
- `drop_data_global`: about 6 rows
- `shops` + `shopitems`: 110 shops and 3.9k rows -> `item -> [(shopId, npcId, price)]`, with the NPC's maps
  taken from the knowledge file

The overlay is rebuilt (an atomic snapshot swap) when the database console calls the drops/shops reload
endpoints, and by `!knowledge reload`. Effective drop chance is `chance / 1e6 x` the player's drop rate (the
world rate, read live). Global drops are flat (`MapleMap:800`), so the rate is not applied to them.

### 4.3 Acceptance (step 1)

- The exporter runs in < 60 s and the file is < 3 MB; a boot load of file + overlay takes < 500 ms and
  < 10 MB retained heap (measured in a test, as the research did for the bundle).
- Golden checks, run against the real file:
  - 2070005 = "Steely Throwing-Knives".
  - 5041000 = "Hyper Teleport Rock" and 5900000 = "Fusion Anvil".
  - Custom equip 1152101 "Doom Shoulder" is present.
  - Map 100000000 lists Henesys's NPCs.
  - Mob 9500317 has a script spawn via NPC 9220004 "Happy" at 209080000, and 9500318/9500319 inherit it
    through `revive`.
  - Counts match the WZ: 5,262 maps, ~1,564 mobs, 2,807 quests.
- An overlay test with a fixture DB result: KPQ coupon 4001007 from 9300001 at 800000 (when the fixture says
  so), and Teleport Rock absent from shop 1338.

## 5. Step 2: the runtime knowledge service (`server.knowledge`)

### 5.1 `WorldKnowledge`

An immutable snapshot (file + overlay), published through a static holder (`WorldKnowledge.current()`,
null-safe; `ready()` is false until loaded). Loading happens off the boot thread after the DB is up; until it
finishes, every tier reports "not ready" and existing behaviour is unchanged. Typed, allocation-light API:

```java
Optional<MapFact> map(int id);   Optional<MobFact> mob(int id);   Optional<ItemFact> item(int id);
Optional<NpcFact> npc(int id);   Optional<QuestFact> quest(int id);
List<DropSource> dropSources(int itemId);        // mob, chance, quest-only, spawn maps (incl. script/revive)
List<Integer> spawnMaps(int mobId);              List<ShopSource> shopsSelling(int itemId);
List<Integer> npcMaps(int npcId);                List<Integer> trainingMaps(int level, String region, int limit);
List<Integer> route(int fromMapId, int toMapId); // town-level hops: portal graph inside a continent + travel.yaml
String dataVersion();                            boolean ready();
```

`travel.yaml` is a small authored file of ships, taxis and continent hops (about 15 rows: Ellinia station
<-> Orbis, Orbis <-> Ludibrium / El Nath / Leafre / Ariant / Mu Lung, Lith <-> Nautilus routes...). Its ids
are validated against the knowledge file at load.

### 5.2 `NameIndex` and `aliases.yaml`

- **Universe**: about 12.5k distinct normalized names over 17k (kind, id) refs, excluding hair/face ids,
  Monster Book cards and "MISSING NAME".
- **Normalization**, applied identically to names and chat:
  - lowercase; drop `'`; turn `-` and other punctuation into spaces;
  - fold "60 %", "60 percent" and "60p" into `60%`;
  - collapse runs of 3+ letters to 2;
  - singularise every token (ies->y, es/s rules, irregulars such as knives, mushies/shrooms, octopi).
- **Scroll grammar** `(pct)(slot)(stat)` in any order: "glove att 60", "60% g att", "helm def 10". The % comes
  from the scroll's desc, never from the id.
- **Match order**:
  1. alias (1.0)
  2. exact name (0.95)
  3. token subset or prefix, e.g. "zakum helm" -> Zakum Helmet (0.6 + 0.3 x coverage)
  4. edit distance <= 1 on tokens of 5+ chars, e.g. "henesis", "elinia" (0.5)
- **Canonical preference** when a name has many ids:
  - mobs below 9,000,000 that actually spawn;
  - non-cash, non-event items backed by a shop or a drop;
  - NPCs placed on a field map;
  - maps listed in `population/fields.yaml`, then non-"Hidden Street" maps, then the lowest id;
  - numbered series (Ant Tunnel I-IV) collapse into a region key.
- **Mentions**: the question pattern captures the slot span; otherwise the longest n-gram match (L = 9..1),
  stopping when the prefix set misses (about 150 lookups for a 16-token line).
- **Stoplist**: a single token never resolves alone when it is a grammar/chat word (the, it, pot, pots,
  scroll, drop, map, hp, mp, pt, cc, ks, pc...) or a real one-word name that collides with chat (egg, key,
  note, moon, leaf, red, green, boss, rain, pi, silver, mia...). Those need a kind-typed slot ("where is pi")
  or 2+ tokens.
- **Ties** within 0.1 between distinct entities -> a clarifying question ("blue or red snail?"). Optionally Jev
  picks among <= 5 candidates and is accepted only at confidence >= 0.6.
- **`scripts/config/knowledge/aliases.yaml`** starts from the verified 230-entry draft (steelies, ilbis,
  subis, zhelm, reds/oranges/whites/manas/elixirs/pe, wa pots, pigs, jr necki, zlupins, drakes, wraiths, pig
  beach, ant tunnel, fm, kpq/lpq/opq/hpq, ludi, el nath, CD = Kerning only when typed as a place...).
  Kind-ambiguous aliases (`ht` = Horntail or Horntail Necklace) carry both ids, and the question type picks.

### 5.3 `ContentQuestionDetector`

Deterministic patterns over normalized text. A pattern fires only if its slot resolves to the right kind.

| Kind | Example lines | Slot kind |
|---|---|---|
| DROP_SOURCE | who drops steelies · where does pig head drop · what drops zhelm · where to farm X | item |
| MOB_DROPS | what do zlupins drop · wraith drops? | mob |
| BUY_WHERE | where can i buy manas · who sells steelies · which shop sells bolts | item |
| WHERE_IS | where is athena · wheres the job advancement npc · where is pig beach | npc, map, region, pq |
| ROUTE | how do i get to ludi · which way to kerning · how to get to el nath from henesys | map, region, pq |
| TRAIN_WHERE | where should i train at 35 · where to train lvl 22 sin · best spot to level for a 50 cleric | level and job tokens; default = the asker's own |
| PRICE | how much are ilbis going for · pc zhelm · price check glove att 60 | item |
| LEVEL_OF | what level are drakes · what lvl is kpq · what level for zhelm | mob, item, map, pq |
| OPINION | is pig beach good exp · are steelies worth it | any: facts for the model, no deterministic answer |

Negatives that must never fire: where are you · where r u · where do we go now · where is everyone · how do i
get to level 30 (TRAIN/LEVEL, not ROUTE) · what level are you · how much hp do you have · is it good · what
drops (no slot). Canonical companion-manager phrases and every `AgentChatIntent` canonical form are also
rejected (they are commands).

### 5.4 `ContentAnswers`

For a detected question and the asker's map:

1. **Facts.** An ordered list of `content.*` facts, each <= 256 chars:
   - `content.question`
   - `content.item_source` / `content.shop` / `content.where` / `content.route` / `content.training` /
     `content.price` / `content.level`
   - `content.boundary` when the index has nothing

   They are sorted nearest-first: same map, then same region (by portal-graph distance), then the rest.
2. **Deterministic reply.** A short reply (<= 70 chars by default; residents use the bubble limit), chosen from
   a few templates per kind with stable variation per bot.

Content rules:
- Chances are spoken as buckets (common >= 10%, sometimes 1-10%, rare 0.1-1%, super rare < 0.1%) after
  applying the live drop rate. Exact percentages are kept for a "veteran" voice flag (step 4).
- Mobs that only appear through a script name the NPC ("only the happyville raid snowmen drop those, talk
  to Happy").
- Items with no source at all say so. That case is also a content-lint signal.
- `PRICE` reads the population price index (`living_price_index`, via `Market`). If there is none, the bot
  says it doesn't know what it goes for; it never invents a number.
- Server-rule questions ("whats the exp rate", "can a warrior wear robes") answer from the low-risk subset of
  the rules fact set (section 10), reading rates live from `World`. Only rules marked "low" are stated
  unprompted.

### 5.5 Ops

- `!knowledge <line>` (GM): shows the detection, resolved entities with scores, the facts and the reply,
  without speaking.
- `!knowledge status`: version, staleness, sizes, last overlay rebuild, and counters (questions seen,
  answered, deflected, unresolved).
- Unresolved question lines are logged at INFO with the slot text. That log is the to-do list for
  `aliases.yaml`.

### 5.6 Acceptance (step 2)

- A probe of 60 real-shaped questions (`KnowledgeProbeTest`, using fixture overlay rows) gives the expected
  kind, entity and answer for >= 95%, and every negative line stays silent.
- Resolution finds steelies -> 2070005, zhelm -> 1002357, "glove att 60" -> 2040804, "pig beach" -> the
  Pig Beach maps, "henesis" -> Henesys. "snail" resolves to mob 100100 and never to the cash pet.
- Lookups stay < 50 µs per question in a microbenchmark test; total retained heap stays < 10 MB.

## 6. Step 3: wiring into chat

### 6.1 Composable grounding (shared)

- `GroundingRequest(agentId, speakerId, speakerName, utterance, intentKey, mapId, operationalFacts)` plus a
  `GroundingRegistry` (ordered providers, register/replace by id).
- `installGrounding(Grounding)` stays as a legacy adapter, registered as `"legacy"`. Population registers as
  `"population"`, and the new `ContentGroundingProvider` as `"content"`.
- `publicFacts` collects from the registry with a per-provider try/catch, rejects keys that are blank or over
  256 chars (instead of throwing), stops at 48 facts, and keeps insertion order (`LinkedHashMap`).
- `world.mapId` / `progression.jobId` gain readable siblings `world.map` ("Henesys") and `progression.job`
  ("Hermit").
- `OllamaDialogueProvider` system prompt: when any `content.*` fact is present, add "State only game facts
  listed in Public context; if they don't cover it, say you don't know, in character."

### 6.2 Citizens (`server.living.population.Conversation`)

- A new tier between the WHERE branch and generation. When `ContentAnswers` gives a reply that the horizon
  allows, deliver it through a new `ChatterEngine.say(session, text)`: voice pack + typing delay, map
  channel. Do **not** route it through `chatter().event("enter")`; that path drops lines silently because of
  intent cooldowns.
- `directions()` becomes ROUTE / WHERE_IS answers. The 12 literal rows go away, together with their substring
  bugs ("fm" inside "fml", "job" inside "jobless").
- Content questions still count against the per-citizen cooldown, but not against the generation budget.

### 6.3 Companions

- In `AgentSocialDialogueRuntime.prepareAndSubmit` (after `prepare`, before the model submit), a content
  answer is delivered through the same delivery path as a model reply. It keeps single-responder election,
  the styler and the memory turn.
- `AgentChatIntentRuntime.judgeUnmatched` returns early for `ContentQuestionDetector.looksLikeContentQuestion`,
  so no chat-intent Jev call is made for these lines.
- `AgentTradeDialogueClassifier.matchChoiceCategory` no longer treats "what mobs drop steelies?" as a
  drop/trade command (lines starting with what/which/who/where).
- No new `Agent*Flow` and no new ingress hook (`ChatVerbBoundaryTest` guards both).

### 6.4 Residents

- `ResidentDialogue.converse`: one call before `judged()`, using the original line (punctuation intact).
  `askAbout` falls back to `ContentAnswers` ABOUT when the persona has no lines. `ASK_ABOUT` accepts more than
  one word.
- `QuestLedger.describe` / `personaName` say **where**:
  - KILL_MOBS: "kill 30 Zombie Lupins (Forest of Tree Dungeons, near Ellinia)"
  - VISIT_MAP: the map name instead of the id
  - NPC steps: the NPC's name and town instead of "NPC <id>"

### 6.5 The horizon (minimal immersion guard; the prior under step 4's personal knowledge, section 7)

`KnowledgeHorizon.knows(bot, entity)`. The rules:
- A bot knows an entity if the entity's level (mob level, item reqLevel, map average mob level, quest
  minLevel) is at most `bot level + HORIZON_LEVEL_SLACK` (default 10).
- It always knows its home region and the hub towns.
- Residents also know everything referenced by their own quests and arcs.
- Outside the horizon the bot deflects in voice ("never been that far tbh", "ask someone higher").

Companions use their own level, so a level-20 companion asked about Zakum's helmet deflects, and a level-90
one knows it.

### 6.6 Config

A new `knowledge:` section in `config.yaml` (ASCII only), read by `config.KnowledgeConfig`:

```
knowledge:
    ENABLED: true
    CONTENT_DIR: scripts/config/knowledge
    CITIZENS: true
    COMPANIONS: true
    RESIDENTS: true
    HORIZON_LEVEL_SLACK: 10
    MAX_REPLY_CHARS: 70
    LOG_UNRESOLVED: true
```

Defaults in the class match, so a missing section means "on". Every host path checks `ready()` and its
flag, and falls back to today's behaviour otherwise.

### 6.7 Acceptance (step 3)

- Unit tests:
  - registry composition, key validation and order (`AgentSocialDialogueRequestFactoryTest`, new);
  - Ollama prompt rule (extend `OllamaDialogueProviderTest`);
  - citizen tier delivery (new `ConversationContentTierTest`);
  - companion tier skips the Jev judge (extend `AgentChatFallThroughCommandsTest` / judge tests);
  - trade classifier negative (`AgentTradeDialogueClassifierTest`);
  - resident hook and quest text (new tests);
  - the horizon.
- The existing chat suites stay green: `ChatVerbBoundaryTest`, the route and ingress tests, `PhraseMatcherTest`,
  the Director tests. The baseline failures listed in `shop-resupply-fix` memory are not new regressions.
- Offline live-data probe: a `KnowledgeProbeMain` reads the real knowledge file plus the **live MySQL**
  read-only and prints the answers to the 60-question set, for the user to eyeball.
- Live check (the user's call, needs a deploy): ask a citizen, a companion and a resident the same five
  questions in Henesys; film it per the demo preference.

## 7. Step 4: personal knowledge

Specified 2026-10-02 from `tmp/knowledge-research/design-step4.md`, which has the line-level edit list; where the two
differ, this section wins. Progress log: `tmp/knowledge-research/progress-4.md`.

Built 2026-10-02 on top of steps 6 and 5, uncommitted and not deployed. What was built, the numbers and the deviations are
in "Implementation notes (step 4)" at the end.

The level horizon (6.5) stays as the *prior*. On top of it, what a bot has lived, heard and been told decides what it
admits to knowing. Nobody says anything false: partial knowledge is the realism lever, never misinformation.

What players see:
- Two citizens of the same level answer differently. The one that trained at the subway says "jr wraiths at the subway";
  the one that grew up around Ellinia says "no clue tbh".
- A bot that killed, looted, bought, walked through, overheard or was told something can answer about it afterwards,
  also after a restart.
- A bot that overhears a true answer in map chat learns it. A wrong, mixed or negated answer teaches nothing.
- Party members, circle-mates on the same map and online guild-mates pass first-hand facts to each other, silently.
- Veterans quote exact chances ("like 0.6%") and rate numbers. Everyone else speaks in buckets and says the rates are
  "pretty fast" (section 2: no rate multipliers outside veteran voices).
- Residents know their town, their past and an optional authored `knows:` list. Companions keep today's level prior for
  anything they have not lived; what they live with their owner adds to it.

Gate: `knowledge.PERSONAL` (default true). Off, or for a bot whose known world is not loaded, every answer is
byte-identical to step 3. The registry is started by `LivingWorld.start`, so with `living_world.ENABLED: false` it is off
too.

Decisions:
- **No agents seam.** `LivingWorldHooks` already sees every character's kills (`mobKilled`, whose attacker ids include
  agents), map entries (`Character.changeMapInternal`) and pickups (`Character.pickupItem`, which agents call too).
  Nothing under `server/agents/**` changes.
- **The synthesized past is not stored.** It is a pure function of (seed, level, home region, persona) and is rebuilt at
  open. Only lived and heard entries are written.
- **NPCs are not a recorded kind.** An NPC is known when the map it stands on is known (the town rule, 7.3).
- **Rumours travel one hop.** Gossip passes only first-hand facts (lived or recalled), never heard ones.
- Companion and resident answer lines are not overheard (their delivery has no host hook); citizen and human lines are.

### 7.1 The known world (`server.knowledge.KnownWorld`)

One per bot, bounded, in memory, guarded by its own monitor.

| Part | Content | Bound |
|---|---|---|
| Lived + heard tables | MAP, MOB and ITEM entries: id, count (saturating at 65,535), touch stamp, heard flag | 32 maps, 48 mobs, 64 items |
| Recall (the synthesized past, never stored) | `record Recall(int[] maps, int[] mobs, int[] mobKills, int[] items, int[] spots)`: sorted arrays (binary search), spots newest band first | 128 maps, 64 mobs, 96 items, 40 spots (48 and 48 before the fix pass's common knowledge) |
| Regions | the region of every recorded or recalled map; never shrinks | - |
| Flags | `Past` (UNKNOWN: companions; SYNTHESIZED: citizens and residents), `veteran`, `prices`, `ready` | - |

Rules:
- A map is recorded only when it has a knowledge region and `forcedReturn == 0`, so instances and PQ stages are skipped.
- A full table evicts, among its 8 least recently touched entries, the heard one with the lowest count, else the lowest
  count. Training spots and farmed mobs outlive transit maps.
- A lived touch turns a heard entry lived; a heard touch never downgrades.
- `spots()`: the recalled spots, then lived maps with visits plus kills >= 30, most used first.
- Queries: `knowsMap/Mob/Item`, `heardOnly(kind, id)`, `kills(mob)` (lived plus recalled), `knowsRegion`, `regions()`,
  `spots()`. `firstHand(kind)` lists lived or recalled entries, never heard ones: what gossip may pass.
- Stored row: space-separated `<K><id>:<count><S>` tokens, oldest touch first. K is `M`/`O`/`I` (map, mob, item) and S
  is `L`/`H` (lived, heard), e.g. `M104040000:31L O1210100:412L I4000021:9H`. At most 144 tokens and 4,096 chars.
  `merge` ignores malformed tokens and inserts stored entries as older than the live ones.
- About 3.3 KB per world. Only online, hosted bots are in memory: about 1.1 MB for 240 citizens, 54 residents and the
  companions.

### 7.2 The past (`server.knowledge.PastSynthesis`)

`static Recall synthesize(WorldKnowledge k, Spec spec, Rates rates)`, pure and deterministic:

1. `spec.homeMaps()` go in first. Then each `knows:` entry is resolved with `names().resolveSlot(text, [MAP, REGION,
   PQ, MOB, ITEM])`, accepted only as alias, exact or scroll. MAP, REGION and PQ add up to 8 map ids each; an entry that
   does not resolve logs INFO once.
2. Bands run from the top down (`band = level / 5 * 5`, down to 5 in steps of 5), each with its own
   `SplittableRandom(seed * 0x9E3779B97F4A7C15L + band)`. A band's picks never depend on the current level, so a
   citizen at 55 keeps the spots it had at 50. Per band:
   - region = `spec.regionAt(band)`; spots from `trainingMaps(band, region, 6)`, falling back to victoria and then to
     anywhere; an empty band is skipped;
   - one pick skewed toward the best fit (`rng.nextDouble() * rng.nextDouble() * size`), and a second one with chance
     0.35;
   - each spot, its town, and the town hops of `route(previousTown, town)` when the town changes;
   - the top 2 non-boss mobs of each spot by spawn count. Kills = the EXP of the band's five levels / (mob exp x the rate
     in force, `min(exp, 5)` below level 30), split across the band's mobs;
   - items: the mobs' non-quest, non-global drops with kills x effective chance >= 1, top 6 by expected count.
3. Caps as in 7.1. A full kind stops taking entries, so the oldest (newbie) bands drop off first.
4. At most about 5 ms at level 200, on `LOW_PRIORITY`, never on Netty.

The spec per host:

| Host | seed | regionAt(l) | homeMaps | veteran | prices |
|---|---|---|---|---|---|
| Citizen (`PresenceScheduler.openKnowledge`) | character id | below 10 maple-island; below the home region's `levelMin` victoria; else the home region id | the home region's towns | level >= `VETERAN_LEVEL`, or within 20 levels of it with age >= 120 days and cohort not `afk-townie` (two private constants) | `FLAG_MERCHANT` |
| Resident (`ResidentDialogue.openKnowledge`) | `persona.id().hashCode()` | below 10 maple-island; else the home town's region | the home town | strength VETERAN or ELITE, or level >= `VETERAN_LEVEL` | `knows:` contains `prices` |
| Companion (lazy, `PersonalKnowledge.companion`) | none: Past UNKNOWN, `Recall.NONE` | - | - | level >= `VETERAN_LEVEL`, refreshed per question | false |

Population region ids are the exporter's region keys (victoria, orbis, ludibrium, el-nath, aquarium, ariant, mu-lung,
leafre).

### 7.3 Grading (`server.knowledge.PersonalHorizon`) and answers

`PersonalHorizon` is pure and package-private. `placeKnown(map)` holds when the bot knows the map, or the map is
town-like (`MapFact.town()` or `isTown`) and its town is a known map or its region is known. So everyone knows the towns,
shops and NPCs of every region they have been in; fields, mobs and items become personal.

| Entity | FULL | ROUGH | otherwise |
|---|---|---|---|
| MAP / REGION | an id passes `placeKnown`, or a hub town or the FM (as today) | its region is known | NONE |
| PQ | its lobby map passes `placeKnown` | always (famous) | - |
| NPC / ROLE | one of its maps passes `placeKnown` | that map's region is known | NONE |
| MOB | killed or heard, or it spawns on a known map | a boss (famous), or a spawn map's region is known | NONE |
| ITEM | looted, bought or heard; seen to drop (some mob's kills x effective chance >= 1); or sold by a shop on a `placeKnown` map | - | NONE |
| QUEST, other | the prior | | |

Combining with the prior in `ContentAnswers.grade(c, entity, kind)`, where "better" means FULL > ROUGH > NONE:
- a resident's own quest and arc refs are FULL first, as today;
- UNKNOWN past (companions): better(personal, prior), so a companion never knows less than today;
- LEVEL_OF: better(personal, prior), because levels are talk everyone hears;
- PRICE when the bot has `prices`: FULL;
- otherwise the personal grade alone.

Answer changes apply only when the responder carries a known world; the null path is untouched.

| Answer | Personal rule |
|---|---|
| Drop sources (`dropSource`, `buyOrGet`, `mobDrops`, `mapDrops`) | A row counts when kills(mob) x chance >= 1 or the bot knows both mob and item (UNKNOWN past: also the prior's reach). The `content.*` facts list only counted rows, so the model cannot leak the rest. Item known but no row counted: the shop reply when a known shop sells it, else `drop.unknown` (DEFLECTED), never "nothing drops it". |
| Spawn picks | `bestSpawn` over the known spawn maps when there are any. |
| Shops (`nearestShop`) | SYNTHESIZED: only shops on `placeKnown` maps; when none is left but some exist, `buy.unknown`. |
| Training (`train`, `mapGood`) | Candidates are `spots()` plus `trainingMaps(level, null, 60)`, kept when `placeKnown`, a field and in the level band. None: `train.high` when the asked level is above the bot's, else `train.unknown`. |
| Deflect and rough wording | `deflect` ("ask someone higher") and `rough` ("high lvl tho") only when the subject's level is above the bot's (an item: its lowest non-global dropper, else reqLevel). Otherwise `deflect.unseen` / `rough.heard`, which claim no level. |
| Hearsay | A subject (or top dropper) known only by hearsay fills `{hedge}` from `hedge.heard` ("heard ", "someone said "). |
| Veteran | `bucket.exact[.boss]` with `{pct}`: whole number from 1%, one decimal from 0.1%, else two, "<0.01%" below that. `describe()` facts carry the percent; rate replies keep their numbers. |
| Non-veteran rates | `rules.rates.vague`, with the low-risk rule facts and no numbers. |

New `ReplyBank` keys, ASCII, with `/quiet` and `/proper` where the neighbours have them: `deflect.unseen`,
`rough.heard`, `rough.town.heard`, `drop.unknown`, `mob.drops.unknown`, `buy.unknown`, `train.unknown`, `bucket.exact`,
`bucket.exact.boss`, `rules.rates.vague`, `hedge.heard` (lines in the design, section 7.8). A test lints the wording:
`deflect.unseen`, `rough.heard`, `drop.unknown` and `train.unknown` contain none of `higher|lvl|level|far|past me`, and
nothing outside `bucket.exact*` contains `%`.

`ContentAnswers.Responder` gains a last component `KnownWorld known` (null: the level horizon). The 6- and 5-argument
constructors stay, and `mainMob` becomes package-private for gossip and hearsay.

### 7.4 Recording (`server.knowledge.PersonalKnowledge`)

One registry: `shared()` on the server, `inMemory(...)` for tests and the probe (no store, work on the calling thread).
`on()` = started && `ENABLED` && `PERSONAL` && the snapshot is ready. Every event checks it first, then costs one map
lookup and a scan of at most 64 ints (about 100 ns, no allocation), so events are safe on Netty and agent threads.

API:
- `open(id, Spec)`: host-owned. The world exists at once (not ready); synthesis and the DB merge run on `LOW_PRIORITY`.
- `close(id)`: written at the next flush, then dropped. `forget(id)`: character deleted, so dropped and its row deleted.
- `known(id)`: a ready world or null; never opens. `companion(id, level)`: get-or-open an UNKNOWN world (lazy).
- Events: `visited`, `killed` (mob +1, map +1), `gained` (loot or purchase), `heard(id, entityKeys)` (open worlds only),
  `gossip(teller, learner, seed)`.
- `status()` and `describe(id)` for the GM command.

| Where | Hook |
|---|---|
| `LivingWorldHooks.mobKilled` | each attacker that is an agent (`AgentPresence.isAgent`): `killed(id, mob, map)` |
| `LivingWorldHooks.looted` | an agent and item > 0: `gained` |
| `LivingWorldHooks.mapEntered` | an agent: `visited` |
| `LivingWorldHooks.characterDeleted` | `forget` |
| `PresenceScheduler.materialize` | `openKnowledge` (7.2), then `visited` (the initial placement skips `changeMapInternal`) |
| `PresenceScheduler.logout` | `close`. Virtual sessions are never opened; the hire hand-over does not close. |
| `Market.shop` | after `bought`: `gained` |
| `ResidentRuntime.materialize` / `maintain` / `standby` | `openKnowledge` / `close` / `close` |
| `LivingWorld.start` / `stop` | `start()` (the flush timer) / `stop()` (a synchronous flush after the citizens logged out, while the DB pool is up) |
| `ContentChat` | responders carry `known(id)`; companions use `companion(id, level)`; residents and grounding use `known(id)`; new `hearsay()` |

Write-behind: every 5 min on `SchedulerLane.SAVE`, the dirty worlds are encoded and saved in batches of 200 until none is
left (the fix pass: one batch a round always took the same first 200). A failure marks them dirty again (one warn per
flush); closed, clean slots are then dropped. A world whose stored row could not be read is never written over it; the
next flush reads it again. Lazy companion worlds close after 30
min untouched; hosted worlds never idle-close. A world reopened before its flush keeps its memory and skips the load. At
worst five minutes of lived knowledge are lost on a crash.

`ContentChat`'s 3-argument constructor (the tests' `KnowledgeTestKit.chat()`) gets a registry that is off (`inMemory`
over a config with `PERSONAL: false`), so every step-3 test stays byte-identical. Personal-answer tests use the new
4-argument form.

### 7.5 Overheard answers and gossip

`server.knowledge.Hearsay`, owned by `ContentChat`, keeps open content questions per (channel, map): at most 2 per
place, for 60 s, in at most 512 places.

`answered(channel, map, speaker, line, now)` returns the MAP/MOB/ITEM keys a line truthfully teaches, or nothing:
1. It takes the newest open question there, asked by someone else within 60 s.
2. The line is not a question and has no negation token (not, no, nah, nope, dont, doesnt, never, isnt, arent, wrong,
   idk).
3. It mentions something other than the question's subject.
4. The question's truth set is built once (at most 512 keys) from `ContentAnswers.explain` with an all-knowing
   responder: for an item its sources, their maps and towns, and the shops selling it; for a mob its drops; the places
   of an NPC, map or mob; the route path; the training maps and their main mobs.
5. Every mention must be in the truth set, so a mixed or false claim teaches nothing. At least one must be of a kind
   that answers the question ("im at henesys lol" after "who drops X" teaches nothing).
6. The result is the subject plus the true mentions. An NPC becomes its map, and a region or PQ at most 3 maps.

This costs 50-300 µs, and only for a non-question line with a mention after an open question on that map.

Who learns (`Conversation`):
- In `onChat` from a human, a content question opens a question. Any other line goes through `answered`, and each agent
  within `NEAR_PX` of the speaker learns with chance 0.6 (`teach`).
- A citizen's own answer (`deliverAnswer`, `overheard`) is taught the same way. `overheard` records the question before
  its per-map rate limit.

Gossip (`SocialFabric.gossip`, each tick after `fame`):
- Loaded, non-virtual, non-AFK sessions are grouped by party, by circle on the same channel and map, and by guild (guild
  chat crosses maps).
- A pair's tie is party 1.0, circle 0.7 or guild 0.4, the highest of its groups.
- Chance per pair `0.05 x tie x (sociability(a) + sociability(b)) / 200`; the teller is random; at most 24 transfers per
  tick.
- The fact is one of: a spot and its main mob; a first-hand mob and a drop the teller knows; a first-hand item and its
  first-hand dropper. It is skipped when the learner knows it all.

A party of five moves about 0.5 facts a minute, about 45 over a 90-minute session, bounded by heard-first eviction.
Gossip has no chat line; it shows in later answers.

### 7.6 Persistence

`KnownWorldStore` is package-private and follows the `CitizenStore` idiom over `tools.DatabaseConnection`: `load(id)`,
`save(rows)` (`INSERT ... ON DUPLICATE KEY UPDATE`, one batch), `delete(id)`. It does not use `SqlTime`: timestamps are
BIGINT epoch ms, so the session time zone cannot bite. A missing table logs one warn and the bot runs in memory only.

Changeset `45-living-knowledge` with `db/tables/036-living-knowledge.sql` and `036-living-knowledge-down.sql` (`DROP TABLE
IF EXISTS living_known_world;`), in the pattern of `43-companion-crew-fresh`:

```sql
CREATE TABLE IF NOT EXISTS living_known_world (
    character_id INT    NOT NULL,
    entries      TEXT   NOT NULL,
    updated_ms   BIGINT NOT NULL,
    PRIMARY KEY (character_id)
);
```

- There is no foreign key, as in `companion_performance`.
- The id is 45 and the file 036 because `44-pq-run-pace` / `035-pq-run-pace` (from `r2/int`) is on the live branch
  `feat/companion-manager` since e363b3cec. At the merge 45 goes after 44 (resolved files in
  `tmp/knowledge-research/merge-44/`, see the deploy checklist).
- `DatabaseMigrations` applies it at the deploy boot; the implementer never applies it.

### 7.7 Personas: `knows:`

A persona may carry a top-level `knows: [prices, free market]`. Entries are names that NameIndex resolves by alias or
exact, or the word `prices`. `ContentLoader` reads them into a new `LivingContent.personaKnows`, exposed as
`LivingContent.knows(id)`. It gets an 11-argument constructor; the 10-argument one delegates with `Map.of()`, so the
`Persona` record and its 22 test sites are untouched.

Seed content:
- tavi (Market Runner): `[prices, free market]`
- lorca (The Fence): `[prices]`
- sable (Cartographer): `[ellinia, perion, kerning city, sleepywood, lith harbor, nautilus]`
- oda (Station Master): `[orbis, ellinia station]`
- pell (Lupin Trapper): `[zombie lupin, lupin]`
- thane (Dungeon Guide): `[ant tunnel, sleepywood]`

`PersonaKnowsResolveTest` checks every shipped entry resolves; step 6 adds the content-lint WARN for editors (9.4).

### 7.8 Config and ops

`KnowledgeConfig` and the `config.yaml` `knowledge:` block gain, after `LOG_UNRESOLVED` (ASCII):
- `PERSONAL: true`: bots answer from what they have lived and heard; off, the level horizon only and nothing is recorded.
- `VETERAN_LEVEL: 70`: at or above, exact chances and rate numbers. `validate()` requires 1..200.

`!knowledge known <name>` prints the world: past, flags, counts per kind (lived, heard, recalled), the top 5 spots and
the last 5 heard. `!knowledge @<name> <line>` dry-runs as that character. `!knowledge status` adds
`PersonalKnowledge.status()`.

### 7.9 Tests and acceptance (step 4)

New tests are in `server/knowledge/` unless noted, over `SeedOverlay.knowledge()` and `Rates(10, 3, 3, 3, 4f, 4)`:

| Test | Asserts |
|---|---|
| `KnownWorldTest` | the caps after 100k random touches; eviction order; heard/lived upgrade; saturation; encode/merge round trip and order; malformed tokens ignored; <= 4,096 chars; regions survive eviction |
| `PastSynthesisTest` | 200 seeds are deterministic; a level-50 recall contains level 45's maps; the caps at level 200; the region fallback; spot mob levels within [band-8, band+5]; p95 <= 10 ms warm |
| `PersonalHorizonTest` | the 7.3 grade table, case by case |
| `PersonalAnswersTest` | (1) "who drops blue snail shells": A (300 kills of 100101) is ANSWERED naming blue snails, B (Perion stumps) is DEFLECTED with `deflect.unseen`; (2) each bot's training answer names its own spots; (3) a bot that heard {4000000, 100101} answers; (4) an empty companion equals `known = null` on every `KnowledgeProbeTest` positive except the rate lines; (5) `%` only from veterans; (6) rate digits only from veterans; (7) an unvisited Victoria field gives `rough.heard`; (8) every MOB/MAP a personal answer mentions is known to that bot or lies on the route path |
| `PersonalKnowledgeTest` | open and ready, lazy companions, at most 200 rows per flush in one call, close then drop, failure keeps dirty, a reopen survives, idle close, gossip passes first-hand keys only, deterministic per seed |
| `HearsayTest` | true answers teach; false, mixed, negated, question, self, expired (61 s) and definition-less lines teach nothing; a MOB_DROPS and a ROUTE case |
| `KnownWorldBudgetTest` | 400 full worlds retain <= 1.5 MB; <= 50 µs per question with a full-world responder |
| `server/living/population/SocialGossipTieTest` | the tie per group, and the max for a pair in two groups |
| `server/living/config/PersonaKnowsResolveTest` | every shipped `knows:` entry resolves, in order |

Acceptance:
1. **Different histories, different answers**: `PersonalAnswersTest` (1)-(3), and in the probe A and B differ on at
   least 10 lines.
2. **Partial, not empty**: A answers at least 40% of the lines the legacy level-35 peer answers, and at least 90% of the
   WHERE_IS, ROUTE, BUY_WHERE and JOB_ADVANCE lines about Victoria towns and their NPCs.
3. **Never misinformation**: (8) holds for 100% of personal answers, and `HearsayTest`'s 7 negative cases learn nothing.
4. **Fallback exact**: the empty companion equals legacy on 100% of the probe lines except rate lines. With `PERSONAL:
   false` every existing test and probe line is byte-identical to step 3 (`probe-live.txt`: 108 of 216 answered, all 107
   negatives silent).
5. **Bounded and cheap**: the 7.1 caps; at most 1.5 MB per 400 worlds; at most 50 µs per question; synthesis p95 at most
   10 ms, off Netty; 0 DB calls on Netty or in question handling; at most 200 rows per flush and one flush per 5 min.
6. **Persistence**: the round trip, close-then-drop and failure-keeps-dirty cases pass. The changeset has an up and a
   down file and is not applied by the implementer.
7. **Boundaries**: as 9.10. The boundary, architecture and lint sets equal the step-3 baseline (10 F + 1 E).
8. **Probe**: `KnowledgeProbeMain` gains a "personal knowledge" section with four bots: A 9101 (lv35, victoria), B 9102
   (lv35, home ludibrium, homeMaps 220000000), V 9103 (lv80, el-nath, veteran) and K 9104 (companion lv35, one visit to
   103000000). It prints legacy | A | B | V | K per line plus the answered counts, the A/B differences and the K/legacy
   differences, to `tmp/knowledge-research/probe-live-step4.txt` (live DB read-only).

Commands:
- `.\mvnw.cmd -q test "-Dtest=KnownWorldTest,PastSynthesisTest,PersonalHorizonTest,PersonalAnswersTest,PersonalKnowledgeTest,HearsayTest,KnownWorldBudgetTest,SocialGossipTieTest,PersonaKnowsResolveTest" -Dsurefire.failIfNoSpecifiedTests=false`
- Legacy guard: `.\mvnw.cmd -q test "-Dtest=server/knowledge/**,ConversationContentTierTest,ResidentContentHookTest,QuestLedgerWhereTextTest,ContentLoaderTest" -Dsurefire.failIfNoSpecifiedTests=false`

Left out: visible gossip lines; learning from companion and resident answer lines; learning prices or levels from chat;
depth beyond one hop and "bob said"; NPC-talk and quest events; per-map kill counts; any exporter or `KnowledgeHorizon`
change.

## 8. Step 5: decisions on the same index

Specified 2026-10-02 from `tmp/knowledge-research/design-step5.md` (edit list and traces there; this section wins where
they differ). Progress log: `tmp/knowledge-research/progress-5.md`.

Built 2026-10-02 on top of step 6, uncommitted and not deployed. What was built, the numbers and the deviations are in
"Implementation notes (step 5)" at the end.

| Decision | Value for players | Verdict |
|---|---|---|
| FARM_ITEM / "go farm X" | High. Today "lisa farm steelies" gets "i don't know what 'steelies' is"; a companion farms its camp even when nothing there drops the item; the ledger credits script-only Fairy Wings (about 47 per 6 h) and Zakum Helmets to a level 30 (about 356). | **Build** (8.3) |
| Citizen gear by level | High. Every citizen above lv40 wears its lv30-40 band gear, up to its level cap of 70-85. | **Build**, citizens only, behind `CITIZEN_GEAR` (8.4) |
| Population TRAIN map choice | Low-medium. FieldCatalog already uses the WZ-generated fields.yaml; the only job signal in the data is undead monsters for Heal. | **Build** the healer preference only (8.5) |
| Travel ETAs off the Victoria roads | Low-medium. An Ossyria-to-Ossyria trip counts as off-island: a 180 s recall, and citizens stand still for 45 s. | **Build** a knowledge fallback for maps outside `ROADS` (8.6) |
| Resupply supplier fallback | None. The agents' router was Victoria-only and its suppliers were the five first-job grocers; since 2026-10-03 the Agents have their own world graph and potion-supplier catalog (8.7). | **Skip** (8.7) |
| Agent training outside Victoria 15-30 | None. The objective refuses levels outside 16-30 before the selector runs, and movement is Victoria-only. | **Skip** (8.7) |

Scope:
- Nothing under `server/agents/**` changes: no seam, no `AgentTuning` key, and the boundary counts stay where they are.
  Only `server.living` (which may import knowledge) and `server.knowledge` change.
- There is no schema change and no exporter change. Every fact needed is already in the file: `MobFact.undead`,
  `ItemFact.reqLevel/reqJob/req*/category/cash/unnamed`, and the drop/shop overlay.
- Decisions read the whole snapshot, not the bot's known world (step 4). They are actions the owner asked for, not
  claims, and refusal lines only name the item's own droppers or its shop NPC.

### 8.1 Gate and config

`server.knowledge.Decisions.active()` returns the snapshot when `ENABLED && DECISIONS` and it is ready, else null.
`citizenGear()` is `active() != null && CITIZEN_GEAR`. Every call site reads `WorldKnowledge k = Decisions.active(); if
(k == null) { today's code }`.

`KnowledgeConfig` and `config.yaml` gain:
- `DECISIONS: true`: FARM_ITEM sites and the honest ledger, healer fields, off-Victoria travel times.
- `CITIZEN_GEAR: true`: generated citizens wear obtainable gear that fits their level and job.

`validate()` gains nothing for these.

### 8.2 Shared rules: wild monsters and obtainable items (also step 6)

`DropSource.scriptOnly` is true for every mob without a placement of its own, including revive children of field mobs
(3400004 Yeti Doll, the revive of the placed 3400003, has no script spawn). It cannot decide "farmable". Steps 5 and 6
share two `WorldKnowledge` methods instead:

```java
/** A monster players meet in the open: placed by WZ or DB map life, or revived from a wild one.
 *  Script-only mobs and their revives are not. */
public boolean wild(int mobId)
/** Players can get it in the open: a shop sells it, it is a global drop, or a wild monster drops it outside a quest. */
public boolean obtainable(int itemId)
```

`wild` is built in the snapshot constructor from `placed`, with the same revive fixpoint as the spawn sites. Whichever of
steps 5 and 6 lands first adds both, with `WorldKnowledgeDecisionsTest` cases: 100100 and 3400004 are wild, 9500317,
9500318 and 9500319 are not; 4000019 and 4310000 are obtainable, 4003003 is not.

### 8.3 FARM_ITEM

`server.knowledge.FarmSites`, pure over a snapshot:

```java
public enum Verdict { FARM, ANY_MONSTER, CASH, SHOP_ONLY, NO_SOURCE, SCRIPT_ONLY, QUEST_ONLY, TOO_STRONG, BOSS_ONLY }
public record Site(int mapId, DropSource source, int distance, String mobs, String place) { }
public record Plan(Verdict verdict, List<Site> sites, String mobs, String npc) { }
public static Plan plan(WorldKnowledge k, int itemId, int level, int anchorMapId, boolean questDrops)
public static DropSource creditSource(WorldKnowledge k, int itemId, int level, int campMapId, boolean questDrops)
```

Each `dropSources(item)` row is classified in this order:

| Row | Class |
|---|---|
| global | kept aside; counts only for the anchor's or camp's continent |
| mob not `wild` | SCRIPT_ONLY (npc = `ContentChat.npcPlace(viaNpc)` when set) |
| `questId != 0` and not `questDrops` | QUEST_ONLY: a bot never holds its owner's quest, so it never sees those drops |
| mob level above level + `TRAINING_LEVELS_ABOVE` (5; the `WorldKnowledge` constant made package-private, not a second constant) | TOO_STRONG |
| boss | BOSS_ONLY: timed spawns, not a farm |
| otherwise | fits. Its field sites are its maps with a region, `forcedReturn == 0`, not a town and `fieldCapacity != 0`; with none it counts as SCRIPT_ONLY (event and PQ copies) |

`plan`:
1. An unknown item is NO_SOURCE; a cash item is CASH.
2. The field sites of fitting rows, deduplicated by map (best chance kept), sorted by `distance(anchor, map)`, then
   chance, then id. Unreachable sites are dropped and the first 5 make a FARM plan.
3. With no site but a global drop on the continent: ANY_MONSTER (Perfect Pitch, Chaos 60%).
4. Otherwise the first non-empty of QUEST_ONLY, TOO_STRONG, BOSS_ONLY, SCRIPT_ONLY, then SHOP_ONLY (npc = the shop NPC
   nearest the anchor), then NO_SOURCE.

`mobs` comes from `names().say(MOB, id)` and `place` from `ContentChat.mapPlace`. `mapPlace` and `npcPlace` read the
published snapshot, so `FarmSitesTest` runs inside `KnowledgeTestKit.publishSeed()`.

`creditSource` returns a fitting dropper that spawns at the camp (best chance), else the best fitting one with any field
site, else a continent global; null when nothing the bot could farm drops it. `plan` costs under 1 ms (one cached
route search per anchor) and `creditSource` under 50 µs.

Hosts:
- **`RosterService.farmPick(persona, item, preferred, anchor, here)`** returns a private `FarmPick(mapId, said,
  refusal)`, or null when knowledge is off (today's rules). The level is the character's, else the persona band's min,
  as in `fieldCampFor`.
  - FARM: `preferred` when it is one of the sites. With `here` and no match it refuses (NOT_HERE). Otherwise it takes
    the first of at most 3 sites that loads as a non-town map and that `roughCampFor` does not rule out.
  - ANY_MONSTER: today's flow, including the town refusal.
  - Every other verdict, and FARM with no usable site: a refusal with the line below.
- **`assign` (FARM_ITEM)**: anchor = the camp, else the home town. A refusal writes nothing. A moved camp writes the
  `roster.camp` event (as `moveCamp` does, so the rough-camp record restarts) and the Ok line adds `" at <place>"`.
- **`delegate` (FARM_ITEM)**: `here` = a map was named. Anchor = here, else the owner's map when ACTIVE, else the work
  map. A refusal returns before any write or walk-out. An ACTIVE companion whose site moved says
  `"<said>. heading there."`. The benched Ok line keeps "at map N".
- The card path (`CompanionCardScreens` -> `CompanionManager.assign`) gets the same behaviour with no change.

Refusal lines, as `Failed(name + ": " + line)` in the lowercase roster register:

| Verdict | Line |
|---|---|
| CASH | that's cash shop stuff, nothing drops it. |
| SHOP_ONLY | nothing drops those. {npc} sells them. ({npc} spoken: "tulcus in kerning city") |
| NO_SOURCE | nothing drops those that i know of. |
| SCRIPT_ONLY | only the {mobs} drop those, {npc} calls em up. can't farm that. (no NPC: only some event monsters drop those. can't farm that.) |
| QUEST_ONLY | those only drop from {mobs} while you're on the quest. |
| PQ_ONLY (fix pass) | those only drop inside {pq}. run it with me? |
| TOO_STRONG | those only drop from {mobs}. way too strong for me. |
| BOSS_ONLY | those only drop from {mobs}, and that's a boss hunt, not a farm. |
| NOT_HERE | nothing here drops those. {mobs} at {place} do. |
| no reachable site | those drop from {mobs}, but i can't get to them. |

The ledger and the command:
- `BenchLedger.farmItem(..., botLevel, questDrops)` takes its chance from a package-private
  `chancePerKill(item, level, camp, questDrops)`. With knowledge it is `creditSource(...).effectiveChance(dropRate,
  bossDropRate)`, 0 when null, with no DB query. Without knowledge it is today's `mobsDropping` top row. FARM_ITEM
  passes `questDrops = false`; `gather` (the owner's active quests) passes true and gains `botLevel`.
- `ResidentDialogue.itemFor(owner, persona, text)`: with knowledge off it calls the legacy `itemIdFor`. A numeric id is
  used as is. Otherwise `resolveSlot(text, [ITEM])`; a tie asks "x or y?" and places no order. There is no legacy prefix
  fallback with knowledge on, because that scan turns "snail" into a cash pet. The FARM_ITEM regex and its count rules
  are unchanged.

### 8.4 Citizen gear by level

Today shaping runs only when a citizen is below its target level, SQL level bumps skip it, and careers.yaml bands end at
lv30-40 loadouts.

`server.knowledge.GearPicks.pick(k, category, bandLevel, jobMask, gender, weaponType, stats, seed)` returns the best equip
of a category (cap, coat, longcoat, pants, shoes, glove, cape, shield, weapon):
- reqLevel in (band - 20, band];
- named, non-cash, not an unnamed custom equip, and `obtainable` (8.2), so boss drops count and event, script and
  quest gear does not;
- reqJob 0 or sharing a bit of `jobMask` (job-specific first); gender by the id digit (`id / 1000 % 10`: 0 male, 1
  female, else both); main-stat requirements within the base stats; a weapon only of its type (`id / 10000`);
- among the picks within 5 levels of the best, one stable for the seed; 0 when none fits.

It scans `items()`, about 1 ms for 8 slots, with no cache.

`server.living.population.CitizenGear.upgrade(agent, job, loadout)` (package-private):
- It skips when `!Decisions.citizenGear()`, below level 10, for Beginners, and for job branches outside 1-5.
- band = level / 10 * 10; jobMask = `1 << (branch - 1)`; seed = `id * 31 + slot.hashCode()`.
- Slots: cap -1, top -5 (longcoat when an overall is worn, else coat; pants are skipped under an overall), pants -6,
  shoes -7, glove -8, cape -9, shield -10, weapon -11. Weapon and shield only when one is worn, and the weapon keeps its
  type, so mastery, the SP build and ammo stay valid.
- `upgradeFor` returns a strictly higher reqLevel or 0, never a downgrade. The pick is added and equipped with
  `AgentEquipmentService.equipInSlot` (as `ResidentWardrobe` does). The replaced piece is removed unless it is in the
  authored loadout; a pick that did not equip is removed again. `saveCharToDB` runs once when anything changed.
- Call sites: `ProgressionLedger.catchUp` after the shaping block, unconditionally (it is idempotent, so SQL-bumped
  citizens catch up), and `CitizenGenerator.shape` after the loadout.
- Not for Residents, hires (`catchUp` already returns for them) or companions.

### 8.5 Healer fields

`WorldKnowledge.undeadShare(map)` is the share of a map's spawn points held by undead monsters, 0 for an unknown or empty
map. 45 trainable fields.yaml fields are undead-majority: Ant Tunnel, the Kerning subway, Sleepy Dungeon, the Excavation
Camps, Forest of Dead Trees, Path of Time and others.

`FieldCatalog.trainingField`: for a CLERIC, PRIEST or BISHOP career at level 30 or more, after the capacity and occupancy
filter and before the social preference, 70% of the time the candidates narrow to `healerFields(candidates,
Decisions.active())` (undeadShare >= 0.5) when that list is not empty. The constants are private in FieldCatalog (living
code, so the agents constant lint does not apply). Companion camps are untouched.

### 8.6 Travel ETAs off the Victoria roads

`WorldKnowledge.rides(from, to)` counts the travel legs on the route other than guide and elevator hops; -1 when
unreachable.

`RecallTravel`:
- The two `OFF_ISLAND_MS` returns become `offRoads(from, to)`: with knowledge active and `rides == 0`, `BASE_MS +
  PER_HOP_MS x (route hops - 1) + FIELD_EXTRA_MS` (no extra for a town); otherwise `OFF_ISLAND_MS`.
- `etaMs(..., cap)` caps a trip that is on the Victoria roads (checked first, so Victoria is byte-identical) or walkable
  by knowledge. Decision 7's 60 s same-continent cap then applies in Ossyria; a trip that needs a ride stays 180 s.
- No movement change and no ship timetable.

### 8.7 Skipped

- **Resupply supplier fallback.** `AgentCareerShopCatalog.forSupply` filters suppliers by
  `AgentVictoriaRouteRuntime.routeHops`, and travel is Victoria-only, so a knowledge supplier off Victoria would never be
  chosen. Inside Victoria no other grocer is on the router graph. Revisit with a non-Victoria router: then
  `shopsSelling(potions)` by `distance`, through the same filter.
  *Done another way (2026-10-03, feat/shops-beyond-victoria):* the Agent router walks every continent
  (`world-portal-route-graph.json`) and, for a caller that opts in, rides the NPC travel legs (`npc-travel-legs.json`), and `forSupply` picks from
  the Agents' own `potion-supplier-catalog.json` (31 town potion shops, checked against the shop SQL by a test), so
  `server.agents` still reads no server knowledge.
- **Agent training outside Victoria 15-30.** `start`/`reattach` refuse levels outside 16-30. The selector's maps need
  `TrainingMap` records and Victoria routes, and companions, citizens and residents never use this path.
- Also left out: dropper targeting inside the live FARM_ITEM slice (it stays a free grinder at the camp); GATHER's
  targeted visit, still one `mobsDropping` per visit (a small follow-up); resident and companion gear; elemental maps;
  job-aware companion camps; "farm glove att 60" (the trailing number stays a count).

### 8.8 Tests and acceptance (step 5)

| Test | Asserts |
|---|---|
| `WorldKnowledgeDecisionsTest` | `undeadShare` 105040302 = 1.0, 101030105 = 1.0, 100010000 = 0, unknown 0; `rides` Orbis -> El Nath 0, Orbis -> Ludibrium >= 1, Henesys -> Orbis >= 1, same map 0, unknown -1; the 8.2 cases |
| `FarmSitesTest` (inside `publishSeed`) | Snail Shell 4000019 at lv10 from Henesys is FARM, first site spawns 100100 within distance 6 and has a place; Fairy Wing 4003003 SCRIPT_ONLY naming Happy, credit null; 5041000 CASH; Zakum Helmet 1002357 TOO_STRONG at 30, BOSS_ONLY at 150, credit null at both; 4032340 QUEST_ONLY (FARM with questDrops); a shop-only id is SHOP_ONLY (preconditions asserted first); 4310000 ANY_MONSTER with a global credit; credit prefers the camp's own dropper; 200 plans average < 1 ms, credit < 50 µs |
| `GearPicksTest` | a warrior one-handed sword at band 70 is type 130, reqLevel 51-70, non-cash, obtainable; female picks are never male ids; reqJob fits; base stats of 4 give 0 or an item with reqs <= 4; stable per seed, at least 2 coats over seeds 0-20 at band 50 |
| `server/living/population/CitizenGearTest` | a lv75 FIGHTER wearing 1302020 gets a type-130 reqLevel 51-70 pick; no sidegrade from a reqLevel-70 weapon; 0 at lv9 and for Beginners; the flag off changes nothing |
| `server/living/population/FieldCatalogHealerTest` | `healerFields` keeps only the undead-majority fields; over 200 seeded draws a lv40 cleric picks undead fields at least 60% of the time and a lv40 FP wizard at the base rate |
| `server/living/roster/RecallTravelKnowledgeTest` | Orbis -> El Nath under 180 s and capped to 60 s; Orbis -> Ludibrium 180 s; Lith -> Henesys 60 s as in `RecallTravelTest`; unpublished, Orbis -> El Nath is 180 s |
| `server/living/roster/FarmItemSiteTest` | assign moves a town camp to a snail field, names the place, writes `roster.camp`; Fairy Wing is refused ("can't farm that") with the row unchanged; delegate "here" on a map without snails is refused with no walk-out; an ACTIVE delegate goes AWAY and says "... heading there."; unpublished keeps today's behaviour |
| `server/living/roster/BenchLedgerFarmCreditTest` | `chancePerKill` is 0 for 4003003 at lv70 and 1002357 at lv30, above 0 for 4000019 at a snail field; unpublished falls back to `mobsDropping` |
| `server/living/resident/ResidentFarmCommandTest` | "farm steelies x200" assigns 2070005 x200; "go farm red pots" delegates 2000000; a tie asks back; "farm qwzx" keeps the old message |

Acceptance:
- **FARM_ITEM**: "farm steelies", "farm red pots" and "farm pig heads" resolve (none does today), and "farm snail
  shells" is 4000019. Every `FarmSitesTest` verdict is right. Per 6 h accrual Fairy Wing goes from 47 to 0 and Zakum
  Helmet at lv30 from 356 to 0. With knowledge on there are 0 `mobsDropping` calls per ledger tick.
- **Gear**: a lv75 FIGHTER shaped with `perion-warrior-l30` wears a type-130 weapon of reqLevel 51-70. Each worn armor
  slot with a candidate reaches reqLevel 41 or more. It never wears a cash item, its EQUIP count does not grow for
  replaced pieces, and `upgrade` costs under 5 ms per citizen.
- **Healers**: lv30+ clerics draw undead-majority fields at least 60% of the time when one is in band; other careers are
  unchanged.
- **Travel**: an Ossyria same-continent recall is at most 60 s (was 180 s); Victoria ETAs are byte-identical; trips that
  need a ride stay 180 s.
- **Flags**: `DECISIONS: false` reproduces today's behaviour in all four builds and `CITIZEN_GEAR: false` stops gear
  changes.
- **Existing suites** stay at the baseline: RecallTravelTest, RecallCapTest, RosterResultTest,
  RosterServiceCharacterizationTest, DelegateWalkOutTest, CompanionManagerTest, CompanionCardScreensTest,
  BenchLedgerRatesTest, BenchUpkeepTest, BenchGatherFormTest, BenchDutyDispatcherTest, ResidentContentHookTest,
  ResidentShaperTest, PopulationContentTest, ChatVerbBoundaryTest.

Risks:
- **The ledger gets stricter.** After deploy, existing FARM_ITEM and GATHER rows for script-only, quest-only (FARM_ITEM
  only) or over-level items stop accruing. The Fairy Wing quest can then no longer be GATHERed, so the section 11
  content decision becomes live.
- **Camps move on assign**, and the move persists after the duty ends. It is logged as `roster.camp`.
- **Gear writes persisted characters**: up to 600 citizens gain 1-8 items at their next load. It is idempotent and never
  downgrades. Turning the flag off leaves the gear on.
- A site the file lists may not load as a map on this server; `farmPick` checks `residents().map()` and `isTown()`.

Commands:
- `.\mvnw.cmd -q test "-Dtest=FarmSitesTest,WorldKnowledgeDecisionsTest,GearPicksTest,CitizenGearTest,FieldCatalogHealerTest,RecallTravelKnowledgeTest,FarmItemSiteTest,BenchLedgerFarmCreditTest,ResidentFarmCommandTest" -Dsurefire.failIfNoSpecifiedTests=false`
- `.\mvnw.cmd -q test "-Dtest=server/knowledge/**,server/living/roster/**,server/living/population/**,server/living/resident/**,*Boundary*Test,*Architecture*Test,*Lint*Test" -Dsurefire.failIfNoSpecifiedTests=false`,
  compared with the known-failure list.

## 9. Step 6: content lint and exporter fixes

Specified 2026-10-02 from `tmp/knowledge-research/design-step6.md`. Its numbers come from a Python model over the
committed file, content and seed SQL; the implementer re-measures them in Java and explains any difference. This section
wins where the two differ. Progress log: `tmp/knowledge-research/progress-6.md`.

Built 2026-10-02, uncommitted and not deployed; the Java measurements match 9.5 and 9.6 exactly. What was built, the
numbers and the deviations are in "Implementation notes (step 6)" at the end.

Three parts:
- **(a)** living-world and population content lint against the knowledge file;
- **(b)** the PowerShell catalog exporters read a Liquibase replay instead of three seed files;
- **(c)** the OPQ/LPQ lobby ids.

There is no config key, no change to the knowledge exporter or file (so no re-export), no agents code and no MapleEdit
change. MapleEdit shows names next to ids because the lint text it already displays carries them.

### 9.1 How the lint reaches MapleEdit

| Path | Runs | Drop and shop rows |
|---|---|---|
| Save (`routes_living.py` `lint_with_edit`) | `ContentLintMain <staged>` or `PopulationLintMain <staged-population> <living-world>`, via `java -cp target/Cosmic.jar` with cwd = the server checkout and a 120 s timeout | offline: the knowledge file plus the Liquibase seed replay |
| Reload (`/actions/living-reload` -> `AdminControlPort.aLivingReload`; `!world reload`, `!world lint`, `!citizens reload`) | `ContentLint.check` / `PopulationLoader.lint` on the server | the published snapshot, with the live DB overlay |

Findings keep today's format: two leading spaces, `ERROR` or `WARN`, then `<file + where>: <text>`. MapleEdit's
`_parse_lint` ignores every other line, and any ERROR blocks the save (HTTP 400). Only unknown ids are ERRORs, so real
content never blocks a save.

### 9.2 `server.knowledge.SeedData`: the Liquibase data, offline

`replay(Path dbRoot)` returns the `drop_data`, `drop_data_global`, `shops` and `shopitems` rows a fresh database holds
after Liquibase, plus the changesets that changed them and what was not applied. `overlay(replay)` turns them into a
`KnowledgeOverlay` exactly as `KnowledgeOverlayLoader` reads the tables (globals with chance > 0, shopitems joined to
shops). `load(dbRoot)` does both; `DEFAULT_ROOT = "src/main/resources/db"`.

- **Order** is `changelog-root.xml`'s: `<include>` in document order, an `<includeAll>` folder's `*.xml` by file name.
  In each changeSet the direct `sqlFile`, `sql`, `insert`, `update` and `delete` children run in order; `<rollback>`
  never runs. Paths resolve against the changelog (`relativeToChangelogFile`) or the resources root. XML is parsed with
  doctype declarations disallowed.
- **SQL**: comments are stripped and statements split outside quotes. The applied shapes are `INSERT INTO t (cols)
  VALUES ...`, the `INSERT ... SELECT ... WHERE NOT EXISTS` idiom, `UPDATE t SET c = v WHERE a = x [AND ...]` and
  `DELETE FROM t WHERE ...`, all with literal values.
- **Not applied**: DDL and other tables are skipped silently (step 4's changeset included). Any other data change on the
  four tables (`REPLACE`, an expression, `OR`/`IN`, `loadData`) is a warning `<file>: changeset <id>: not applied:
  <first 100 chars>`. A watched change under `<preConditions>` is applied, with the warning "preconditions not
  evaluated".
- **Expected today**: no warnings. Applied: 101, 102, 151, 152, 162, 163, 200, 2026-07-15-kpq-coupon-chance,
  2026-08-20-kpq-coupon-chance-80, 2026-09-05-epq-poison-bottle-chance-60. Rows: drop_data 22,157, drop_data_global 6,
  shops 110, shopitems 3,881 (3,882 inserted, 1 deleted by 200). (9300001, 4001007) = 800000, (9300173, 4001161) =
  600000, and shop 1338 has 25 rows and no 5041000. The live DB had 3,891 shop rows: about 10 are operator edits the
  seed cannot know.
- A replay takes under 1 s warm.

`server.knowledge.export.SeedDataMain [db-root] [out.json]` (default `tmp/seed-data.json`) writes ASCII JSON `{source,
applied, warnings, tables: {name: {columns, rows}}}` with the old SQL tuple column order (a missing column is 0). It
prints a count per table and `WARN <text>` per warning, and exits 0.

The test fixture `SeedOverlay` keeps its API and delegates to `SeedData.load`. Its hand-applied KPQ/EPQ/1338 edits and
regexes go away.

### 9.3 `server.knowledge.KnowledgeLint`

It holds the knowledge-side checks and their wording. The hosts walk their own content and pass ids with a "where"
prefix, so living imports knowledge and never the reverse; `KnowledgeLint` imports nothing from living or agents.

- `current()`: the offline instance when installed, else the published snapshot when ready (one instance cached per
  snapshot), else null. Null means the knowledge checks are skipped and the lint is today's.
- `installOffline(knowledgeDir, dbRoot)` loads the knowledge file, travel.yaml, aliases.yaml and `SeedData.load`, and
  returns notes for the operator: an unreadable file (nothing installed), a missing seed folder (source checks
  skipped), replay warnings, stale inputs.
- Staleness: `KnowledgeLoader.checkStaleness` is split into a pure package-private `changedInputs(file)` plus the
  logging, and `staleSources()` exposes the last result.

| Method | Finding | Severity |
|---|---|---|
| `label(kind, id)` | "4003003 Fairy Wing", "209080000 Extra Frosty Snow Zone near Happyville", "1222014 (unnamed custom equip)"; the bare id when unknown | - |
| `require(kind, id, where, out)` | `<kind> <id> does not exist in the server WZ` | ERROR for ITEM, MOB and MAP (complete tables). WARN while the file is stale for that kind (`wz/Character.wz/` and `wz/Item.wz/`; `wz/Mob.wz`; `wz/Map.wz/Map`), with "run tools\knowledge-export.bat". NPC is always WARN, because the table omits unnamed, unplaced NPCs. |
| `obtainable(item)` | Nothing when `WorldKnowledge.obtainable` (8.2). Else the first match: only script-spawned droppers (naming the NPC and its map); only quest-gated rows (naming the quest); droppers that never spawn; nothing at all ("in the Liquibase seed data" offline; "quest rewards, NPC scripts and reactors are not checked") | WARN; skipped without rows |
| `spawns(mob)` | "never spawns" (no map and no script; offline adds "DB-placed life is not checked offline"); "only appears through a script" when it is not `wild` but a script spawns it | WARN |
| `trainingField(map)` | a forced return (instanced or private); no monsters; no portal, portal-script warp or travel leg leads in | WARN |
| `lobby(pq, map)` | the PQ (its aliases.yaml entry) starts at its NPC's map, not at this one | WARN |
| `gear(where, equips, outfit, holders)` | an equip needs a level above every holder's band max ("it is carried, never worn"); an outfit slot without a 0 whose lowest reqLevel is above every holder | WARN |

"Above every holder" uses the band max on purpose: gear a band grows into is legitimate, because `ResidentWardrobe` wears
the first entry the game's own level check allows.

### 9.4 Host changes

`ContentLint` (living world): the existing outfit-slot ERROR prints `label(ITEM, id)`. A new `knowledgeChecks(content,
knowledge, out)` runs when `current()` is not null:
- **Personas**: gift items `require(ITEM)`; portraits are skipped (client-only NPCs). `knows:` entries (7.7) that do not
  resolve are a WARN; whichever of steps 4 and 6 lands second adds this check.
- **Quests**: step items and DELIVER takes get `require(ITEM)`, then `obtainable` unless a `dropFrom` step hands them
  out, once per item per quest. Kill and `dropFrom` mobs get `require(MOB)` then `spawns`. Visit maps get
  `require(MAP)`. `npc:<id>` targets get `require(NPC)`; a non-numeric one is an ERROR "is not an NPC id". Reward items
  get `require(ITEM)`.
- **Contracts**: price items get `require` and `obtainable`; proof mobs get `require` and `spawns`.
- **Loadouts**: equips, consumables and outfit entries get `require(ITEM)`, then `gear` with the holders: each persona
  using the loadout (its level band) and each arc chapter using it (its owner's band).

`PopulationLoader.lint`:
- careers' loadouts: `gear`, with one holder per career band (from the previous band's max + 1);
- regions: town and FM `require(MAP)`; each lobby `require(MAP)` and `lobby`; the two existing messages print labels;
- TRAIN-eligible fields (not a town, capacity > 0, mob level > 0, as `FieldCatalog.trainingField`): `require(MAP)` and
  `trainingField`;
- `market.yaml`: the permit `require(ITEM)`; basket goods `require` and `obtainable`;
- intents: their maps `require(MAP)`.

`ContentLintMain` and `PopulationLintMain`:
- The first statement is `Configurator.initialize(new DefaultConfiguration())` (risk 1, 9.8).
- Then `KnowledgeLint.installOffline(Path.of(new KnowledgeConfig().CONTENT_DIR), Path.of(SeedData.DEFAULT_ROOT))`. The
  class defaults are used; config.yaml is not read.
- Each note prints as `  WARN knowledge: <note>` after the census line.
- `QuestLogExportMain` is unchanged.

Live, the checks start once the snapshot is ready, so boot is unchanged; they cost under 20 ms per reload, on the
command or admin thread.

### 9.5 Expected findings on the shipped content (seed rows)

**Living world: 0 ERROR, 9 new WARN.** One is Fairy Wing at `ellinia.yaml quest thessaly-a-fallen-wing step wing`: it
drops only from 9500317 Kid Snowman, 9500318 Angry Snowman and 9500319 Giant Snowman, which only NPC 9220004 Happy
spawns, at 209080000. The `return` step's take of the same item is not reported again. The other 8 are dead gear:

| Loadout | Equip (reqLevel) | Above |
|---|---|---|
| beginner-townsfolk | 1002026 Brown Bamboo Hat (25) | nettle 15-17 and 18 more |
| ellinia-mage-l22 | 1002036 Green Jester (30) | casse 21-24 |
| ellinia-mage-l30 | 1002034 Blue Jester (30) | arc casse-cold-study chapter-3 |
| kerning-thief-l28 | 1072033 Iron Chain Boots (30) | vex 26-29 |
| perion-spearman-l32 | 1002025 Red Duke (40) | halvard and 1 more |
| perion-warrior-l30 | 1302020 Maple Sword (35) | garrick 28-32 |
| perion-warrior-l30 | 1002025 Red Duke (40) | dorran and 2 more |
| sailor-cutlass | 1102001 Blue Napoleon (50) | hadrik 30-33 |

There are 0 unknown ids among 1,357 references (698 items, 25 mobs, 22 maps), 0 unspawnable kill mobs, and no other
must-obtain item without a source.

**Population: 0 ERROR, 8 WARN after (c), 10 before.**
- 5 gear: beginner-townsfolk 1002026, ellinia-mage-l22 1002036, kerning-thief-l28 1072033, and sailor-cutlass 1302008
  Gladius (30) and 1102001 (50) for warrior-page 10-29.
- 3 TRAIN fields with no way in: 103000906, 200060001, 261020700. Two of them are script warp targets, which the wording
  allows for.
- Before (c) only: the two lobby findings.
- 0 unknown maps among 713 fields, towns and FMs, and 0 basket findings (24 goods).

These are real content issues for the user to fix or accept; the lint only reports them.

### 9.6 PowerShell exporters

Drops, global drops and shops come from `SeedDataMain`'s JSON, the same replay the lint uses, instead of regexes over
three seed files. The live DB is not used: the exporters must be reproducible from the repo, and operator edits already
live in the runtime overlay.

| File | Change |
|---|---|
| `tools/catalog/Update-AllCatalogs.ps1` | `-SqlRoot` (default `src/main/resources/db`). The first export step replays the seed into `<run>/seed-data.json` and fails fast without `target\Cosmic.jar`. It passes `-SeedDataPath` to the game and NPC exporters and `-SqlRoot` to `Test-CatalogBundlePrep`, writes the status report after the readiness report, and adds `summary.reports.seedData`. |
| `tools/catalog/Test-CatalogBundlePrep.ps1` | `$SqlRoot` defaults to `src/main/resources/db`, as the reactor exporter does: `sql.hash` is non-null and `fileCount` is 67 (69 once step 4's two changeset files exist). |
| `tools/catalog/Get-CatalogStatus.ps1` | Last Write is the newest file's time (the folder's when it is empty). |
| `tools/game-catalog/Export-GameKnowledgeCatalog.ps1` | `-SeedDataPath` (empty: run `SeedDataMain` itself). The shop and drop catalogs iterate the JSON rows (same column order; bodies unchanged). New `generated_global_drop_catalog.json` (6 rows) and a `global-drop` item flag. Categories: 0 meso, 20000-29999 face, 30000-39999 hair, 5000000-5009999 pet, other 5xxxxxx cash. `Read-SqlTupleRows` is removed; the summary names the replay. |
| `tools/npc-catalog/Export-NpcCatalog.ps1` | `-SeedDataPath`; shop NPC ids and shop items come from the rows; the source strings say "src/main/resources/db (Liquibase replay)". |
| READMEs (`tools/game-catalog`, `tools/npc-catalog`) | The replay needs `target\Cosmic.jar`. |

Verification writes only under `tmp/step6-catalog/`; the default `tmp/game-catalog` and `tmp/npc-catalog` hold the
2026-09-27 baseline.
1. Package, run `SeedDataMain`, the game export (about 3 min) and the NPC export with `-SkipApproach`, then diff against
   the baseline. Expected, and nothing else:
   - drop rows 22,157, with two chances changed: 9300001/4001007 200000 -> 800000 and 9300173/4001161 200000 -> 600000;
   - shop 1338 26 -> 25 items (no 5041000), shop item rows 3,882 -> 3,881;
   - categories: pet 539 -> 54, cash 485 new, unknown 2,059 -> 0 (face 540, hair 1,518, meso 1);
   - 5041000 loses `sold-in-shop`, and 6 items gain `global-drop`;
   - the NPC shop row `9090000|1338` -1.
2. Optional full refresh (about 27 min) into `tmp/step6-catalog/*`: check the source hashes, the status report (readiness
   exists, Last Write right) and the summary.
3. The adaptive catalog regeneration (`Export-VictoriaAdaptiveQuestCatalogs.ps1`) runs only with every path and
   `-OutputDirectory` under `tmp/step6-catalog/`. Its diff (expected: 9300001/4001007 200000 -> 800000 plus `revision`
   and `sourceHashes` churn) is reported, not applied; `git status src/main/resources/agents` stays clean.

### 9.7 Lobby ids (section 11)

`scripts/config/population/regions.yaml`:
- OPQ 200000000 -> 200080101: Wonky 2013000 stands there, and `2013000.js` starts OPQ only on that map.
- LPQ 220000000 -> 221024500: Red Sign 2040034 is placed only there.
- KPQ 103000000 stays (Lakelis is placed there).

This matches `aliases.yaml` and the agents' OPQ/LPQ test services. Live effect: PQ-activity citizens of orbis (51-70)
and ludibrium (35-50) stand at the PQ lobbies, and lobby chatter and citizen-led party invites move there. Orbis and
Ludibrium stop being "lobby" towns, so town chatter starts playing there. Needs the user's live check.

### 9.8 Risks

1. **The offline lint can roll the live server log.** MapleEdit runs it with cwd = the live server dir. Any class with a
   static logger initialises log4j2, whose `RollingFile` with `OnStartupTriggeringPolicy` would rename or truncate
   `logs/cosmic-log.log`. This is plausibly pre-existing (`PopulationLintMain` already reaches `ResidentShaper`) but not
   verified. `Configurator.initialize(new DefaultConfiguration())` as the first statement of both lint mains and
   `SeedDataMain` gives a console appender at ERROR level and no files.
2. **Seed vs live drift.** A no-source WARN can differ between a save (seed rows) and a reload (live rows, including about
   10 operator shop edits). It is only ever a WARN.
3. **A stale knowledge file after merges.** The ERROR downgrade is per kind (script edits never downgrade items, mobs or
   maps), and the note says why.
4. **Deploy coupling.** MapleEdit uses the live checkout's jar, `scripts/config/knowledge/` and `src/main/resources/db`,
   so the new checks appear only after the merge and deploy. A missing knowledge folder gives a note, never a failure.
5. **Gear WARN volume** (13 lines): real dead items, for the user to fix or accept.
6. **The exporters now need the jar.** `ConvertFrom-Json` on about 26k rows takes a few seconds.

### 9.9 Tests and acceptance (step 6)

| Test | Asserts |
|---|---|
| `SeedDataTest` | the 9.2 counts and values; the `applied` order; every 152 seed tuple survives except the two edits (the old regex as oracle); rollback never runs; `REPLACE` and expression updates on watched tables warn, other tables do not; orphan shopitems and chance-0 globals are dropped |
| `KnowledgeLintTest` | labels; Fairy Wing gives one WARN naming 9500317 and 9220004 Happy; 4000029, 2000000 and 4310000 pass; 4031273 is quest-gated (2104 Fanzy's Red Furball); 4031000 has no source ("Liquibase seed data", "not checked"); spawn cases 100100 / 9500317 / 6230101; training cases 100010000 / 103000800 / 200060001; lobby opq 200000000 warns with 200080101 and Wonky, the right lobbies and unknown PQs pass; gear above every holder warns, a holder that reaches it and a pool with a 0 pass; ERROR vs stale WARN vs NPC WARN; `installOffline` with the real dirs (no notes; `label(ITEM, 2070005)` is "2070005 Steely Throwing-Knives"), an empty dir, a missing db root |
| `server/living/config/ContentLintKnowledgeTest` | shipped content has no ERROR with knowledge; a script-only quest item warns once and `dropFrom` silences it; unknown KILL, VISIT, reward and equip ids are four ERRORs; without knowledge the lint is unchanged; a loadout above a 6-8 persona warns |
| `server/living/population/PopulationLintKnowledgeTest` | the shipped population has no ERROR and no lobby finding; a town lobby warns with the real lobby; an unknown basket item is an ERROR and Fairy Wing a WARN; an instanced TRAIN field warns |

Acceptance:
- Compile exits 0 and the four new tests are green. The regression set below is green, `KnowledgeProbeTest` stays at
  81/81 and 97/97 after the `SeedOverlay` switch, and `KnowledgeFileFreshnessTest` is green.
- Shipped content: 0 ERROR; living world 9 new WARN, population 8. Differences from 9.5 are explained.
- Command lines (`java -cp "target/classes;<target/classpath.txt>" server.living.config.ContentLintMain`, and the same
  for `PopulationLintMain`): exit 0, the 9.5 lines with two leading spaces, under 5 s each, and nothing under `logs/`
  created or modified.
- MapleEdit simulation: `PopulationLintMain` on a copy with basket 9999999 exits 1 with `  ERROR market.yaml basket: item
  9999999 ...`; with 4003003 it exits 0.
- The replay runs under 1 s warm with 0 warnings. The exporter diff is exactly 9.6, and the committed
  `src/main/resources/agents/catalogs` is untouched.
- Live (the user's call, after deploy): `!world reload` and `!citizens reload` show the findings, and citizens form
  OPQ/LPQ parties at 200080101 / 221024500.

Commands:
- `.\mvnw.cmd -q test "-Dtest=SeedDataTest,KnowledgeLintTest,ContentLintKnowledgeTest,PopulationLintKnowledgeTest" -Dsurefire.failIfNoSpecifiedTests=false`
- `.\mvnw.cmd -q test "-Dtest=server/knowledge/**,ContentLoaderTest,ContentLintHireIdsTest,PopulationContentTest,PhraseMatcherTest,ConversationContentTierTest,ResidentContentHookTest,QuestLedgerWhereTextTest,MarketKnownPriceTest" -Dsurefire.failIfNoSpecifiedTests=false`

Left out:
- More item sources (WZ quest rewards, NPC `gainItem`, reactors, gachapon, maker). They need exporter changes; the
  message names them as unchecked.
- Job-gated gear, and kill-mob level against the quest band.
- The 133 TRAIN-eligible fields in regions no citizen uses.
- The unnamed custom equips and item stats in the PowerShell catalog.
- Regenerating the committed adaptive catalogs, and Kerning's lobby-kind chatter quirk.

### 9.10 Steps 4-6 together (merge and deploy)

| Shared | Steps | Rule |
|---|---|---|
| `KnowledgeConfig` + `config.yaml` `knowledge:` | 4, 5 | One block, after `LOG_UNRESOLVED`: `PERSONAL: true`, `VETERAN_LEVEL: 70`, `DECISIONS: true`, `CITIZEN_GEAR: true`. ASCII. It ships exactly with the jar (YamlConfig rejects unknown keys). |
| `WorldKnowledge` | 5, 6 | `wild` and `obtainable` (8.2) are added once; `undeadShare` and `rides` (step 5); `TRAINING_LEVELS_ABOVE` becomes package-private. |
| `ResidentDialogue` | 4, 5 | `openKnowledge` (7.2) and `itemFor` (8.3) are separate methods. |
| `src/main/resources/db` | 4, 6 | Changeset 45 is DDL on another table, so `SeedData` skips it and the replay is unchanged; the SQL file count becomes 69 in the worktree and 71 after the merge with the live branch's 035 up/down. |
| `SeedOverlay` / `KnowledgeTestKit` | 4, 5, 6 | Step 6 makes `SeedOverlay` replay-backed with the same rows, so the step 4 and 5 tests need no change. |
| `ContentLint` personas | 4, 6 | The `knows:` WARN lands with the second of the two. |

Boundaries for all three steps:
- Nothing under `src/main/java/server/agents/**` changes, and no `AgentTuning` or `agent-engine.yaml` entry is added.
- New `server.knowledge` classes import neither `server.living` nor `server.agents`. The only agents imports stay the
  existing step-3 seam installs in `ContentChat`.
- Boundary and architecture violation counts stay at the step-3 baseline.

Deploy is the user's call, as one restart after the merge:
- the jar;
- `config.yaml`'s four new keys;
- `scripts/config/living-world/personas` (`knows:`) and `scripts/config/population/regions.yaml`;
- `scripts/config/knowledge/` is unchanged by steps 4-6;
- `DatabaseMigrations` applies changeset 45 at that boot, and MapleEdit picks up the lint through the live checkout's jar.

Kill switches are `PERSONAL`, `DECISIONS` and `CITIZEN_GEAR` (gear already given stays).

Rollback: the `knowledge:` block and the jar go back together. yamlbeans rejects unknown fields, so an older jar with
the new config.yaml fails at `YamlConfig`'s static init (the server and every MapleEdit lint). Restore the previous
config.yaml with the jar, or delete the `knowledge:` block (the defaults are all on). Changeset 45 can stay applied.

Final sweep after all three, the step-3 fix-pass command plus the new tests. Failures must be only the known
pre-existing set:

```
.\mvnw.cmd test "-Dtest=server/knowledge/**,server/living/**,server/agents/social/**,server/agents/capabilities/dialogue/**,server/agents/integration/ollama/**,client/command/**,*Boundary*Test,*Lint*Test,*Architecture*Test,AgentChatIngressServiceTest,AgentChatRouteCoordinatorTest,AgentTargetedChatRouteServiceTest,AgentUntargetedChatRouteServiceTest,AgentPendingOfferChatRouteServiceTest,AgentChatIntentJudgeTest,PhraseMatcherTest,AgentDirectorChatServiceTest,AgentDirectorProposalServiceTest,ConversationCompanionTest,ContentLoaderTest" -Dsurefire.failIfNoSpecifiedTests=false
```

## 10. Server rules the bots may state

From `server-rules.md` section 6. Each fact is read from its live source at answer time:

| Fact (template) | Risk |
|---|---|
| Monster EXP {X}x, meso/drops {M}x/{D}x, bosses {B}x (World getters) | meta: only when asked |
| Until level 30 monster EXP is held to {min(X,5)}x | meta |
| Quest EXP pays 6x the listed amount (3x below level 10) | constant in `ExpAction:53` |
| Maps hold about {R}x the usual monsters, up to {P} per spawn spot, and refill on their own | low |
| Party EXP only reaches members within 5 levels of the monster or of someone who hit it | low |
| Any class can wear any gear; only level, your own main stat and fame count (Pirates and Beginners: level only) | medium: the client may show red text |
| Every bag tab is fully expanded; pets never expire | low |
| At level 10 everyone gets a Hyper Teleport Rock: permanent, 10 saved maps, crosses continents | low |
| Perfect Pitch drops from anything; PQs and bosses hand out more; Inkwell trades it | low |
| Chaos 60% and White Scrolls drop from anything, very rarely | low |
| Gear from level 10 up gains item levels (to 30); the Hidden Appraiser by the FM reads it | low |
| Gachapon is 90/8/2 common/uncommon/rare, and every machine shares the rare prizes | low |
| A recruit 2+ levels below you in your party gets +15% EXP per level of gap, up to +90% | low (in-world) |

Never stated:
- dead or switched-off settings (PQ bonus EXP, quest_rate, fishing, maker rate, scroll bonus, godly, gacha v2);
- GM tools (dressing room);
- internals (anti-cheat, `bot_economy`, the bot-only job gate);
- skill numbers that differ from the client tooltip, unless the question is about that skill (then quote
  Skill.wz and say "the tooltip is off").

## 11. Content bugs found during the research (not fixed by steps 1-3 unless noted)

- `scripts/config/population/market.yaml` labels do not match the ids, so seed prices sit on the wrong
  items:

  | id | label | actual item |
  |---|---|---|
  | 2044501 | Claw | Bow ATT |
  | 2044701 | Bow | Claw ATT |
  | 2040802 | Gloves ATT 60% | Gloves DEX 10% |
  | 2040002 | DEF 60% | DEF 10% |
  | 4000019 | Green Mushroom Cap | Snail Shell |
  | 4000000 | Snail Shell | Blue Snail Shell |
  | 1302020 | Cutlass | Maple Sword |
  | 1302030 | Maple Sword | Maple Soul Singer |
  | 2070005 | Kumbi | Steely |
  | 2070006 | Tobi | Ilbi |

  **Fixed in step 3** because PRICE answers read this index; the ids are corrected to match the labels' intent.
- `regions.yaml` uses the towns 200000000 / 220000000 as the OPQ/LPQ lobbies. The NPC maps are 200080101 /
  221024500. **Fixed in step 6** (9.7).
- Living quest `thessaly-a-fallen-wing` (`ellinia.yaml:163`) needs Fairy Wing 4003003. It drops only from the
  Happyville raid snowmen (spawned by NPC 9220004 "Happy"), so it cannot be obtained in Ellinia. This is a
  content decision for the user. Step 6 reports it as a lint WARN (9.5); step 5's bench ledger credits it 0 (8.3).
- `ItemInformationProvider.itemNameCache` is never filled, so every `getAllItems()` re-walks six String files.
  It is a perf bug and outside this plan.
- `DialogueContextSnapshot` throws on a fact key over 256 chars and the bot goes silent. **Fixed in step 3**
  (registry validation).

## 12. Risks and open questions

- **Wrong confident answers** are worse than "idk". Mitigations: the kind-typed slots, the stoplist, ties
  answered with a clarifying question, the honest boundary reply, and unresolved-line logging.
- **Answer fatigue**: five citizens answering the same question. Only the addressed citizen answers (existing
  addressee logic), and companions use the existing single-responder election.
- **Rates change live** (GM, MapleEdit): rates are never cached into facts; they are read per answer.
- **The client WZ vs the server WZ** can drift (living-world NPC portraits exist only in the client). The
  knowledge file follows the server, which is what the game logic obeys.
- **Overlay at boot needs the DB**. If the load fails, the tiers stay off and existing behaviour runs; this is
  logged once.
- **Open (user)**: should bots ever quote exact drop percentages? (Default: buckets.) Should rates be talked
  about at all? (Default: only when asked, veteran voices.) Step 4 implements both defaults (7.3). Fairy Wing
  quest: change the item, or make the quest point at Happyville?

## Implementation notes (steps 1-3)

Built 2026-10-01 in three slices plus a review-and-fix pass; progress logs in `tmp/knowledge-research/progress-{A,B,C,fix}.md`.

**What exists**
- Step 1: `KnowledgeExportMain` (+ `tools\knowledge-export.bat`) writes `scripts/config/knowledge/server-knowledge.json`
  (columnar, ASCII, exporter version 3: scroll success %, NPC function text). `KnowledgeOverlayLoader` reads drop_data,
  drop_data_global, shops+shopitems (with pitch) and plife. `WorldKnowledge` is one immutable snapshot behind one
  volatile, rebuilt on a single `knowledge-loader` thread with generations; rebuilds are requested by
  `MonsterInformationProvider.clearDrops()`, `ShopFactory.reloadShops()`, the `!pnpc`/`!pmob` commands and their remove
  commands, and `!knowledge reload`. `travel.yaml` has 64 legs.
- Step 2: `NameIndex` + `aliases.yaml` (230+ entries), `ContentQuestionDetector`, `ContentAnswers` (facts + one short
  reply, per-(asker, bot) memory), `ReplyBank` (four voices), `ServerRules` (live World rates), `KnowledgeHorizon`
  (FULL / ROUGH / NONE), `!knowledge status | reload | <line>`.
- Step 3: `GroundingRegistry` (+ `GroundingRequest`), the Ollama content rule, `ContentChat` (host glue), citizens'
  content tier in `Conversation`, the companion seams (`AgentChatIntentRuntime.installContentGate`,
  `AgentSocialDialogueRuntime.installAnswerer`, `AgentSocialResponderElection.installPreference`), residents' hook and
  quest "where" text, corrected `market.yaml` ids, `KnowledgeProbeMain` (a test-source tool now, not in the jar).

**Deviations from the plan**
- No Jev tie-break: a tie between two candidates of the same kind asks back once, then answers its best guess ("prob x").
  A one-word slot only resolves through an alias or an exact (or one-typo) name, and partial word matches never count,
  so party chat ("where is the leader") stays silent; question kinds decide between kinds (an NPC vs a role).
- More question kinds than section 5.3 (GET_WHERE, JOB_ADVANCE, WHERE_AM_I, MAP_DROPS, MAP_GOOD, RULES, FOLLOW_UP,
  THANKS) and NPC roles as an entity kind. Rates are stated to anyone who asks directly (no veteran voice yet).
- The horizon grades instead of cutting off: a little past the bot's level it gives only a rough place; bosses and PQs
  are famous; Orbis, El Nath, Ludibrium and the FM are known to everyone; levels and prices are always answered.
- Companions answer their owner or party without the per-asker budget; a question asked a third time goes to the model
  (grounded) instead of getting no reply. Citizens never answer through the companion seams; each host's flag gates
  its own grounding facts, and quest place text follows `knowledge.RESIDENTS`.
- PRICE falls back to the nearest NPC shop's price ("50 at luna") when the price index has no sale; a basket good
  with only a seed row (0 samples) is quoted at its basket price, never at the stale seed.
- Generic WZ map names ("Line 1 <Area 1>", "B1 <Area 1>") are spoken as their street; Hidden Street maps get their
  town ("someone elses house by henesys") and rank below named fields; shop and station interiors whose return map is
  themselves (Ellinia's stores, NLC's subway station) belong to their town.
- Template replies to greetings (`ChatterEngine.say`) keep CHATTER_ENABLED and a 15 s self cooldown per citizen; the
  `town.directions` chatter intent has `maxLevel: 20` and never names the town it is said in.

**Measured** (tests in the broad suite, 2026-10-01)
- Exporter: 4.2-8.7 s standalone (slice A); the in-memory re-export in `KnowledgeFileFreshnessTest` takes about 5.5 s.
  File: 1,651,516 bytes.
- Boot load (best of 3, warm): file + travel + aliases + fields 24 ms, snapshot build 79 ms (cold first load about
  130 + 65 ms). Retained heap 9.03 MB (estimate 8.45 MB) with a live-sized overlay.
- Lookups about 80 ns; a route 150-250 us on first use, about 200 ns cached; 33 us per question (178 probe lines).
- `KnowledgeProbeTest`: 81/81 positives right, 97/97 negatives silent. Live probe (read-only, MySQL on 3306):
  22,157 drops, 6 global, 3,891 shop rows, 1 plife, 615 price rows in 577 ms; 108 of 216 lines answered, every
  negative silent (`tmp/knowledge-research/probe-live.txt`).
- Sweep (knowledge, living, agents social/dialogue/ollama, client commands, chat suites, every Boundary/Lint/Architecture
  test): 1,921 run, 10 failures + 1 error, all pre-existing (the agents boundary set, identical to the baseline, plus
  `AgentChatRouteCoordinatorTest` and `AgentWhisperCommandServiceTest`).

**Open items**
- Price index repair needs sign-off: the live `living_price_index` still holds the old basket seeds (0 samples) under
  the corrected ids (2044501 at 2.5m, 1302004 at 100k, 2040002, 2040802, 1302030, 2070005, 2070006). Content answers
  ignore them; the market's own S> lines and stalls still use them through `Market.price()`. Proposed:
  `DELETE FROM living_price_index WHERE samples=0 AND item_id IN (2040002,2044501,2040802,1302004,1302030,2070005,2070006)`
  plus a restart; the three rows with sales (2044701, 4000019, 1302020) are real sales and stay unless decided otherwise.
- Not deployed or verified live (boot log, the Henesys three-host check, filming).
- `drop_data_global` level bands (USE_GACHA_V2) are not loaded; the flag is off and all 6 rows are continent -1.
  Continent-scoped globals are honoured.
- Amoria (Thomas Swift) and other NPC warps are not in `travel.yaml`, so "how do i get to amoria" says it does not know.
  Route costs sometimes pick an odd way (Henesys to Aqua via Ludibrium and Helios Tower).
- Companion answers drop a line when the asker changes maps, but have no "a human already answered" check.
- Not detected (left to the model): "where do i take the boat", "where do i trade perfect pitch", a bare "kerning?".
- Untested seams: `AgentSocialDialogueRuntime`'s answer delivery (scheduling + memory turn) and `Conversation.onChat`
  end to end (both need the live runtime); the routing decisions are unit-tested through `forContentTier`,
  `saysTheSame` and `ChatterEngine.mayReply`.
- Merge checklist: any byte change under `scripts/{npc,event,reactor,portal,map,quest,item}` or the exported WZ
  makes the boot log warn (source fingerprint) until someone runs `tools\knowledge-export.bat` (needs
  `mvnw package`) and commits the regenerated file. `KnowledgeFileFreshnessTest` ignores the fingerprint and fails
  only when the re-exported content differs (a new script spawn, a renamed item, an edited map), so other branches'
  unrelated script edits do not turn it red. Re-export after merging other branches' script or WZ edits.
- `config.yaml`'s `knowledge:` block must ship with or after the jar (YamlConfig rejects unknown blocks).

## Implementation notes (step 6)

Built 2026-10-02; progress log `tmp/knowledge-research/progress-6.md`.

**What exists**
- `server.knowledge.SeedData` replays the Liquibase changelogs offline; `export/SeedDataMain` writes it as JSON for the
  PowerShell exporters and configures log4j to a console-only ERROR level first. The test fixture `SeedOverlay`
  delegates to it, so its regexes and hand-applied edits are gone.
- `WorldKnowledge.wild` / `obtainable` (8.2) were added by step 6, since step 5 had not started; they are covered by
  `WorldKnowledgeDecisionsTest`.
- `KnowledgeLint` (9.3), plus `KnowledgeLoader.changedInputs` (pure) and `staleSources`. The host checks are in
  `ContentLint.knowledgeChecks` and `PopulationLoader.lint`. `ContentLintMain` and `PopulationLintMain` configure log4j
  first, install the offline lint and print its notes as `  WARN knowledge: ...`.
- `regions.yaml` puts the OPQ lobby at 200080101 and the LPQ lobby at 221024500.
- The PowerShell exporters and READMEs changed as in 9.6. The game exporter also writes
  `generated_global_drop_catalog.json`, and the NPC exporter's summary wording names the replay.

**Measured**
- Replay: 0 warnings, the 10 changesets of 9.2 and 22,157 / 6 / 110 / 3,881 rows. It takes about 100 ms warm in a
  test JVM and about 250 ms in a fresh JVM (`SeedDataMain`). The JSON is 760,801 bytes.
- Shipped content with seed knowledge gives exactly the 9.5 lines:
  - living world: 0 ERROR and 9 new WARN, 47 warnings in total (38 before);
  - population: 0 ERROR, 10 WARN before (c) and 8 after.
- Command lines: exit 0 for both.
  - The living lint takes 2.2 s (0.45 s with the old jar); the population lint takes 2.1-2.2 s (1.07 s before).
  - Nothing under `logs/` changed.
  - MapleEdit simulation: basket 9999999 exits 1 with `  ERROR market.yaml basket: item 9999999 does not exist in the
    server WZ`; basket 4003003 exits 0 with the Fairy Wing WARN.
- Live cost per reload with a ready snapshot: the living lint takes 1.6 ms (0.55 ms without knowledge) and the
  population lint 0.8 ms (0.1 ms). The first call after a publish takes 37 ms, which includes the cold JIT and building
  the per-snapshot `KnowledgeLint`.
- Risk 1 (9.8) is real and pre-existing. The old jar's `PopulationLintMain`, run in a scratch directory holding
  `logs/cosmic-log.log`, rolled that file into `logs/archive/server/*.log.gz`, truncated it and created 7 more log files.
  The new mains leave `logs/` untouched.
- Exporter diff against the 2026-09-27 baseline (game export plus NPC export with `-SkipApproach`, both under
  `tmp/step6-catalog/`):
  - It is exactly the 9.6 list. Maps, mobs, quests and skills are identical.
  - `generated_npc_catalog` holds no shop items, so only its `sources` strings change, as they do on every inventory row.
  - With `-SkipApproach`, only approach-derived fields differ.
- Adaptive catalog preview (every path under `tmp/step6-catalog/`; `src/main/resources/agents` stays clean):
  - Step 6's own effect, isolated by also regenerating from the baseline inputs, is 9300001/4001007 200000 -> 800000 in
    `victoria-mob-drop-facts.json`, plus `catalogId`, `revision` and `sourceHashes.drops` churn in all five files.
  - The committed adaptive catalogs already differ from a regeneration of the baseline (String.wz item names, and
    `jobs: null` vs `{}`) in 6 to 986 places per file. This is pre-existing drift and is not applied.
- Full refresh (`Update-AllCatalogs` into `tmp/step6-catalog/refresh/*`): exit 0 in 27 min.
  - The replay is its first step, and `summary.reports.seedData` names its output.
  - `catalog-source-hashes.json` has a non-null `sql.hash` and `fileCount` 67.
  - The status report is written after the readiness report (readiness exists = True), and Last Write is each folder's
    newest file.
  - Verification is INCOMPLETE with the one known NPC warning, as in section 1.
- Sweep (the 9.10 command): 1,960 run, 10 failures and 1 error, all from the pre-existing set. The boundary counts are at
  the step-3 baseline: 59 numeric constants, 23 compatibility imports and 13 Cosmic-audit entries.

**Deviations**
- `gear` warns per holder, naming the holders whose band max is below the equip's level. This is the design's algorithm
  and gives its numbers.
  - The 9.9 example (holders 1-9 and 10-29 giving "and 1 more") contradicts that rule, because 10-29 reaches 25.
  - The test uses holders 1-9 and 10-19, and also checks that 1-9 with 10-29 names only 1-9.
- Mob 9999999 and NPC 9999999 exist in the WZ, so the tests use 7777777 for an unknown mob or NPC.
- `SeedData.TABLES` is a `List`, which fixes the table order in the JSON.
- A column that a seed statement leaves out is 0 in both the overlay and the JSON. The DDL defaults (continent -1,
  quantities 1) are not modelled, and no seed row omits a column today.
- `trainingField` reports only the first problem a map has.
- A staleness check that fails offline marks every kind stale and adds a note.
- The `knows:` WARN is not added, because step 4 has not landed (`Persona` has no `knows`). Whichever of steps 4 and 6
  lands second adds it.

**Open**
- The Fairy Wing quest stays as it is and keeps its WARN; this is the user's content decision (section 12).
- The 8 dead-gear warnings and the 3 TRAIN fields with no way in are real content issues for the user to fix or accept.
- Not deployed. After the deploy, the live checks of 9.9 still need running: `!world reload` and `!citizens reload`
  show the findings, and citizens form OPQ/LPQ parties at the new lobbies.

## Implementation notes (step 5)

Built 2026-10-02 on top of step 6; progress log `tmp/knowledge-research/progress-5.md`.

**What exists**
- `knowledge.DECISIONS` and `knowledge.CITIZEN_GEAR` (`KnowledgeConfig`, `config.yaml` after `LOG_UNRESOLVED`), and the gate
  `server.knowledge.Decisions` (`active()`, `citizenGear()`).
- `WorldKnowledge.undeadShare` and `rides`. The route search counts the rides it takes (a travel edge at ride cost), so
  `rides` reads the cached route. `TRAINING_LEVELS_ABOVE` is package-private; `wild` and `obtainable` came with step 6.
- `FarmSites.plan` / `creditSource` (8.3). The hosts are `RosterService.farmPick` (assign and delegate), `BenchLedger.chancePerKill`
  (FARM_ITEM with `questDrops = false`, GATHER with `true`) and `ResidentDialogue.itemFor`.
- `RecallTravel.offRoads` / `walkable` (8.6), `FieldCatalog.healerFields` and the healer preference (8.5).
- `GearPicks.pick` and `CitizenGear.upgrade` / `upgradeFor` (8.4), called from `ProgressionLedger.catchUp` (every load,
  inside its try) and `CitizenGenerator.shape`.
- Nothing under `server/agents/**` changed, no `AgentTuning` key, no schema or exporter change (no re-export).

**Deviations**
- **Script-only.** "Mob not wild -> SCRIPT_ONLY" applies only when a script spawns the mob. Zakum3 (8800002, the only Zakum
  Helmet dropper) has no placement and no script spawn: the altar's reactor calls it up. Under the literal rule it would be
  SCRIPT_ONLY at every level, which contradicts 8.8's TOO_STRONG at 30 / BOSS_ONLY at 150. A mob with no spawn the file knows
  of therefore goes through the level and boss checks, and without a field site it never farms.
- **Out of reach.** When fitting droppers exist but none of their fields is reachable from the anchor, the plan is FARM
  with no sites, and the companion says "{mobs} drop those, but i can't get to them." A continent global drop (ANY_MONSTER)
  wins over that. Example: steelies at lv50 from Perion, where only Crimsonwood's Leprechauns fit, and Crimsonwood is not on
  the route graph.
- **Snail distance.** The nearest Victoria snail field from Henesys is 7 maps away (Thicket Around the Beach I, then
  Right Around Lith Harbor at 8), not 6 or less. The test asserts those two maps and a distance of 8 or less.
- **Gear rules.** `GearPicks` also skips the 91 equips named "MISSING NAME" and anything with a fame requirement.
  "Job-specific first" ranks job-specific candidates above generic ones. The seed then picks among the candidates of the
  best one's kind within 5 levels of it (inclusive).
- **Refusal names.** A refusal names up to 2 distinct spoken mob names joined by "and", and says "some monsters" when it
  knows none.
- **Test seams.** `FarmItemSiteTest` spies `describeAssignment`, because `ItemInformationProvider`'s static init needs the
  DB pool and Mockito cannot instrument it. `CitizenGearTest` covers `upgradeFor` and `upgrade`'s gate (a mock character
  is never touched with a flag off). The inventory moves (add, equip, remove the replaced piece, save) need a live
  character; they are checked after the deploy.

**Measured** (seed snapshot, tests and a deleted probe)
- Plans:
  - 4000019 at lv10 from Henesys: FARM, snails, 104000200 at distance 7.
  - 4003003: SCRIPT_ONLY, "snowmen and angry snowman", "Happy in Happyville", credit null.
  - 1002357: TOO_STRONG at 30 and BOSS_ONLY at 150 ("zakum").
  - 4032340: QUEST_ONLY (FARM with quest drops).
  - 2040000: SHOP_ONLY (Tulcus in Kerning City).
  - 4310000: ANY_MONSTER with a global credit.
  - 5041000: CASH.
  - "farm steelies x200", "go farm red pots", "farm 50 pig heads" and "farm snail shells" resolve to 2070005, 2000000,
    4000017 and 4000019. "glove att" asks back.
- Ledger: `chancePerKill` is 0 for Fairy Wing (lv70, even at Happy's field with quest drops) and Zakum Helmet (lv30), and
  0.6 for Snail Shell at Right Around Lith Harbor at drop rate 1. With knowledge on there is no `mobsDropping` call.
- Cost: `plan` < 1 ms and `creditSource` < 50 us averaged over 200 calls (asserted). `GearPicks` for 8 slots is about
  0.4 ms warm and 1.6 ms cold per citizen.
- Travel: Orbis -> El Nath 60 s (route [200000000, 211000000], was 180 s), Orbis -> Aqua 60 s, El Nath -> an Orbis field
  120 s (60 s capped). Ludibrium, Leafre and Mu Lung stay 180 s; Victoria is unchanged (RecallTravelTest and RecallCapTest
  green).
- Healers: undead-field draws per 1,000 for a cleric at lv30 / 40 / 60 are 702 / 759 / 756; fire wizards and fighters
  draw 57 / 239 / 253. Unpublished, the cleric's draws equal the wizard's (the random stream is untouched).
- Gear for a lv75 fighter (STR 300) wearing perion-warrior-l30's Maple Sword:
  - Red Katana (type 130, reqLevel 70), Bronze Planet, Orihalcon Platine, Bronze Platine Pants, Dark Carzen Boots and
    Bronze Husk (all 70), Red Gaia Cape (65), Silver Ancient Shield (70); no fitting warrior overall.
  - A lv72 hermit's claw becomes Black Scarab (70).
- Sweep (the 9.10 command): 1,996 run, 10 failures and 1 error, the same failures with the same messages as step 6.
  Boundary counts are unchanged: 59 numeric constants, 23 compatibility imports, 13 Cosmic-audit entries. Log:
  `tmp/knowledge-research/sweep-step5.log`.

**Open**
- Not deployed. After the deploy:
  - the ledger is stricter: FARM_ITEM and GATHER rows for script-only, quest-only (FARM_ITEM), boss or over-level items stop
    accruing, which includes the Fairy Wing quest (section 11);
  - FARM_ITEM camps move on assign (`roster.camp`);
  - up to 600 citizens gain gear at their next load.
- Live checks to run: "<name> farm steelies" and "<name> go farm snail shells"; a lv70+ citizen's equips after it loads;
  a cleric citizen's field; an Ossyria recall ETA.
- PQ-only drops read as TOO_STRONG below the PQ mob's level and as "event monsters" at or above it. For example, the KPQ
  coupon at lv25: Ligator (PC) is lv32.
- Places off the route graph (Crimsonwood, the Phantom Forest) are "can't get to them", like the content answers.
- Still as before: the live FARM_ITEM slice grinds freely at the camp, and GATHER's targeted visit still runs one
  `mobsDropping` per visit (8.7).

## Implementation notes (step 4)

Built 2026-10-02 on top of steps 6 and 5; progress log `tmp/knowledge-research/progress-4.md`.

**What exists**
- `server.knowledge`: `KnownWorld` (7.1), `PastSynthesis` (7.2), `PersonalHorizon` (7.3), `PersonalKnowledge` (the registry,
  events, gossip, write-behind; 7.4), `KnownWorldStore` (7.6), `Hearsay` (7.5). `ContentAnswers.Responder` has the last
  component `KnownWorld known`; `grade(c, e, kind)` combines the personal grade with the level prior as 7.3 says; the
  answer rules of 7.3 and the eleven new `ReplyBank` keys are in. `ContentChat` owns the registry and the `Hearsay`; its
  3-argument constructor gets an in-memory registry that is off, so every step-3 test runs unchanged.
- Hosts: `LivingWorldHooks` (`mobKilled`, `looted`, `mapEntered`, `characterDeleted`), `PresenceScheduler.openKnowledge`
  plus the initial `visited` and the `close` at logout, `Market.shop` (`gained`), `SocialFabric.gossip` (each tick after
  `fame`), `Conversation` (`overhear`, `taught`, `teach`; `overheard` records the question before its rate limit),
  `ResidentDialogue.openKnowledge` (from `ResidentRuntime.materialize`; `maintain` and `standby` close),
  `LivingWorld.start` / `stop`, `KnowledgeCommand` (`known <name>`, `@<name>`, `status`).
- `knowledge.PERSONAL` and `knowledge.VETERAN_LEVEL` (`KnowledgeConfig`, `config.yaml` after `LOG_UNRESOLVED`; the block
  now ends `PERSONAL, VETERAN_LEVEL, DECISIONS, CITIZEN_GEAR`).
- Changeset `45-living-knowledge` with `db/tables/036-living-knowledge.sql` and `-down.sql` (not applied); the SQL file
  count is 69. `SeedData` skips it (another table).
- Personas: `knows:` on tavi, lorca, sable, oda, pell and thane; `LivingContent.knows(id)` (11-argument constructor, the
  10-argument one delegates). The content-lint WARN for a `knows:` entry that names nothing landed here
  (`KnowledgeLint.personaKnows`, from `ContentLint.knowledgeChecks`); the shipped entries are all clean.
- Tests: the nine of 7.9 (`PersonalAnswersTest` also lints the new wording, by whole words: "never farmed those" has
  no "far"), plus `PersonalProbe` (the four probe bots, shared by the test and the main) and
  `KnowledgeProbeTest.botsWithDifferentHistoriesAnswerDifferently` (acceptance 1, 2 and 4 offline). `KnowledgeProbeMain`
  prints the personal section after the step-3 table.
- Nothing under `server/agents/**` changed; no `AgentTuning` key, no exporter change, no re-export.

**Deviations**
- `PersonalHorizon.grade` takes no map or region (it reads every map of the entity itself). NPCs and roles standing in a
  hub town or the FM are FULL as hub maps are, so Orbis's, El Nath's and Ludibrium's NPCs stay known to everyone.
- Items: "seen to drop" also counts global rows (all kills x chance >= 1), and the Hyper Teleport Rock is always known
  (every character gets one at level 10; without that, "how do i get a tele rock" deflected).
- A companion (unknown past) keeps the level horizon's deflect and rough words: it only deflects where the prior does,
  and there those level claims are true. `deflect.unseen` and `rough.heard` are a synthesized past's.
- A synthesized past names no spawn map it does not know: drop answers then name the mob without a place, "where are X"
  deflects with `deflect.unseen`, and a script-only drop without a known map uses the plain drop line.
- Facts: with any known world, `content.item_source` lists only the counted rows (there is no "drops from: no monster"
  when rows exist but none is counted), and "no NPC shop sells it" becomes a boundary fact when a shop the bot does not
  know sells it. `buy.unknown` comes before `buy.none.drop`, which would otherwise claim "npcs dont sell those".
- `train.unknown` is "idk, i trained somewhere else" / "not sure, didnt train around here" / proper "I'm not sure; I
  trained somewhere else": the design's "at that lvl" / "at that level" fail its own wording lint.
- `KnownWorld` keeps each table in one `int[]` (id, count with a heard bit, stamp), about 3.2 KB a world. The last touch
  and the dirty mark live in the registry (an id leaves the dirty set before it is encoded, so a concurrent event marks it
  again). `merge` takes stored entries newest first and drops the older ones a full table cannot take.
- `SocialFabric.tie` takes a `Ties` record built from the live party (`agent.getParty()`; `session.partyId` is never
  reset), circle, guild, channel and map.
- Gossip shape (a) passes a first-hand spot with its WZ main mob; (b) and (c) pass only items and mobs the teller saw or
  has. Heard entries are never passed. Hearsay gives TRAIN_WHERE no subject (the spot is the answer); THANKS, FOLLOW_UP
  and OPINION lines open no question.
- `RosterStateWriterBoundaryTest.changesets40To43AreRegistered...` asserted 43 is the last changeset; it is renamed
  `...InOrderWithTheirRollbacks` and now checks that whatever follows 43 has a higher id.
- The probe counts A/B differences with B asked under A's id (the same seed), and compares K with the same id without a
  world, so only knowledge changes a reply.

**Measured** (tests over the seed snapshot; the live probe read-only)
- 400 full worlds (every table at its cap, a level-120 past) retain 1.24 MB (3.2 KB each). A question to a full world
  takes 35.7 us on average over the 178 probe lines. Synthesis p50 0.48 ms, p95 1.25 ms, max 1.4 ms for levels 1-120.
- Seed probe, 81 positives: answered legacy (lv35) 74, A 54, B 55, V 61, K 74; A answers 54 of legacy's 74 (73%) and
  21 of the 22 Victoria town, shop and NPC lines; A and B differ on 10; K equals the level horizon on every line but the
  rate line; every negative is silent for every bot.
- Live probe (`tmp/knowledge-research/probe-live-step4.txt`): the step-3 section is byte-identical to `probe-live.txt`
  (108 of 216); answered legacy 98, A 68, B 70, V 81, K 98; A and B differ on 11 lines; K differs only on the two rate
  lines.
- `KnowledgeProbeTest`'s legacy benchmark: 38.5-42.9 us per question with the new personal test in the same JVM, as at
  steps 5 and 6 (42.4 / 40.3 us). The helpers that filter map lists return `IntList`s like every other map list: with
  `ArrayList`s the hot loops over them went megamorphic and the same benchmark read 47-63 us.
- Sweep (the 9.10 command): 2,043 run, 10 failures and 1 error, all from the pre-existing set (the agents boundary tests,
  `AgentChatRouteCoordinatorTest`, `AgentWhisperCommandServiceTest`), the same as steps 5 and 6. Boundary counts are
  unchanged: 59 numeric constants, 23 compatibility imports, the same Cosmic-audit set. In that run: the legacy benchmark
  33.2 us, a full world 25.4 us, 400 worlds 1.25 MB. Log: `tmp/knowledge-research/sweep-step4.log`.

**Open**
- Not deployed. At the deploy boot `DatabaseMigrations` applies changeset 45 (re-check the id against `r2/int`'s
  `44-pq-run-pace`). Until then the store logs one warn and worlds stay in memory.
- Live checks: `!knowledge known <citizen>` after it trains a while; `!knowledge @<citizen> who drops X` for two citizens of
  different homes; answer a question in map chat near a citizen and see its `last heard`; after a restart its lived
  entries reload.
- Hired citizens keep their world open until released or the restart (risk 4 of the design).
- Companion and resident answer lines are still not overheard, and residents and companions do not gossip (both as 7.5
  says; they do learn from what they overhear).

## Implementation notes (steps 4-6)

Review-and-fix pass over steps 4-6, 2026-10-02: 30 review findings (correctness, behaviour, regressions), each checked
against the code first; progress log `tmp/knowledge-research/progress-fix-4-6.md`. Uncommitted, not deployed.

**What changed**
- Persistence (`PersonalKnowledge`, `KnownWorldStore`): a failed read of a bot's row is no longer "no row". The store
  throws, the slot stays unloaded (never written over the row), and the next flush reads it again. The timer and `stop()`
  share `flushAll()`, which writes batches of 200 until no loaded world is dirty (one batch a round always took the same
  first 200 ids). `forget()` deletes a row only while personal knowledge is started and on. A lazily opened (companion)
  world is configured with an unknown past at once, so its first answer is worded like the later ones (the rate line
  used to switch from numbers to vague). A PQ lobby is a recordable map (OPQ's lobby is instanced).
- `NameIndex`: `pqAtLobby`, `pqOf` (every map sharing a stage's forced return: stages, bonus, exit) and `pqs()`;
  aliases.yaml's LPQ gains its first stage (922010100). A map name of nothing but ordinals, numbers, single letters,
  basement codes and generic words is said as its street ("kerning square", 16 maps, all Kerning Square).
- `WorldKnowledge.obtainable` uses a second set: monsters placed in the open world (a map with a region, and either no
  forced return or an id below 9300000), plus their revives. `wild` is unchanged (SCRIPT_ONLY still uses it). The
  content lint names instanced-only droppers as such.
- `KnowledgeLoader` checks staleness before it publishes, so a lint cached with the new snapshot sees the stale list.
- Answers (`ContentAnswers`, `ReplyBank`): a rough place for a mob comes from all its spawns (a synthesized past used to
  fall back to the map named after it: Boss Rush's "Stage 9 <Mushmom>"), and the named-room fallback rejects maps without
  a region. A resident's own quest and arc refs count its drop rows (Oda knows who drops stirge wings). Vague rate lines
  follow the rate asked (`rules.rates.vague.exp/.meso/.drop/.boss`). A synthesized past's deflections of an item or a mob
  claim no place (`deflect.item/.mob`, `deflect.unseen.item/.mob`). Training answers rank the bot's own spots first and
  the asker's side of the sea first; a spot that needs a ride is said with its town (`train.far`, "cloud terrace in ludi
  was good for me"). Proper lines lost their semicolons; a veteran quotes the exact chance on about half its drop answers
  and never below 0.01%.
- The past (`PastSynthesis`, `PersonalHorizon`): common knowledge after the personal bands. The PQs whose band it passed
  in the PQ's region (the lobby, the stages with monsters, those monsters at 100 kills each and their drops), and the 3
  busiest open fields (capacity, then spawns) of every band up to its level in the region it lived in at that band. Recall
  caps 128 maps and 64 mobs (were 48 and 48). A drop of 10% or more (at the live rates) of a mob it knows counts as seen.
- Decisions: `FarmSites.creditSource` credits FARM_ITEM only with its camp's droppers (the anywhere fallback is GATHER's,
  whose visit goes to them); a dropper that spawns only inside one PQ is the new PQ_ONLY verdict at any level ("those
  only drop inside kpq. run it with me?"); refusal lines name the NPC as bots say it ("happy in happyville") and read
  right for one boss ("those only drop from zakum"). `RosterService.farmPick` no longer loads candidate maps. GATHER:
  the ledger charges no upkeep for a tick where every remaining quest item credits 0 (knowledge on), and assign or
  delegate adds the reason for the first item it cannot get ("Olwen: fairy wings? only the snowmen and angry snowman drop
  those, happy in happyville calls em up. can't farm that."). Citizen gear: an owned copy is worn, not bought again; 45%
  of seeds wear the tier about ten levels under the best; 20% of slots keep a piece still within 20 levels of the band;
  25% of citizens without a cape stay without one.
- Hosts: `PresenceScheduler.openKnowledge` (with the first `visited`) and `ResidentDialogue.openKnowledge` catch and log,
  so a knowledge failure never fails a login or a materialize.
- Content: towns.yaml moves "pt at pig beach anyone" and "im so tired of snails" into `town.lfg.newbie` (maxLevel 20) and
  `town.smalltalk.newbie` (maxLevel 15), so Orbis and Ludibrium citizens (35+) never say them; fields.yaml sets capacity 0
  for the three fields nobody can walk into (103000906, 200060001, 261020700).
- Tests: new cases in `PersonalKnowledgeTest` (a busy round, a failed read, forget while off, a slow companion row),
  `PersonalAnswersTest` (a resident's own refs; item and mob deflections never say "been"; no answer names an event map;
  a name is all things said so), `FarmSitesTest` (PQ_ONLY; FARM_ITEM credit at a town is null), `WorldKnowledgeDecisionsTest`
  (event gear not obtainable, Pianus gear is; PQ lobbies and instances), `NameIndexTest` (Kerning Square),
  `CitizenGearTest` (a crowd is not one kit; an owned pick is worn), `BenchUpkeepTest` (a GATHER it can do nothing for
  costs nothing), `BenchLedgerFarmCreditTest`, `FarmItemSiteTest` (the GATHER warning). `KnowledgeProbeTest` gains four
  field-boss WHERE_IS positives at the end (85), so every earlier line keeps its seed. Timing assertions take the best of
  three runs; the budgets are unchanged.
- Docs: this section, the Status line, 7.1's recall caps, 7.4's write-behind, 7.6's changeset note, 8.3's refusal table,
  9.10's config rule, file count and rollback note; the lint mains' javadoc; changeset 45's comment.

**Deviations from the findings' suggested fixes**
- C3 (event gear): a forced return alone would also drop field bosses' rooms (Mushmom's house, Jr. Balrog's sanctuary,
  Pianus's cave, Dead Mine), so the open rule takes a region and either no forced return or a non-copy id (below
  9300000). 208 items stop being obtainable, 39 of them non-cash equips: the anniversary Maple gear, Astaroth's Andras
  set, MV's hats, KPQ's Squishy Shoes, and drops of instanced Zipangu and Showa bosses.
- B6 (sparse past): the floor is per band in the region lived at that band, not every lived region at every band (that
  collapsed A and B to 1 differing line); it adds maps only, since a known map's monsters are known and their common drops
  now count as seen.
- B5 (rates): the companion world is ready at once rather than taking a vague flag from its level.
- B7 (wording): companions and the level horizon keep the step-3 `deflect` pool, so the legacy path stays byte-identical;
  only synthesized pasts get the item and mob pools.
- B13 (GATHER): a warning on the Ok line, not a refusal; the duty still serves the items it can get.
- B11 (gear clones): no reqLevel weighting; the three draws above are stable per citizen and slot, so `upgrade` stays
  idempotent.
- B15 (content lint): the loadouts are unchanged. The lint already says those pieces are "carried, never worn"
  (`canWearEquipment` refuses them), so no player sees over-level gear; the visible effect is an empty slot, a content
  choice. Only the three unreachable fields changed.
- R1 (changeset 44): no merge here (the worktree stays on 1c2331636). The resolved files are in
  `tmp/knowledge-research/merge-44/` and were verified in a scratch tree: `git archive e363b3cec` plus this worktree's
  166 changed and new files, `config.yaml` and `LivingWorld.java` merged cleanly by `git merge-file`.
- R5 (lint mains and config.yaml): documented, not changed: an id the server WZ lacks is a content error with the feature
  on or off, and reading config.yaml without YamlConfig is a second parser for one directory.
- R6 (timing flakes): the best of three runs, not a 3x margin or a perf tag.

**Measured**
- Seed probe (`KnowledgeProbeTest`, 85 positives): recall 1.000, 97/97 negatives silent. Personal: answered legacy 78, A
  65, B 64, V 72, K 78; A answers 65 of the 78 lines legacy answers (83%; before the pass 54 of 74, 73%), 21 of 22
  Victoria town lines; A and B differ on 12; K equals the level horizon except the rate lines.
- Live probe (read-only, `tmp/knowledge-research/probe-live-steps4-6.txt`): run with the original 81 positives, the step-3
  section is byte-identical to `probe-live-step4.txt`, and on those 216 lines answered legacy 98, A 76 (was 68), B 76
  (70), V 89 (81), K 98; A and B differ on 14; K differs only on the two rate lines. With the 85 positives: 112 of 220
  lines from the all-knowing bot; legacy 102, A 80, B 80, V 93, K 102. Every field boss now gets its own town ("where is
  mushmom" -> around henesys); "what drops kpq coupons" -> "farm ligators at kpq" for A, B and V (was "no idea").
- Budgets: 31.6 us per question (legacy benchmark), 23.9 us with a full world, 400 full worlds 1.35 MB (3.5 KB each; was
  1.24 MB), synthesis p50 0.95 ms, p95 1.57 ms, max 2.1 ms for levels 1-120 (was p95 1.25 ms).
- Content lint (`tmp/knowledge-research/lint-content.txt`): living world 0 ERROR, 47 WARN (the same set as before);
  population 0 ERROR, 5 WARN (the three unreachable-field warnings are gone). The open rule added no warning.
- Sweep (the 9.10 command, `tmp/knowledge-research/sweep-fix-4-6.log`): 2,055 run, 10 failures and 1 error, the
  pre-existing set with order-normalized messages identical to step 4's; 59 numeric constants, 23 compatibility imports,
  13 Cosmic-audit entries. `KnowledgeFileFreshnessTest` green; nothing the exporter reads changed, so no re-export.
- Merged scratch tree (e363b3cec + this worktree, `tmp/knowledge-research/sweep-merged-e363b3cec.log`): the 9.10 sweep
  plus `PqRunPaceSchemaTest`: 2,146 run, 10 failures and 1 error, the same pre-existing set;
  `RosterStateWriterBoundaryTest` 6/6, `PqRunPaceSchemaTest` 4/4, `KnowledgeFileFreshnessTest` green. The boundary
  counts there are 59 / 23 / 13, equal to the live branch alone (`boundary-live-e363b3cec.log`, same messages), so the
  merge adds no violation.

**Open items**
- The price index repair still needs the user's OK (implementation notes 1-3): the live `living_price_index` keeps the
  0-sample basket seeds under the corrected ids (2040002 80k, 2044501 2.5m, 2040802 900k, 1302004 100k, 1302030 300k,
  2070005 25k, 2070006 90k; read 2026-10-02 by the probe). Proposed: `DELETE FROM living_price_index WHERE samples=0 AND
  item_id IN (2040002,2044501,2040802,1302004,1302030,2070005,2070006)` plus a restart.
- Fairy Wing (thessaly-a-fallen-wing) stays a content decision; GATHER now says so instead of charging for nothing.
- FARM_ITEM rows created before the deploy whose camp has no dropper accrue nothing until the owner gives the order again
  (which moves the camp).
- The loadout pieces above their holders' levels (8 lint warnings) stay carried, not worn.
- Everyone who levelled in Victoria at 21-30 ran KPQ in its synthesized past, and everyone at 10+ in Victoria ran HPQ.
- Hired citizens keep their world open until released; companion and resident answer lines are not overheard; residents
  and companions do not gossip (as before).

**Deploy checklist**
1. Merge `feat/server-knowledge` into `feat/companion-manager`. Four files overlap: `config.yaml` and `LivingWorld.java`
   merge cleanly; take `changelog-tables.xml` (43, 44, 45) and `RosterStateWriterBoundaryTest.java` from
   `tmp/knowledge-research/merge-44/`, and replace the live branch's `src/test/java/server/living/store/PqRunPaceSchemaTest.java`
   with the copy there (44 after 43 and before 45, no longer "last"). Recompile and run the 9.10 sweep plus
   `PqRunPaceSchemaTest`; expect the known pre-existing set only.
2. `config.yaml` `knowledge:` block: `ENABLED`, `CONTENT_DIR`, `CITIZENS`, `COMPANIONS`, `RESIDENTS`, `HORIZON_LEVEL_SLACK`,
   `MAX_REPLY_CHARS`, `LOG_UNRESOLVED`, `PERSONAL`, `VETERAN_LEVEL`, `DECISIONS`, `CITIZEN_GEAR` (ASCII). It ships exactly
   with the jar; a rollback restores both (or deletes the block).
3. Liquibase at that boot: `44-pq-run-pace` (if the live server has not applied it yet) and `45-living-knowledge`
   (`living_known_world`), each with a `-down` file. 71 SQL files after the merge. `SeedData` skips both (DDL).
4. Content: `scripts/config/knowledge/` (new; aliases.yaml's LPQ stage), personas' `knows:`, population `regions.yaml`
   (lobbies), `chatter/towns.yaml`, `fields.yaml`.
5. The price index repair above, only with the user's OK.
6. Re-export: none needed now (e363b3cec changes no wz/ or scripts/{npc,event,reactor,portal,map,quest,item} file). Run
   `tools\knowledge-export.bat` after merging any branch that does, and keep `KnowledgeFileFreshnessTest` green.
7. Kill switches: `PERSONAL`, `DECISIONS`, `CITIZEN_GEAR` (gear already given stays).
8. Live checks: the boot log's `[knowledge] loaded ...` line and no `living_known_world is missing` warning;
   `!knowledge status` (personal on, flush rows); `!knowledge known <citizen>`; `!knowledge @<citizen> where is mushmom`;
   "farm kpq coupons" and "farm fairy wings" to a companion; a GATHER assign with a Fairy Wing quest; a lv70+ citizen's
   gear; OPQ citizens at 200080101 answering "where is opq" in full.

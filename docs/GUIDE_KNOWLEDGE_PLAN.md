# Guide knowledge plan: what players know from the guides

Status (2026-10-24, `feat/guide-knowledge`, not merged): G1 crawl built and running; G2 distiller built and trialled
on 3 threads by a stand-in model, full run waits for a logged-in `claude` CLI; G3 import, G4 answers and G5 question
shapes built with tests; G6 not started. No `guide-knowledge.json` is committed yet, so the server behaves as before
until the first import. Builds on `docs/SERVER_KNOWLEDGE_PLAN.md` (the `server.knowledge` index, horizon, voices,
decisions), which stays the source of truth for facts.

## 0. Goal

`server.knowledge` makes bots right about *facts* of this server: who drops what, where an NPC is, what level a map
is. It cannot answer what players learn from each other: *where should a level 35 sin train, how much dex for a
bandit, what do i bring to zakum, how does apq work, is a ilbi worth it.* Today those lines fall through to the model
with no facts (OPINION) or get a level-only training map.

Player-written v83 guides hold that knowledge. This plan turns them into short claims, checks every claim against
the server's own data, and gives the bots an index they can answer from and start their own choices from.

## 1. Sources (surveyed 2026-10-24)

| Source | State | Use |
|---|---|---|
| royals.ms forum, Guides (57) and Class Guides (109) | Up. XenForo 1, robots allows `/forum/`. ~30 + ~26 listing pages (~1,100 threads), 2013-2026. MapleRoyals is a v83 server, so the content era is right; its own changes (HP washing tweaks, donor NPCs, events, custom quests) must be filtered. | Main source. |
| southperry.net | Up. MyBB, no robots.txt. Mostly post-Big Bang GMS, but 2008-2010 threads survive with their dates (MapleGuides, Training Center, Game Mechanics, job forums). | Threads started before 2010-07-14 (GMS Big Bang) only. |
| sleepywood.net | Domain does not resolve. Last archive.org snapshot 2023-09; archive.org was offline on 2026-10-24. | Later, from archive.org. |
| BasilMarket | Dead; archive.org only. | Later, from archive.org. |
| hidden-street.net | Up behind Cloudflare; robots.txt disallows ClaudeBot/anthropic-ai and reserves AI rights. It is a database, which the WZ export already covers. | Not used. |

## 2. Constraints

- **Server data wins.** This server is rebalanced (KuroMS skills, custom rates, zeroed stat requirements, any class
  in any gear, 307 new equips). A guide claim that names something missing here, or puts a map at the wrong level, is
  dropped at import. Claims that rest on numbers (skill %, rates, drop chance, exp/hr, prices, formula constants) are
  dropped too: the guides' numbers are GMS or MapleRoyals numbers.
- **Forum posts belong to their authors.** Raw pages and parsed threads live only in a local corpus folder outside
  git (`FriendServer/guide-corpus/`). The repo gets claims written fresh in our own words (the distiller is told never
  to copy sentences), each with its source thread URL as attribution.
- **Immersion.** Claims carry a level range and classes; the existing horizon decides whether a bot knows one. A
  level-12 does not explain Zakum prequests. Bots say claims through `say` lines in their voice, never as citations.
- **Cost.** Distilling is a one-off batch on the user's Claude plan (`claude -p`). Answering costs 0 Jev / 0 LLM:
  in-memory lookups like the rest of `server.knowledge`.
- **Crawling is polite.** One request per 3 s per host, robots.txt honoured, an honest user agent, every page cached
  so re-parsing never re-fetches.

## 3. Pipeline

```
 forums --crawl.py--> guide-corpus/threads/<source>/<id>.json      (local, never committed)
        --distill.py (claude -p, JSON schema)--> guide-corpus/claims/<source>/<id>.json   (local)
        --GuideImportMain (NameIndex + server data checks)--> scripts/config/knowledge/guide-knowledge.json (committed)
                                                         \--> tmp/guide-import-report.md (dropped claims and why)
 runtime: GuideKnowledge (loaded with WorldKnowledge) -> ContentAnswers (TRAIN_WHERE, OPINION, BUILD, HOW_TO) + decisions
```

## 4. Claims (`tools/guide-corpus/claims-schema.json`)

Per thread chunk the model returns `usable`, `skip_reason` and up to 40 claims:

| Field | Meaning |
|---|---|
| `topic` | training_spot, party_quest, boss, quest, job_advance, build_ap, build_sp, gear, item_value, meso_making, mechanic, travel, skill_use, general_tip |
| `jobs` | branch keys (beginner, warrior, fighter-line, ..., aran); empty = any class |
| `level_min`, `level_max` | character levels the claim is for; 0 = not given |
| `entities` | kind (map, mob, item, npc, quest, pq, boss, skill, region) + in-game name |
| `claim` | one plain sentence, our wording |
| `say` | the same advice as a player would type it in chat (lowercase, < 90 chars) |
| `depends_on_numbers` | rests on a number that may differ here |
| `strength` | consensus, author_opinion, contested |

## 5. G1: crawl (`tools/guide-corpus/crawl.py`) - done 2026-10-24

Stdlib Python. Parses XenForo 1 (royals) and MyBB (southperry) listings and threads, keeps the first 3 pages of each
thread, flags the starter's posts (`is_op`). Southperry: listings sorted by start date, stops at the Big Bang cutoff.

## 6. G2: distil (`tools/guide-corpus/distill.py`)

- Prefilter: opening posts under 600 chars, trade/housekeeping titles.
- Opening posts joined, split into 30k-char chunks (at most 6); one `claude -p --model sonnet --json-schema` call per
  chunk with `distill-prompt.md` as the system prompt, no tools, no settings, no MCP, an empty cwd.
- The child gets the environment without `CLAUDE*`/`ANTHROPIC*` (a CLI started inside a Claude Code session inherits
  `CLAUDE_CODE_SIMPLE`, which forces API-key auth).
- Resumable (one output file per thread), 3 calls at a time, waits out usage limits, stops when not logged in.
- **What ran instead (2026-10-24):** the CLI was never logged in, so agents inside a Claude Code session did the
  distilling, one per batch (`tools/guide-corpus/agents/`: `make_batches.py`, `AGENT_TASK.md`, `collect.py`). Same
  prompt, same schema, same per-thread output files. 99 batches, 6 at a time, about 15M agent tokens in ~4 hours.
  Each agent costs ~90k tokens of overhead before it reads its batch, so short threads go 40 to a batch.

## 7. G3: import (`server.knowledge.GuideImport`, `tools/guide-import.bat`)

Offline, like the content lint: `KnowledgeFile` + travel + aliases + Liquibase seed data -> `WorldKnowledge` ->
`NameIndex`.

1. Resolve each entity with `NameIndex.resolve` (map -> MAP/REGION, mob -> MOB, boss -> MOB/PQ, item -> ITEM,
   npc -> NPC/ROLE, quest -> QUEST, pq -> PQ, region -> REGION/MAP). Only alias, exact, scroll and one-typo matches
   count, plus a subset match that differs only by a leading "the" or series numerals ("The Magic Rock",
   "Jr. Boogie 1"). The chat index knows quests only through aliases, so a quest also resolves by its full WZ name
   (two words or more). A region or map name that only a PQ alias knows resolves to the PQ ("Mu Lung Dojo"). Skills
   are not in the index: their names are checked against String.wz (also "Improving Max HP" for "Improved MaxHP")
   and kept as tags.
2. Drop a claim when a named entity does not resolve, when `depends_on_numbers` is set, when a training_spot names
   no map or monster, when a training map's or monster's level here is far outside the claim's levels
   (more than 15 under `level_min` or 20 over `level_max`), or when the claim or its `say` repeats ten words in a row
   of its own thread (the claims must be reworded; facts are kept, sentences are not).
3. Normalise: levels swapped when reversed, `say` trimmed and lowercased, a branch key absorbs its line keys, the
   same advice from several threads kept once with every thread in its `src`.
4. Write `guide-knowledge.json` (sources with URL/title/author/date, claims with entity refs and `src`) and
   `tmp/guide-import-report.md` (counts per drop reason, the most frequent unresolved names: alias candidates).
   Spots are not stored: storing (map, class, band) rows grew to ~350 rows for 10 claims (regions and monsters with
   many spawn maps), so the server derives them from the training claims at load with the same map/level rule
   (`GuideKnowledge.spotMaps`) the import checks with. Support for a map = distinct threads among the claims covering it.

## 8. G4: runtime index and the existing answers

`server.knowledge.GuideKnowledge`, immutable, loaded and published with `WorldKnowledge` (same reload, same
`!knowledge status`). Indexes: claims by entity key, by topic, and spots by (job key, band).

- **TRAIN_WHERE**: maps that guides recommend for the asker's class and level rank before the mob-level-distance
  tie-break; the horizon and the bot's known world still decide what it knows. The best claim goes in as a
  `content.guide` fact.
- **OPINION**: up to two claims about each resolved entity (highest support, within the bot's horizon) become
  `content.guide.*` facts for the model.

## 9. G5: new question shapes

- **BUILD** ("what do i put my ap in", "how much dex for a sin", "what skills first as a cleric"): build_ap /
  build_sp claims for the asker's (or the named) class and level; reply with the claim's `say` in the bot's voice.
- **HOW_TO** ("how do i do zakum prequest", "how does apq work", "tips for horntail", "what do i bring to pap"):
  party_quest / boss / quest / job_advance claims about the resolved entity, gated by the horizon.
- Detector rules, reply lines and tests follow the existing `ContentQuestionDetector` / `ReplyBank` patterns.

## 10. G6: decisions

Claims as priors, never scripts (the learner and live measurement decide):
- citizen and companion training-map choice: guide spot support as a prior weight;
- persona AP builds and SP order: the build claims as the starting point the combat learner adjusts.
Where these hook in is decided after G4, by reading the current choosers.

## 11. Acceptance

- G1: corpus counts per source; spot-check 5 threads per source for clean text and OP flags.
- G2: trial on 3 threads (done by a stand-in model), then the full run; report usable %, claims, cost.
- G3: import report reviewed; 20 random kept claims checked by hand against the server; unit tests on the drop rules.
- G4/G5: unit tests per answer path; live check with the client (ask a companion where a level 35 sin should train,
  what to bring to Zakum, how much dex for a bandit).

## 12. Implementation notes (2026-10-24)

- **Trial (3 threads, 90 claims).** 18 kept before the prompt and match fixes. Drops: 36 unresolved names (19 of them
  "Romeo and Juliet Party Quest": aliases.yaml had only kpq/lpq/opq/hpq/cwkpq as PQs), 31 "rests on numbers" (the
  model also flagged level ranges and recipe counts), 5 level mismatches. Several rejected monsters (Ashigaru,
  Petrifighter, Booper) are not on this server: the check works as intended.
- **PQ aliases (2026-10-24).** aliases.yaml gained 21 `kind: pq` rows (LMPQ, RJPQ, PPQ, EPQ, APQ, CPQ, GPQ, Zakum PQ and
  expedition, HT PQ and expedition, Pink Bean, Papulatus, Balrog, Showa, Scarga, Dojo, Ariant, Boss Rush, MV's Lair,
  Cafe PQ) and full names on the 5 old rows; research in `FriendServer/guide-corpus/pq-aliases-draft.md`. Some lobbies
  are towns (CPQ Orbis, LMPQ Ludibrium, Boss Rush Henesys, Ariant, MV NLC): harmless, `pqOf` skips towns and towns
  have no forced return. Not added (they collide with mob/map aliases): zakum, horntail/ht, papu. "balrog" and
  "pink bean" now answer WHERE questions with the PQ. The trial re-import kept 43 of 90 claims (was 23).
- **Prompt fixes after the trial:** what counts as a number (only values this server may change), map names without
  the street, PQ full names listed, no monster stats in claims, `say` under 70 chars (`knowledge.MAX_REPLY_CHARS`).
- **Name matching.** Exact, alias, scroll and one-typo matches count; a subset match counts only for a multi-word map
  or region name (WZ names carry the street: "Dungeon, Southern Forest I"). Series numerals do not count in names, so
  a spot is right to the series, which is what a training answer needs.
- **Composite refs** are stored as `KIND:name@first id` and looked up with `NameIndex.composite`: composite ids are
  indexes into aliases.yaml order and shift when it changes.
- **Duplicates.** The same advice from several threads is kept once, but every thread still counts toward the spot's
  support.
- **Loading.** `KnowledgeLoader` loads the guide file whenever it builds a new snapshot (boot, `!knowledge reload`);
  a broken file costs the guide answers only. `!knowledge status` ends with the guide line. Kill switch
  `knowledge.GUIDES`.
- **TRAIN_WHERE.** Spots guides give for the asker's class (or the class named in the line) and level band rank right
  after the bot's own spots; guide maps on the asker's side of the sea that the bot knows join the candidates. Half
  the time the reply is the guide's own `say` line for the pick (through the voice's finish); the claim text always
  goes in as `content.guide`.
- **BUILD / HOW_TO.** BUILD is a keyword shape checked before TRAIN_WHERE ("where do i put sp at 30"). HOW_TO is a set
  of weak slot rules before the "how do i join/start X" WHERE_IS rule, answered through the normal slot pipeline
  (ask-backs, horizon, repeat and budget limits). Claims whose `level_min` is past the bot's horizon are not known.
  With no guide claims about the subject HOW_TO stays silent, as before; BUILD says "idk" (truth over coverage).
- **Hosts.** `ContentChat.kind` reports BUILD/HOW_TO only while guide claims are loaded, so with the switch off or no
  file every host behaves exactly as before. With claims loaded, an open BUILD/HOW_TO question no citizen can answer
  goes unanswered (the content tier's rule) instead of reaching the model; a citizen named in the line still falls
  through to chat.

## 13. Open questions

- Sleepywood and BasilMarket through archive.org once it is back up.
- Whether guide `say` lines need the voice transforms used for `ReplyBank` lines, or are short enough as they are.
- Memory: the claim count after import decides whether claim text is kept in heap or only `say` lines.
- Only thread starters' posts are kept, so question threads carry no answers (306 of 1,112 threads unusable). Replies
  from other posters would add answers, at the cost of weaker attribution and more noise.
- `depends_on_numbers` is the distiller's own judgment; a few build claims with GMS numbers (HP per AP) got through.
- "zakum prequest tips" is not a HOW_TO shape (only "tips for X" is).

## 14. Results (2026-10-24)

- **Corpus:** royals.ms 658 threads (Guides + Class Guides), southperry.net 1,223 threads started before Big Bang.
  1,112 distilled (royals 395, southperry 717); the other 769 skipped: starter's posts under 600 chars, trade or
  housekeeping titles, or low-value titles (calculators, trackers, cosmetics, patch notes: 175).
- **Distilled:** 8,406 claims from 806 usable threads.
- **Imported:** 6,107 kept from 767 threads (73%): boss 538, build_sp 711, mechanic 643, skill_use 626, party_quest 622,
  gear 605, training_spot 585, quest 437, build_ap 372, general_tip 370, item_value 349, meso_making 114,
  job_advance 108, travel 90. Dropped: 923 rest on numbers, 844 name something not on this server (Petrifighter,
  Krexel, Anego, Ulu City, Temple of Time), 309 spots far from their levels, 83 skills not on this server, 66 copy
  the guide's wording, 52 spots naming no map or monster, 18 name a server or forum, 4 duplicates.
- **At load:** all 6,107 claims resolve (`droppedAtLoad` 0); 32,710 (map, class, band) training spots derived.
  `guide-knowledge.json` is 2.9 MB.
- **Aliases added for the guides:** regions Maple Island, Phantom Forest, Crimsonwood Mountain, Singapore (without
  Changi Airport and the flight), Eos Tower; mobs "showa boss" (The Boss), "wiseman solomon"; NPCs Carta the Sea
  Witch, Amos, Hughes, Neinheart/"nineheart", Mu Young. "the boss" cannot be an alias: "the" and "boss" are both
  dropped by the slot rules.
- **Answer ranking:** claims more guides agree on rank after consensus and before class-specific; HOW_TO puts the
  subject's own topic first (party_quest for a PQ, boss for a monster, quest for a quest).

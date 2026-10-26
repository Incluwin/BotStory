# Citizens remember the people they meet (plan)

Branch `feat/citizen-person-memory`, cut from `ff6fbe48a` (feat/companion-manager, live 2026-10-25). The gap, from
docs/POPULATION_GUIDE.md "Not done yet": "conversation memory of a human is the social relationship memory plus the
journal, not a per-human summary". A citizen you partied with, traded with or talked to yesterday greets you like a
stranger.

## 1. What exists today (evidence)

| Piece | Where | What it keeps | Why it is not a memory of you |
|---|---|---|---|
| Shared minutes | `living_acquaintance` (`db/tables/030-population.sql`), `CitizenStore.java:32` (`Acquaintance`), read and written every minute per (citizen, human) on one map in `SocialFabric.java:558-586` | party minutes, map minutes, buddy/guild asked, `last_at` | Counters only. They drive the buddy request (`SocialFabric.java:429-444`), the guild invite (`:367-387`), the Jr. Master promotion (`:349-364`), the invite decision (`CitizenInviteDecision.java:194-202`) and the hire list (`CitizenStore.metBy`, `:313-328`). Nothing says what happened, when or where. |
| Journal | `Journal.java:15-28`, one per `CitizenSession` (`CitizenSession.java:33`) | 48 plain-language entries of the session ("partied with...", "Ann said ...") | In memory, per session: it is gone when the citizen logs off. Not keyed by person. |
| Life summary | `living_citizen.life_summary`, read by `Conversation.java:700-702` | meant to be the weekly fold of the journal (plan 6.4) | Nothing writes it (only `CitizenStore.save` copies the field through), so "my recent weeks" is always blank. Not about any one person. |
| Engine relationship memory | `server.agents.social.memory.*`; `SocialRelationshipMemory.java:30-46` | familiarity/trust/affinity, a count of conversations, the last 8 turns | Persists only to the separate Postgres behind `SOCIAL_DB_ENABLED` (`SocialPostgresDataSource.java:13-14`, default false). `FriendServer/.env` sets no `SOCIAL_*`, so live it is JVM memory and every restart forgets it. It only counts generated conversations ("Spoken with 3 times"), never a party, a run or a trade, and feeds one summary string into the prompt. |
| Residents' friendship | `server.living.bond.Friendship`, bond points + `living_event_log` daily counts (`Friendship.java:144`, `:213-220`; `LivingStore.java:1702-1715`) | a 1-10 meter per (player, Resident persona) | Residents only: "Hired citizens are not Residents and keep no friendship" (`Friendship.java:26`). `living_event_log` is an append-only log keyed by a persona string, read for daily caps and GM records; nothing prunes it. |
| Greeting | `Conversation.java:176-179` | "hey <name>" or "hi", a coin flip | Same for a stranger and an old friend. |
| Model grounding | `Conversation.facts`, `Conversation.java:678-714` | persona card, session journal, scene, rules | Takes the speaker's id and never uses it. |

Live database, 2026-10-25 (read-only counts): `living_acquaintance` has 1,141 rows for 7 humans and 479 citizens
(730 active); the most people any citizen has met is 6 (2.4 on average); 197 rows are walk-bys of 2 minutes or less,
399 rows 30 minutes or more, 14 rows with party minutes; the oldest `last_at` is 2026-09-21.

### Why extend `living_acquaintance` and not add a store

- The row already is the (citizen, human) pair, is written every minute they share a map, and `last_at` (ON UPDATE
  CURRENT_TIMESTAMP) already says when they last were together. A memory is a few more columns on that row.
- `living_event_log` cannot hold it: append-only, keyed by persona string rather than citizen id, no per-pair bound,
  and other features count its rows for daily caps, so pruning it would be pruning their data.
- The engine's Postgres relationship memory is off live and only knows generated conversations.
- A separate table would duplicate the pair key and `last_at`, and need its own retirement cleanup.

Existing writers stay untouched: `saveAcquaintance`'s `ON DUPLICATE KEY UPDATE` names only its own columns, so the
memory columns are written by their own statement, and the many `new Acquaintance(...)` call sites (and their tests)
cannot wipe a memory.

## 2. Design

### 2.1 What a citizen remembers about one person (`PersonMemory`, pure, unit-tested)

- `warmth`, -100..100: how it feels about them. It fades toward 0, halving every `MEMORY_WARMTH_HALF_LIFE_DAYS` (14),
  from `memory_at` (the last time it changed).
- `first_met_at`: when the first thing worth remembering happened ("we met a couple of weeks ago").
- Up to `MEMORY_EPISODES_MAX` (6) episodes: a kind, when, where (map id), a count, a short detail (quest key, item
  name, what they asked about). Repeats coalesce: talk within 2 h, a party stint within 3 h on one map, the same quest
  outcome within 30 min, the same item trade within 1 h each add to the count instead of a new episode.

| Episode | From | Weight | Warmth |
|---|---|---|---|
| TALK: they talked to it | `Conversation.respond` | 2 | +2 |
| THANKED: they thanked it | content tier, kind THANKS | 3 | +4 |
| HELPED: it answered their question | `Conversation.deliverAnswer` | 3 | +2 |
| PARTY: a party stint together (count = minutes) | `SocialFabric.acquaintances`, same party | 2 + min(6, minutes/10) | +1 per 10 min, +8 at most a stint |
| PQ_CLEAR / PQ_FAIL: a run together | `CitizenPartyQuests.finish` (citizen runs); `LivingWorldHooks.partyQuestCleared` (a citizen guest in a player's run) | 10 / 6 | +12 / +4 |
| PQ_LEFT: they left the citizen run before its end and it failed | `CitizenPartyQuests.finish` | 8 | -60 |
| BOUGHT: it bought at their shop | `Market.shop` | 5 | +3 |
| SOLD: they bought at its stall | `Market` stall sales (its shop's sold list) | 5 | +5 |
| FAMED / DEFAMED | `LivingWorldHooks.famed` | 5 / 7 | +8 / -60 |

An episode's weight halves every `MEMORY_HALF_LIFE_DAYS` (10); under weight 1 it is forgotten. So a chat lasts about
10 days, a trade 23, a cleared run 33. Under weight 3 it is only a feeling: it still counts, it is no longer brought
up by name. A memory with no episode left and warmth under 1 is forgotten whole (its columns cleared; the shared
minutes stay).

Recall wording follows age, the way people misremember: earlier (under 6 h), yesterday (under 30 h), the other day
(under 6 days), last week (under 14 days), a while back. Older episodes come out vague ("long time no see").

### 2.2 How it is used

1. **Reunion greeting.** In the minute loop (`SocialFabric.acquaintances`), the first sight of someone it remembers
   after `MEMORY_RETURN_AFTER_MINUTES` (180) apart: if it is free (loaded, not AFK, no stall, not in an event instance)
   and within earshot (700 px), and they are not hidden (a GM), it greets them on map chat by name, bringing up the
   strongest episode it still remembers in detail: "Ann! that kpq yesterday was fun", "oh hey Ann, thx again for the
   maple shield", "Ann... u gonna stay this time?". One citizen greeting per player per `MEMORY_GREET_GAP_MS` (5 min),
   so a crowd of friends doesn't all wave at once. Out of earshot it waits for a later minute of the same visit.
2. **"hi" from someone it remembers** gets a remembered hello ("hey Ann! back again?") instead of the coin flip.
3. **Model grounding.** `Conversation.facts` adds "what i remember about Ann" (at most 256 chars): when they met, the
   episodes in recall wording with places, and a feeling word (fond / friendly / don't know them well / wary /
   annoyed). The model brings up the shared run or trade itself.
4. **Warmth changes behaviour.** A warm memory (warmth >= 15) makes them someone it knows for the invite decision
   (`CitizenInviteDecision.Familiarity.known`) and it never ignores their lines on purpose (`Conversation` tier 0).
   A cold one (warmth <= -30): see decision D1.
5. **Ops.** `!citizens memory` (cache and table counts) and `!citizens memory <citizen>` (who it remembers, warmth,
   episodes) for the live checks.

### 2.3 Hot paths, threads, persistence

- `PersonMemories` keeps a cache of memories in use: (citizen, human) pairs seen this session or touched by an event.
  Events from the chat thread, the market and PQ ticks and the fame handler only change the cache (no JDBC). An event
  for a pair not loaded yet queues its change; the next social tick loads the row and applies it.
- The social tick (every minute, LOW_PRIORITY, where the acquaintance reads and writes already run) loads the new
  pairs (one query each, the first time in a citizen's session), and writes the changed memories in one JDBC batch.
  A pair seen again is not re-read.
- A citizen logging off flushes and drops its entries; `Population.stop` flushes everything before the citizens
  unload, so a restart keeps what happened up to the last event.
- Reads on hot paths (the greeting tier, grounding) use the cache only; a pair not cached reads as no memory.

### 2.4 Storage and bounds

Changeset `49-citizen-person-memory`, `db/tables/040-citizen-person-memory.sql` (rollback `-down.sql`) adds to
`living_acquaintance`: `warmth SMALLINT NOT NULL DEFAULT 0`, `memory_at TIMESTAMP NULL`, `first_met_at TIMESTAMP NULL`,
`episodes VARCHAR(600) NOT NULL DEFAULT ''`, and `INDEX idx_living_acq_memory_at (memory_at)` for the prune.
Timestamps go through `SqlTime` (UTC sessions). Episodes are a compact text codec (`kind|epochMinute|mapId|count|detail`
per line, detail at most 32 ASCII chars), so 6 episodes stay under 600 chars.

| Bound | Key | Default | Enforced |
|---|---|---|---|
| Episodes per person | `MEMORY_EPISODES_MAX` | 6 | the codec keeps the heaviest |
| People per citizen | `MEMORY_PEOPLE_MAX` | 30 | when a citizen starts remembering someone new and already remembers that many, it forgets the least memorable one |
| Remembered pairs server-wide | `MEMORY_TOTAL_MAX` | 20,000 | hourly prune: clears memories faded out (no change for `MEMORY_FORGET_DAYS`, 60) and, past the cap, the oldest |
| Cache entries | (code) 4,096 | | clean entries of citizens no longer online go first |
| Retired citizens | | | `SocialFabric.onRetire` clears its memories |

Worst case: 20,000 rows x about 650 bytes = 13 MB. At today's numbers (7 humans, 6 people per citizen at most) it is
a few hundred rows. Clearing a memory sets `last_at = last_at` so it does not look like a meeting.

`MEMORY_ENABLED` (population block) is the kill switch: false reads and writes no memory and every behaviour above is
as before (greeting coin flip, plain invite decision, no grounding line).

## 3. Decisions (the user, 2026-10-25: both as recommended)

- **D1 Grudges: cool and decline.** A let-down (leaving a citizen's run early, a defame) makes the greeting and the
  grounding cooler, and while warmth is at or under -30 a reserved or easygoing citizen turns down that player's party
  invite (outgoing ones, buddies and guildmates still accept). It fades in about two weeks. (Not chosen: tone only.)
- **D2 On by default.** `MEMORY_ENABLED: true`, like the other population features; false is the kill switch.

## 4. Tests

- `PersonMemoryTest`: episode weights and coalescing, warmth decay by half-life, forgetting under the floor, the
  episode cap keeps the heaviest, recall wording by age, the codec round-trip (bad rows read as no memory), salience
  ordering for the per-citizen cap.
- `PersonMemoriesTest` (mocked store): events before a load are applied after it, one batch write per tick for dirty
  entries, logout flushes and evicts, the per-citizen cap forgets the least memorable, the kill switch writes nothing.
- `SocialFabricReunionTest`: a remembered player back after 3 h is greeted by name with the episode; not within 3 h;
  not out of earshot; one greeting per player per gap; a stranger gets none.
- `ConversationMemoryTest`: "hi" from a remembered player gets the remembered hello; the grounding line; a warm
  player is not ignored.
- `CitizenInviteDecisionTest` additions: warm counts as known; cold declines per D1.
- Content: the `memory.*` chatter triggers are known to the lint; `PopulationContentTest` still loads.
- Targeted runs first, then `config.AgentConfigurationBoundaryTest` and the content lint, then the full split suite.

## 5. Live checks (after the merge and deploy; not run by this session)

L1. Talk to a citizen in Henesys ("hi <name>", one question). `!citizens memory <name>` shows a TALK and a HELPED
    episode for you with a positive warmth.
L2. Restart the server (start-maple-stack -RestartServer). `!citizens memory <name>` still shows them.
L3. Come back to that citizen's map more than 3 h later (or after the restart with `MEMORY_RETURN_AFTER_MINUTES`
    lowered for the check): it greets you by name in map chat; a second citizen that remembers you stays quiet for
    5 min.
L4. Run KPQ with a citizen party to the clear. `!citizens memory <leader>` shows PQ_CLEAR; the next reunion greeting
    names the KPQ.
L5. Leave a citizen run mid-way: PQ_LEFT and a negative warmth; the next greeting is cool; per D1 an easygoing citizen
    turns down your invite.
L6. Buy from a citizen's FM stall: SOLD for you on that citizen.
L7. Ask the citizen something open ("what have u been up to") with generation on: the reply can mention the shared
    run; the grounding line is in the dialogue debug log.
L8. `!citizens memory` after an hour: the prune ran; rows stay under the caps; the social tick time (log) did not grow.
L9. Set `MEMORY_ENABLED: false`, restart: greetings are the coin flip again and nothing new is written.

## 6. Not in scope (follow-ups)

- Citizens do not answer whispers at all (`LivingWorldHooks.whisper` routes only to Residents).
- The weekly life summary (plan 6.4) has no writer, so "my recent weeks" is always blank.
- Memories of other citizens (bot-to-bot) stay the circle/guild ties and gossip they are.

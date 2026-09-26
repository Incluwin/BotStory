# Population: how to run, tune and author the citizens

Implementation of `docs/POPULATED_SERVER_PLAN.md` (first cut, 2026-09-21). Code lives in
`server.living.population.*` and runs inside the living world; content is YAML under
`scripts/config/population/`. Read `docs/LIVING_WORLD_GUIDE.md` first for the shared pieces
(residents, quests, companions, the MapleEdit page).

## Switches

`config.yaml` -> `population:` (needs `living_world.ENABLED: true`). The ones you will touch:

| Key | Meaning |
|---|---|
| `TARGET_CITIZENS` | how many citizens the registry keeps alive; births refill after retirements |
| `ONLINE_MAX` | hard cap on citizens loaded as characters at once; the rest are online only on the ledger |
| `CHANNELS` | comma list of channels citizens are spread across |
| `PRESENCE_MULTIPLIER` | scales the cohort curves (0.5 = half as many online at any hour) |
| `LIFE_PACE`, `SEED_BACKDATE_DAYS` | how fast citizens level and how old the seeded population looks |
| `CHATTER_*` | lines per minute per town map, per-citizen cooldown, megaphones per hour |
| `CONVERSATION_*` | generated replies per player per day, per-citizen cooldown, concurrency |
| `GUILDS_ENABLED`, `GUILD_TARGET`, `BUDDIES_ENABLED`, `BUDDY_REQUEST_AFTER_MINUTES` | social fabric |
| `MARKET_ENABLED`, `MARKET_STALLS_MAX`, `MARKET_BUY_BUDGET_PER_DAY` | Free Market participation |

Tables come from Liquibase changeset `30-population` (`living_citizen`, `living_circle`,
`living_guild`, `living_price_index`, `living_acquaintance`). Generated replies use the existing
social dialogue runtime: Ollama at `SOCIAL_OLLAMA_ENDPOINT` / `SOCIAL_OLLAMA_MODEL` (env), mode
`SOCIAL_DIALOGUE_MODE` (`DIALOGUE_ONLY` = model with deterministic fallback, `DETERMINISTIC_ONLY`
= templates only, free).

## What happens

**Seeding.** An empty registry births citizens in batches of 12 per presence sweep until
`TARGET_CITIZENS` (about ten minutes for 120). Each birth draws a cohort, a career, a voice and
flags by weight, generates a name that passes the same checks as a player's (syntax, uniqueness,
the Jev name screen), creates an Agent-only account and character with a random look, backdates
its join date (squared random, so most are newcomers) and shapes it to the level its age and pace
say it should have (job chain, growth, AP, gear from the living-world loadouts). Verified: 120
citizens, levels 1-82, average 34, 22 beginners, every job branch represented.

**Presence.** Every `SWEEP_MS` offline citizens log in with a probability derived from their
cohort's hour weight, so the online fraction follows the curve (evenings busy, 4am dead, weekends
different). A session lasts a cohort-typical time and picks an activity by cohort weights:
`TRAIN` at a field in its region whose mob level fits (capacity-aware, biased to maps humans are
on), `TOWN` (town-life visit where a profile exists, standing spots otherwise), `PQ` at the
region's lobby if the level fits, `MARKET` (Free Market), `AFK`. Every 25-45 minutes it re-plans;
15% of the time it goes AFK for 5-15 minutes ("afk"/"back"). Travel between maps is instant when
nobody can see either side and paced by the road-hop ETA when someone can.

**Progression.** Loaded citizens level by fighting. The ledger raises the stored level of offline
citizens toward their life-curve target (`0.5 + level/20` hours per level, 1.2 h a day times pace,
slowing to a fifth past the career cap) so the rank board moves without anyone loaded; the next
load repairs growth, advances jobs, upgrades gear and tops up pocket money.

**Churn.** Citizens past their retirement date stop logging in (a goodbye if anyone can hear it)
and `BIRTHS_PER_DAY` newcomers are born while below target.

**Chatter.** Events (a level-up, death, rare drop, boss, login/logout, afk/back, arriving on a
map, idle dwell, party forming, a megaphone slot) propose templated lines to citizens in range.
A citizen speaks with a probability from its personality's sociability, subject to a per-citizen
cooldown, a per-map token bucket (`CHATTER_TOWN_LINES_PER_MINUTE`, fields a third), and a memory
of recent templates. Slots are filled (`{name} {level} {item} {price} {map} {guild} {pq} ...`),
the voice pack lowercases/abbreviates/typos the line, and it is delivered after a typing delay on
map, party, guild or buddy chat or as a megaphone. Nothing here calls a model.

**Social.** `GUILD_TARGET` bot guilds are founded by level-10+ citizens (real guilds with emblem,
motto, capacity 30) and recruit citizens whose level fits, same cohort preferred. Citizens form
circles of 3-5 (mutual buddies) that log in near each other and party up when on the same map;
at a PQ lobby a forming party invites a fitting human. Shared minutes with a human on a map or in
a party accumulate; after `BUDDY_REQUEST_AFTER_MINUTES` one buddy request is sent, and recruiters
send one guild invite. Famers fame buddies, guildmates and nearby humans once a day.

**Conversation.** A human line in map chat near citizens goes through: silence (the voice pack's
ignore rate, unless called by name), templates (greetings, "where is X"), one Jev `citizen-address`
Choice when several citizens are near and it is unclear who was meant, then generation through the
social dialogue runtime with grounding facts installed (who I am, my recent weeks, what happened
this session, scene, rules). Replies are styled by the voice pack and delayed like typing. Budgets:
`CONVERSATION_PER_PLAYER_PER_DAY` generated replies per human (then "gtg, ttyl"), a per-citizen
cooldown, `CONVERSATION_MAX_CONCURRENT`.

**Market.** `market.yaml` seeds a price index (`living_price_index`) that drifts toward observed
sales. Merchant citizens on a `MARKET` session walk into a Free Market room and open a real stall
with what they looted (up to `MARKET_STALLS_MAX` stalls); browsing citizens buy goods priced under
90% of the index from human shops within `MARKET_BUY_BUDGET_PER_DAY`. Chatter reads the index for
`S> {item} {price}`.

## Ops

`!citizens` (GM 5): `status`, `online`, `cohorts`, `who <name>` (card, targets, journal),
`birth [cohort] [backdateDays]`, `retire <name>`, `purge confirm` (retire everyone, reseed),
`force <n>` (keep n loaded regardless of the hour - load tests), `sweep`, `reload`, `guilds`,
`market`, `conversation`.

AdminControlPort: `GET /runtime/citizens` (census, online, cohorts, recent chatter, counters),
`POST /actions/citizens` `{"force": n, "sweep": true, "reload": true}`. MapleEdit's Living World
page shows all of it under "Citizens right now" and edits the population files (saving runs
`tools\population-lint.bat` semantics on a temp copy).

Tools: `tools\population-lint.bat` validates content; `tools\population-fields.bat` regenerates
`fields.yaml` from `wz/` (730 open-world maps: name, region, mob count, average mob level,
capacity; Neo City is skipped). Logs: `citizen born`, `citizen X online/offline`, `bot guild`,
`circle formed`, `opened a stall`; `[chatter]` lines at debug on `server.living.population.ChatterEngine`.

## Authoring

| File | What |
|---|---|
| `cohorts.yaml` | when each cohort is online (24 weekday + 24 weekend weights 0-10 in its zone, peak %), session length, activity mix, share of births |
| `careers.yaml` | job path (final job name), popularity, level cap, loadout by level band (living-world loadout ids) |
| `names.yaml` | name patterns with slots and syllable lists; era flavour |
| `voices.yaml` | how a citizen types: style id, lowercase/typo/abbreviation/emoticon rates, pet phrases, typing speed, ignore rate |
| `guild-names.yaml` | guild name patterns, mottos, emblem palette |
| `regions.yaml` | level bands, main town maps (+ optional `townWeights`, one per town, so hubs like Henesys/Kerning draw most town life), PQ lobbies, FM entrance per region |
| `fields.yaml` | generated map catalog; prune or add maps freely |
| `market.yaml` | permit item and the market basket with seed prices |
| `chatter/*.yaml` | intents: `trigger` (enter, dwell, levelup.other/self, death.other/self, drop.rare(.self), boss, jobadvance.other, login, logout, login.buddy, smega, party.forming, party.invite, price, afk, back, lag), `where` (town, field, fm, lobby, any, or `mapIds`), `channel` (map, party, guild, buddy, smega), `weight`, `cooldownMs`, `lines`, optional per-style `styles` |

Lines are ASCII; `{slots}` that cannot be filled drop the line. The lint refuses unknown
triggers, channels and jobs, loadouts that do not exist, and non-ASCII.

## Sizing and measuring

The first cut ran 120 citizens and 30 loaded. A presence sweep with 6 logins took ~300 ms; a
birth (account, character, shaping) ~250 ms. Raise `TARGET_CITIZENS` and `ONLINE_MAX` in steps
while watching `sweepMs` in `!citizens status`, GC in `logs/gc/`, and the client on a crowded
town; `MAP_VISIBLE_MAX` and the field capacities are the per-map brakes. `!citizens force <n>`
is the load-test knob: it keeps n citizens loaded regardless of the hour.

Measured 2026-09-21 (16-core laptop, `-Xmx3072m`): 600 citizens / 240 loaded costs about one
CPU core per ~130 loaded citizens (grinders dominate) and ~1.4 GB working set at 138 loaded.
The next lever is `ONLINE_MAX` 320-400 with `-Xmx4096m`, but only with ~2 GB more RAM free than
this machine had at the time. Crowding is mostly a *distribution* problem: `townWeights` in
`regions.yaml` concentrates town life in hubs, and the cohort activity mix decides how many are
in towns/FM versus out grinding, which is where they are neither seen nor cheap.

## Not done yet (from the plan)

Citizens do not run party quests themselves (they gather at lobbies, form parties and invite you;
the existing KPQ automation is not wired to them yet); no expeditions; no alliances; travel is a
map change with a delay rather than walking through portals; conversation memory of a human is the
social relationship memory plus the journal, not a per-human summary; the client-side visible cap
(`MAP_VISIBLE_MAX`) is not yet enforced beyond field capacities and town admission.

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
citizens, levels 1-82, average 34, 22 beginners, every job branch represented. Past the last loadout band (lv30-40), a
citizen of level 10+ in a job wears obtainable gear of its own level band and job from server knowledge each time it
loads (`knowledge.CITIZEN_GEAR`, docs/SERVER_KNOWLEDGE_PLAN.md 8.4; never a downgrade, the weapon keeps its type, and gear
already given stays when the flag is off). Clerics, priests and bishops of level 30+ train on undead-majority fields 70%
of the time when one is in band (8.5).

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
load repairs growth, advances jobs, upgrades gear and tops up pocket money. Every load also repairs the AP, SP and HP of
levels the ledger wrote alone and spends SP along the job chain's profiles, a first job left unspent included.

**Jobs (2026-10-06).** `careers.yaml` names a second job; a citizen's life goes on along that branch to the fourth
(FIGHTER: CRUSADER at 70, HERO at 120; `CitizenJobPath`). The first and second job come off-screen with the catch-up when
it loads. The third and fourth it takes like a player (`CitizenJobErrands`): once its life reaches the level, its next
session (or its next re-plan, if it got there fighting) starts on its instructor's map (El Nath's Chief's Residence
211000001, Leafre's Forest of the Priest 240010501), it walks up to its chief or elder (at most `JOB_ERRAND_WALK_MAX_MS`),
stands `JOB_ERRAND_TALK_MS`, advances with the stock job change, says a `jobadvance.self` line (others there say
`jobadvance.other`) and goes about its day. SP banked past Lv70 goes into the new job's skills. Moved away or logged out
on the way: no job yet, it goes again with its next plan; refused: again next session. Citizens already old enough for
their next job when this shipped were flagged `job-backlog` once (world flag `population.job-backlog-marked`, 105
citizens on 2026-10-06): that job came off-screen at their next load, and they mention it a little after logging in
(`jobadvance.offscreen.self`). `JOB_ERRANDS_ENABLED: false` is the kill switch: every job off-screen at the next load.

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
cooldown, `CONVERSATION_MAX_CONCURRENT`. Content questions ("who drops steelies") are answered from server knowledge
as each citizen knows it (`knowledge.PERSONAL`, docs/SERVER_KNOWLEDGE_PLAN.md 7): a past rebuilt from its id, level and
home region (training spots per five levels, their towns and mobs, the drops those kills showed), what it killed, looted,
bought and walked through, and true answers to open questions it overheard; party, circle and guild mates online
together pass first-hand facts to each other silently. Only veterans (`knowledge.VETERAN_LEVEL`, or old hands within 20
levels of it) quote drop percentages and rate numbers. Known worlds are written to `living_known_world` every 5 minutes.

**Market.** `market.yaml` seeds a price index (`living_price_index`) that drifts toward observed
sales. Merchant citizens on a `MARKET` session walk into a Free Market room and open a real stall
with what they looted (up to `MARKET_STALLS_MAX` stalls); browsing citizens buy goods priced under
90% of the index from human shops within `MARKET_BUY_BUDGET_PER_DAY`. Chatter reads the index for
`S> {item} {price}`.

## Ops

`!citizens` (GM 5): `status`, `online`, `cohorts`, `who <name>` (card, targets, journal),
`birth [cohort] [backdateDays]`, `seed [regionId count]` (below), `retire <name>`, `purge confirm` (retire everyone, reseed),
`force <n>` (keep n loaded regardless of the hour - load tests), `sweep`, `reload`, `guilds`,
`market`, `conversation`, `level <name> <level>` (raise an offline citizen's stored level as the ledger does; never
lowers), `login <name>` (load that citizen now, replacing a ledger-only session).

**Testing job errands live.**
1. Pick an active second-job citizen below Lv70 that is offline: `!citizens who <name>` shows its job and flags.
2. Type `!warp 211000001` (El Nath's Chief's Residence), so you are there when it arrives.
3. Type `!citizens level <name> 70`.
4. Type `!citizens login <name>`. The reply says `JOB at map 211000001`, going for its third job.
5. Watch it walk up to its chief, stand a moment, change job (the job effect) and say a line.
6. Type `!citizens who <name>`. The journal says `became a crusader`, and the job is the third job.
7. For the backlog path, pick a citizen with flag `job-backlog` and type `!citizens login <name>`. It loads with its
   third job, the flag is gone, and within a minute it mentions the job in map chat.

AdminControlPort: `GET /runtime/citizens` (census, online, cohorts, recent chatter, counters),
`POST /actions/citizens` `{"force": n, "sweep": true, "reload": true, "seed": "regionId", "count": n}`. MapleEdit's Living World
page shows all of it under "Citizens right now" and edits the population files (saving runs
`tools\population-lint.bat` semantics on a temp copy).

**Targeted seeding.** A citizen's home region is picked once, at birth, from the regions whose band fits its level, so
after the first seeding almost every newcomer is a level-1 Victoria citizen. To fill one region, queue seeds for it:
`!citizens seed <regionId> <count>` (1-200 a call; `!citizens seed` alone lists region ids with their bands and the
queue) or `POST /actions/citizens {"seed": "orbis", "count": "12"}` (replies with `queued`, `band` and `seedQueue`; the
seed is checked after a `reload` in the same request and before `force`/`sweep` are applied; `sweep: true` births the
first batch on the HTTP thread). `GET /runtime/citizens` shows `seedQueue` and `seedSkipped`. Each sweep (`SWEEP_MS`)
births up to 12 queued seeds in request order before the usual churn, and fast seeding only gets what is left of those
12. A seed's home region is forced; its career, pace and backdate (at most `SEED_BACKDATE_DAYS`) are drawn the way fast
seeding draws them until its level lands in the band, so seeds look like the citizens who settled there on their own
(orbis lv40-70: median 54; leafre: median 74). Its life is drawn from lives at least 30 days longer than its age rather
than clamped to that, so a batch retires gradually over the following months (leafre seeds: about 140 days on average).
`TARGET_CITIZENS` does not limit queued seeds, but they count toward it: below target they take the place of fast-seed
births; at or above it they are extra until retirements bring the registry back under target, and ordinary births
(mostly level-1 newcomers) pause meanwhile. Seeds only reach what a career gets to in `SEED_BACKDATE_DAYS`: with the
shipped careers (caps 70-85), `LIFE_PACE` 0.8 and 180 days that is lv86, so leafre (lv70-120) gets lv70-86 and the
listings say "seeds reach lv86". Higher seeds need higher-cap careers in `careers.yaml`. A band no career lands in is
refused when queued; a seed that still cannot be born (content reloaded meanwhile, creation failed) is skipped, logged
and counted in `seedSkipped`. The queue is in memory only: a restart drops whatever is still pending.

Tools: `tools\population-lint.bat` validates content (map and item ids against the server knowledge: an
unknown id is an error; PQ lobbies away from their NPC, training fields players cannot walk into, unfarmable
basket goods and career gear above its band are warnings); `tools\population-fields.bat` regenerates
`fields.yaml` from `wz/` (894 open-world maps whose id prefix `FieldCatalogMain.region` maps to a regions.yaml
id: name, region, mob count, average mob level, capacity; Neo City is skipped; a map already listed keeps its
capacity, so the estimate and the Victoria capacity catalog only seed new maps). Logs: `citizen born`,
`citizen X online/offline`, `bot guild`, `circle formed`, `opened a stall`; `[chatter]` lines at debug on
`server.living.population.ChatterEngine`.

## Authoring

| File | What |
|---|---|
| `cohorts.yaml` | when each cohort is online (24 weekday + 24 weekend weights 0-10 in its zone, peak %), session length, activity mix, share of births |
| `careers.yaml` | job path (final job name), popularity, level cap, loadout by level band (living-world loadout ids) |
| `names.yaml` | name patterns with slots and syllable lists; era flavour |
| `voices.yaml` | how a citizen types: style id, lowercase/typo/abbreviation/emoticon rates, pet phrases, typing speed, ignore rate |
| `guild-names.yaml` | guild name patterns, mottos, emblem palette |
| `regions.yaml` | level bands, main town maps (+ optional `townWeights`, one per town, so hubs like Henesys/Kerning draw most town life), PQ lobbies, FM entrance per region |
| `fields.yaml` | generated map catalog; edit capacities freely (0 = nobody trains there), other edits are regenerated; every region id must be a `regions.yaml` id and every region town a `town: true` entry of that region |
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

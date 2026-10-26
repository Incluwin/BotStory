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
| `PQ_RUNS_ENABLED`, `PQ_RUN_QUESTS`, `PQ_RUNS_PER_CHANNEL`, `PQ_RUNS_MAX` | citizen parties run party quests themselves (kill switch, which quests, runs at once per channel / in all) |
| `PQ_FORM_PATIENCE_MS`, `PQ_HUMAN_SEAT_HOLD_MS`, `PQ_RUN_COOLDOWN_MS`, `PQ_CALLS_PER_PARTY` | a lobby party gives up after; a seat waits for an invited human; no PQ plan after a run; citizens a short party calls over |
| `SHUTDOWN_LOGOUT_THREADS`, `SHUTDOWN_LOGOUT_BUDGET_MS` | server shutdown: loaded citizens unload (a full save each) on this many threads for at most this long; the rest unload with their channel |
| `MEMORY_ENABLED`, `MEMORY_PEOPLE_MAX`, `MEMORY_EPISODES_MAX`, `MEMORY_TOTAL_MAX`, `MEMORY_FORGET_DAYS` | citizens remember the people they meet (kill switch; people per citizen, things per person, remembered pairs server-wide, forgotten when unchanged this long) |
| `MEMORY_HALF_LIFE_DAYS`, `MEMORY_WARMTH_HALF_LIFE_DAYS`, `MEMORY_RETURN_AFTER_MINUTES`, `MEMORY_GREET_GAP_MS` | how fast episodes and warmth fade; apart this long is a reunion; one reunion greeting per player this often |

Tables come from Liquibase changeset `30-population` (`living_citizen`, `living_circle`,
`living_guild`, `living_price_index`, `living_acquaintance`). Changeset `48-citizen-person-memory` adds the memory columns to
`living_acquaintance`. Generated replies use the existing
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
15% of the time it goes AFK for 5-15 minutes ("afk"/"back").

**Travel** (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md, `PORTAL_TRAVEL_*`). A citizen on a map a player is on goes to
another map on foot. It walks to the right portal and goes through it, map to map along the route graphs. At a dock it
walks up to the boarding NPC and is gone the way a boarding player is. As soon as the map it stands on is empty, it
takes the cheap way: it stands there for the old pause (1 s, or the road-hop ETA's quarter when a player is on the
destination) and changes map. A player on the destination sees it come in at the portal its route enters by. A player
who comes onto its map during the pause sees it walk from where it stands. With no way on that a player could watch,
it goes back to what it was doing until nobody watches ("waits for nobody to watch" in the log); it never vanishes in
front of a player. `PORTAL_TRAVEL_ENABLED: false` restores the delayed map change everywhere.

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

**Social.** `GUILD_TARGET` bot guilds are founded by level-10+ citizens (real guilds with an emblem
that fits the name, motto, capacity 30; see `GuildEmblems`) and recruit citizens whose level fits, same cohort preferred. Citizens form
circles of 3-5 (mutual buddies) that log in near each other and party up when on the same map;
at a PQ lobby a forming party invites a fitting human. Shared minutes with a human on a map or in
a party accumulate; after `BUDDY_REQUEST_AFTER_MINUTES` one buddy request is sent, and recruiters
send one guild invite. Famers fame buddies, guildmates and nearby humans once a day.

**Party quests (2026-10-24, docs/CITIZEN_PARTY_QUESTS_PLAN.md).** A citizen on a `PQ` session at a lobby whose quest is
in `PQ_RUN_QUESTS` (KPQ: Kerning City, Lakelis, lv21-30; HPQ since 2026-10-25: Henesys Park, on the floor below Tory's
ledge, lv10-20) waits beside the quest's NPC instead of strolling the town (`CitizenPartyQuests`, every 10 s). The
first there opens a party and asks for people ("lf2m kpq"), the others whose level fits join it, and a party-less human
whose level fits gets the leader's invite before the last seat goes to a citizen: the seat waits
`PQ_HUMAN_SEAT_HOLD_MS` for the answer, and the party never goes in while it is out (the invite is withdrawn after). A
party short of its seats calls citizens over from a minute on, one a minute, at most as many as it still needs and
`PQ_CALLS_PER_PARTY` in all: first a loaded circle or guild mate whose level fits and who is training or in town
("anyone wanna kpq?", "omw"), else a citizen of the lobby's region idling in town whose PQ band it is, who just walks
over (live, a party that called one mate only gave up "nobody came" twice in its first hour); one short of the quest's
minimum after `PQ_FORM_PATIENCE_MS` gives up ("nvm gonna go train") and keeps out of party quests for 10 minutes. With
the minimum in (3, and 90 s for the quest's preferred 4) it goes in by the quest's own admission
(`AgentKpqAdmissionService.admitCitizens`, `AgentHpqAdmissionService.admitCitizens`), its citizen leader leading and
clicking the NPC, only when: fewer than `PQ_RUNS_PER_CHANNEL` / `PQ_RUNS_MAX` citizen runs are on; the Agent scheduler
sheds no load; every member stands at the NPC, alive, in the quest's band with room for its items (a human who does not
hears why, once); and the engine's lobby policy leaves a lobby free for humans plus one for each human party waiting
there. Citizens in a party a human leads are that human's business. During the run presence leaves its citizens alone
(no re-plan, AFK or logout; a session that runs out logs off after it), the quest's coordinator holds full rate for
them in the instance (the old background cadence never finished KPQ's stage 1), runs it on its Agent-led path without
its status lines (KPQ's puzzle calls in the casual lines companion runs use; in HPQ the leader asks a human for any
rice cakes he picked up, since Growlie takes them only from the leader, and tells him to claim at Tory without anyone
waiting for him), and a downed citizen who is not the leader taps out of the party ("died lol gl guys") while the rest
carry on with three. The end books the run in each citizen's journal ("cleared KPQ with Ann, Bo"), the tally and the
last ten runs (`!citizens pq`), says "gg"/"rip" in party and map chat and thanks a human who came along; each citizen
leaves the quest's party once it stands outside the instance and plans its next activity (no PQ for
`PQ_RUN_COOLDOWN_MS`), or logs off if its session ran out. The engine's older background KPQ and HPQ directors get no
citizens for a quest citizens run themselves (HPQ's lobby policy also counts citizen runs against its background caps);
`PQ_RUNS_ENABLED: false` gives them back to it and leaves citizens gathering and inviting as before.

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

**Memory of people** (docs/CITIZEN_PERSON_MEMORY_PLAN.md). A citizen remembers each player it has met: what happened
between them (they talked, it answered their question, they thanked it, a party stint, a party quest cleared or failed
together or left early, a trade at a shop or a stall, a fame or a defame), when and where, and how it feels about them
(warmth -100..100), on the pair's `living_acquaintance` row. Meeting again after `MEMORY_RETURN_AFTER_MINUTES` apart it
greets them by name on map chat from within earshot, bringing up the strongest thing it still remembers in detail (`Ann!
that KPQ yesterday was fun`; `chatter/memory.yaml`), at most one citizen per player per `MEMORY_GREET_GAP_MS`; a "hi"
gets a remembered hello; the model's grounding gets "what i remember about Ann". Warmth 15 and up makes them someone it
knows for its invite decision and it never ignores their lines on purpose; a let-down (left its run before it failed, a
defame) is a grudge (warmth -30 or under) for about two weeks, when a reserved or easygoing citizen turns down their
party invite (outgoing ones, buddies and guildmates still accept). Episodes fade (a chat in about 10 days, a cleared run
in about a month), and so does warmth; at most `MEMORY_PEOPLE_MAX` people a citizen and `MEMORY_TOTAL_MAX` pairs in all
(an hourly prune). Events change a cache only; the social tick reads a pair's row once a citizen session and writes the
changes in one batch a minute; a logout and the shutdown write what is left.

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
lowers), `login <name>` (load that citizen now, replacing a ledger-only session), `pq` (citizen runs now and since boot,
the last ten), `pq send <name>` (a loaded citizen goes to the lobby its level fits, out for a party quest).
`memory` (the memory cache and table counts), `memory <name>` (who that citizen remembers, warmth, what happened).

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
| `guild-names.yaml` | guild name patterns, mottos, emblems: the 16 client colours, background shapes, and per name word its marks, colours and shape (an old bot guild whose emblem the client cannot draw gets its name's emblem on the first social tick) |
| `regions.yaml` | level bands, main town maps (+ optional `townWeights`, one per town, so hubs like Henesys/Kerning draw most town life), PQ lobbies, FM entrance per region |
| `fields.yaml` | generated map catalog; edit capacities freely (0 = nobody trains there), other edits are regenerated; every region id must be a `regions.yaml` id and every region town a `town: true` entry of that region |
| `market.yaml` | permit item and the market basket with seed prices |
| `chatter/*.yaml` | intents: `trigger` (enter, dwell, levelup.other/self, death.other/self, drop.rare(.self), boss, jobadvance.other, login, logout, login.buddy, smega, party.forming, party.invite, price, afk, back, lag, and the party-quest ones in `chatter/pq.yaml`: pq.lf, pq.join, pq.go, pq.clear, pq.fail, pq.down, pq.giveup, pq.call, pq.call.answer, pq.thanks, pq.wait), `where` (town, field, fm, lobby, any, or `mapIds`), `channel` (map, party, guild, buddy, smega), `weight`, `cooldownMs`, `lines`, optional per-style `styles`; `{need}` is how many more a PQ party wants. Party-quest lines repeat as players repeat them: no recent-intent memory, only each intent's own `cooldownMs` |

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

Citizen parties run KPQ and HPQ (LPQ waits, the user's call 2026-10-25: LudiPQ has one lobby a channel; OPQ after the
OPQ live work; docs/CITIZEN_PARTY_QUESTS_PLAN.md "Round 2"; at the LPQ and OPQ lobbies they still only gather and
invite you); no expeditions; no alliances; travel on foot ends on the first map nobody watches (no citizen walks a
whole multi-map trip unseen) and rides no real ship; citizens remember players (each other only through circles,
guilds and gossip) but answer no whispers, and the weekly life summary of plan 6.4 is never written; the client-side
visible cap (`MAP_VISIBLE_MAX`) is not yet enforced beyond field capacities and town admission.

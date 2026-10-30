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
| `BOSS_RUNS_ENABLED`, `BOSS_RUN_BOSSES`, `BOSS_RUNS_MAX`, `BOSS_RUN_SEATS` | citizen parties run Balrog expeditions themselves (kill switch, `easy_balrog`/`balrog_normal` only, expeditions at once in all, the party they fill to) |
| `BOSS_NORMAL_MIN_LEVEL`, `BOSS_RUN_COOLDOWN_MS`, `BOSS_SIGNUP_WAIT_MS` | six citizens at this level go Normal; no PQ or expedition plan after one; how long the leader waits for a human to sign up |
| `SHUTDOWN_LOGOUT_THREADS`, `SHUTDOWN_LOGOUT_BUDGET_MS` | server shutdown: loaded citizens unload (a full save each) on this many threads for at most this long; the rest unload with their channel |
| `MEMORY_ENABLED`, `MEMORY_PEOPLE_MAX`, `MEMORY_EPISODES_MAX`, `MEMORY_TOTAL_MAX`, `MEMORY_FORGET_DAYS` | citizens remember the people they meet (kill switch; people per citizen, things per person, remembered pairs server-wide, forgotten when unchanged this long) |
| `MEMORY_HALF_LIFE_DAYS`, `MEMORY_WARMTH_HALF_LIFE_DAYS`, `MEMORY_RETURN_AFTER_MINUTES`, `MEMORY_GREET_GAP_MS` | how fast episodes and warmth fade; apart this long is a reunion; one reunion greeting per player this often |
| `MINIGAMES_ENABLED`, `MINIGAME_TOWNS`, `MINIGAME_THINK_MIN_MS`/`MAX_MS`, `MINIGAME_GAMES_MIN`/`MAX` | citizens play omok and match cards (kill switch, off by default; the towns; think time per move; matches a session) |
| `MINIGAME_ROOM_WAIT_MS`, `MINIGAME_IDLE_MS`, `MINIGAME_STRONG_PERCENT`, `MINIGAME_COOLDOWN_MS` | an empty room closes after; a room where nothing happens is left after; citizens who play well; no PLAY plan after one for |
| `NEEDS_ENABLED`, `NEEDS_PER_CITIZEN`, `NEEDS_REFRESH_MINUTES` | the needs board ("Needs board" below; kill switch, off by default); item requests a citizen keeps open at once; how often a loaded citizen's are derived again |
| `NEEDS_EXPIRE_MIN_HOURS`/`MAX_HOURS`, `NEEDS_REPOST_HOURS`, `NEEDS_SCROLL_CHANCE`, `NEEDS_SUPPLY_PREMIUM` | a potion or scroll request ends after; the same one is not asked again for; chance a derivation asks for a scroll; a potion's offer over its shop price |
| `NEEDS_GEAR_WANT_SHARE`, `NEEDS_GEAR_DEADLINE_HOURS` | decision D4: share of free gear upgrades held back as a want, and how long until it gets the piece anyway |
| `NEEDS_CLAIMS_PER_PLAYER`, `NEEDS_CLAIM_MINUTES`, `NEEDS_WHISPER_GAP_MINUTES` | requests a player holds at once and for how long; one request whisper per player this often |
| `CLEAR_RECORDS_ENABLED` | every listed PQ and boss clear is booked in `living_clear` ("Rival crew and records" below; on: it only measures) |
| `RIVALS_ENABLED`, `RIVALS_MIN_LEVEL`, `RIVALS_CREW_MIN`/`MAX` | rival crews, the records page and `@records` (kill switch, off by default); the main's level from which it gets a crew; crew size |
| `RIVALS_JOIN_BAND`, `RIVALS_LEAVE_BAND`, `RIVALS_REFRESH_MINUTES` | levels from the main to join (D12: 5) and to leave (7); how often a crew is checked while its player is online |
| `RIVALS_BANTER_GAP_MINUTES`, `RIVALS_HELP_GAP_HOURS`, `RIVALS_HELP_OPEN_MINUTES` | banter at most this often; a Zakum help request at most this often, open this long |
| `RIVAL_HELP_REQUESTS_ENABLED` | the crew's Zakum help requests (needs `RIVALS_ENABLED`; off until the citizen Zakum run, F7.4b, exists) |
| `LF_SMEGA_ENABLED`, `LF_SMEGA_AFTER_MS`, `LF_SMEGA_PER_PARTY`, `LF_SMEGA_STOCK`, `LF_SMEGA_RESTOCK_CHANCE` | party megaphones ("Jump quests and party megaphones" below; kill switch, off by default, needs the whispers on): a short party megaphones after; megaphones a party sends; Super Megaphones a citizen buys at a load (at most 2) and the chance it does |
| `CITIZEN_WHISPERS_ENABLED`, `WHISPER_REPLY_GAP_MS`, `WHISPER_THREAD_MS`, `WHISPER_JOIN_COMING_MS` | players' whispers reach citizens (kill switch, off by default); one out-of-the-blue reply per player this often; a whisper back this soon answers the citizen's own whisper; a party waits this long for a player who whispered in |
| `JQ_ENABLED`, `JQ_COURSES`, `JQ_FEE_RESERVE` | citizens climb jump quests (kill switch, off by default); `pwr,fop_a` (`fop_b` held, below); mesos kept on top of Shane's fee |
| `JQ_STAGE_SUCCESS_MIN`/`MAX`, `JQ_PRACTICE_STEP`, `JQ_FALLS_MAX`, `JQ_ATTEMPT_MAX_MS`, `JQ_COOLDOWN_MS` | a citizen's chance to clear seven jumps (a shorter stage is easier in step); its rise per clean stage; falls and time before it gives up; no JQ plan after one for |

Tables come from Liquibase changeset `30-population` (`living_citizen`, `living_circle`,
`living_guild`, `living_price_index`, `living_acquaintance`). Changeset `49-citizen-person-memory` adds the memory columns to
`living_acquaintance`. Changeset `51-living-need` adds `living_need` (the needs board's requests); a board hand-in for a
citizen who is away waits in `inventoryitems` as type 14 (`ItemFactory.NEED_ESCROW`) under its character id. Changeset
`53-rival-crew` adds `living_clear`, `living_clear_member` (every recorded clear and who was in it) and `living_rival`
(each player account's rival crew). Generated replies use the existing
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
region's lobby if the level fits, `MARKET` (Free Market), `AFK`, `PLAY` (omok and match cards, "Minigames" below, while
`MINIGAMES_ENABLED`). Every 25-45 minutes it re-plans;
15% of the time it goes AFK: 5-15 minutes in a town, Free Market room or lobby ("afk"/"back"), but on a training
field only a pause of 30 s to `FIELD_AFK_MAX_MS` (at most 2 min, decision D6, "brb water"/"back"), started only at
80%+ HP with no mob within 400 px and ended early under 60% HP or with a mob within 150 px (`FieldPause`; see
"Imperfections" in docs/LIVING_WORLD_GUIDE.md).

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

**Boss expeditions (2026-10-25, docs/CITIZEN_EXPEDITIONS_PLAN.md).** Mu Young's room in Sleepywood (105100100) is a
lobby `balrog` (lv50-120) of every region whose citizens reach Lv50 (Orbis, El Nath, Aquarium, Ariant, Mu Lung, Leafre,
Crimsonwood, Malaysia); a citizen on a `PQ` session whose level fits more than one lobby of its region picks one at
random. There a party forms as at a PQ lobby, with the same human invite, calls and patience, up to `BOSS_RUN_SEATS`;
each citizen first buys what it lacks of the boss's potions at the Ant Tunnel Park store (as a bench ally does), and one
that cannot afford them says so and goes. The party's leader makes the expedition with Mu Young's own window
(`CitizenBossDoor`, `AgentBossRunService.openCitizenExpedition`): Normal for six citizens all `BOSS_NORMAL_MIN_LEVEL`
or more who carry Normal's potions, else Easy (3 or more). The boss run signs the other citizens up through her window,
one talk a tick; a human of the party hears once to sign up by talking to her. Once every citizen is listed and every
human too (or `BOSS_SIGNUP_WAIT_MS` passed) and the scheduler sheds no load, the leader talks her Start. Only bosses the
server moves itself for a fight a bot signed up for (`living_world.COMPANION_BOSS_SERVER_AUTHORITY` with the boss in
`COMPANION_BOSS_ENABLED_TYPES`), so citizens fight Balrog with or without a human (no "no human left" walk-out);
Zakum and up need a human's client to fight back. After the clear they play the reward room as players do
(the box, a human's first 7 s, their share by weapon), then walk out by the stock ways (`CITIZEN_AFTER_CLEAR_MS` at
most). A citizen that goes down says so and leaves the party; the run goes on. The end books it ("cleared Easy Balrog
with ..."), says gg/rip, thanks a human, and each citizen goes back to its day with no PQ or expedition for
`BOSS_RUN_COOLDOWN_MS`. Player first: no list while a Balrog list or fight of either difficulty is open on the channel
or a human of Lv50+ outside their party stands in her room; at most `BOSS_RUNS_MAX` citizen expeditions (1); the last
`COMPANION_BOSS_HUMAN_SEATS` seats of their list stay free for players, who may sign up on it; a player's companions never join it. Log lines
`citizen boss run in: boss= session= ...`, `citizen boss run start: ...` and `citizen boss run: boss= outcome= ...`,
beside the run's own "Boss run ..." lines.

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

**Minigames (2026-10-26, docs/BOT_CONCEPT_PLAN.md F5a).** A cohort weight `PLAY` sends a citizen to a town of its region
in `MINIGAME_TOWNS` whose map allows minigames (Henesys, Ellinia, Perion, Kerning, Ludibrium; the Free Market's
fieldLimit forbids them). It carries an omok set (4080000-4080011, one per citizen) and the match cards, walks to a
bench, and sits down in an open room near it, a player's first (once it has been open about 15 s), else a citizen's;
with none, it sometimes says "omok anyone?" and opens its own, and the balloon shows over its head. A citizen whose room
stays empty 30 s goes over to a player's room that opens nearby. It plays `MINIGAME_GAMES_MIN`-`MAX` matches with 1-8 s
think times and leaves; a room nobody joins closes after `MINIGAME_ROOM_WAIT_MS`, and the second ends the session.
Omok is `OmokAi`: threat-based (finish a five, block one, make or stop a double threat, else the cell that builds the
most five-cell windows), with a per-citizen strength (`MINIGAME_STRONG_PERCENT` play well, the rest casually, plus a
little for every match on their record) that sets how often it notices a threat and how noisy its judgement is. Match
cards is `MatchCardMemory`: it knows only cards turned face up, recalls each with a chance that fades with the turns
since, and forgets what it fails to recall. Its room lines (`chatter/minigames.yaml`: hi, nice, gg, bye) go to the
room's chat on the `room` channel, only with a player across the table; seated in a room it says nothing on the map and
answers no map chat. Records are the stock `omokwins`/`matchcardwins` columns. The rules themselves are
`server.maps.MiniGameRules`, which the packet handler calls too, and the citizen plays through the room's
`MiniGameObserver` (its client drops every packet). Turn order follows the client's: a room's first game opens with its
owner, later ones with the last loser; the opener plays stone 1, corrected by what the other side places. The omok
win scan covers the whole board and every finished game clears it (Cosmic PR #294, for everyone). Log lines
`citizen X plays minigames at <map>`; the journal says `beat Bo at omok`.

**Needs board (2026-10-27, docs/BOT_CONCEPT_PLAN.md F1, `NEEDS_ENABLED`, off by default).** Citizens ask for what they
really lack and pay from their own purse (`server.living.population.needs`, `NeedLedger`). At each load (after its
catch-up and its escrow pickup) and every `NEEDS_REFRESH_MINUTES` while loaded, a citizen's requests are derived from
its real state, at most `NEEDS_PER_CITIZEN` item requests each:
- *SUPPLY*: short of its Easy Balrog standard (`AgentSupplyReadiness.shortfall`), the potion the Ant Tunnel Park store
  would sell it (`AgentShopPotionPolicy`), as many as the missing points take and its purse pays, at the shop price
  times `NEEDS_SUPPLY_PREMIUM` (the price index seeds potions at about 10 meso, so it is not used here). The request
  shrinks as the citizen buys some itself and ends when it carries enough or buys at the store before an expedition.
  Only boss expeditions provision potions; citizens in KPQ/HPQ never restock, so they never ask for party-quest potions.
- *SCROLL*: with chance `NEEDS_SCROLL_CHANCE` a derivation asks for a `market.yaml` basket scroll (never Chaos or Clean
  Slate) for a worn piece that still has slots, weapon first, at its index price when the purse covers it. It ends when
  the piece is gone or full, or the citizen holds that scroll.
- *GEAR* (decision D4): `NEEDS_GEAR_WANT_SHARE` of the free upgrades `CitizenGear.upgrade` would hand it are held back
  and asked for at the index price instead; after `NEEDS_GEAR_DEADLINE_HOURS` it gets the piece as before ("bought it
  elsewhere") and never asks for it again. Stable per citizen and item: every load decides the same way.
- *PARTY*: a citizen party forming at a lobby past its call delay lists itself (a boss party without a cleric asks for
  one, else "N more") until it goes in, gives up or breaks up. It is apart from the cap and cannot be claimed.

Potion and scroll requests end after `NEEDS_EXPIRE_MIN_HOURS`..`MAX_HOURS`, the same request is not asked again for
`NEEDS_REPOST_HOURS`, and a hired citizen's requests end. While the switch is on the random "B> {item}" lines are gone: a
citizen with an open request names it (item, count, price, what for) in FM and town chat and some megaphones, and
whispers it to a player it likes (`WARM` or more) on its map, once a player per `NEEDS_WHISPER_GAP_MINUTES`; a "yes i
have it" back is answered while `CITIZEN_WHISPERS_ENABLED` is on, and a PARTY request's page then names the leader to
whisper (otherwise it never says "whisper me"). The market follows needs: a citizen with an open GEAR or
SCROLL want goes to the FM three times as often, walks to the room whose player shop sells that item at its offer or
less, and buys it up to its offer (above the 90%-of-index rule, outside the browse budget; single pieces; equips base or
better: weapon and magic attack at least the base's, weighted stats at least the base's). The request is then met.

Stock Bulletin Boards (NPC 9040011) are spawned server-side at the places `needs.yaml` lists (the FM entrance and eleven
towns) on every population channel and re-checked every minute; the Guild PQ maps' own 9040011 keeps its stock script
(only the spawned objects open the needs board). A board's menu lists the requests sorted by distance from the board,
then by how well the citizen knows you, with each one's details (who, what, how many, price, where they are, deadline),
Claim (held for you `NEEDS_CLAIM_MINUTES`, at most `NEEDS_CLAIMS_PER_PLAYER` at once) and "Hand it over here"; "Your
requests" lists what you hold; "Records" (F7, while `RIVALS_ENABLED`) is the clear records page. A rival crew's
invitation is listed only for the player it invites. `@requests` (everyone) is the same in chat:
`@requests claim <n>`, `@requests drop <n>`. To hand over in person, double-click the citizen while carrying what it asked
for (tradeable; an equip base or better) and pick how many. Either way one `NEED_FULFILLED` economy transaction moves the
items from your bag into the citizen's (or its escrow while it is away), the offer from its purse (its stored row while
away) to yours, and the request's row. It then uses what it got: its potions go on autopot, gear is worn in its slot, a
scroll is applied (a pass or fail line). Its next load takes the escrow, and thanks for a board hand-in are whispered once
you are both online. Warmth: a `FULFILLED` episode, +8 in person, `FULFILLED_BOARD` +4 at a board, each once a UTC day
per citizen; it greets you with it later. Logs: `citizen <id> needs SUPPLY white potion x100 at 345 ea
(easy_balrog)`, `citizen <id> need ... ended: EXPIRED`, `needs board: N requests live`; the journal says `asked for 100
white potion (345 ea)`.

### Rival crew and records

2026-10-27, docs/BOT_CONCEPT_PLAN.md F7, decisions D12 and D13. Code: `server.living.population.records` and `.rivals`.

**Clear records** (`CLEAR_RECORDS_ENABLED`, on: measuring only).
- Every cleared event instance of a quest that `rivals.yaml` lists is one `living_clear` row, whoever cleared it: players,
  their companions, citizens. The members go in `living_clear_member`.
- `EventInstanceManager.clearPQ` calls `LivingWorldHooks.eventCleared`, once an instance. That is a party quest's end and
  a boss body's death alike: boss scripts call `clearPQ` when the boss dies.
- The row keeps:
  - each member: H player, P companion (a Resident or a hired citizen), C citizen, B another bot;
  - the clear's kind: HUMAN if any player was in it, else COMPANION, CITIZEN or BOT;
  - when it started: the instance's first event timer (a restarted timer keeps it), else its creation;
  - how long it took (untimed when under a second);
  - the rival crew credited with it.
- A script the file does not list (a job instance, a wedding, a boat) is no record.
- The row is written on companion-ops, never on the script's thread. The log line is
  `clear booked: quest= kind= time= crew= members=`.

**Records page** (`RIVALS_ENABLED`).
- A Bulletin Board's menu gains "Records": each quest or boss with a clear, its first clear (who, the UTC date), its
  fastest (m:ss and who), and your own best.
- `@records` (everyone) is the same in chat, one line a quest; `@records <quest>` gives one quest. Players type the
  aliases in `rivals.yaml`: kpq, balrog, zakum, ...
- "First" means first since recording began; nothing older was recorded anywhere.

**The crew** (`RIVALS_ENABLED`). Each player account gets one crew, racing its main: the account's highest-level
character from `RIVALS_MIN_LEVEL` (20); one of its characters online that overtakes it becomes the main at once.
- **Forming.** The crew has `RIVALS_CREW_MIN`..`MAX` (3..5; it takes 4 or 5) citizens within `RIVALS_JOIN_BAND` (5)
  levels of the main.
  - Never picked: a hire, a member of another crew, another guild's master or jr. master, anyone under Lv10.
  - The first pick's cohort is preferred, so they share hours.
  - The crew's name comes from the bot-guild name generator. Its first member founds a real guild under that name, with
    the name's emblem, and the others quit their old bot guild and join it.
  - The crew is also one circle (`living_circle`). They log in together (presence's circle boost) and party up on the
    same map, and their PQ lobby calls go to circle and guild mates first. So the crew runs together without any steering.
  - Crew guilds are kept in `living_rival`, not `living_guild`, so `GUILD_TARGET`, recruiters and alliances leave them
    alone. `isBotGuild` still says yes.
- **Drifting (D12).** Nobody's EXP, level or gear is touched. While the player is online the crew is checked every
  `RIVALS_REFRESH_MINUTES` (30):
  - A member more than `RIVALS_LEAVE_BAND` (7) levels from the main leaves. The band is wider than the join band, so one
    level-up at the edge does not churn.
  - A loaded member says why in guild chat ("u guys level too fast for me lol") and whispers the player, then quits the
    guild half a minute later. An away member quits silently (the guild's own "left the guild" notice).
  - A leaving master first hands the guild to the highest member. A crew left empty has its guild disbanded and
    founds it again later, under the same name if it is free.
  - Citizens in band take the free seats (up to 4), saying hi in guild chat when they are loaded.
  - The crew keeps its name and its records while its members change.
- **Credit.** A clear counts for a crew when two or more of its members are in it and its player is not (a shared run
  is no race). The crew's best per quest comes from those clears.
- **Contact.**
  - Banter: a loaded member on the player's map (not in an instance) says something to them, once every
    `RIVALS_BANTER_GAP_MINUTES` (45).
  - The crew beats one of the player's times while the player is online: one megaphone from a member who was in the run.
    The run must set the crew's own new best. The megaphone is a slot of `CHATTER_SMEGA_PER_HOUR` with the 10-minute gap
    (`ChatterEngine.megaphone(from, text, consumeItem)`, the one megaphone entry point outside the cadence, shared with
    the LF party megaphones of F5c; the crew passes `consumeItem` false, carrying no megaphones in v1). With the cap
    spent, it is a whisper.
  - The player beats the crew's best with a new personal best: a loaded member whispers congratulations. With nobody
    loaded, it is owed for up to a day and said once a member is on while the player is.
  - While the player is away, each crew clear is a `rival.news` row on the main. Their next letters (F3,
    `LETTERS_ENABLED`) bring one note signed by the crew's leader ("hey Ann, Ironclads here. while u were gone we did easy
    balrog in 14:20 (beat ur 16:05 :P)..."). It counts in `LETTERS_MAX_NOTES` and is named in the pink line.
- **Help requests (F7.4, `RIVAL_HELP_REQUESTS_ENABLED`, off: an invitation to a run that does not exist yet would be a
  tell).** The crew asks the player along when all of these hold: 3+ members loaded, their median level in Zakum's band,
  and the main online and in the band.
  - The leader posts a PARTY need on the needs board with the player as the guest (`zakum|guest:<id>`): only that player
    sees it, as "wants you along for zakum", meet at the Door to Zakum (211042300).
  - The leader also whispers the invitation.
  - It happens once per `RIVALS_HELP_GAP_HOURS` (12, stored, so a restart does not ask again) and stays up
    `RIVALS_HELP_OPEN_MINUTES` (30).
  - Whispered replies go to `RivalCrews.replied` once the whisper intake (F5c) routes them (topic "rival", wired at
    integration): while the request is open the first yes word ("ya omw") gets "see u there" and a no word ("nah
    busy") ends the request on the board; anything else, or any reply to a boast or a congratulation, gets a short
    answer.
  - The citizen Zakum run itself is not built (F7.4b, below): the request is the invitation.

The lines are in `chatter/rivals.yaml`; no Jev. Logs: `rival crew '<name>' (<id>) formed for account ...`,
`rival crew '<name>': X joined / is leaving / left`, `rival crew '<name>' beat character N's <quest> time: ...
(megaphone|whisper|letters)`, `rival crew '<name>' asked X along for Zakum`. A member's journal says `joined the
<crew>` / `left the <crew>`.

**Jump quests and party megaphones (2026-10-27, docs/BOT_CONCEPT_PLAN.md F5b-c; `JQ_ENABLED`, `LF_SMEGA_ENABLED`,
`CITIZEN_WHISPERS_ENABLED`, all off by default).**

*Party megaphones.* A citizen-led PQ party still short `LF_SMEGA_AFTER_MS` (4 min) after it formed has its leader send
one megaphone (`LF_SMEGA_PER_PARTY`): "LF1M KPQ 21-30 ch2, whisper Ann" and variants (`pq.smega` in
`chatter/whispers.yaml`). It is a real Super Megaphone (5072000): one is used up per line, and with none in its cash tab
it sends nothing. It is notice type 3 with the whisper button on, through `ChatterEngine.megaphone`, the one megaphone
entry point (rival crews use it too), so it takes one `CHATTER_SMEGA_PER_HOUR` slot and keeps the 10-minute gap of the
merchant and recruiter megaphones. Megaphones are cash items no NPC sells, so a citizen whose level fits a citizen-run
lobby of its region and carries none buys `LF_SMEGA_STOCK` (never more than 2) at a load, with chance
`LF_SMEGA_RESTOCK_CHANCE`. The line says "whisper me", so it goes out only while `CITIZEN_WHISPERS_ENABLED` is on too.

*Whispers.* With `CITIZEN_WHISPERS_ENABLED` a player's whisper to a loaded citizen is answered (`CitizenWhispers`,
reached from `LivingWorldHooks.whisper` after the Residents and the roster declined it; no model call):
- "me", "inv", "can i join", "kpq?" and the like to a short party's leader get its invite by the lobby invite's rules
  (party-less, level fits, a seat free counting held ones, one invite out at a time; the 10-minute lobby-invite gap does
  not apply, they asked). The player may be anywhere in the world: the leader whispers where to come, tells them again
  in party chat once they join, and the party waits `WHISPER_JOIN_COMING_MS` past its patience while they walk over.
- A refusal says why: full, level ("kpq is 21-30 sry"), already in a party, an invite already out. Join words to a
  member who is not the leader point to the leader.
- A whisper back within `WHISPER_THREAD_MS` of something the citizen whispered first (its needs-board request, a fame,
  a rival's words) goes to whoever opened that thread (a topic handler; the needs board's answers a "yes i have it"),
  else gets an acknowledgement ("np", "ty"). Senders tag their whispers with a topic through `ChatterEngine.whisper`.
- Anything else gets a short reply that fits what it is doing ("in pq rn", "training atm", its stall's room, "hi"),
  at most once per `WHISPER_REPLY_GAP_MS` a player, nothing while it is AFK, and sometimes nothing (its voice's ignore
  rate).
- The needs board's PARTY page names the leader to whisper.

*Jump quests* (decision D11). A cohort weight `JQ` sends a Victoria citizen up Pet-Walking Road (100000202, any level)
or, from Lv25 with Shane's 5000 mesos and `JQ_FEE_RESERVE` left over, into the Forest of Patience: course A
(101000100-101, Lv25-49). Course B (101000102-104, Lv50+, quest 2051) is built but left out of `JQ_COURSES`: its
first map is a zig-zag of ropes with a jump off each rope's top, and offline the bot loses that line after the first
rope (`JumpQuestRoutesTest` proves B's other five legs). Breath of Lava stays out: its fire hurts only on the client,
so a bot would walk through it unharmed. Shumi's jump quest (103000900-909) comes later.
- Rewards come from the stock NPCs. Frod at the road's top pays only someone with a pet out (pet closeness), which no
  bot has, so citizens climb the road for fun and leave by its own portal. The Forest's pile (1043000, 1043001) gives
  a random prize, plus Sabitrama's item while quest 2050 (2051) runs, and warps to Ellinia. A citizen whose 2050 (2051)
  can start walks up to Sabitrama in Sleepywood (1061005) first, then on to Shane; after a clear with the item it goes
  straight back to Sabitrama to turn it in (stock rewards).
- A course is cut into stages along the navigation path (`JumpQuestCourses`): seven jumps, or fewer up to a rope's top
  (it stops on the small platform up there, as players do). The citizen climbs from checkpoint to checkpoint with its
  jumps' noise on (`AgentJumpNoise`): the launch x of each jump moves by a draw from N(0, sigma), sigma solved per stage
  (`JumpWindows`) on what the noise itself does there: the jump probe's landing from every pixel a press can reach (a
  one-pixel hole in a landing run costs only that pixel), the press kept on its platform but never moved past
  navigation's own pick (a window in a platform's last pixels is still takeable), and a press the noise refuses drawn
  again (below). Each jump takes its own stage's spread, even where two stages' heights overlap.
  `JumpQuestRoutesTest` checks the noise's own draws against the calibration stage by stage; before, Pet-Walking Road
  disagreed (0.98 cleared where 0.70 was asked, 0.00 where 0.78 was) and a 30% climber missed one road jump in
  fourteen (now one in eight, what its stages ask; 40 seeds). A drawn press is taken on its x (a pixel either way), or
  at rest within half a walk step of it (a precise approach to a jump coasts to rest on its launch), so the takeoff
  is the draw, as the calibration asks. Taken at the first position near it, it came early every time (navigation looks every 100 ms) and
  a 70% citizen cleared about 30% of 101000100's stages. The skill is its chance on seven jumps, stable per citizen within
  `JQ_STAGE_SUCCESS_MIN`..`MAX` (30-70%), scaled to a shorter stage, plus `JQ_PRACTICE_STEP` for every stage it clears
  without a fall, this session. A narrow jump fails more often than a wide one.
- A miss is a real drop: every drawn launch off the edge's window is probed first, pixel by pixel over the whole band
  the citizen may take off from (one pixel can be the one that misses a lip into a void, as 101000101's r60->r59 does
  at its window's end), and kept only if it lands on a foothold that can still reach the checkpoint and no farther down than a
  fall that hurts (else it draws again, then jumps exactly). Presses stay 4 px inside a platform's ends (or at
  navigation's pick, if that is nearer an end), so walking to one never steps off. A miss costs a climb, never HP. It
  lands lower, says "rip" or "ugh" now and then (`jq.fall`), and climbs again. Noisy misses do not count against the edge's reliability, and the
  imperfections' town rope fumble (F8) stays off during an attempt, so falls are not counted twice.
- After `JQ_FALLS_MAX` falls or `JQ_ATTEMPT_MAX_MS` it gives up (`jq.giveup`): on the road it walks off with its next
  plan; in the Forest it walks down to Shane (1032004, at every map's foot) and back to Ellinia. It never stands still
  two minutes in a jump quest; an exit NPC that fails three times logs it off. At the top it stands a few seconds,
  says `jq.clear` and leaves; no JQ plan for `JQ_COOLDOWN_MS` (6 h).
- The below-map recovery teleport must never happen in front of a player. A fall below the map during an attempt with
  a player on the map logs the citizen off (a disconnect, a normal sight); with nobody there the recovery runs unseen.
  The out-of-map distance recovery (which meets such a fall first, once it leaves the map's area) asks the same way.
  Both are counted in `!citizens jq` and `/runtime/citizens` (`jumpQuests`) and logged WARN. `JumpQuestRoutesTest`
  proves none happen on the shipped courses.
- Logs: `citizen <name> starts forest of patience (GATE)`, `citizen <name> cleared pet walking road with 3 falls in 412
  s`, `citizen <name> gives up on ... after 8 falls`; the journal says `cleared forest of patience after 3 falls`.

## Ops

`!citizens` (GM 5): `status`, `online`, `cohorts`, `who <name>` (card, targets, journal),
`birth [cohort] [backdateDays]`, `seed [regionId count]` (below), `retire <name>`, `purge confirm` (retire everyone, reseed),
`force <n>` (keep n loaded regardless of the hour - load tests), `sweep`, `reload`, `guilds`,
`market`, `conversation`, `level <name> <level>` (raise an offline citizen's stored level as the ledger does; never
lowers), `login <name>` (load that citizen now, replacing a ledger-only session), `pq` (citizen runs now and since boot,
`memory` (the memory cache and table counts), `memory <name>` (who that citizen remembers, warmth, what happened).
the last ten), `pq send <name> [lobby]` (a loaded citizen goes to the lobby its level fits, out for a party quest; a
lobby id such as `balrog` picks one of several that fit), `jq` (attempts on, per course tries/clears/give-ups/falls and
clean stages, below-map log-offs and unseen teleports, the last ten attempts), `jq send <name> [pwr|fop_a]` (a
loaded citizen goes for that course now, or one that fits; Sabitrama first when its quest can start).

**Testing jump quests live** (`JQ_ENABLED: true`).
1. Type `!warp 100000202` and `!citizens jq send <name> pwr` for a loaded Victoria citizen. It comes to the road and
   climbs it stage by stage, falls now and then, and either clears it ("finally!!") or gives up after 8 falls.
2. Type `!citizens jq`: the road's tries, clears, falls and clean stages; below map `0 logged off, 0 teleported`.
3. For the Forest: `!citizens level <name> 30` (offline), `!citizens login <name>`, `!warp 105040300`, then
   `!citizens jq send <name> fop_a`. It walks to Sabitrama, takes quest 2050, goes to Ellinia, pays Shane, climbs both
   maps, clicks the pile (a prize and 4031020) and comes back to Sabitrama to turn it in.

**Testing party megaphones and whispers live** (`LF_SMEGA_ENABLED` and `CITIZEN_WHISPERS_ENABLED` true).
1. Stand at Lakelis (103000000) with a Lv21-30 character, party-less, and wait for a citizen party to form short.
2. About 4 minutes after it formed its leader sends "LF1M KPQ 21-30 ch1, whisper <name>"; `!citizens who <leader>`
   shows one Super Megaphone fewer.
3. Whisper the leader "inv": the invite comes; from another map it also whispers where to come.
4. Whisper any citizen "hi": a short reply (or none: its ignore rate), and at most one per 2 minutes.

`!needs` (GM 5): the board's counts; `!needs <citizen>` every request of one citizen with its state, hold and deadline;
`!needs map` the open requests of the citizens on your map. `@requests` (everyone): the requests you hold and the nearest
open ones, numbered; `claim <n>`, `drop <n>`.

`!rivals` (GM 5): every crew, one line each. `!rivals <player>` shows that player's crew: members with level, leader,
online or leaving, and its bests beside the player's. `!rivals clears` shows the last 20 clears booked since the boot.
`@records [quest]` (everyone) is the records page in chat while `RIVALS_ENABLED` is on.

**Testing the rival crew live** (switch on; a Lv20+ test character):
1. Log in and wait a minute. `!rivals <you>` shows a crew of 4-5 within five levels, and their guild tag is over their
   heads.
2. `!citizens level <member> <your level + 10>` (offline), or level yourself up past the band. At the next refresh
   (`RIVALS_REFRESH_MINUTES`; lower it to test) it leaves, with lines if it is loaded, and another citizen joins.
3. Clear Easy Balrog. `@records balrog` lists it as first, fastest and yours.
4. `!rivals clears` shows the crew's citizen runs. A faster one while you are online brings a megaphone.
5. Log out. The crew clears something. Log in: a note from the crew's leader arrives.

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
| `needs.yaml` | where the needs board's Bulletin Boards stand (map, x, y above the ground); `NeedBoardsPlacementTest` checks each against the map's WZ footholds and stock NPCs |
| `rivals.yaml` | the quests whose clears are records: event script, label, `pq` or `boss`, aliases for `@records`; `RivalsContentTest` checks every `BOSS_EVENT_SCRIPTS` entry and the citizens' own PQs are listed and every script exists |
| `chatter/rivals.yaml` | the rival crew's lines: `rival.banter` (map), `rival.beat` (smega only, a slot of the hourly cap), `rival.beat.whisper`, `rival.congrats`, `rival.help`, `rival.leave.behind/ahead(.whisper)` (whispers), `rival.join` and `rival.leave.*` (guild); slots `{name} {crew} {quest} {time} {theirs}` |
| `chatter/needs.yaml` | the needs board's lines: `dwell` and `smega` intents named `need.<kind>.*` (only that kind's request fills them), and the triggers `need.whisper`, `need.thanks`, `need.got`, `need.scroll.pass`, `need.scroll.fail`; slots `{item} {what} {count} {price} {total} {for}` |
| `chatter/*.yaml` | intents: `trigger` (enter, dwell, levelup.other/self, death.other/self, drop.rare(.self), boss, jobadvance.other, login, logout, login.buddy, smega, party.forming, party.invite, price, afk, back, lag, and the party-quest ones in `chatter/pq.yaml`: pq.lf, pq.join, pq.go, pq.clear, pq.fail, pq.down, pq.giveup, pq.call, pq.call.answer, pq.thanks, pq.wait; the minigame ones in `chatter/minigames.yaml`: game.lf, game.title.omok, game.title.cards, game.hi, game.nice, game.win, game.loss, game.tie, game.bye; the megaphone and whisper ones in `chatter/whispers.yaml`: pq.smega (slots `{need} {pq} {levels} {ch} {me}`), pq.where, whisper.join.*, whisper.ack.*, whisper.need.yes, whisper.hi, whisper.busy, whisper.training, whisper.pq, whisper.shop; the jump-quest ones in `chatter/jq.yaml`: jq.start, jq.fall, jq.clear, jq.giveup), `where` (town, field, fm, lobby, any, or `mapIds`), `channel` (map, party, guild, buddy, smega, room = a minigame room's chat), `weight`, `cooldownMs`, `lines`, optional per-style `styles`; `{need}` is how many more a PQ party wants. Party-quest lines repeat as players repeat them: no recent-intent memory, only each intent's own `cooldownMs` |

Lines are ASCII; `{slots}` that cannot be filled drop the line. The lint refuses unknown
triggers, channels and jobs, loadouts that do not exist, and non-ASCII.

## Sizing and measuring

The first cut ran 120 citizens and 30 loaded. A presence sweep with 6 logins took ~300 ms; a
birth (account, character, shaping) ~250 ms. Raise `TARGET_CITIZENS` and `ONLINE_MAX` in steps
while watching `sweepMs` in `!citizens status`, GC in `logs/gc/`, and the client on a crowded
town; `MAP_VISIBLE_MAX` and the field capacities are the per-map brakes. `MAP_VISIBLE_MAX` (60) is
enforced per map instance since 2026-10-25 (`MapVisibleCap`, kill switch `MAP_VISIBLE_CAP_ENABLED`): a login to a full map
logs in on another map of the same kind, else virtual; an everyday move to a full map goes elsewhere of the same kind; a
party-quest or job-errand move waits and retries while one everyday citizen there moves on; a map over the cap walks its
newest everyday citizens out through a portal while someone watches. Nobody vanishes in place for the cap. Residents and
companions do not count. docs/SCALE_PLATFORM_PLAN.md section 1. `!citizens force <n>`
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
invite you); expeditions are Easy and Normal Balrog only (docs/CITIZEN_EXPEDITIONS_PLAN.md); travel on foot ends on
the first map nobody watches (no citizen walks a whole multi-map trip unseen) and rides no real ship; citizens remember
players (each other only through circles, guilds and gossip), and the weekly life summary of
plan 6.4 is never written. The needs board asks for no quest items (decision D4b) and sorts by warmth until
Standing (C1) lands. Whispers get templated answers only (no model call); the merchant and recruiter megaphones
still use up no item. Jump quests are Pet-Walking Road and the Forest of Patience; Shumi's (103000900-909, F5.6)
and bot-hosted events come later, and Breath of Lava stays out.

The rival crew's help request is only an invitation (F7.4b is not built). The run itself would need these:
- citizens running Zakum through the expedition engine (`AgentBossCitizenRuns` and `CITIZEN_BOSSES` know only Balrog);
- the crew leader's trials quest 100201 for the `Zakum05` portal and an Eye of Fire for Adobis's list, both off-screen;
- a `CitizenZakumDoor`, with its lobby in `rivals.yaml`;
- the guest signing up at Adobis, with `humanOptional` false, since Zakum needs a player's client;
- the leader dropping the Eye on the altar once the guest is inside.
Zakum is the only boss crews ask about.

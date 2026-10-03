# Test guide: living world, population, TypeSafe judgments

Everything the `integration/cosmic-agents` branch adds on top of P0nk/Cosmic + Kiwiism/cosmic_agents,
with a pass/fail check for each. Automated checks first, then in-game procedures. Written
2026-09-21; the "verified" column says what has already been exercised on this machine so you know
where to concentrate.

Legend: **A** automated (runs without a client) - **G** needs a GM character in game -
**P** needs a second player or a second account - **D** destructive/outward-facing (changes
client files or player data; do on the test server only).

## 0. Environment

| Piece | How |
|---|---|
| Build | `mvnw.cmd -q -o package -DskipTests` in `FriendServer/MapleCommons v83 Server` |
| Secrets | `FriendServer/.env`: `DB_USER`, `DB_PASS`, `ADMIN_CONTROL_TOKEN`, `TYPESAFE_API_KEY` (never paste values into docs, chat or commits) |
| Start | `start_all.bat` / `launch.bat`, or manually: load `.env`, then `java -Dagents.scheduler.mode=central-sequential -Dwz-path=wz -jar target/Cosmic.jar` |
| Patch server | `patch-server\serve.bat` (port 8485) or `python patch-server/serve.py --root "<Game Files>" --port 8485`; the launcher shows a Yes/No dialog when it is down |
| Client | `MapleCommons Client\Game Files\Launch Game.exe`; kaentake DLL; GM account with gm level 5+ |
| Admin port | `http://127.0.0.1:8480`, header `Authorization: Bearer <ADMIN_CONTROL_TOKEN>`; e.g. `curl -s -H "Authorization: Bearer $T" http://127.0.0.1:8480/runtime/living` |
| MapleEdit | `MapleEdit\start.bat`, `http://localhost:5050`, Cosmic SSO login; `cosmic_root` set on the Deploy page; `ADMIN_CONTROL_TOKEN` in its environment for the status panels |
| Logs | `logs/cosmic-log.log` (grep `resident.`, `citizen`, `bench session`, `[typesafe-`, `[phrase]`, `[chatter]` at debug), `logs/typesafe-usage.csv`, `logs/gc/gc.log` |
| DB | MySQL 8, schema `cosmic`; Liquibase applies `29-living-world` and `30-population` on boot |
| Config | `config.yaml` sections `living_world:` and `population:`; both ASCII only |

Useful GM commands: `!world`, `!citizens`, `!typesafe`, `@quests`, `@roster`, `@bond <name>`.
Player-side chat commands are plain map chat (`hi Olwen`, `Olwen quest`, `accept`, ...).

## 1. Automated checks (A)

| ID | Check | Command | Expected | Verified |
|---|---|---|---|---|
| A-01 | Compile | `mvnw.cmd -q -o compile` | exit 0 | yes |
| A-02 | Living/population unit tests | `mvnw.cmd -q -o test -Dtest="server.living.**.*Test" -Dsurefire.failIfNoSpecifiedTests=false` | ContentLoaderTest 5, PhraseMatcherTest 5, RecallTravelTest 2, PopulationContentTest 5, all green | yes |
| A-03 | Jev tests | `-Dtest=AgentChatIntentJudgeTest,AgentChatFallThroughCommandsTest,AgentChatIntentCatalogCompatibilityTest,AgentAuthorityServiceTest` | all green | yes |
| A-04 | Upstream boundary tests (baseline) | `-Dtest=AgentConfigurationBoundaryTest,AgentArchitectureBoundaryTest,AgentSystemOwnershipManifestTest` | exactly the upstream baseline: ConfigurationBoundary 2 failures (Balrog/PQ numeric constants, yaml guidance), ArchitectureBoundary 3 failures + 1 error (missing llm/gateway dir); identical on pristine `integration-refs/cosmic_agents` | yes (identical set) |
| A-05 | Full suite minus heap hogs | `-Dtest="!BotManagerTest,!BotCombatManagerTest"` | same baseline failures as upstream master; nothing new under `server.living` | not this round |
| A-06 | Living content lint | `tools\living-world-lint.bat` | `OK (38 warnings)` - warnings are flags set but never read | yes |
| A-07 | Population content lint | `tools\population-lint.bat` | `OK (4 warnings)`: the Ereve forests 130010010/020/110/120, entered by script portals the knowledge export misses | yes |
| A-08 | Field catalog regen is stable | copy `scripts/config/population/fields.yaml` to `/tmp/fields.yaml` (the generator keeps the capacities it finds in the output file), `tools\population-fields.bat wz /tmp/fields.yaml`, then diff the two | identical unless `wz/` changed since the last regen | yes |
| A-09 | Quest-log export | `tools\living-world-questlog.bat` | 79 quests, ids 30000-30078 unchanged in `quest-log-ids.yaml`, item and mob names resolved (no `item 4000011`) | yes |
| A-10 | Quest.wz patch (no deploy) | `cd MapleEdit\app && python living_world_quests.py --dry-run` then without `--dry-run` | writes `out/Quest.wz`; `QuestInfo/Check/Act` gain 79 entries; server XML staged | yes |
| A-11 | Secret hygiene | see section 9 | no `.env`, `sso-key.json`, `*.wz`, key/token values in tracked files or history | see 9 |

## 2. Boot matrix (A)

Run each variant, wait for `Living world started` and `Population started`, then hit
`/runtime/living` and `/runtime/citizens`.

| ID | Variant | Expected | Verified |
|---|---|---|---|
| B-01 | Normal (`.env` complete) | `JevClient ... apiKey=<set>`; both features start; Liquibase `29`/`30` applied once (subsequent boots: no changeSet lines) | yes |
| B-02 | No TypeSafe key (`TYPESAFE_API_KEY` unset) | `apiKey=<missing>`; both features start; Residents still answer exact phrases; `!typesafe` shows unavailable; no ERROR lines | yes |
| B-03 | No Ollama at `SOCIAL_OLLAMA_ENDPOINT` | generated replies fall back to the deterministic catalog; no ERROR lines (circuit opens quietly) | yes (Ollama absent here) |
| B-04 | `population.ENABLED: false` | `Population disabled`; living world unaffected; `/runtime/citizens` returns `enabled:false` | not this round |
| B-05 | `living_world.ENABLED: false` | `Living world disabled`; population stays off (it runs inside the living world); `/runtime/living` `enabled:false`; vanilla gameplay intact | not this round |
| B-06 | Empty content dir path | `content failed to load; feature stays off`, server keeps booting | not this round |
| B-07 | Timezone | `ROUTINE_ZONE` respected: at 23:00 Eastern all Lith residents materialize (`!world residents`) even though the JVM runs in GMT | yes |

## 3. Living world - Residents and quests (G)

Start in Lith Harbor (104000000) with a level 5+ test character. `ALWAYS_AWAKE_TOWNS: lith-harbor`
keeps the cast materialized; otherwise walk in and wait one tick (`RESIDENT_TICK_MS`).

| ID | Steps | Expected | Verified |
|---|---|---|---|
| LW-01 | `!world residents` | 8 Lith personas listed with maps/positions; Tobin absent between 23:00 and 07:00 (his slot is `materialize: false`) | yes |
| LW-02 | Stand near Olwen, say `hi Olwen` | Olwen answers in a chat bubble; `@bond Olwen` shows stage MET | not yet |
| LW-03 | `Olwen quest` | NPC window with a townsperson portrait, quest steps and reward summary, Yes/No | not yet |
| LW-04 | Accept | `Quest started: The Snail Tally.`; `@quests` lists the quest and the current step (`defeat Snail 0/15`) | not yet |
| LW-05 | Kill 15 snails on 104010000 / 104000000 | pink progress lines every kill; step advances to `bring word to Olwen` | not yet |
| LW-06 | `Olwen done` | completion window, meso/exp/bond reward, `@quests` empty | not yet |
| LW-07 | `Olwen quest` again | The Lost Manifest offered (level 6, needs snail tally); COLLECT_ITEM counts shells already in inventory | not yet |
| LW-08 | `Olwen talk` / `menu` | NPC menu: work, quests in progress, travel, who are you | not yet |
| LW-09 | Whisper a resident (`/w Olwen hello`) | whisper reply, not map chat | not yet |
| LW-10 | Say something the regexes miss ("got anything interesting?") with the key present | `[typesafe-resident]` line in the log; either acts, hints (`looking for work? say 'quest'`) or shrugs; `!typesafe` counts one `resident-intent` request | not yet |
| LW-11 | Same without the key (B-02) | Resident falls back to `speakPlainly`/shrug; no request | not yet |
| LW-12 | Rumor: `!world rumor play lith-dock-confession` while standing on the dock | Hadrik, Tobin, Brannock walk to the spot and speak in turn; you receive the listener flag (`@bond Hadrik` shows it); `living_event_log` gets `rumor.performed` | not yet |
| LW-13 | Rumor director: make two rumors due at once (`!world flag`/`arc` to unlock, same `serverHours`) | only one performs per map per tick; a rumor a present player is waiting on (HEAR_RUMOR step) goes first | not yet |
| LW-14 | `!world reload` after editing a quest YAML | reload succeeds, lint errors reported in game if any | yes (via admin route) |
| LW-15 | Quest log mirror (needs Quest.wz deployed, section 7) | with `QUEST_LOG_MIRROR: true`, accepting a quest shows it in the client quest log under the persona's name; completing moves it to Completed; relog keeps it | not yet |

## 4. Living world - companions, roster, bench (G, some P)

Prerequisite: complete `brannock-crates` (LW-03..06 with Brannock, level 5) and reach ACQUAINTED
(25 bond points; `!world bond <you> brannock 30` shortcuts it).

| ID | Steps | Expected | Verified |
|---|---|---|---|
| CO-01 | `Brannock join me` | NPC window with contract terms (15,000 meso, 4 h, OWN_DROPS 10%); "I have it. Let's go." | not yet |
| CO-02 | Accept with the meso | Brannock joins your party; `@roster` lists him ACTIVE; companion commands (`follow me`, `grind`, `stop`) work; `!typesafe` shows no request for exact phrases | partly (unit: CompanionTestGuideTest) |
| CO-03 | Phrase pack: `can u farm around here pls`, `stop buffing` | `[phrase]` log line, no Jev request, correct canonical (`grind`, `stop buff`) | partly (unit tests) |
| CO-04 | Free-form command with the key (`go whack those pigs`) | `[typesafe-chat]` ACT/ASK/IGNORE; a LIVE ACT is redispatched once (no double action) | yes (earlier session) |
| CO-05 | `bench Brannock` | leaves the party, heads to camp; `@roster` BENCHED with assignment | partly (unit: CompanionTestGuideTest) |
| CO-06 | `Brannock train` then wait 10 min | `living_roster.accrued_exp` grows toward `ownerLevel - paceGap`; `call Brannock` applies it (level-up effects) | partly (unit: CompanionTestGuideTest) |
| CO-07 | Camp at a field: walk to pig beach, `Brannock camp here`, `bench Brannock` | within 2 min `bench session: Brannock train live at 104010001` in the log; you can walk in and watch him fight; leaving the map keeps him fighting (BACKGROUND_ACTIVE) | partly (unit: CompanionTestGuideTest) |
| CO-08 | `Brannock farm meso` while camped at a field | live farming session; `bench.harvest` events with meso/stacks; `Brannock what did you get` shows the haul; `Brannock hand it over` transfers | partly (unit: CompanionTestGuideTest) |
| CO-09 | `Brannock farm snail shell 20` when Olwen's manifest quest is active for you | refused as your own errand only if Brannock is the giver; Olwen's item is allowed | partly (unit: CompanionTestGuideTest) |
| CO-10 | Delegation: with Brannock active, `Brannock go farm meso here` on a field; walk to another map; `Brannock come back` | he despawns, `@roster` shows AWAY with the map; `freeSlots` in `@roster` is one lower. On "come back" he joins the party at once (stock "has joined" notice; the party window shows the field he farms), whispers "on my way. ~N s." (N at most 60 on Victoria Island) and `@roster` shows him travelling; when the ETA ends he walks in from the portal nearest you and only then follows | partly (unit: TravelArrivalTest) |
| CO-11 | Recall travel (join-then-travel): bench him in Henesys, call from Lith; change map twice before he arrives; call him again and bench him on the way; call him again and enter a PQ before the ETA | he joins the party at once and the party window shows Henesys; "on my way. ~60 s." (`RECALL_MAX_ETA_MS` caps the same continent at 60 s, off-island stays 180 s); no follow warp while he travels, and at the ETA he walks in at the portal nearest you on the map you are on now; benched on the way he leaves the party and turns back; with you inside the PQ he waits outside at its entrance (HOLD, out of the party, seat kept, refuses invites) and rejoins when you come out; `RECALL_WHISTLE_ITEM_ID` set to an item you hold makes the call instant (a walk-in from the portal) | partly (unit: TravelArrivalTest, HoldRejoinTest, RecallCapTest) |
| CO-12 | Wages: `Brannock wages`, `Brannock pay wages 5000`; repeat with `living_world.ROSTER_CONTRACT_MODE` set to each of AUTHORED, PERMANENT_ON_ROSTER (the default) and PERMANENT_AT_SWORN | status line; paid-until advances; ledger stops charging upkeep; the same in every mode (decision 3: wages keep working, even where contracts are permanent) | not yet |
| CO-13 | Contract end rules (decision 3): give a contract `LEADER_DEATH_3X` (no shipped contract lists it; add it to Hadrik's `leavesOn`) and die three times with him out; also run out its `durationHours` of travel, outlevel him by more than `maxLevelGap`, and bench Dorran (`benchToleranceDays: 0`) for over an hour. Do each under the default `ROSTER_CONTRACT_MODE: PERMANENT_ON_ROSTER`, then under `AUTHORED`, then under `PERMANENT_AT_SWORN` with the bond below and at SWORN. Then repeat one of them inside a PQ (committed) | PERMANENT_ON_ROSTER, and PERMANENT_AT_SWORN at SWORN: nobody leaves, nothing is said, `@roster` still lists them. AUTHORED, and PERMANENT_AT_SWORN below SWORN: he leaves with his line (three deaths: "three times. i'm not dying for this.") and `@roster` no longer lists him. Committed to a run, he says "after this run, i'm heading home." once, finishes the run, and leaves when the run ends (or when he leaves it). Where a bench can end Dorran's contract, "Take a break at camp" (or a swap, or "Make room") asks Yes/No in your notes first and a chat bench has him say "an hour at camp with nothing to do and i walk."; under PERMANENT_ON_ROSTER, or at SWORN, nothing asks | partly (unit: ContractPolicyTest, DeferredWalkOutTest, BenchWarningTest) |
| CO-14 | Party cap (P): two humans + companions | `call` refused when 6 slots are used; away companions of both humans count | not yet |
| CO-15 | Owner logout/login and channel change (decision 8a): (a) with two companions out in your own party, log out and back in within 20 min; (b) log out for 20+ min, or restart the server, then log in; (c) lead a party with a friend, then log out; (d) change channel with companions out; (e) stay in the Cash Shop | (a) one says "gn!" in party chat; they stay in the party window as offline members and `@roster` shows them parked; at login they come back one by one, 2-8 s apart, each walking in from the portal nearest you; (b) 20 min after the logout they go to their duty (the party window drops them, `@roster` shows them benched); at login the usual lineup joins at once and whispers ETAs ("on my way. ~N s."), and one whisper sums up what the bench earned; a restart parks everyone who was out; (c) they say bye and leave the party at once; the friend leads and has the seats back; (d) no goodbye and nobody parks; they show offline for a moment, then appear on the new channel 1-3 s apart and walk in from the portal, all within 30 s; a live bench slice stays on its channel; (e) they idle where they stand; after 20 min in the Cash Shop they log off as at a logout | partly (unit: LogoutOwnPartyTest, LogoutSharedPartyTest, ParkGraceTest, LoginRestoreTest, ChannelFollowTest, BootReconcileTest, InstanceLogoutHandoffTest) |
| CO-16 | `release Brannock`; then `release <name>` for a companion inside a PQ; then delete an owner character that has a roster (char select, Delete) | goes home, resident again next tick, contract cooldown applies to rejoin. Inside the PQ it says "after this run, i'm heading home." and goes home when the run ends. Deleting the owner releases every row of theirs on companion-ops: loaded companions log off, live bench slices end, seats and leases go back, each Resident is back in its routine (`resident.released` and `roster.release` in `living_event_log`) and `living_roster` has no row for that character | partly (unit: DeferredWalkOutTest, DeletionCleanupTest) |

## 5. Population - citizens (G)

| ID | Steps | Expected | Verified |
|---|---|---|---|
| POP-01 | Fresh DB or `!citizens purge confirm`, then watch `!citizens status` | 12 births per minute until `TARGET_CITIZENS`; `citizen born` lines; then `seeding` disappears | yes |
| POP-02 | Level distribution: `SELECT (c.level DIV 10)*10 b, COUNT(*) FROM living_citizen lc JOIN characters c ON c.id=lc.character_id WHERE lc.state='ACTIVE' GROUP BY b` | most in 0-40, a tail to 80; every job branch in `c.job` | yes (120: avg 34, max 82) |
| POP-03 | Names: `SELECT name FROM living_citizen ORDER BY RAND() LIMIT 20` | era-plausible, 4-12 chars, no collisions with players, none rejected by the name screen | yes |
| POP-04 | `!citizens cohorts` at different hours | online counts follow hour weights (evenings busy, 4am near zero; nightowls inverted) | partly (morning: near zero as expected) |
| POP-05 | `!citizens force 15` then `!citizens online` | 15 loaded within one sweep (<1 s); activities spread over TRAIN/TOWN/PQ/MARKET/AFK and several regions | yes |
| POP-06 | Visit a citizen's field (`!warp <map>` or walk) | it is fighting for real, moves, loots; leaving keeps it fighting | not yet |
| POP-07 | Level up near citizens (`!level` up once on a field with citizens) | up to two say `gratz`/`gz`/`ding` after a typing delay; not every citizen, not instantly | not yet |
| POP-08 | Die near citizens | `f`/`rip`; own deaths make the citizen say `ugh`/`lost 5% again` | not yet |
| POP-09 | Idle in Henesys for 10 minutes with a few citizens present | a handful of lines (`how do i get to ellinia`, `S> ...`, `fame me back?`), never more than `CHATTER_TOWN_LINES_PER_MINUTE`, no template repeats from the same citizen within a session | yes (via `/runtime/citizens` recent chatter) |
| POP-10 | Guilds: `!citizens guilds` | `GUILD_TARGET` guilds with names, mottos, level bands; members shown; the guild window shows an emblem when you join one | partly (created; emblem unverified) |
| POP-11 | Circles: `SELECT COUNT(*) FROM living_circle`; watch two circle-mates online on one map | ~one circle per 4 citizens; they form a party (`party.forming` chatter) and go train together | partly (circles created) |
| POP-12 | PQ lobby: level 21-30 character in Kerning City with a `PQ`-session citizen party present | party invite arrives with a line like `<name> wanna join us`; declining is fine; citizens are admitted to background KPQ by the engine (`Autonomous KPQ` log lines) | not yet |
| POP-13 | Acquaintance: stay on a map with one citizen for `BUDDY_REQUEST_AFTER_MINUTES` x3 (or party with it for the base time) | one buddy request from that citizen, never a second; `living_acquaintance` row grows each minute | not yet |
| POP-14 | Guild invite: stand near a `recruiter` citizen of a guild whose level band fits, past the acquaintance threshold | exactly one guild invite + a whisper; accept: you are in a bot guild, guild chat shows citizens greeting on login | not yet |
| POP-15 | Fame: stand near a `famer` citizen at level 15+ | `X has raised your fame` once a day + whisper `famed u, fame back?` | not yet |
| POP-16 | Conversation tier 1: `hi` next to one citizen; `where is ellinia` | greeting back or a directions line; `!citizens conversation` templated count +1 | not yet |
| POP-17 | Conversation tier 3 with Ollama running: `<name> how's your day going` | reply mentions something real from its journal/card (map, level, guild); voice pack applied; `generated` +1; without Ollama the deterministic catalog answers | not yet |
| POP-18 | Budget: exceed `CONVERSATION_PER_PLAYER_PER_DAY` | citizens answer `gtg, ttyl`/`brb` templates instead of generating | not yet |
| POP-19 | Silence: address a citizen with a high `ignoreRate` voice several times without its name | some lines ignored; naming it always gets a response path | not yet |
| POP-20 | Market: FM room with a merchant citizen (`!citizens online` shows `stall`) | a real player shop with looted items at index-ish prices; you can buy; closing/leaving releases the spot | partly (stall opened; purchase unverified) |
| POP-21 | Market buy: open your own shop in an FM room with an item priced under 90% of `SELECT price FROM living_price_index WHERE item_id=...`, wait for a browsing citizen | `citizen X bought ... from You` log line, meso in your shop; `living_price_index` drifts toward your price | not yet |
| POP-22 | Churn: `!citizens retire <name>` while it is online | goodbye line, logout, `RETIRED`; a birth follows within `86400000/BIRTHS_PER_DAY` ms | partly (purge path) |
| POP-23 | Ledger: pick an offline citizen, note `characters.level`, come back a day later | level rose toward `!citizens who <name>` target without a login; first login repairs growth/job/gear (no AP/HP anomalies: check `!citizens who`, then `!world`-style inspection or DB `str/dex/int/luk`, `maxhp`) | not yet |
| POP-24 | Reload: edit `chatter/towns.yaml`, `!citizens reload` | new lines appear; lint errors block a bad file in MapleEdit but `!citizens reload` reports them | yes (route) |
| POP-25 | Presence off-hours sanity | with `force 0` at 4am local, `!citizens online` shows 0-3 | yes |

## 6. MapleEdit (browser)

| ID | Steps | Expected | Verified |
|---|---|---|---|
| ME-01 | Dashboard card "Living World" -> `/living` | tree with personas/quests/contracts/arcs/rumors per town + files + population section; counts match `!world census` / `!citizens status` | yes (harness) |
| ME-02 | Open `quests/lith-harbor.yaml`, add a broken quest (`steps: []`), Save | refused: `ERROR ... a quest needs at least one step`; file unchanged | yes |
| ME-03 | Add a harmless comment, Save | `Saved ...`; backup under MapleEdit `backups/`; "Reload on server" applies | yes |
| ME-04 | Open `population/chatter/towns.yaml`, add a line with a typo in `trigger`, Save | refused by the population lint (`unknown trigger`) | not yet |
| ME-05 | "Citizens right now" | active/target, loaded/virtual, guilds, chatter lines, sweep ms, cohorts table, online table, last 12 chatter lines | yes |
| ME-06 | Sweep now / Force online... / Reload population content | counts change; force keeps N loaded until cleared | yes (route) |
| ME-07 | Export quest log | `quest-log-ids.yaml` and `out/Quest.wz` refreshed; message tells you to deploy | yes (CLI path) |
| ME-08 | Residents table | matches `!world residents` | yes |

## 7. Quest.wz deploy (D)

Ships to every friend's client through the patch server; do it on the test server first.

1. `tools\living-world-questlog.bat`, `python MapleEdit/app/living_world_quests.py` (or the page button).
2. MapleEdit Deploy: ship `out/Quest.wz` to Game Files, regenerate the manifest (`patch-server\regen.bat`).
3. Relaunch the client; the launcher pulls Quest.wz.
4. Set `living_world.QUEST_LOG_MIRROR: true`, `!world reload` (or restart).
5. LW-15. Also confirm the client's quest window does not list living quests as *available* (`lvmin 200`)
   and that stock quests still render (spot check one).
6. Rollback: deploy the pristine `data/wz_client/Quest.wz`, flag back to false.

## 8. Scale and soak (G/A)

Goal: find this machine's ceiling before raising `TARGET_CITIZENS`/`ONLINE_MAX`.

1. `!citizens force N` for N = 15, 30, 60, 100 (raise `ONLINE_MAX` first); wait 10 min each.
2. Record per step: `!citizens status` sweep ms, `logs/gc/gc.log` pause times, CPU, `Map high-water`
   warnings, client FPS on a crowded town (stand in Henesys with the citizens forced there via
   `PRESENCE_MULTIPLIER` or by picking `afk-townie`-heavy cohorts), any `WARN` in the log that is
   not the known supply-recovery/high-water noise.
3. Stop when sweep > 2 s, GC pauses > 200 ms, or the client stutters; set `ONLINE_MAX` to two thirds of that.
4. Note the numbers in `docs/POPULATED_SERVER_PLAN.md` section 9 (replace the hypotheses).

| N loaded | sweep ms | GC max pause | CPU | client ok | notes |
|---|---|---|---|---|---|
| 15 | 564 (measured) | none visible | - | not checked | |
| 30 | | | | | |
| 60 | | | | | |
| 100 | | | | | |

## 9. Push preparation (A)

Before pushing the branch anywhere:

1. `git status` clean; `git log --oneline fec53bc77..HEAD` lists the branch's commits (39 at the time of writing).
2. Tracked-file scan: `git ls-files | grep -iE "\.env$|sso-key|\.wz$|jevapi|Game Files"` returns nothing (verified).
3. Value scan (do not print the values): read each secret from `.env` into a shell variable and
   `git log -p --all | grep -c -F "$VALUE"` must be 0 for the API key, the admin token and the DB
   password; also grep the work tree excluding `target/`, `logs/`, `.git/`. The history scan takes
   several minutes on this repo.
4. `.gitignore` covers `.env`, `/logs/**`, `/target`, `/wz/`; confirm `.runtime/` and `logs/soak`
   are not tracked (`git ls-files .runtime logs | head`).
5. Config files committed with secrets blank: `config.yaml` `DB_PASS: ""`, no `TYPESAFE_API_KEY`
   anywhere but `.env`/`.env.example`.
6. Upstream contribution: `integration-refs/cosmic_agents` branch `feat/typesafe-jev` (10 commits)
   is separate and pushes to the user's fork of Kiwiism/cosmic_agents, not to this repo.
7. Remotes: `origin` is P0nk/Cosmic and `cosmic_agents` is Kiwiism - add your own fork as a remote
   and push `integration/cosmic-agents` there; never push to `origin`.

## 10. Regression checklist for vanilla gameplay (G)

With both features on, the base game must behave as before:

| ID | Check | Expected |
|---|---|---|
| R-01 | Log in, walk, warp, change channel | unchanged |
| R-02 | Level up on a map with no citizens | normal effects; no chatter, no errors |
| R-03 | Party with a human, party chat | unchanged; citizen party chat only in citizen parties |
| R-04 | Guild create/invite between humans | unchanged; bot guilds appear in the guild ranking NPC list |
| R-05 | Buddy add between humans | unchanged |
| R-06 | Free Market: open your shop, buy from an NPC-free room | unchanged; citizen stalls are ordinary player shops |
| R-07 | Megaphones | player smegas unchanged; citizen smegas <= `CHATTER_SMEGA_PER_HOUR` |
| R-08 | Ranking board | citizens appear with real levels; humans unaffected |
| R-09 | GM commands from cosmic_agents (`@spawnbot`, `!typesafe`) | unchanged |
| R-10 | Disable both features (B-04/B-05) | nothing from this branch is observable |

## 11. Results log

Copy this block per run.

```
Date / build (git rev):
Environment (key present? Ollama? MapleEdit? client build?):
Section 1 automated: A-01..A-11 ->
Section 2 boot: B-01..B-07 ->
Section 3 residents: LW-xx passed / failed (ids) ->
Section 4 companions: CO-xx ->
Section 5 population: POP-xx ->
Section 6 MapleEdit: ME-xx ->
Section 8 soak table filled? ->
Blocking issues:
Notes / follow-ups:
```

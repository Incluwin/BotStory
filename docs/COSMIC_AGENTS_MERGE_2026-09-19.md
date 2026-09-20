# cosmic_agents merge — 2026-09-19

Branch: `integration/cosmic-agents` (checkpoint of the pre-merge working tree is commit
`1b1b3e439`; `integration/solomapling-social` was left untouched).

Merged [Kiwiism/cosmic_agents](https://github.com/Kiwiism/cosmic_agents) `master`
(`3c7e09435`, 2026-09-08) into the FriendServer Cosmic fork. cosmic_agents is the
nutnnut-derived **combat-companion Agent platform** (`server.agents.*`, ~2,400 files).
This server already carried **SoloMapling** (ambient social bots) and the FriendServer
`server.bot.*` FM economy, so both bot paradigms now live side by side.

## How the merge was done

cosmic_agents re-imported its git history, so it shares no usable ancestor with P0nk.
Its tree is based on exactly P0nk `fec53bc77` (same as ours), so a synthetic commit was
grafted: tree = cosmic_agents `master` **with upstream `wz/` restored** (cosmic_agents
removed `wz/` from its repo), parent = `fec53bc77`. Branch `cosmic-agents-graft` points
at it. A normal 3-way merge then produced 113 content conflicts.

* 62 files: our only change was the `printStackTrace()` → slf4j sweep, and cosmic_agents
  rewrote the same spots to use `monitoring.RuntimeFailureLogger` → **theirs**.
* 51 files resolved by hand, keeping both sides' intent (details below).

## Decisions worth knowing about

| Area | Resolution |
|---|---|
| `Monster` HP is `long` (ours) | Kept. Added `getHpAsInt()` / `getMaxHpAsInt()` (saturating) and pointed ~12 agent/telemetry call sites at them. Boss HP bar keeps our >2.1B scaling **after** their EasyBalrog aggregate check. Mockito stubs in ~15 tests changed to long literals. |
| Equip requirements | cosmic_agents extracted checks into `server.EquipRequirementChecker` (strict: job + all four stats). **Rewritten to the FriendServer policy** (no job gate, primary stat only, Pirate/Beginner ungated, reqLevel + reqPOP kept). Agents plan gear through the same class, so they obey the same rule. Our `failsPrimaryStatReq` in `ItemInformationProvider` was removed as redundant; the Quest Ring / Monster Book medal / Evolving Ring bypass stays. `EquipRequirementCheckerTest` updated accordingly. |
| Spawn density | Ours: 4 mobs per non-boss/mobile point (`SpawnPoint.MAX_PER_NONBOSS_POINT`). Theirs: per-world `mob_rate` / `max_mob_per_spawnpoint` + per-map overrides. Kept their parameterised `shouldSpawn(int)` with our boss/immobile → 1 guard; `MapleMap.getNumShouldSpawn` uses their config-driven formula; **config.yaml sets `mob_rate: 4.0`, `max_mob_per_spawnpoint: 4`** so density is unchanged. Our fast-refill kill listener (`aniTime` refill, mobTime/5 on clear) is intact. `MapFactory` default `createMobInterval` stays 2000. |
| Quest EXP | Theirs is config-driven (`exp_rate × quest_rate`); with our 10×/5 that is 50×. **Our `ExpAction` (3× below lvl 10, 6× otherwise) was restored verbatim.** |
| Autosave | Theirs: coalescing `CharacterAutosaverTask` (window + 250 ms dispatch, SAVE scheduler lane). Kept, with the window defaulting to our `AUTOSAVE_INTERVAL_MINUTES` (10) unless `-Dcosmic.persistence.autosaveWindowMs` overrides. |
| Scrolling | Theirs moved `ScrollHandler` logic into `ScrollTransactionService`. Our crash-safety `saveCharToDB()` after a consumed scroll was ported into that service. |
| Item expiry | Theirs gates pets/items behind `PETS_NEVER_EXPIRE` / `ITEMS_NEVER_EXPIRE`. Config sets `PETS_NEVER_EXPIRE: true` (our previous behaviour); upstream's `isPermanentItem` → never-expire rule was re-added alongside `ITEMS_NEVER_EXPIRE`. |
| `Client.login()` | Theirs' restructured body kept; our `bot_flag` account rejection and `tools.PasswordHasher` entry point re-injected. Our `AdminEventEmitter` login/logout hooks and the service-bot `ioChannel == null` guard kept. Our unknown-opcode/`validateState` debug logs kept after their inbound capture hook. |
| `Server` startup/shutdown | Our subsystems (GM test queue, AdminControlPort/Sampler, ConnectionThrottle/AntiCheat, AdminServiceBot, bot-economy tasks) start alongside their `AgentPopulationRuntime`, Director bridge, map-graph viewer. Our 30 s shutdown warning broadcast runs before their bounded shutdown sequence (world shutdown is theirs — ours would have double-shut). |
| Commands | Theirs re-levelled several upstream commands (e.g. `zakum` 4→3, `pnpc` 4→5) — their levels kept. Our unique commands kept at gm4: `bot opq reactor move betafmshop test fmbot tradebot env boteconomy checksets resetboss`, plus `reloaditems/reloadmobs/reloadskills` (gm3). Their renames apply: `whatdropsfrom`→`mobdrops` (player level), `healperson`→`healplayer`, `expeds`→`expeditions`, `pap`→`papulatus` (`CommandsExecutorRegistrationTest` pins the retired names). |
| DB / Liquibase | Our changesets 25–28 (`ganre`) precede theirs (`Codex`; ids `25`, `26-remove-bot-owners`, `27-…`, `28a–g`, `29`, `30`). Different authors → no identity clash. Their `28a–28g` drop `command_overrides`, `setting_*`, `server_cms_*`, `scheduled_operations`, `configuration_profiles` — verified none exist in our schema or MapleEdit. Our data changesets 162/163/200 kept. |
| DB credentials | Their code reads `DB_PASSWORD`; the FriendServer stack exports `DB_PASS`. `DatabaseConnection.configuredPassword()` accepts either. `docker-compose.yml` keeps our `DB_USER`/`DB_PASS` form. |
| `config.yaml` | **Our values win.** 61 new cosmic_agents keys appended (their defaults) under a `cosmic_agents (Kiwiism) settings` banner, except: `mob_rate`, `max_mob_per_spawnpoint`, `PETS_NEVER_EXPIRE` (above) and **`TAILSCALEHOST: 100.65.63.117`** — their `getInetSocket` routes 100.64/10 clients to this address, and their default was Kiwiism's own tailnet IP. `DEPLOYMENT_PROFILE: local` (the `production` profile refuses a root DB user, disabled PIN/PIC and `AUTOMATIC_REGISTER`). Every key was verified to map to a field in `ServerConfig`/`WorldConfig`/`ObserverConfig`/`BotEconomyConfig` (YamlBeans rejects unknown keys). |
| `config.yaml` encoding | `CHARSET: US-ASCII` means the server decodes `config.yaml` as ASCII — a UTF-8 em dash in a comment broke every config-dependent test with an opaque `Error setting property` message. Keep the file 7-bit. `YamlConfig.loadConfig` now chains the underlying cause so this is visible next time. |
| `launch.bat` | Our `.env` loading + incremental `mvnw package` kept; their JVM guardrails (heap dump / GC log / `-Dagents.scheduler.mode=central-sequential`) added to the `java` line. |
| `Dockerfile` | Their `COPY wz_victoria ./wz` reverted to `COPY wz ./wz`. |
| NPC 22000 (Shanks) | Their Agent-admin menu + our Lith Harbor → Southperry return trip both present. |
| `EquipType` | Theirs, plus our `EARRING(103)` and `SHOULDER(115)`. |
| `LifeFactory` | Their `final` removed from the WZ provider fields — our `!reloadmobs` hot-reload reassigns them. |

## Things cosmic_agents brings that are off by default

* Agent Director panel bridge (127.0.0.1:8790), Database Console bridge (8787), map-graph
  viewer — all env-gated (`COSMIC_*_ENABLED=true`).
* Agent population runtime starts but spawns nothing until `!spawnbot` / `!agentpop`
  (GM level 6). Companion commands are plain map chat once a bot is spawned
  (`README_SERVER_BOTS.md`).
* Optional PostgreSQL "economy evidence" / social databases (`economy-database/`,
  `social-database/`) — not wired into our stack.
* Optional Ollama chat for bots.

## Verification

* `mvnw compile` — clean (3,711 sources).
* `mvnw test` — **4,533 tests, 4,525 pass.** The 8 that fail also fail on pristine
  cosmic_agents `3c7e09435` (verified by running the same classes in a clean clone):
  `CharacterPersistenceSectionMutationTest`, `AgentConfigurationBoundaryTest` (2),
  `AgentArchitectureBoundaryTest` (4 — source-lint import ceilings + a missing
  `dialogue/llm/gateway` dir), `AgentNpcInteractionAnchorCatalogTest` (needs a generated
  `tmp/game-catalog/generated_map_catalog.json`). Upstream's problem, not the merge's.

## Not yet done

* Live boot against the FriendServer MySQL (Liquibase will apply changesets 25/Codex …
  30 on first start) and a client login smoke test.
* Exercising SoloMapling social bots and cosmic_agents companions on the same map at
  the same time.
* Unused slf4j imports/fields remain in a handful of files where the logging sweep was
  superseded (harmless).

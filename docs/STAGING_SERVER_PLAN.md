# Staging server: a second, isolated test stack on the same PC

Status: proposal, 2026-10-06. Nothing in this plan runs until the user approves it.

Goal: a Claude session can run its own branch as a real server, log in with a real client, and test,
while the live server (feat/companion-manager, ports 8484/7575-7577/8480, schema `cosmic`) keeps running
untouched.

Names used in this document:
- **Live stack**: the server started by `tools/cua/start-maple-stack.ps1` from the main tree, the patch
  server on 8485 and the client in `MapleCommons Client/Game Files`.
- **Staging stack**: the second server, its MySQL schema `cosmic_staging` and its client copy.
- **Tested worktree**: the git worktree whose build the staging server runs.

## 1. Findings (read-only inventory)

### Network

| What | Live value | Can a second instance change it? |
|---|---|---|
| Login port | 8484, all interfaces | **No. Hard-coded** at `Server.java:1004` `initLoginServer(8484)`. |
| Channel ports | 7575 + (channel-1) + world*100, all interfaces | **Base hard-coded** at `Channel.java:80` `BASE_PORT = 7575`. The count comes from `worlds[].channels`. |
| Advertised channel address | `HOST: 100.65.63.117` (Tailscale) | Yes, `config.yaml` `HOST`, `LANHOST`, `LOCALHOST`, `TAILSCALEHOST`. |
| Admin control port | 127.0.0.1:8480 | Yes, `ADMIN_CONTROL_PORT_BIND`. |
| Database Console bridge, Director bridge, nav-graph viewer | 8787, 8790, 8790 | Off on live (env not set). Leave off on staging. |
| Patch server | 8485 (python) | Staging does not need it. |

- Java binds these ports exclusively on Windows (no `SO_REUSEADDR` in `net/netty`). A staging server
  that tried 8484 or 7575 would fail to start. It could not take connections from the live server.
- A channel bind failure calls `System.exit(0)` (`Server.java:989-991`). The failure is silent, so the
  start script must check the ports and the log itself.

### Database

- One MySQL80 service on 3306 serves everything. Schema `cosmic` is 264 MB: 123 tables, all InnoDB, no
  views, triggers, routines or events, no foreign keys to other schemas.
- The schema name appears only in `config.yaml` `DB_URL_FORMAT` (no env override). `DB_USER` and
  `DB_PASS` come from env first.
- Liquibase runs at every start against the connected schema. Its lock table is per schema.
- At start the server writes whole tables: `UPDATE accounts SET loggedin = 0`, `UPDATE characters SET
  HasMerchant = 0`, coupon cleanup, economy journal rollback. Each hour it resets ranking columns. These
  are harmless on a separate schema and harmful on a shared one.
- The only MySQL user is `root@localhost`.
- The four biggest tables are logs: `economy_transaction_outbox` 146 MB, `economy_transaction_journal`
  46 MB, `admin_map_presence_samples` 26 MB, `admin_player_samples` 7.5 MB. Without their rows, a copy is
  about 40 MB.
- The optional Postgres databases (economy 5433, social 5434) are off by default.

### Files the server reads and writes

All paths are relative to the working directory, except one.
- Reads: `config.yaml`, `agent-engine.yaml`, `economy-engine.yaml`, `wz/` (`-Dwz-path`, but some code
  hard-codes `wz/`), `scripts/**`.
- Writes: `logs/**` (log4j2, GC log, `typesafe-usage.csv`, heap dumps), `cache/bot-nav/v60`
  (`-Dagents.navigation.cacheDir`, 113 MB), `.runtime/agents/**` (agent state, 16 MB, must match the
  DB), `tmp/`, `docs/agents/evidence/{skillprobe,arena}`, `sso-key.json`, and the tracked files
  `scripts/config/agents/combat-policy/` and `combat-profiles/`.
- `wz/Item.wz/Etc/0400.img.xml` is written only when `ENABLE_SOLO_MAPLING` is true (false on live).
- Tails `logs/gm-command-queue.txt` (GM test queue).
- The one outside path: `../MapleEdit/app/patches/zero-secondary-stats.json` (read). If it is missing,
  the server runs silently with no overrides. The staging run folder must therefore sit directly under
  `FriendServer`.
- No file locks, PID files, mutexes, registry or `%APPDATA%` writes in the server.

### Client

- Login target: `config.ini` `[config] host=127.0.0.1 port=8484 patch_url=http://127.0.0.1:8485`, read
  from the current directory (`kaentake/src/constants.h:5`, `injector.cpp:30-43`). The port is not
  hard-coded in the DLL. Channel connections go where the server says.
- Without `patch_url` the launcher skips patching (`patcher.cpp:640-644`).
- `CreateMutexA_hook` makes the client mutex unique per process. Two or more clients can run.
- Every client window is titled `MapleCommons` (`system.cpp:108-115`).
- Remembered login and UI settings live in `HKCU\Software\Wizet\MapleStory` (`LMA`, `LastPassword`,
  `RMA`, `siCharacterName`, `uiWnd*`). The key path is inside the packed exe. Two copies share it.
- **The SSO bridge is hard-coded to the live admin port.** `cosmicSso.cpp:34-35` posts the typed
  account and password to `127.0.0.1:8480` (`/actions/cosmic-login`, `/actions/cosmic-sso-attest`) on
  every login. It writes `%APPDATA%\Cosmic\sso.json`. The two endpoints only run SELECTs on the live
  `accounts` and `characters` tables.
- Game Files is 17 GB, of which 6.7 GB is not `*.bak`. A hard-link copy on C: costs almost no disk.
  A copy already exists from 2026-10-03 (scratchpad `qlog/client`).

### Tools that assume one stack

- `start-maple-stack.ps1`: `Get-ServerJava` matches `Cosmic\.jar` on the command line. On
  `-RestartServer` it kills every match after the timeout. `Get-ClientWindow` takes the first
  `MapleStory` process. If only a staging client runs, the script reports "client already running"
  and does not start the live client.
- `maple_client.py`: `ADMIN_RUNTIME` is fixed to `127.0.0.1:8480` (positions, buffs, login check),
  `SERVER_LOG` is the live log. Without `MAPLE_PID` it takes the first `MapleCommons` window, which can
  be the staging client.
- `maple-capabilities.yaml` (cua allow-list) allows only the live `MapleStory.exe` and `Launch Game.exe`.
- `stop_all.bat` kills every `MapleStory.exe` by image name.
- MapleEdit talks only to 8480 and the live server paths.

### Capacity, measured 2026-10-06 19:30-19:40

- PC: i5-14400F, 16 logical CPUs, 15.8 GB RAM.
- Available RAM: 0.4-1.0 GB. Committed: 33.5 GB of a 49 GB limit. Page file in use: 13 GB (peak 24 GB).
  Paging: about 3,000 pages/s.
- Most memory is other sessions' work: 19 `claude` processes (6.8 GB private), Maven and surefire JVMs
  (about 8 GB private, temporary).
- Live server: 1.5 GB private, 1.2-1.4 GB working set. Heap after GC about 675 MB of 1.13 GB committed
  (max 2 GB). CPU about 0.7 of one core on average (1,126 CPU-seconds in 28 minutes).
- MySQL80: about 0.7 GB private.

## 2. Proposal

### 2.1 Server code change (one small commit, live behaviour unchanged)

Branch `feat/staging-server`, off the live commit `f5b8c0ab5`.
- New `server:` keys in `config.yaml`: `LOGIN_PORT` (default 8484), `CHANNEL_BASE_PORT` (default 7575),
  `BIND_ADDRESS` (default empty = all interfaces, as today).
- `Server.initLoginServer`, `Channel` and the two Netty servers use them. A missing key keeps today's
  value, so the live `config.yaml` needs no edit.
- A unit test checks the defaults and the overrides.
- Every branch that wants to run on staging needs this commit. The deploy owner merges it into
  feat/companion-manager. New branches then inherit it. The start script refuses a build without it.

### 2.2 Staging profile (slot 1)

| Item | Live | Staging |
|---|---|---|
| Login port | 8484 | 9484 |
| Channel ports | 7575-7577 (3 channels) | 9575 (1 channel by default, `-Channels N` for 9575..) |
| Admin port | 8480 | 9480 |
| Bind address | all interfaces | 127.0.0.1 (tailnet friends cannot reach it) |
| Advertised host | 100.65.63.117 | 127.0.0.1 |
| MySQL schema | `cosmic` | `cosmic_staging` |
| MySQL user | root | `cosmic_staging`, rights on `cosmic_staging.*` only |
| Run folder | `MapleCommons v83 Server` | `FriendServer/staging-server` |
| Jar | `target\Cosmic.jar` | `staging-server\CosmicStaging.jar` (copy, so live scripts never match it) |
| Heap | `-Xmx2048m` | `-Xmx1024m` (`-HeapMb`) |
| Citizens (`population.ENABLED`) | on (717 target) | off by default (`-Population on`) |
| Service bot, bridges, SoloMapling | off | off (the script refuses SoloMapling on) |
| Client folder | `MapleCommons Client/Game Files` | `FriendServer/staging-client` (hard links) |
| Client `config.ini` | port 8484, patch_url | port 9484, no patch_url |

Run folder content, rebuilt from the tested worktree at every start:
- `config.yaml`: the worktree's file plus the staging overrides above.
- `agent-engine.yaml`, `economy-engine.yaml`: copied.
- `scripts/`: copied (12 MB). Never a link, because the server writes tracked files there.
- `wz`: a directory junction to the worktree's `wz/` (read-only while SoloMapling is off).
- `CosmicStaging.jar`: copied from the worktree's `target/Cosmic.jar` after `mvnw package -DskipTests`.

Run folder content kept between starts: `logs/`, `cache/bot-nav/`, `.runtime/`, `tmp/`, `sso-key.json`,
`staging.env` (DB user and password), `seed/`, `lease.json`.

### 2.3 Scripts (beside `start-maple-stack.ps1`)

`tools/cua/start-staging-stack.ps1`:
- `-ServerDir <tested worktree>` builds there and starts the staging server.
- `-NoClient`, `-NoServer`, `-Stop`, `-Status`.
- `-ResetDb` restores the seed (see 2.4). `-Reseed` takes a new read-only copy of live first.
- `-Channels`, `-HeapMb`, `-Population on|off`, `-Force` (skips the RAM check only).

Safety rules in the script:
1. It acts only on processes whose command line contains `CosmicStaging.jar` or whose exe is under
   `staging-client`. It never calls port 8480 and never reads or writes the live run folder.
2. Before a start it checks: ports 9484/9575/9480 free, generated `DB_URL_FORMAT` ends in
   `/cosmic_staging`, `DB_USER` is `cosmic_staging`, the build has `LOGIN_PORT`, SoloMapling off.
3. It refuses to start if available RAM is below 2.5 GB, unless `-Force`.
4. It takes a lease (`lease.json`: session name, branch, start time) so two sessions do not share the
   staging stack by accident. `-Stop` releases it.
5. It stops the server through its own admin port 9480 (`/actions/shutdown`), then waits for java to
   exit. It kills only its own java after the timeout.

Changes to existing tools (outside the repo, need the user's OK):
- `maple_client.py`: env `MAPLE_ADMIN_URL`, `MAPLE_SERVER_LOG`, `MAPLE_GAME_DIR`, with today's values as
  defaults. Without `MAPLE_PID` it picks only a client whose exe is in the live Game Files.
- `start-maple-stack.ps1`: `Get-ClientWindow` counts only the live Game Files exe.
- `maple-capabilities.yaml`: add the staging client's `MapleStory.exe` and `Launch Game.exe`.

### 2.4 Database seed and reset

Seed (`-Reseed`):
1. `mysqldump --single-transaction --no-tablespaces cosmic` as root. This is a consistent read with no
   table locks (all tables are InnoDB). It never writes `cosmic`.
2. Structure only for the four log tables (about 40 MB instead of 264 MB).
3. Copy the live `.runtime/` (16 MB) and `cache/bot-nav/v60` (113 MB) at the same time, because agent
   state must match the DB.
4. Save all of it under `staging-server/seed/`.

Reset between tests (`-ResetDb`), as the `cosmic_staging` user:
1. Stop the staging server if it runs.
2. `DROP DATABASE cosmic_staging`, `CREATE DATABASE cosmic_staging`, load the seed file. The script
   accepts no other schema name.
3. Restore `.runtime/` from the seed.

Liquibase then applies the tested branch's changelogs to `cosmic_staging` at start. New migrations are
tested there first, and a reset undoes them. Expected reset time: 20-60 s (not measured yet).

### 2.5 Client

- Hard-link copy of every non-`.bak` file in Game Files. Real copies of `config.ini` and
  `kaentake_diag.txt`, which the DLL writes. No `.patch_cache` and no `manifest.txt`.
- Without `patch_url` the launcher never patches, so nothing writes through the hard links.
- Launch with `__COMPAT_LAYER=RunAsInvoker` and the copy as working directory, the same as the live client.
- The test accounts in `tools/cua/test-account*.local.txt` exist in the copied DB with the same
  passwords, so `maple_client.py login --account-file` works.
- Registry: before the staging client starts, the script exports `HKCU\Software\Wizet\MapleStory`.
  After the staging login and at `-Stop`, it writes back the live values of `LMA`, `LastPassword`, `RMA`,
  `siCharacterName`, `siWorldID`, `siChannelID`.
- SSO: the script saves `%APPDATA%\Cosmic\sso.json` before the staging client starts and puts it back
  after the staging login and at `-Stop`.

## 3. What cannot be isolated

1. **Keyboard, mouse and foreground window.** cua delivers keys only to the foreground client, and
   movement needs a real key hold. Only one session can drive a client at a time, and not while the user
   types. Headless tests (GM queue file, admin port 9480, `-Dagents.headless-operator`) run in parallel.
2. **RAM.** Both stacks share 15.8 GB (see 4.1).
3. **MySQL instance.** One service, one 128 MB buffer pool, shared disk. Schemas and users are separate.
4. **`HKCU\Software\Wizet\MapleStory` and `%APPDATA%\Cosmic\sso.json`.** Save and restore only. If the
   live client logs in during a staging run, the restore can write back older values.
5. **The client's SSO call to the live admin port.** At each staging login the live server checks the
   test account's password (read-only). A staging Kaentake.dll that reads the SSO port from `config.ini`
   would fix this later.
6. **TypeSafe API key and Ollama.** Shared quota, cost and load. Staging writes its own
   `logs/typesafe-usage.csv`.
7. **MapleEdit.** It works only with the live server. Staging has no dashboard.
8. **`stop_all.bat`.** It also kills the staging client.

## 4. Risks

1. **Memory (main risk).** Staging server about 1.0-1.3 GB with citizens off, plus about 0.5 GB for a
   client (unconfirmed). The PC already pages at about 3,000 pages/s. More paging can slow the live
   server's ticks, which caused past regressions. Mitigation: start staging only for a test, stop it
   after, RAM check in the script, citizens off, one channel, 1 GB heap.
2. **Live DB.** The only access to `cosmic` is the read-only seed dump. The staging server's MySQL user
   has no rights on `cosmic`, so a wrong config fails at connect. The reset script hard-codes
   `cosmic_staging`.
3. **Port mistakes.** Exclusive binds mean a wrong port makes the staging server fail, not the live
   server. The script checks ports before the start and reads the staging log after it.
4. **Wrong client driven.** Two windows share the title `MapleCommons`. The `maple_client.py` change and
   `MAPLE_PID` pinning remove this.
5. **Stale seed.** The copy ages. `-Reseed` refreshes it from live (read-only).
6. **Branch without the port commit.** It cannot run on staging. The script says so and stops.

## 5. Open items

- Slot 2 (ports 10484/10575/10480, schema `cosmic_staging2`) only if RAM allows.
- Staging Kaentake.dll with a configurable SSO port.
- A desktop lease so two sessions do not drive clients at the same time.

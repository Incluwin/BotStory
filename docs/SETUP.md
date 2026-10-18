# Setting up BotStory

This guide takes you from a fresh download to playing on your own BotStory server. The server runs
anywhere Java 21 or Docker runs; the game client (GMS v83) runs on Windows.

- [1. The server on Windows](#1-the-server-on-windows) (recommended: two double-clicks)
- [Other ways to run the server](#other-ways-to-run-the-server) (Docker, or by hand on any OS)
- [2. The game client](#2-the-game-client)
- [3. Logging in](#3-logging-in)
- [4. Playing with friends](#4-playing-with-friends)
- [5. Optional services](#5-optional-services)
- [Troubleshooting](#troubleshooting)

## 1. The server on Windows

`setup.bat` checks for and sets up everything the server needs.

1. **Get the code.** Either `git clone https://github.com/Incluwin/BotStory.git`, or on GitHub
   choose **Code > Download ZIP** and unzip it. Put it in a folder you can write to, such as
   `C:\Games\BotStory`.
2. **Double-click `setup.bat`.** It walks through these steps and is safe to run again:
   - **Java 21.** If Java 21 or newer is missing, it offers to install Amazon Corretto 21 with
     `winget`. After an install, run `setup.bat` again so the new Java is on PATH.
   - **MySQL 8.** It looks for a MySQL server on port 3306.
     - With none and Docker Desktop running, it offers to start MySQL 8.4 in a container named
       `botstory-mysql`. It picks a random password and keeps the data in the Docker volume
       `botstory-mysql`.
     - Without Docker, install [MySQL Community Server 8](https://dev.mysql.com/downloads/installer/)
       ("Server only", port 3306, choose a root password) and run `setup.bat` again.
   - **`.env`.** It copies `.env.example` to `.env` and saves your database login there.
     It asks for the MySQL password, checks it, and creates the `cosmic` database.
   - **The first build.** It builds `target\Cosmic.jar`; downloading the libraries takes a few
     minutes the first time.
3. **Double-click `start-server.bat`.** It loads `.env` and runs `launch.bat`, which rebuilds
   whatever changed and starts the server with a 2 GB heap. The first start creates the database
   tables, the Residents and the citizens, so it takes longer than later starts. The server is
   ready when the window shows `Cosmic is now online`.
4. **To stop the server,** type `!shutdown` in game as a GM, or press Ctrl+C in the server window.
   Both save everyone first. Closing the window kills the server without saving.

Settings live in two files:

- `config.yaml`: rates, worlds, the living world, companions and bosses. The server reads it at
  start.
- `.env`: your secrets and local addresses. Never commit it; it is git-ignored.

## Other ways to run the server

### Docker (Windows, macOS, Linux)

Requires Docker Desktop (or Docker Engine with Compose v2). Give Docker at least 4 GB of memory:
the server alone uses a 2 GB heap.

```bash
cp .env.example .env
```

Set `DB_PASS=` in `.env` to a password of your choice; it becomes MySQL's root password. Then:

```bash
docker compose up --build
```

This builds the server image and starts it together with MySQL. Clients connect to this PC on
ports 8484 and 7575-7577.

- `config.yaml`, `scripts/` and `wz/` are mounted into the container, so for changes to those,
  `docker compose restart maplestory` is enough.
- Code changes need `docker compose up --build`.
- The optional economy database starts only with `docker compose --profile economy up`. Set
  `ECONOMY_DB_PASSWORD` in `.env` before you use it.
- **Players on other PCs.** Docker Desktop passes every connection to the server from its own
  network address (172.17.0.1), so the server takes every player, even one on this PC, for a
  home-network player and sends them `SERVER_LANHOST`. `SERVER_HOST` and `SERVER_TAILSCALEHOST`
  have no effect here. Set `SERVER_LANHOST=` to the one address all your players use, such as
  this PC's Tailscale IP; players on this PC can connect through that address too.

### By hand (any OS)

Run these from the repository folder:

1. Start MySQL 8 on `localhost:3306`.
2. Set `DB_USER` and `DB_PASS` in the environment.
3. Build:

   ```bash
   ./mvnw package -DskipTests
   ```

4. Start the server:

   ```bash
   java -Xmx2048m -Dagents.scheduler.mode=central-sequential -Dwz-path=wz -jar target/Cosmic.jar
   ```

The server creates the `cosmic` database on its first start (`createDatabaseIfNotExist` in
`config.yaml`'s `DB_URL_FORMAT`).

## 2. The game client

BotStory's client is the stock Global MapleStory v83 client. These are the steps that work with
public files:

1. **Install v83 with the Cosmic WZ files.** From [P0nk/Cosmic-client](https://github.com/P0nk/Cosmic-client):
   1. Download `MapleGlobal-v83-setup.exe` and the 12 files in `cosmic-wz`. Open each file on
      GitHub and use its download button: they are stored in Git LFS, so a `git clone` without
      Git LFS gets small pointer files instead.
   2. Run `MapleGlobal-v83-setup.exe`.
   3. Delete `HShield`, `ASPLnchr.exe` and `Patcher.exe` from the install folder.
   4. Copy the files in `cosmic-wz` over the installed ones.

   Plain v83 WZ files do not work: the server uses NPCs, maps and items that only the Cosmic
   files have. A Community Repack WZ set works too.
2. **Pick a way to connect to your server.** Both default to `127.0.0.1:8484`, this PC:
   - [kaentake](https://github.com/iw2d/kaentake/releases) (a resizable HD client). Copy the
     release files into the install folder and run `Kaentake.exe` as administrator. For another
     server, run `Kaentake.exe <server address> 8484`, or set `host=` and `port=` in its
     `config.ini`. Keep `MapleStory.exe` for this option.
   - `HeavenMS-localhost-WINDOW.exe` from P0nk/Cosmic-client: copy it into the folder and run it.
     It only connects to this PC (P0nk's README shows how to hex-edit the address), and
     Windows Security flags it as a virus because it is a modified client.
3. **Set the large-address-aware flag** (kaentake option only). A long session can run the
   client out of its 2 GB address space. This sets the flag on the stock `MapleStory.exe`; it
   checks the file's hash first and moves the original to `botstory-wz-backup\` next to it:

   ```bash
   py -3 tools\wz-patcher\botstory_wz.py exe --exe "C:\Nexon\MapleStory\MapleStory.exe"
   ```

4. **Add the Residents' portraits and quest log** before you play in the Victoria towns:
   1. Start the server once, so it creates the Residents, then stop it.
   2. Run this from the server folder (it needs [Python 3.8+](https://www.python.org/downloads/)):

      ```bash
      tools\living-world-client.bat "C:\Nexon\MapleStory"
      ```

      It renders each Resident's portrait from your own `Character.wz`. It also writes the
      Resident NPCs (ids from 9970000, and 9970999 for clickable quest markers) and the
      Residents' quests into your client's `Npc.wz`, `String.wz`, `Quest.wz` and `Etc.wz`. The
      originals go to `botstory-wz-backup\`.

   The v83 client expects every NPC the server shows it to exist in its WZ files, so do this
   step before you play. Run it again after the Residents' gear or quests change.
5. **Quest markers without BotStory's `Custom.wz`.** The markers over Residents are drawn from
   BotStory's `Custom.wz`. On any other client, set both marker paths in `config.yaml` to the
   stock icons:

   ```yaml
   QUEST_MARKER_AVAILABLE: "UI/UIWindow.img/QuestIcon/0"
   QUEST_MARKER_READY: "UI/UIWindow.img/QuestIcon/2"
   ```

   They then draw at the Resident's feet instead of over its head.

### What the public client does not have yet

The BotStory host's own client adds Nexon-derived WZ content, which is not in this repository:

- `Custom.wz` with the native Crew window, the head-height quest markers and the mob HP bar.
- BotStory's item, skill and cash-shop edits, and the large quick-slot bar.
- Images for 307 equips the server took from the Community Repack, which a client on the Cosmic
  WZ set lacks.

The BotStory build of kaentake is not published yet either. It adds the Crew window, the client
feature toggles and the feature handshake. `tools/wz-patcher/README.md` describes how that pack
is applied.

Without them, you manage companions with:

- the stock party window: invite calls a companion, expel benches it;
- chat commands;
- the companion card: double-click your companion;
- the Adventurer's Roster item, which opens the Crew window as NPC dialog.

## 3. Logging in

- **`admin` / `admin`:** a ready-made GM account. Its character `Admin` is GM level 6, so it can
  use every `!` command. PIN and PIC are off (`ENABLE_PIN`/`ENABLE_PIC` in `config.yaml`).
- **Any new username and password:** this makes a player account on first login
  (`AUTOMATIC_REGISTER`). To make one of its characters a GM, log in as `Admin` and run
  `!setgmlevel <character> 6`.
- **First steps:** `@commands` lists the player commands, `@roster` shows your companions, and
  `@quests` shows your Resident quests. [docs/LIVING_WORLD_GUIDE.md](LIVING_WORLD_GUIDE.md)
  explains recruiting and talking to Residents.

## 4. Playing with friends

Players on the server's own PC need nothing extra. For anyone else, put the address their client
should use in `.env`, then restart the server. Each player points their client at that address,
for example with `Kaentake.exe <address> 8484`. Under Docker Desktop only `SERVER_LANHOST`
applies (see [Docker](#docker-windows-macos-linux)).

| Players | `.env` line | Value |
|---|---|---|
| Your home network | `SERVER_LANHOST=` | this PC's IPv4 address (`ipconfig`) |
| Over [Tailscale](https://tailscale.com/) | `SERVER_TAILSCALEHOST=` | this PC's Tailscale IP |
| The internet | `SERVER_HOST=` | your public IP; forward TCP 8484 and 7575-7577 to this PC |

Before strangers can reach the server:

- Change or remove the `admin` account, since its password is public. For example: make your own
  character a GM, then delete the account in MySQL.
- Consider turning PIN and PIC on in `config.yaml`.

## 5. Optional services

The server runs without any of these.

| Service | What it adds | How |
|---|---|---|
| TypeSafe Jev | Typed judgments for free-form chat with companions and Residents | `TYPESAFE_API_KEY` in `.env`; switches `AGENT_TYPESAFE_*` (agent-engine.yaml), `USE_TYPESAFE_*` (config.yaml) |
| Ollama | Generated small talk for citizens and companions | `SOCIAL_OLLAMA_*` in `.env`; without it, the deterministic catalog answers |
| MapleEdit admin dashboard | The loopback AdminControlPort (:8480) | `ADMIN_CONTROL_TOKEN` in `.env` |
| Economy database (PostgreSQL) | `!economy` runs and the economy dashboard | `ECONOMY_DB_*` in `.env`; `docker compose --profile economy up economy-db` |
| Database Console | A web console for game data | `database-console/README.md`; off unless `COSMIC_DATABASE_CONSOLE_BRIDGE_ENABLED=true` |

## Troubleshooting

- **The client can't connect.**
  - "Unable to connect to the login server" means the server isn't online yet, or the client is
    pointed at the wrong address.
  - Wait for `Cosmic is now online`.
  - Check that nothing else is using port 8484.
- **MySQL login errors at start.** Fix `DB_USER`/`DB_PASS` in `.env`, or run `setup.bat` again.
- **The build fails.** `java -version` must report 21 or newer. When several JDKs are installed,
  `JAVA_HOME` must point at a JDK 21.
- **`Could not read config file config.yaml`.** The server must start from the repository folder;
  `start-server.bat` does this for you.
- **The client closes in a Victoria town.** Check that you ran step 4 of
  [the game client](#2-the-game-client) (`tools\living-world-client.bat`) on that client, so the
  Residents' NPCs are in its WZ files.

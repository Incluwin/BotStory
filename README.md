# BotStory

BotStory is a Global MapleStory v83 server built on
[P0nk/Cosmic](https://github.com/P0nk/Cosmic) and the
[cosmic_agents](https://github.com/Kiwiism/cosmic_agents) Agent platform. Its
autonomous characters are normal Cosmic characters that obey the server's
gameplay rules:

- **Companions** you recruit, party with, bench and manage without typing: they
  level, buy supplies, run party quests and clear bosses with you.
- **Residents**: town characters with routines, dialogue, portraits, quests
  (shown in the client's quest log), friendship levels and contracts. See
  [docs/LIVING_WORLD_GUIDE.md](docs/LIVING_WORLD_GUIDE.md).
- **Citizens**: an ambient population that trains, chats, forms guilds and
  trades. See [docs/POPULATION_GUIDE.md](docs/POPULATION_GUIDE.md).
- An admin control port (used by the MapleEdit dashboard), TypeSafe Jev
  judgments for chat and intent, and a skill-probe harness.

The game client is the stock GMS v83 client with the BotStory fork of
[kaentake](https://github.com/iw2d/kaentake), running on either the clean Cosmic
WZ files or the Community Repack WZ files; see [the game client](docs/SETUP.md#2-the-game-client).

## Credits And Upstream Lineage

- [P0nk/Cosmic](https://github.com/P0nk/Cosmic) is the upstream v83 server base.
  Cosmic was created and maintained by P0nk with contributions from the wider
  Cosmic community.
- [Kiwiism/cosmic_agents](https://github.com/Kiwiism/cosmic_agents) is the Agent
  platform BotStory's companions run on.
- [nutnnut/Cosmic](https://github.com/nutnnut/Cosmic) provided the companion
  behavior from which the reconstructed Agent runtime was derived.
- [MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling)
  provided the ambient player population (`soloMapling`).
- Cosmic itself descends from the OdinMS and HeavenMS emulator lineage.

This repository is not the official Cosmic project.

## Current State

The Agent implementation is divided into explicit ownership areas:

- `server.agents.runtime` owns live sessions, lifecycle, state, tick
  orchestration, scheduling, and registries.
- `server.agents.capabilities` owns movement, navigation, combat, loot,
  supplies, inventory, equipment, trade, shops, builds, dialogue, social,
  quest, NPC, and reactor behavior.
- `server.agents.commands` owns command parsing and coordination.
- `server.agents.plans` contains plan models and retained script execution.
- `server.agents.integration` defines server-facing gateways.
- `server.agents.integration.cosmic` contains Cosmic-specific adapters.
- `server.agents.monitoring` exposes runtime, scheduler, parity, and queue
  diagnostics.

Agent runtime hardening includes bounded asynchronous queues, session-scoped
callbacks, lifecycle cleanup, safe navigation-cache persistence, weighted graph
cache eviction, concurrent combat-profile caching, secure backing-account
provisioning, per-Agent mailbox support, and a central scheduler implementation.
The mailbox and central scheduler remain disabled by default; the established
compatibility paths remain active.

The repository also contains read-only game-data catalog tooling and a Database
Console for administrative game-data access. The Database Console should not be
exposed publicly without authentication and network hardening.

## Features

Everything here is on in the shipped `config.yaml` unless it says otherwise. The switches live in
`config.yaml` (sections `living_world`, `population`, `knowledge`, `mods`) and `agent-engine.yaml`.
Items marked *(cosmic_agents)* came with the Agent platform; BotStory tuned or extended them.

### Companions: recruiting and running a crew

- **Recruiting:**
  - Recruit Residents by contract, paying their price in mesos, items or kill proof; they stay on
    your roster.
  - Hire citizens for a fee and wages. A citizen who has partied with you for 10 minutes offers
    to stay.
- **The roster:** up to 10 companions per player, 5 in your party and 5 on the bench.
- **Managing it without typing:**
  - The stock party window: invite calls a companion, expel benches it. A bot never leads the
    party, and two players share the seats fairly.
  - The companion card (double-click your companion): follow, wait or hunt; camp duty; fighting
    style; supplies and loot; haul, wages and gear; Let's trade; gifts; part ways.
- **Calling companions:**
  - A called companion travels to you with an ETA and walks in through the portal.
  - It waits outside instances and follows you across channels.
  - It parks when you log out and comes back when you log in.
- **The Crew window:** the Adventurer's Roster item opens it as NPC dialog. With the BotStory
  client it is a native, mouse-driven window (drag to swap, hotkey U).
- **Lineups and records:**
  - Named lineups (usual, Hunting, PQ, Boss) and "Who should come along?" suggestions; party
    quest NPCs name their pick.
  - The card keeps records, such as "KPQ: cleared 1 of 6".
- **Social:**
  - Opt-in crew rotation ("Keep my crew fresh").
  - Bench companions answer your Party Search.
  - Companions ask to be buddies and sit in a "Crew" buddy group.
- **Bench duties while you play:** train, farm mesos or an item, gather, sell the haul, run a
  real Free Market stall, or rest. Duties come with haul, Duey and bank hand-over, wages and
  upkeep. Leave the choice to them and a bandit picks the duty and the spot.
- **EXP:** companions don't dilute your EXP, and bench EXP is banked toward your level.
- **Chat verbs** in map chat or a whisper, checked against ownership: call, bench, swap, park,
  release, "*name* camp here", train, farm, gather, sell, stall, rest, haul, wages, come back.

### What companions do on their own

- **Leveling:** grinding, patrolling or holding a spot *(cosmic_agents)*. Job advancement
  errands: they walk to their instructor and back.
- **Builds:**
  - SP and AP follow a career bundle; a mismatched bundle is re-picked with one AP reset, and
    spare SP goes to dump skills.
  - They read the skill and mastery books in their bag and say in party chat which ones failed or
    need an earlier book.
- **Gear:** they choose and upgrade it, keep the better roll, and Residents wear their own
  friendship gear.
- **Supplies:**
  - They restock their own potions to the standard of the party quest or boss ahead, walking to
    a shop and back. This includes field-boss towns, Holy Water from Orbis for Horntail, and seal
    and darkness cures.
  - Short on mesos, they ask you for potions or coin.
  - They buy and drink Sniper, Warrior and Magic potions when those help, spending at most 25% of
    their spare mesos, with a toggle per companion.
  - They buy and share ammo, check it before a run, and sell ammo they can't use to free Use
    slots.
- **Loot:** by policy (everything, own kills, quest items, nothing), and they leave boss drops to
  people.

### Combat

- **Basics:** skill planner, party buffs and heals *(cosmic_agents)*, and autopot with HP/MP
  thresholds set per companion.
- **Combat learner:** semi-Markov Q-learning that practices only on bench slices nobody is
  watching. Live play uses a learned table only after `!combatarena` adopts it.
- **Support:** Priests and Bishops cast Dispel on afflicted party members. In boss runs, a seduced
  or confused member casts Hero's Will.
- **Skill handling:** rebuffs after a mob dispel, summons, dashes, crash skills, weapon charges,
  the Berserk window and a Dragon Roar HP guard.
- **Navigation:** a graph with jump, drop, climb, rope, portal, Flash Jump and Teleport edges
  *(cosmic_agents)*. BotStory adds:
  - following you through the portal you took;
  - an instance-aware follow guard;
  - ladder and rope fixes;
  - Haste and Teleport routes for the RJPQ Dark Labs.

### Party quests

- **KPQ, HPQ, LPQ and EPQ** with companions (`COMPANION_PQ_ENABLED_KEYS`):
  - The entry NPC proposes a lineup and can call from your bench, while extras wait beside her.
  - Downed companions tap out.
  - Run records and clear-time estimates.
- **RJPQ** with companions (the Romeo and Juliet desks, Dark Lab runners): built, off by default.
  Add `rjpq,rjpqj` to the key list.
- **OPQ, LMPQ and PPQ** companion runs (sealed-room learning, the LMPQ regroup, the PPQ medal
  hand-over): started by a GM only, with `!opqtest`, `!lmpqtest` or `!ppqtest party`.
- **Fixed:** the PPQ side-room portals.

### Bosses

- **Companion boss runs:** the server moves the boss, and its attacks hit companions.
  - Companions spend their own daily entries and always leave two seats for players.
  - A companion that dies to a field boss rests in town first.
  - You can call bench allies into a run.
- **On by default:** Easy Balrog and Pink Bean (`COMPANION_BOSS_ENABLED_TYPES`).
- **Built, off by default:**
  - Normal Balrog, Zakum, Papulatus, Scarga, Horntail, Showa, Latanica and the five Neo City
    bosses.
  - Field-boss groups for Victoria, Ossyria, Ludus, Mu Lung, Nihal, Pianus, Leafre, the Temple of
    Time, Zipangu and Masteria.
- **Test kit:** `!bossgear` gives a companion a boss kit and puts its own gear back afterwards.

### Residents: the living world

- **The cast:** 72 named Residents in the seven Victoria towns, 24 of them recruitable. They
  follow hourly routines and sleep when nobody is near.
- **Talking to them:** say a Resident's name, stand near them or whisper. "menu" or a
  double-click opens a real NPC window with their portrait. The portrait is rendered from your
  client's own `Character.wz`, so no game art ships with the server.
- **Quests:** 197 of them, 118 for friendship.
  - They show in the client's quest log under "Residents", with Quest Helper targets.
  - Quest markers float over Residents, shown only to the player they concern.
- **Friendship:**
  - Levels 1-10, earned from quests, fame, daily gifts, party quests and bosses.
  - Friendship quests give the Resident's own weapon, or armour made for your class, pre-scrolled
    to a recipe.
  - At friendship level 4, a Resident sends a buddy request.
- **Story:** 25 contracts, 9 story arcs that change who is recruitable, and Resident-to-Resident
  rumors played out in public.

### Citizens: an ambient population

- **The population:** 717 generated citizens, up to 240 of them online on channel 1. Each has a
  name, look, career, backdated age and level, with births and retirements.
- **Daily presence by cohort** (busy evenings, different weekends): training, town, party quest
  lobbies, the market and AFK. It spans 17 regions and 30 towns profiled by the TownLife
  generator.
- **Chatter:** templated lines in each citizen's own voice, megaphones, and conversations with
  optional Ollama replies.
- **Social life:** 24 bot guilds, buddies and fame.
- **Free Market:** a price index that drifts with sales. Merchant citizens open real stalls and
  buy underpriced goods from player shops.

### Server knowledge

- **Answers from this server's data:** bots answer questions like "who drops steelies", "where
  do I buy manas" or "how do I get to Ludi" from this server's own WZ, drop and shop data, live
  rates and slang aliases.
- **Knowledge to match:** each bot knows what a player of its level and home town would know;
  only veterans quote drop rates.
- **Shared with decisions:** the same index picks farming fields and citizens' gear. Content
  linters check every id against it.

### Talking to bots

- **No setup needed:** phrase packs and menus work without any key or model.
- **TypeSafe Jev** (optional): judges chat that no rule matched, with a "did you mean...?". It
  also judges trade replies and party quest questions in shadow mode.
- **Ollama** (optional): generated small talk for citizens and companions.

### Gameplay changes from stock Cosmic

- **Rates:**
  - 10x EXP, with monster EXP held to 5x below level 30.
  - 3x mesos, drops and boss drops.
  - 4x mob density, 2-second respawns and full respawns.
- **Equipment:**
  - Secondary-stat requirements are gone; only the class's primary stat counts.
  - Any class can wear any gear.
  - Equipment levels up.
- **Characters and items:**
  - A permanent Hyper Teleport Rock at level 10.
  - Perfect Pitch as a global drop, a rebalanced NX card drop and Fusion Anvil transmog.
  - Pets never expire, characters get the maximum inventory slots, and HP/MP caps at 30,000.
  - Autosave every 10 minutes and a custom keyset on new characters.
- **Login and security:** PIN and PIC off for local play, a per-IP connection throttle, and
  anti-cheat in log-only mode.

### Client features and server mods

- **Client feature handshake:** the BotStory client reports its feature toggles, and the
  server's policy allows or denies each one (`mods.FEATURES`). Free Addy's damage and speed caps
  and the extended Chain Lightning are allowed by default. Clients that never report are never
  affected. See [docs/CLIENT_FEATURE_HANDSHAKE.md](docs/CLIENT_FEATURE_HANDSHAKE.md).
- **Server mods** with switches (`mods.SWITCHES`) and a `!mods` report per client.
- **The BotStory client:** the native Crew window, the head-height quest markers and the feature
  toggles come from the BotStory kaentake build and its `Custom.wz`, which are not public yet.
  See [docs/SETUP.md](docs/SETUP.md#what-the-public-client-does-not-have-yet).

### Commands

- **Players:**
  - `@roster` (or `@companions`): your companions.
  - `@bond <name>`: your friendship with a Resident.
  - `@quests`: your Resident quests.
- **GMs, living world:**
  - `!world`: status, reload, wake/sleep, arcs, flags, bonds, rumors, phrases.
  - `!citizens`: population status, births, seeding, guilds, market.
  - `!knowledge`: status, reload, or ask it a question.
  - `!grantcompanion`: put a Resident on a player's roster.
  - `!mods`: mod switches and client features.
  - `!typesafe`: Jev spend and cost projection.
- **GMs, companions and testing:**
  - `!setlevel`, `!bossgear`, `!bossmirror`, `!combatarena`, `!skillprobe`, `!stepto`.
  - The party quest test runs `!kpqtest`, `!hpqtest`, `!lpqtest`, `!epqtest`, `!opqtest`, `!lmpqtest`,
    `!ppqtest` and `!rjpqtest`; `party` runs your current party as a companion run.
  - `!spawnbot`, `!regennav`, `!botcfg` *(cosmic_agents)*.

### Fixes and hardening

- **Deadlocks:**
  - owner logout (party lock vs map lock);
  - logout buffs;
  - item-drop packets built under the map lock;
  - an attack-buff sum vs the party lock, which froze every timer.
- **Database:**
  - UTC database sessions, so saves no longer fail in a daylight-saving gap;
  - 11 save indexes (350 ms to 43 ms per save);
  - a shutdown that saves players first and finishes in seconds.
- **Packets and bans:** packet budgets for mob control and the Crew window. Bots are never
  autobanned.
- **Ported from other Cosmic forks:** thread-safe monster information caches, serialized map
  script engines, and the early release of a dead mob's tasks.
- **From cosmic_agents:**
  - a packet frame cap and per-family rate limiter;
  - shop, NPC-prompt and login validation;
  - transactional deletion and dirty-section autosaves;
  - bounded thread pools and JVM guardrails.

### Tools

- **Setup:** `setup.bat` and `start-server.bat` for Windows, and `docker compose` for any OS.
- **Client:**
  - [tools/wz-patcher](tools/wz-patcher/README.md), including the pack-free `exe` command for the
    large-address-aware flag.
  - `tools\living-world-client.bat`: Resident portraits and the quest log for any client.
- **Content:** content linters, the knowledge export and the Jev cost projector.
- **Diagnostics:** the skill probe harness (`!skillprobe`) and the map-graph viewer (off unless
  `COSMIC_AGENT_MAPGRAPH_ENABLED` is set).
- **Admin:** the loopback AdminControlPort for the MapleEdit dashboard, and the Database Console.
- **Mod intake:** triages mod releases from Discord and forks of Cosmic; it needs your own bot
  token.
- **Tests:** a suite of about 11,500 tests; see [docs/TEST_GUIDE.md](docs/TEST_GUIDE.md).

## Setup

On Windows the server takes two double-clicks; [docs/SETUP.md](docs/SETUP.md) has the details,
Docker, the game client, hosting friends and troubleshooting.

1. **Get the code:** `git clone https://github.com/Incluwin/BotStory.git`, or Code > Download ZIP.
2. **Run `setup.bat`.**
   - It checks for Java 21 and offers to install it.
   - It finds MySQL, or offers to start one in Docker.
   - It saves the database login in `.env` and builds the server.
3. **Run `start-server.bat`** and wait for `Cosmic is now online`.
4. **Set up a v83 client:** see [docs/SETUP.md](docs/SETUP.md#2-the-game-client).
5. **Log in** as `admin` / `admin` (a GM), or with any new username and password.

With Docker on any OS, set `DB_PASS` in `.env`, then start everything:

```bash
cp .env.example .env
```

```bash
docker compose up --build
```

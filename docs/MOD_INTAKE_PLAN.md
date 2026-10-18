# Mod intake plan: one toggleable v83 client and server from the M. Development C. releases

User 2026-10-17: "scan/ingest chat and attachment data from a certain discord server, take those conversations as
context, and use that to build and maintain a unified kaentake v83 client with all mods, as well as server mods, for
v83 specifically. Unified client should have a way to toggle which features are available."

The server is **M. Development C.** (invite `discord.gg/bTG7fubsfG`). Its releases section holds many threads of
GitHub links and code fixes that today each have to be worked into a server by hand, in a bespoke way. This plan
automates the reading, sorting and porting, and keeps the result in one client DLL and one server, each mod behind a
switch.

## Decisions (user, 2026-10-17)

| Question | Answer |
| --- | --- |
| Discord access | A read-only bot the user adds to the server (Discord API, incremental sync). No self-bot. |
| Binary-only mods (DLLs, patched exes) | Studied (disassembled) only, then ported as source into the one `Kaentake.dll`. Never shipped, never run. |
| Who switches client features | The player (config.ini, launcher page), and the server may veto or require a feature at login. |
| Target | v83 only. Client = Incluwin/kaentake fork (base `ship/2026-10-14-wzmap`). Server = BotStory Server. |

## Pipeline

```
Discord releases ──► 1 Ingest ──► 2 Fetch sources ──► 3 Catalog + triage ──► 6 Port ──► ship (user OK)
  (bot, REST)         SQLite        git mirrors,         mods.yaml,             mod/<id>
                      + quarantine  gists, pastes         Jev judgments          branches
                                                                 ▲
                      7 Maintain: new replies in a tracked thread ┘  (daily sync)

4 Client toggle framework (kaentake)  ─┐
5 Server mod framework + handshake    ─┴─► every ported mod plugs into these
```

One file is the source of truth for what exists: `tools/mod-intake/catalog/mods.yaml` (in this repo). The kaentake
registry table, the server's `mods:` defaults, the launcher's feature list and a docs page are generated from it.

## Phases

### P1 Ingest (`tools/mod-intake`, Python stdlib)
Status 2026-10-17: built on `feat/mod-intake`, 39 tests against a fake Discord API (`tools/mod-intake/README.md`).
The first real sync waits for the bot token.
Update 2026-10-17: the user cannot add a bot (no Manage Server, no admin to ask), so two more sources feed the same
store: `forks` (GitHub forks of P0nk/Cosmic and iw2d/kaentake with work of their own, one thread per fork branch,
the diff from the compare API as a stored attachment, nothing cloned) and `inbox` (Discord text pasted by hand into
`<data dir>/inbox/`, one file per thread). Exporting with the user's own account was ruled out (Discord's Terms).
- REST only (no gateway): list the releases channels, their active and archived threads (forum and text channels),
  then page messages with `after=<last snowflake>` per thread. 429 handling from `retry_after`.
- Store in SQLite (FTS5): channels, threads, messages, edits (keep old content), reactions count, embeds,
  attachments, extracted links (GitHub repo/commit/PR/gist/blob, pastebin, other).
- Attachments: download on first sight (Discord CDN links are signed and expire; refresh via the API if a download is
  missed), store by sha256 in a quarantine folder with no execute path, record type and size.
- Data folder outside git: `FriendServer/mod-intake-data/` (db, attachments, `.env` with the bot token). Discord
  content is other people's messages: it never goes to GitHub or anywhere else.
- CLI: `sync`, `status`, `search <text>`, `thread <id>`.
- Done when: a full first sync of the releases section, a second sync fetches only new messages, and tests pass on
  recorded fixtures (built first, so P1 does not wait for the bot token).

### P2 Fetch linked sources
Status 2026-10-17: built on `feat/mod-intake-p2` (`fetch` and `sources` commands, `sources` and `code_blocks`
tables), tested against local bare repos and a fake web (`tools/mod-intake/README.md`, "Fetch (P2)"). The first
real run waits for the first real sync.
- GitHub repos: `git clone --filter=blob:none` into a mirror cache, pinned to the commit the thread links or the
  default branch head at sync time. Gists and pastes saved raw. Nothing fetched is built or run.
- Code blocks inside messages are extracted as files (language from the fence, else guessed).
- Done when: every link in the ingested threads resolves to a stored snapshot or a recorded failure reason.

### P3 Catalog and triage
Status 2026-10-17: built on `feat/mod-intake-p3` (`triage`, `dossier`, `board`, `overrides`, `inventory`;
`catalog/mods.yaml`, `overrides.yaml`, `inventory.yaml`), tested on the fake Discord fixture after a P2 fetch from
local repositories with a fake Jev (`tools/mod-intake/README.md`, "Triage (P3)"). The real triage run waits for the
first real sync.
- One dossier per release thread: title, author, date, links, attachments, code blocks, and the follow-up replies
  that carry fixes or bug reports.
- Judgments via TypeSafe Jev (typed, per thread; see `use-typesafe-skill`), with rules in code wherever plain
  matching suffices (v83 offsets like `0x00Axxxxx`, file paths, Java package names):
  - kind: client hook / client WZ / server Java / server script / DB / tool / not a mod
  - version: v83 / other / unknown; server family: Cosmic, HeavenMS-line (ports cleanly) / other base
  - does this reply contain a fix to the release, a bug report, or neither
  - applies to BotStory: already have it / conflicts with something we have / new
- Output: `mods.yaml` entries with status `candidate | skipped (reason) | porting | ported | shipped`, plus an HTML
  board to browse them.
- Our current mods are entered first (inventory below), so "already have it" has something to match.
- Done when: every release thread has a dossier and a triage result the user can browse and correct.

### P4 Client toggle framework (kaentake)
- A registry replaces the hard-coded list in `AttachClientHooks()` (`src/hook.h`): one entry per module with id,
  title, default, `core` flag, `server_gated` flag, attach function, and the target addresses it hooks.
- **Core** modules always attach and have no switch: bypass, inlink, stringpool, resman, wzmaplimit, version guard.
- `config.ini` `[features]` section, one line per module (`mob_hp_tag=1`). Missing line = the module's default, so
  today's behaviour is unchanged on day one. The existing `crew_window` key keeps working as an alias.
- Each hook checks a runtime flag before doing its own work and falls through to the original when off. Disabled in
  config = not attached at all; vetoed by the server = attached but inert, so the veto needs no restart.
- Two modules hooking the same address are reported at build time (registry check) and in the catalog.
- Launcher (`Launch Game.exe`) gets a Features page: checkboxes from the registry, writes `config.ini`.
- Done when: every existing module has an entry, a client with no `[features]` section behaves byte-for-byte like
  the shipped DLL (same attach list), and each switch is checked in game on the test client.

### P5 Server mod framework and feature handshake
- `server.mods.*`: a `ServerMod` registry with a `mods:` section in `config.yaml` (US-ASCII only, see
  `cosmic-agents-merge-state`), one switch per server mod, read at boot and flippable by a GM command.
- Handshake modelled on the Crew window (Recv 0x117 HELLO / Send 0x17A WELCOME): after login the client sends its
  feature list with versions; the server answers per feature `allow | deny | require`. A required feature the client
  lacks gets a clear message, not a silent failure. Old clients that never send the list are treated as today.
- Gameplay-affecting client mods (for example `AttachFreeAddy`: damage cap 999,999, speed cap 250) default to
  `server_gated`, so the server decides.
- Done when: unit tests for the policy table, and a live check that a server `deny` turns a feature inert on a
  running client.

### P6 Port pipeline
- Per candidate: one branch `mod/<id>` in kaentake or this repo (both for paired mods), the code ported into the
  framework from P4/P5, a `ported_from` pin (thread message id + upstream commit sha), build, tests, review.
- Client mods written for v83 port nearly directly; hex patches become hooks; mods for other versions need their
  addresses mapped to v83 and are marked as such (skipped unless the mapping is certain).
- Server mods from the HeavenMS line usually map onto Cosmic; each port lists the files it touches so overlapping mods
  are visible before they collide.
- WZ content goes into the wz-patcher pack (`tools/wz-patcher`), Custom.wz overlay first.
- Nothing ships without the user's OK; shipping follows the existing DLL/manifest routine
  (`client-wz-mapping-2026-10-14`, `p10-native-crew-window-2026-10-12`).

### P7 Maintenance
- Scheduled daily sync. A new reply in a thread whose mod is `ported` or `shipped` is triaged; a fix opens an update
  task for that mod (diff against the pinned `ported_from`). New release threads join the triage queue.
- A short report per sync: new candidates, updates available, failed fetches.

## Bot setup (the user does this; tokens never pass through Claude)
1. discord.com/developers: New Application, then Bot. Turn on **Message Content Intent** (privileged; without it the
   API returns messages with empty content). No other intents.
2. OAuth2 URL generator: scope `bot`, permissions **View Channels** and **Read Message History** only
   (permission integer 66560). Invite it to the server, with the admins' knowledge, and make sure it can see the
   releases channels.
3. Put the token in `FriendServer/mod-intake-data/.env` as `DISCORD_BOT_TOKEN=...`, and the server id as
   `DISCORD_GUILD_ID=...` (Developer Mode, right-click the server, Copy Server ID).

## Current client modules (kaentake `src/hook.h`, `feat/base-overlay`)
Core: ClientBypass, ClientInlink, StringPool, ResMan, WzMapLimit (from ResMan), version guard.
Toggleable: AvatarData, ItemEffect, Resolution, MobHpTag, ToolTip, FusionAnvil, ItemIcon, TempStat, QuickLogin,
RememberPassword, FreeAddy (damage/speed caps), BossHP (off: duplicates MobHpTag), ChronicleExtras, EnlargeQuickslot,
ShoulderSlots, IsCorrectBodyPart, GetBodyPartFromItem, ExtChainLightning, MapInfoToolTip, QuestListColor, CrewWindow
(already has `crew_window`, `crew_hotkey`).
Upstream iw2d/kaentake branches not in our fork (`custom-ui`, `development`, `split-map-wz`, `replacedStandAction`)
are catalogued as candidates too.

## Risks and open items
- **Licences and credit.** Many posts carry no licence. Each `mods.yaml` entry keeps author and source link, and the
  client gets a credits page. A mod whose licence forbids redistribution is marked and left out of shipped builds.
- **Server rules.** The bot reads only what it is invited to read; if the server's admins object, the data is deleted.
- **Untrusted content.** Attachments and fetched repos are data. Binaries are opened only by disassemblers; no fetched
  build script or exe is run.
- **Mod conflicts.** Same-address hooks and same-file server patches are flagged before porting; the user picks.
- **Throughput.** P6 is the slow part (one port is a real piece of work). Triage keeps the queue to v83 mods that
  apply; ports run one session per mod, a few at a time.

## Order
P1 and P4 are independent and can run in parallel. P5 follows P4 (feature ids). P2 then P3 follow P1. P6 needs P3,
P4 and P5. P7 last.

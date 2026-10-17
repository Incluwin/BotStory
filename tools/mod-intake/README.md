# BotStory mod intake

Reads the releases channels of a Discord server (M. Development C.) into a local SQLite store: every
thread, message, edit, attachment and link. Later phases sort that store into mods and port them into
BotStory's client and server; see `docs/MOD_INTAKE_PLAN.md`. This tool is phase P1.

Python 3.8 or newer, standard library only.

## Bot setup

Done once, by a person: the token never passes through Claude or this repo.

1. On discord.com/developers, create an application, then its Bot. Under Privileged Gateway Intents turn
   on **Message Content Intent**. Without it Discord sends every message with no text, and `sync`
   says so.
2. Under OAuth2 > URL Generator pick scope `bot` and the permissions **View Channels** and
   **Read Message History** (permission integer 66560), nothing else. Open the URL and add the bot to
   the server, with the admins' knowledge. It must be able to see the releases channels.
3. Create the data folder (default `FriendServer/mod-intake-data`, next to the server repo) with a
   `.env` file:

   ```
   DISCORD_BOT_TOKEN=<the bot's token>
   DISCORD_GUILD_ID=<server id: Developer Mode on, right-click the server, Copy Server ID>
   # optional:
   INTAKE_CHANNELS=<channel or category ids, comma separated>
   INTAKE_MAX_ATTACHMENT_MB=100
   ```

   Environment variables of the same names win over the file. `MOD_INTAKE_DATA` or `--data` picks
   another folder. The tool refuses a data folder inside any git repository.

## Use

```
python tools/mod-intake/mod_intake.py channels
python tools/mod-intake/mod_intake.py sync
python tools/mod-intake/mod_intake.py status
python tools/mod-intake/mod_intake.py search duey fix
python tools/mod-intake/mod_intake.py thread "Boss HP"
```

- `channels` lists the server's channels and marks with `*` the ones `sync` reads. Without
  `INTAKE_CHANNELS` those are text and forum channels whose own name or category name contains
  "release". A category id in `INTAKE_CHANNELS` means all its text and forum channels.
- `sync` reads the tracked channels' active and archived public threads, then only the messages newer
  than the last one stored for each thread or text channel. It also re-reads each thread's first post,
  because release posts get edited with new versions; the old text is kept as a revision.
  `--no-starters` skips that (one request per thread), `--no-attachments` skips downloads.
- `status`, `search` and `thread` read the local store only.

Run `sync` as often as you like; a sync with nothing new costs a few listing requests plus the
first-post re-reads. Rate limits are respected (429 `retry_after`, empty buckets).

## Data folder

```
mod-intake-data/
  .env
  intake.sqlite3          channels, threads, messages, message_revisions, attachments, links, sync_runs
  attachments/ab/abcd...  each attachment once, named by its sha256, no file extension
```

The store holds other people's messages: it stays on this PC and is never committed or uploaded.
Attachments are kept as data to read (diff, disassemble), never run; downloads go only to Discord's
CDN hosts. Signed CDN links expire, so `sync` downloads at once and, when a link has expired,
re-reads the message for a fresh one. Failed downloads are retried on the next sync, up to 5 tries.

Not covered: private threads, deleted messages (they stay in the store), and edits to messages
other than a thread's first post.

## Tests

```
python -m unittest discover -s tools/mod-intake/tests
```

The tests run against `tests/fake_discord.py`, an in-memory Discord API with the real paging rules,
fed by `tests/fixtures/guild.json` (a made-up server; no real Discord content is in this repo).

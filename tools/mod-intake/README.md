# BotStory mod intake

Reads the releases channels of a Discord server (M. Development C.) into a local SQLite store: every
thread, message, edit, attachment and link (phase P1, `sync`), then snapshots what those links point at
so triage and porting work from pinned local copies (phase P2, `fetch`). Later phases sort the store
into mods and port them into BotStory's client and server; see `docs/MOD_INTAKE_PLAN.md`.

Python 3.8 or newer, standard library only. `fetch` also needs the git command line.

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
python tools/mod-intake/mod_intake.py fetch --dry-run
python tools/mod-intake/mod_intake.py fetch
python tools/mod-intake/mod_intake.py status
python tools/mod-intake/mod_intake.py sources --status manual
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
- `fetch` snapshots every link that has no snapshot yet (see below). `--dry-run` says what it would
  clone and read without touching the network, `--limit N` handles only the first N links,
  `--delay S` sets the pause between requests to one host (default 1 s), `--retry-failed` retries
  links that already failed three times.
- `status`, `sources`, `search` and `thread` read the local store only. `sources` lists the
  snapshots (`--status stored|failed|manual|skipped`, `--kind github_pull`); `thread` shows each
  link's pinned commit or why it has none.

Run `sync` as often as you like; a sync with nothing new costs a few listing requests plus the
first-post re-reads. Rate limits are respected (429 `retry_after`, empty buckets).

## Fetch (P2)

Each link a message carries gets one row in `sources`: the same repository linked again in a later
message is pinned again, at that later state.

| Link | Snapshot |
| --- | --- |
| GitHub repo, issue | the default branch head at fetch time |
| commit | that commit (fetched by its sha when no branch holds it); base = its parent |
| pull request | `refs/pull/N/head` (or the `/commits/<sha>` it names); base = merge-base with the default branch |
| blob, tree, raw file | the branch, tag or commit in the URL (branch names with `/` are worked out), plus the path |
| compare `a...b` | head `b`, base `a`; a compare against another fork is noted, not fetched |
| release tag | the tag's commit. A release asset (`releases/download/...`) is `manual`: binaries are not downloaded |
| gist | its head, or the revision in the URL (gists are cloned whole) |
| GitLab, Bitbucket, Codeberg | the default branch head |
| pastebin, hastebin, paste.ee, ... | the raw text (`pastebin.com/raw/<id>` form), stored by sha256 |
| Discord file link | the attachment `sync` stored, if it is one |
| mega, mediafire, drive, dropbox, onedrive | `manual`: never requested; get the file by hand if it matters |
| Discord message link, other sites | `skipped`, with a note |

A failure (deleted or private repo, missing commit, 404, an HTML page instead of a paste) is recorded
with its reason and retried on the next two runs. A host that keeps rate limiting (git 429, HTTP 429
or a rate-limit 403) is waited out (60 s, 3 min, 10 min for git; `Retry-After` for pastes), then left
for the next run without using up the links' tries.

Repositories are partial clones (`--filter=blob:none --no-checkout`), one per repository, updated with
`git fetch` when a new link needs a newer state. Commits and trees are local; file contents come in
for what a link points at (the linked file, both sides of each file a commit, pull request or compare
changes, up to 300 files), so the change survives the repository being deleted. Other file contents
stay on the host; git loads them the first time something reads them. Each pinned commit gets a ref
(`refs/intake/pins/<sha>`) so no later fetch or gc can drop it, and auto gc is off in the mirrors.

What is fetched is data to read, never to run:

- Clones never get a work tree. git runs with an empty hooks and template folder
  (`sources/no-hooks`), no credential helper or password prompt (unauthenticated: a private or
  deleted repo is a failure, not a login window), https as the only transport
  (`GIT_ALLOW_PROTOCOL=https`, so a `url.insteadOf` rewrite to ssh fails rather than using your keys),
  and local lookups never fetch on demand (`GIT_NO_LAZY_FETCH=1`).
- Pastes are stored without a file extension. Redirects are followed only within the same host.
- No GitHub API calls: only git's own clone and fetch, paced one second apart per host.
- Nothing in the data folder is built, run or imported by this tool or by later phases.

Code blocks in messages (```` ``` ```` fences) go to the `code_blocks` table on every `sync`: language
from the fence (aliases like `c++`, `js` normalised), else a simple guess (diff, xml, sql, Cosmic
NPC-script javascript, java, cpp, asm, python, json, ini, text). Opening a P1 store fills the table
for the messages already in it.

Not covered: release assets and download hosts (by hand), re-pinning a link whose message was edited
but kept the same URL, and private gists.

## Data folder

```
mod-intake-data/
  .env
  intake.sqlite3          channels, threads, messages, message_revisions, attachments, links, sync_runs,
                          sources, code_blocks
  attachments/ab/abcd...  each attachment once, named by its sha256, no file extension
  sources/github/<owner>/<repo>/       partial clones, no work tree (lower-case names)
  sources/git/<host>/<owner>/<repo>/   GitLab, Bitbucket, Codeberg
  sources/gist/<id>/
  sources/files/ab/abcd...             paste text by sha256, no file extension
  sources/no-hooks/                    empty on purpose: git's hooks and template folder
```

Repositories are kept forever (pins are refs); a big server fork can take tens of MB even without
file contents. `fetch --dry-run` lists the clones a run would make.

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
The fetch tests (`tests/test_fetch.py`) clone local bare repositories built in a temp folder
(`tests/git_fixture.py`) with git limited to the file transport, and read pastes from a fake web, so
they need no network.

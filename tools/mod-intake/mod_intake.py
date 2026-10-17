"""BotStory mod intake: read a Discord server's releases channels into a local store, snapshot what they link.

    python tools/mod-intake/mod_intake.py channels          list the server's channels, * = would be tracked
    python tools/mod-intake/mod_intake.py sync              fetch new threads, messages, edits, attachments
    python tools/mod-intake/mod_intake.py fetch             snapshot what new links point at (git, pastes)
    python tools/mod-intake/mod_intake.py status            what the store holds
    python tools/mod-intake/mod_intake.py sources           list link snapshots (--status failed|manual|...)
    python tools/mod-intake/mod_intake.py search <words>    full-text search over messages and file names
    python tools/mod-intake/mod_intake.py thread <id|name>  print one thread

Settings live in <data dir>/.env; see README.md.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from intake import config as configlib  # noqa: E402
from intake.api import DiscordClient, DiscordError, Unauthorized  # noqa: E402
from intake.fetch import run_fetch  # noqa: E402
from intake.store import Store  # noqa: E402
from intake.sync import IntakeError, run_sync, select_tracked  # noqa: E402

CHANNEL_TYPES = {0: "text", 2: "voice", 4: "category", 5: "announcement", 13: "stage", 15: "forum", 16: "media"}
SOURCE_STATUSES = ("stored", "failed", "manual", "skipped")
MAX_FAILURES_SHOWN = 20


def main(argv=None, transport=None, sleep=None, page_size=100, **fetch_options):
    """transport: the Discord API for sync/channels, the web for fetch. fetch_options go to run_fetch (tests)."""
    parser = argparse.ArgumentParser(prog="mod_intake.py", description=__doc__.split("\n")[0])
    parser.add_argument("--data", help="data folder (default: $MOD_INTAKE_DATA or FriendServer/mod-intake-data)")
    sub = parser.add_subparsers(dest="command", required=True)
    sync_p = sub.add_parser("sync", help="fetch new threads, messages, edits and attachments")
    sync_p.add_argument("--no-starters", action="store_true", help="skip re-reading each thread's first post")
    sync_p.add_argument("--no-attachments", action="store_true", help="do not download attachments this time")
    fetch_p = sub.add_parser("fetch", help="snapshot what new links point at: git mirrors, raw pastes")
    fetch_p.add_argument("--limit", type=int, help="handle at most this many links this run")
    fetch_p.add_argument("--retry-failed", action="store_true", help="retry failed links even after 3 tries")
    fetch_p.add_argument("--delay", type=float, default=1.0, help="seconds between requests to one host (1)")
    fetch_p.add_argument("--dry-run", action="store_true", help="say what would be fetched; no network")
    sub.add_parser("channels", help="list the server's channels; * marks the ones sync would read")
    sub.add_parser("status", help="summarise the store")
    sources_p = sub.add_parser("sources", help="list link snapshots")
    sources_p.add_argument("--status", choices=SOURCE_STATUSES)
    sources_p.add_argument("--kind", help="a link kind, e.g. github_pull")
    search_p = sub.add_parser("search", help="full-text search")
    search_p.add_argument("words", nargs="+")
    search_p.add_argument("--limit", type=int, default=20)
    thread_p = sub.add_parser("thread", help="print a thread by id or name fragment")
    thread_p.add_argument("key", nargs="+")
    args = parser.parse_args(argv)

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")  # Discord text has emoji; a cp1252 console must not crash
    try:
        cfg = configlib.load(args.data)
        if args.command in ("sync", "channels"):
            cfg.require_discord()
            client = DiscordClient(cfg.token, transport=transport, **({"sleep": sleep} if sleep else {}))
            if args.command == "channels":
                return cmd_channels(client, cfg)
            return cmd_sync(client, cfg, args, page_size)
        if not cfg.db_path.exists():
            print(f"No data yet in {cfg.data_dir}: run 'sync' first.")
            return 1
        store = Store(cfg.db_path)
        try:
            if args.command == "fetch":
                return cmd_fetch(store, cfg, args, transport, sleep, fetch_options)
            if args.command == "status":
                return cmd_status(store, cfg)
            if args.command == "sources":
                return cmd_sources(store, args.status, args.kind)
            if args.command == "search":
                return cmd_search(store, " ".join(args.words), args.limit)
            return cmd_thread(store, " ".join(args.key))
        finally:
            store.close()
    except Unauthorized:
        print("Discord refused the bot token (401). Check DISCORD_BOT_TOKEN in the data folder's .env.",
              file=sys.stderr)
        return 2
    except (IntakeError, DiscordError) as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1


def cmd_sync(client, cfg, args, page_size):
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    store = Store(cfg.db_path)
    try:
        stats = run_sync(client, store, cfg.guild_id, str(cfg.attachments_dir), cfg.channel_ids, page_size=page_size,
                         refresh_starters=not args.no_starters, fetch_attachments=not args.no_attachments,
                         max_attachment_bytes=cfg.max_attachment_bytes)
    finally:
        store.close()
    print(f"Synced {stats.threads} threads ({stats.new_threads} new): {stats.new_messages} new messages, "
          f"{stats.edited_messages} edited; attachments {stats.stored_attachments} stored, "
          f"{stats.failed_attachments} failed, {stats.skipped_attachments} too large; {client.requests} requests.")
    return 0


def cmd_fetch(store, cfg, args, transport, sleep, options):
    if sleep:
        options = dict(options, sleep=sleep)
    stats = run_fetch(store, cfg.data_dir, transport=transport, limit=args.limit, retry_failed=args.retry_failed,
                      delay=args.delay, dry_run=args.dry_run, **options)
    if stats.dry_run:
        return 0
    print(f"Fetched {stats.links} links: {stats.stored} stored, {stats.failed} failed, {stats.manual} manual, "
          f"{stats.skipped} skipped" + (f", {stats.deferred} left for the next run" if stats.deferred else "")
          + f"; {stats.clones} clones, {stats.updates} updates, {stats.git_requests} git and "
            f"{stats.http_requests} paste requests.")
    for url, error in stats.failures[:MAX_FAILURES_SHOWN]:
        print(f"  failed  {url}\n          {error}")
    if len(stats.failures) > MAX_FAILURES_SHOWN:
        print(f"  ... {len(stats.failures) - MAX_FAILURES_SHOWN} more: see 'sources --status failed'")
    return 0


def cmd_sources(store, status, kind):
    rows = store.sources(status, kind)
    for r in rows:
        pin = (r["pinned_sha"] or "")[:12]
        print(f"{r['status']:<8} {r['kind']:<16} {pin:<12} {r['url']}")
        detail = r["error"] or r["note"]
        if detail:
            print(f"{'':<38}{detail}")
    if not rows:
        print("No sources" + (" with that status or kind." if status or kind else " yet: run 'fetch'."))
    return 0


def cmd_channels(client, cfg):
    channels = client.guild_channels(cfg.guild_id)
    tracked = select_tracked(channels, cfg.channel_ids)
    by_parent = {}
    for c in channels:
        by_parent.setdefault(c.get("parent_id"), []).append(c)
    ordered = sorted(by_parent.get(None, []), key=lambda c: (c["type"] == 4, c.get("position", 0)))
    for top in ordered:
        print(_channel_line(top, tracked, ""))
        for child in sorted(by_parent.get(top["id"], []), key=lambda c: c.get("position", 0)):
            print(_channel_line(child, tracked, "    "))
    source = "INTAKE_CHANNELS" if cfg.channel_ids else "names containing 'release' (set INTAKE_CHANNELS to choose)"
    print(f"\n* = read by sync ({len(tracked)} channels, from {source})")
    return 0


def _channel_line(c, tracked, indent):
    mark = "*" if int(c["id"]) in tracked else " "
    kind = CHANNEL_TYPES.get(c["type"], f"type {c['type']}")
    return f"{mark} {indent}{c.get('name')}  [{kind}]  {c['id']}"


def cmd_status(store, cfg):
    s = store.status()
    print(f"Data: {cfg.data_dir}")
    print("Tracked channels:")
    for c in s["tracked_channels"]:
        print(f"  #{c['name']}  [{CHANNEL_TYPES.get(c['type'], c['type'])}]  access={c['access'] or '-'}")
    total, archived = s["threads"]
    print(f"Threads: {total} ({archived} archived)")
    print(f"Messages: {s['messages']} (+{s['revisions']} earlier versions of edited posts)")
    print("Attachments: " + (", ".join(f"{n} {st} ({_size(b)})" for st, n, b in s["attachments"]) or "none"))
    print("Links: " + (", ".join(f"{kind} {n}" for kind, n in s["links"]) or "none"))
    waiting = f"{s['unfetched_links']} links not fetched yet (run 'fetch')" if s["unfetched_links"] else ""
    print("Sources: " + (", ".join(x for x in (", ".join(f"{n} {st}" for st, n in s["sources"]), waiting) if x)
                         or "none"))
    blocks, in_messages = s["code_blocks"]
    print(f"Code blocks: {blocks} in {in_messages} messages")
    if s["last_fetch"]:
        f = json.loads(s["last_fetch"])
        print(f"Last fetch: {f['at']} -> {f['links']} links, {f['stored']} stored, {f['failed']} failed"
              + (f", {f['deferred']} left for the next run" if f.get("deferred") else ""))
    run = s["last_run"]
    if run:
        print(f"Last sync: {run['started_at']} -> {run['status']}, {run['requests']} requests, "
              f"{run['new_messages']} new / {run['edited_messages']} edited messages")
        for line in (run["notes"] or "").splitlines():
            print(f"  note: {line}")
    return 0


def cmd_search(store, text, limit):
    hits, threads = store.search(text, limit)
    for t in threads:
        print(f"thread {t['id']}  {t['name']}")
    for h in hits:
        print(f"{h['created_at'][:10]}  {h['place']}  ({h['author_name']}, message {h['id']})")
        print("    " + " ".join(h["snippet"].split()))
    if not hits and not threads:
        print("No matches.")
    return 0


def cmd_thread(store, key):
    matches = store.find_conversations(key)
    if not matches:
        print("No thread or tracked channel matches.")
        return 1
    if len(matches) > 1:
        print("Several match; use an id:")
        for m in matches:
            print(f"  {m['id']}  {m['name']}")
        return 1
    conv = matches[0]
    print(f"== {conv['name']}  ({conv['id']})")
    for m, attachments, links, revisions in store.conversation(conv["id"]):
        edited = f", edited {m['edited_at'][:10]} ({revisions} earlier version(s) kept)" if m["edited_at"] else ""
        print(f"\n-- {m['author_name']}  {m['created_at'][:16]}{edited}  [{m['id']}]")
        print(m["content"])
        for a in attachments:
            where = f"sha256 {a['sha256'][:12]}" if a["sha256"] else a["status"]
            print(f"   [file] {a['filename']}  {_size(a['size'] or 0)}  {where}")
        for link in links:
            snapshot = ""
            if link["status"] == "stored":
                snapshot = f"  -> {link['pinned_sha'][:12]}" if link["pinned_sha"] else "  -> stored"
            elif link["status"]:
                snapshot = f"  -> {link['status']}: {link['error'] or link['note']}"
            print(f"   [{link['kind']}] {link['url']}{snapshot}")
    return 0


def _size(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


if __name__ == "__main__":
    sys.exit(main())

"""BotStory mod intake: read a Discord server's releases channels into a local store, snapshot what they link.

    python tools/mod-intake/mod_intake.py channels          list the server's channels, * = would be tracked
    python tools/mod-intake/mod_intake.py sync              fetch new threads, messages, edits, attachments
    python tools/mod-intake/mod_intake.py forks             read GitHub forks with commits of their own
    python tools/mod-intake/mod_intake.py inbox             import Discord text pasted into <data>/inbox/*.txt
    python tools/mod-intake/mod_intake.py fetch             snapshot what new links point at (git, pastes)
    python tools/mod-intake/mod_intake.py status            what the store holds
    python tools/mod-intake/mod_intake.py sources           list link snapshots (--status failed|manual|...)
    python tools/mod-intake/mod_intake.py search <words>    full-text search over messages and file names
    python tools/mod-intake/mod_intake.py thread <id|name>  print one thread
    python tools/mod-intake/mod_intake.py inventory         refresh catalog/inventory.yaml from kaentake
    python tools/mod-intake/mod_intake.py triage --dry-run  dossiers + rule triage + what Jev would cost
    python tools/mod-intake/mod_intake.py triage            judge with Jev, write catalog/mods.yaml + the board
    python tools/mod-intake/mod_intake.py dossier <id|name> print one thread's dossier
    python tools/mod-intake/mod_intake.py board --serve     browse and correct the catalog (127.0.0.1)
    python tools/mod-intake/mod_intake.py overrides ...     list, set or import corrections

Settings live in <data dir>/.env; see README.md.
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from intake import board as boardlib  # noqa: E402
from intake import config as configlib  # noqa: E402
from intake import dossier as dossierlib  # noqa: E402
from intake import forks as forklib  # noqa: E402
from intake import inbox as inboxlib  # noqa: E402
from intake import inventory as invlib  # noqa: E402
from intake import jev as jevlib  # noqa: E402
from intake import triage as triagelib  # noqa: E402
from intake import yamlish  # noqa: E402
from intake.api import DiscordClient, DiscordError, Unauthorized  # noqa: E402
from intake.fetch import run_fetch  # noqa: E402
from intake.store import Store  # noqa: E402
from intake.sync import IntakeError, run_sync, select_tracked  # noqa: E402

TOOL_DIR = Path(__file__).resolve().parent
CATALOG_DIR = TOOL_DIR / "catalog"
SERVER_REPO = TOOL_DIR.parents[1]  # tools/mod-intake -> the server repo (a worktree works too)

CHANNEL_TYPES = {0: "text", 2: "voice", 4: "category", 5: "announcement", 13: "stage", 15: "forum", 16: "media",
                 1001: "github forks", 1002: "inbox"}
SOURCE_STATUSES = ("stored", "failed", "manual", "skipped")
MAX_FAILURES_SHOWN = 20


def main(argv=None, transport=None, sleep=None, page_size=100, jev_transport=None, environ=None, serve=None,
         github_transport=None, **fetch_options):
    """transport: the Discord API for sync/channels, the web for fetch; jev_transport: the TypeSafe API for triage;
    github_transport: the GitHub API for forks; environ: replaces os.environ for the API key and GitHub token lookups;
    serve: called with the board server instead of serve_forever. fetch_options go to run_fetch; their git_env also
    reaches triage and board (tests)."""
    parser = argparse.ArgumentParser(prog="mod_intake.py", description=__doc__.split("\n")[0])
    parser.add_argument("--data", help="data folder (default: $MOD_INTAKE_DATA or FriendServer/mod-intake-data)")
    parser.add_argument("--catalog", help=f"catalog folder (default: {CATALOG_DIR})")
    sub = parser.add_subparsers(dest="command", required=True)
    sync_p = sub.add_parser("sync", help="fetch new threads, messages, edits and attachments")
    sync_p.add_argument("--no-starters", action="store_true", help="skip re-reading each thread's first post")
    sync_p.add_argument("--no-attachments", action="store_true", help="do not download attachments this time")
    forks_p = sub.add_parser("forks", help="read GitHub forks whose branches have commits of their own")
    forks_p.add_argument("--upstream", action="append",
                         help=f"owner/repo whose forks to read; repeatable (default {', '.join(forklib.UPSTREAMS)})")
    forks_p.add_argument("--limit", type=int, help="compare at most this many fork branches this run")
    forks_p.add_argument("--max-depth", type=int, default=forklib.MAX_DEPTH,
                         help=f"forks of forks to follow (default {forklib.MAX_DEPTH})")
    sub.add_parser("inbox", help="import Discord text you pasted into <data dir>/inbox/*.txt")
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
    inv_p = sub.add_parser("inventory", help="refresh catalog/inventory.yaml's client part from kaentake")
    inv_p.add_argument("--kaentake", help="kaentake checkout (default: kaentake next to the server repo)")
    inv_p.add_argument("--ref", default="feat/feature-toggles",
                       help="branch or commit to read (default feat/feature-toggles); '' = the work tree")
    triage_p = sub.add_parser("triage", help="dossiers, rule tags and Jev judgments -> catalog/mods.yaml")
    triage_p.add_argument("--dry-run", action="store_true",
                          help="write dossiers and print the triage and Jev estimate; no API calls, no catalog")
    triage_p.add_argument("--no-jev", action="store_true", help="use cached judgments only; never call the API")
    triage_p.add_argument("--limit", type=int, help="judge at most this many threads this run")
    triage_p.add_argument("--model", help=f"Jev model (default $TYPESAFE_MODEL or {jevlib.DEFAULT_MODEL})")
    triage_p.add_argument("--all", action="store_true", help="list every thread, not only the first 60")
    dossier_p = sub.add_parser("dossier", help="print one thread's dossier (from the last triage)")
    dossier_p.add_argument("key", nargs="+")
    dossier_p.add_argument("--json", action="store_true", help="the whole dossier as JSON")
    board_p = sub.add_parser("board", help="write the HTML board (cached judgments only); --serve to correct")
    board_p.add_argument("--serve", action="store_true", help="serve it on 127.0.0.1 and save corrections")
    board_p.add_argument("--port", type=int, default=boardlib.DEFAULT_PORT, help=f"(default {boardlib.DEFAULT_PORT})")
    ov_p = sub.add_parser("overrides", help="corrections that survive re-runs (catalog/overrides.yaml)")
    ov_sub = ov_p.add_subparsers(dest="action", required=True)
    ov_sub.add_parser("list", help="print the corrections")
    ov_set = ov_sub.add_parser("set", help="correct one thread: overrides set <thread> status=porting ...")
    ov_set.add_argument("thread")
    ov_set.add_argument("fields", nargs="+", help="field=value (an empty value removes the field)")
    ov_imp = ov_sub.add_parser("import", help="merge a board export (board-corrections.json)")
    ov_imp.add_argument("file")
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
        paths = triagelib.Paths(cfg.data_dir, Path(args.catalog) if args.catalog else CATALOG_DIR)
        if args.command == "inventory":
            return cmd_inventory(paths, args)
        if args.command == "overrides":
            return cmd_overrides(paths, args)
        if args.command in ("forks", "inbox"):
            cfg.data_dir.mkdir(parents=True, exist_ok=True)
            store = Store(cfg.db_path)
            try:
                if args.command == "inbox":
                    return cmd_inbox(store, cfg)
                return cmd_forks(store, cfg, args, os.environ if environ is None else environ, github_transport,
                                 sleep)
            finally:
                store.close()
        if not cfg.db_path.exists():
            print(f"No data yet in {cfg.data_dir}: run 'sync', 'forks' or 'inbox' first.")
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
            if args.command == "triage":
                return cmd_triage(store, cfg, paths, args, jev_transport, environ, sleep,
                                  fetch_options.get("git_env"))
            if args.command == "board":
                return cmd_board(store, paths, args, fetch_options.get("git_env"), serve)
            if args.command == "dossier":
                return cmd_dossier(paths, " ".join(args.key), args.json)
            return cmd_thread(store, " ".join(args.key))
        finally:
            store.close()
    except jevlib.JevAuthError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 2
    except (jevlib.JevError, invlib.InventoryError, yamlish.YamlError) as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except Unauthorized:
        print("Discord refused the bot token (401). Check DISCORD_BOT_TOKEN in the data folder's .env.",
              file=sys.stderr)
        return 2
    except (IntakeError, DiscordError, forklib.GitHubError) as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1


def cmd_forks(store, cfg, args, environ, transport, sleep):
    stats = forklib.run_forks(store, cfg.attachments_dir, environ, upstreams=args.upstream or forklib.UPSTREAMS,
                              transport=transport, limit=args.limit, max_depth=args.max_depth,
                              **({"sleep": sleep} if sleep else {}))
    print(f"Read {stats.forks} forks: {stats.never_pushed} never pushed to, {stats.unchanged} unchanged since the last "
          f"run, {stats.skipped_owner} our own; compared {stats.compares} branches, {stats.no_own_commits} without "
          f"work of their own; {stats.threads} fork threads saved ({stats.new_threads} new)"
          + (f", {stats.dropped} dropped (branch gone or merged)" if stats.dropped else "")
          + f"; {stats.requests} GitHub requests.")
    if stats.stopped:
        print(f"Not finished: {stats.stopped}")
    return 0


def cmd_inbox(store, cfg):
    folder = cfg.data_dir / "inbox"
    stats = inboxlib.run_inbox(store, folder)
    print(f"Inbox {folder}: {stats.files} files, {stats.imported} imported, {stats.unchanged} unchanged, "
          f"{stats.skipped} skipped, {stats.removed} removed (file deleted).")
    return 0


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
    forks, fork_branches, inbox_files = store.fork_counts()
    print(f"Other sources: {forks} GitHub forks read, {fork_branches} fork branches with work of their own; "
          f"{inbox_files} inbox files")
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


def cmd_inventory(paths, args):
    repo = Path(args.kaentake) if args.kaentake else default_kaentake()
    source = invlib.KaentakeSource(repo, args.ref or None)
    data = invlib.regenerate(paths.inventory, source)
    print(f"{paths.inventory}: {len(data['client'])} client modules from kaentake {source.ref or 'work tree'} "
          f"({source.commit[:12]}), {len(data['server'])} server entries kept.")
    return 0


def default_kaentake():
    """kaentake next to the main server checkout (also from a worktree of it)."""
    p = subprocess.run(["git", "-C", str(SERVER_REPO), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    common = Path(p.stdout.decode("utf-8", "replace").strip()) if p.returncode == 0 else SERVER_REPO / ".git"
    return common.parent.parent / "kaentake"


def _server_repo():
    return SERVER_REPO if (SERVER_REPO / ".git").exists() else None


def cmd_triage(store, cfg, paths, args, jev_transport, environ, sleep, git_env):
    environ = os.environ if environ is None else environ
    model = args.model or environ.get("TYPESAFE_MODEL") or jevlib.DEFAULT_MODEL
    jev = None
    ask = not (args.dry_run or args.no_jev)
    if ask:
        key = jevlib.load_key(environ, [cfg.data_dir / ".env", cfg.data_dir.parent / ".env"])
        if not key:
            print("TYPESAFE_API_KEY not found (environment, the data folder's .env, or the .env next to it). "
                  "Use --dry-run or --no-jev to triage on rules and cached judgments only.", file=sys.stderr)
            return 2
        jev = jevlib.JevClient(key, transport=jev_transport, model=model, **({"sleep": sleep} if sleep else {}))
    out = triagelib.run(store, paths, jev=jev, ask=ask, model=model, write=not args.dry_run, limit=args.limit,
                        server_repo=_server_repo(), git_env=git_env)
    print_triage(out, args.all)
    for note in out.notes:
        print(f"note: {note}")
    est = triagelib.estimate(out.pending, model)
    cached = len(out.requests) - len(out.pending)
    if args.dry_run:
        print(f"\nDry run: {len(out.dossiers)} dossiers written to {paths.dossiers}.")
        print(f"Jev ({model}): {est['requests']} requests to send for {est['threads']} threads, "
              f"~{est['tokens']:,} input tokens, ~${est['usd']:.4f} at ${jevlib.PRICE_PER_MTOK}/Mtok; "
              f"{cached} already cached.")
        print("Nothing sent and no catalog written. 'triage' judges and writes catalog/mods.yaml.")
        return 0
    if ask:
        print(f"\nJudged {out.asked} requests ({out.tokens:,} input tokens, ~${jevlib.cost_usd(out.tokens):.4f}); "
              f"{cached - out.asked} were cached; {len(out.failed)} failed"
              + (f"; {est['requests']} left for the next run" if est["requests"] else "") + ".")
    else:
        print(f"\nCached judgments only: {cached} of {len(out.requests)} requests have answers.")
    path = boardlib.write(out, paths)
    print(f"Wrote {paths.mods} ({len(out.entries)} entries) and the board {path}.")
    return 0


def print_triage(out, show_all):
    rows = sorted(out.entries, key=lambda e: triagelib._unit_sort_key(e["thread"]))
    for e in rows if show_all else rows[:60]:
        flag = "?" if e["review"] else " "
        print(f"{flag} {e['status']:<9} {e['kind'] or '-':<13} {e['version'] or '-':<7} {e['family'] or '-':<14} "
              f"{e['applies'] or '-':<12} {e['id']:<34} {e['title'][:60]}")
        if e.get("reason"):
            print(f"{'':<12}{e['reason']}")
    if not show_all and len(rows) > 60:
        print(f"... {len(rows) - 60} more (--all)")
    counts = triagelib.summary(out.entries, None)
    print(f"{counts['entries']} threads: " + ", ".join(f"{n} {s}" for s, n in counts["status"].items())
          + f"; {counts['review']} need review (?), {counts['judged']} judged by Jev.")


def cmd_board(store, paths, args, git_env, serve):
    out = triagelib.run(store, paths, ask=False, write=True, server_repo=_server_repo(), git_env=git_env)
    path = boardlib.write(out, paths)
    if not args.serve:
        print(f"Board: {path} (open it in a browser; 'board --serve' saves corrections directly).")
        return 0
    try:
        server = boardlib.BoardServer(out, paths, port=args.port)
    except OSError as e:
        print(f"Error: cannot listen on 127.0.0.1:{args.port} ({e.strerror or e}); pick another with --port.",
              file=sys.stderr)
        return 1
    print(f"Board: {server.url}  (127.0.0.1 only; Ctrl+C stops it). Corrections go to {paths.overrides}.")
    try:
        if serve:
            serve(server)
        else:
            server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.httpd.server_close()
    return 0


def cmd_dossier(paths, key, as_json):
    dossiers = dossierlib.load_all(paths.dossiers)
    if not dossiers:
        print("No dossiers yet: run 'triage' (or 'triage --dry-run') first.")
        return 1
    hits = [d for d in dossiers if d["id"] == key] or [d for d in dossiers if key.lower() in d["title"].lower()]
    if len(hits) != 1:
        print("No dossier matches." if not hits else "Several match; use an id:")
        for d in hits:
            print(f"  {d['id']}  {d['title']}")
        return 1
    d = hits[0]
    if as_json:
        print(json.dumps(d, ensure_ascii=False, indent=1))
        return 0
    s = d["stats"]
    print(f"== {d['title']}  ({d['id']}, {d['unit']} in #{d['channel']['name']})")
    print(f"by {d['author']['name']} on {(d['created_at'] or '')[:10]}; {s['replies']} replies, {s['links']} links, "
          f"{s['attachments']} attachments, {s['code_blocks']} code blocks, {s['edits']} earlier versions")
    h = d["hints"]
    versions = ", ".join(f"{v} ({w})" for v, w in h["versions"]) or "-"
    families = ", ".join(f"{f} ({w})" for f, w in h["families"]) or "-"
    print(f"rules: kinds {', '.join(h['kinds']) or '-'}; versions {versions}; families {families}")
    for k, v in d["evidence"]["release"].items():
        if v:
            print(f"  {k}: {v}")
    for m in ([d["starter"]] if d["starter"] else []) + d["replies"]:
        print(f"\n-- {m['author']}  {(m['created_at'] or '')[:16]}  [{m['id']}]")
        print(m["text"])
        for link in m["links"]:
            snap = link["snapshot"] or {}
            pin = " " + snap["pinned_sha"][:12] if snap.get("pinned_sha") else ""
            files = f", {link['files']['count']} files ({link['files']['mode']})" if link.get("files") else ""
            print(f"   [{link['kind']}] {link['url']} -> {snap.get('status', 'not fetched')}{pin}{files}")
        for a in m["attachments"]:
            print(f"   [file] {a['filename']}  {a['type']}")
    return 0


def cmd_overrides(paths, args):
    if args.action == "list":
        data = triagelib.load_overrides(paths.overrides)
        if not data:
            print(f"No corrections in {paths.overrides}.")
        for unit_id, fields in data.items():
            print(f"{unit_id}: " + ", ".join(f"{k}={v}" for k, v in fields.items()))
        return 0
    if args.action == "import":
        try:
            n = boardlib.import_corrections(paths, args.file)
        except (OSError, ValueError) as e:
            print(f"Error: {e}", file=sys.stderr)
            return 1
        print(f"Imported corrections for {n} threads into {paths.overrides}. "
              f"Run 'triage --no-jev' or 'board' to rebuild mods.yaml.")
        return 0
    fields = {}
    for item in args.fields:
        if "=" not in item:
            print(f"Error: expected field=value, got {item!r}", file=sys.stderr)
            return 1
        key, value = item.split("=", 1)
        if key.strip() == "review":
            value = {"true": True, "false": False, "": None}.get(value.lower(), value)
        fields[key.strip()] = value
    try:
        current = triagelib.save_override(paths.overrides, args.thread, fields)
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    print(f"{args.thread}: " + (", ".join(f"{k}={v}" for k, v in current.items()) or "no corrections left")
          + ". Run 'triage --no-jev' or 'board' to rebuild mods.yaml.")
    return 0


def _size(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


if __name__ == "__main__":
    sys.exit(main())

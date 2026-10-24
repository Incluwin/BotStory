"""Distil crawled guide threads into claims with the headless Claude CLI.

Reads  <corpus>/threads/<source>/<id>.json  (from crawl.py)
Writes <corpus>/claims/<source>/<id>.json   thread header + one model answer per chunk (see claims-schema.json)

Needs a logged-in CLI (`claude auth login`). The output stays in the corpus folder; import it into the server with
tools/guide-import.bat, which checks every claim against the server's own data.

Usage: py -3 -I distill.py --corpus <dir> [--source royals] [--model sonnet] [--jobs 3] [--limit N]
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT = os.path.join(HERE, "distill-prompt.md")
SCHEMA = os.path.join(HERE, "claims-schema.json")
DEFAULT_CLAUDE = os.path.join(os.path.expanduser("~"), ".local", "bin", "claude.exe")

MIN_CHARS = 600
CHUNK_CHARS = 30000
MAX_CHUNKS = 6
SKIP_TITLE = re.compile(r"read before you post|forum rules|^\W*(?:wts|wtb|selling|buying|s>|b>)\b|price check|giveaway"
                        r"|recruit|application|ban appeal", re.I)


class Stop(Exception):
    pass


def chunks_of(thread):
    text = "\n\n---\n\n".join(p["text"] for p in thread["posts"] if p.get("is_op") and p["text"].strip())
    if len(text) <= CHUNK_CHARS * 1.2:
        return [text]
    out, cur = [], []
    size = 0
    for para in text.split("\n\n"):
        if size + len(para) > CHUNK_CHARS and cur:
            out.append("\n\n".join(cur))
            cur, size = [], 0
        cur.append(para[:CHUNK_CHARS])
        size += len(para) + 2
    if cur:
        out.append("\n\n".join(cur))
    return out[:MAX_CHUNKS]


def prefilter(thread):
    op = sum(len(p["text"]) for p in thread["posts"] if p.get("is_op"))
    if op < MIN_CHARS:
        return f"opening posts only {op} chars"
    if SKIP_TITLE.search(thread["title"]):
        return "title is forum housekeeping or trade"
    return None


def child_env():
    # A CLI started from inside a Claude Code session inherits its CLAUDE_CODE_* switches (CLAUDE_CODE_SIMPLE forces
    # API-key auth); the batch child gets the plain user environment instead.
    return {k: v for k, v in os.environ.items() if not k.startswith(("CLAUDE", "ANTHROPIC"))}


def ask(args, system, schema, text, workdir):
    cmd = [args.claude, "-p", "--model", args.model, "--system-prompt", system, "--tools", "", "--strict-mcp-config",
           "--setting-sources", "", "--disable-slash-commands", "--no-session-persistence",
           "--output-format", "json", "--json-schema", schema]
    for attempt in range(5):
        proc = subprocess.run(cmd, input=text, capture_output=True, text=True, encoding="utf-8", cwd=workdir,
                              env=child_env(), timeout=900)
        try:
            result = json.loads(proc.stdout)
        except json.JSONDecodeError:
            result = {"is_error": True, "result": (proc.stdout + proc.stderr)[-400:]}
        if not result.get("is_error") and result.get("structured_output") is not None:
            return result
        message = str(result.get("result") or "")
        if "Not logged in" in message or "/login" in message:
            raise Stop("the claude CLI is not logged in; run: claude auth login")
        if re.search(r"limit|overloaded|rate", message, re.I):
            wait = 600 * (attempt + 1)
            print(f"  model busy or at a usage limit ({message[:80]}); waiting {wait // 60} min", flush=True)
            time.sleep(wait)
            continue
        time.sleep(20)
    raise RuntimeError(f"no usable answer: {message[:200]}")


def distil(args, system, schema, path, out_path, workdir, lock, totals):
    with open(path, encoding="utf-8") as f:
        thread = json.load(f)
    header = {k: thread.get(k) for k in ("source", "id", "url", "forum", "title", "starter", "started", "pages")}
    reason = prefilter(thread)
    chunks = []
    if reason is None:
        parts = chunks_of(thread)
        for i, part in enumerate(parts):
            text = (f"source: {thread['source']}\nforum: {thread['forum']}\ntitle: {thread['title']}\n"
                    f"author: {thread['starter']}\ndate: {thread['started']}\n"
                    f"part: {i + 1} of {len(parts)}\n\n{part}")
            result = ask(args, system, schema, text, workdir)
            usage = result.get("usage", {})
            chunks.append(dict(result["structured_output"], usage={
                "input": usage.get("input_tokens", 0) + usage.get("cache_read_input_tokens", 0)
                + usage.get("cache_creation_input_tokens", 0), "output": usage.get("output_tokens", 0),
                "cost_usd": result.get("total_cost_usd", 0)}))
    record = dict(header, prefilter=reason, model=args.model, chunks=chunks)
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(record, f, ensure_ascii=False, indent=1)
    os.replace(tmp, out_path)
    claims = sum(len(c["claims"]) for c in chunks)
    with lock:
        totals["threads"] += 1
        totals["claims"] += claims
        totals["cost"] += sum(c["usage"]["cost_usd"] for c in chunks)
        print(f"{totals['threads']:5d} {header['source']} {header['id']}: {claims:2d} claims"
              f"{' (' + reason + ')' if reason else ''} {header['title'][:60]}  [cost so far ${totals['cost']:.2f}]",
              flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--source", action="append")
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--claude", default=DEFAULT_CLAUDE)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    with open(PROMPT, encoding="utf-8") as f:
        system = f.read()
    with open(SCHEMA, encoding="utf-8") as f:
        schema = json.dumps(json.load(f), separators=(",", ":"))
    todo = []
    for source in args.source or sorted(os.listdir(os.path.join(args.corpus, "threads"))):
        tdir = os.path.join(args.corpus, "threads", source)
        cdir = os.path.join(args.corpus, "claims", source)
        os.makedirs(cdir, exist_ok=True)
        for name in sorted(os.listdir(tdir), key=lambda n: int(n.split(".")[0]) if n.split(".")[0].isdigit() else 0):
            if name.endswith(".json") and not os.path.exists(os.path.join(cdir, name)):
                todo.append((os.path.join(tdir, name), os.path.join(cdir, name)))
    if args.limit:
        todo = todo[:args.limit]
    print(f"{len(todo)} threads to distil with {args.model}, {args.jobs} at a time", flush=True)
    lock, totals = threading.Lock(), {"threads": 0, "claims": 0, "cost": 0.0}
    workdir = tempfile.mkdtemp(prefix="guide-distill-")  # an empty cwd: no CLAUDE.md or project settings
    with cf.ThreadPoolExecutor(args.jobs) as pool:
        futures = {pool.submit(distil, args, system, schema, src, dst, workdir, lock, totals): src for src, dst in todo}
        for future in cf.as_completed(futures):
            try:
                future.result()
            except Stop as stop:
                print("stopping:", stop, flush=True)
                pool.shutdown(wait=False, cancel_futures=True)
                return 2
            except Exception as e:
                print("error", futures[future], e, flush=True)
    print(f"done: {totals['threads']} threads, {totals['claims']} claims, cost ${totals['cost']:.2f}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

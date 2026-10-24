"""Batch the crawled threads for subagent distillation (the stand-in for distill.py when the CLI is not logged in).

Writes <corpus>/distill-batches/batch-NNN.txt (thread sections, lines wrapped) and batches.json (the plan), in value
order: tier A first. Threads that already have <corpus>/claims/<source>/<id>.json are left out.
Usage: py -3 -I make_batches.py <corpus> [--chars 100000] [--max-threads 12]
"""
import argparse
import json
import os
import re
import sys
import textwrap

CAP = 30000
SKIP_TITLE = re.compile(r"read before you post|forum rules|^\W*(?:wts|wtb|selling|buying|s>|b>)\b|price check|giveaway"
                        r"|recruit|application|ban appeal", re.I)
LOW = re.compile(r"calculat|tracker|\btool|spreadsheet|planner|chair|hair|face|medal|monster book|full-?screen|python"
                 r"|\bwiki\b|library|price|market|\bevent|easter|egg|screenshot|video|scam|safety|account|\bnx\b|cash"
                 r"|skill tables?|kmst|msea|\bjms\b|\bems\b|patch|changes|itcg|story|speech|rubric|offers|dps lab|converter"
                 r"|encyclopedia|lag\b|resolution|windows|install|client|donat|vote|discord|broken|in progress", re.I)
HIGH = re.compile(r"guide|training|leveling|levelling|\bpq\b|party quest|boss|prequest|quest|build|job adv|advancement"
                  r"|warrior|fighter|crusader|hero|page|paladin|spear|dragon knight|dark knight|mage|magician|wizard"
                  r"|cleric|priest|bishop|bowman|hunter|ranger|bowmaster|crossbow|sniper|marksman|thief|assassin|hermit"
                  r"|night lord|bandit|shadower|pirate|brawler|marauder|buccaneer|gunslinger|outlaw|corsair|beginner"
                  r"|cygnus|aran|zakum|horntail|papulatus|pink bean|balrog|apq|cpq|lpq|kpq|opq|mesos?|hp wash|accuracy", re.I)


def tier(title, forum):
    if LOW.search(title):
        return "C"
    if forum == "Class Guides" or HIGH.search(title):
        return "A"
    return "B"


def op_text(thread):
    return "\n\n---\n\n".join(p["text"] for p in thread["posts"] if p.get("is_op") and p["text"].strip())


def wrap(text):
    out = []
    for line in text.split("\n"):
        out.extend(textwrap.wrap(line, 400, break_long_words=True, break_on_hyphens=False) or [""])
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus")
    ap.add_argument("--chars", type=int, default=100000)
    ap.add_argument("--max-threads", type=int, default=12)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    out_dir = os.path.join(args.corpus, "distill-batches")
    plan_path = os.path.join(out_dir, "batches.json")
    previous = []
    if os.path.exists(plan_path):  # a re-run (more threads crawled) adds batches after the planned ones
        with open(plan_path, encoding="utf-8") as f:
            previous = json.load(f)
    planned = {key for p in previous for key in p["threads"]}
    todo = []
    for source in sorted(os.listdir(os.path.join(args.corpus, "threads"))):
        for name in os.listdir(os.path.join(args.corpus, "threads", source)):
            if (not name.endswith(".json") or os.path.exists(os.path.join(args.corpus, "claims", source, name))
                    or f"{source}:{name[:-5]}" in planned):
                continue
            with open(os.path.join(args.corpus, "threads", source, name), encoding="utf-8") as f:
                t = json.load(f)
            text = op_text(t)
            if len(text) < 600 or SKIP_TITLE.search(t["title"]):
                continue
            todo.append((tier(t["title"], t["forum"]), source, t, text))
    order = {"A": 0, "B": 1, "C": 2}
    todo.sort(key=lambda x: (order[x[0]], x[1], -min(len(x[3]), CAP)))
    os.makedirs(out_dir, exist_ok=True)
    plan, batch, size = list(previous), [], 0
    first_new = len(plan)

    def flush():
        nonlocal batch, size
        if not batch:
            return
        n = len(plan) + 1
        path = os.path.join(out_dir, f"batch-{n:03d}.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(section for _, section, _ in batch))
        plan.append({"batch": n, "file": path, "tier": batch[0][2], "threads": [key for key, _, _ in batch],
                     "chars": size})
        batch, size = [], 0

    for t_tier, source, t, text in todo:
        if t_tier == "C":
            continue
        body = text[:CAP]
        section = (f"===== THREAD {source} {t['id']} =====\nsource: {source}\nforum: {t['forum']}\ntitle: {t['title']}\n"
                   f"author: {t['starter']}\ndate: {t['started']}\ntruncated: {'yes' if len(text) > CAP else 'no'}\n\n"
                   f"{wrap(body)}\n===== END {source} {t['id']} =====\n")
        if batch and (size + len(body) > args.chars or len(batch) >= args.max_threads or batch[0][2] != t_tier):
            flush()
        batch.append((f"{source}:{t['id']}", section, t_tier))
        size += len(body)
    flush()
    with open(plan_path, "w", encoding="utf-8") as f:
        json.dump(plan, f, indent=1)
    new = plan[first_new:]
    skipped = sum(1 for x in todo if x[0] == "C")
    print(f"{len(new)} new batches ({first_new + 1}..{len(plan)}), {sum(len(p['threads']) for p in new)} threads "
          f"(tier A {sum(len(p['threads']) for p in new if p['tier'] == 'A')}, "
          f"B {sum(len(p['threads']) for p in new if p['tier'] == 'B')}), {skipped} low-value threads skipped")


if __name__ == "__main__":
    main()

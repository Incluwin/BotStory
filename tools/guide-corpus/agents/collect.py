"""Collect subagent answers: <corpus>/claims-raw/batch-NNN-pK.jsonl -> <corpus>/claims/<source>/<id>.json (distill.py's
format), checking each answer against claims-schema.json. Prints per-batch status and the threads still missing.
Usage: py -3 -I collect.py <corpus> <claims-schema.json>
"""
import glob
import json
import os
import re
import sys


def check_claim(c, s):
    props = s["properties"]["claims"]["items"]["properties"]
    if not isinstance(c, dict) or set(c) != set(props):
        return "fields"
    if c["topic"] not in props["topic"]["enum"]:
        return "topic"
    if not isinstance(c["jobs"], list) or any(j not in props["jobs"]["items"]["enum"] for j in c["jobs"]):
        return "jobs"
    for f in ("level_min", "level_max"):
        if not isinstance(c[f], int) or not 0 <= c[f] <= 200:
            return f
    kinds = props["entities"]["items"]["properties"]["kind"]["enum"]
    if not isinstance(c["entities"], list) or any(not isinstance(e, dict) or e.get("kind") not in kinds
                                                  or not isinstance(e.get("name"), str) for e in c["entities"]):
        return "entities"
    if not isinstance(c["claim"], str) or len(c["claim"]) > 240 or not isinstance(c["say"], str) or len(c["say"]) > 100:
        return "text"
    if not isinstance(c["depends_on_numbers"], bool) or c["strength"] not in props["strength"]["enum"]:
        return "flags"
    return None


def main():
    corpus, schema_path = sys.argv[1], sys.argv[2]
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    with open(schema_path, encoding="utf-8") as f:
        schema = json.load(f)
    with open(os.path.join(corpus, "distill-batches", "batches.json"), encoding="utf-8") as f:
        plan = json.load(f)
    answers, bad_lines, bad_claims = {}, 0, {}
    for path in sorted(glob.glob(os.path.join(corpus, "claims-raw", "batch-*.jsonl"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                    a = row["answer"]
                    key = f"{row['source']}:{row['id']}"
                    assert isinstance(a["usable"], bool) and isinstance(a.get("skip_reason", ""), str)
                except Exception:
                    bad_lines += 1
                    continue
                claims = []
                for c in a.get("claims") or []:
                    why = check_claim(c, schema)
                    if why:
                        bad_claims[why] = bad_claims.get(why, 0) + 1
                    else:
                        claims.append(c)
                answers[key] = {"usable": a["usable"], "skip_reason": a.get("skip_reason", ""), "claims": claims[:40]}
    written, missing = 0, []
    for p in plan:
        for key in p["threads"]:
            source, tid = key.split(":")
            out = os.path.join(corpus, "claims", source, tid + ".json")
            if key not in answers:
                if not os.path.exists(out):
                    missing.append((p["batch"], key))
                continue
            with open(os.path.join(corpus, "threads", source, tid + ".json"), encoding="utf-8") as f:
                t = json.load(f)
            record = {k: t.get(k) for k in ("source", "id", "url", "forum", "title", "starter", "started", "pages")}
            record.update(prefilter=None, model="subagent-sonnet", chunks=[answers[key]])
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, "w", encoding="utf-8") as f:
                json.dump(record, f, ensure_ascii=False, indent=1)
            written += 1
    claims = sum(len(a["claims"]) for a in answers.values())
    unusable = sum(1 for a in answers.values() if not a["usable"])
    print(f"{written} threads written ({unusable} unusable), {claims} claims; {bad_lines} bad lines; "
          f"bad claims dropped {bad_claims}")
    done = {}
    for b, key in missing:
        done.setdefault(b, []).append(key)
    incomplete = sorted(b for b in done if any(key in answers for key in next(p for p in plan if p["batch"] == b)["threads"]))
    print(f"missing {len(missing)} threads in {len(done)} batches; partly done batches: {incomplete}")
    return 1 if bad_lines else 0


if __name__ == "__main__":
    sys.exit(main())

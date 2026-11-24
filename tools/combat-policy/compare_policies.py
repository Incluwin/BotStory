#!/usr/bin/env python3
"""Compares combat-policy candidates with live play on a check stack (docs/WORLD_SYSTEMS_PLAN.md I1.1).

  compare_policies.py run   --stack <stack.json> [--jobs 100,110] [--parallel 6] [--learn 300] [--fresh]
  compare_policies.py table --stack-tree <worktree the stack ran from> --out docs/COMBAT_POLICY_RESULTS.md \
                            --staging docs/combat-policy-staging

run    For each job: gives it arena learning time (policy=learn adopt=off) where it has fewer than MIN_DECISIONS
       candidate decisions, or with --fresh for every scenario not measured yet (what bench grinders learned is replaced
       on the stack); --learn 0 gives none and skips what has too few. Then measures candidate against live play in alternating pairs
       (policy=candidate adopt=off) until the verdict is settled or MAX_LOOKS batches are spent. Every reading is a
       line of <stack tree>/logs/arena-comparisons.jsonl, so a stopped run goes on where it was.
table  Judges the readings, writes the Markdown table and puts the winners' policy files in --staging.

Nothing here adopts anything: adopt=off never writes combat-policy/, and --staging is refused inside it. Only a check
stack is driven (the admin port of tools/checks; the live port 8480 is refused).

The verdict of one job and scenario, on the paired differences (candidate - live) of all its pairs:
  candidate wins   the CONFIDENCE interval lies above 0 and the mean is at least MARGIN of live play's rate
  priors won       the interval lies below +MARGIN: whatever the candidate is worth, it is not MARGIN better
  not enough data  neither after MAX_LOOKS batches (or the candidate has too few decisions)
CONFIDENCE is 99 % per look, so with at most three looks a verdict is wrong less than 3 % of the time.
"""
import argparse
import json
import math
import statistics
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from queue import Queue
from threading import Lock

MARGIN = 0.03
CONFIDENCE = 0.99
PAIRS, SECONDS, MAX_LOOKS = 8, 20, 3
MIN_DECISIONS = 400
SCENARIOS = ("single", "multi")
LIVE_ADMIN_PORT = ":8480"
OPERATOR = "Crewfriend"
# Flat ground with no mobs of its own, 450 px or more on both sides of the first portal (one arena each).
MAPS = (180000000, 140030000, 450007000, 450012000, 270000000, 271030000, 450006000, 450009120)
# Every job a citizen can hold: the explorer trees, the Cygnus Knights and Aran.
JOBS = [b + o for b in (100, 200, 300, 400, 500)
        for o in ((0, 10, 11, 12, 20, 21, 22, 30, 31, 32) if b in (100, 200) else (0, 10, 11, 12, 20, 21, 22))]
JOBS += [b + o for b in (1100, 1200, 1300, 1400, 1500) for o in (0, 10, 11)] + [2100, 2110, 2111, 2112]


def level_for(job: int) -> int:
    """The middle of the levels a citizen spends in that advancement."""
    if job % 100 == 0:
        return 25
    return {0: 55, 1: 100, 2: 140}[job % 10]


def t_quantile(p: float, df: int) -> float:
    """Student t quantile from the normal one (Cornish-Fisher; within 1 % from 5 degrees of freedom up)."""
    z = statistics.NormalDist().inv_cdf(p)
    return (z + (z ** 3 + z) / (4 * df) + (5 * z ** 5 + 16 * z ** 3 + 3 * z) / (96 * df ** 2)
            + (3 * z ** 7 + 19 * z ** 5 + 17 * z ** 3 - 15 * z) / (384 * df ** 3))


def judge(candidate: list, live: list, capped: bool = False) -> dict:
    """The verdict on paired readings; see the module note."""
    n = min(len(candidate), len(live))
    out = {"pairs": n, "candidate": 0.0, "live": 0.0, "change": 0.0, "low": 0.0, "high": 0.0, "verdict": "not enough data"}
    if n < 6:
        return out
    diffs = [c - l for c, l in zip(candidate, live)]
    mean, live_mean = statistics.fmean(diffs), statistics.fmean(live[:n])
    if live_mean <= 0:
        return out
    half = t_quantile(0.5 + CONFIDENCE / 2, n - 1) * statistics.stdev(diffs) / math.sqrt(n)
    out.update(candidate=statistics.fmean(candidate[:n]), live=live_mean, change=mean / live_mean,
               low=(mean - half) / live_mean, high=(mean + half) / live_mean)
    if capped:
        out["verdict"] = "not enough data"
    elif out["low"] > 0 and out["change"] >= MARGIN:
        out["verdict"] = "candidate wins by %.1f %%" % (100 * out["change"])
    elif out["high"] < MARGIN:
        out["verdict"] = "priors won"
    return out


def readings(tree: Path) -> dict:
    """(job, scenario) -> {candidate, live, looks, deaths, level, capped} pooled over the runs of the latest decisions."""
    out = {}
    log = tree / "logs" / "arena-comparisons.jsonl"
    for raw in log.read_text(encoding="utf-8").splitlines() if log.exists() else []:
        line = json.loads(raw)
        key = (line["jobId"], line["scenario"])
        got = out.get(key)
        if got is None or got["decisions"] != line["decisions"]:  # another candidate: start over (arena kills level an Agent)
            got = out[key] = {"candidate": [], "live": [], "looks": 0, "deaths": 0, "capped": False, "job": line["job"],
                              "level": line["level"], "decisions": line["decisions"], "seconds": line["seconds"]}
        got["looks"] += 1
        got["deaths"] += line["deaths"]
        got["capped"] |= line["capped"]
        got["candidate"] += line["candidateDps"]
        got["live"] += line["liveDps"]
    return out


def candidate_of(tree: Path, job: int) -> dict:
    path = tree / "scripts/config/agents/combat-policy/candidates" / f"{job}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"scenarios": {}}


def decisions_of(tree: Path, job: int, scenario: str) -> int:
    return candidate_of(tree, job)["scenarios"].get(scenario, {}).get("decisions", 0)


class Stack:
    def __init__(self, state_file: Path):
        state = json.loads(state_file.read_text(encoding="utf-8-sig"))
        self.url, self.token, self.tree = state["adminUrl"], state["token"], Path(state["worktree"])
        if LIVE_ADMIN_PORT in self.url:
            sys.exit("refusing the live server's admin port")
        self.lock = Lock()

    def request(self, method: str, path: str, body=None) -> dict:
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.url + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as res:
            return json.loads(res.read())

    def gm(self, command: str) -> str:
        """Runs one allowlisted command as the headless operator and returns what it said at once."""
        with self.lock:
            answer = self.request("POST", "/actions/gm-command", {"operator": OPERATOR, "command": command})
            time.sleep(1.0)
            try:
                answer = self.request("GET", "/runtime/gm-command")
            except OSError:
                pass
            return json.dumps(answer)

    def arena(self, command: str, wait: float) -> None:
        """Starts an arena run, waiting for the Agent's previous one, and waits until the next could start."""
        deadline = time.time() + wait + 600
        while True:
            said = self.gm(command)
            if "Combat arena started" in said:
                break
            if "already in progress" not in said or time.time() > deadline:
                raise RuntimeError(f"{command}: {said[-400:]}")
            time.sleep(10)
        time.sleep(wait)


def compare_job(stack: Stack, job: int, learn_seconds: int, fresh: bool, maps: Queue) -> str:
    scenarios = [s for s in SCENARIOS if learn_seconds > 0 or decisions_of(stack.tree, job, s) >= MIN_DECISIONS]
    if not scenarios:
        return f"{job}: no candidate"
    arena_map = maps.get()
    agent = f"Arena{job}"
    base = f"combatarena {agent} job={job} level={level_for(job)} map={arena_map} adopt=off"
    try:
        line = " noblesse" if 1000 < job < 2000 else " legend" if job >= 2000 else ""
        stack.gm(f"spawnbot {agent} confirm noparty{line}")
        time.sleep(5)
        for scenario in scenarios:
            if decisions_of(stack.tree, job, scenario) < MIN_DECISIONS or fresh and (job, scenario) not in readings(stack.tree):
                stack.arena(f"{base} scenario={scenario} policy=learn learn={learn_seconds}", learn_seconds + 60)
            for _ in range(MAX_LOOKS):
                got = readings(stack.tree).get((job, scenario))
                if got and (got["looks"] >= MAX_LOOKS
                            or judge(got["candidate"], got["live"], got["capped"])["verdict"] != "not enough data"):
                    break
                before = got["looks"] if got else 0
                seconds = 2 * PAIRS * (SECONDS + 4) + 30
                stack.arena(f"{base} scenario={scenario} policy=candidate pairs={PAIRS} evalseconds={SECONDS}", seconds)
                for _ in range(30):
                    got = readings(stack.tree).get((job, scenario))
                    if got and got["looks"] != before:
                        break
                    time.sleep(10)
            got = readings(stack.tree).get((job, scenario))
            print(job, scenario, judge(got["candidate"], got["live"], got["capped"]) if got else "no reading", flush=True)
        return f"{job}: done"
    except Exception as failure:  # one job's trouble must not end the others' runs
        return f"{job}: FAILED {failure}"
    finally:
        maps.put(arena_map)


def run(args) -> None:
    stack = Stack(Path(args.stack))
    maps: Queue = Queue()
    for m in MAPS[:args.parallel]:
        maps.put(m)
    jobs = [int(j) for j in args.jobs.split(",")] if args.jobs else JOBS
    with ThreadPoolExecutor(args.parallel) as pool:
        for result in pool.map(lambda j: compare_job(stack, j, args.learn, args.fresh, maps), jobs):
            print(result, flush=True)


def rows(tree: Path, jobs: list) -> list:
    out, seen = [], readings(tree)
    for job in jobs:
        for scenario in SCENARIOS:
            got = seen.get((job, scenario))
            if got is None:
                out.append({"job": job, "name": candidate_of(tree, job).get("jobName", ""), "scenario": scenario,
                            "level": level_for(job), "decisions": decisions_of(tree, job, scenario), "runs": 0, "deaths": 0,
                            "capped": False, **judge([], [])})
            else:
                out.append({"job": job, "name": got["job"], "scenario": scenario, "level": got["level"], "runs": got["looks"],
                            "decisions": got["decisions"], "deaths": got["deaths"], "capped": got["capped"],
                            **judge(got["candidate"], got["live"], got["capped"])})
    return out


def stage(tree: Path, job: int, winners: list, staging: Path) -> Path:
    """The job's policy file as it would be adopted: what it has now plus the winning scenarios."""
    candidate = candidate_of(tree, job)
    live = tree / "scripts/config/agents/combat-policy" / f"{job}.json"
    policy = json.loads(live.read_text(encoding="utf-8")) if live.exists() else {**candidate, "scenarios": {}}
    for row in winners:
        policy["scenarios"][row["scenario"]] = {**candidate["scenarios"][row["scenario"]], "adopted": True,
                                                "learnedDps": row["candidate"], "priorDps": row["live"]}
    staging.mkdir(parents=True, exist_ok=True)
    out = staging / f"{job}.json"
    out.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")
    return out


def table(args) -> None:
    tree, staging, out = Path(args.stack_tree), Path(args.staging), Path(args.out)
    if "combat-policy" in staging.resolve().parts[-1:] or "agents" in staging.resolve().parts:
        sys.exit("refusing to stage into the live policy folder")
    jobs = [int(j) for j in args.jobs.split(",")] if args.jobs else JOBS
    result = rows(tree, jobs)
    lines = ["| Job | Scenario | Level | Decisions | Runs | Pairs | Candidate dps | Live dps | Change (99 % interval) | Deaths | Verdict |",
             "|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|"]
    for r in result:
        interval = "%+.1f %% (%+.1f .. %+.1f)" % (100 * r["change"], 100 * r["low"], 100 * r["high"]) if r["pairs"] else "-"
        lines.append(f"| {r['job']} {r['name']} | {r['scenario']} | {r['level']} | {r['decisions']} | {r['runs']} | "
                     f"{r['pairs']} | {r['candidate']:.0f} | {r['live']:.0f} | {interval} | {r['deaths']} | {r['verdict']} |")
    winners = {}
    for r in result:
        if r["verdict"].startswith("candidate wins"):
            winners.setdefault(r["job"], []).append(r)
    commands = []
    for job, won in winners.items():
        stage(tree, job, won, staging)
        commands.append(f'Copy-Item "{args.staging}/{job}.json" "scripts/config/agents/combat-policy/{job}.json"')
    text = "\n".join(lines) + "\n\nAdoption (the coordinator's and the user's decision; run from the server's folder):\n\n```powershell\n" \
        + ("\n".join(commands) or "# no winner") + "\n```\n"
    marker = "<!-- results -->"
    head = out.read_text(encoding="utf-8").split(marker)[0] if out.exists() else "# Combat policy comparisons\n\n"
    out.write_text(head + marker + "\n\n" + text, encoding="utf-8")
    print(text)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(required=True)
    r = sub.add_parser("run")
    r.add_argument("--stack", required=True)
    r.add_argument("--jobs", default="")
    r.add_argument("--parallel", type=int, default=6, choices=range(1, len(MAPS) + 1))
    r.add_argument("--learn", type=int, default=300)
    r.add_argument("--fresh", action="store_true")
    r.set_defaults(go=run)
    t = sub.add_parser("table")
    t.add_argument("--stack-tree", required=True)
    t.add_argument("--jobs", default="")
    t.add_argument("--out", required=True)
    t.add_argument("--staging", required=True)
    t.set_defaults(go=table)
    args = parser.parse_args()
    args.go(args)


if __name__ == "__main__":
    main()

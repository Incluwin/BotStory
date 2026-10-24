# Guide corpus tools

Turn player-written v83 guides into claims the bots know (docs/GUIDE_KNOWLEDGE_PLAN.md).

The corpus itself is **never committed**: forum posts belong to their authors. Keep it in a folder outside the repo
(the host uses `FriendServer/guide-corpus/`). Only `scripts/config/knowledge/guide-knowledge.json` is committed: short
claims reworded by the distiller, each citing its source thread.

## 1. Crawl

```
py -3 -I tools/guide-corpus/crawl.py --out <corpus> --source royals
py -3 -I tools/guide-corpus/crawl.py --out <corpus> --source southperry
```

One request per 3 s per host, robots.txt honoured, every page cached (`<corpus>/raw`), so a re-run only fetches what
is new. Southperry threads started after GMS Big Bang (2010-07-14) are skipped.

## 2. Distil

Needs the Claude CLI logged in once (`claude auth login`).

```
py -3 -I tools/guide-corpus/distill.py --corpus <corpus> --model sonnet --jobs 3
```

`distill-prompt.md` is the system prompt and `claims-schema.json` the answer schema. One output file per thread
(`<corpus>/claims/<source>/<id>.json`); a re-run skips threads already done and waits out usage limits.

**Without the CLI (how the 2026-10-24 corpus was distilled):** agents inside a Claude Code session, one per batch.

```
py -3 -I tools/guide-corpus/agents/make_batches.py <corpus> --max-threads 40
py -3 -I tools/guide-corpus/agents/collect.py <corpus> tools/guide-corpus/claims-schema.json
```

`make_batches.py` packs the threads not yet distilled into `<corpus>/distill-batches/batch-NNN.txt` (100k chars, in
value order: class/training/PQ/boss/quest guides first; calculators, trackers, cosmetics and patch notes skipped) and
appends to `batches.json`, so a re-run after a new crawl only adds batches. Each agent follows `agents/AGENT_TASK.md`
(fill in `<tools>` and `<corpus>`) and writes `<corpus>/claims-raw/batch-NNN-pK.jsonl`. `collect.py` checks every
answer against the schema, writes the per-thread claim files the import reads, lists unfinished batches, and exits 1
on a malformed line. An agent costs ~90k tokens before it reads its batch, so pack short threads 40 to a batch.

## 3. Import

From the server directory, after `mvnw.cmd package -DskipTests`:

```
tools\guide-import.bat <corpus>
```

Writes `scripts/config/knowledge/guide-knowledge.json` and `tmp/guide-import-report.md` (what was dropped and why, and
the names that did not resolve: candidates for `aliases.yaml`). It also reads `<corpus>/threads` to drop any claim that
repeats ten words in a row of its guide. Check how a name resolves with
`java -cp target\Cosmic.jar server.knowledge.export.GuideImportMain --resolve scripts/config/knowledge "map:ant tunnel i"`.

The server loads the file with the rest of the knowledge (`!knowledge reload`, `!knowledge status`);
`knowledge.GUIDES: false` in config.yaml turns it off.

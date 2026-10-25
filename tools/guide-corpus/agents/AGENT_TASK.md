# Distil one guide batch (one agent per batch)

The session that runs the batches replaces `<tools>` with the absolute path of `tools/guide-corpus` and `<corpus>`
with the corpus folder before handing this file to an agent.

Your prompt names a batch number NNN (three digits). Work only with the files named here: do not run shell commands
or scripts, and do not read anything else.

1. Read the rules: `<tools>/distill-prompt.md` (treat it as your system prompt and follow it exactly) and the answer
   schema `<tools>/claims-schema.json`.
2. Read the whole batch file `<corpus>/distill-batches/batch-NNN.txt` with the Read tool, 2000 lines at a time
   (offset/limit), until the end. It holds several threads, each between `===== THREAD <source> <id> =====` and
   `===== END <source> <id> =====`, with a header (source, forum, title, author, date, truncated) and the author's own
   posts.
3. For each thread write one answer matching the schema exactly: `{"usable": bool, "skip_reason": string, "claims": [...]}`
   (skip_reason "" when usable; at most 40 claims; every claim has all nine fields with the schema's enum values;
   claim at most 240 chars; say lowercase and under 70 chars). Claims are in your own words; never copy the guide's
   sentences. Skill names exactly as the game names them (e.g. "Fire Charge", "Power Strike", "Haste").
4. Save the answers as JSON Lines, one thread per line: `{"source": "<source>", "id": "<id>", "answer": {...}}`, with the
   Write tool: `<corpus>/claims-raw/batch-NNN-p1.jsonl` for the first 4 threads, `batch-NNN-p2.jsonl` for the next 4,
   and so on (each file only its own threads, one line per thread, no pretty-printing). Do not check the files
   afterwards.
5. Reply with one line only: `batch NNN: <threads> threads, <claims> claims, <unusable> unusable`. Do not quote the guides.

# Chat intent evaluation

This evaluates the actual Java request builders and decision policy, not a separately
reimplemented prompt. No game server restart is needed to run it.

## Data and provenance

- `seed.jsonl`: 40 synthetic cases with explicit command labels. The last ten are
  contrastive holdout cases. These are implementation checks, not player evidence.
- `challenge.jsonl`: 14 phrases from the earlier log analysis, labeled by the coding
  assistant. Some accept several commands because the wording is ambiguous. Original
  runtime context was unavailable. These are provisional annotations, not confirmations.
- `confirmations.jsonl`: generated locally from successful `[phrase] learned ... (PLAYER ...)`
  records. Never label a model ACT or the pre-dispatch `confirmed` log as player ground truth.
  The importer scans current and gzip archived logs, deduplicates overlapping files, and
  excludes phrases with conflicting confirmed commands. Legacy logs lack runtime state;
  those rows explicitly say so and contain only the recorded message.

Do not combine provenance groups to claim production accuracy. Repeated observations of
one phrase are not independent examples. Review ambiguous annotations before expanding the
benchmark. Keep new player phrases separate from examples used to tune descriptions.

## Run

From the server repository, with Python and Java available:

```sh
python tools/jev-eval/collect_confirmations.py
python -m unittest discover -s tools/jev-eval -p test_eval.py
```

Set `TYPESAFE_API_KEY` in the environment. The next command makes paid API requests;
it runs only when explicitly enabled, and normal tests skip it:

```sh
./mvnw -o -q test -Dtest=JevChatEvaluationTest -Djev.eval=true \
  -Djev.eval.dataset=tools/jev-eval/seed.jsonl \
  -Djev.eval.output=tools/jev-eval/results/run.json
python tools/jev-eval/summarize.py tools/jev-eval/results/run.json
```

Use `challenge.jsonl` or a nonempty `confirmations.jsonl` as the dataset to evaluate that
slice. `-Djev.eval.repeats=3` repeats all cases and triples API work. Record the resolved
model from response traces, not just the `jev-latest` alias. Results and extracted player
data are ignored by Git; share only deliberately reviewed summaries.

## Strategies and metrics

- `flat`: original full intent/argument request, no averaging or re-ask.
- `averaged4`: full-request averaging with four deterministic sampled orders, including
  the current judge's narrow re-ask if the first answer produces ASK. Actual calls and
  metered input tokens are included; seed 42 controls order sampling, not model randomness.
- `hierarchical`: unaveraged category request, then one branch request with only relevant
  argument questions. CHAT or very low category confidence stops after one request. A
  no-match option remains in every branch. At most two requests are made; there is no
  extra permutation re-ask. Routing uncertainty cannot be hidden by a confident leaf.

Each result includes actual question payloads, raw responses, chosen command, outcome,
confidence, request count, input tokens and wall time. Strategy order rotates between
cases to reduce systematic timing bias. The summary separates provenance and split,
reports command accuracy (whether the candidate command matches the label), ASK/ACT
rates, false automatic actions, automatic-action precision, unavailable responses and
cost/latency. A correct ASK candidate is not counted as an automatic success. Do not use
ASK rate alone: a confidently wrong command is worse than clarification.

The API confidence statistic and pijev's winning-mean probability are different scores;
fixed thresholds here measure existing policy behavior, not probability calibration.
Multi-label challenge cases and partial hierarchical distributions are not appropriate
for a direct Brier/log-loss comparison, so this report does not pretend to provide one.

## Hierarchy rollout

`AgentChatIntentJudge.HIERARCHICAL_ENABLED` in `agent-engine.yaml` defaults to `false`.
The category and branch request kinds are exempt from global permutation expansion.
The groups preserve the differences between stop/logout, follow/move-once and
grind/patrol/sentry; probability mass is never pooled to authorize a different command.

Enable only after representative player data shows fewer unnecessary ASKs without more
wrong automatic actions and acceptable latency. The initial synthetic run did not meet
that requirement, so the existing server policy remains selected. No server restart or
deployment is performed by the evaluation tools.

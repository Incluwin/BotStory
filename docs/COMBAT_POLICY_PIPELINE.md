# Combat policy pipeline (I1.1)

`tools/combat-policy/compare_policies.py` compares what Agents learned (a job's candidate table) with live play on a
check stack, and writes the table in `docs/COMBAT_POLICY_RESULTS.md`. It adopts nothing.

## How a comparison is played

`!combatarena <agent> job=<id> level=<n> map=<id> adopt=off scenario=<single|multi> policy=candidate pairs=8 evalseconds=20`

- One Agent shaped to the job, its catalog SP build, the best plain weapon of its family it can hold
  (`AgentHarnessProvisioning.arenaWeaponsFor`), buffed, at full tick rate, against dummies that refill.
- Both sides of a pair play as live play does: the rotation profile counts, and the candidate is followed only where a
  live Agent would follow an adopted table (`LIVE_MIN_EVIDENCE` tries), else the scorer decides. So a "candidate wins"
  is the gain adoption would bring, not the gain of a table played blindly. Which side goes first alternates.
- `adopt=off` writes no policy file and no rotation profile. Each reading is one line of `logs/arena-comparisons.jsonl`.
  Comparisons of different Agents run side by side, each on a flat map of its own.
- `policy=learn adopt=off learn=<s>` gives arena learning time and keeps the table as the job's candidate.

## Verdict

On the paired differences of all pairs of one candidate, 99 % two-sided t interval per look, at most three looks of
eight pairs (so a verdict is wrong less than 3 % of the time):

| Verdict | When |
|---|---|
| candidate wins by X % | the interval is above 0 and the mean is at least 3 % of live play's rate |
| priors won | the interval is below +3 %: the candidate is not 3 % better, whatever else |
| not enough data | neither after three looks, or a reading hit the dummy's HP pool |

Deaths: an Agent in the arena is topped up every second and its dummies do not fight back, so the column only shows
that no run was spoiled by one. A skill policy chooses among the attacks already legal and touches no potion, heal or
retreat point, but a slower or closer skill can change how often an Agent is hit: read the tell audit's per-class death
rate after adopting (`tools/tells-report.ps1`).

## Run it

Set up a throwaway worktree for the stack with, in its own `config.yaml` / `agent-engine.yaml` (never committed):
`living_world.ENABLED` and `population.ENABLED` off, `BENCH_COMBAT_LEARN` off, the operator in
`AGENT_AUTHORITY_ADMINISTRATOR_NAMES`, and `AgentProvisioningPolicy.DEFAULT_MAX_PER_WINDOW` / `_PER_CONTROLLER` at 200.
Copy the live server's `scripts/config/agents/combat-policy/candidates/` into it (the folder is git-ignored).

```powershell
tools\checks\start-check-stack.ps1 -Worktree <stack worktree>
python tools\combat-policy\compare_policies.py run --stack <tools>\checks\.stack\stack.json --learn 0
python tools\combat-policy\compare_policies.py run --stack <tools>\checks\.stack\stack.json --fresh --learn 180 --parallel 12
python tools\combat-policy\compare_policies.py table --stack-tree <stack worktree> --out docs\COMBAT_POLICY_RESULTS.md --staging docs\combat-policy-staging
```

The first run judges the bench candidates, the second gives every job arena learning time and judges that. Move
`logs/arena-comparisons.jsonl` aside between the two. A stopped run goes on where it was.

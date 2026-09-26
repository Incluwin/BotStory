# Combat self-improvement plan

Bots should learn how to use their skills from measured results instead of from hand-written
rotations, and the same measurements should later drive skill balancing. This plan turns the
combat loop into a scored policy with learnable weights, adds the telemetry that produces the
reward, and reuses the skill-probe harness as the arena where learning and regression testing
happen without players watching.

## 1. Where the combat loop stands (2026-09-21)

- **Attack choice** (`AgentSkillAttackPlanRuntime` → `AgentAttackPlanScoringPolicy`): every
  executable attack skill is planned against the current target set, scored by *estimated*
  damage per animation second from `CombatFormulaProvider.estimateExpectedDamage`, and the
  best raw/useful DPS wins. The estimate is the server's own formula, so it is exact for
  base damage but blind to anything that happens *after* the hit.
- **Special moves** (`AgentCombatSpecialMoveTickRuntime`): a summon is kept up, a mob debuff
  opens on each fresh target, survival skills fire on thresholds, with two attacks between
  special moves.
- **Gates observed to be wrong in the live client:**
  - Armor Crash opened on Kentaurus. Crash skills only cancel a monster buff; against an
    unbuffed mob they are a wasted cast. *Fixed:* `AgentCrashSkillPolicy` requires a
    cancellable buff on the target.
  - Coma/Panic fired at two orbs. On this server a finisher scales ×1.2 at two orbs to ×2.5
    at five (`AbstractDealDamageHandler`) and drops the whole stack, so the two-orb finisher
    is a DPS loss versus Brandish. *Fixed as a prior:* `AgentComboFinisherPolicy` waits for a
    full stack (`MIN_ORBS`, clamped to what Combo Attack can hold).
- Both fixes are deterministic priors. They are what a learner should discover on its own,
  and they are the kind of rule that will be wrong for some other skill we have not watched
  yet. The learner below is how the remaining cases get found.

## 2. Goals and non-goals

Goals
1. Every skill choice is made by one scored policy whose weights can change from evidence.
2. Evidence is measured, not estimated: damage actually dealt, kills, EXP, potions, deaths,
   time, per cast and per grinding window.
3. Learning happens where mistakes are cheap (the arena) and is only *exploited* live.
4. Learned rotations are inspectable, versioned and overridable by the operator.
5. The same data produces a per-skill viability report that feeds skill balancing.

Non-goals (for this plan)
- Movement/positioning learning. Only skill selection and its conditions.
- Boss-specific scripting. Bosses stay on the deterministic path until Phase 4.
- Any model call. This is counting and bandits, cheap enough to run on every bot.

## 3. Design

### 3.1 Context, action, reward

- **Context** `c` is a small bucketed tuple computed at decision time:
  `job line` · `level band (10s)` · `targets in best box (1 / 2-3 / 4+)` · `target HP% (≤25 / ≤60 / full)` ·
  `combo orbs (0 / partial / full)` or `energy (off / charged)` · `MP% (≤20 / ≤50 / high)` ·
  `target buffed? (y/n)` · `party present? (y/n)`. Eight fields, ~1.5k reachable buckets per job.
- **Action** `a` is a skill id (plus the basic attack). Special moves and attacks share the
  same action space so "cast Coma now" and "Brandish now" compete directly.
- **Reward** `r` is measured over the *action window*: from the cast until the next decision.
  `r = damage_dealt / window_seconds` for attacks, with the same units for special moves via
  the damage the window enabled (a stun that let two Brandishes land for free counts through
  the DPS of that window). Secondary rewards are logged, not learned from yet: kills, EXP,
  MP spent, HP lost, potions used, deaths.

### 3.2 Scored policy

`score(a | c) = estimate_dps(a, c) × w[job][c][a] + prior_bonus(a, c) − cost(a, c)`

- `estimate_dps` is today's `AgentAttackPlanScoringPolicy` number (kept as the physics prior).
- `w` starts at 1.0 everywhere and is what learning changes.
- `prior_bonus/cost` hold the deterministic rules we already know are right (crash gate,
  full-stack finisher, MP reserve, cooldown opportunity cost) expressed as numbers so the
  learner can override a prior that turns out wrong for a bucket.
- Selection: **Thompson sampling** over `w` (Beta/Normal posterior per `(job, c, a)`), ε=0 in
  live play (exploit), ε>0 only in the arena. Contexts share strength through a fallback
  chain `(job, c, a) → (job, coarse c, a) → (job, a)` so a fresh bucket inherits its job's
  general knowledge.

### 3.3 Learning signal path

1. `AgentCombatAttackRuntime.attackMonster` and the special-move `afterCast` hook emit a
   `CastEvent {agentId, job, level, skillId, context, tCast}`.
2. The server's damage application for that bot (`AbstractDealDamageHandler` for the bot's
   packets is server-side already) is attributed to the open cast window through the existing
   `AgentCombatAttackRuntime` result (damage per target is known when the attack commits).
3. `AgentCombatOutcomeLedger` (new, per agent, in-memory ring) closes the window at the next
   decision and produces `Outcome {reward, kills, expGained, mpSpent, hpLost}`.
4. `AgentSkillPolicyLearner` folds each outcome into the posterior for `(job, c, a)`; posteriors
   are **shared per job line across all bots**, so 40 heroes grinding is 40× the evidence.
5. Every N minutes the posterior means are flushed to `agent_skill_policy_weights`
   (Liquibase changeset; keyed by job, context hash, skill) and exported to
   `scripts/config/agents/combat-policy/<job>.yaml` for review and git history.

### 3.4 Arena (learning where it is cheap)

The skill-probe harness already spawns a job at a level with a max build into a controlled map
and can drive the combat loop headlessly (`!skillprobe … loop=<s>`). The arena adds:

- `!combatarena <job> level=<n> map=<id> mobs=<id>x<n> minutes=<m> policy=<name|learn>`:
  fills the map with a fixed pack, runs the loop, restocks the pack on kill, and writes a
  report to `docs/agents/evidence/arena/` with kills/min, damage/min, EXP/min, potions,
  deaths, and the rotation actually used (skill share, transition matrix).
- `policy=learn` runs with exploration on; `policy=frozen` evaluates the current posterior;
  `policy=prior` runs today's deterministic rules. The delta between `frozen` and `prior` is
  the acceptance test for shipping learned weights.
- Headless driver via the admin port (`POST /actions/gm-command`, allowlist extended with
  `combatarena`), so a nightly job can run every job line × 3 level bands × 2 map types.

### 3.5 Operator surface

- `!rotation <job>`: prints the learned top rotation per context bucket and its confidence.
- MapleEdit "Combat" page: per-job table of buckets → best action, evidence count, DPS; a
  lock toggle per (job, skill) that pins `w` (operator override) and a "reset job" button.
- Any skill whose learned share is ~0 across all buckets is listed in the viability report
  (3.6) instead of silently vanishing.

### 3.6 Viability report → skill balancing (long term)

From the same ledger, per skill: usage share under the frozen policy, realized DPS vs the best
alternative in the same buckets, MP per damage, and the contexts where it *is* chosen. Skills
with a near-zero share everywhere are "not viable" on this server.

Balancing loop (each step is an arena experiment, never live-first):

1. Propose a change for one skill from `scripts/config/agents/skill-balance.yaml`
   (server-side overrides consumed by `StatEffect`: damage %, MP cost, cooldown, hit count).
2. Re-run the arena for the job with `policy=learn` until the posterior stabilizes.
3. Accept when: the skill's share rises into a target band, the job's DPS moves less than a
   set tolerance against the cross-class median, and rotation entropy (build variety) does not
   drop. Reject otherwise.
4. Mirror accepted numbers into `Skill.wz` with the existing WZ patch tooling so tooltips match.

Cross-class variance is tracked as the spread of per-job arena DPS at equal level and gear
band; the goal is less variance *and* more viable choices per job, not identical jobs.

## 4. Phases

| Phase | Deliverable | Files (new unless noted) | Done when |
|---|---|---|---|
| 0 | Deterministic priors that are plainly right | `AgentCrashSkillPolicy`, `AgentComboFinisherPolicy` (edit), `agent-engine.yaml` keys | **Done 2026-09-21.** Hero rotation is Brandish until full orbs, no Crash on unbuffed mobs. |
| 1 | Telemetry | `AgentCombatCastEvent`, `AgentCombatOutcomeLedger`, `AgentCombatTelemetrySink` (batched DB writes to `agent_combat_cast_log`), changeset `31-combat-telemetry` | 24 h of live grinding produces per-skill realized DPS per job; `!rotation` prints it. |
| 2 | Scored policy with fixed weights | `AgentSkillPolicyScorer` wraps `AgentAttackPlanScoringPolicy`; special moves routed through the same scorer; priors expressed as bonus/cost | Behaviour identical to Phase 0 (regression via skill-probe matrix 392/393) but every choice goes through one scorer. |
| 3 | Arena | `!combatarena`, `AgentCombatArenaService`, admin-port allowlist entry, report writer | Nightly run over 12 jobs × 3 bands; reports checked into `evidence/arena/`. |
| 4 | Learning | `AgentSkillPolicyLearner` (Thompson), `agent_skill_policy_weights`, YAML export, exploit-only live loading | Learner re-derives the Phase 0 priors from a `w=1` start in the arena (this is the acceptance test) and finds at least two rotations we did not hand-write. |
| 5 | Operator surface | MapleEdit Combat page, lock/reset, `!rotation` | Operator can pin or reset a job's rotation without a restart. |
| 6 | Viability + balancing | `AgentSkillViabilityReport`, `skill-balance.yaml` + `StatEffect` override, arena acceptance rule, WZ mirror | First accepted balance change ships with its arena evidence. |

## 5. Risks and how the plan handles them

- **Noisy reward.** Map, mob level and gear all move DPS. Buckets include level band and target
  count; rewards are per-second so window length does not bias; posteriors need a minimum
  evidence count before they can override a prior.
- **Exploration is visible.** Live bots exploit only; exploration is confined to the arena and to
  headless population citizens flagged `LEARN` (off by default).
- **CPU.** One ring buffer per bot, integer math, DB writes batched per minute. Budget: under
  2% of the agent tick time measured by `BotPerformanceMonitor`.
- **Learning the wrong thing.** The reward is damage, so utility skills (stun, slow, heal) are
  undervalued at first. Phase 4 credits a special move with the damage of the window it opened;
  survival skills stay on thresholds and are not learned in this plan.
- **Overfitting to this server's formula.** Intended. The formula *is* the game these bots play;
  when balancing changes the formula the learner re-converges in the arena before going live.

## 6. Status 2026-09-22 (evening): the learner exists

**Built** (`server.agents.capabilities.combat.learning`, arena `policy=learn`):

- `AgentCombatLearner` is semi-Markov Q-learning instead of the Thompson weights sketched in 3.2:
  a finisher's worth depends on what the stack does *after* it, which a per-cast bandit cannot
  see. State = `AgentCombatLearningContext` (targets in reach 1 / 2-3 / 4+, boss, Combo orbs
  0 / 1-2 / 3-4 / each count from 5 / full - Advanced Combo holds 10 and its flat bonus keeps
  growing past the x2.5 finisher cap at 5, so "cash at 5 or hold to 10" must be visible - Energy
  full, summon up, target buffed / debuffed; fields that do not apply are left out). Actions =
  this tick's attack candidates plus summons/debuffs (one action space). Target = cast damage +
  gamma^seconds x value of the next situation, seconds measured.
- Priors (3.2's `estimate_dps x w`): every option starts at the scorer's estimate (own expected
  damage + carrying on at the best estimated rate, minus its time) worth `PRIOR_WEIGHT` visits;
  options estimated under `PRUNE_RATIO` of the best rate are not explored.
- Reward = the formula's expected damage for that exact cast x realized/expected calibration
  (per situation, shrunk to per skill, shrunk to 1): the damage roll is noise with no bearing on
  the choice; caps, immunities and server edits the formula misses stay in.
- Arena: learn per scenario in `LEARN_EPISODE_SECONDS` episodes, then `LEARN_EVAL_REPEATS` x
  `LEARN_EVAL_SECONDS` alternating runs of the frozen table vs the prior scorer. The table is
  adopted for live play only if it is not worse; live Agents exploit adopted situations with
  `LIVE_MIN_EVIDENCE` and otherwise keep the prior. Reports list each learned rule with its
  evidence (casts, damage/cast, seconds/cast) and every tooltip that disagrees with the server.
- `priors=off` lifts the hand-written finisher and Crash gates so the learner has to rediscover
  them (the Phase 4 acceptance test); a toy Combo model in `AgentCombatLearnerTest` already shows
  it learning to hold the stack although an early finisher hits harder per cast.

**Hero L140-145, single target, priors off** (`evidence/arena/hero-*` 2026-09-22 21:25 -> 22:33):

| Run | Change | Learned vs prior | What it did |
|---|---|---:|---|
| 1 | no priors, 300 s | -43% | starved of samples; 22% of casts on 0-damage Three Snails |
| 2 | scorer priors, pruning, 600 s | -5% | holds the stack past 5 orbs; wastes casts on Power Strike/Shout/Armor Crash |
| 3 | expected-damage reward, 5 x 40 s eval | -5% | the waste is 0-damage debuffs locking 1.9 s: a special's prior used the animation (0.6 s) and a finisher's unsustainable DPS as the carry-on rate |
| 4 | real cast lock, sustainable/observed rate | **-0.3%** | parity with the hand-tuned prior from scratch; cashes the stack at 3-4 orbs instead of 5+ |

Measured along the way: on FriendServer's numbers the Hero's finisher timing barely matters
(cash at 3-4, 5 or hold to 10 all land within noise, ~61-64k dps); the flat Advanced Combo bonus
and the x1.54/x2/x2.5 finisher steps balance out.

**Bishop:** the first two runs measured nothing - Angel Ray (~409k/cast) emptied the 5M-HP dummy
in every trial and both policies read exactly 125k dps; the first tie was "adopted" (revoked by
hand). The arena now counts damage second by second and tops the pack back up to the arena HP (an
overridden mob's `getMaxHp()` still reports its stock stat, which silently defeated the first
refill), flags any trial that took most of the pool inside one second, and never adopts on a capped
evaluation. Third run (`bishop-L147-...232749`): **learned +50.2% vs the prior** (303.5k vs 202.1k
dps, outside the noise) - adopted. The prior spent a third of its casts re-summoning: casting a
moving summon cancels the moving one up, so "keep a summon up" flip-flopped Bahamut / Summon Dragon
forever. The learner stopped it (Angel Ray 97% of casts); the prior rule is fixed too
(`AgentCombatSummonRuntime.replacesActiveSummon`).

**Rush:** traced in the arena - the bot now charges ~216 px per Rush (clamped at the platform
edge). The mob drag cannot show there: the server only simulates mob physics when a real player
watches the map (`MobPhysicsService` physics-audience gate), so arena dummies never move; the drag is
covered by `MobSimulationSessionTest` and needs a live-client look.

**Found by measuring, not yet fixed:**

- Bot summons never attacked: summon damage is driven only by the client's SUMMON_ATTACK packet
  (`SummonDamageHandler`) and a bot sends none. *Fixed:* `AgentCombatSummonRuntime.tickSummonAttacks`
  plays the client's part from Skill.wz `summon/attack1` (cycle, attackAfter, mobCount, box or
  radius) and sends the same packet through `CombatGateway.dispatchSummonAttack`, so the server's
  cap/status/broadcast apply; summon damage is credited to the learner and the rotation breakdown.
  Still open: summons do not follow a bot (no MOVE_SUMMON), and the direction byte needs a look in
  the client.
- 81 tooltip/data mismatches across 70 skills (FriendServer edits Skill.wz, not String.wz; e.g.
  Corkscrew Blow 420%/3 mobs in the tooltip vs 180%/5 computed). Learning uses the server's
  numbers; anything an Agent *says* about a skill must too.
- Client-only pirate rules the server never enforced, now kept by Agents (`AgentClientSkillRules`):
  Double Uppercut, Corkscrew Blow, Backspin Blow, Barrage and Snatch cannot be re-cast for
  `REUSE_DELAY_MS` after one lands (locked skills drop out of the candidates, so weaving is left to
  the learner); Corkscrew Blow makes the pirate untouchable for `CORKSCREW_INVINCIBLE_MS` after it
  lands (touch damage and server-driven mob attacks skip the Agent); Demolition and Snatch are only
  plannable under Super Transformation. The learner sees "transformed" as part of the situation,
  and the arena fights every other episode and evaluation pair with the transformation held off,
  so both of the vanilla loops (Barrage/Dragon Strike off-transform, Demolition transformed) are
  learned and measured. Corkscrew's invincibility only pays once dummies hit back (mechanics).
  The reuse delay counts from the end of the attack animation: counted from the hit, Barrage's own
  2.3 s animation outlasted it and the first run spammed Barrage. Energy Charge triples damage and
  carried over between trials, so trials now start from a set bar and cycle through empty/full as
  well as transformed/not; learner vs prior is judged on paired differences (mean +/- standard
  error), since the conditions swing a pair's damage five-fold.
  Result (`buccaneer-L130-...015327`): untransformed Barrage <-> Dragon Strike, with a full bar
  Barrage <-> Energy Orb (Energy Orb fills Barrage's gap better than Dragon Strike once charged),
  transformed Demolition - the vanilla loops, found by the learner. Learned vs prior -5.3%
  (-2.7k +/- 3.8k dps), a tie: the prior weaves too, because a locked skill is off everyone's menu.
- Corkscrew Blow's charge is a choice (short vs full, and pre-charging while mobs approach);
  today every keydown is fired at full charge.

**Fixed with it:** Rush / Assaulter / Corkscrew Blow / Backspin Blow now carry the Agent along
the ground (`AgentCombatDashPolicy`, `AgentKnockbackMovementService.beginGroundDash`, clamped at
walls and ledges) and forward charges drag the mobs they hit (`MobSimulationSession` push).

**Next:** a `mechanics` scenario instead of the plain boss (the dummy casts real WDEF-up /
immunity / counter mob skills; needs a "hold" action and an HP-loss cost so not attacking into a
counter is learnable); Agent summon attacks; the Buccaneer reuse delay; SP marginal value per
point from WZ + formula so builds are learned too.

## 7. Immediate next steps

1. Restart with Phase 0 and confirm in the client: Hero holds orbs to five, no Armor Crash on
   Kentaurus, spawns beside the leader, off-class gear stripped on the next auto-equip pass.
2. Phase 1 telemetry (one session of work): the ledger, the changeset, `!rotation`.
3. Arena command on top of the skill-probe harness (one session).
4. Learner and acceptance test (two sessions), then the operator page.

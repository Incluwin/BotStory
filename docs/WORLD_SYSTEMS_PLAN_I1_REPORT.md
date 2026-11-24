# I1. Learning: what was built, decided and found

Branch `feat/world-learning`. No schema change (changeset 70 stays spare). Both switches ship off; off, every path
answers as before.

| Piece | State |
|---|---|
| I1.1 adoption pipeline | Built and run: `docs/COMBAT_POLICY_PIPELINE.md`, tables in `docs/COMBAT_POLICY_RESULTS.md`. Nothing adopted. |
| I1.4 support points | Built, `population.SUPPORT_LEARNING_ENABLED` off. |
| I1.2 duel learner and self-play | Built, `pvp.DUEL_LEARNING_ENABLED` off. |
| I1.3 job-scale bandit | Design only, and the design is "do not build": below. |

## I1.1

- `!combatarena ... adopt=off`: a comparison that writes no policy file or profile. Both sides play as live play does
  (rotation profiles count, `LIVE_MIN_EVIDENCE` counts), so the number is what adoption would bring. Comparisons of
  different Agents run side by side on maps of their own. `policy=learn adopt=off` gives a job arena learning time and
  keeps the table as its candidate.
- `tools/combat-policy/compare_policies.py`: drives it through the headless operator on a check stack (the live admin
  port is refused), pools the readings, judges them (99 % per look, 3 % margin, a win held over 16 pairs), writes the
  table and stages the winners.
- The arena armed an Agent below Lv 120 with a starter sword (its one Lv 120 weapon could not be equipped); it now takes
  the best plain weapon of its family the Agent can hold.

**Found on the way** (not fixed here, each its own follow-up):

1. *Bench learning is measured at the wrong speed.* Bench grinders learn on maps nobody sees, where an Agent ticks at
   background cadence and a swing takes about 4 s (the candidates' `meanSeconds` are 3.5-4.5 s for skills whose
   animation is 0.7 s). A Q-value per second learned there is not the one a player would see: it undervalues slow big
   hits less and cooldowns more, and the Crusader's bench candidate, 100,000 decisions strong, plays 45-61 % worse than
   the priors. Bench learners should be held at full rate, as a duel holds its fighters, or learn nothing.
2. *Bench candidates mix levels.* One table per job over every level (Magician: 452,620 decisions, one situation, one
   action) says little; most have one action per situation and play exactly as the priors.
3. *Only Bishop (232 single) is adopted today.* `112.json` and `512.json` are in the folder with `adopted: false`.
4. *White Knight charges.* With a low-level charge the buff loop recasts Fire Charge every 3 s (57 casts in 180 s,
   Lv 100 White Knight at level 1 of the charge), and Charged Blow uses the charge up. That is most of why the White
   Knight's candidate, which hardly uses Charged Blow, gains so much: fix the prior and the gain shrinks.
5. *Deaths cannot be measured in the arena* (dummies do not hit back, the Agent is topped up every second).

**Adoption.** The user approved Fire/Poison Arch Mage, Sniper and Outlaw on 2026-11-24 under four conditions; none
repeated its win in the independent second run, so all three are held. White Knight and Paladin (one target, about
+75 % twice) are the only confirmed wins. The Cygnus Knights and Aran (19 jobs) were not run for want of time.
The arena's 3 minutes of learning give 100-200 decisions a setting: enough to play, too few to beat the priors often.

## I1.4

- `AgentSupportPoints` is the engine seam: today's number when no rule is installed, when it is off, and in everything
  `AgentImperfections.excluded` names (event instances, boss maps, party quests). Its bounds hold whatever the rule
  says: heal points 0.80-0.95 (above the 0.70 potion point and far above the 0.45 floor), a survival buff never recast
  later than today, no buff before 85 % or after it runs out, a lapse F8.3 chose left alone.
- `SupportLearning` (population) plays one arm per lever for one Agent for four minutes: HEAL 0.80 / 0.85 / **0.90** /
  0.95, BUFF 0.86 / 0.88 / **0.90** / 0.95 (bold is today). Reward: party damage uptime (0-1) minus 5 per death in the
  party, human or Agent; a death ends the episode at once. Stored as `companion_yield` rows of source `support`, as I2
  did for prices. It reuses I2's `PriceArms`, which is on `Ucb`.
- *Decided, not in the plan:* only free citizens explore; a companion plays the best arm once it has 30 episodes and
  today's point before that. A human's own attacks do not count toward uptime (the seam is on the Agent's attack), their
  deaths do. Solo citizens count as a party of one.

## I1.2

- `DuelLearner` (situation, actions, reward), `DuelLearnedBrain` (what is legal, and doing it), `DuelPolicies` (tables,
  measured pairs, verdict per job, in `logs/pvp/duel-policies/`), `DuelSelfPlay` (a bout that leaves nothing behind),
  `coliseum/SelfPlay` (pairs, floors, hours). `!pvp selfplay [n]|status` starts bouts by hand.
- State: distance against own reach, level, own and foe HP in thirds, out of MP, the foe melee or ranged, mid-swing,
  tonic ready. Actions: each swing in reach, approach, retreat, jump, change level, buff, drink, wait. Reward: damage
  dealt less taken, each as a share of the victim's max HP, plus or minus 1 at the end.
- The gate: a job learns 4,000 thinks, then plays 40 measuring pairs (the same two fighters, once with the table, once
  with the prior brain) spread over the five classes and three bands; the table fights real duels only if the mean paired
  margin less 2.33 standard errors is above 0. A job the prior beat learns half as much again and is measured anew.
  Results per job are the pairs in its file, the log line "duel policy result", and `!pvp selfplay status`.
- Self-play leaves no duel row (so no learned rate, toughness or matchup), no pairing, no ladder row, no line, no
  memory, no paper; HP and MP come back as after any duel, and spent stars, arrows, rocks and fresh cooldowns are given
  back. Only citizens with no human on their map are taken; they return to their spot.
- *Decided, not in the plan:* H_ref is the victim's own max HP, so a warrior's damage is not counted double under even
  classes. Footwork (PD's steps and hops) and the prior's gates stay in force under the learner. Own potions in field
  duels stay the prior's. Team fights keep the prior brain. The measuring bouts play the table under the live evidence
  rule, so what is measured is what would be used. Off-peak is 08:00-12:00 UTC.

- *Trial on the check stack (2026-11-24):* six bouts by `!pvp selfplay 6` fought their rounds (rounds of 29-64 s, one
  side down), eight jobs learned 60-320 decisions each in three minutes, no error, and `logs/pvp` held nothing but
  `duel-policies` (no duels.csv, no learned rates). No job has been measured yet: a verdict needs 4,000 decisions plus
  40 pairs, so "the learned brain beats the prior brain" is **not shown yet**; a few off-peak nights will.
  The bots' own autopot still drinks tonics at its alert, so DRINK decides only above it.

## I1.3 PVP_JOB_SCALE: design, and why not to build it

V2.4 (2026-11-08) wanted a per-job scale within +/-15 %, tuned by a bandit on the self-play matrix. Since then three
learners took that job and are live: `PvpRateLearner` (what a job path deals), `PvpToughness` (what a class and band
takes) and `PvpMatchups` (what is left per class pair), all fed by every duel and bounded. A fourth lever on the same
win rate would chase the same error as those three, and from self-play it would learn a *different* fight (a learned
brain, exploring) from the one players meet. If it is ever wanted:

- Arms per job: 0.85, 0.90, 0.95, **1.00**, 1.05, 1.10, 1.15 on `PvpDamageModel.coefficient`, on `Ucb` with the prior
  at 1.00; a week per arm, so the three learners settle between changes.
- Reward: `-(win rate against other classes - 0.5)^2` over measured self-play pairs with the *prior* brain on both
  sides, at least 60 bouts an arm.
- Only after `DUEL_LEARNING_ENABLED` has been on long enough for the verdicts to settle, and only for a job whose
  win rate stays outside 40-60 % with the three learners converged.

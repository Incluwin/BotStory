# Charged Blow and the charge it uses up (2026-11-24)

**Fault.** Charged Blow (1211002) removes the weapon's charge unless Advanced Charge keeps it
(`CloseRangeDamageHandler.applyCloseRangeEffects`), and the buff loop casts the charge again at once, whatever its timer
says (`AgentCombatBuffRuntime.tickBuffs`, `AgentWeaponChargePolicy.Decision.CAST_NOW`). The scorer priced the blow by its
own animation only, so each blow cost two casts and was charged for one. It beat Power Strike (White Knight) and Blast
(Paladin) on one target, and the Agent looped blow, recast.

**Who.** White Knight and Paladin with Charged Blow. In the 2026-11-24 copy of the live database 9 of 38 White Knights
have it (Lv 100-109; their build takes it last) and 2 of 6 Paladins lack Advanced Charge 10 (at 10 the charge is always
kept and nothing changes). Page has no charge; Dawn Warrior, Thunder Breaker and Aran share the buff stat but have no
skill that uses it up. The earlier note "with a low-level charge" (`COMBAT_POLICY_RESULTS.md`) was wrong: the charges
were high, Charged Blow was just above the alternative.

**Fix.** `AgentAttackPlanScoringPolicy.swingSeconds` adds the charge's own cast times the chance it is used up
(`AgentWeaponChargePolicy.spentChargeMs`); the learner's priors use the same seconds. No policy file changes.

**Arena** (check stack, fresh Agent per run, dummies, 16 pairs of 20 s per cell, damage per second of live play; before =
main line f2334c9e1, after = 9f42fecdf). The machine also ran another session's suite during part of before run 1.

| Job | Setting | Run | Before | After | Change (99 % interval) | Deaths before / after |
|---|---|---|---:|---:|---|---|
| White Knight 110 | single | 1 | 5097 | 7803 | +53.1 % (+44.4 .. +61.7) | 0 / 0 |
| White Knight 110 | single | 2 | 5126 | 7894 | +54.0 % (+44.8 .. +63.2) | 0 / 0 |
| White Knight 111 | pack | 1 | 11247 | 11368 | +1.1 % (-11.2 .. +13.4) | 0 / 0 |
| White Knight 111 | pack | 2 | 11332 | 12142 | +7.2 % (-2.7 .. +17.0) | 0 / 0 |
| Paladin 140 | single | 1 | 9160 | 14904 | +62.7 % (+42.9 .. +82.5) | 0 / 0 |
| Paladin 140 | single | 2 | 8305 | 14816 | +78.4 % (+66.0 .. +90.8) | 0 / 0 |
| Paladin 140 | pack | 1 | 15857 | 15879 | +0.1 % (-9.1 .. +9.4) | 0 / 0 |
| Paladin 140 | pack | 2 | 16425 | 15853 | -3.5 % (-10.6 .. +3.7) | 0 / 0 |

Casts on one target: Charged Blow 34-48 % of casts before (both jobs), at most 2 % after; warm-up buff casts 55-68 in
180 s before (15 of 16 warm-ups; the other, a Paladin's, took 5), 2-5 after. In the pack the White Knight's Charged Blow
share is nearly unchanged (35-39 % before, 35-37 % after).

Deaths are 0 because dummies do not hit back; they say nothing about live deaths.

**Against the staged policies** (the same candidates as `combat-policy-staging/arena`, on the fixed build, candidate
minus live play):

| Job | Setting | Run 1 | Run 2 |
|---|---|---|---|
| White Knight | single | +2.0 % (-4.7 .. +8.8) | +3.0 % (-4.3 .. +10.3) |
| White Knight | pack | -2.2 % (-14.7 .. +10.3) | -6.9 % (-15.5 .. +1.7) |
| Paladin | single | -6.7 % (-15.6 .. +2.2) | -5.1 % (-17.3 .. +7.0) |
| Paladin | pack | +12.2 % (+1.0 .. +23.3) | +11.8 % (-1.1 .. +24.6) |

The staged wins were single target only, and there the fixed rule is at parity or ahead in both runs. The Paladin's
candidate also leads in the pack by about 12 % (not confirmed: run 2's interval spans 0, and the same candidate lost
there by 17 % and 9 % in the first two arena runs on 2026-11-24). That setting was never staged.

**Watch after deploy:** the tell audit's death rate for warriors over 24 h against its baseline
(`tools/tells-report.ps1`). Revert: `git revert 9f42fecdf`.

**Suite:** full split suite on 9f42fecdf: tests=16934 failures=0 errors=0 skipped=33 result=PASS.

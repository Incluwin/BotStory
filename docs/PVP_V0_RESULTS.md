# PvP V0 probe results

The probes of `docs/PVP_PLAN.md` section 3, run 2026-11-08. A probe's row says PENDING until it has evidence. The tool
is `!pvp` (GM5, `client/command/commands/gm5/PvpCommand.java`, measurement only).

| Probe | Status | One line |
|---|---|---|
| V0.1 Own damage number | PENDING (needs the probe build live) | — |
| V0.2 Remote number without a mob | PENDING (needs the probe build live) | — |
| V0.3 Movement cadence | PENDING (needs the probe build live) | — |
| V0.4 Zero-target audit | PENDING (needs the probe build live) | — |
| V0.5 Calibration | DB half DONE; damage meter PENDING | `H_ref` ≈ 34·L^1.11, class-balanced |
| V0.6 Grind-minute table | DONE (EXP); meso partly | 13.7k EXP a minute in the 70s, 77.8k in the 100s |
| V0.7 WASM | PENDING | — |

## V0.5 Calibration: `H_ref` (database half)

- **Source:** live DB, 2026-11-08 ~17:15 EST, characters at Lv 30+ that are not beginners and not GMs (1,121 rows).
- **Method:** base max HP (`characters.maxhp`), median per class per 10-level band, then the mean of the class
  medians.
- **Why class-balanced:** the pooled median jumps from 4,808 to 9,606 between the Lv 90s and the Lv 100s only because
  warriors are 42 of the 81 citizens in the 100s.

| Band | n | `H_ref` | Warrior | Magician | Bowman | Thief | Pirate |
|---|---|---|---|---|---|---|---|
| 30s | 36 | 1,745 | 2,296 | 1,058 | 1,588 | 1,734 | 2,051 |
| 40s | 64 | 2,137 | 2,566 | 1,238 | 2,006 | 2,082 | 2,793 |
| 50s | 135 | 2,781 | 4,526 | 1,438 | 2,386 | 2,410 | 3,144 |
| 60s | 162 | 3,187 | 5,504 | 1,638 | 2,842 | 2,804 | 3,146 |
| 70s | 180 | 4,191 | 7,226 | 2,158 | 3,520 | 3,596 | 4,453 |
| 80s | 160 | 4,571 | 8,076 | 2,328 | 3,938 | 4,014 | 4,499 |
| 90s | 110 | 5,153 | 9,181 | 2,568 | 4,318 | 4,714 | 4,982 |
| 100s | 81 | 5,575 | 10,031 | 2,778 | 4,660 | 5,066 (n=1) | 5,339 |
| 110s | 46 | 6,169 | 10,966 | 3,028 | 4,945 | — | 5,738 |
| 120s | 16 | 7,133 | 13,061 | 3,698 | 5,975 | 6,377 | 6,556 |

- **Fit:** `H_ref(L) ≈ 34 · L^1.11`, within about 5% from Lv 30 to 129.
  - Above Lv 130 there are fewer than 20 characters per band, so the model uses the fit there.
  - The Lv 70s value (4,191) matches the plan's worked example (4,183).
- **Two things the in-server `!pvp calibrate` will fix:**
  - It reads base max HP, so equipment HP is not counted.
  - It covers loaded characters only.
- **Gear base and sustained damage** come from `!pvp calibrate` and `!pvp meter` once the probe build is live.
  - The meter takes each attack's best single target, credited over the time to the same character's next attack, and
    skips gaps over 2.5 s.
  - It needs about an hour of citizens grinding.
  - Known undercount: summon damage does not pass `applyAttack`.

## V0.6 Grind-minute table

- **Source:** `companion_yield` (`BenchYieldModel`): 21,000 measured citizen and companion field hours, weighted by
  samples.
- One grind-minute (GMin) is the EXP column divided by 60.

| Band | Sample hours | EXP/h | GMin EXP | Hours per level (start of band) | Mob mesos/h |
|---|---|---|---|---|---|
| 30s | 1,330 | 60,024 | 1,000 | 1.1 | 8,916 |
| 40s | 1,257 | 217,747 | 3,629 | 1.1 | 34,125 |
| 50s | 2,905 | 273,833 | 4,564 | 2.1 | 32,453 |
| 60s | 2,426 | 567,407 | 9,457 | 1.7 | 55,117 |
| 70s | 3,157 | 824,089 | 13,735 | 2.0 | 62,879 |
| 80s | 4,387 | 1,162,078 | 19,368 | 2.4 | 30,302 |
| 90s | 1,870 | 3,327,622 | 55,460 | 1.4 | 13,736 |
| 100s | 1,842 | 4,668,202 | 77,803 | 1.7 | 4,739 |
| 110s | 963 | 7,123,860 | 118,731 | 2.0 | 5,087 |
| 120s | 126 | 8,253,852 | 137,564 | 2.9 | 0 |

- **EXP is usable as it stands.**
  - The plan's daily cap of 110 GMin is about 0.7 of a level at Lv 75, and about 0.8 of a level at Lv 105.
  - A rival win (20 GMin) at Lv 75 is 275k EXP, or 13% of a level.
- **Mesos are not usable as measured.**
  - The column counts picked-up mob mesos only.
  - It collapses above Lv 80. That points to a measurement gap (high-level citizens not picking up, or not booking),
    not to real income.
  - Citizens' NPC sales of loot are 4.4x their mob mesos across the whole server (`economy_flow_daily`, 2026-11-07:
    MOB_DROP 222.7 M, NPC_SALE 910.5 M).
  - For the stake, the model uses the larger of `5 × measured` and the bench prior `1,782 · L` per hour.
  - Follow-up, not a PvP item: why `meso_per_hour` falls to about 0 at Lv 100+.

## PD12: tournament time

The server clock is EST (-0500). The plan's default is Saturday 20:00 server time unless the user names another evening.

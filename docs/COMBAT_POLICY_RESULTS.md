# Combat policy comparisons (I1.1, 2026-11-23 and 24)

How it is measured and judged: `COMBAT_POLICY_PIPELINE.md`. Raw readings and staged files: `combat-policy-staging/`.
**Nothing is adopted.** Most rows end "not enough data": the interval spans 0 and reaches past +3 %, that is, no
difference was found that 24 pairs could show. Deaths are the Agent found dead in any trial of either side.

## The three jobs the user approved (2026-11-24): all held

| Job | Won in | First run | Independent second run | Other setting (first; second) | Decision |
|---|---|---|---|---|---|
| 212 FP Arch Mage | pack | +32.7 % (16 pairs) | **-40.3 %, priors won** | single +0.0 %; +2.1 % | held: condition 1 fails |
| 321 Sniper | pack | +7.4 % (16 pairs) | **+0.5 %, no difference** | single -3.6 %; +1.6 % | held: condition 1 fails |
| 521 Outlaw | single | +5.7 % (24 pairs) | **-21.0 %, no win** | pack -5.5 %; -13.1 % (priors won) | held: conditions 1 and 2 fail |

With about 75 rows at 99 % per look, one to three lucky wins were to be expected; the second run is what tells.

## Confirmed in both runs (not approved by anyone yet)

White Knight 121 and Paladin 122 on one target: about +75 % in both runs. **The cause is a defect of the live rules, not
a clever table:** with a low-level charge, live play recasts the charge every few seconds and Charged Blow spends it;
the candidate hardly uses Charged Blow. Fixing that rule helps every White Knight and Paladin at once and is the better
change. If a policy is wanted meanwhile (revert: delete the file):

```powershell
Copy-Item "docs/combat-policy-staging/arena/121.json" "scripts/config/agents/combat-policy/121.json"
Copy-Item "docs/combat-policy-staging/arena/122.json" "scripts/config/agents/combat-policy/122.json"
```

`bench/121.json` (both settings, learned by bench grinders) also confirmed: single +66.9 %, pack +17.0 % in the second run.
After any adoption watch the tell audit's warrior death rate for 24 h against its baseline (`tools/tells-report.ps1`).

## Arena learning, 180 s a setting, then measured (43 Explorer jobs and Beginner)

| Job | Setting | Decisions | First run: candidate vs live dps (99 % interval) | Independent second run | Verdict |
|---|---|---:|---|---|---|
| 0 BEGINNER | single | 206 | 60 vs 61, -1.9 % (-9.2 .. +5.4), n=24, deaths 0 | - | not enough data |
| 0 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 100 WARRIOR | single | 135563 | 533 vs 545, -2.2 % (-9.4 .. +5.0), n=24, deaths 0 | - | not enough data |
| 100 WARRIOR | multi | 191 | 771 vs 806, -4.4 % (-12.0 .. +3.2), n=24, deaths 0 | - | not enough data |
| 110 FIGHTER | single | 166 | 4289 vs 4276, +0.3 % (-5.9 .. +6.5), n=24, deaths 0 | - | not enough data |
| 110 FIGHTER | multi | 168 | 5475 vs 5444, +0.6 % (-6.0 .. +7.1), n=24, deaths 0 | - | not enough data |
| 111 CRUSADER | single | 157 | 25015 vs 24025, +4.1 % (-5.4 .. +13.7), n=24, deaths 0 | - | not enough data |
| 111 CRUSADER | multi | 149 | 53909 vs 51625, +4.4 % (-6.2 .. +15.1), n=24, deaths 0 | - | not enough data |
| 112 HERO | single | 195 | 41331 vs 41849, -1.2 % (-10.6 .. +8.1), n=24, deaths 0 | - | not enough data |
| 112 HERO | multi | 176 | 73186 vs 85346, -14.2 % (-30.2 .. +1.7), n=16, deaths 0 | - | priors won |
| 120 PAGE | single | 197 | 1920 vs 1868, +2.8 % (-3.9 .. +9.5), n=24, deaths 0 | - | not enough data |
| 120 PAGE | multi | 176 | 3390 vs 3353, +1.1 % (-4.7 .. +6.9), n=24, deaths 0 | - | not enough data |
| 121 WHITEKNIGHT | single | 140 | 7064 vs 4041, +74.8 % (+49.6 .. +100.0), n=8, deaths 0 | 8290 vs 4776, +73.6 % (+60.9 .. +86.3), n=16, deaths 0 | **candidate wins, confirmed** |
| 121 WHITEKNIGHT | multi | 112 | 8606 vs 8525, +0.9 % (-7.4 .. +9.3), n=24, deaths 0 | 10587 vs 10850, -2.4 % (-11.5 .. +6.7), n=24, deaths 0 | not enough data; second run: not enough data |
| 122 PALADIN | single | 138 | 14900 vs 8756, +70.2 % (+43.2 .. +97.1), n=8, deaths 0 | 16316 vs 9261, +76.2 % (+56.5 .. +95.8), n=16, deaths 0 | **candidate wins, confirmed** |
| 122 PALADIN | multi | 121 | 14361 vs 17219, -16.6 % (-24.5 .. -8.7), n=8, deaths 0 | 16658 vs 18298, -9.0 % (-16.3 .. -1.6), n=8, deaths 0 | priors won; second run: priors won |
| 130 SPEARMAN | single | 203 | 1796 vs 1816, -1.1 % (-9.9 .. +7.7), n=24, deaths 0 | - | not enough data |
| 130 SPEARMAN | multi | 197 | 3570 vs 3616, -1.3 % (-8.8 .. +6.2), n=24, deaths 0 | - | not enough data |
| 131 DRAGONKNIGHT | single | 155 | 15887 vs 15577, +2.0 % (-4.3 .. +8.3), n=24, deaths 0 | - | not enough data |
| 131 DRAGONKNIGHT | multi | 152 | 48768 vs 49833, -2.1 % (-6.5 .. +2.2), n=24, deaths 0 | - | priors won |
| 132 DARKKNIGHT | single | 157 | 22012 vs 21726, +1.3 % (-4.5 .. +7.2), n=24, deaths 0 | - | not enough data |
| 132 DARKKNIGHT | multi | 130 | 66752 vs 66912, -0.2 % (-7.7 .. +7.2), n=24, deaths 0 | - | not enough data |
| 200 MAGICIAN | single | 167 | 476 vs 463, +2.8 % (-2.5 .. +8.1), n=24, deaths 0 | - | not enough data |
| 200 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 210 FP_WIZARD | single | 163 | 792 vs 1212, -34.6 % (-47.2 .. -22.1), n=8, deaths 0 | - | priors won |
| 210 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 211 FP_MAGE | single | 164 | 2857 vs 2819, +1.4 % (-7.9 .. +10.7), n=24, deaths 0 | - | not enough data |
| 211 FP_MAGE | multi | 156 | 13285 vs 13122, +1.2 % (-9.5 .. +12.0), n=24, deaths 0 | - | not enough data |
| 212 FP_ARCHMAGE | single | 141 | 10818 vs 10813, +0.0 % (-8.8 .. +8.9), n=24, deaths 0 | 14394 vs 14091, +2.1 % (-6.9 .. +11.2), n=24, deaths 0 | not enough data; second run: not enough data |
| 212 FP_ARCHMAGE | multi | 104 | 28873 vs 21752, +32.7 % (+5.1 .. +60.4), n=16, deaths 0 | 39354 vs 65956, -40.3 % (-58.8 .. -21.9), n=8, deaths 0 | **not confirmed: priors won** |
| 220 IL_WIZARD | single | 171 | 827 vs 1023, -19.2 % (-27.7 .. -10.6), n=8, deaths 0 | - | priors won |
| 220 IL_WIZARD | multi | 154 | 2893 vs 2932, -1.3 % (-8.2 .. +5.5), n=24, deaths 0 | - | not enough data |
| 221 IL_MAGE | single | 196 | 3225 vs 3309, -2.5 % (-14.7 .. +9.6), n=24, deaths 0 | - | not enough data |
| 221 IL_MAGE | multi | 153 | 7120 vs 9091, -21.7 % (-35.0 .. -8.3), n=8, deaths 0 | - | priors won |
| 222 IL_ARCHMAGE | single | 130 | 10122 vs 10732, -5.7 % (-20.5 .. +9.1), n=24, deaths 0 | - | not enough data |
| 222 IL_ARCHMAGE | multi | 91 | 42337 vs 45720, -7.4 % (-23.3 .. +8.5), n=24, deaths 0 | - | not enough data |
| 230 CLERIC | single | 157 | 774 vs 787, -1.6 % (-6.9 .. +3.6), n=24, deaths 0 | - | not enough data |
| 230 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 231 PRIEST | single | 152 | 4395 vs 4385, +0.2 % (-9.0 .. +9.5), n=24, deaths 0 | - | not enough data |
| 231 PRIEST | multi | 130 | 16627 vs 16821, -1.2 % (-12.8 .. +10.5), n=24, deaths 0 | - | not enough data |
| 232 BISHOP | single | 118 | 6921 vs 6673, +3.7 % (-5.6 .. +13.0), n=24, deaths 0 | - | not enough data |
| 232 BISHOP | multi | 88 | 24384 vs 31828, -23.4 % (-45.1 .. -1.7), n=8, deaths 0 | - | priors won |
| 300 BOWMAN | single | 201 | 469 vs 487, -3.7 % (-14.0 .. +6.6), n=24, deaths 0 | - | not enough data |
| 300 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 310 HUNTER | single | 211 | 2177 vs 2196, -0.9 % (-7.5 .. +5.7), n=24, deaths 0 | - | not enough data |
| 310 HUNTER | multi | 196 | 5195 vs 5243, -0.9 % (-9.1 .. +7.3), n=24, deaths 0 | - | not enough data |
| 311 RANGER | single | 205 | 12412 vs 12578, -1.3 % (-6.2 .. +3.6), n=24, deaths 0 | - | not enough data |
| 311 RANGER | multi | 192 | 10874 vs 13285, -18.1 % (-31.9 .. -4.4), n=8, deaths 0 | - | priors won |
| 312 BOWMASTER | single | 193 | 33503 vs 31789, +5.4 % (-7.1 .. +17.8), n=24, deaths 0 | - | not enough data |
| 312 BOWMASTER | multi | 173 | 31716 vs 31625, +0.3 % (-8.5 .. +9.1), n=24, deaths 0 | - | not enough data |
| 320 CROSSBOWMAN | single | 212 | 2037 vs 2075, -1.8 % (-9.5 .. +5.8), n=24, deaths 0 | - | not enough data |
| 320 CROSSBOWMAN | multi | 193 | 7129 vs 7031, +1.4 % (-7.2 .. +9.9), n=24, deaths 0 | - | not enough data |
| 321 SNIPER | single | 215 | 17664 vs 18324, -3.6 % (-9.9 .. +2.7), n=16, deaths 0 | 24158 vs 23769, +1.6 % (-5.4 .. +8.7), n=24, deaths 0 | priors won; second run: not enough data |
| 321 SNIPER | multi | 194 | 19414 vs 18072, +7.4 % (+0.4 .. +14.5), n=16, deaths 0 | 23234 vs 23126, +0.5 % (-5.3 .. +6.2), n=24, deaths 0 | **not confirmed: not enough data** |
| 322 MARKSMAN | single | 204 | 48002 vs 49115, -2.3 % (-12.9 .. +8.4), n=24, deaths 0 | - | not enough data |
| 322 MARKSMAN | multi | 176 | 36945 vs 49547, -25.4 % (-41.5 .. -9.4), n=16, deaths 0 | - | priors won |
| 400 THIEF | single | 193 | 688 vs 707, -2.7 % (-7.9 .. +2.4), n=16, deaths 0 | - | priors won |
| 400 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 410 ASSASSIN | single | 246 | 2504 vs 2494, +0.4 % (-6.3 .. +7.1), n=24, deaths 0 | - | not enough data |
| 410 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 411 HERMIT | single | 242 | 8258 vs 8028, +2.9 % (-4.7 .. +10.4), n=24, deaths 0 | - | not enough data |
| 411 HERMIT | multi | 199 | 20325 vs 19771, +2.8 % (-3.5 .. +9.1), n=24, deaths 0 | - | not enough data |
| 412 NIGHTLORD | single | 235 | 22827 vs 23554, -3.1 % (-13.2 .. +7.1), n=24, deaths 0 | - | not enough data |
| 412 NIGHTLORD | multi | 214 | 50725 vs 51056, -0.6 % (-10.4 .. +9.1), n=24, deaths 0 | - | not enough data |
| 420 BANDIT | single | 207 | 3482 vs 3419, +1.9 % (-3.0 .. +6.7), n=24, deaths 0 | - | not enough data |
| 420 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 421 CHIEFBANDIT | single | 204 | 8012 vs 8035, -0.3 % (-3.7 .. +3.2), n=24, deaths 0 | - | not enough data |
| 421 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 422 SHADOWER | single | 193 | 44709 vs 44549, +0.4 % (-9.5 .. +10.2), n=24, deaths 0 | - | not enough data |
| 422 SHADOWER | multi | 198 | 96944 vs 106779, -9.2 % (-20.3 .. +1.9), n=16, deaths 0 | - | priors won |
| 500 PIRATE | single | 190 | 224 vs 228, -1.9 % (-8.3 .. +4.4), n=24, deaths 0 | - | not enough data |
| 500 PIRATE | multi | 153 | 276 vs 268, +2.8 % (-3.0 .. +8.7), n=24, deaths 0 | - | not enough data |
| 510 BRAWLER | single | 195 | 1112 vs 1126, -1.3 % (-5.2 .. +2.7), n=16, deaths 0 | - | priors won |
| 510 BRAWLER | multi | 178 | 1326 vs 2013, -34.1 % (-53.9 .. -14.4), n=8, deaths 0 | - | priors won |
| 511 MARAUDER | single | 136 | 8817 vs 10846, -18.7 % (-36.9 .. -0.5), n=16, deaths 0 | - | priors won |
| 511 MARAUDER | multi | 104 | 19031 vs 26985, -29.5 % (-55.6 .. -3.4), n=24, deaths 0 | - | priors won |
| 512 BUCCANEER | single | 127 | 26414 vs 29004, -8.9 % (-22.4 .. +4.6), n=24, deaths 0 | - | not enough data |
| 512 BUCCANEER | multi | 106 | 42343 vs 60798, -30.4 % (-55.8 .. -4.9), n=16, deaths 0 | - | priors won |
| 520 GUNSLINGER | single | 293 | 1466 vs 1452, +1.0 % (-2.6 .. +4.6), n=24, deaths 0 | - | not enough data |
| 520 GUNSLINGER | multi | 209 | 3510 vs 3663, -4.2 % (-15.1 .. +6.7), n=24, deaths 0 | - | not enough data |
| 521 OUTLAW | single | 208 | 10109 vs 9560, +5.7 % (+1.2 .. +10.3), n=24, deaths 0 | 11381 vs 14407, -21.0 % (-60.2 .. +18.2), n=24, deaths 0 | **not confirmed: not enough data** |
| 521 OUTLAW | multi | 207 | 14988 vs 15853, -5.5 % (-25.1 .. +14.2), n=24, deaths 0 | 24033 vs 27649, -13.1 % (-28.7 .. +2.6), n=8, deaths 0 | not enough data; second run: priors won |
| 522 CORSAIR | single | 235 | 99740 vs 107721, -7.4 % (-19.4 .. +4.6), n=24, deaths 0 | - | not enough data |
| 522 CORSAIR | multi | 182 | 103965 vs 107936, -3.7 % (-11.1 .. +3.7), n=24, deaths 0 | - | not enough data |

## Bench candidates as the live server had them on 2026-11-23

| Job | Setting | Decisions | First run: candidate vs live dps (99 % interval) | Independent second run | Verdict |
|---|---|---:|---|---|---|
| 0 BEGINNER | single | 39618 | 55 vs 52, +6.5 % (-1.5 .. +14.5), n=24, deaths 0 | - | not enough data |
| 0 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 100 WARRIOR | single | 135563 | 415 vs 344, +20.4 % (+10.4 .. +30.4), n=8, deaths 0 | 676 vs 686, -1.4 % (-11.2 .. +8.4), n=24, deaths 0 | not enough data; second run: not enough data |
| 100 WARRIOR | multi | 59051 | 533 vs 450, +18.5 % (+2.0 .. +34.9), n=8, deaths 0 | 1603 vs 1570, +2.1 % (-4.6 .. +8.8), n=24, deaths 0 | not enough data; second run: not enough data |
| 110 FIGHTER | single | 3646 | 2629 vs 2643, -0.5 % (-6.7 .. +5.7), n=24, deaths 0 | - | not enough data |
| 110 FIGHTER | multi | 44419 | 4729 vs 4789, -1.3 % (-5.9 .. +3.4), n=24, deaths 0 | - | not enough data |
| 111 CRUSADER | single | 27559 | 13262 vs 24264, -45.3 % (-57.9 .. -32.8), n=8, deaths 0 | - | priors won |
| 111 CRUSADER | multi | 72414 | 18699 vs 47946, -61.0 % (-67.7 .. -54.3), n=8, deaths 0 | - | priors won |
| 120 PAGE | single | 10439 | 1674 vs 1631, +2.6 % (-5.0 .. +10.3), n=24, deaths 0 | - | not enough data |
| 120 PAGE | multi | 34675 | 2107 vs 2629, -19.8 % (-39.1 .. -0.6), n=8, deaths 0 | - | priors won |
| 121 WHITEKNIGHT | single | 4738 | 7454 vs 3734, +99.6 % (+72.5 .. +126.8), n=8, deaths 0 | 8834 vs 5292, +66.9 % (+55.3 .. +78.6), n=16, deaths 0 | **candidate wins, confirmed** |
| 121 WHITEKNIGHT | multi | 9915 | 8801 vs 6016, +46.3 % (+29.7 .. +62.8), n=8, deaths 0 | 13532 vs 11564, +17.0 % (+3.1 .. +30.9), n=16, deaths 0 | **candidate wins, confirmed** |
| 130 SPEARMAN | single | 3744 | 1669 vs 1753, -4.8 % (-12.4 .. +2.8), n=24, deaths 0 | - | priors won |
| 130 SPEARMAN | multi | 103625 | 1824 vs 2228, -18.1 % (-27.5 .. -8.7), n=8, deaths 0 | - | priors won |
| 131 | single | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 131 DRAGONKNIGHT | multi | 11593 | 32994 vs 33221, -0.7 % (-15.4 .. +14.1), n=24, deaths 0 | - | not enough data |
| 132 | single | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 132 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 200 MAGICIAN | single | 452620 | 357 vs 375, -4.9 % (-11.2 .. +1.4), n=16, deaths 0 | - | priors won |
| 200 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 210 FP_WIZARD | single | 1214 | 1176 vs 1175, +0.1 % (-5.9 .. +6.0), n=24, deaths 0 | - | not enough data |
| 210 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 211 FP_MAGE | single | 1772 | 2728 vs 2738, -0.4 % (-8.3 .. +7.6), n=24, deaths 0 | - | not enough data |
| 211 FP_MAGE | multi | 798 | 11026 vs 10707, +3.0 % (-9.7 .. +15.7), n=24, deaths 0 | - | not enough data |
| 220 IL_WIZARD | single | 2492 | 990 vs 997, -0.8 % (-7.9 .. +6.4), n=24, deaths 0 | - | not enough data |
| 220 IL_WIZARD | multi | 40143 | 1876 vs 1810, +3.6 % (-11.7 .. +18.9), n=24, deaths 0 | - | not enough data |
| 230 CLERIC | single | 11095 | 770 vs 783, -1.7 % (-9.6 .. +6.3), n=24, deaths 0 | - | not enough data |
| 230 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 231 | single | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 231 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 310 HUNTER | single | 5285 | 1808 vs 1870, -3.3 % (-9.1 .. +2.5), n=16, deaths 0 | - | priors won |
| 310 HUNTER | multi | 14224 | 4368 vs 4024, +8.5 % (-2.8 .. +19.9), n=24, deaths 0 | - | not enough data |
| 400 THIEF | single | 123785 | 463 vs 484, -4.5 % (-11.5 .. +2.6), n=24, deaths 0 | - | priors won |
| 400 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 410 ASSASSIN | single | 63699 | 2139 vs 2194, -2.5 % (-9.0 .. +4.0), n=24, deaths 0 | - | not enough data |
| 410 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 421 | single | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 421 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 510 | single | 0 | - | - | not enough data (no pack skill, or too little learned) |
| 510 | multi | 0 | - | - | not enough data (no pack skill, or too little learned) |

# P7 Easy Balrog supplies: points standard, ammo, fight logs, no-healer warning

> Built 2026-10-08/09 on branch `fix/easy-balrog-supplies` (on the live tip 34fcd7d0b). Not deployed, not live-tested.
> Evidence: the lost Easy Balrog run of 2026-10-08 19:54-20:15 (LAA client, no crash).

## Result

The 2026-10-08 run was lost because every companion's HP potions ran out before Balrog's body died. This branch makes
companions carry enough for a 20-minute fight, logs what each member carries during the fight, and has a companion
warn when nobody in the party can Heal.

## Why the run was lost (diagnosis, verified by two skeptics per cause)

- Every party companion ended with 0 HP potions. Each death came 13-60 s after that.
- A member on the melee station took 5,200-7,400 HP a minute, a member on the ranged station 4,000-4,600.
- The body's damage rate after 50% fell to less than a third. Deaths, Ingrid's empty MP (no more Hyper Body) and
  knockback explain about two-thirds of the drop.
- Unconfirmed: Flint (gunner) ran out of bullets at the default 500. He did no damage from 20:01:39 with full MP.
- Isolde was refused as BANNED by a click in the expedition window, so nobody could heal.
- Gunnar died from the scripted `!hpmp Gunnar 0` step, not from low potions.
- Not a cause: the 7 s reflect pause. The body is immune during it.

## What changes (only where COMPANION_BOSS_ENABLED_TYPES lists easy_balrog)

1. **Ammo standard.** Companions and bench allies with a claw, bow, crossbow or gun carry 2,000 projectiles for Easy
   Balrog (was 500). `AMMO_STANDARDS: 'lpq=1600,epq=1600,easy_balrog=2000'`.
2. **Potion standard in points.** New `AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS: 'easy_balrog=130000/32000'`.
   A companion counts the HP and MP its potions restore, not how many it carries. At a store it buys the shop's best
   potion until it reaches the standard: about 130 Unagi and 40 Pure Water at Ant Tunnel Park, about 215,000 mesos.
   One visit reaches the standard. Its top-up then buys toward 200% with what is left.
   `BOSS_STANDARDS easy_balrog=100/60` stays in the file. It applies again only if the points key is removed.
3. **No-healer warning.** When nobody who goes in can cast Heal (the owner, another human of the party, or a party
   companion on Mu Young's list), one party companion says one line in party chat before Start:
   "no heals this run? i'll be chugging pots.", "nobody's healing, so watch your hp out there.",
   "no priest with us. keep your pots handy." or "no healer this time. pot early, ok?". It never blocks anything.
   A Heal user who is refused or withdraws later still triggers it. Bench allies never count and never say it.
4. **Fight logs** (server log only):
   - The 30 s vitals line adds `pots=H/M`, and for shooters `ammo=N noAmmo=B` (` SA` while Soul Arrow or Shadow Claw
     is up), and the decision age `age=Nms`.
   - `Easy Balrog body status phase= body= hp=cur/max (pct%) reflect=` every 30 s.
   - `Agent out of ammo in a run: agent= id= map= weapon=` once when a member runs dry in a run.
   - `Boss run member down` ends with the dead member's `pots=` and `ammo=`.

KPQ, HPQ, LPQ, EPQ and every key not listed keep counting potions exactly as before. The desk's count overload never
reads the points key. Human players' own characters do not change.

## How it is built

- `AgentSupplyReadiness.recoveryStandard` / `shortfall(Character, Activity)` measure
  `AgentPotionInventoryPolicy.recoveryCapacity` against the points key.
- `AgentSupplyReadinessRuntime` prices and slots a trip in the potion the counter really buys
  (`AgentShopService.coveragePickRecovery`, `recoveryFloorStack`), including the last-free-Use-slot case.
- `AgentShopService` readiness floor converts a points gap into a count of the pick.
- `AgentEngineConfigValidator` refuses a malformed `BOSS_RECOVERY_STANDARDS`.
- `AgentEasyBalrogScenario` vitals/body-status lines, `AgentSupplyLogFields`, `AgentCombatAmmoCheckRuntime` dry line,
  `AgentBossRunPorts.supplies` for the member-down line.
- `CompanionBossRuns` / `CompanionBossRun.noHealerSaid` / `BossRunLines` for the warning.
- Not changed: `AgentDeathTickCoordinator`, the route graphs, PQ desks, scripts (no knowledge file change).

## Tests

Each step was written failing-first with a mutation check and an independent audit. A three-lens review with two
skeptics per finding confirmed 6 defects (1 medium: the warning missed the live-run order where the Heal user is
refused after the signed-up line; 5 low). All are fixed with tests.

## Live check recipe (GM commands first)

Before you start: a restart disconnects everyone online. Tell other players first. Restart only with
`start-maple-stack -RestartServer`. Never use `!setlevel ... restore`. Do not click "ban" in the expedition window.

Setup:
1. Deploy the branch. Keep `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"`. Use the LAA client copy.
2. Start the memory logger (the PowerShell loop in the P7A crash notes).
3. Put Bossfriend's party together: Isolde (Priest), Thane, Ingrid, Sorrel and Flint, all Lv70. Call no bench allies.
4. Run `!givemeso <name> 400000` for each of the five companions.

Check A, the no-healer warning (about 5 min):
5. Bench Isolde. Run `!warp 105100100`. Talk to Mu Young, pick Easy, then "Lets get this going!".
6. Pick "My party will register too.". Expect exactly one no-healer line in party chat after the signed-up line.
7. Talk to Mu Young and pick "Stop the expedition.". Call Isolde back and wait until she arrives.

Check B, buying at Ant Tunnel Park:
8. Run `!warp 105070001` (Ant Tunnel Park). Wait about 1 min.
9. Expect each short companion to say its potion or ammo line and walk to the 24 Hr Mobile Store (NPC 1061001).
10. Read the "readiness restock" log lines. Expect Unagi and Pure Water bought toward 130,000 HP and 32,000 MP, and
    bullets toward 2,000 for Flint.

Check C, the full fight (do not fast-forward it: the fight length is what this tests):
11. Run `!warp 105100100`. Create the Easy expedition and register the party. Expect no no-healer line and no
    low-pots line.
12. Pick "Start the fight!". Do not run `!hpmp` on companions.
13. Every 30 s, read the vitals and `Easy Balrog body status` lines.
14. The check passes if all three hold:
    - the body casts skill 133 (`Boss server cast mob=8830007 ... skill=UNDEAD`), which needs it at 25% or less;
    - the body dies before any member's `pots=` reaches 0;
    - Flint shows no `Agent out of ammo in a run` line and no NO_CANDIDATES while `ammo=` is above 0.
15. After the run, read the `PQ potions ... pq=easy_balrog` line. Send the start and end counts to the bossing session
    to tune the standard.
16. If the client closes, note the time and read msexcr.ini.

If a check fails: redeploy the previous build.

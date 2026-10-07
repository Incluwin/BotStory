# P7 Part A: Easy Balrog with the owner's party companions

> Built 2026-10-07 on branch `feat/companion-bossing` (base fe436a04b). Plan: docs/BOT_MANAGEMENT_PLAN.md (P7, flow 4,
> flow 8). Later bosses: docs/COMPANION_BOSSING_PLAN.md. Not deployed, not live-tested yet.

## Result

An owner at Mu Young (105100100) can bring up to five of their own party companions into an Easy Balrog expedition.
The companions sign up through Mu Young's own window, fight beside the owner, take real Balrog hits, wait outside when
they go down, and follow the owner out after the run. Everything is off until `COMPANION_BOSS_ENABLED_TYPES` lists
`easy_balrog`.

## What the player sees (with the switch on)

- Mu Young's Easy windows offer "My party will register too." after the owner creates or joins the expedition, on
  "already registered", and in the leader's list. Her answer is "Very well. Have them come and speak with me before
  time runs out."
- Each companion walks up beside Mu Young and signs up. The stock "[Expedition] ... has joined" notice appears.
- One companion says "we're all signed up. ready when you are." (or a variant) when nobody is waiting any more.
- While a companion is signed up and inside, the server runs Balrog's moves and attacks for the whole fight, also for
  the humans (the Poison Golem mechanism). `COMPANION_BOSS_SERVER_AUTHORITY: false` returns to the stock choice.
- Companions use the Easy Balrog fight rules (claw, add and body phases, a stop while Balrog reflects). They never
  summon on the Balrog maps, because the map forbids summons for players (fieldLimit SUMMON).
- A dead companion revives at 105100100 after the normal 5 s, says one lower-case line, and waits for its owner.
- When no living human is left in the fight for 3 s, the companions leave through the exit NPC and one says
  "everyone's down... let's regroup outside."
- After the clear, companions follow the owner to the reward room and out by its portal. They pick up nothing there.
- Inside a boss run, companions leave boss drops to the humans. The card text "Leave boss drops to you outside boss
  runs" now reads "Leave boss drops to you except in party runs". This text changes at deploy even with the switch off.
- A companion short of 40 HP / 50 MP potions at 105100100 says the no-shop line once. No shop is reachable from there
  (the room has no outgoing route edge). See open decision 1.

## Config

`config.yaml` (living_world, appended):
- `COMPANION_BOSS_ENABLED_TYPES: ""`. The kill switch. `"easy_balrog"` turns Part A on. Validated against
  `config.CompanionBossKeys`.
- `COMPANION_BOSS_SERVER_AUTHORITY: true`.

`agent-engine.yaml` (appended, each with Values/Purpose/Technical lines):
- `AgentSupplyReadiness.BOSS_STANDARDS: 'easy_balrog=40/50'` (user decision 8). `config.AgentEngineConfigValidator`
  allowlists it as a non-numeric value.
- `AgentBossRunService.MONITOR_MS 500`, `AgentBossRunSession.REGISTRATION_MARGIN_MS 60000`, `VERDICT_WAIT_MS 12000`,
  `NPC_REACH_PX 200`, `WALK_TIMEOUT_MS 30000`, `START_GRACE_MS 5000`, `HUMAN_GONE_GRACE_MS 3000`, `EXIT_REACH_PX 120`,
  `EXIT_TIMEOUT_MS 30000`.

No database change. Changeset 47 stays free.

## How it is built

- Agent side, new package `server.agents.capabilities.expedition.boss`: `AgentBossRunSession` (phases REGISTERING,
  STARTING, FIGHTING, POST_CLEAR, ENDED), `AgentBossRunService` (enlist, monitor on the Agent scheduler, handTo),
  `AgentBossRunRegistry`, `AgentBossRunMember`, `AgentBossRunPorts` (live adapters), `AgentBossRunHost` (the rule
  living installs), `AgentBossRunCatalog` / `AgentBossRunDefinition` (Easy Balrog row, a slot for Zakum).
  `AgentEasyBalrogScenario.forCompanions()` is the fight with no fixture, no failure on death and no disconnect.
- Living side: `CompanionBossRuns` (Mu Young offer, enlist on companion-ops, commitments `boss:<key>:<session>:<owner>`,
  lines, run records, lost-run sweep, logout delegation), `CompanionBossRun`, `BossEntrance` (spots beside Mu Young),
  `BossRunLines`.
- Core: `EasyBalrogEncounterService.installServerAuthorityRule` and
  `ServerMobAutonomyService.registerEncounterActor(monster, key, forceServer)`.
- Not changed: `AgentDeathTickCoordinator` (the stock respawn already lands on 105100100), `AgentExpeditionLobbyService`
  (the GM `!balrogtest` harness), `EventInstanceManager`, `BalrogBattle_Easy.js`, KPQ/HPQ/LPQ/EPQ code.

Shared files touched (small appends or narrow edits): config.yaml, agent-engine.yaml, LivingWorldConfig,
AgentEngineConfigValidator, AgentSupplyReadiness (`partyQuestStandard` reads BOSS_STANDARDS after
PARTY_QUEST_STANDARDS), AgentLootPolicy, AgentCombatSummonRuntime, ServerMobAutonomyService,
EasyBalrogEncounterService, LivingWorld, LivingWorldHooks, RosterService (one clause in ownerEnteredMap),
CompanionRestock, CompanionAuditor, CompanionFollowGuard, CompanionSettingsApplier, CompanionCardScreens,
scripts/npc/1061014.js.

## Tests

- Every build step was written failing-first, with one mutation check by the builder and one by an independent auditor.
- A 6-lens review with 3 skeptics per finding confirmed 3 defects. All are fixed, each with failing-first tests:
  re-seat at adoption (a companion parked and reloaded during sign-up), a logged-out anchor after the clear waits for the
  handoff, varied refusal lines. A fourth fix hands companions back when a run ends on an internal error.
- Full suite: see the hand-over message for the final count. The expected failure is
  `KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed`, because 1061014.js changed and
  scripts/config/knowledge/server-knowledge.json is left to the integrator (run tools\knowledge-export.bat after the
  merge). `KnowledgeFileFreshnessTest` passes.

## Open decisions (asked 2026-10-07)

1. Potions before Easy Balrog: (a) Lv50+ companions keep 40/50 in every town with a shop (recommended), (b) a
   Sleepywood-Henesys trip, (c) only the no-shop line (what Part A ships).
2. Levelling ohboy's companions for the two-owner check: `!setgmlevel ohboy 6` on test days, `0` before each check.
3. Boss clears stop counting as party-quest clears (today: PQ records named like "Balrogundefined").

## Live check recipe (GM commands first)

Before you start: a restart disconnects everyone online. Tell other players first. Restart only with
`start-maple-stack -RestartServer`.

Setup:
1. Deploy with `COMPANION_BOSS_ENABLED_TYPES: ""`. On Bossfriend, talk to Mu Young and pick Easy. The windows must be
   stock.
2. Set `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"` and restart.
3. Check Thane's arc (`thane-last-descent`) and the contracts of Isolde, Sorrel, Ingrid, Flint and Thane before you
   grant them.
4. On Bossfriend (GM6), run `!grantcompanion isolde Bossfriend`. Repeat for `thane`, `sorrel`, `ingrid` and `flint`.
5. Call all five into your party.
6. Run `!setlevel me 70 111 auto` (any 3rd-job id).
7. Run `!setlevel Isolde 70 231`, `!setlevel Thane 70 111`, `!setlevel Sorrel 70 321`, `!setlevel Ingrid 70 131` and
   `!setlevel Flint 70 521`. Do not add `auto` (it is refused for companions). Never use `restore`.
8. For Isolde, Thane, Ingrid and Flint, run `!giveitem <name> 2000002 60` and `!giveitem <name> 2000003 60`.
9. Run `!giveitem Sorrel 2000002 10`, so Sorrel is short.
10. Run `!giveitem Sorrel 2061000 2000` and `!giveitem Flint 2330000 2000`.
11. Run `!warp 105100100`.

Run 1, the main path:
12. Expect Sorrel's no-shop line once.
13. Talk to Mu Young, pick Easy, then "Lets get this going!". Pick "My party will register too.".
14. Expect five walk-ups beside her, five "[Expedition] ... has joined" notices and one "signed up" line.
    `!expeditions` lists 6 members.
15. Pick "Start the fight!". The log shows `Easy Balrog encounter started ... serverAuthorityForced=true` and
    "Server mob autonomy acquired mob=8830009", and no "Boss native controller selected".
16. Expect companions to lose HP and drink. Expect them to stop while Balrog reflects.
17. Check that Isolde (Summon Dragon) and Sorrel (Golden Eagle) never summon on 105100400.
18. Watch whether Bossfriend takes body attack1 hits outside about x -400..400. Report it either way: under server
    authority this 9-of-9 attack hits only its base box (BossAction.hasDistributedRegions).
19. Run `!hpmp Thane 0`. After about 5 s Thane is on 105100100, says one down line and stays in the party.
20. Clear Balrog and talk to 1061018. In the reward room the companions pick up nothing.
21. Leave by the portal. Within 30 s the companions are out and following you. Thane joins them. One says "gg".
22. Read the database: `companion_performance` rows with activity `easy_balrog` (4 cleared, Thane tapped_out),
    `friend.boss` rows in `living_event_log`, and the log line "PQ potions: ... pq=easy_balrog".

Run 2, no human left:
23. Register and start again. Run `!hpmp 0` (it sets your own HP and MP) and do not press OK.
24. About 3 s later the companions talk to 1061018 and appear on 105100100. One says the regroup line.
25. Press OK. They follow you.

Run 3, late sign-up:
26. Pick the option and press Start at once. Expect "couldn't make it" or "not gonna make it" lines, worded
    differently per companion. The signed-up companions fight.

Run 4, logout with no heir:
27. Log out inside the fight. The companions park.
28. Log back in. They come back.

Run 5, handoff (needs ohboy; open decision 2):
29. From Bossfriend, run `!setlevel ohboy 70 <job> auto`.
30. ohboy joins Bossfriend's party (2 humans, 4 companions). Both register and start.
31. Bossfriend logs out during the fight. One companion says "i'll see this through with you, ohboy."
32. Repeat with the logout after the clear, in the reward room. The companions stay with ohboy.
33. They leave when ohboy leaves, and park.

Run 6, authority off:
34. Set `COMPANION_BOSS_SERVER_AUTHORITY: false` and restart (warn players first).
35. The log shows `serverAuthorityForced=false`. Bots take touch damage only.

Regression:
36. `!balrogtest quick` still clears, and its start line says `serverAuthorityForced=false`.
37. One KPQ desk run and one HPQ desk run with companions still work.

If a check fails: set `COMPANION_BOSS_ENABLED_TYPES: ""` and restart.

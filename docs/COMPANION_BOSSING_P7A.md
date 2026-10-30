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
- A companion short of 100 HP / 60 MP potions at 105100100 says the no-shop line once (40/50 until fix L6). No shop is reachable from there
  (the room has no outgoing route edge). See open decision 1.

## Config

`config.yaml` (living_world, appended):
- `COMPANION_BOSS_ENABLED_TYPES: ""`. The kill switch. `"easy_balrog"` turns Part A on. Validated against
  `config.CompanionBossKeys`.
- `COMPANION_BOSS_SERVER_AUTHORITY: true`.

`agent-engine.yaml` (appended, each with Values/Purpose/Technical lines):
- `AgentSupplyReadiness.BOSS_STANDARDS: 'easy_balrog=100/60'` (user decision 8 set 40/50; fix L6 raised it after the
  first live run). `config.AgentEngineConfigValidator`
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

1. Potions before Easy Balrog (superseded by fix L7, the Ant Tunnel Park store): (a) Lv50+ companions keep the standard in every town with a shop, (b) a
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

## Live run 1 and fix L1-L7 (2026-10-07/08, branch fix/p7a-live-1)

Live run 1 (Bossfriend Lv70 + five Lv70 companions): the mechanics passed (no-shop line, walk-ups, sign-up line,
server authority, down line and wait outside, no summons). The fight was lost at Balrog 80%: each companion burned
20-36 HP potions a minute (White Potions) and died within 3-5 minutes. Runs 2 (no human left) and 4 (logout) passed.
The GM `!balrogtest` fixture never showed the potion cost, because it gives free Power Elixirs.

Fixes (each failing-first with a mutation check and an independent audit):
- L1: an Agent whose airborne tick is clamped by a fight boundary while crossing a floor now lands on it. Before, it
  fell through and was recovered below the map (Isolde twice at x -100). Also changes the GM fixture and EPQ stage 4,
  the only other users of a boundary.
- L2: ordinal 0 leaves the initial-claw staging once it stands within 14 px of its safe point (it idled about 60 s
  8 px short). Also changes the GM fixture.
- L3: a lost Easy Balrog fight (time-out, wipe, everyone gone) removes its parts without the kill path, so it no longer
  throws on a null leader or credits a clear. This bug was older than Part A and hit every Easy Balrog run.
- L4: an Agent's hit on a server-owned boss no longer raises DISTANCE_HACK or refreshes the boss position (472 alerts
  in run 1). Humans are unchanged.
- L5: Agents use the 10 gun-only pirate skills only while holding a gun (all Agents, all content). Flint holds a
  beginner sword (his gun loadout was never applied), so he now uses basic attacks until he gets a gun.
- L6: the Easy Balrog potion standard is 100 HP / 60 MP potions (a shop tops up toward 200/120).
- L7: when an owner with party companions walks through Ant Tunnel Park (105070001) while Easy Balrog is on, a Lv50+
  companion short of the standard buys at the 24 Hr Mobile Store (NPC 1061001: Unagi 1000 HP, Pure Water 800 MP) and
  catches up. The store is now in potion-supplier-catalog.json, so 29 Ant Tunnel and Drake maps now use it as their
  nearest supplier instead of Henesys.

Known limits: the standard counts potions, not HP, so 100 White Potions still pass; potion choice at 1061001 follows
the existing shop band rule (Unconfirmed live); whether Agents reach NPC 1061001 at (333,-248) is Unconfirmed live.

Extra live checks for the fix branch (GM-first, after the setup above):
1. Give Flint a gun: `!giveitem Flint 1492000 1` (or any gun) and check that he equips it, or note that he does not.
2. Give each companion mesos for potions (about 400k each), or check its purse first.
3. From Sleepywood, walk down through the Ant Tunnels with the five companions (no `!warp` past Ant Tunnel Park).
4. In Ant Tunnel Park, expect short companions to say a go line, buy at the 24 Hr Mobile Store and catch up.
5. Read the "PQ potions" line after the run and compare it with run 1.
6. Repeat Run 1 of the recipe. Expect no "below map bounds" line for 105100400, no DISTANCE_HACK alerts for the
   companions, Isolde grinding from the start, and Flint never using a gun skill without a gun.
7. Let one run end without a clear (everyone dies or leaves). Expect no "Runtime failure at
   EventInstanceManager#monsterKilled" error and no clear effect.

## Body cast crash: diagnostics and repro (2026-10-08, branch fix/p7a-live-2)

In live run 2, Bossfriend's client closed at 10:56:03, 1.7 s after the body (8830007) cast its first skill
(Physical/Magic Counter, skill 145 level 4). The cause is not proven. The lead (unconfirmed) is how the client handles
the cast packet: the MOVE_MONSTER packet that starts the skill1 animation and the Skill1 sound. The status packet that
applies the counter at impact is unlikely to be the trigger.

What the branch adds:
- D1, a log line at each server-owned skill impact: `Boss server impact mob= oid= skill= level= sinceCastMs=`. It
  shows when the status packet really goes out.
- D2, a log line once per (character, oid) when a client sends MOVE_LIFE for a mob that the server owns:
  `MOVE_LIFE for server-owned mob chr= mob= oid=`. It shows stale control (a human client that still thinks it
  controls the mob).
- Fix A: when the server owns the body, its activation sends a stop-control packet for the body to every human on the
  map who is not changing maps. The `Easy Balrog body activated` line gains `controlStops=<count>`.
- Fix B: the body's skill casts (145 and 133) send no action. The movement and the impact do not change.
- Visible change while Fix B is on (the default): the body's 145 and 133 casts play no skill1 animation and no Skill1
  sound. The status still applies at impact.
- The GM6 tool `!balrogprobe`.

`!balrogprobe` (GM6 only, each use is logged as `gm balrogprobe ...`):
- `!balrogprobe hp <percent> [mobId]` lowers each live, non-fake mob on your map (or only mobId) to
  max(1, floor(maxHp x percent / 100)). It never kills a mob and never raises HP. It prints one line per lowered mob.
  Clients see the new HP on the HP bar at the mob's next hit.
- `!balrogprobe telegraph action` makes the body's casts send the skill1 action again (Fix B off).
- `!balrogprobe telegraph none` sends the casts without the action (Fix B on).
- `!balrogprobe telegraph` prints the current value.
- The switch is not saved. A restart sets it back to `none`.

Warning: each arm below may close the human client. Each step changes only your own test server.

Before you start:
1. Deploy the branch. Restart only with `start-maple-stack -RestartServer`.
2. Use one human client (Bossfriend, GM6).
3. Filter the server log for `Boss server`, `Easy Balrog`, `Exception caught` and `MOVE_LIFE`.

Arm S, the status packet only (about 2 min, works on any build):
1. Go to a quiet field with a few ordinary mobs and no boss.
2. Type `!mobskill 140 12`. This is the control status. It already reached a human without a crash.
3. Wait 5 s.
4. Type `!mobskill 145 4`. This sends only the status packet, with no cast packet.
5. Watch the client for 10 s. The expected result is no exit.

Arm R, a server-owned body that was never fake, so no stale control (about 3 min):
1. Go to a quiet map with a wide flat floor and no Agent or other player, for example `!warp 180000000`.
2. Type `!hide`. A hidden player cannot control the body, so the body starts server-owned.
3. Type `!balrogprobe telegraph action`.
4. Type `!spawn 8830007`. Expect `Server mob autonomy acquired mob=8830007`.
5. If you see `Boss native controller selected`, stop and check that you are hidden.
6. Type `!balrogprobe hp 49 8830007`.
7. Type `!spawnbot <existing bot name>`. The body waits for a target, and a hidden player is not one.
8. Within about 1 s, expect `Boss server cast mob=8830007 ... skill=PHYSICAL_AND_MAGIC_COUNTER level=4`.
9. Watch the client for 10 s.
10. If the client exits, relaunch it and type `!hide`. Type `!killall`, then `!balrogprobe telegraph none`. Repeat
    steps 4-9.
11. If the client does not exit, wait 60 s for a second 145 cast. Then type `!balrogprobe hp 24 8830007` to get one
    133 cast.

Arm E, the real encounter, fast-forwarded (about 4 min):
1. Type `!balrogprobe telegraph action`.
2. Open the Easy Balrog companion run the same way as run 2. Stay in the party on map 105100400.
3. After `Easy Balrog encounter started`, type `!balrogprobe hp 1 8830009`.
4. After `Easy Balrog seal released` (60 s), type `!balrogprobe hp 1 8830008`.
5. Wait for `Easy Balrog body activated`. After that line, no D2 line may name the body's oid.
6. Type `!balrogprobe hp 49 8830007`. Expect the cast line within about 4.5 s (the action lock plus the pause).
7. Watch the client for 10 s.
8. If the client does not exit, repeat Arm R step 11.

What to watch:
- t0 is the `Boss server cast` line. t1 is the D1 `Boss server impact ... sinceCastMs=` line. t2 is
  `Exception caught by Bossfriend ... Connection reset`.
- If t2 comes before t1, the status packet never went out.
- On screen: does skill1 start, does a speech balloon appear, and does the client freeze for about 1 s before it
  exits?

How to read the results:
1. If Arm S exits, the status packet is the trigger. Remove 145 from the body's skills and fix the counter status
   encoding with a decode test.
2. If Arm R exits with `action` and passes with `none`, the cast packet's action is the trigger. Keep Fix B on. Keep
   Fix A as hygiene.
3. If Arm R exits with both values, compare t1 and t2. If the status packet went out first, go to item 1. Otherwise
   capture the packets the human client receives and rerun Arm E.
4. If Arm R and Arm E both pass for 3 casts with `action`, Fix A probably removed the trigger. Use `action` for normal
   play. Change the default only after one clean full run.
5. If Arm R passes but Arm E exits with `action`, a state that only the encounter has remains. Read the D2 lines, rerun
   Arm E with `none`, then capture the packets.

## Client crash: what the evidence shows (2026-10-08)

- The repro arms: Arm S (the counter status packet alone) and Arm R (a never-fake body casting 145 and 133 with the
  skill action) did not crash. Arm E crashed in the claw phase, after Fix A and Fix B were live.
- The two Windows crash dumps (Kaentake.dll+0x98b7) are NOT the fight crashes. They come from clients relaunched after
  the disconnect, and the fault is a second crash at process exit: Kaentake's static item-effect map
  (kaentake-questcolor itemeff.cpp:21) releases PCOM objects after PCOM.DLL is unloaded. A scratch fix exists
  (exit-leak.patch); shipping a new Kaentake.dll to friends needs the user's approval and it does not stop the
  mid-fight crash.
- The only record of an in-game crash is the game's own crash log, %LOCALAPPDATA%\VirtualStore\Windows\SysWOW64\msexcr.ini
  (14:00:23): a MOVE_MONSTER packet with an attack action reached CMob::OnMove, which started the mob's attack sound;
  SOUND_DX8.DLL asked for a 134,788-byte buffer, got NULL and wrote to it. The 10:56 crash also came right after a
  mob sound started (the body's skill sound).
- Lead (unconfirmed): the 32-bit client (not large-address-aware, 2 GB of address space) runs out of memory, or its heap
  is damaged, in a busy boss fight. Next step: log the client's memory every 5 s during a run (PowerShell
  Get-Process loop) and compare with a field baseline.
- Fix A (stop stale body control) fixes a real server defect and stays. The stale-control log also saw one MOVE_LIFE for
  claw 8830009 at encounter start, so claws can briefly be client-controlled too. Fix B (body casts without the skill
  action) has not been shown to matter; it stays on until the memory check, then the user decides.
- 2026-10-29: Fix B is off by default (`telegraph = action`). The memory check settled the crash: the large-address-aware
  client (shipped 2026-10-09) ran Easy Balrog with 9 body casts and no crash, and with the WZ mapping fix (2026-10-14)
  boss fights peak at 1.5-2.5 GB of the 4 GB address space. Fix A stays. `!balrogprobe telegraph none` still turns Fix B
  back on until a restart.

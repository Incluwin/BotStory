# P8: Zakum with companions

> Built 2026-10-08/09 on branch `feat/companion-bossing-p8` (base 2432bdb54 plus the docs commit 70ca70623), one
> commit per build step plus review fixes. Not pushed, not deployed, not live-tested. Zakum ships off.
> Plan: docs/COMPANION_BOSSING_PLAN.md (G1-G13, "Findings for P8") and docs/BOT_MANAGEMENT_PLAN.md (P8).
> Easy Balrog: docs/COMPANION_BOSSING_P7A.md (Part A) and docs/COMPANION_BOSSING_P7B.md (Part B).

## Result

An owner can bring up to five party companions into a Zakum expedition at Adobis (211042400). With Part B on, up to
five bench allies can come as well. Companions shop at Glibber on the way and sign up through Adobis's own window.
Each companion spends its own daily Zakum entry. They line up at the altar, fight the arms and then the body, and
take the arms' real attacks. A companion that goes down waits in El Nath. After the clear they leave by Amon.

Zakum stays off until `COMPANION_BOSS_ENABLED_TYPES` lists `zakum`. Turn it on only after the staged client-memory
checks in the recipe below pass. With zakum off, players see no change.

Two GM6 tools are new: `!bossgear` gives a scrolled boss kit, and `!bossmirror` shows and sets the attack mirror.

Decision for you: should companions stay in the fight when their owner leaves before the clear but a human of the
owner's party stays in? As built, they leave with the owner. Only a logout hands them to that human (the P7 path). I
recommend keeping it as built until a two-human Zakum run is planned. If you do not answer, the build stays as it is.

You need to: nothing before the deploy. The deploy owner's steps are in "Hand-over to the deploy owner".

## User decisions this build follows (2026-10-08)

The user answered the 13 open questions of the P8 plan with "use all recommended", except the supply standard.
1. A dead companion waits in El Nath for the rest of the run. There is no re-entry, so the conditional step 16 was
   not built.
2. The P8 exit check "a dead ally re-enters" becomes "a dead companion waits in El Nath and rejoins after the run".
3. Companions spend their own daily Zakum entry. It is logged only when they enter.
4. The quota change applies to companions only. Human logging stays stock.
5. The Zakum standard is 500 HP and 400 MP potions plus 4000 projectiles (the user's own answer, not 200/200).
6. Companions top up at Glibber on Ice Valley II (211040200), not in El Nath town.
7. Touch hits and mirrored hits share one 1.5 s hit window per companion.
8. An owner who leaves before the clear takes their party companions out. See the decision above for the gap.
9. `!bossgear` items are untradeable and tagged "bossgear".
10. The Easy Balrog client-memory log runs before the first live Zakum fight.
11. The first live Zakum uses only the owner and the 5 party companions. Allies come after a clean memory stage.
12. Superseded on 2026-10-09: the user approved shipping the large-address-aware MapleStory.exe to friends, and it
    is live in Game Files. The live checks use the normal client. No copied folder is needed.
13. Superseded by the other-bosses plan (decision 13, default branch): P11 runs on crew B (ohboy) at Lv100, so crew A may go to Lv160 without waiting for P11. Levels only rise, so finish the Lv70 Easy Balrog runs with crew A first.

## What the player sees (with zakum on)

On the way:
- In Ice Valley II (211040200, the El Nath taxi stop), a Lv50+ party companion short of the Zakum standard says a go
  line, for example "low on pots for zakum, grabbing some here. back in ~2 min.". It buys at Glibber (NPC 2030009)
  from its own purse and catches up.
- This happens only while the owner carries the Eye of Fire, which the Door to Zakum asks every player for. An owner
  who hunts Yetis there, or rides the taxi on to Sharp Cliff or the Dead Mine, keeps the normal standard.
- Companions follow the owner through the Door to Zakum. They need no quest and no Eye of Fire (plan decision 11a).
- In Adobis's room no companion leaves for a shop. The nearest shop is 22 maps away with no way back. A short
  companion says the no-shop line when the owner walks in, at most once in `COMPANION_RESTOCK_ASK_EVERY_MS` (30 min).

At Adobis:
- After the owner creates or joins the expedition, on "already registered", and in the leader's list, Adobis offers
  "My party will register too.". With `COMPANION_BOSS_ALLY_TYPES` listing zakum, he also offers "Call allies from my
  bench...". His answer is "Very well. Have them come and speak with me before time runs out.".
- Agents, and owners with nothing to offer, get his stock windows byte for byte.
- Each companion walks up beside Adobis and signs up. The stock "[Expedition] ... has joined" notice appears. One
  companion says a signed-up line.
- A companion with no Zakum entry left today says "already went in today. i'll wait out here." or a variant. It does
  not sign up. A capped ally still shows in the picker. It whispers "already did my run today, can't come. sorry!"
  or a variant from its camp and does not travel. Its bench slice goes on.
- Zakum needs 6 names on the list to start. The owner and 5 companions are exactly 6, so one refusal blocks the Start.

In the fight:
- After the Start, the first party companion inside says "ready when you are. drop the eye on the altar." or a
  variant. It says it once, and only if the owner stands on the altar with the Eye of Fire in the bag.
- Companions never pick up the Eye of Fire. Until Zakum appears, they stand in a line left of the altar.
- Arms: melee companions hit the low arms first (8800007, 8800009, 8800010). Ranged and magic companions hit the high
  arms first (8800003-8800006, 8800008). When the low arms are down, melee companions climb to a platform near the
  high arms.
- The arms' attacks hit companions. Companions lose HP and show damage numbers. A jump attack misses a companion in
  the air. Touch damage and arm attacks share one 1.5 s hit window per companion.
- The arms stay with the human whose client moves them, and they keep attacking. Companion hits never take an arm
  away, and far hits raise no distance alert.
- Companions never hit the fake body and never summon on the altar.
- Body: physical companions stop hitting the body while it is immune to weapons. Magic companions stop while it is
  immune to magic. With nothing to hit, they hold.
- Adds: when 6 or more adds stand, companions clear the adds first, in any phase. Fewer adds come last.
- A companion hits an arm or the body only while a living, visible human controls that part. A hidden or dead
  controller counts as no human.

Going down and leaving:
- A dead companion revives in El Nath after the normal 5 s. It says a Zakum line, for example "ugh, zakum got me.
  i'll wait in el nath.". It waits there and follows the owner when the owner comes out. Nobody can re-enter a
  running Zakum.
- If the owner leaves the fight before the clear (by Amon, or by reviving in El Nath), the owner's party companions
  leave too. One says "you're out? we're coming too." or "heading out with you." when the first one is out.
- Each party companion that leaves with its owner is free at once. It takes orders, follows a channel change, can be
  benched and restocks again, even while another human fights on. A dead companion stays the run's while its owner
  fights on. When its owner leaves the fight before the clear, it is free at once too, by the same rule.
- That rule waits until the owner was seen in the fight, or 5 s after the Start. A dead owner who still lies on the
  altar counts as in the fight. Allies stay while any human stands.
- When no living human is left in the fight for 3 s, companions leave by Amon. One says "everyone's down... let's
  regroup outside.".
- After the clear one companion says "gg! zakum's down." or "we did it! gg.". Companions talk to Amon to leave. A
  companion that cannot reach his ledge in 30 s is moved to Adobis's room. Party companions then follow the owner.
  Allies say "good fight" and go home.
- Logout, boss drops and the Part B drop owner work as in P7.

With zakum off: no visible change for players. GMs get `!bossgear` and `!bossmirror` at deploy.

## Config

`config.yaml` (living_world): no new keys.
- `COMPANION_BOSS_ENABLED_TYPES` stays `"easy_balrog"`. `"easy_balrog,zakum"` turns Zakum on.
- `COMPANION_BOSS_ALLY_TYPES` stays `"easy_balrog"`, as live ships it for Easy Balrog bench allies.
  `"easy_balrog,zakum"` also lets Zakum owners call bench allies. The boss must be on both lists.
- One comment line above `COMPANION_BOSS_ENABLED_TYPES` says zakum stays off until its memory checks pass.

`agent-engine.yaml` (appended, each with Values/Purpose/Technical lines):

| Key | Shipped | What it does |
|---|---|---|
| `mobcontrol.AgentBossScope.ENABLED` | `'true'` | G2: Agents never take a covered boss part, and their far hits raise no DISTANCE_HACK |
| `mobcontrol.AgentBossScope.EXPEDITION_TYPES` | `'ZAKUM'` | Expedition types whose boss parts are covered |
| `mobcontrol.AgentBossScope.BOSS_IDS` | `'none'` | Field boss ids covered outside events (for P13) |
| `expedition.boss.AgentBossAttackMirror.MODE` | `'APPLY'` | OFF, OBSERVE (log only) or APPLY (companions take the hits) |
| `...AgentBossAttackMirror.DEDUP_MAX_MS` | `'6000'` | Cap on the window that counts repeated reports as one attack |
| `...AgentBossAttackMirror.MAX_SHOWN_HITS` | `'30'` | Companions per attack that show a damage number. The rest lose HP silently |
| `...AgentBossAttackMirror.DAMAGE_PERCENT` | `'100'` | Scales the HP loss of mirrored hits. A deadly hit, MP burn and a disease stay at any percent |
| `...AgentBossAttackMirror.SHARED_HIT_WINDOW` | `'true'` | Mirrored hits share MOB_HIT_COOLDOWN_MS (1500) with touch hits |
| `...AgentBossAttackMirror.CALIBRATION_WINDOW_MS` | `'1500'` | How long a prediction and a human's hit report wait for each other |
| `...AgentBossAttackMirror.SUMMARY_LOG_MS` | `'60000'` | One summary line per run per interval |
| `expedition.boss.AgentBossClientLoad.ATTACK_INTERVAL_FLOOR_MS` | `'0'` | Off. A minimum time between attacks for companions inside any boss run |
| `expedition.zakum.AgentZakumScenario.VITALS_LOG_INTERVAL_MS` | `'30000'` | Party vitals line interval |
| `...AgentZakumScenario.PRE_SUMMON_OFFSET_PX` | `'150'` | First line-up spot, left of the altar |
| `...AgentZakumScenario.PRE_SUMMON_SPACING_PX` | `'30'` | Space between line-up spots (14 spots fit) |
| `...AgentZakumScenario.FLOOR_REACH_PX` | `'100'` | Arms within this height count as low arms |
| `...AgentZakumScenario.ADD_CLEAR_THRESHOLD` | `'6'` | Live adds at which companions clear the adds first |
| `...AgentZakumScenario.PLATFORM_ANCHORS` | `'-345,-488\|-373,-556\|446,-530\|473,-391'` | Platform spots for melee companions on the high arms |

All keys start with `server.agents.capabilities.`. Value edits:
- `supplies.AgentSupplyReadiness.BOSS_STANDARDS` is `'easy_balrog=100/60,zakum=500/400'`.
- `supplies.AgentSupplyReadiness.AMMO_STANDARDS` is `'lpq=1600,epq=1600,easy_balrog=2000,zakum=4000'`
  (easy_balrog=2000 came from live 30abc1405).
- `supplies.AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS` stays `'easy_balrog=130000/32000'` (live 30abc1405).
  Zakum is not listed, so Zakum companions count potions against 500/400 (user decision 2026-10-08).
- The Values line of `AgentBossRunSession.START_GRACE_MS` now says that the Start warps members one at a time while
  the run already ticks. The value 5000 is unchanged.

No database change. Changeset 47 and `db/tables/038-*` stay free.

## How it is built

The attack mirror (G1). Zakum's arms are moved by a human's client, so the server never computes their attacks.
The mirror reads each arm attack the client reports and applies it to the companions in its shape.
- Core: `BossAttackMirrorSeam` gets one call in `MoveLifeHandler` (after the controller's reply, before the relay)
  and one in `TakeDamageHandler` (log only, for calibration). It turns raw actions 24-41 into an attack index and a
  facing, and passes the region mask (pOption). It holds no settings.
- Core: `BossMirrorGeometry` and `BossActionGeometry.containsRegions` test boxes, circles and region attacks.
  `ServerMobActionCatalog` fixes the one negative radius (8800010 attack2), and adds `resolvedAnimationMs` (UOL frames)
  and `jumpAttack`. `ServerMobDamageService.applyOrdinaryAttack` gets a percent and a broadcast flag. The old call
  passes (100, true) and is byte-identical.
- Agents: `AgentBossAttackMirror` gates the scope, dedups, schedules the impact at the attack's attackAfter, picks the
  targets and runs each hit on the target's own mailbox (`AgentCombatDamageRuntime.applyMirroredHit`). It also keeps
  the shown-hit cap, the calibration counts and the summary line. `LivingWorld` installs it.

G2, the control-steal block. `AgentBossScope.guards` covers boss parts of listed expedition types, listed field ids and
the GM trial map. `ServerMobAutonomyService.blocksAgentPhysicsInstance` and the DISTANCE_HACK exemption in
`AbstractDealDamageHandler` each ask it. `AgentBossScope.engaged` says whether a living, visible human drives a part.

The fight. `AgentZakumDefinition` holds the ids. `AgentZakumScenario` runs the phases WAITING_FOR_SUMMON, ARMS,
TRANSITION, BODY and DONE, the line-up, the platform anchors and the vitals log. `BossTargetRules` (pure) orders the
targets: adds at the threshold, then the arm tiers, then the body, then the adds below the threshold.

Boss-run changes. `AgentBossRunDefinition` gets four flags, all off for Easy Balrog: `exitNpcAfterClear`,
`markerItemIds`, `neverTargetFakes` and `ownerLeavingTakesParty`. `AgentBossRunSession` talks to Amon after the clear,
reports each adoption (`host.entered`) and runs the owner-left pass. `AgentBossRunRegistry.neverTarget` and
`preservesMarker` feed one clause each in `AgentCombatTargetSelector` and `AgentLootEligibility`.

Quota. `ExpeditionBossLog.attemptBoss` asks `LivingWorldHooks.bossQuota`. `CompanionBossQuota` answers for enlisted
companions from a memory cache, so sign-up and Start run no JDBC on the Agent scheduler. The pre-check runs on
companion-ops before the enlist. Each companion's entry is written once, at adoption (`ExpeditionBossLog.recordEntry`).
Humans and every boss without a log keep the stock path.

Supplies. `BossEntrance.ZAKUM` names Ice Valley II as the approach store and holds trips in Adobis's room
(`holdsTripsAtEntry`). `CompanionRestock.restocksFrom` is false there while zakum is on. A new
`CompanionRestock.bossGate` overload says the no-shop line against the Zakum standard.

Lines. `BossRunLines.BY_BOSS` holds Zakum's own words. New intents: `QUOTA` (every boss), `OWNER_LEFT` (every boss
with the rule) and `ZAKUM_EYE`. Easy Balrog's wordings and their order are unchanged.

Client load. `AgentBossClientLoad` gives the attack-interval floor and counts committed attacks for the summary
(`botAttacks=`).

Script and commands. `scripts/npc/2030013.js` gets seven blocks in Mu Young's pattern with the key "zakum".
`BossGearCommand` with `BossGearPlanner` and `CompanionGearTool`, and `BossMirrorCommand`, are GM6.

Not changed: `AgentDeathTickCoordinator`, ZakumBattle.js, Zakum05.js, 2111001.js, 2030010.js, `EventInstanceManager`,
`MapleMap`, `Monster`, the Easy Balrog encounter, KPQ/HPQ/LPQ/EPQ code, server-knowledge.json and the database.

## Tests

- Each step was written failing-first, with one mutation that turned the named test red. Each step ran the guard set
  and the P7 boss tests. An independent audit checked each step. Steps 3, 10 and 13 got review-fix commits.
- Tiebreak fixes: `CompanionBossRunsTest` pins that an OWNER_LEFT companion is let go at once (and only from its own
  run). `CompanionBossAlliesCallTest.anAllyCalledToZakumSpendsItsOwnEntryOnceAsItGoesIn` pins that a bench ally's
  Zakum entry is logged once as it goes in.
- New test classes: AdobisHookScriptTest, AgentBossAttackMirrorTest, AgentBossClientLoadTest, AgentBossRunCatalogTest,
  AgentBossRunRegistryRulesTest, AgentBossRunSessionOwnerLeftTest, AgentBossScopeTest,
  AgentEngineConfigValidatorBossMirrorTest, AgentEngineConfigValidatorBossScopeTest,
  AgentEngineConfigValidatorZakumAnchorsTest, AgentZakumAnchorReachTest, AgentZakumScenarioTest,
  BossAttackMirrorSeamTest, BossGearPlannerTest, BossMirrorCommandTest, BossMirrorGeometryTest, BossTargetRulesTest,
  CompanionBossQuotaTest, CompanionGearToolTest, MoveLifeHandlerBossMirrorTest and TakeDamageHandlerBossMirrorTest.
  `testutil.BossLogDatabase` is a mocked database for the quota tests.
- The first full suite of step 15 found one new failure, `AgentCosmicBoundaryAuditTest`. `AgentBossScope` and
  `AgentBossAttackMirror` read `.getClient()` outside the Cosmic adapters, to tell a bot from a human. The audit test
  now approves both files, as it approves AgentFreeMarketStallService.
- Step 15 first tried `AgentCharacterGatewayRuntime.characters().isHeadlessControlled` instead. It gives the same
  answer, but it starts `CosmicAgentServerAdapter` earlier in the test run. MuYoungHookScriptTest and
  AdobisHookScriptTest then reset `AgentPresence`, and `CosmicAgentServerAdapterTest` failed by test order.
- Full suite on the step 15 tree (2026-10-09, plain `test` at -Xmx6g): 12,118 tests, 1 failure, 0 errors, 37
  skipped. The base 2432bdb54 had 11,872 tests, 0 failures and 37 skipped, so P8 adds 246 tests.
- The expected failure is `KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed`, because scripts/npc/2030013.js
  changed and server-knowledge.json is left to the integrator.

## Hand-over to the deploy owner

1. P8 makes no death-chain change. `AgentDeathTickCoordinator` is unchanged. A dead companion waits in El Nath, and the
   re-entry gate (plan step 16) was not built.
2. After the merge, run `tools\knowledge-export.bat`. Until then
   `KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed` fails, because 2030013.js changed. Do not regenerate
   server-knowledge.json on the branch.
3. Deploy as shipped. Zakum is off: `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"` and
   `COMPANION_BOSS_ALLY_TYPES: "easy_balrog"`. Easy Balrog bench allies stay on, as live has them.
4. Live at deploy even with zakum off:
   - the GM6 commands `!bossgear` and `!bossmirror`,
   - the mirror, installed with the living world. It acts only in open companion runs of listed types and in the GM
     trial,
   - G2 on the boss parts of any ZAKUM expedition event. With zakum off no companion signs up, so it changes
     nothing,
   - the quota hook. A player costs only memory reads, and the stock count runs.
5. To turn Zakum on (recipe Stage 2), set `"easy_balrog,zakum"`. In the same commit, update
   `LivingWorldConfigBossTest.configYamlShipsEasyBalrogOnWithServerAuthorityAndSaysWhy`. It pins the value and the
   yaml line.
6. For allies (recipe Run 5), set `COMPANION_BOSS_ALLY_TYPES: "easy_balrog,zakum"`. Keep easy_balrog in the list.
   Without it, Easy Balrog bench allies turn off. In the same commit, update
   `LivingWorldConfigBossTest.allyKeysAreOffInCodeAndShipEasyBalrogOnWithOneOwner` (the value) and
   `LivingWorldConfigBossTest.theYamlCarriesTheFourKeys` (the yaml line).
7. If a stage stops on the client-memory rule, set `ATTACK_INTERVAL_FLOOR_MS` to 900 or `MAX_SHOWN_HITS` to 3.
   `AgentBossClientLoadTest.theShippedFloorIsZeroAndChangesNothing` pins the floor at 0. Nothing pins MAX_SHOWN_HITS.
8. Other tests that pin shipped P8 values: `AgentBossScopeTest.theYamlShipsTheScopeOnForZakumWithNoListedIds`
   (ENABLED, EXPEDITION_TYPES, BOSS_IDS), `AgentZakumAnchorReachTest` (PLATFORM_ANCHORS),
   `AgentSupplyReadinessBossStandardTest` (BOSS_STANDARDS) and `AgentSupplyReadinessTest` (AMMO_STANDARDS).
9. Rollback: set `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"` and `COMPANION_BOSS_ALLY_TYPES: "easy_balrog"`, then
   restart. Do not set the ally list to `""`: that turns Easy Balrog bench allies off.
10. Merge with P11 by appending after the `// slot: zakum` comments in CompanionBossKeys, AgentBossRunCatalog,
    BossEntrance and BossRunLines.BY_BOSS. AdobisHookScriptTest pins the seven 2030013.js blocks and the stock
    SHA-256. Any later edit of 2030013.js must update it.
11. Add `FootholdMockingGuardTest` to every targeted run that adds test files. Add `AgentCosmicBoundaryAuditTest` to
    every targeted run that edits `server.agents`.
12. Decide at merge whether the two approved `.getClient()` reads stay. Moving them behind the character gateway also
    needs the presence resets in MuYoungHookScriptTest and AdobisHookScriptTest to stop breaking
    `CosmicAgentServerAdapterTest`.

## Live check recipe (GM commands first)

Warnings first:
- A restart disconnects everyone online. Tell players before each restart. Restart only with
  `start-maple-stack -RestartServer`.
- Each stage may close your client.
- Never use `!setlevel ... restore`. Do not add `auto` for companions.
- Do not use `!hide`. A hidden GM controller makes companions hold.
- Decision 13: crew A goes to Lv160 (steps 5-11) only after its Lv70 Easy Balrog runs are done. P11 uses crew B.

Stop rule: stop the stage on either of these:
- a new entry in the game's crash log, %LOCALAPPDATA%\VirtualStore\Windows\SysWOW64\msexcr.ini,
- VM_MB climbing steadily through the stage. Unconfirmed threshold: about 150 MB above the stage start.

After a stop, set `ATTACK_INTERVAL_FLOOR_MS` to 900 or `MAX_SHOWN_HITS` to 3, restart and run the stage again.
The floor does not act in Stage 1, because the GM trial is not a boss run.

Evidence to grep in the server log:
- `Boss mirror summary session=` (counts from the start of the run, ending in `botAttacks=`),
- `Boss mirror observe` and `Boss mirror calibration miss`,
- `Zakum combat phase=` and `Zakum party vitals phase=` (each companion's choice and each part's flags),
- `Boss quota:` (refusals, `entry logged=`, and a WARN if a member had no cached answer),
- `Boss run member leaving:` and `Boss run member out:` (for example `reason=OWNER_LEFT`),
- `gm bossgear:` and `gm bossmirror`.

Setup (zakum off):
1. Deploy with `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"`.
2. Talk to Adobis with Bossfriend. Check that his windows are stock.
3. Use the normal client: it is large-address-aware since 2026-10-09 (decision 12). In PowerShell, start the
   memory logger and leave it running:
   ```
   while ($true) { Get-Process MapleStory -ErrorAction SilentlyContinue | Select-Object @{n='t';e={Get-Date -Format HH:mm:ss}}, Id, @{n='Private_MB';e={[int]($_.PrivateMemorySize64/1MB)}}, @{n='VM_MB';e={[int]($_.VirtualMemorySize64/1MB)}} | Export-Csv -Append -NoTypeInformation maple_mem.csv; Start-Sleep -Seconds 5 }
   ```
4. Run the Easy Balrog memory stage if it has not run (decision 10).
5. Run `!setlevel me 160 <4th-job id> auto`.
6. Run `!setlevel Isolde 160 232`, `!setlevel Thane 160 112`, `!setlevel Sorrel 160 322`, `!setlevel Ingrid 160 132`
   and `!setlevel Flint 160 522`.
7. For each of the five, run `!bossgear <name> preview`, then `!bossgear <name>`.
8. Run `!bossgear me`, then put your pieces on.
9. Bench Thane, Ingrid and Flint. Call Orrin, Magna and Gunnar, and wait until each arrives.
10. Run `!setlevel Orrin 160 122`, `!setlevel Magna 160 122` and `!setlevel Gunnar 160 112`.
11. Run `!bossgear` for Orrin, Magna and Gunnar.
12. Bench the three allies. Call Thane, Ingrid and Flint back.
13. Run `!giveitem Sorrel 2061000 4000` and `!giveitem Flint 2330000 4000`. If a bag shows less, they buy the rest
    at Glibber.
14. Run `!givemeso <name> 1500000` for each of the eight. In a test, a full top-up from empty cost 1,262,600 mesos.
15. Run `!startquest 100200`, `!completequest 100201` and `!item 4001017 1`.

Stage 1, field dry run:
16. Run `!warp 180000000`.
17. Run `!bossmirror mode observe` and `!bossmirror trial on`. The trial binds the map you stand on.
18. Run `!spawn 8800010`.
19. Stand near the arm with your companions for 2 minutes. Expect `Boss mirror observe` lines, counts in
    `!bossmirror status` and no companion HP loss.
20. Run `!bossmirror mode apply`. Expect companions in range to lose HP with damage numbers. Expect no DISTANCE_HACK
    line for MID 8800010. Expect the arm to keep attacking.
21. Run `!killall` and `!bossmirror trial off`.

Stage 2, enable zakum:
22. Warn players. Set `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog,zakum"` and restart.

Run 1, owner plus 5:
23. Carry an Eye of Fire. Take the El Nath taxi to Ice Valley II. Leave one companion short of potions. Expect its
    go line, a buy at Glibber and a catch-up.
24. Talk to Jeff. Walk to the Door to Zakum (or `!warp 211042300`) and enter.
25. Talk to Adobis and pick "Lets get this going!". Expect "My party will register too." and "That's all for now.".
    Pick "My party will register too.".
26. Expect five walk-ups, five join notices and one signed-up line. `!expeditions` lists 6, exactly the minimum.
27. Start the fight.
28. Expect the Eye line. Drop exactly 1 Eye at the altar centre. Expect no companion to pick it up, and Zakum to
    appear about 5 s later.
29. During the arms, expect mirror summaries with applied hits above 0 for several arm ids. Expect the arms to keep
    attacking you, no DISTANCE_HACK for MID 8800003-8800010, companions drinking potions, and no summons.
30. Run `!hpmp Thane 0`. Expect Thane in El Nath with a Zakum down line. Expect him to stay there.
31. Clear Zakum and talk to Amon. Expect companions to follow by Amon or to be moved out within 30 s. Expect Thane to
    join you outside.
32. Optional, read-only: `SELECT characterid FROM bosslog_daily WHERE bosstype='ZAKUM'` returns 6 rows.

Run 2, no human left:
33. Run `!resetboss <name> ZAKUM` for all six, you included.
34. Create the expedition, register your party, start and drop the Eye. When Zakum appears, run `!hpmp 0` on
    yourself and do not press OK.
35. Expect the companions to leave by Amon with the regroup line. Press OK.

Run 3, quota:
36. Reset everyone except Sorrel. Create the expedition and pick "My party will register too.".
37. Expect Sorrel's quota line, and that Sorrel does not walk up. The list stops at 5, so end the expedition.

Run 4, owner leaves early:
38. Reset all six. Create the expedition, register your party, start and drop the Eye. During the arms, talk to
    Amon.
39. Expect each party companion to walk to Amon's ledge at (-473,-430), or to be moved to Adobis's room after 30 s.
    Expect one owner-left line in party chat when the first one is out.

Run 5, the P8 exit run with at least 8 companions:
40. Warn players. Set `COMPANION_BOSS_ALLY_TYPES: "easy_balrog,zakum"` and restart. Reset every quota.
41. Create the expedition and register your party. In the first minute, call all three allies through "Call allies
    from my bench...". Expect ETA whispers, arrival at west00, "Boss ally provisioned ... store=2030009" lines and
    sign-up. `!expeditions` lists 9.
42. Clear Zakum. Record tick, GC and the memory log. Film it for your video sign-off.

Run 6, logout: log out mid-fight. Expect your party companions to park, and your allies to stay with any human left.

Regression: `!balrogtest quick`, the Easy Balrog Part A windows, one KPQ desk run and one HPQ desk run.

Rollback: set `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"` and `COMPANION_BOSS_ALLY_TYPES: "easy_balrog"`, then
restart.

## Limits and unconfirmed points

- The client crash is the biggest risk. Live Easy Balrog fights already closed the human client (see P7A, "Client
  crash"). Zakum stays off until each stage passes the stop rule. Bot rebuff effects are not throttled.
- Unconfirmed: that the v83 client fills pOption with the region mask. Invalid masks fall back to a random sample.
  Calibration counts both.
- Unconfirmed: that melee companions reach the high arms from the platforms. The anchors are 106-191 px from the left
  high arms' boxes and 180-270 px from the right ones. Tune FLOOR_REACH_PX and PLATFORM_ANCHORS from the vitals line.
- Unconfirmed: that companions reach Amon's ledge at (-473,-430). The fallback moves them out after 30 s.
- Unconfirmed: Glibber's reach live, and which potions a small purse buys. A smaller purse buys cheaper potions up to
  the same counts.
- Unconfirmed: potion drain over a 35-45 min fight. Read the "PQ potions" lines after Run 1. Heals reach only the
  healer's own party.
- Use-tab space is tight at 500/400: 9 potion stacks, 1 free slot and several ammo stacks in 24 slots. A full bag
  takes the existing "bag is full" path.
- The hold in Adobis's room covers only companions with a standing restock duty. An enlisted companion or an ally
  has none, as in P7.
- Unconfirmed: whether a dead controller's client keeps sending attacks. The mirror treats it as no human.
- The mirror trusts the controller's client. MP use and the dedup window bound it.
- Server CPU with 9 parts and up to 13 companions is not measured. Measure tick and GC at each stage.
- Allies from Victoria take about 3 min. Call them in the first minute.
- Side effects still open in docs/BOT_MANAGEMENT_PLAN.md (plan decisions 2 and 23): BOSS points come per boss kill
  (cap 3 a day). Companions also get quest 100200 completed when they leave after the clear. Since the merge of live
  30abc1405, `living_world.BOSS_EVENT_SCRIPTS` lists ZakumBattle, so the PQ-clear hook only logs "Boss event cleared"
  for a Zakum clear and writes no 'Zakum<channel>' party-quest record.
- Since the merge of live 30abc1405, two Easy Balrog additions also apply to Zakum runs: the no-healer warning
  (`BossRunLines.NO_HEALER`, generic words, once per party before Start) and the supplies on the "Boss run member
  down" line (`AgentBossRunPorts.supplies`). The Zakum vitals line does not show supplies yet.
- The quota cache lives in memory. After a living-world restart mid-run, members take the stock path with a WARN.
  They are still charged exactly once.
- The GM trial binds the map at `trial on` and holds it until `trial off`. It does nothing with
  `AgentBossScope.ENABLED: 'false'`, and mirrors nothing while the living world is off.
- `!bossgear` gives the highest pieces the game has, not Lv160 gear. A Lv160 Hero gets Lv100 armour, a Lv85 cape and
  Lv120 weapons.
- `!bossgear <me|name> remove` takes every boss piece back, worn and carried. A companion that is let go (by its
  owner, a contract end, its owner's deletion or the 14-day inactivity sweep) loses its boss pieces as it is unloaded,
  so the kit never goes home with a Resident or on to another owner. Its other gear stays.
- The same release then gives it back its own level (user decision 2026-10-09): its `!setlevel` hold is restored as
  `!setlevel <name> restore` would, gear its own level does not allow goes into its bag, and the hold is released
  (`gm.level.release` 'restored on release'). Bench, park, logout and channel follow keep the hold, so a crew keeps
  its Lv160 test levels while it stays on its owner's roster.
- A release deletes the roster row at once, but the strip and the restore run in the unload at the end of the
  walk-out (about 8 s). A crash or a hard kill in that window, or a release in the last seconds of a shutdown
  countdown, used to leave the persona with its hold and its boss pieces and no roster row. The living world now
  finishes such a release when it starts (`RosterService.finishReleaseCleanupAtStart`, on companion-ops right after
  the boot reconciliation). One bounded query finds each persona on nobody's roster that still has a hold or a boss
  piece. Each one gets the same strip, refit and restore as an offline release, with the same online checks and logs.
  Nobody knows then who let it go, so the refit reads the persona's newest `bossgear.worn` or `bossgear.removed` row
  under any owner (`LivingStore.latestPersonaEvent`). A `bossgear.worn` row puts back what the kit displaced, and the
  `bossgear.removed` row goes under that row's owner, so the record ends. A row that names the bag slot and print of
  each piece puts back the very copy, as a release does, when the persona carries two of an id. A row from before the
  bag slot and print were kept names the id alone. With no record, or when the read fails, it
  wears the best piece it carries, and the removal goes under owner 0. One INFO line
  `Release cleanup at start: stripped= restored= skipped=` follows, only when it found one. Personas on a roster, a
  human's own record and characters online or loaded stay as they are. A skipped persona waits for the next start.
- Zakum's level and not-here refusals have one wording each, so two companions refused alike say the same words.
- The javadocs of `LivingWorldHooks.bossParty*`/`bossAlly*` and `CompanionBossRuns.offers` still say "Mu Young's Easy
  window". Adobis uses them too.
- The first report for a new Zakum part parses its Mob.wz XML up to three times on the I/O thread, about 1-2 ms each,
  once per part id.

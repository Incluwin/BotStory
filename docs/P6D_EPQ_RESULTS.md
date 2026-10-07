# P6d EPQ: companion runs, Steps 2-6 (branch p6g/epq)

Ellin PQ (Forest of Poison Haze) plays as a companion run: a human leader and his bot companions, played stock. Steps 2-6 are built on p6g/epq from 992878bae, one commit per plan item. The switch `living_world.COMPANION_PQ_ENABLED_KEYS` still says `kpq,hpq`. The integrator adds `epq` after the live checks (Step 7, below).

Binding plan: `p6g_epq_steps2to6_plan_revised.md` (session scratchpad). User decisions applied: the assists are off in companion runs (the stage-2 bug respawn, the stage-4 flower respawn, the post-clear dummy kill). The damage preserve holds back only Agent attackers in companion runs. The leader places the boss stone himself, coached by the companion who found it. Stock party sizes are 4-6, and the desk prefers 6. A tap-out is allowed only above the stock floor of four. Companions say "sprite", never "Yuris", and talk like players: lower case, no counters, no system text.

## What was built

| Commit | Item |
| --- | --- |
| 2a061f7e5 | EPQ: let the companion-run classes call the coordinator's helpers |
| 80dbf9902 | EPQ: name Golem's Marble and the Altaire Fragment for what they are |
| a7043b9e2 | EPQ: companion runs talk in map chat like players |
| 954884c65 | EPQ: a companion run waits for its leader at Ellin and goes in with him |
| c9a276eeb | EPQ: companions take a walking exit with their leader, not ahead of him |
| b4a01ac4c | EPQ: companion runs play stage 2 without forced bug respawns, and one lurer works both sides |
| 9d2e285eb | EPQ: the human leader hits flowers and stone bugs as in stock; only companions hold back |
| 4444d6210 | EPQ: companions bring the monster marbles to their leader's feet |
| 09aa5a6a8 | EPQ: the stone collector brings the purple stone to its leader's feet |
| 2dadc7439 | EPQ: after the golem a companion run leaves on its own and never fails |
| 4e441ef74 | EPQ: a cleared companion run is recorded as cleared whoever ends it |
| 727fbf18b | EPQ: companions finish at the fountain and leave on their own |
| f1f216feb | EPQ: companions in their run's instance tick at full rate |
| 729290658 | EPQ: a companion run hears its companions' own words, never the status lines |
| e535467c7 | EPQ: companions coach their leader through each stage |
| 22f9f741a | EPQ: lint every companion line |
| 13aa41570 | EPQ: one rule table for every member of a companion run |
| b1d82dc59 | EPQ: a companion run ends in its companions' own words |
| fbde4acbb | EPQ: deaths, departures and the leader in a companion run |
| 5e42aece4 | EPQ: with no companion standing the run ends, even when nobody is left to tick it |
| 867916dfe | EPQ: a run whose marbles or stone are gone for good ends at once |
| 06d3e9e66 | EPQ: a stalled companion run says why it stops |
| 8b290f8be | EPQ: the forest as the clear-time estimate sees it |
| 56d50900f | EPQ: a companion run marks its pace as it goes |
| d71585093 | EPQ: a companion run's pace goes to its host |
| 033bb8338 | Ellin speaks at her own desk |
| 967e83cad | Ellin's entrance takes companion runs (still off by the switch) |
| bf0645d13 | Ellin's desk judges readiness by her quest's own standard |
| 7d5975b28 | Ellin hands a party leader's click with companions to the living world |

- Step 2 (companion play): companions take the walking exits with the leader. Stage 2 runs without forced respawns, and one lurer works both sides. The leader hits flowers and bugs as in stock. The monster marbles and the purple stone are handed over at his feet (landing preview, nudge, recollect, dying drop). After the golem the companions wait 60 s for out00, then leave by the event's own exit. The golem's marble goes into the fountain. A cleared run is never a failure. An empty event closes, so the channel's lobby is free again. The run's maps tick at full rate.
- Step 3 (voice): companion runs hear only the companions' own lines (the stock status lines stay in stock runs). The finder or one narrator coaches the leader through each stage. A lint checks every line.
- Step 4 (deaths, tap-outs, failure): one rule table covers every member, and partings are in the companions' words. A leader down on the golem's map keeps the run alive through the whole golem fight. A run with no companion standing ends on the dying Agent's own tick. Lost marbles or a lost stone end the run at once. A stalled run says why it stops.
- Step 5 (pace): AgentEpqPaceProfile (10 segments, WZ census, golem chain), pace marks through the run, and the pace report to the host, with one 'EPQ pace span:' line per segment for companion runs.
- Step 6 (the desk): EllinLines (her voice), EpqEntrance (registered; the desk stands aside while the switch does not list epq), the desk's readiness by the quest's own potion standard, and the four-line hook in scripts/npc/2133000.js.

## Deviations from the plan

- 2.3 also re-pointed AgentEpqFailureLogTest.theEventTimer (the companion-run clock: entry + timer + slack).
- 2.4 did not re-point AgentEpqTopUpTest (it stayed green). enterPortal's walk became the helper walkToPortal.
- 2.5 re-pointed AgentEpqDeadMemberRolesTest.aCorpseIsNoLurerAndItsBottleDoesNotCount and AgentEpqDepartedMemberTest.aDepartedAgentTakesNoStageTwoLurerSlot to a stock-session helper.
- 6.2: EpqFixtureEntrance registers a real HUMAN_ASSISTED EPQ session itself, as the KPQ, HPQ and LPQ fixtures do. It does not go through AgentEpqAdmissionService.admit, which needs the party and client gateways the fixture does not have. Its ground follows Map.wz 300030100 (Ellin's ledge and the floor x -450..450). The red run of 6.2 (the three slots empty, as before the commit) fails all 12 new tests on "no companion party-quest entrance for epq".
- 6.3 also updated the javadocs of checkShortSoon and waitingReason and one sentence of PqEntrance's class doc, so they say the standard is the quest's. No code changed beyond the three plan lines.
- 6.4: the knowledge test that turns red is KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed ("knowledge file older than scripts/npc"). KnowledgeFileFreshnessTest.reExportMatchesTheCommittedFile and KnowledgeLintTest.unknownIdsAreErrorsOnlyWhileTheirInputsAreFresh stay green in this tree. The plan named those two. The integrator's step is the same: regenerate server-knowledge.json after the merge.

## Companion lines (map chat, as built)

`<leader>` and `<name>` are player names. AgentEpqCompanionLinesTest checks every line: lower case except names, no digits, no counters or arrows, and at most 98 characters with a 12-letter name.

- ready when you are, <leader>. the portal's over here.
- these trees are poisoned. we clear every one and the way opens.
- that's the last tree. the portal's on the right.
- the bugs carry the poison. we pull them into the fountain first, then hit them in the water.
- <leader>, drop that diluted poison by the thorns on the right.
- first poison's on the thorns. keep them coming.
- halfway through the thorns.
- one more and the thorns are gone.
- the thorns are gone! the exit's on the right.
- it's a maze. wait here, we'll find ellin. don't talk to the mileposts, they send you out.
- found ellin! she's bringing everyone over.
- ellin hands out purification marbles. hit a poison flower till it's weak, then use a marble on it.
- <leader>, the monster marbles are still on the ground. grab them before they vanish.
- picked the marbles back up so they don't vanish. here they are again.
- you've got all twenty. ellin's up top, give them to her.
- <leader>, talk to ellin with the marbles and we're through.
- <leader>, couldn't get to you, left the marbles by ellin.
- almost out of pots, leaving the marbles here, grab them.
- long climb. the stone's in one of the boxes up top. wait by the sprite, we'll bring it down.
- found the purple stone! bringing it down to you.
- <leader>, couldn't get to you, left the stone by the sprite.
- <leader>, the stone's still on the ground. the sprite needs to see it.
- picked the stone back up so it doesn't vanish. here it is again.
- <leader>, talk to the sprite with the stone. she'll take us to the altar.
- almost out of pots, leaving the stone here, grab it.
- sec, drinking up first.
- ready when you are, <leader>. drop the purple stone right under the altar.
- <leader>, drop the stone under the altar and the golem comes out.
- <leader>, the stone has to land right under the altar. pick it up and try again.
- we did it! only you can open the way out, <leader>. the portal on the right.
- we got the golem! sorry you went down, <leader>. see you outside.
- the golem dropped a marble, <leader>! grab it, it goes in the fountain outside.
- got the golem's marble. i'll drop it in the fountain outside.
- we'll head out and wait for you outside.
- marble's in the fountain! exp for everyone.
- <leader>, drop the marble by the fountain. exp for everyone, and a fragment on the way out.
- see you outside!
- the monster marbles are at your feet, <leader>. pick them up.
- that's all twenty at your feet, <leader>. pick them up and give them to ellin up top.
- the purple stone is at your feet, <leader>. pick it up and show it to the sprite.
- <leader>, the golem's marble is at your feet. drop it by the fountain outside.
- the monster marbles are right next to you, <leader>. pick them up.
- that's all twenty right next to you, <leader>. pick them up and give them to ellin up top.
- the purple stone is right next to you, <leader>. pick it up and show it to the sprite.
- <leader>, the golem's marble is right next to you. drop it by the fountain outside.
- <name> had to go. we'll keep going with four.
- <name> had to go. we'll keep going with five.
- <leader>, hang in there! don't press ok yet, we can still beat it.
- here it comes! everyone on the golem.
- it's charging up! keep hitting it.
- super-charged now, watch out for the poison rain!

Partings (said once, before the clear only):

- we can't finish this one. let's head out.
- sorry, i went down too. let's regroup outside.
- too many of us went down. let's regroup outside.
- <name> left, and we need four for the forest. let's head out.
- we're short now, and we need four for the forest. let's head out.
- <name>, you went down, so we can't finish this one. let's head out.
- <name> dropped out, so we can't finish this one. let's head out.
- we lost the monster marbles, so there's no way to finish now. let's head out.
- we lost the purple stone, so there's no way to finish now. let's head out.
- we ran out of time. we'll get the golem next time.
- we've been stuck too long. let's call it and head out.

Host lines (party chat, existing host code): the arrival line "here! ready for ellin when you are." and the tap-out line from the persona.

## Ellin's desk lines (her window)

- Title: `#e#b<Party Quest: Forest of Poison Haze>#k#n` (2133000.js).
- Lineup: "Four of you, then? These are the ones I'd take into the forest:" (Five, Six). Otherwise "Here's who is with you:".
- "Who comes into the forest? You and three to five more." / "Six is the most the forest can take. Take someone out first." / "Who should I call? They'll meet you here at the edge of the forest."
- "I can only take four to six of you into the forest." / "I need at least four of you, all between level 44 and 55." / "Before I can take you in:" / "Something went wrong. Try again in a moment."
- Refusals: "<name> needs to be between level 44 and 55 for the forest.", "<name> can't come in. The forest only lets adventurers through.", "<name>'s bags are full. Free four Etc slots and one Use slot first.", "<name> isn't here yet.". Each is said to the leader when it is his ("You need to be here at the edge of the forest first." ...).
- Waiting reasons: "Lv<n>, not ready for this yet", "Lv<n>, past the forest", "not an adventurer", "bags full", "not at the forest", plus the shared ones ("low on potions" ...).
- Clear time (30-minute timer): "With this crew, the golem should fall in under 15 minutes."; a hedged time until 3 recorded runs; " Anywhere from <a> to <b>, depending on how the golem goes." from 3 minutes wide; "Last time you went in with them it took about <N minutes>. I'd say about <m> this time."; "#rI don't think this crew would get through before the forest closes.#k You only get half an hour in there." within 2 minutes of the timer; "#rMind the clock:#k ..." when the slow end passes it.
- Under the leader's pick: "With these, I'd say about <N minutes>." (+ " The ones I picked would get you through about <n> minutes sooner.") or "With these, I doubt you'd reach the golem in time."
- Cautions (two at most, about the golem 9300182 only): "<name> might not last long against the golem. Keep an eye on them." first, then "<name> will have a hard time landing hits on the golem."
- The stock start's refusals are 2133000.js's own texts, word for word.

## Tunables (32 new agent-engine.yaml keys, each with Values, Purpose and Technical)

All are appended at the end of the EPQ block of agent-engine.yaml, prefix `server.agents.capabilities.partyquest.epq.`.

| Key | Default |
| --- | --- |
| AgentEpqCompanionRun.COMPANION_ENTRY_WAIT_MS | 30000 |
| AgentEpqCompanionRun.TIMEOUT_SLACK_MS | 2000 |
| AgentEpqCompanionRun.LEADER_AT_EXIT_PX | 150 |
| AgentEpqCompanionRun.LEADER_HINT_MS | 20000 |
| AgentEpqHandOver.MARBLE_BATCH | 5 |
| AgentEpqHandOver.NEAR_PX | 400 |
| AgentEpqHandOver.REACH_PX | 60 |
| AgentEpqHandOver.TIMEOUT_MS | 30000 |
| AgentEpqHandOver.PICKUP_NUDGE_MS | 40000 |
| AgentEpqHandOver.RECOLLECT_MS | 150000 |
| AgentEpqHandOver.RECOLLECT_MAX | 2 |
| AgentEpqHandOver.DYING_HP_PERCENT | 25 |
| AgentEpqHandOver.STONE_TIMEOUT_MS | 120000 |
| AgentEpqCompanionRun.CLEAR_WAIT_MS | 60000 |
| AgentEpqCompanionRun.GOLEM_MARBLE_LEADER_FIRST_MS | 15000 |
| AgentEpqCompanionRun.FOUNTAIN_WAIT_MS | 45000 |
| AgentEpqCompanionRun.ALTAR_READY_WAIT_MS | 20000 |
| AgentEpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS | 45000 |
| AgentEpqCompanionRun.OBJECTIVE_LOST_CONFIRM_MS | 10000 |
| AgentEpqPaceProfile.YES_MS | 2000 |
| AgentEpqPaceProfile.TREES_EFFICIENCY | 0.30 |
| AgentEpqPaceProfile.BOTTLES_MS | 105000 |
| AgentEpqPaceProfile.MAZE_MS | 40000 |
| AgentEpqPaceProfile.MARBLES_MS | 300000 |
| AgentEpqPaceProfile.HANDIN_MS | 30000 |
| AgentEpqPaceProfile.CLIMB_MS | 165000 |
| AgentEpqPaceProfile.SPRITE_MS | 20000 |
| AgentEpqPaceProfile.ALTAR_MS | 25000 |
| AgentEpqPaceProfile.GOLEM_EFFICIENCY | 0.18 |
| AgentEpqPaceProfile.FRAGILE_TOUCHES | 5 |
| AgentEpqPaceProfile.WEAK_HIT_CHANCE | 0.6 |
| AgentEpqPaceProfile.PACE_EPOCH | 1 |

The desk constants are in server.living, not tunables: QUICK_S 900, CLOSE_TO_TIMER_S 120, RANGE_FROM_S 180, SEEN_RUNS 3, CAUTIONS 2 (EllinLines). Seats 4-6, her choice 6. Extras wait at x 164, 134, 104 ... on the floor (EpqEntrance).

## Re-pointed existing tests

Each changed only the session flag (true -> false) or used a stock-session helper. No assertion changed.

- 2.3: AgentEpqLineLogTest.theLobbyReadyLineIsLoggedAndStillSaidInPartyAndMapChat, AgentEpqTopUpTest.nobodyDrinksOnTheRecruitMap, AgentEpqDepartedMemberTest.anAgentNotYetRegisteredOnTheRecruitMapIsNoLeaver; AgentEpqFailureLogTest.theEventTimer (the companion-run clock).
- 2.5: AgentEpqDeadMemberRolesTest.aCorpseIsNoLurerAndItsBottleDoesNotCount, AgentEpqDepartedMemberTest.aDepartedAgentTakesNoStageTwoLurerSlot.
- 2.7: AgentEpqLineLogTest.theMarbleHandoffLineIsLoggedUnderItsMemberKey; AgentEpqVitalsLogTest.aNonLeaderMarbleDropNearEllinLogsWhereItWasMadeAndWhereItLands, .anUnknownLandingIsLoggedAsNone, .aFailedDropLogsNothing; AgentEpqDeadMemberRolesTest.aCorpsesMarblesDoNotCountTowardTheTwenty; AgentEpqProgressLogTest.theFirstFlowerRespawnIsLoggedAndEveryOneIsCounted; AgentEpqStageFourHelpTest.withNoLiveFlowerAtAllTodaysAssistStillRuns, .aHelpersCaptureIsLoggedAsNotItsOwn.
- 2.9: AgentEpqProgressLogTest.killingTheDummyAfterTheClearIsAnAssistAndStillHappens; AgentEpqEventEndTest.afterTheClearALeaderWhoLeftFailsNothing, .afterTheClearALeaderInTheEventOnTheExitMapReadsAsTheReward, .afterTheClearALeaderOnTheNormalExitMapIsNotTheReward, .afterTheClearAnAgentTheEventHoldsOnTheExitMapTakesTheRewardThoughThePhaseNeverReadIt.
- 2.11: AgentEpqEventEndTest.anAgentOnTheExitMapInTheRewardPhaseHitsTheRewardAsBefore.
- 3.1: AgentEpqLineLogTest.theBossLineIsLoggedOnceUnderItsClaimKey.
- 4.3: AgentEpqEventEndTest.anOfflineLeaderTakesOnlyTheWatchdogPath.
- AgentEpqDeathDepartureGateTest stayed green unchanged (4.4).

## Tests and guards at the tip

- Every item has a failing-first test and mutations, each red on its named test with the tree restored (session scratchpad: `p6g_epq_<item>_red.log`, `p6g_epq_mutations.json`).
- New Step 6 tests: EllinLinesTest, EpqEntranceTest, EllinDeskIntegrationTest, EllinDeskReadinessTest, EllinHookScriptTest. The KPQ and HPQ desk tests (LakelisDeskGoldenTest, ToryDeskTest, ToryDeskIntegrationTest, ToryHookScriptTest) and the prep-owned entrance tests stay green unchanged.
- The archcount equals the expected file ('cap partyquest->dialogue' +1 only). The yaml doc check finds no new undocumented key.
- Full suite: at 7d5975b28 (Step 6 code complete; this doc adds no code), 1840 classes and 10808 tests; the failing names are the 28 of the P6c baseline plus KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed(Path), red by design until the integrator regenerates server-knowledge.json. The guard classes match P0 (p6g_epq_guard_compare.py), TownLifeGeneratedProfilesTest included in the green.

## Integrator items

```text
I1 (integrator, not a user question; N3) Deploy p6i/held-dead-safety with or before p6g/epq when it is ready, and
   run L10b. If it is not ready, run L10b anyway and send the result to the session that owns it.
   [Z12] After that merge, also re-run AgentEpqDeathDepartureGateTest and AgentEpqShortHandedGateTest.
I2 (integrator; N4) Merge p6g/epq onto the live tip (f5b8c0ab5 or later), not onto 992878bae. The shop delta shares
   no file with this branch except agent-engine.yaml, where it edits lines near :3020, far from the EPQ block.
I3 (integrator) KnowledgeFileFreshnessTest.reExportMatchesTheCommittedFile and
   KnowledgeLintTest.unknownIdsAreErrorsOnlyWhileTheirInputsAreFresh fail after 6.4 by design. Regenerate
   scripts/config/knowledge/server-knowledge.json on the integration branch after the merge, never on p6g/epq.
I4 (integrator; Z9) After merging p6g/lpq and p6g/epq the Red Sign desk reads lpq=40/50. Run every LPQ desk test
   (LpqRedSignDeskTest, LpqLineupCompositionTest and any LPQ desk integration test). A red one whose fixture companion
   holds between the default and the lpq standard gets the lpq standard in its fixture on the integration branch (no
   assertion change). Tell the LPQ owner that its "Known limits" note (p6g_lpq_steps2to6_plan.md:1417-1419) is out of
   date.
I5 (integrator; Z1) p6g/lpq has its own exitAgentsOnly (AgentLpqTerminationService) and an after-clear leave, and HPQ's
   has the same shape. Ask the LPQ owner to check that a leave with no human left in the event cannot leave an empty
   LudiPQ instance open. This branch changes no LPQ or HPQ code; HPQ stays byte-for-byte.
```

Correction to I3: in this tree the red knowledge test is KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed (see Deviations). Regenerating server-knowledge.json after the merge clears it.

Two questions of the plan stay with the user, each with its default:

- Q5: once EpqEntrance is registered, companions in Ellin's lobby restock to the epq potion standard while the switch still says "kpq,hpq" (CompanionRestock reads PqEntrance.keyAt). Default: accept (live check L0b).
- Q6: at a large epq standard a companion short of Use slots shows "low on potions" at the desk and asks the party. Default: no change on this branch; the integrator asks the user after L5.

## Step 7: the live recipe (for the integrator; GM-first, no code)

```text
Rules: GM commands only for setup, fault injection and walking; never to do a companion's job. Abort a run with
'!epqtest stop' (never '!warp' out of the instance). Record every GM command used (logwatch prints 'Chr <name> used
command <Class>' lines).
Tools: VENV = C:/Users/ganre/Desktop/MapleServer/tools/cua/.venv/Scripts/python.exe; KIT = scratchpad/pqkit.
- Driver: write KIT/pqs/epq.py on the kpq/hpq pattern (stage handlers that hold near the companions; loot at his feet on
  the 'EPQ handover:' lines with the leader's loot key; Ellin up top on 930000400 via '!stepto npc 2133001'; the sprite
  via '!stepto npc 2133004'; the altar drop by dragging the stone from the Etc tab onto the floor x 301..475, with
  '!drop 4001163 1' at that spot as the recorded fallback when the drag fails; out00 via '!stepto out00' then 'up'; the
  fountain drop the same way; east00 via '!stepto east00' then 'up'; the death pop-up OK; the reconnect wrapper).
- Keys: Crewfriend loot 'b', jump 'z'; ohboy loot 'z', jump 'alt'; maple_client hold keys are lower case ('right',
  'left', 'up'); click the canvas before keys.
- Logs: 'python KIT/logwatch.py tail --pq EPQ --seconds 2400' and, in another shell,
  grep -E "EPQ (phase|line|handover|recollect|after clear|cleared run|tap-out|leader down|objective lost|parting|pace span|departure|departed|session|rescue|boss|checkpoint|vitals|potions|drop|top-up)|companion epq" logs/cosmic-log.log
- '!npc <id>' (N5) only when '!stepto npc <id>' finds no portal near that NPC, and never on a map where the
  companions walk to that NPC. Record each use.
- Order (N3, N4): deploy on the live tip (f5b8c0ab5 or later). Deploy p6i/held-dead-safety first if it is ready.
Crew and setup (Crewfriend 865 GM6 leads; the Step-0 crew Casse, Lioren, Pell, Halvard, Kestrel ACTIVE, loaded, alive):
1. '!setlevel me 48 auto', then '!setlevel party 48' (jobs kept; 44-55 band).
2. Potions to at least the deployed epq standard (PARTY_QUEST_STANDARDS in the deployed agent-engine.yaml): '!giveitem
   <name> 2000002 <hp>' and '!giveitem <name> 2000003 <mp>' per companion; Crewfriend '!item 2000002 100', '!item
   2000003 100'. Free slots: 4 Etc and enough Use (one per 100 potions plus one).
3. '!warp 300030100' (the companions follow).
L0 Deploy with "kpq,hpq". Pass: the epq pace warm in the boot log, no WARN from it; 'companion epq' desk installed; a
   click on Ellin with companions in the party is answered by the stock script.
L0b Restock before the switch (Q5, RV16): with "kpq,hpq", walk Crewfriend into 300030100 with one companion below the
   deployed epq standard. Record what it does. With Q5 = accept: no desk text (the click stays stock, Z10); the
   companion's own readiness trip to Mayoren 2130000 on 300000000 (Altaire Camp) and back, with its restock log lines.
   Then give every companion the standard with '!giveitem' before L1.
L1 Prod lane, full run, no skips: '!epqtest party prod 21', click Ellin within 30 s, play every stage as the leader.
   Pass: the phase chain PREPARING->ENTERING->STAGE_ONE..BOSS->REWARD->EXITING->COMPLETED (ENTERING may be skipped when
   you walk on at once); companions wait at portal 4 and both portal 3s until you go (E0 if you dawdle); E1..E15 in
   'EPQ line:' with lower-case text; 'EPQ handover: item=marbles ... place=FEET' and 'item=stone place=FEET'; the altar
   lines from the stone's finder; no 'EPQ rescue:' line and 'rescues={}' on release; the fountain (when the golem dropped
   its marble): 'EPQ drop: key=reward-fountain' or your own drop, 52000 EXP for all; 'EPQ session released:
   outcome=completed companionRun=true'; the party still six; the companions follow on 300030100; 'EPQ pace span:' lines
   for all ten keys (complete=true where marks exist); no companion chat other than section 11's lines, the host lines and
   persona chatter.
L1b Prod lane, targeted (each its own run; checkpoints allowed):
   a) '!epqtest checkpoint flowers': leave the first batch lying 150 s -> E10 at 40 s, 'EPQ recollect:' and E11 at 150 s.
   b) '!epqtest checkpoint climb': stay away from the bottom -> E13b or the walk to you; hold the stone 20 s -> E13w.
   c) '!epqtest checkpoint boss' + '!item 4001163 1': E14w/E14 before the drop; after the clear stand still 60 s -> E16w,
      'EPQ after clear: action=leave why=wait', companions out, run completed (no 'EPQ stalled').
   d) On 930000600 before the drop: '!hpmp me 0' (leader down, no golem yet) -> DEFEATED parting at once. Another run:
      drop the stone, then '!hpmp me 0' while a golem lives -> E20, companions keep fighting; press OK -> DEFEATED parting
      and the stock exile.
      [Z3] In the second run stay down until 9300180 dies and 9300181 revives ('EPQ boss: golem=9300181'): no 'EPQ
      parting:' in that gap.
   e) Prod lane tap-out: '!hpmp Lioren 0' on 930000100 (6 registered) -> 'EPQ departure: PROCEED', 'EPQ departed:', 'EPQ
      tap-out: accepted=false' (party prod has no host run), no failure, Lioren idle on 930000800.
   f) Golem's marble (when 9300182 drops it; 60 %): leave it 15 s -> E16m, then the work Agent takes it (E16t); stand still
      60 s -> it walks to you, E16h, the marble lands at your feet, then E16w and 'EPQ after clear: action=leave ...
      marble=handed'. Record "not seen" when no marble drops.
   g) After a clear, close the leader's client -> 'EPQ after clear: action=leave why=offline' in the next pass, the run
      completed, no 'EPQ session failure:'. In another run, close it before the clear -> 'EPQ parting: kind=GONE' and the
      failure in the next pass (not after 480 s).
      [Z1] In the first run also: 'EPQ companion exit closed the empty event:' once the companions are out; then a new
      '!epqtest party prod' party on that channel gets in with one Ellin click (no "Another party has already
      entered...").
   h) N1, the fountain window (when 9300182 drops its marble): pick the marble up yourself, take out00, drop it
      in the fountain (x -73..220 on 930000800) and walk straight to east00. Pass: no companion takes east00 before
      the fountain fires (about 5 s after your drop), 52000 EXP for everyone on the map, each companion leaves with
      an Altaire Fragment 4001198, E17c once and no E17a (you dropped it). Record "not seen" when no marble
      drops.
   i) N2, the stone lost on the boss map: '!epqtest checkpoint boss', then '!item 4001163 1'. Walk to x about
      -300 (left of the altar), drop the stone there and step away. Pass: E14m at about 20 s (by the narrator: in a
      checkpoint run nobody found the stone), then 'EPQ objective lost: ... item=stone' and the LOST parting about
      10 s after the stone expires (180 s), never the 480 s watchdog.
Switch flip (integrator's own commit "kpq,hpq,epq" plus the LivingWorldConfigCompanionPqTest pin), deploy, then:
L2 "kpq,hpq" -> stock Ellin; "kpq,hpq,epq" -> Ellin's lineup window.
L3 'Let me choose...'; a 7th member -> "Six is the most the forest can take. Take someone out first."
L4 Bench call from off the island (about 180 s ETA, RecallTravel.java:24): the arrival line in party chat.
L5 Restock at Ellin under the deployed epq standard: the store line ('Gone to the store' / RAN_TO_THE_STORE) and the trip
   to Mayoren 2130000 at Altaire Camp; Yes after they are back; a companion short of arrows/stars shows "low on ammo".
L6 gm0 leader: '!setgmlevel ohboy 6'; ohboy runs '!setlevel me 48 <job> auto' and '!setlevel <each companion> 48';
   '!setgmlevel ohboy 0'; ohboy leads a desk run with 4-5 of his companions. Pass: companion_performance 'epq' rows
   cleared, a complete pq_run_pace 'epq' row (not excluded), the clear time against the window's estimate.
L7 Zero drift: same party, no hold map, follow restored, CompanionDriftReport clean, no 'remains in owned recovery', the
   next Ellin click works.
L8 Six members with extras held on the floor left of Ellin's ledge; both rejoin.
L9 Two humans (Crewfriend a member in ohboy's run): stage 2 with two companions (one lurer works both sides).
L10 Desk run of six: '!hpmp <companion> 0' -> tap-out accepted, the host's tap-out line, HOLD on 930000800, E18 "... with
   five."; again -> "... with four."; the next death at four -> HOLD (corpse stays, run goes on); kill every remaining
   companion -> WENT_DOWN said by the last one, stock exile, companions follow afterwards.
L10b N3, a held corpse past five minutes: in a run at four registered, '!hpmp <companion> 0' on 930000100 while
   trees still stand ('EPQ departure: ... decision=HOLD' once), then wait six minutes without killing the trees.
   Pass: the corpse still lies on 930000100, no 'EPQ departed:' for it, the run carries on. If the corpse moves, that
   is the held-dead-safety defect: record it for that session and repeat after p6i/held-dead-safety is deployed.
L11 One TIMEOUT or a golem wipe: TIMEOUT or WENT_DOWN parting, stock exile, companions follow.
L12 Lakelis and Tory clicks unchanged (LakelisDeskGoldenTest text; Tory's window).
L13 Ellin click latency, tick percentiles, companion-ops backlog, full-rate holds released at the end.
L14 Video p6d-epq-companions.mp4 plus a 60 s highlight; the user signs off.
After the live days: '!setlevel <name> restore' for each companion and '!setlevel me restore'. Results go into
docs/P6D_EPQ_RESULTS.md on the integration branch and a memory note.
```

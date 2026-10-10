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

## Review fixes (lens A and lens B of cbb8e8879)

Each code change has a test that was red before it (red logs `p6g_epq_rv_<item>_red.log` in the session scratchpad) and mutations that turn it red (`p6g_epq_mut_srv1.json`, `p6g_epq_mut_srv2.json`, results in `p6g_epq_mutations.json`, 16 of 16 killed).

| Commit | Finding | What changed |
| --- | --- | --- |
| 4186af715 | A1 (medium): a release that ran whole between holdFullRate and requireFullRateSimulation left a companion at full rate after the run | AgentEpqSession.holdFullRate returns the marked answer; the take-back clears by it whether or not the mark is still there. Tests: AgentEpqFullRateTest (review A's probe, plus a pin that a requirement set by something else stays). |
| 061339d01 | A2, A3 (low): test gaps G4 and K3 | AgentEpqObjectiveLostTest.aBrokenBoxThatStaysOnTheMapIsNoBoxLeft (a broken 3002001 box stays alive and inactive); AgentEpqCompanionAfterClearTest.aStallAfterTheCompanionsLeftCompletesTheRun ('action=complete why=stall'). Mutants G4 and K3 now red. |
| 367cee95e | A4 (low): CLEAR_WAIT_MS and FOUNTAIN_WAIT_MS ranges overlap | The stuck clock on 930000800 counts CLEAR_WAIT_MS from the end of the fountain wait (AgentEpqCompanionPlay.stuckAtTheFountain). With the shipped values a stuck companion leaves at 105 s instead of 60 s. Both Technical lines say so. |
| 30f32e000 | B6 (medium): identical at-feet lines and nudges in a burst | One E9/E9b/E11 per convergence within LEADER_HINT_MS (E9t always said, and it speaks for a carrier right after it); one E10/E13n per item within PICKUP_NUDGE_MS. |
| 2431ec7cc | B8 (low): dying-drop lines contradicted what happened | A dying carrier's stack is re-dropped with the at-feet line (E9/E13), not E11/E13r; E19/E19s lose "almost" and "grab them"; 'EPQ recollect: ... dying=' now tells the truth (it always said false). |
| 54474a953 | B9, B10 (low): "poison rain"; a tally of the thorns | E15c names the seal (Mob.wz skill 120, first on 9300182); the second poison says nothing. |
| 2e19df983 | B11 (low): no coaching for a marble outside the fountain | New E17m fountain-miss. |
| 370319997 | B12 (low): "let's head out" said from outside | Outside forms of every parting, chosen by whether the speaker still stands in the bound event. |
| (this commit) | B1-B5, B7, B13 (docs) | The line table above; the Step 7 recipe fixes below. |
| (fix/epq-golem-marble-fountain) | Live 2026-10-08: the holder of the golem's marble left with it ('marble=held') | The leave went at the hand-over's time-out with the walk to the leader still open, and the hand-over's own fallback (drop where it stands past TIMEOUT_MS) could never come first. The event's exit put the holder on the public 930000800 and the run was released in the same pass, so nothing could drop it in the fountain. Now a holder still holding it as the leave goes leaves it on its map first (E16h beside him, else the new E16x), and E16t no longer promises the fountain without the portal. |
| (fix/epq-after-clear-adds) | Live 2026-10-07 and 2026-10-08: after 'EPQ boss: cleared' the golem's last adds (9300177-9300179) kept hitting the companions, who stood still (10-07: Halvard, Pell and Kestrel went down at x -540 with no HP potion left; 10-08: the holder's walk to the leader never ended within 30 s) | AgentEpqCoordinator.boss fights only while a golem lives; past the clear AgentEpqCompanionRun.afterClearMember called stop for every companion without an errand. Now, until the leave goes, a companion fights an add within ADD_THREAT_PX first, then does its golem's-marble errand (the work Agent's take, the hand-over), then fights the adds left anywhere on the map, and stands only when none is left ('EPQ after clear adds: state=FIGHT' once, 'state=GONE' once). The leave, its timers, the hand-overs and stock runs are unchanged. Tests: AgentEpqAfterClearAddsTest. |
| (fix/epq-sealed-basic-attack) | Live 2026-10-08 (session ad7e9a29): 9300182 lived 14 min; Halvard, Kestrel and Casse went down and the run was stopped | 9300182's seal (MobSkill 120 level 3: 70 % a cast, 30 s, everyone within 450 px, every 35 s) takes every attack skill, and EPQ requires an attack skill of each member every tick (AgentEpqCoordinator.tickMember, from the graft), so AgentCombatPlanRuntime.planAttack gave a sealed companion no plan at all while it stood in melee drinking potions; meanwhile HEAL_M (MobSkill 114 level 22) healed it 19k-40k a minute below 70 %. Now a companion run's sealed member falls back to its basic attack until the seal ends (AgentCombatSkillConstraintState.releaseAttackSkill); a stock run keeps the requirement. New 'EPQ boss hp:' every BOSS_HP_LOG_INTERVAL_MS (30 s) while a golem lives: its HP, the members on 930000600 and who is sealed. Tests: AgentEpqSealedCompanionTest; BotCombatManagerTest.aSealedAgentHasNoAttackWhileAnAttackSkillIsRequiredAndItsBasicAttackWithout pins the planner. Not changed: the rest rule (it starts only once the potions are gone and ends at POT_STOP potions at any HP, and a member pinned at x -540 must cross the golem to reach x 1020). |
| (fix/epq-golem-rest) | Live 2026-10-08 (sessions ad7e9a29 and e65d82a8): Halvard and Kestrel went down at x -540 with the golem 40-300 px to their right; Kestrel's rest started at 501 HP and he was down 47 ms later; Halvard's rest ended 6 s after it started at 15-22 % HP, when a party member shared 110-158 potions, and he was down 14-16 s later | EPQ base fix 7 rested only below POT_STOP potions and 40 % HP (after the autopot had drained the stock in melee), sent a member whose own floor end was in the golem's reach to the far end across the golem, and ended at POT_STOP potions at any HP. Now a golem-fight member rests once its HP falls under REST_HP_FRACTION (0.5, with or without potions) or while it holds fewer than POT_STOP HP potions; it backs off on its own side of the golem (its floor end, or its spot when it stands past that end or already out of reach), never across it and never toward it; it drinks one HP potion every TOP_UP_INTERVAL_MS (600 ms) while under RESUME_HP_FRACTION (0.8), on the walk too; and it rejoins only at RESUME_HP_FRACTION with POT_STOP potions. 'EPQ boss rest:' adds why= (START/MOVE: hp or pots; END: safe, clear or left-map) and drinks=. A companion run's rester says E15r or E15d once a run each. Stock runs rest the same, without the lines. Tests: AgentEpqBossRestTest; AgentEpqCompanionLinesTest lints the two lines. |

Recipe fixes in Step 7: the restore trap (B1: never '!setlevel me restore' or '!setlevel party restore' on Crewfriend), '!hpmp 0' for the leader (B2), L1b i with '!drop' only (B3), L10b on 930000200 (B4), the pass items for where the companions stand after an after-clear leave (B7), and the log proofs (B13: a case-insensitive grep that also matches 'EPQ companion exit closed the empty event:', 'EPQ member left the run and was ejected' and 'companion EPQ'; L0 without the desk-install line, which does not exist; 'decision=PROCEED'; E16m at once in L1b f; E14w only when a companion tops up; COMPLETED only on 'EPQ session released:').

No finding was skipped. Finding B13 arrived cut off after its item 6; items 1-6 are fixed. Re-pointed tests (assertions changed on purpose): AgentEpqCompanionRewardTest.aCompanionEast00KeepsRefusingIsTakenOutThroughTheEventsExit (60 s to 105 s, A4); the E19/E19s texts in AgentEpqMarbleHandOverTest.aCarrierAboutToGoDownLeavesItsStackAndAnotherTakesItBackAtOnce and AgentEpqStoneHandOverTest.aCarrierAboutToGoDownLeavesTheStoneWhereItStands (B8); AgentEpqCoordinatorVoiceTest's companion-run list without the second poison's line (B10).

## Companion lines (map chat, as built)

`<leader>` and `<name>` are player names. AgentEpqCompanionLinesTest checks every line: lower case except names, no digits, no counters or arrows, and at most 98 characters with a 12-letter name. Each line is said once per run under its key, which the log shows as `EPQ line: session=<id> key=<key> speaker=<name> text=<line>`. The labels are the binding plan's (section 11). A suffix in a key is the hand-over's number (`<n>`), the stack's map object id (`<stack>`), the member's character id (`<id>`) or the map id (`<map>`).

"Narrator" is the first companion (session order) that is online, up, in the run's bound event and on the leader's map; a line with no narrator waits for a later pass. "The finder" is the first companion seen holding the purple stone; a finder line falls back to the narrator when the finder is not on the leader's map. Lines marked review B changed after the review of cbb8e8879.

| Label | Key | Trigger | Speaker | Line |
| --- | --- | --- | --- | --- |
| E0 | exit-nudge-`<map>` | A companion waited LEADER_HINT_MS (20 s) at a walking exit (portal 4 of 930000000, portal 3 of 930000100 and 930000200) with the leader on that map | The waiting companion | ready when you are, `<leader>`. the portal's over here. |
| E1 | trees | The leader is on 930000100 | Narrator | these trees are poisoned. we clear every one and the way opens. |
| E1b | trees-done | No Poisoned Lord Tree is left | Narrator | that's the last tree. the portal's on the right. |
| E2 | bottles | The leader is on 930000200 | Narrator | the bugs carry the poison. we pull them into the fountain first, then hit them in the water. |
| E3 | bottle-hint | The leader held a diluted poison for LEADER_HINT_MS | Narrator | `<leader>`, drop that diluted poison by the thorns on the right. |
| E4a | stage2-progress-1 | The first diluted poison is on the thorns | The work Agent | first poison's on the thorns. keep them coming. |
| E4b | stage2-progress-2 | The second poison | Nobody (review B: no running tally) | (nothing; was "halfway through the thorns.") |
| E4c | stage2-progress-3 | The third poison | The work Agent | one more and the thorns are gone. |
| E5 | stage2-progress-4 | The thorns open | The work Agent | the thorns are gone! the exit's on the right. |
| E6 | maze | The leader is on 930000300 | Narrator | it's a maze. wait here, we'll find ellin. don't talk to the mileposts, they send you out. |
| E7 | maze-found | A companion reaches maze Ellin first | That companion | found ellin! she's bringing everyone over. |
| E8 | flowers | The leader is on 930000400 | Narrator | ellin hands out purification marbles. hit a poison flower till it's weak, then use a marble on it. |
| E9 | marbles-at-feet-`<n>` | A carrier dropped monster marbles at the leader's feet (or right next to him). Review B: one line speaks for every carrier converging on him within LEADER_HINT_MS | The carrier | the monster marbles are at your feet, `<leader>`. pick them up. / ... are right next to you, `<leader>`. pick them up. |
| E9t | marbles-twenty | The drop that makes twenty (always said once) | The carrier | that's all twenty at your feet, `<leader>`. pick them up and give them to ellin up top. / ... right next to you ... |
| E9b | marbles-by-ellin-`<n>` | The carrier could not reach him, or its walk passed TIMEOUT_MS, so the stack lies by Ellin (one line per convergence, review B) | The carrier | `<leader>`, couldn't get to you, left the marbles by ellin. |
| E10 | marbles-nudge-`<stack>` | A stack dropped for him has lain PICKUP_NUDGE_MS (40 s). Review B: one nudge speaks for every stack that comes due within 40 s after it | The stack's dropper | `<leader>`, the monster marbles are still on the ground. grab them before they vanish. |
| E11 | marbles-recollected-`<n>` | A stack he left lying was picked back up before it expired (RECOLLECT_MS) and dropped for him again; not a dying carrier's stack (review B) | The re-collector | picked the marbles back up so they don't vanish. here they are again. |
| E11b | marbles-held | He holds all twenty and no hand-over said so | Narrator | you've got all twenty. ellin's up top, give them to her. |
| E11c | marbles-handin-nudge | He held the twenty for LEADER_HINT_MS | Narrator | `<leader>`, talk to ellin with the marbles and we're through. |
| E19 | marbles-dying-`<id>` | A carrier at 25 % HP or less with no HP potion drops its marbles where it stands (another companion takes them to him at once, with E9) | The dying carrier | out of pots, leaving the marbles here. (review B; was "almost out of pots, leaving the marbles here, grab them.") |
| E12 | climb | The leader is on 930000500 | Narrator | long climb. the stone's in one of the boxes up top. wait by the sprite, we'll bring it down. |
| E13f | stone-found | A companion first holds the purple stone | The finder | found the purple stone! bringing it down to you. |
| E13 | stone-at-feet-`<n>` (the plan's key had no suffix) | The stone dropped at his feet or right next to him | The carrier | the purple stone is at your feet, `<leader>`. pick it up and show it to the sprite. / ... right next to you ... |
| E13b | stone-by-sprite-`<n>` (the plan's key had no suffix) | The carrier could not reach him, or its walk passed STONE_TIMEOUT_MS | The carrier | `<leader>`, couldn't get to you, left the stone by the sprite. |
| E13n | stone-nudge-`<stack>` | The stone has lain PICKUP_NUDGE_MS | The dropper | `<leader>`, the stone's still on the ground. the sprite needs to see it. |
| E13r | stone-recollected-`<n>` | The stone he left lying was picked back up and dropped again (not a dying carrier's) | The re-collector | picked the stone back up so it doesn't vanish. here it is again. |
| E13w | stone-held | He held the stone for LEADER_HINT_MS | The finder | `<leader>`, talk to the sprite with the stone. she'll take us to the altar. |
| E19s | stone-dying-`<id>` | A dying carrier drops the stone where it stands | The dying carrier | out of pots, leaving the stone here. (review B; was "almost out of pots, leaving the stone here, grab it.") |
| E14w | boss-topup | A companion starts a top-up on 930000600 before the golem fight | That companion | sec, drinking up first. |
| E14 | altar-ready | He holds the stone, and nobody tops up or ALTAR_READY_WAIT_MS passed | The finder | ready when you are, `<leader>`. drop the purple stone right under the altar. |
| E14n | altar-nudge | He still holds the stone LEADER_HINT_MS after E14 | The finder | `<leader>`, drop the stone under the altar and the golem comes out. |
| E14m | stone-floor | A purple stone has lain outside the altar's area for LEADER_HINT_MS | The finder | `<leader>`, the stone has to land right under the altar. pick it up and try again. |
| E15a | golem-9300180 | The first golem is alive | Narrator | here it comes! everyone on the golem. |
| E15b | golem-9300181 | The charged golem is alive | Narrator | it's charging up! keep hitting it. |
| E15c | golem-9300182 | The super-charged golem is alive | Narrator | super-charged now, careful, it can seal your skills! (review B; was "... watch out for the poison rain!") |
| E15r | boss-rest-`<id>` | A companion's golem rest starts with POT_STOP or more HP potions (its HP fell under REST_HP_FRACTION); once a run each | That companion | backing off to pot up, be right back. |
| E15d | boss-rest-dry-`<id>` | A companion's golem rest starts with fewer than POT_STOP HP potions; once a run each | That companion | out of pots, i'll hang back till i get some. |
| E20 | leader-down | He is at 0 HP on 930000600 during the golem fight | Narrator | `<leader>`, hang in there! don't press ok yet, we can still beat it. |
| E16 | cleared | The clear, with him standing on 930000600 | Narrator | we did it! only you can open the way out, `<leader>`. the portal on the right. |
| E16d | cleared (same key as E16: only one of the two is said) | The clear, with him down | Narrator | we got the golem! sorry you went down, `<leader>`. see you outside. |
| E16m | golem-marble | The golem's marble lies on 930000600 after the clear (said at once) | Narrator | the golem dropped a marble, `<leader>`! grab it, it goes in the fountain outside. |
| E16t | golem-marble-taken | The work Agent picked it up (GOLEM_MARBLE_LEADER_FIRST_MS, 15 s, after the drop) | The work Agent | got the golem's marble. take the portal out and i'll drop it in the fountain. (fix 2026-10-08; was "got the golem's marble. i'll drop it in the fountain outside.") |
| E16h | golem-marble-handed | Before the leave, the holder dropped it at his feet, or the leave came due (TIMEOUT_MS after its decision) and the marble landed right next to him | The holder | `<leader>`, the golem's marble is at your feet. drop it by the fountain outside. / ... right next to you ... |
| E16x | golem-marble-left | Before the leave the holder has no route to him, or the leave came due (TIMEOUT_MS after its decision) before it got to him: it leaves the marble where it stands, on his map (fix 2026-10-08, new; the no-route drop was silent before) | The holder | `<leader>`, couldn't reach you, so the golem's marble is here. drop it by the fountain outside. |
| E16w | wait-outside | The companions leave through the event's exit while he still stands in the event | Narrator | we'll head out and wait for you outside. |
| E17a | fountain-dropped | A companion dropped the golem's marble in the fountain | The holder | marble's in the fountain! exp for everyone. |
| E17b | fountain-hint | He holds the golem's marble on 930000800 | The first companion whose tick sees it | `<leader>`, drop the marble by the fountain. exp for everyone, and a fragment on the way out. |
| E17m | fountain-miss | The golem's marble has lain on 930000800 outside the fountain (x -73..220) for LEADER_HINT_MS with him on the map (review B, new) | The first companion whose tick sees it | `<leader>`, the marble has to land right in the fountain. pick it up and try again. |
| E17c | see-you | The first companion takes east00 | That companion | see you outside! |
| E18 | drop-`<id>` | A member left the run and it goes on | Narrator | `<name>` had to go. we'll keep going with four. / ... with five. |

Partings (key `failed`; said once, before the clear only; SILENT says nothing). The speaker is the given one (the dying companion for WENT_DOWN from the departure gate), else the narrator, else any companion standing in the event, else the first online companion. Review B: a speaker that already stands outside the event (EllinPQ's end moved everyone to 930000800: the leader pressed OK, went offline, or the event ended) says the outside form.

| Kind | In the event | From outside (review B) |
| --- | --- | --- |
| GENERIC | we can't finish this one. let's head out. | we couldn't finish this one. |
| WENT_DOWN (others) | too many of us went down. let's regroup outside. | too many of us went down. |
| WENT_DOWN (self) | sorry, i went down too. let's regroup outside. | sorry, i went down too. |
| LEFT | `<name>` left, and we need four for the forest. let's head out. / we're short now, and we need four for the forest. let's head out. | `<name>` left, and we need four for the forest. / we're short now, and we need four for the forest. |
| DEFEATED | `<leader>`, you went down, so we can't finish this one. let's head out. | `<leader>`, you went down, so we couldn't finish this one. |
| GONE | `<name>` dropped out, so we can't finish this one. let's head out. | `<name>` dropped out, so we couldn't finish this one. |
| LOST | we lost the monster marbles (the purple stone), so there's no way to finish now. let's head out. | we lost the monster marbles (the purple stone), so there was no way to finish. |
| TIMEOUT | we ran out of time. we'll get the golem next time. | (the same) |
| TOO_LONG | we've been stuck too long. let's call it and head out. | we were stuck too long, so we called it. |

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
| AgentEpqCoordinator.BOSS_HP_LOG_INTERVAL_MS (fix/epq-sealed-basic-attack, later) | 30000 |

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
- After the review fixes, full suite at 370319997 (the last code commit; the docs commit adds no code): 1840 classes and 10826 tests; the failing names are the 28 of the P6c baseline plus KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed(Path), as before. TownLifeGeneratedProfilesTest is green. The guard classes match P0 (p6g_epq_guard_compare.py), the archcount equals the expected file, and the yaml doc check finds no undocumented key. The EPQ, Ellin desk and guard classes alone: 76 classes, 512 tests, all green.
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

Updated after the review of cbb8e8879: B1 (the restore trap), B2 ('!hpmp 0'), B3 ('!drop' and the bag stone), B4 (L10b), B7 (where the companions stand after an after-clear leave), B13 (the log proofs), and pass items for the review B lines (L1, L1b j and k). The labels and keys are the line table's.

```text
Rules: GM commands only for setup, fault injection and walking; never to do a companion's job. Abort a run with
'!epqtest stop' (never '!warp' out of the instance). Record every GM command used (logwatch prints 'Chr <name> used
command <Class>' lines).
Tools: VENV = C:/Users/ganre/Desktop/MapleServer/tools/cua/.venv/Scripts/python.exe; KIT = scratchpad/pqkit.
- Driver: write KIT/pqs/epq.py on the kpq/hpq pattern (stage handlers that hold near the companions; loot at his feet on
  the 'EPQ handover:' lines with the leader's loot key; Ellin up top on 930000400 via '!stepto npc 2133001'; the sprite
  via '!stepto npc 2133004'; the altar drop by dragging the stone from the Etc tab onto the floor x 301..475, with
  '!drop 4001163 1' at that spot as the recorded fallback when the drag fails ([B3] '!drop' makes a new stone at his
  feet and leaves the one in his bag; east00 removes it at the end); out00 via '!stepto out00' then 'up'; the
  fountain drop the same way; east00 via '!stepto east00' then 'up'; the death pop-up OK; the reconnect wrapper).
- Keys: Crewfriend loot 'b', jump 'z'; ohboy loot 'z', jump 'alt'; maple_client hold keys are lower case ('right',
  'left', 'up'); click the canvas before keys.
- Logs: 'python KIT/logwatch.py tail --pq EPQ --seconds 2400' and, in another shell,
  grep -iE "EPQ (phase|line|handover|recollect|after clear|cleared run|tap-out|leader down|objective lost|parting|pace span|departure|departed|session|rescue|boss|checkpoint|vitals|potions|drop|top-up|member left|companion exit)|companion epq" logs/cosmic-log.log
  ([B13] case-insensitive: it also matches 'companion EPQ ...', 'EPQ companion exit closed the empty event:' and
  'EPQ member left the run and was ejected')
- '!npc <id>' (N5) only when '!stepto npc <id>' finds no portal near that NPC, and never on a map where the
  companions walk to that NPC. Record each use.
- Order (N3, N4): deploy on the live tip (f5b8c0ab5 or later). Deploy p6i/held-dead-safety first if it is ready.
Crew and setup (Crewfriend 865 GM6 leads; the Step-0 crew Casse, Lioren, Pell, Halvard, Kestrel ACTIVE, loaded, alive):
0. Note Crewfriend's level before step 1 (the end of the recipe sets him back to it; never a restore, B1).
1. '!setlevel me 48 auto', then '!setlevel party 48' (jobs kept; 44-55 band).
2. Potions to at least the deployed epq standard (PARTY_QUEST_STANDARDS in the deployed agent-engine.yaml): '!giveitem
   <name> 2000002 <hp>' and '!giveitem <name> 2000003 <mp>' per companion; Crewfriend '!item 2000002 100', '!item
   2000003 100'. Free slots: 4 Etc and enough Use (one per 100 potions plus one).
3. '!warp 300030100' (the companions follow).
L0 Deploy with "kpq,hpq". Pass: no WARN "EPQ's pace profile could not be warmed", "EPQ's monsters could not be
   warmed for the lineup" or "PQ pace profile epq did not warm" in the boot log ([B13] a good warm and the desk's
   install log nothing); a click on Ellin with companions in the party is answered by the stock script.
L0b Restock before the switch (Q5, RV16): with "kpq,hpq", walk Crewfriend into 300030100 with one companion below the
   deployed epq standard. Record what it does. With Q5 = accept: no desk text (the click stays stock, Z10); the
   companion's own readiness trip to Mayoren 2130000 on 300000000 (Altaire Camp) and back, with its restock log lines.
   Then give every companion the standard with '!giveitem' before L1.
L1 Prod lane, full run, no skips: '!epqtest party prod 21', click Ellin within 30 s, play every stage as the leader.
   Pass: 'EPQ phase:' lines PREPARING->ENTERING->STAGE_ONE..BOSS->REWARD->EXITING (ENTERING may be skipped when you
   walk on at once), then 'EPQ session released: outcome=completed companionRun=true' ([B13] COMPLETED has no 'EPQ
   phase:' line); companions wait at portal 4 and both portal 3s until you go (E0 if you dawdle); the 'EPQ line:' keys
   of the line table for each stage you reached, with lower-case text and no stage2-progress-2; 'EPQ handover:
   item=marbles ... place=FEET' and 'item=stone place=FEET'; carriers converging on you say one at-feet line between
   them (plus E9t), never two identical E9 within 20 s, and stacks dropped together get one E10; the altar lines from
   the stone's finder; no 'EPQ rescue:' line and 'rescues={}' on release; the fountain (when the golem dropped its
   marble): 'EPQ drop: key=reward-fountain' or your own drop, 52000 EXP for all; the party still six; the companions
   follow on 300030100; 'EPQ pace span:' lines for all ten keys (complete=true where marks exist); no companion chat
   other than the line table's lines, the host lines and persona chatter.
L1b Prod lane, targeted (each its own run; checkpoints allowed):
   a) '!epqtest checkpoint flowers': leave the first batch lying 150 s -> E10 at 40 s, 'EPQ recollect:' and E11 at 150 s.
   b) '!epqtest checkpoint climb': stay away from the bottom -> E13b or the walk to you; hold the stone 20 s -> E13w.
   c) '!epqtest checkpoint boss' + '!item 4001163 1': E14 before the drop; [B13] E14w only when a companion starts a
      top-up on 930000600 (a checkpoint crew usually arrives at full HP: then record "not seen"). [B3] If the drag
      fails, '!drop 4001163 1' leaves the '!item' stone in your bag (east00 removes it). After the clear stand still
      60 s -> E16w, 'EPQ after clear: action=leave why=wait', companions out, 'EPQ session released:
      outcome=completed' (no 'EPQ stalled').
      [B7] Then take out00 and east00 yourself. Pass: within about 60 s every companion that left early stands on
      300030100 or follows you again; no 'remains in owned recovery' line; CompanionDriftReport clean. The event's
      exit puts them on the public 930000800, which has the same map id as the instance's reward map, and the
      follow's map sync matches by map id (AgentFollowMapSyncService.syncFollowMap): while you stand on the
      instance's 930000800 they stay on the public copy, and they should warp to you once you take east00. If they
      stay there after that, record it as a defect before the switch flip.
   d) On 930000600 before the drop: '!hpmp 0' (leader down, no golem yet) -> DEFEATED parting at once. Another run:
      drop the stone, then '!hpmp 0' while a golem lives -> E20, companions keep fighting; press OK -> DEFEATED parting
      and the stock exile. [B2] One parameter targets yourself; '!hpmp me 0' looks for a player named "me". [Review B]
      When the event has already moved everyone out after OK, the parting reads "<leader>, you went down, so we
      couldn't finish this one." (no "let's head out"); a companion still inside says today's line.
      [Z3] In the second run stay down until 9300180 dies and 9300181 revives ('EPQ boss: golem=9300181'): no 'EPQ
      parting:' in that gap.
   e) Prod lane tap-out: '!hpmp Lioren 0' on 930000100 (6 registered) -> 'EPQ departure: ... decision=PROCEED' [B13],
      'EPQ departed:', 'EPQ tap-out: accepted=false' (party prod has no host run), no failure, Lioren idle on
      930000800.
   f) Golem's marble (when 9300182 drops it; 60 %): E16m at once [B13]; leave it lying 15 s -> the work Agent takes it
      (E16t); stand still 60 s -> it walks to you, E16h, the marble lands at your feet, then E16w and 'EPQ after
      clear: action=leave ... marble=handed'; then the [B7] pass item of c. Record "not seen" when no marble drops.
      [Fix 2026-10-08] When its walk does not end within 30 s of the leave's decision, the holder still never leaves
      with the marble: E16h "right next to you" or E16x, an 'EPQ handover: item=golem-marble ...' line, then E16w and
      'marble=handed' or 'marble=dropped', never 'marble=held'; the marble lies on 930000600 and no companion holds
      4001164 on 300030100 afterwards.
   g) After a clear, close the leader's client -> 'EPQ after clear: action=leave why=offline' in the next pass, the run
      completed, no 'EPQ session failure:'; when you log back in, the [B7] pass item of c (they follow you again). In
      another run, close it before the clear -> 'EPQ parting: kind=GONE' and the failure in the next pass (not after
      480 s); a companion still inside says "... dropped out, so we can't finish this one. let's head out.", one the
      event already moved out says "... so we couldn't finish this one." [Review B].
      [Z1] In the first run also: 'EPQ companion exit closed the empty event:' once the companions are out; then a new
      '!epqtest party prod' party on that channel gets in with one Ellin click (no "Another party has already
      entered...").
   h) [Fix 2026-10-08, adds after the clear] '!epqtest checkpoint boss' + '!item 4001163 1', drop the stone, fight the
      golem chain to 'EPQ boss: ... cleared'. If no add is left at the clear, stand by the companions and type '!spawn
      9300179 3', then '!spawn 9300177 2' (setup only; record both). Then stand still. Pass: 'EPQ after clear adds: ...
      state=FIGHT' at once; the companions attack the adds; 'EPQ after clear adds: ... state=GONE' once they are dead;
      no 'EPQ member down:' line after the clear; then at 60 s E16w and 'EPQ after clear: action=leave why=wait', with
      'marble=handed' or 'marble=none' when the golem dropped its marble or not, never 'marble=held'.
      Second run, marble with adds: when 9300182 drops its marble, spawn the adds next to it at once. Pass: E16t at
      about 15 s, the holder fights an add on it and then walks to you, 'EPQ handover: item=golem-marble ...
      place=FEET'. Extra grep: grep -E "EPQ (after clear adds|member down|boss: .* cleared)" logs/cosmic-log.log
   i) [Fix 2026-10-08, sealed companions] '!epqtest checkpoint boss' + '!item 4001163 1', drop the stone and fight
      the golem chain; watch 9300182. Pass: an 'EPQ boss hp:' line every 30 s from the first golem on, with
      'sealed=' naming the members under the seal after each 'skill=SEAL' cast; between two lines the pct drops while
      'sealed=' is not '-' (the sealed companions still hit it with their basic attacks); the golem dies ('EPQ boss:
      ... cleared'). 9300182 seals on its own about every 35 s; '!debuff' skips Agents (CommandTargetPolicy), so
      there is no GM way to seal a companion. Record each line's pct and sealed= for the report.
      Extra grep: grep -E "EPQ boss (hp|rest)|EPQ member down|skill=(SEAL|HEAL_M)" logs/cosmic-log.log
   h) N1, the fountain window (when 9300182 drops its marble): pick the marble up yourself, take out00, drop it
      in the fountain (x -73..220 on 930000800) and walk straight to east00. Pass: no companion takes east00 before
      the fountain fires (about 5 s after your drop), 52000 EXP for everyone on the map, each companion leaves with
      an Altaire Fragment 4001198, E17c once and no E17a (you dropped it). Record "not seen" when no marble
      drops.
   i) N2, the stone lost on the boss map: '!epqtest checkpoint boss' ([B3] no '!item'). Walk to x about -300 (left of
      the altar), run '!drop 4001163 1' there and step away, so the only stone lies on the floor. Pass: E14m at about
      20 s (by the narrator: in a checkpoint run nobody found the stone), then 'EPQ objective lost: ... item=stone' and
      the LOST parting about 10 s after the stone expires (180 s), never the 480 s watchdog.
   j) [Review B] The fountain miss (when 9300182 drops its marble): pick it up, take out00, drop it on 930000800
      outside the fountain (x below -73 or above 220) and wait. Pass: E17m once, about 20 s later, from one companion;
      then pick it up and drop it in the fountain: 52000 EXP for everyone on the map. Record "not seen" when no marble
      drops.
   k) [Review B] When a carrier runs out of HP potions on 930000400 or 930000500 (not forced; record "not seen"
      otherwise): E19/E19s "out of pots, leaving the ... here.", another companion takes the stack at once and drops it
      at your feet with E9/E13 (never E11/E13r), and 'EPQ recollect: ... dying=true'.
   l) [Fix 2026-10-09, golem rest] '!epqtest party prod', '!item 4001163 1', then '!epqtest checkpoint boss'; drop
      the stone and fight the golem chain with the companions. Watch each companion's HP and potion count ('EPQ
      vitals:'). Pass, for every 'EPQ boss rest:' line: START says why=hp under half HP with potions left, or why=pots under POT_STOP potions; no START has hpPots=0 at under 20 % HP unless the
      member entered without potions; the restPoint x is on the member's side of the golem x (a member at x -540 with
      the golem to its right rests at x -540, never x 1020); drinks= grows between START and END; END says why=safe
      only with hp at or over 80 % and hpPots at or over POT_STOP (or why=clear / why=left-map). In map chat each
      resting companion says "backing off to pot up, be right back." or "out of pots, i'll hang back till i get some."
      at most once each. Fail: 'EPQ member down:' for a companion within 15 s after its own 'EPQ boss rest: ...
      state=END why=safe'. A why=pots rest needs a companion that runs its stock down (record "not seen" if none
      does).
      Extra grep: grep -E "EPQ boss (hp|rest)|EPQ member down|EPQ down detail|key=boss-rest" logs/cosmic-log.log
   m) [Review of the golem rest fix, 2026-10-09] A dry companion pinned at the wall, and its asks for potions. GM-first:
      1. Before the run, say 'pots' to each companion. Pick one with fewer than 10 HP potions (a newly granted persona
         or one that ran dry before). If none has fewer than 10, record "not forced" and use L1b l's natural drain.
      2. Put the dry companion and at least one companion with more than 300 HP potions in the party.
      3. Run '!epqtest party prod', '!item 4001163 1', then '!epqtest checkpoint boss'.
      4. Stand at the left wall (x about -540, '!pos') so the companions gather there, then drop the stone.
      Pass, dry companion pinned ('EPQ boss rest: ... state=START why=pots restPoint=(-540,150) golem=(x,150)' with x
      at most -40): at full HP it attacks from the wall (a ranged class hits the golem, a melee class hits what comes
      into its reach) and never walks right of the golem. Then '!hpmp <name> <a value under half its max HP>' (this
      sets MP too): it stops hitting the golem but still hits an add that reaches it. '!healagent <name>': it hits the
      golem again.
      Pass, asks: 'EPQ boss rest ask: ... result=sent asks=1' at START, then one line about every 30 s while hpPots
      stays under 10 (result=held while the share's 30 s cooldown or 10-minute no-donor backoff holds it). A donor
      with more than 300 HP potions says its offer line in map chat, the stack lands, the ask lines stop, and the rest
      ends with 'state=END why=safe' at 80 % HP or more.
      [Review of a3a577489] While the pinned companion rests under 40 % HP with an add on it, map chat has no "low on
      pots!! walking to you" from it and it does not glare. While no donor qualifies, consecutive 'result=held' lines
      come about 30 s apart, never about 60 s apart.
      [Review of 638f7a198] After the clear, if an add is left (or '!spawn 9300179 3', as in L1b h), run '!hpmp <name>
      <a value under 40 % of its max HP>' on the dry companion while it fights the add. Pass: no "low on pots!!
      walking to you" and no glare from it within the next 45 s.
      Fail: the pinned companion walks right of the golem; it stands idle at full HP while an add hits it; ask lines
      come more often than every 30 s, or go on after hpPots reaches 10.
      Extra grep: grep -E "EPQ boss rest|EPQ member down" logs/cosmic-log.log
Switch flip (integrator's own commit "kpq,hpq,epq" plus the LivingWorldConfigCompanionPqTest pin), deploy, then:
L2 "kpq,hpq" -> stock Ellin; "kpq,hpq,epq" -> Ellin's lineup window.
L3 'Let me choose...'; a 7th member -> "Six is the most the forest can take. Take someone out first."
L4 Bench call from off the island (about 180 s ETA, RecallTravel.java:24): the arrival line in party chat.
L5 Restock at Ellin under the deployed epq standard: the store line ('Gone to the store' / RAN_TO_THE_STORE) and the trip
   to Mayoren 2130000 at Altaire Camp; Yes after they are back; a companion short of arrows/stars shows "low on ammo".
L6 gm0 leader: '!setgmlevel ohboy 6'; ohboy runs '!setlevel me 48 <job> auto' and '!setlevel <each companion> 48';
   '!setgmlevel ohboy 0'; ohboy leads a desk run with 4-5 of his companions. Pass: companion_performance 'epq' rows
   cleared, a complete pq_run_pace 'epq' row (not excluded), the clear time against the window's estimate.
   [B1] Before L6, note ohboy's level and job. After L6, set him back with '!setlevel ohboy <that level>'; use
   '!setlevel ohboy restore' only if his gm.level.hold record is the one L6 made.
L7 Zero drift: same party, no hold map, follow restored, CompanionDriftReport clean, no 'remains in owned recovery', the
   next Ellin click works.
L8 Six members with extras held on the floor left of Ellin's ledge; both rejoin.
L9 Two humans (Crewfriend a member in ohboy's run): stage 2 with two companions (one lurer works both sides).
L10 Desk run of six: '!hpmp <companion> 0' -> tap-out accepted, the host's tap-out line, HOLD on 930000800, E18 "... with
   five."; again -> "... with four."; the next death at four -> HOLD (corpse stays, run goes on); kill every remaining
   companion -> WENT_DOWN said by the last one, stock exile, companions follow afterwards.
L10b N3, a held corpse past five minutes, with a live mob on its map. [B4] Not on 930000100: the standing companions
   clear the trees well inside six minutes (Step 0: 50 trees in about 165 s with five), so the test could pass for
   the wrong reason. In a run at four registered, clear 930000200's thorns and stay on 930000200: the companions hold
   at portal 3 while you stand there, and EllinPQ's respawnStg2 refills the Stone Bugs every 4 s while a player is on
   the map. '!hpmp <companion> 0' there ('EPQ departure: ... decision=HOLD' once), then wait six minutes on that map
   (under the 480 s stall watchdog). Pass: a live mob stands on 930000200 for the whole wait; the corpse still lies
   there; no 'EPQ departed:' for it; the run carries on. If the corpse moves, that is the held-dead-safety defect:
   record it for that session and repeat after p6i/held-dead-safety is deployed. A long golem fight on 930000600 is
   the other place for this test.
L11 One TIMEOUT or a golem wipe: TIMEOUT or WENT_DOWN parting, stock exile, companions follow.
L12 Lakelis and Tory clicks unchanged (LakelisDeskGoldenTest text; Tory's window).
L13 Ellin click latency, tick percentiles, companion-ops backlog, full-rate holds released at the end.
L14 Video p6d-epq-companions.mp4 plus a 60 s highlight; the user signs off.
After the live days: '!setlevel <name> restore' for each companion, and '!setlevel me <level>' for Crewfriend (the
level he had before step 1, for example 50). [B1] Never '!setlevel me restore' or '!setlevel party restore' on
Crewfriend: his gm.level.hold record is Lv37 Beginner from 2026-10-05, and his Spearman skills would not come back.
ohboy: see L6. Results go into docs/P6D_EPQ_RESULTS.md on the integration branch and a memory note.
```

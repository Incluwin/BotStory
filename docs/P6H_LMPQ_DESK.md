# P6h LMPQ: companion runs from Rolly's desk, Steps 2-6 (branch feat/lmpq-desk)

The Ludibrium Maze PQ ("lmpq") can now be played as a companion run started from its entry NPC, Rolly (9103001 on 220000000). Before this branch it could only be started with the GM command `!lmpqtest party prod`. The branch is based on feat/default-on-content 7c2bc785e. The switch `living_world.COMPANION_PQ_ENABLED_KEYS` is not changed here. The integrator adds `lmpq` after the live checks and also regenerates `scripts/config/knowledge/server-knowledge.json`. Until that file is regenerated, `KnowledgeLintTest` fails in this tree with "knowledge file older than scripts/npc", which is expected.

Binding plan: `p6h_lmpq_plan.md`, section "STEPS 2-6", in the session scratchpad. User decisions applied:

- Rolly's desk prefers six seats: SeatRules(3, 6, 6).
- The test party is Option T.
- Step 0 never ran, so the plan's provisional values stand: TAUROMACIS_MIN_LEVEL is 65, and every pace prior is marked as a guess in its yaml text.
- D5 (an escort for the leader) is not built.

## What was built

| Commit | Item |
| --- | --- |
| 0027472dc | Step 2. Companion play: party-chat voice (AgentLmpqDialogue, AgentLmpqCompanionPlay); directions to the rendezvous room, one line per room and a reminder; Room 16 (Pietri's room) as the rendezvous; R17 (a room the leader only walks through keeps its working companion); a walk-and-pick instead of the map-wide vacuum; the coupon hand-over at the leader's feet (AgentLmpqHandOver: line, nudge, re-collect before the item expires); the short line; danger rules below TAUROMACIS_MIN_LEVEL; resting when out of HP potions; full rate (AgentLmpqFullRateSimulation); claims; completion that does not wait for humans |
| 40c449fc5 | Step 3. AgentLmpqMemberVerdicts (the KPQ/HPQ/LPQ/RJPQ table with LMPQ's floor of three); tap-outs through the host; humans who left are dropped with one line; a dead companion held at the floor is let out after MEMBER_RECOVERY_TIMEOUT_MS; AgentLmpqParting kinds SILENT, GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, TIMEOUT and TOO_LONG, said once and never after the clear |
| 3646b501c | Step 4. AgentLmpqPaceProfile is `maze(farm \| walk) + handin + pietri + claim` for 3-6 members. farm is a Quota of 30-36 coupons on 9400211 (HP read from Mob.wz). Tauromacis 9400218 is the danger mob. The timer is 900 s from the catalog. The pace report is rewritten to the profile's keys and carries each member's power at entry. |
| 7e8fe6990 | Step 5. LmpqEntrance goes in PqEntrance's lmpq slot. RollyLines gives Rolly his own voice. Slot lines are added to CompanionPqKeys and CompanionPqFixture (LmpqFixtureEntrance). New tests: LmpqEntranceTest, RollyLinesTest, RollyDeskIntegrationTest. |
| 9f1c29b68 | Step 6. The hook goes in 9103001.js, in the leader's branch of "Enter the Ludibrium Maze", in 2040034.js's shape. RollyHookScriptTest checks the placement, and checks that the script without the hook is byte-identical to stock (SHA-256 at d893309b4). |
| 22782e8fd | Step 3 fix. If the leader logs off after the clear, the run no longer fails. It completes once its companions are out, and REWARD_STALL_MS still ends a run that gets stuck. |

## Deviations from the plan (checked against the current code, not the plan's stale line numbers)

- **2d, hand-over loot rule.** The plan had a COUPON_HANDOVER_LOOT_INHIBIT_MS timer. It was replaced by a claim: only the leader may loot a stack dropped for him. The dropper may also loot it, but only when its re-collect is due. The rule lives in `AgentLmpqSessionRegistry.preservesPortalMarker`, which AgentLootEligibility already asks; AgentLootEligibility itself was not edited. A new key, COUPON_HANDOVER_LINE_WINDOW_MS, makes carriers who hand over one after another share one line.
- **2e, resting.** A companion out of HP potions rests in the rendezvous room, not "out of reach" anywhere. It uses two new keys, REST_HP_FRACTION and RESUME_HP_FRACTION. The existing Tauromacis hold (TAUROMACIS_BUDGET_MS, built before this branch) now waits only while a counted companion can actually face a Tauromacis.
- **2f, full rate.** Built in RJPQ's shape rather than as a copy of HPQ's class.
- **2, tests.** No separate AgentLmpqCoordinatorPolicyTest. Its pins are covered by AgentLmpqCompanionRunTest, AgentLmpqCompanionRendezvousTest and the existing AgentLmpqWordingTest.
- **3, leader logout.** Logging out after the clear is now no failure (22782e8fd). The failure-site pin in AgentLmpqFailureLineTest goes from 8 to 9 tickSession sites (the new reward stall). When an owner logs out mid-run, his companions go through the existing departed path (B3) and nothing is said.
- **4, pace marks.** There is no AgentLmpqPaceMarks class. The marks live on AgentLmpqSession: the leader's first arrival in the rendezvous room, the coupons secured at the regroup, and each member's power at entry.
  - walk is a leader-side fixed term with a 90 s prior (about 3.2 hops). Hops are not modelled.
  - handin is leader-bounded.
  - The human leader counts as FARM_LEADER_CREDIT (0.25) of a farmer.
  - At the priors, the reference Lv55 crew has a median of about 4.3 min with six seats and about 7.1 min with three. At a tenth of the damage, three seats go over the timer about 80% of the time, so the desk never promises a weak crew.
- **5, spots around Rolly.** Step 0 run 2 never happened, so the spots come from Map.wz 220000000 instead.
  - Extras wait on his platform (foothold 251), 26 px apart, starting 100 px to his left (x -24 to -154). That keeps them off his click spot (x 26-126) and far from the hidden portal ph00_1 (x 242) the party arrives by.
  - The kept-clear zone is 90 px wide, from 100 px above to 40 px below. It covers his click spot, the foot of the steps and the spawn ledge.
  - The dps mobs are as planned: 9400211, 9400213, 9400217.
- **5, Rolly's lines.** He says the clear time is "under 8 minutes" when it is that fast. He warns when a run is within 2 minutes of the timer, gives a range once it spans 3 minutes, and hedges until 3 recorded runs. His only cautions are FRAGILE against Tauromacis and MISSES on the Dark Stone Golem (the coupon mob). The plan's "Three to six of you, everyone level 51 to 70!" is used word for word.

## Live checks (Step 7, adapted)

- **L0.** Deploy with lmpq off. The log has no "pace profile could not be warmed" WARN for LMPQ. Rolly is stock.
- **L1.** `!lmpqtest party prod` full run: directions per room; coupons at the leader's feet, and only he picks them up; zero 'LMPQ session failure:' lines; claims; the party is whole at 809050017.
- **L2.** Kill switch without lmpq: Rolly's script starts the stock event.
- **L3.** With lmpq on: Rolly's lineup window shows his portrait, the title "<Party Quest: Ludibrium Maze>" and "Six of you, then?". A 7th member is refused ("Six is the most the maze lets in."). "Let me choose..." works.
- **L4.** A bench call to Ludibrium (RecallTravel OFF_ISLAND 180 s). On arrival the companion says "here! ready for the maze when you are."
- **L5.** At Rolly, a companion under 30/40 potions restocks at Misky (220000002, 1 hop).
- **L6.** Desk Yes, then the clear. companion_performance gets a row, and pq_run_pace gets an 'lmpq' row with spans farm, walk, handin, pietri and claim, and coupons drawn.
- **L7.** Zero drift: the other quests' records are unchanged.
- **L8.** `!hpmp <companion> 0` with 6 members: the companion taps out and the run goes on.
- **L9.** The same with 3 members: a body in Room 16 (or on the clear map from CLEARING on) is held. Elsewhere it is WENT_DOWN after 45 s.
- **L10.** Leader death before the clear: DEFEATED. A leader who is idle, dead or offline after the clear causes no failure.
- **L11.** The TIMEOUT parting is said once.
- **L12.** A 3-member run clears.
- **L13.** KPQ, HPQ, LPQ, EPQ, OPQ and RJPQ desks are unchanged.
- **L14.** Tick percentiles with 5 Agents at full rate.
- **L15.** Video and sign-off.

**Watch first:**

1. Where companions stand at Rolly. Nobody should stand on the portal at x 242.
2. The leader reaches Room 16 by directions alone. D5 depends on this.
3. Coupon stacks at his feet: only he loots them, they are re-collected before they expire, and none are lost.
4. With the Lv55 test party, every companion is below TAUROMACIS_MIN_LEVEL. Rooms 6 and 7 are skipped, so expect a slower farm.
5. The first pq_run_pace rows against the guessed priors.

## Open items

- Every pace prior and TAUROMACIS_MIN_LEVEL is provisional because Step 0 never ran. Calibrate from the first runs and bump PACE_EPOCH if the profile changes.
- D5 (escort) is not built. It is needed only if L1 shows the leader getting lost.
- A director-run LMPQ (maxLobbies 1) still blocks the desk with the stock "Another party has already entered..." message.
- LMPQ has no AMMO_STANDARDS entry, so it uses PARTY_QUEST_AMMO (500).
- Rolly's lines and the companions' lines are drafts; the user may edit them.
- The integrator still has to flip the switch and regenerate server-knowledge.json.

## Tunables (agent-engine.yaml, LMPQ block)

| Key | Default | Range | Step |
| --- | --- | --- | --- |
| AgentLmpqTestService.SPAWN_STAGGER_MS | 500 | GM harness only | 1b |
| AgentLmpqCoordinator.TIMEOUT_SLACK_MS | 5000 | 0-30000 | 1b |
| AgentLmpqCoordinator.REWARD_REFUSALS_MAX | 3 | 1-10 | 1b |
| AgentLmpqCoordinator.REWARD_STALL_MS | 120000 | 30000-600000 | 1b |
| AgentLmpqCoordinator.ENTRY_HOLD_GRACE_MS | 5000 | (before this branch) | 1b |
| AgentLmpqCoordinator.TAUROMACIS_BUDGET_MS | 300000 | (before this branch) | hold |
| AgentLmpqCoordinator.DIRECTION_REPEAT_MS | 45000 | 15000-180000 | 2 |
| AgentLmpqCoordinator.COUPON_HANDOVER_REACH_PX | 60 | 20-120 | 2 |
| AgentLmpqCoordinator.COUPON_HANDOVER_TIMEOUT_MS | 30000 | 5000-90000 | 2 |
| AgentLmpqCoordinator.COUPON_PICKUP_NUDGE_MS | 40000 | 10000-120000 | 2 |
| AgentLmpqCoordinator.COUPON_RECOLLECT_MS | 150000 | 60000-170000 (clamped below expiry, one WARN) | 2 |
| AgentLmpqCoordinator.COUPON_RECOLLECT_MAX | 2 | 0-5 | 2 |
| AgentLmpqCoordinator.COUPON_HANDOVER_LINE_WINDOW_MS | 8000 | 2000-30000 | 2 (new) |
| AgentLmpqCoordinator.TAUROMACIS_MIN_LEVEL | 65 (provisional) | 51-71 | 2 |
| AgentLmpqCoordinator.REST_HP_FRACTION | 0.4 | 0.2-0.6 | 2 (new) |
| AgentLmpqCoordinator.RESUME_HP_FRACTION | 0.7 | 0.5-0.95 | 2 (new) |
| AgentLmpqCoordinator.MEMBER_RECOVERY_TIMEOUT_MS | 45000 | 10000-120000 | 3 |
| AgentLmpqPaceProfile.FARM_SEEK_MS | 15000 (guess) | 2000-60000 | 4 |
| AgentLmpqPaceProfile.FARM_COMBAT_EFFICIENCY | 0.6 (guess) | 0.1-1.0 | 4 |
| AgentLmpqPaceProfile.FARM_LEADER_CREDIT | 0.25 (guess) | 0.0-1.0 | 4 |
| AgentLmpqPaceProfile.WALK_MS | 90000 (guess) | 20000-600000 | 4 |
| AgentLmpqPaceProfile.HANDIN_MS | 90000 (guess) | 10000-600000 | 4 |
| AgentLmpqPaceProfile.PIETRI_MS | 20000 (guess) | 5000-300000 | 4 |
| AgentLmpqPaceProfile.CLAIM_MS | 15000 (guess) | 5000-300000 | 4 |
| AgentLmpqPaceProfile.FRAGILE_TOUCHES | 5 | 2-15 | 4 |
| AgentLmpqPaceProfile.WEAK_HIT_CHANCE | 0.6 | 0.3-0.9 | 4 |
| AgentLmpqPaceProfile.PACE_EPOCH | 1 | from 1 | 4 |
| AgentLmpqWatchdogRuntime.PERIOD_MS | 1000 | 250-5000 | 1b |

Unchanged elsewhere: `AgentSupplyReadiness.PARTY_QUEST_STANDARDS` already lists lmpq=30/40.

## Live 2026-10-19 (89d38f027, "lmpq" on by default)

Rolly answers with his window ("<Party Quest: Ludibrium Maze>", the leader, Dorran Lv75 and Lioren Lv77 kept waiting
as "past the maze", Let me choose / the bench call / Never mind). No full run: the test crew is Lv75-78, above the
maze's 51-70, and lowering them with `!setlevel` can drop skills a restore does not give back.

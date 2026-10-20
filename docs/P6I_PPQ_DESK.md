# P6i PPQ with companions: Steps 2-6 (branch feat/ppq-desk)

Built on feat/default-on-content 7c2bc785e, following the binding plan `p6i_ppq_plan_revised.md`
(sections 7-13). Every behaviour below is gated on `AgentPpqSession.companionAdopted`: stock,
Agent-led and `!ppqtest` fixture runs keep their old behaviour and lines word for word.
`living_world.COMPANION_PQ_ENABLED_KEYS` in config.yaml is **not** changed here; the integrator
flips "ppq" after the live checks.

## Commits

| Commit | Step | What |
|---|---|---|
| 814f141a2 | 2-3 | Companion play on the ship, and the companions' own lines |
| 2c1feb764 | 4 | Deaths, leavers, the leader, and an early end in a companion's words |
| 353b233f3 | 5 | Clear-time pace: profile, marks, report, wired into the release |
| 29de0ea91 | 6 | Guon's desk (PpqEntrance, GuonLines), the 2094000.js hook, the "ppq" key and fixture slots |

## Already present before this branch (not rebuilt)

- Step 1 and 1b: the companion seam, adoption, `!ppqtest party prod`, phase/stage logs, the
  after-clear limit, release walking Agents out of Wu Yang's room, the Feather/Etc-room rule, leader
  rules, departures handed back (`observeDepartures` / `recoverDetachedMember`), no EVENT cap while a
  human is held, and the death gate `AgentPpqDeathDepartureGate` (1b.5).
- 7.4 medals: `AgentPpqMedalHandOver` already gathered the wave's medals and dropped them at the
  leader's feet in batches, by Guon when he cannot be reached, with the nudge and the re-collect.
  Step 2 only added the dying rule (M6) and changed its lines to the companions' lower-case ones.
- 7.6 side rooms: `sideRoom()` (fight beside a leader inside, else leave by out00). The companion
  "join" branch stays removed on purpose.
- 7.10 after the clear: the after-clear limit and the release from 925100600 were there; Step 2
  added the wait by Wu Yang until the leader leaves, then the Feather exit (D-f').

## Built here

- **Step 2 (plan 7):** companions leave FOLLOW for the whole run. They hold full rate where no
  player sees them (`AgentPpqFullRateSimulation`). The P3 default applies: go ahead once a stage is
  clear (`AgentPpqCompanionPlay.deckMove`). They wait by in00 while the leader can still open a chest
  and follow him in only once he is inside (D-c). No Old Metal Key goes into a door until the
  companions' keys plus the keys lying on the floor cover every closed door; human keys do not count.
  At the boss door they keep the map clear and go through only with the leader beside them or
  inside. They never loot the chest key (D-b). They wait by Wu Yang, then take his Feather (D-f').
- **Step 3 (plans 8 and 12):** `AgentPpqDialogue` speaks in map chat beside the leader, or in party
  chat when he is on another map, and logs `PPQ line:`. It covers S1, M1-M6, C0, C1, K1, W1, B1-B3,
  E1, D1 and T1. All lines are lower case with no digits (lint test).
- **Step 4 (plan 9):** NEW `AgentPpqMemberVerdicts` and `AgentPpqParting`.
  - `AgentPpqCompanionRun.pass` runs before the clear. It handles a lead passed (GENERIC), the leader
    down (D1 once, no failure), each member's verdict and T1.
  - MISSING for `MEMBER_RECOVERY_TIMEOUT_MS` leads to `departMissing`.
  - The gate has a short-handed clause: a companion held at the floor with no other companion
    standing ends the run WENT_DOWN.
  - `endedCause` names how the run ended: DEFEATED, TIMEOUT (from the last `getTimeLeft` read),
    LEFT (event at its floor) or GENERIC.
  - `AgentPpqCompanionRun.fail` makes only the first failure speak, says nothing past the clear, and
    says nothing for SILENT.
  - A companion the gate let go gets `TapOut("ppq", ..., 251010404)`. A refusal hands it back with no
    failure.
  - `PPQ medals lost with member:` is logged.
- **Step 5 (plan 10):** NEW `AgentPpqPaceProfile`, `AgentPpqPaceMarks` and `AgentPpqPaceReport`.
  - Twelve segments: stage1, medals1-3 (Quota, negative binomial of 20 at 30%), handin1-3 (leader
    Fixed "handin"), deck1, deck2, doors, boss (9300119, 420000 HP) and wuyang (leader Fixed).
  - Each segment's slow threshold is its stock stage timer, read from PiratePQ.js and 2094002.js.
  - The release hands the record to `childFinished` with one `PPQ pace span:` line per span.
- **Step 6 (plan 11):**
  - "ppq" goes in `CompanionPqKeys`.
  - `PpqEntrance`: seats 4-6, Guon's own choice is 6. Extras wait at Beside(-420,-30) on the floor
    left of his platform, at x -1697.. and y 242 on the real Map.wz. KeepClear(190,40,25) covers his
    platform and the deck above.
  - `GuonLines` is his desk voice. The stock refusals use 2094000.js's words.
  - The hook goes in 2094000.js before `getEligibleParty`.
  - `PpqFixtureEntrance` fills the fixture slot.

## Deviations from the plan

1. **Tap-out without the walk to Guon on 925100700 (plan 9.2, R18 not built).**
   - Instead, the departure pass asks the host for the tap-out as it hands the companion back. The
     host's hold moves the companion to 251010404, next to the owner's last spot or a portal, the
     same place Guon's own exit script would send it.
   - The companion respawns alone on 925100700. Only players who already left could see it
     teleport.
   - There is no REQUEST_TAP_OUT/KEEP_DEPARTED verdict. A refused tap-out is the old hand-back.
2. **Plan 9 test names merged.** AgentPpqDeathPolicyTest, AgentPpqLeaderDownTest and
   AgentPpqCompanionFailTest are folded into `AgentPpqLeaderAndFailTest`, alongside
   `AgentPpqShortHandedGateTest`, `AgentPpqMemberVerdictTest` and `AgentPpqPartingTest`.
3. **LEFT when the party drops below 3.** There is no separate site: PiratePQ.js ends its own event
   at the floor. `endedCause` names LEFT when the last pass saw at most 3 registered.
4. **Entry wait.** A companion the event never held once `COMPANION_ENTRY_WAIT_MS` has passed since
   the bind departs like one that walked out (plan 7.1).
5. **Tunable names follow the shipped hand-over.** Plan 13's MEDAL_HANDOVER_* and HANDOVER_* map to
   `AgentPpqMedalHandOver.*` with its shipped values: TIMEOUT 20000, nudge 15000, re-collect 120000.
   COMPANION_AFTER_CLEAR_LIMIT_MS is the shipped `AFTER_CLEAR_LIMIT_MS` plus the new
   `COMPANION_AFTER_CLEAR_WAIT_MS`. LOOT_REACH_PX, NPC_REACH_PX and HANDOVER_LOOT_INHIBIT_MS were not
   added: the shipped `INTERACTION_RADIUS` and the hand-over keys cover them.
6. **Pace priors.**
   - The plan listed only the leader terms. The fights also need efficiency and seek priors, as
     every other PQ has: STAGE_ONE_EFFICIENCY, MEDALS_SEEK_MS, MEDALS_EFFICIENCY, DECK_EFFICIENCY,
     DOORS_EFFICIENCY and BOSS_EFFICIENCY.
   - The deck census comes from PiratePQ.js setup (10+10 per deck), not from Map.wz.
   - The 30% medal chance is the 10% in the drop data times world drop_rate 3.
   - Wave kills are a lower bound: drops in the live count between passes.
   - `MemberRun.tappedOut` is true for every member who left the run, not only those who tapped out.
7. **Plan 11.5 has no CompanionPartyQuestServicePpqTest class.** `GuonDeskIntegrationTest` covers
   the kill switch, desk routing, `start ppq`, the extras on the left floor and the tap-out hold. The
   restock trip at Guon (7 hops) is left to live checks L0b and L7.
8. **Fixture exit map.** `PpqFixtureEntrance.exitMap()` is 251010404, where the tap-out holds the
   companion, not PiratePQ's exitMap 925100700.
9. **keyAt(251010404) = "ppq" moved no supply pin.** The supply-readiness tests pass unchanged.
   Companions at Guon now restock to ppq=30/40 even with the switch off (live L0b).
10. **No live-party read (plan 9.3, "the live party gone" leads to GENERIC).** PiratePQ.js already
    ends its event when the party disbands (`disbandParty`), and a member who leaves the party
    leaves the event (`leftParty`). The run then sees the event disposed and parts by `endedCause`.
    A lead passed shows up as `event.getLeaderId()` and fails the run GENERIC.

## Live checks (plan 14, adapted)

Never use `!warp` or `!warpto` inside the PQ. Grep for: `PPQ phase:`, `PPQ stage:`, `PPQ line:`,
`PPQ departed:`, `PPQ tap-out:`, `PPQ parting:`, `PPQ session failure:`, `PPQ potions:`,
`PPQ pace span:`, `PPQ medals lost`, `PPQ leader down:`, `PPQ member missing:`, `PPQ after clear:`.

- **L0:** deploy with the switch *without* ppq. Guon's click starts stock PPQ: the hook returns
  false. The `pq.pace` warm of ppq logs, and AgentPpqPaceProfile.load must not throw (Map.wz and
  Mob.wz reads).
- **L0b:** companions that follow Crewfriend to Guon restock to 30/40, once.
- **L1:** `!ppqtest party prod skipchests` plus Guon. No line before entry, FOLLOW is left, nobody
  is map-synced, stage 1 is cleared by the companions, and S1 is said.
- **L2:** medals. Batches land at his feet (M2), M5 is said, he hands in from the floor, and each
  wave takes at most 8 minutes. `PPQ pace span: key=medals1 ... work=` is above 0.
- **L3:** decks and doors. No box hits. Keys go in only once they cover every door. The boss door
  waits for him, and W1 is said.
- **L4:** boss. B2 is said. He clicks Wu Yang. E1 is said. Companions wait until he leaves 925100600,
  then take the Feather. The party is intact. Twelve `PPQ pace span:` lines, all complete.
- **L5:** chest room (`!giveitem Crewfriend 4031437 1`). Companions wait near in00 and go in after
  him. C1 is said. That deck's span is `why=detour`.
- **L6:** deaths.
  - `!hpmp <companion> 0` at 6 registered: PROCEED. `PPQ tap-out: accepted=false` on an adopted run;
    the run continues.
  - At 3 registered: HOLD.
  - With every companion down: `PPQ parting: kind=WENT_DOWN`.
  - `!hpmp Crewfriend 0`: D1, no failure. His OK gives `kind=DEFEATED`.
- **L7:** after the flip.
  - Guon's window shows his title, seats 4-6 and the bench call. Yes leads to ENTERING. Extras stand
    on the floor left of his platform and are out of the party.
  - The restock trip to Lan Ming says "Gone to the store".
  - A companion death at 6 gives an accepted tap-out: the companion is held on 251010404, and T1 is
    said.
- **L8:** a 4-member desk run, end to end. Check the `pq_run_pace` row for ppq and the
  companion_performance rows.
- **L9:** video (user sign-off).

**Watch first:**

- L0's pace warm (the profile parses Map.wz and Mob.wz on companion-ops).
- In L6/L7, where a tapped-out companion appears. It is moved from 925100700 to 251010404 by the
  host's hold, with no walk to Guon on the exit map (deviation 1).

## Open items

- R18: the walk to Guon 2094002 on 925100700 before the tap-out.
- Step 0 live smoke results are unknown. Every pace prior is a guess; recalibrate from Step 0 and
  L8, and bump PACE_EPOCH if the model changes.
- `scripts/config/knowledge/server-knowledge.json` is not regenerated here. KnowledgeLintTest and
  KnowledgeFileFreshnessTest stay red in this tree until the integrator regenerates it.
- The flip of "ppq" in `COMPANION_PQ_ENABLED_KEYS` (integrator, after Step 7).

## Tunables (agent-engine.yaml, PPQ block; key prefix `server.agents.capabilities.partyquest.ppq.`)

| Key | Default |
|---|---|
| AgentPpqCoordinator.EVENT_TIMEOUT_MS | 4500000 |
| AgentPpqCoordinator.STALL_TIMEOUT_MS | 600000 |
| AgentPpqCoordinator.RETRY_MS | 900 |
| AgentPpqCoordinator.INTERACTION_RADIUS | 70 |
| AgentPpqCoordinator.AFTER_CLEAR_LIMIT_MS | 300000 |
| AgentPpqCoordinator.COMPANION_ENTRY_WAIT_MS | 30000 |
| AgentPpqCoordinator.PRE_ENTRY_TIMEOUT_MS | 900000 |
| AgentPpqCoordinator.EXIT_WAIT_RADIUS_PX (new) | 150 |
| AgentPpqCoordinator.COMPANION_AFTER_CLEAR_WAIT_MS (new) | 180000 |
| AgentPpqCoordinator.MEDAL_DYING_HP_PERCENT (new) | 25 |
| AgentPpqRuntime.COORDINATOR_LEASE_MS | 3000 |
| AgentPpqSession.MIN_LEASE_MS | 250 |
| AgentPpqTestService.SPAWN_STAGGER_MS | 500 |
| (server.agents.field) AgentPpqTestFixtureService.PPQ_START_LEVEL | 67 |
| AgentPpqMedalHandOver.BATCH | 5 |
| AgentPpqMedalHandOver.NEAR_PX | 400 |
| AgentPpqMedalHandOver.REACH_PX | 60 |
| AgentPpqMedalHandOver.TIMEOUT_MS | 20000 |
| AgentPpqMedalHandOver.PICKUP_NUDGE_MS | 15000 |
| AgentPpqMedalHandOver.RECOLLECT_MS | 120000 |
| AgentPpqMedalHandOver.RECOLLECT_MAX | 2 |
| AgentPpqMedalHandOver.LINE_WINDOW_MS | 8000 |
| AgentPpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS (new) | 45000 |
| AgentPpqCompanionRun.TIMEOUT_SLACK_MS (new) | 2000 |
| AgentPpqPaceProfile.STAGE_ONE_EFFICIENCY (new) | 0.30 |
| AgentPpqPaceProfile.MEDALS_SEEK_MS (new) | 3000 |
| AgentPpqPaceProfile.MEDALS_EFFICIENCY (new) | 0.30 |
| AgentPpqPaceProfile.LEADER_MEDAL_CREDIT (new) | 0.5 |
| AgentPpqPaceProfile.HANDIN_MS (new) | 30000 |
| AgentPpqPaceProfile.DECK_EFFICIENCY (new) | 0.30 |
| AgentPpqPaceProfile.DOORS_EFFICIENCY (new) | 0.25 |
| AgentPpqPaceProfile.BOSS_EFFICIENCY (new) | 0.35 |
| AgentPpqPaceProfile.WUYANG_MS (new) | 20000 |
| AgentPpqPaceProfile.FRAGILE_TOUCHES (new) | 7 |
| AgentPpqPaceProfile.WEAK_HIT_CHANCE (new) | 0.6 |
| AgentPpqPaceProfile.PACE_EPOCH (new) | 1 |
| (supplies) AgentSupplyReadiness.PARTY_QUEST_STANDARDS ppq | 30/40 (unchanged, P2 default) |

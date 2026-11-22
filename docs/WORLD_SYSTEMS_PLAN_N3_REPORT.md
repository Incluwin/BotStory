# N3 citizen-led runs: build report (2026-11-22)

Branch `feat/world-citizen-runs` from `feat/companion-manager` 263e4aef4. Plan: `docs/WORLD_SYSTEMS_PLAN.md` N3,
decisions D5, D6, D7. No schema change. Live checks: `MapleServer/tools/checks/checks/n3-citizen-runs` (G1-G7,
written, not run).

| Phase | State | Switch |
|---|---|---|
| N3.1 whispered invitations, "Ask to join" | built, tested | `CITIZEN_RUN_INVITES_ENABLED` (off) |
| N3.2 companions in citizen runs | spike: not clean; companions stay out (coordinator 11-22) | none (no switch) |
| N3.3 citizen OPQ | not built: needs a leader-conducted mode (below) | would ride `PQ_RUN_QUESTS` |
| N3.4 citizen Zakum, rival help | not built: needs a decision on unwatched Zakum (below) | `CITIZEN_ZAKUM_ENABLED` (off, does nothing yet) |
| N3.5 LPQ | spike report (below); not built, as D5 says | - |
| N3.6 invitation bandit | built, tested | rides N3.1 |

## N3.1 Whispered invitations

`CitizenRunInvites` (population package) rides `CitizenPartyQuests`' 10 s tick; no timer of its own. A party forming
at a lobby that is still short of its preferred seats (and holds no seat for a lobby invite) lets one of its citizens
at the lobby whisper a known human: the citizen of the party warmest toward them, at least `PersonMemory.WARM` (15,
`Standing.Rung.REGULAR`), and nobody in the party holding a grudge (warmth -30 or less). Gates (`gate`, each tested):
online player, same channel as the party, the lobby's level band, not standing on the lobby (the lobby invite asks
them), not in a party (a party of only their own companions is fine, see below), no event instance and no boss run (`AgentBossRunRegistry.forMember`), not in the Cash Shop,
logged in at least `CITIZEN_RUN_INVITE_LOGIN_QUIET_MS` (10 min), no invitation waiting for an answer, and the
per-human gap: `CITIZEN_RUN_INVITE_GAP_MS` (30 min) across all citizens, doubled per "no" in a row up to 8x. A party
whispers at most `CITIZEN_RUN_INVITES_PER_PARTY` (2), one a minute. The line (`chatter/runs.yaml` `run.invite`, or
`run.invite.member` naming the leader) says the quest, the channel and how many seats are open.

The answer comes back through `CitizenWhispers` topic `run` (`Population` registers it). A yes ("ya", "omw",
"sure"...) runs `CitizenPartyQuests.acceptRunInvite`, which is the whispered-seat path (`whisperJoin`): the leader's
party invite, the seat held `PQ_HUMAN_SEAT_HOLD_MS` for the invite's answer, and once they joined the party waits
`WHISPER_JOIN_COMING_MS` past its patience while they walk over; the leader tells them where in party chat and says on
the lobby's map that they are coming (`pq.coming` / `boss.coming`, logged as "waits for X (asked along, on the way)").
A no gets a friendly line (`whisper.run.no`) and a journal entry, nothing else. A yes after the party went in or broke
up gets `whisper.run.gone`. No answer in `CITIZEN_RUN_INVITE_ANSWER_MS` (3 min) is booked as not taken.

"Ask to join" is an option on the needs board's PARTY request page (not on a rival's guest request) while the switch
is on: `CitizenPartyQuests.askToJoin` runs the same whispered-seat path from the player's side, the leader whispers
its usual answer and the board's window says what happened.

**Players with their crew out** (coordinator, 2026-11-22): most players walk around with companions, so a player
whose party holds only themselves and their own companions (`CompanionDirectory.ownerOfCharacter`) is invitable; a
party with another player or another owner's bot is not. Their invitation is `run.invite.crew` ("only got 1 spot tho.
ur crew ok waiting?"). A yes gets `whisper.run.yes.crew` ("bench ur crew and ill inv u") and the seat is held for
`CITIZEN_RUN_INVITE_ANSWER_MS` (the party neither goes in nor gives the seat away); once they have no party the leader
invites them (`followUp`, on the tick). Nobody's companions are benched or moved by the population. If the crew follows
its owner into the citizen party anyway (the roster re-seats it), the existing blocker keeps the run out and the leader
says once "ur crew has to sit this one out, bench them n we go".

## N3.6 Invitation bandit

`InviteBandit`: contexts (hour in four UTC bands, the human in a field or in a town/FM, standing rung REGULAR/FRIEND,
time since they last went into this quest with citizens: never / within the hour / longer) keep asked and yes counts.
A context is asked while mean + `CITIZEN_RUN_INVITE_EXPLORE` x sqrt(ln(1 + N) / (1 + n)) reaches
`CITIZEN_RUN_INVITE_MIN_ACCEPT` (0.3 and 0.15): an unasked context is always asked; one where nobody says yes stops
after roughly 10-20 tries and is retried as N grows. The arithmetic is CitizenSpots', copied (the class is left
alone). In memory since boot (no schema change), so it relearns after a restart; the per-human backoff is what stops
asking one person who never says yes.

## N3.2 Spike: companions in a citizen run (D7: not clean, so no)

Companions follow their owner into any party (`CompanionPartySync.left`/`regroup`, `LiveCrew.seat`), so they reach
a citizen party, and KPQ/HPQ `admitCitizens` would drive them correctly as citizen-run members (full rate, citizen
leader, `companionAdopted` stays false). But:

1. `CitizenPartyQuests.blocker` refuses any Agent that is not a waiting citizen.
2. `goIn` would file them under `humans`: person memories, thanks lines and "(human)" names for a bot.
3. A citizen run takes no `Commitment`, so `CompanionPartySync` may bench or re-seat a companion out of the instance
   mid-run when the owner logs off, leaves or is expelled.
4. A companion's tap-out is unknown to `CitizenPartyQuests.tapOut`, so the KPQ and HPQ coordinators fail the whole run
   ("defeated") when it dies (`AgentKpqCoordinator` ~3096-3107, `AgentHpqCoordinator` ~1721-1730).
5. No card or run record for the companion (`CompanionRunLifecycle.runEnded` reaches only companion runs).
6. An already-registered companion engagement makes admission throw (`AgentPartyQuestEngagementRegistry` 22-38).

Fixing it touches roster commitments and both coordinators' tap-out path, outside this piece. So companions stay
out (coordinator, 2026-11-22: no switch for it); invitations say how many seats are open, and a player with only
their crew is asked for one seat, theirs alone.

## N3.3 Citizen OPQ: why it is not built

The seams are mostly there (findings, base 263e4aef4): an Agent-led OPQ path exists (director, `Mode.AUTONOMOUS`),
`AgentOpqCoordinator.runEak` can run Eak for any talker, run ends and handbacks are generic
(`AgentPartyQuestLifecycleRuntime`), and OPQ's seat rules (5-6) and `validateCompanion` fit citizen parties. The
blocker: Eak (`scripts/npc/2013001.js` 51-61) answers only the event leader, in every room, and the autonomous path
has each room's own member talk to him (walkway 732, lobby 779, sealed 842, lounge 911), so a citizen OPQ led by one
citizen stalls at the first room. "The citizen leader makes every Eak click" needs a leader-conducted mode: members
work the rooms, the Agent leader walks to each finished room and talks to Eak, as the human-led conductor
(`AgentOpqConductor`) asks of a human leader. That is a new driver for an Agent leader, not a mark. Also needed:

- `OrbisPQ.js` has `maxLobbies = 1`; the player-first rule (one lobby kept for humans) then never lets citizens in.
  The script does use instance maps (`em.newInstance("Orbis" + lobbyid)`), so a policy-driven second lobby, as
  KerningPQ.js and HenesysPQ.js have, should work, but it changes how many human parties can run OPQ at once.
- an `AgentOpqLobbyPolicy` (none exists), a `citizenRun` mark on `AgentOpqSession`, `admitCitizens`, a full-rate hold
  (as `AgentHpqFullRateSimulation`; unwatched OPQ Agents fall to background cadence and the timed puzzles stall),
  and `tapOut` for citizen runs (today only `companionAdopted`).
- `tickStorage` never calls Eak (statusStg2 is never set by the coordinator): unverified whether it stalls.
- Evidence: human-led OPQ runs with companions still run over the 45-minute clock (runs 4-6).

Estimate: medium-large (a leader driver, policy, admission, hold; 600-900 lines) with live risk.
Questions for the coordinator: build the leader-conducted mode now, and may OPQ get a second lobby a channel?

## N3.4 Citizen Zakum: why it is not built

- Zakum moves only under a human's client: `CompanionBossRuns.forcesServerAuthority` is installed only into
  `EasyBalrogEncounterService` (`LivingWorld.java` ~233), and `AgentZakumScenario` (101-104) holds its members while
  no human drives a part. `AgentBossRunSession.watchHumans` (~731) walks a run out without a human unless the boss is
  `humanOptional`, which Zakum's catalog entry is not.
- No rolled ("abstract") resolution exists for any boss. The plan's "resolved by a roll when nobody is on the map"
  would have to kill the parts server-side, mark the event cleared, give drops and write the boss log: a fake clear,
  and new design (a product decision, not a seam).
- Prerequisites: quests 100200 and 100201 completed and Eye of Fire 4001017 (Zakum05.js 28-38, 2030008.js,
  2030013.js 184). No Java code tracks those quests, and nothing gives citizens them; citizens would need a
  prerequisite errand (the Eye is forged from quest items).
- Lobby: regions.yaml has no `zakum` lobby; `openCitizenExpedition` needs the leader on the recruit map 211042400.
- Zakum's ~528M HP took a Lv160 crew live; a citizen crew at the rival help request's level (50+) would not clear it
  in a watched fight either.

So the rival help request (`RivalCrews.helpRequest`) still ends in "see u there" and `RIVAL_HELP_REQUESTS_ENABLED`
stays off. Seam when built: a `zakum` ExpeditionDoor beside `CitizenBossDoor` (registered in
`CitizenPartyQuests.shippedDoors`, lobby 211042400 in regions.yaml), a `Boss` record in `AgentBossCitizenRuns`, and
`RivalCrews.replied` on a yes sending the crew to that lobby with the player as the held seat
(`acceptRunInvite`-style). Questions: is a rolled clear acceptable with nobody watching (D-level decision), and do
citizens get the prerequisites by errand or by fiat?

## N3.5 LPQ spike (D5: report only)

Verdict: buildable with four changes, but only after a live proof that the stock Agent-led LPQ clears at all; not
worth building now.

- Second lobby: `LudiPQ.js` (40) hardcodes `maxLobbies = 1`, but instances are per lobby (`em.newInstance("Ludi" +
  lobbyid)`, instance maps), portals lpq0-7 and stage NPCs use the player's own instance, and Red Sign's
  `warpIntoActiveLpq` already walks several sessions. A policy-driven `maxLobbies` with a human-reserved lobby (as
  KPQ's) is needed; there is no LPQ lobby policy today.
- Citizen mark: nothing in `lpq/` knows citizens. Admission (`AgentLpqAdmissionService` 192-231) wants six members
  (5-6 only for a companion run) and the four roles of `AgentLpqRosterRequirementPolicy` (Teleport+magic, Dark Sight,
  a stage-7 ranged weapon, a physical attacker); random citizen rosters will often miss Teleport or Dark Sight. The
  full-rate hold (`AgentLpqCoordinator` ~6223) applies only to companion runs.
- Rescues: `AgentLpqRescue.Kind` has 13 kinds with about 49 call sites (teleports ~13, item vacuums ~11, in-event map
  moves 6, trap returns ~6, event re-registers 3, item grants 5, kills with drops 2, remote reactor, forced transfer,
  remote NPC); companion runs withhold them. A citizen run would keep them all on: with no human in it they are
  unseen, and the teleports and vacuums are what keep Agents moving (the four-minute room recovery shows the stock path
  stalls without them). With a human aboard they would be seen, which is a tell; dropping them risks stalls.
- Evidence: the Agent-led path has never cleared live, stage 9 (Alishar) wiped Lv 48 crews, and the user deferred LPQ
  on 2026-10-25 (`CITIZEN_PARTY_QUESTS_PLAN.md` 268-273). Next step if wanted: one GM-run stock Agent-led LPQ to
  the end; build only on a clear.

## Decided here (not in the plan)

- Invitations need `CITIZEN_WHISPERS_ENABLED` too: without it the answer never reaches the citizen.
- Any citizen of the forming party may ask (the one warmest toward the human), not only the leader; the leader still
  sends the party invite, and the line names the leader.
- One grudge in the party keeps the human uninvited.
- A "no" doubles the per-human gap (to 8x); no answer counts for the bandit only.
- The travel hold is the existing `WHISPER_JOIN_COMING_MS` (5 min) past the party's patience, as for a whispered seat.
- A human on the lobby map gets the lobby invite, never a whisper.
- Invitations also run at an expedition lobby (Balrog), whose doors use the same forming path.
- A player who said yes with their crew out holds the seat for `CITIZEN_RUN_INVITE_ANSWER_MS` (3 min) to bench it.
- The bandit and "time since last ran" are in memory since boot (no schema change in N3).

## Files

New: `population/CitizenRunInvites.java`, `population/InviteBandit.java`, `chatter/runs.yaml`,
`CitizenRunInvitesTest` (21 tests). Changed (hunks): `CitizenPartyQuests` (Forming fields, invites field and
accessors, tick expire, the ask after the loners join, `pq.coming` in `tellWhisperedWhere`, `acceptRunInvite`,
`askToJoin`, `citizensIn`, `ran` at goIn, status), `CitizenWhispers.answerJoin` package-private, `Population` (topic
registration), `PopulationLoader.TRIGGERS`, `NeedScreens.details` ("Ask to join"), `CitizenPartyQuests.blocker` (crew line, `ownerIn`), `tellHuman` (crew line), `PopulationConfig` (7 fields +
validation), `config.yaml` (N3 block after `PQ_CALLS_PER_PARTY`), test fixture `CitizenLobbyFixture.arrives` (humans).

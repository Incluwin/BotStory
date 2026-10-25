# Citizens run party quests themselves (plan, 2026-10-24)

Goal (POPULATION_GUIDE "Not done yet"): a citizen party that forms at a PQ lobby goes in, runs the quest, records the
run and goes back to its day; a human who took a citizen's invite runs it with them. KPQ first. Player first,
bounded cost, chatter in each citizen's own voice. Branch `feat/citizen-party-quests`.

## What exists (read 2026-10-24)

- **Citizens at lobbies.** A `PQ` activity (cohort weight 5-15%) sends a citizen whose level fits to its region's
  lobby (`regions.yaml`: KPQ at Kerning City 103000000 lv21-30, LPQ 221024500, OPQ 200080101), where it only strolls.
  `SocialFabric.partyUp` (every 2 min) parties circle-mates on the same map; at a lobby the party invites one fitting
  human (`party.invite` chatter). Circles are drawn by cohort, not level, so two circle-mates rarely stand at one lobby:
  live logs show about 8 PQ logins a day at Kerning. A lobby party needs recruiting beyond the circle.
- **An older director already takes citizens into KPQ.** `AgentKpqPopulationRuntime` (engine, `ENABLED: 'true'`) gets
  every loaded PQ-session citizen from `SocialFabric.installSeams`, takes four party-less ones lv21-30 from anywhere on
  the channel, warps them to Kerning (`changeMapNear`, seen or not) and queues a KPQ-owned party. Live it queued
  citizen parties until 2026-10-15 and none since, and none of them ever cleared ("Evidence" below). The runs that got
  in ended "No KPQ progress for 15 minutes in stage 1": the run's Agents sat at the background cadence (pace span
  `throttled=1.00`, a fifth of the combat rate), since only companion runs hold full rate in the instance. It ignores
  the circle party and the lobby the citizen went to, and it is a second owner of the same citizens.
- **KPQ runs with a bot leader and human members.** The stock coordinator (cosmic_agents) runs an Agent-led party:
  PREPARING walks Agents to Lakelis and waits for humans within reach, ENTERING has the Agent leader click Lakelis
  (`KPQ.runNpc`), humans get their Cloto coupon prompts (`AgentKpqHumanDialogueRuntime`), stage 5 and rewards as for
  any run. Its narration is stock English ("Party ready. Everyone is here. Starting KPQ in 5 seconds.") unless the
  session is a companion run, which has casual lines for the same moments.
- **The adopt-a-party seam.** `AgentKpqAdmissionService.admit(operator, leader, members, seed, now)` adopts an
  existing party as it is (PRODUCTION, party ownership EXTERNAL: KPQ never disbands it). The companion desk (Lakelis,
  `KpqEntrance`) calls it with a human leader. Every other PQ has its own admission; HPQ, LPQ and OPQ admit an Agent
  leader (AUTONOMOUS / bonus modes), RJPQ and EPQ are human-leader only.
- **Player first, already in the engine.** `AgentKpqLobbyPolicy`: 2 KPQ lobbies a channel, 1 kept for humans
  (`HUMAN_RESERVED_LOBBIES`), runs operated by an Agent counted against `MAX_BACKGROUND_PER_CHANNEL: 1` /
  `MAX_BACKGROUND_TOTAL: 2`; the coordinator gives up a background start if the last free lobby went meanwhile.
- **Run end.** `AgentPartyQuestHandback` (one rule, installed by the living world: `CompanionRunLifecycle`) is asked
  where each released Agent goes and is told `runEnded`. Citizens currently get the stock answer: TownLife in Kerning.

### Evidence (live logs 2026-09-24 to 2026-10-24)

The old director queued 237 citizen KPQ runs (the last on 2026-10-15). None cleared:

| End | Runs |
|---|---|
| PREPARING: "The party could not gather at Lakelis" | 185 |
| "The KPQ event instance disappeared or the leader left it" | 14 |
| "No KPQ progress for 15 minutes in stage 1" | 11 |
| "Agent runtime was removed" (a member logged off) | 9 |
| "A party member did not reach the Kerning entrance" | 8 |
| a member defeated (stock runs fail on any death) | 5 |
| other | 5 |

So most runs never got to Lakelis: the director warped citizens into Kerning from anywhere and gave the walk to the
NPC one blocker window. Citizen runs start only with every member already standing beside Lakelis, and their
sessions cannot end under them mid-run.

## Design

### 1. CitizenPartyQuests (population side, new)

One class in `server.living.population`, ticked every 10 s on the LOW_PRIORITY lane; it only looks at loaded
citizens standing on a lobby map with a `PQ` session.

- **Forming.** A citizen at the lobby with no party opens one if no citizen party is forming there, and says it wants
  people (`pq.lf` chatter: "lf2m kpq", "kpq anyone?"). Others at the lobby whose level fits join a forming citizen
  party (bot-to-bot join, as `partyUp` does), up to the quest's seats (KPQ 3-4, prefers 4). A party a human leads is
  never touched: citizens join a human's party only when the human invites them (as now).
- **Calling friends.** A party still short after a minute asks once in guild chat; one online, loaded guild or circle
  mate whose level fits and who is not busy (training or in town, no party, not on a supply trip, not cooling down)
  answers and re-plans to the lobby. Bounded: one call per forming party.
- **Human first.** A party-less human on the lobby whose level fits gets the invite before the last seat goes to a
  citizen (the existing `party.invite` path, once per human per 10 min), and the seat is held for them until they
  answer or 60 s pass. A party never goes in while an invite to a human is out.
- **Patience.** A party that cannot reach its quest's minimum in `PQ_FORM_PATIENCE_MS` (10 min) gives up ("nvm, gonna
  go train"), leaves the party and re-plans.
- **Gate to go in** (all of it, checked on the tick that would start the run):
  `PQ_RUNS_ENABLED`; the quest in `PQ_RUN_QUESTS`; fewer than `PQ_RUNS_PER_CHANNEL` citizen runs on the channel and
  `PQ_RUNS_MAX` in all; the engine's lobby policy says a background party may take a lobby (one stays free for
  humans); no human party waiting at the entry NPC on that map (a party with a human in it that is not this one);
  the Agent scheduler's load shedding NORMAL on every shard; every member on the lobby map, alive, and passing the
  quest's own `memberProblem` (level band, inventory room) and `registrationBlocker`.
- **Going in.** Town life and stall end, then the quest's door admits the party: for KPQ the adopt seam with the
  citizen leader as operator and event leader. The coordinator walks them to Lakelis and the leader clicks her: the
  stock entry, the stock instance.
- **During the run.** The citizens' sessions carry the run: no re-plan, AFK, stroll, wander, party-up or logout while
  it lasts (a session that would end meanwhile ends after the run). The handback answers RELEASE for them (the
  population moves them, not Kerning TownLife).
- **End.** `runEnded` (forwarded by the living world's handback rule): each member's journal gets the run ("cleared
  kpq with Ann, Bo and you" / "wiped in kpq stage 2"), population counters by quest (runs, clears, fails, last runs
  for `/runtime/citizens` and `!citizens pq`), one log line `citizen pq run: ...`; chatter in party and map chat;
  a per-citizen cooldown (`PQ_RUN_COOLDOWN_MS`, 30 min: no PQ plan meanwhile, and no lobby party even while it still
  stands at Lakelis); then each, once it is alive and out of the instance, leaves the quest's party and re-plans its
  day (its circle re-forms through `partyUp`), or logs off if its session ran out during the run.

### 2. Engine seam (KPQ only, small)

- `AgentKpqAdmissionService`: a host admission for citizen parties: the same adopt seam, without the stock
  "Joining KPQ." / "Party ready." lines, and marking the session a citizen run.
- `AgentKpqSession`: the citizen-run mark. The coordinator then (a) holds full-rate simulation for the run's Agents in
  the instance as it does for companion runs, the fix for the stage-1 timeouts, and (b) speaks its casual lines
  (`voice()`), the ones companion runs already use, and (c) lets a downed member who is not the leader tap out through
  the handback (the population takes it out of the party) while the rest carry on with three, where a stock run fails
  on any death. Nothing else of the companion run (its levers assume a human leader) is switched on.
- `AgentKpqLobbyPolicy.backgroundSlotAvailable` readable by the host (it already counts Agent-operated engagements,
  so citizen runs fill the background cap and the human lobby stays free).
- The old director stops getting citizens (`SocialFabric.installSeams` hands it an empty set while `PQ_RUNS_ENABLED`),
  so one system owns citizens' PQs; it keeps the Agent population registry's own Agents.

### 3. Chatter (content)

New triggers in `chatter/pq.yaml`, lines per voice style like the rest: `pq.lf` (lf2m kpq, lf1m, need 1 more),
`pq.join` (joining, inv pls, ty), `pq.go` (ok lets go, going in), `pq.clear` (gg, gratz, ty all, ez), `pq.fail` (rip,
we wiped, ugh, sorry guys), `pq.down` (died lol gl guys), `pq.giveup` (nvm, no one around, gonna go train), `pq.call`
and `pq.call.answer` (guild: anyone kpq? / omw), `pq.wait` (to a human who cannot come in yet, and why), and
`pq.thanks` (party: "ty {name}, good run"). `{need}` is how many more the party wants. The voice pack lowercases,
abbreviates and types them as for every line; nothing calls a model.

### 4. Other quests

Doors are per quest. KPQ ships in this branch. Next, in the order the coordinators allow: LPQ (Agent leader admitted,
population lobby exists), HPQ (Agent leader admitted; needs a Henesys Park lobby in `regions.yaml`; its director is
off), OPQ (AUTONOMOUS mode exists; the OPQ live-run session is changing its coordinator, so after that lands). LMPQ,
PPQ, EPQ, RJPQ need a human leader or have no population lobby: not for all-citizen runs.

## Config (`config.yaml` -> `population:`)

| Key | Default | Meaning |
|---|---|---|
| `PQ_RUNS_ENABLED` | true | kill switch; false = citizens only gather and invite, as before, and the old director gets them back |
| `PQ_RUN_QUESTS` | `kpq` | quests citizens may run |
| `PQ_RUNS_PER_CHANNEL` | 1 | citizen runs at once on one channel |
| `PQ_RUNS_MAX` | 2 | citizen runs at once in all |
| `PQ_FORM_PATIENCE_MS` | 600000 | a lobby party gives up after this |
| `PQ_HUMAN_SEAT_HOLD_MS` | 60000 | a seat stays open this long for a human who was invited |
| `PQ_RUN_COOLDOWN_MS` | 1800000 | no PQ plan for a citizen this long after a run |

Cost: a run loads nothing new (its members are loaded citizens); it adds full-rate ticks for 3-4 Agents in one
instance for ~10-20 min, bounded by the caps, the engine's lobby caps and the load-shedding gate. The 10 s tick only
walks loaded sessions (at most 240 on channel 1) and does nothing else unless citizens stand at a lobby.

## Decisions (the user, 2026-10-24: the recommendations)

1. **All-citizen runs on by default?** On, with the kill switch, like every other built PQ and boss.
2. **Concurrent citizen runs per channel?** 1 (KPQ has 2 lobbies a channel and keeps 1 for humans, so 1 is the most it
   can use anyway); `PQ_RUNS_MAX` 2.
3. **May a citizen lead a run a human joined?** Yes. The citizen who invited you leads, as a player who formed the
   party would; you do your own Cloto coupons and get the prompts. (Not chosen: handing you the lead when you join,
   the companion desk's model, which is tuned for companions rather than a citizen party.)

## Done (branch `feat/citizen-party-quests`, offline)

Code: `CitizenPartyQuests` + `CitizenPqDoor` (population), `chatter/pq.yaml`, the presence and SocialFabric hooks, the
living world's handback forwarding (`CompanionRunLifecycle`), and in KPQ the citizen-run mark, its admission
(`admitCitizens`) and the coordinator's full rate / casual voice / tap-out for it. Ops: `!citizens pq`,
`!citizens pq send <name>`, `/runtime/citizens` (`partyQuests`, `partyQuestRuns`).

Tests (all new, all green):
- `CitizenKpqRunReplayTest`: through KPQ's real admission, registries and lobby policy, then the run's end as KPQ's
  termination reports it through the living world's handback rule: four citizens with no human go in and their clear
  comes home (journal, gg, tally, re-plan, cooldown); three citizens and a human who took the invite go in (the human a
  HUMAN member, the citizen the event leader) and their clear comes home with a thank-you to the human; a failed run
  comes home as a failure; the last lobby a human could enter is never taken.
- `CitizenRunCoordinatorTest`: in the instance a citizen run holds full rate (a stock run of the same Agents does
  not), its leader calls the stage-2 formation as a player does, a downed citizen taps out and the run goes on with
  three, and EXITING completes it without the stock waiting line.
- `CitizenAdmissionTest`: the citizen door adopts the party as a citizen run, a human member as HUMAN, and keeps out a
  party with a member away from Kerning.
- `CitizenPartyQuestsTest` (19): forming beside Lakelis, the 90 s wait for a fourth, the human invited first with the
  seat held, decline / no answer, out-of-band humans never invited, a human party waiting keeps citizens out, a
  human-led party left alone, one run a channel, the switch, load shedding, a human member out of the band told why,
  clear booked, a session that ran out logs off after the run, tap-out, a run lost from the registry ended after a
  minute, give-up, calling a training circle mate.
- `PresenceSchedulerPartyQuestTest`: a citizen in its run outlives its session until the run is over, no re-plan or AFK
  moves it meanwhile, and one just back plans no party quest.
- `SocialFabricPqCitizensTest` (changed): the old director gets no citizens while citizens run KPQ, and gets them back
  with the switch off.

Not proven offline: the stages themselves with citizens (the stage tests drive stock and companion sessions; what a
citizen run changes there is the three coordinator differences above), the navigation to Lakelis, and a real clear's
time. Those are the live checks.

## Live checks after deploy

Run on channel 1 with a GM6 character (the GM-command rules: warp, level and items to set up, never skip the behaviour).
Setting `PQ_RUNS_ENABLED` and the other keys needs a restart; `!citizens reload` only re-reads chatter content.

1. **Boot.** `!citizens pq` answers `pq runs: 0 in, 0 forming` and `/runtime/citizens` has `partyQuests`. No
   `citizen party-quest tick failed` warnings. While `PQ_RUNS_ENABLED`, no new "queued" lines from the old KPQ
   population director naming citizens.
2. **All-citizen clear.** Find 3-4 loaded Victoria citizens lv21-30 (`!citizens online`), `!citizens pq send <name>` each (or
   wait for their own PQ plans), and watch Kerning City 103000000: they walk to Lakelis and wait beside her, the first
   says lf-something in map chat, the others join its party, and after 90 s with three (or at once with four) the log
   has `citizen pq run in: quest=kpq ... leader=<first>`. Then `KPQ phase:` lines with `citizenRun=true`, pace spans
   without `throttled=1.00`, stage 1 progressing, casual stage calls, and `citizen pq run: quest=kpq ... outcome=cleared`
   within ~20 min. Afterwards: "gg"-style lines in party and map chat, `!citizens who <name>` journal "cleared KPQ with ...",
   `!citizens pq` tally `kpq 1 runs 1 cleared`, each member leaves the KPQ party and goes about its day, and none of
   them comes back to Lakelis within 30 min.
3. **A human by invite.** A test character levelled to 21-30 (`!level`), party-less, standing at Lakelis while
   citizens form: the citizen leader invites it before its last seat goes to a citizen. Accept: the party goes in with
   the citizen clicking Lakelis, the human gets the Cloto coupon prompts in stages 1-2 and plays the stages, and the
   clear ends with a party-chat thank-you naming the human. Out of the band (lv20 or lv31) the human gets no invite.
4. **Player first.** (a) Decline the invite: the next citizen takes the seat. (b) Leave the invite open: the party
   does not go in for up to 60 s, then the seat goes to a citizen and the invite is withdrawn. (c) A human party
   standing at Lakelis keeps a full citizen party out (DEBUG log `citizen <leader> party at KPQ waits: ...`). (d) With one KPQ
   lobby taken by a human run, citizens do not take the other. (e) A second citizen party on channel 1 waits while one
   citizen run is in. (f) A human's own party with citizens invited into it is not touched by any of this.
5. **A death.** In a citizen run a citizen other than the leader that dies says a "died, gl" line, respawns outside,
   leaves the party (`citizen pq run: <name> taps out of ...`), and the other three carry on.
6. **Cost.** During a run, load shedding stays NORMAL, `/runtime/budget` and the Scale health line show no tick-budget
   jump beyond the run's 3-4 Agents, and nothing starts while the scheduler sheds load.
7. **Kill switch.** `PQ_RUNS_ENABLED: false` and a restart: citizens at Lakelis gather and invite as before and never
   go in; `!citizens pq` shows `0 in`.

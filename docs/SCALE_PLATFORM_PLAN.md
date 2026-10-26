# Scale platform plan (branch feat/scale-platform, cut from ff6fbe48a)

Four platform pieces, made real and ready to switch on:

1. `population.MAP_VISIBLE_MAX` enforced (no map shows more visible citizens than the cap).
2. The central Agent tick scheduler rework (central-sharded).
3. The per-Agent mailbox.
4. The World Director (assisted, then autonomous).

Turning 2-4 on by default is the user's call; this branch builds them, measures them offline, and keeps the
shipped defaults where they are until the user answers. 1 may ship on, behind a kill switch.

## 0. Live baseline (read-only, `logs/cosmic-log.log`, build ff6fbe48a, boot 20:05:49 on 2026-10-25)

- 248-249 Agents, players=0, `-Dagents.scheduler.mode=central-sequential` (launch.bat:31).
- 406 `Slow central Agent tick` WARNs between 20:06:17 and 21:12:16 (66 min, about 6 a minute). Single-Agent tick
  times: p50 469 ms, p90 677 ms, p95 831 ms, max 13,432 ms (20:59:08, agent 469). One Agent produced 37 of them.
  The multi-second ones sit next to `Slow bot pathfind: caller=target-score took 855.9 ms` lines: the cost is
  navigation inside one Agent's tick, not the scheduler.
- In central-sequential every other Agent waits behind such a tick: a 13.4 s tick is a 13.4 s freeze for all 248.
- Process CPU (read-only `Get-Process` sample, 20 s window at 21:0x): 0.43 cores; 0.44 cores averaged over the
  first 61 min (1,626 s CPU / 3,670 s up). The engine is not CPU bound; it is head-of-line blocked.
- There is no periodic scheduler line in the log: queue lag p50/p95/p99 exist only in `@agentscheduler`
  (`AgentSchedulerMetrics.Snapshot`, AgentSchedulerMetrics.java:18-41). The July pre-merge server-only gates
  (docs/agents/evidence/central-scheduler/phase-11/METRICS.md:60-95) measured queue-lag p99 68 ms sequential vs
  55 ms sharded at 250 Agents and 75 vs 48 ms at 500, with work p50 93-165 us.

## 1. MAP_VISIBLE_MAX

**State.** Declared only. `PopulationConfig.java:31-32` (`MAP_VISIBLE_MAX = 40`, config.yaml:1104 ships 60), checked
`>= 1` in `validate()` (:147-148), read nowhere else in src/main or src/test. `PERCEPTION_HOPS` (:29-30) is also
never read. docs/POPULATION_GUIDE.md:220-222 calls it a per-map brake and :238-239 lists it under "Not done yet".

What limits a map today:
- `FieldCatalog.trainingField` (FieldCatalog.java:119-162) skips TRAIN fields at `occupancy >= capacity`;
  `occupancy` (:197-205) counts non-virtual citizen sessions by `session.mapId` across ALL channels; capacities in
  fields.yaml are 0-6 and towns are 0. Towns, PQ lobbies, the Free Market and instructor maps have no cap.
- TownLife admission (`AgentTownLifeLifecycleRuntime.java:108-113`, 24-36 per town JSON) only limits the TownLife
  engine: a rejected citizen strolls the town anyway (`PresenceScheduler.stroll`).
- `AGENT_MAP_CROWD_MIN_AGENTS` / `AGENT_MAP_MAX_ACTIVE_COMBAT_PERCENT` (agent-engine.yaml:223,227) choose who rests on
  a grinding map (`AgentMapActivityPolicy.java:44-99`); not admission.

Live: 51 of 350 logins in the hour went to the Free Market entrance 910000000, 39 to Kerning, 37 to Henesys.

**Where citizens enter a map.** Exactly two places:
- login: `PresenceScheduler.materialize` (PresenceScheduler.java:411-467) picks `mapFor` (:717-742) and loads the
  character in place (`ResidentSpots.fallback`, :428-430). A login is virtual only when `loaded >= ONLINE_MAX`
  (:227).
- every move: `PresenceScheduler.travel` (:975-1003); callers are replan (:909), field-boss leave (:945), the supply
  trip's resume (:1181), SocialFabric circles (SocialFabric.java:531), Market rooms (Market.java:161) and citizen
  party-quest lobby trips/calls (CitizenPartyQuests.java:656,840). Walks (CitizenTravel) and cheap moves (`arrive`
  :1028-1064) both start here.

**Plan.**
- `MapVisibleCap` (new, server.living.population): counts the visible citizens of one map instance (channel + map id):
  citizens whose loaded character stands there now (the truth, so engine-driven moves such as supply trips count too)
  plus admitted moves still on their way there (a reservation per session, cleared when it lands or is replaced, with
  an expiry so a dropped move cannot hold a slot forever). Check-and-reserve is one locked step, so two moves cannot
  both take the last slot.
- login: a full `mapFor` choice is re-picked (same activity, full maps excluded), then the home town, then another
  town; if every pick is full the citizen logs in virtual (online on the ledger, never seen) - "stays abstract".
- travel: a full destination gets another destination of the same kind (another field, town or market room). A
  purposeful move with no alternative (party-quest lobby, job errand, supply-trip resume) waits where it stands and
  retries, and one ambient citizen on the full map is sent elsewhere so a slot opens.
- sweep: a map over its cap (a cap lowered by reload, or engine moves) sends its newest ambient citizens elsewhere
  through `travel`, which walks them out through a portal while someone watches and moves them the cheap way while
  nobody does. Nothing ever vanishes in place for the cap.
- Kill switch `population.MAP_VISIBLE_CAP_ENABLED` (default true): false = today's behaviour exactly.
- Counts citizens only (Residents, companions and other Agents are not citizens).
- Known bounded exception: a citizen walking a multi-map route while watched passes through intermediate maps
  (AgentMapWalk) without a check; it is on such a map for seconds. Documented, not gated.

## 2. Central scheduler rework (central-sharded)

**State.** Built. `AgentSchedulerMode` LEGACY_PER_AGENT / CENTRAL_SEQUENTIAL / CENTRAL_SHARDED. The code default is
CENTRAL_SHARDED (`AgentSchedulerConfig.configuredMode`, AgentSchedulerConfig.java:181-193), but every launcher pins
sequential: launch.bat:31, Dockerfile:52, docs/SETUP.md:103, docs/TEST_GUIDE.md:18 (came with the cosmic_agents merge,
docs/COSMIC_AGENTS_MERGE_2026-09-19.md:42). Sharded = `shardCount` = clamp(cpus/2, 1, 4) independent
`AgentTickScheduler`s keyed by a hash of (character id, session generation)
(AgentShardedTickScheduler.java:95-121), gated by the gateway affinity audit (`AgentScheduler.java:46-52`,
`AgentGatewayAffinityCatalog.multiShardReady`, all 31 gateway annotations are shard-safe today).
Docs say Phases 0-11 are "locally complete" (docs/agents/AGENT_CENTRAL_SCHEDULER.md:329-342) and the default flip
waits on parity and soak evidence (phase-11/REMAINING_RISKS.md:3-17). Tick slicing exists, off
(DEFAULT_TICK_SLICING_ENABLED, yaml:5827), but has only four coarse slices, so it cannot split the slow navigation
call. Simulation tiers are on (yaml:5755,5815). Load shedding is off (yaml:5703).

**Missing.**
- Every shard's loop runs on Cosmic's 4-thread CORE timer lane (`CosmicSchedulerGateway`, TimerManager.java:78), the
  same pool Cosmic's own core timers use. Four shards each stuck in a 13 s tick would take every CORE thread.
- The switch is three copies of a JVM flag in launchers and docs instead of one shipped value.
- `Slow central Agent tick` has no shard or map; no periodic scheduler line in the log at all.
- No tick-lag or CPU harness: AgentTickSchedulerScaleGateTest is correctness only (no timing, no output).
- Cross-Agent shared state that sharding newly exposes is unaudited outside the gateways
  (docs/agents/AGENT_COSMIC_THREAD_AFFINITY_AUDIT.md:65-77 says so). Audit running in this session; findings and
  fixes land in section 6.
- tools/soak/Test-AgentSchedulerLiveGatePreflight.ps1:2 requires branch `feature/agent-central-scheduler-runtime`.
- Docs contradict each other on the default (AGENT_CENTRAL_SCHEDULER.md:10 vs :336,
  AGENT_FULL_CENTRALIZED_SCHEDULER_IMPLEMENTATION_PLAN.md:62, AGENT_ENGINE_SCALING_TRACK.md:50).

**Plan.**
- One switch: `AgentSchedulerConfig.DEFAULT_MODE` in agent-engine.yaml, shipped `central-sequential` (today's live
  behaviour); `-Dagents.scheduler.mode` still overrides. Launchers and docs lose their pins.
- Dedicated Agent timer lane: `TimerManager.SchedulerLane.AGENT` (threads = shard count) for the shard loops and their
  wakes in central-sharded mode, so a slow Agent stalls only its own shard and never a Cosmic core timer.
  Sequential stays on CORE unless the same key asks otherwise.
- Slow-tick WARN gains shard and map; a 5-minute `Agent scheduler health` INFO line (mode, shards, agents, queue lag
  p50/p95/p99/max, work p50/p95/p99, slow and failed counts, deferrals, mailbox depth) next to `Scale health`.
- `AgentSchedulerLagBench` (opt-in `-DrunBenchmarks=true`): see Measurement.
- Fix the preflight branch check; reconcile the docs.

## 3. Per-Agent mailbox

**State.** On in every central mode: `AgentMailboxRuntime.enabled()` (AgentMailboxRuntime.java:17-20) is true unless
the mode is legacy, so it is on live today. Drained before gameplay each tick (AgentTickOrchestrator.java:36,
AgentTickSliceRuntime.java:22, AgentQuiescenceController.java:123). About 65 call sites route through
`dispatch/submit` (chat, equip handler, trade, formation, party gather, companions, bench, residents, entry-scoped
delayed actions via `AgentSchedulerRuntime.scheduleScoped`). "Switched off" is stale
(phase-0/REMAINING_RISKS.md:34-35).

**Missing (bypasses that mutate an Agent off its own tick).**
1. `PlayerInteractionHandler.java:318-319` -> `AgentRelationshipRuntime.setInteractionTarget` on the packet thread.
2. Citizen moves: `PresenceScheduler` cheap-move `arrive` (LOW_PRIORITY timer, :1016/:1043) stops TownLife/movement
   and calls `changeMap` (:1076) off the Agent's tick; `CitizenTravel` steps (:131) stop movement and place it.
3. `AgentWorldDirectorControlService.setMode` (:57-63) changes live capability state from the HTTP bridge thread.
4. About 62 entry-less `AgentSchedulerRuntime.schedule/afterDelay` calls (PQ lobby ticks, test services) and
   `RosterService` (companion-ops executor, changeMap at :1140, :2922). Wider than this branch; listed, not moved.
- `enabled()` rebuilds `AgentSchedulerConfig.fromSystemProperties()` on every dispatch.

**Plan.** Route 1-3 through the mailbox behind one switch, `AgentMailboxRuntime.ROUTE_EXTERNAL_MUTATIONS` (shipped
false until the user decides), cache the mode check, and measure mailbox delivery latency (submit -> run on the
Agent's tick) per scheduler mode in the bench.

## 4. World Director

**State.** Observe/shadow sampling runs every 5 s per Agent in OBSERVE mode
(AgentRegistrationCoordinator.WORLD_DIRECTOR_OBSERVE_INTERVAL_MS, yaml:5675); MANUAL/ASSISTED/AUTONOMOUS poll a file
inbox every 1 s and drive durable handoffs with rollback (`AgentWorldDirectiveProcessor`, 60 s deadline yaml:5683).
Gates: `AgentWorldDirectorAssistedGate` (:23-45) and `AgentWorldDirectorAutonomousGate` (:26-58), keys
AGENT_WORLD_DIRECTOR_ASSISTED_* / AUTONOMOUS_* (yaml:297-349, all closed).

**Missing.**
1. Nothing produces a POLICY directive: every directive is OPERATOR (AgentWorldDirectorExecutive.java:178), approved
   proposals too. OPERATOR skips the rollout gates (AgentWorldDirectorExecutionRuntime.java:143-151), so ASSISTED acts
   like MANUAL and AUTONOMOUS never acts. The ASSISTED_* / AUTONOMOUS_* keys are reached only by tests.
2. Observe samples live in memory only (AgentWorldDirectorObserveState.java:39-45) and reset at relogin, so the
   autonomous `MIN_OBSERVE_SAMPLES` (100 x 5 s) restarts at 0 on every boot; yaml:338 claims they are restored.
3. `MAX_RECENT_FAILURES` reads tick failures (`AgentTickFailureStateRuntime.failureCount`), not Director failures.
4. No Logger or metric anywhere in control/ or world/; what OBSERVE would choose is visible only via
   `@agentworld shadow`.

**Plan.** ASSISTED: the Director's own observe choice becomes a POLICY proposal; an operator approval issues it as a
POLICY directive through the assisted gate (allowlist, targets, concurrency, rollback). AUTONOMOUS: for Agents in the
autonomous cohort, a stable observe choice (same selection N samples running, enough samples, no recent Director
failures, a concurrency slot) is issued as a POLICY directive with no approval. Persist observe sample evidence with
the session file; count Director handoff failures/rollbacks; log every POLICY proposal/handoff/rollback and add a
Director part to the scheduler health line. Defaults stay closed until the user decides.

## 5. Measurement plan (offline)

`AgentSchedulerLagBench` (src/test, `-DrunBenchmarks=true -Dtest=AgentSchedulerLagBench`, heap 768 MB) drives real
`AgentTickScheduler` shards on real executors with synthetic Agents:
- cost model from evidence: ordinary ticks log-normal around the July gates' work p50 (about 150 us, p99 about
  2 ms); slow ticks drawn from the live slow-tick quantiles above at the live rate (406 per 66 min per 248 Agents),
  plus a 4x stress run; spin-burn, so CPU is real.
- scenarios: 248 Agents at the background cadence (live, players=0), 600 Agents with a fifth at the 50 ms
  presentation cadence, 1,000 Agents stress.
- modes: central-sequential on a shared 4-thread pool (today), central-sharded x4 on the shared pool, central-sharded
  x4 on the dedicated lane.
- reported: per-Agent tick-gap overrun p50/p95/p99/max (how late each Agent's next tick is: the freeze a player
  sees), share of ticks more than 250 ms late, a probe timer on the shared pool (how late Cosmic's own core timers
  run), process CPU cores and scheduler overhead (CPU minus the burned work), mailbox delivery latency
  p50/p99/max for actions submitted from an outside thread, and World Director observe cost per sample.
Before = the shipped configuration; after = each candidate. Interleaved runs (A B A B) because the host also runs the
live server and other sessions.

Live measurement for the coordinator to arrange after a deploy: the new `Agent scheduler health` line over one
hour in central-sequential, then one hour with `-Dagents.scheduler.mode=central-sharded` at players=0, same build.

## 6. Results

(filled in as measured)

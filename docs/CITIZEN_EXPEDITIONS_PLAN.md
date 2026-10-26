# Citizens run boss expeditions (plan, 2026-10-25)

Goal: citizens form and join the stock boss expeditions the way they run KPQ and HPQ
(docs/CITIZEN_PARTY_QUESTS_PLAN.md). They gather at the boss's entry NPC, the right level and gear sign up, a human
who fits can join through an invite, the run goes in and they go back to their day. Only where the bossing engine can
actually carry them. Player first, a kill switch and caps in config, clear log lines. Branch `feat/citizen-expeditions`.

## What exists (read 2026-10-25)

- **The stock expedition.** One expedition per boss type per channel (`cm.getExpedition`): while one registers or
  fights, the entry NPC tells everyone else "Someone already took the initiative". The leader creates it at the NPC,
  members sign up through the NPC's own window, the list closes after 5 minutes (`ExpeditionType`), and the leader's
  Start warps the members standing on the recruit map into the event (Expedition.java, 1061014.js). The game shows the
  stock "[Expedition] ... has been declared the expedition captain" notices for any leader. A run ends when at most
  one player is left, and bots count.
- **The companion boss runs (P7 to P20).** `AgentBossRunService/Session/Registry/Host` (server.agents) and
  `CompanionBossRuns` (server.living.roster) carry companions through every expedition boss with a human owner. Each
  member signs up through the NPC's window, one talk per tick. Inside, the boss's own fight runs it (Balrog's
  scenario, the priority fight of boss-rules.json). A dead member waits outside, or re-enters for Scarga and Horntail,
  or revives inside for Pink Bean. After the clear it follows its owner out. Bench allies are members that lead
  themselves (`Kind.SELF_LED`), anchored to their owner. Rule A: when no human stands in the fight for
  `HUMAN_GONE_GRACE_MS`, every member walks out (`watchHumans`). The host rule (seat, room on the list, lines, records,
  the daily quota) is one `AgentBossRunHost.Rule`, which the living world installs.
- **Who moves the boss.** Only Easy and Normal Balrog are moved by the server once a bot has signed up for the fight
  (`COMPANION_BOSS_SERVER_AUTHORITY`, `CompanionBossRuns.forcesServerAuthority` -> `AgentBossRunRegistry.signedUpIn`,
  which counts any boss-run session). Every other expedition boss (Zakum, Scarga, Horntail, The Boss, Pink Bean,
  CWKPQ) is moved by a human's client. Bots take its attacks only through P8's mirror, which reads that client's
  packets. With no human in the fight such a boss never attacks, so a citizen-only run there would be no real fight.
- **Citizen levels.** Regions cap citizens at Lv120 (Leafre 70-120, Crimsonwood 70-110, El Nath and Mu Lung 45-90,
  Malaysia 45-95, Aquarium 50-80, Ariant 35-85, Orbis 40-70). Victoria's citizens are Lv1-45, so nobody who lives near
  Sleepywood meets Balrog's Lv50 gate. Citizens wear their own job-gated gear, not the 60%-scrolled sets the test crews
  had. Only Victoria, Orbis and Ludibrium list lobbies today; every other region's citizens never go to one.
- **What the bosses take** (Mob.wz, live results in COMPANION_BOSSING_P7A..P20):

| Boss | HP (parts) | Body level | Live clear with companions |
|---|---|---|---|
| Easy Balrog | 7.5M (body 3.21M, claws 2.06M + 2.23M) | 84 | Lv70 3rd job crew of 9: claws 5.5 min, body ~15 min |
| Normal Balrog | 10M (body 4.28M, claws 2.64M + 3.06M) | 105 | Lv100 crew, 60% gear: 3 min 20 s |
| Zakum | 528M (8 arms of 33M, bodies 66M + 88M + 110M) | 140 | Lv160 crew with 60% gear, 2026-10-20 |
| Scarga | ~600M | - | Lv200 crew, 35 min (Lv160 could not) |
| The Boss | 1.05B | - | 15 bots at Lv160, 1,500 potions each, 49 min |

- **Supplies.** The boss standards are 130,000 HP / 32,000 MP points for Easy Balrog (about 0.22M mesos) and 300,000 /
  240,000 for Normal (about 0.86M), bought at the Ant Tunnel Park store on the way (BossEntrance).

So the engine can carry citizens through **Easy and Normal Balrog**, with or without a human, because the server moves
Balrog there and Lv50-110 citizens can win it. Every other expedition boss is out of their reach, and without a human
it does not even fight back.

## Design

### 1. Lobby at Mu Young (population content)

- The Temple of Mu Young (105100100) becomes a lobby `balrog` (Lv50-120) for the regions whose citizens reach Lv50:
  El Nath, Orbis, Aquarium, Ariant, Mu Lung, Leafre, Crimsonwood and Malaysia. A citizen on a PQ outing travels there
  as it travels to any lobby: on foot where someone watches and a route exists, else by the delayed map change.
- A citizen whose level fits more than one lobby of its region now picks one at random (Orbis Lv51-70: OPQ or Balrog).
  Victoria's lobbies do not overlap, so KPQ and HPQ are unchanged.
- Regions that had no lobby now send their Lv50+ citizens on PQ outings at their cohort's PQ weight (5-15% of a day).

### 2. Forming (CitizenPartyQuests, the same as for KPQ and HPQ)

- Citizens wait beside Mu Young. The first opens a party and asks for people. Others whose level fits join it, a short
  party calls citizens over (mates first, then townsfolk of the band), and a party-less human whose level fits gets
  the party invite before the last seat goes to a citizen. The party's size picks the difficulty: Normal for 6 with
  every citizen at `BOSS_NORMAL_MIN_LEVEL` (80) or more, else Easy (3 or more).

### 3. Going in (a citizen door at Mu Young + a citizen-run mark on the boss-run session)

- The citizen leader creates the expedition with Mu Young's own window. A new `AgentBossRunService.openCitizenExpedition`
  opens a boss-run session marked as a citizen run, with every citizen of the party as a self-led member anchored to
  the leader. The session walks each one up and signs it up through her window, as it does for bench allies. A human
  in the party signs up by talking to her (the leader tells them once). Once everyone in the party is on the list,
  or the hold for a human is over, the leader presses her Start.
- Inside, the run is the boss's own fight (server authority, Balrog's scenario). For a citizen run:
  - Rule A does not apply on Balrog, so citizens fight on with no human inside. A human who joined fights beside them.
  - After the clear, the citizens play the reward room as players do (decision 4): the battle's exit NPC, the reward
    box, a human's first 7 seconds, then their share by weapon class. Then they walk out by the stock way (the reward
    room's portal), at most `CITIZEN_AFTER_CLEAR_MS` (3 min) after the clear.
  - A citizen that goes down waits outside, then goes back to its day.
- The run's end reaches the population (the same hand-back as the PQ runs). Each citizen books it in its journal
  ("cleared Easy Balrog with ..."), says something, leaves the party, keeps out of expeditions for
  `BOSS_RUN_COOLDOWN_MS` (1 h) and plans its next activity.

### 4. Player first

- Citizens never open a Balrog list on a channel where a human leads a list or a fight of either difficulty, or
  while a human party waits in Mu Young's room. A human who walks in while citizens register can still sign up on
  their list, which keeps `COMPANION_BOSS_HUMAN_SEATS` seats free.
- The leader never presses Start while an invite to a human is out, or while a human in the party has not signed up
  yet and the hold is not over.
- Boss drops (decision 4): citizens loot as players do. A human in the run gets the first 7 seconds in the reward room,
  then each citizen picks up its own share (AgentEasyBalrogRewardPolicy by the weapon it wears).
- A player's companions never join a citizen list (`AgentBossRunService.enlist` refuses them), and a citizen list
  keeps its last `living_world.COMPANION_BOSS_HUMAN_SEATS` (2) seats for players (the population's host rule).
- Bounded: `BOSS_RUNS_ENABLED` (the kill switch), `BOSS_RUN_BOSSES` ("easy_balrog,balrog_normal"; only bosses the
  server moves are accepted), `BOSS_RUNS_MAX`, `BOSS_RUN_SEATS` (citizens per run, 6), the Agent scheduler's load
  shedding (no Start while it sheds), and the boss-run session's own full-rate hold, only inside the fight.

### 5. Supplies and gear

- A citizen signs up only at the boss's level gate and carrying its potion standard. One short of it buys on its own
  supply trip first, as companions do. One that cannot afford it does not sign up and says so.

### 6. Chatter (content, `chatter/boss.yaml`)

- New triggers in each citizen's voice: `boss.lf` ("lf easy balrog, need 2"), `boss.go`, `boss.clear` (gg),
  `boss.wipe`, `boss.down`, `boss.thanks` to a human who came along, `boss.giveup`, `boss.signup` to a human in the
  party who still has to talk to Mu Young.

### 7. Logs and ops

- `citizen boss run in: boss= session= channel= leader= members=` and `citizen boss run: boss= outcome= minutes=
  members= reason=`, beside the session's own "Boss run ..." lines. `!citizens status` and `/runtime/citizens` show
  the runs.

## Questions (asked 2026-10-25)

1. Which bosses do citizens run? Recommended: Easy and Normal Balrog only, for now.
2. May citizens run Balrog with no human in it? It holds that channel's Balrog for about 20-30 minutes. Recommended:
   yes, but they open a list only while no human is at Mu Young or running Balrog on that channel.
3. How many citizen expeditions at once? Recommended: 1 on the whole server.
4. Boss drops? Recommended: citizens never pick them up. A human in the run gets the drop of a citizen's kill, and in
   a citizen-only run the drops stay on the floor.

## Decisions (the user, 2026-10-25)

1. Easy and Normal Balrog only (`BOSS_RUN_BOSSES`; config validation refuses any boss the server does not move).
2. Yes, citizens run Balrog with no human in it, but never open a list while a human leads a Balrog list or fight of
   either difficulty on that channel, or while a human of Lv50+ outside their party stands in Mu Young's room.
3. `BOSS_RUNS_MAX: 1` on the whole server.
4. Citizens play the reward room and loot their share as players do, a human getting the first 7 seconds (not the
   recommendation).

## What was built (branch `feat/citizen-expeditions`)

- Agent side (`server.agents.capabilities.expedition`): `AgentBossCitizenRuns` (the bosses citizens may run, with Mu
  Young's create and Start choices); `AgentBossRunService.openCitizenExpedition` and `startCitizens`;
  `AgentBossRunSession` citizen mark (the leader is on the list already, its Start talk every
  `CITIZEN_START_TALK_EVERY_MS` once asked, no rule A, the after-clear play then `CITIZEN_AFTER_CLEAR_MS`);
  `AgentBossRunHost.installCitizens` (a citizen run reports to the population, never to the living world);
  `AgentBossRunPorts.expedition`; `AgentEasyBalrogScenario.forCitizens` / `forNormalCitizens` (reward room played,
  weapon class read off the citizen; Normal's exit NPC warps one talker at a time).
- Population side (`server.living.population`): `CitizenBossDoor` (Mu Young's door: bosses on, Easy or Normal,
  player-first lobby check, potions on arrival, the stage of the list); `CitizenPqDoor.ExpeditionDoor`;
  `CitizenPartyQuests` (forming as for PQs, `boss.*` lines, the Start once everyone is listed, the host rule, downs,
  ends, its own cap and cooldown); `FieldCatalog.lobbiesFor` and a random pick among fitting lobbies; regions.yaml
  `balrog` lobbies; `chatter/boss.yaml`; `!citizens pq send <name> [lobby]`.
- Config: population `BOSS_*` keys (config.yaml), agent-engine.yaml `CITIZEN_START_TALK_EVERY_MS`,
  `CITIZEN_AFTER_CLEAR_MS`.

## Tests (offline)

- `AgentBossRunCitizenRunTest` (real boss-run service and session, fake ports): the leader makes the list and the run
  takes its citizens; no list made, no run; an open list on the channel is never joined; the leader needs no sign-up
  talk and the others sign up one a tick; no Start before the population asks, then again every
  `CITIZEN_START_TALK_EVERY_MS` while the list stays open; a companion run is never started by the population; a
  player's companions never join a citizen list; the run reports to the citizen host only; citizens fight on with no
  human past `HUMAN_GONE_GRACE_MS`; after the clear they play the reward room, then head out and the run ends
  "members-out", cleared; the Balrog scenario plays the reward room only for citizens.
- `CitizenBossRunsTest` (population with the real `CitizenBossDoor` and a fake boss-run engine): a party at Mu Young
  forms and its leader makes the list; the Start once every citizen is listed, once; a human of the party is told to
  sign up and the Start waits for them, but not past `BOSS_SIGNUP_WAIT_MS`; no list while a Balrog list or fight is
  open; a Lv85 human outside their party in her room keeps citizens out, a Lv30 one does not; one citizen expedition
  in all; a citizen short of potions says so and goes; a downed citizen says so and leaves the party while the run
  goes on; a clear is booked, talked about, thanks the human and sends everyone back with the longer cooldown; a list
  that closed unstarted is a failure; the kill switch and a boss the server does not move keep them from signing up;
  Normal for six at Lv80+ with Normal's potions, else Easy; holds and stages; `BOSS_RUN_BOSSES` validation.
- Together the two halves cover the chain: the population's door opens the run through the service, the run reports
  through `AgentBossRunHost.citizens()` to the population's host, and the population books it. No offline test drives
  the real Mu Young script, the stock expedition's warp, Balrog's own fight or the reward room's drops.

## Live checks after deploy

The OPQ live-run session (the only one driving the client) or the coordinator runs these. GM6 test character
crewfriend24 or Bossfriend; citizen names from `!citizens online` (an Orbis, El Nath or Leafre citizen of Lv50+).

1. **Boot.** No ERROR lines; config validated (`BOSS_RUN_BOSSES` accepted). `!citizens pq` prints the run line with
   no "BOSS_RUNS_ENABLED is off".
2. **Lobby.** `!citizens pq send <name> balrog` for three loaded Lv50+ citizens of a region past Victoria. Each
   travels to 105100100, buys potions (a "Shop" line for Ant Tunnel Park's store, or none when it carries them), and
   waits beside Mu Young. Expect `boss.lf` in map chat and a party of three.
3. **All-citizen Easy Balrog.** With no human at Mu Young, expect `citizen boss run in: boss=easy_balrog`, the stock
   "[Expedition] ... has been declared the expedition captain" notice, `Boss run sign-up ... registered` for each,
   `citizen boss run start`, `Boss run fighting`, then `Boss run cleared` and `citizen boss run: boss=easy_balrog
   outcome=cleared` within about 30 minutes. Follow it by the log lines. To watch, use a test character below Lv50
   (the player-first rule ignores one) in Mu Young's room; never warp into the battle map, which is the event's.
4. **Player first.** While a GM of Lv50+ outside their party stands in Mu Young's room, no citizen list opens
   (`gives up` or waiting lines only). While a human-led Balrog list is open, none either.
5. **Human by invite.** A Lv50+ test character with no party at Mu Young gets the citizen leader's party invite;
   accept, hear `boss.signup`, sign up with Mu Young, see the leader start, fight beside them, get the first 7 s in
   the reward room, hear `boss.thanks`. The party breaks up after.
6. **Downs and wipes.** `!kill` a citizen inside: `boss.down` in party chat, it leaves the party, the run goes on.
7. **Kill switch.** `BOSS_RUNS_ENABLED: false` and restart: citizens still gather and invite at Mu Young but never
   make a list.
8. **Load.** During a run, `/runtime/budget` shows no shedding caused by it, and the run holds full rate only inside.

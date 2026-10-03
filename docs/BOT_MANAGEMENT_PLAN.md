# Bot Management Plan (party, bench, expeditions)

> Status: exploration + plan, 2026-09-27. Nothing here is implemented yet. Built from a verified read of the roster, agent lifecycle, party/NPC/UI surfaces, PQ and expedition code, kaentake and MapleEdit, then three competing designs (playable-soonest, immersion-first, foundations-first) scored by three judges; this is the winner with the agreed grafts and two review passes applied. Facts disputed in review were re-checked in source and are marked (verified). Every class or method named here exists at the path given or is marked NEW. Paths are relative to `FriendServer/MapleCommons v83 Server` unless noted. Open questions for the user are in "Decisions for the user".

## Summary

- **The stock party window becomes the crew manager, with no client change.** Invite calls a companion from the bench, Expel sends it to the bench, and Leave takes your crew with you. A bot can never become leader. Make-room at 6/6 depends on P0 probe 12. If that probe fails, make-room moves to a Crew-window button.
- **Everything is clickable.** Double-click your own companion to open its card in the stock NPC window. An "Adventurer's Roster" USE item opens the whole Crew window (party 5/5 plus bench) from anywhere. The existing chat verbs still work. They now go through the same owner checks and the same roster executor as the menus.
- **5 active companions plus a bench.** `ROSTER_MAX` caps the whole roster: active, travelling, held, away, benched and parked companions, hires and escorts all count. The default of 10 means 5 active plus 5 on the bench. Bench size per friend is decision 20.
  - The bench trains, farms, gathers, sells, runs a real FM stall or rests in town.
  - A called companion joins the party at once, and the party window shows where it is coming from. After a short ETA it walks in through your map's portal.
- **Every friend can get companions.** Supply is the 15 recruitable Residents, shared out by decision 18, plus citizens you party with and hire (synthetic `hire:<cid>` personas). A hired citizen keeps its face and is adopted where it stands, with no blink. The dropped `bot_owners` table is not revived.
- **KPQ comes first; the order after that is decision 21.**
  - Lakelis proposes a lineup and can call someone from your bench. The extras wait beside her and rejoin afterwards.
  - Companions play the real stage roles through the public PRODUCTION-mode `AgentKpqAdmissionService.admit`. Nothing has ever called that entry point, so P6 opens by smoke-testing exactly that path.
  - The other PQs follow in P6b.
- **Expeditions hold more than 5 bots, from every friend in the expedition.** Each friend brings party companions plus up to `ROSTER_MAX − 5` bench allies.
  - Easy Balrog becomes playable in P7. It runs under server authority while companions are registered, which reuses the existing Balrog behaviours; Poison Golem already forces server authority the same way.
  - Zakum comes in P8 and needs the NEW `BossAttackMirror`. It applies real damage through `ServerMobDamageService.applyOrdinaryAttack`.
  - A boss is never enabled for friends until bots are seen taking its hits.
- **Two friends in one party.**
  - Each friend gets a fair share of the 6 seats, and humans always come first.
  - A leader's expels stick, and each owner manages only their own companions.
  - A citizen you invite holds a guest seat charged to you.
- **Foundations come first.** Before any full manager UI ships, these are fixed:
  - the ownership and authority holes, including the whisper bridge and the `transfer` verb;
  - roster/party drift;
  - companions stranded by channel changes;
  - the "ghost PQ map" bug;
  - double-loading;
  - follow pop-ins.

  A zero-drift auditor gates every phase from P2a on.
- **Learning.**
  - A bandit can choose bench duty and camp from measured EXP and meso rates.
  - Bench yields are calibrated from live slices.
  - The combat learner runs in LEARN mode only for bench grinders no human can see, and its tables are adopted only after an arena check.
  - Every PQ and boss run is recorded per companion, so lineup suggestions can be learned later.
- **Milestones.**
  - P1 ships safety on its own.
  - The first no-typing milestone is P1.5 (Crew window v0).
  - The full click-only manager, with party semantics, bench, settings and hiring, is P0 through P4 (P2a, P2b, P3, P3b, P4).
  - Nothing through P9 touches the DLL. The only client data is a small item-name patch, plus Resident portrait batches if you approve them (decision 6).

## What exists today

**Works and can be reused**
- **Native party window.** `PartyOperationHandler` handles ops 1-6. The party window already shows 6 slots with name, job, level, channel, map and a same-map HP bar.
- **Party chokepoint (verified).**
  - `World.updateParty(int, PartyOperation, PartyCharacter)` (World.java:1060) is the single chokepoint for JOIN, LEAVE, EXPEL, DISBAND, CHANGE_LEADER and LOG_ONOFF. It calls no living code.
  - It fetches the `Party` before its switch. It then broadcasts through the private `updateParty(party, …)`, which iterates `party.getMembers()`.
  - `disbandParty` (:993-999) only removes the map entry, so a hook at the end still sees every member, even for DISBAND.
  - What is lost is CHANGE_LEADER's previous leader, because `party.setLeader` runs before the broadcast.
- **Spawn APIs (verified).** All are in `AgentInteractionRuntime`:
  - `spawnAgentForLeader(leader, name[, joinParty])` (:50/:55) follows the leader and joins its party by default.
  - `spawnStationaryAgentForLeaderAt(leader, name, map, pos)` (:87) registers without follow.
  - `registerDirectorIdleAgent` (:67).
  - `reloginAgent` (:112).
- **Roster backend.**
  - `RosterService` (server/living/roster/RosterService.java) has recruit :173, call :222, bench :314, swap :331, delegate :373, beginEscort :556, onOwnerLogin :573 and onOwnerLogout :585.
  - `recruit` (:178) and `ContractService` (:73) refuse once `roster.size() >= ROSTER_MAX` (verified). The cap covers every row, so the default of 10 means 5 active plus 5 on the bench.
  - `RecallTravel` gives ETAs of 20 s + 40 s per hop, or 180 s off-island.
  - `BenchLedger` and `BenchSessions` share `BenchSessions.benchGoverned` (:82). `ContractService` handles contracts.
- **NPC windows.**
  - `LivingTalk` is the server-driven NPC window builder. `open(chr, npcId)` (:46) always needs a speaker NPC id, and the speaker byte is always 0. It has no number input and runs only while `living_world` is ENABLED.
  - `NPCScriptManager` JS has `sendGetNumber` and validated `#L` menus. `NPCConversationManager.sendNext(text, speaker)` (:193) already passes a speaker byte.
  - `PacketCreator.getNPCTalk(npc, type, text, end, speaker)` (:3439), `getNPCTalkNum`, `getPlayerNPC`/`IMITATED_NPC_DATA` and `partyInvite` all exist.
- **Recruiting a Resident.** `ResidentDialogue` offers "Would you travel with me?" through `ContractService.askToJoin` (:354-356).
- **Double-click hook (uncommitted).** `LivingWorldHooks.characterClicked` (:171) calls `ResidentDialogue.onCharacterClicked` (:322). Today it skips any companion owned by anyone (:327).
- **KPQ production seam (verified).**
  - `AgentKpqAdmissionService.admit(operator, leader, members, seed, now)` (kpq/AgentKpqAdmissionService.java:29, public) runs in **PRODUCTION** mode. Nothing calls it.
  - GM6 `!kpqtest party` calls the package-private 6-argument overload with **TEST_OBSERVATION** (AgentKpqTestService.java:275-276).
  - The two modes differ in several ways. Human-leader prompts fire only in TEST_OBSERVATION (`AgentKpqHumanDialogueRuntime.promptDelayed`, :131-134). Termination and return paths differ (AgentKpqTerminationService:56-60, 88-91), as do post-run lifecycle (AgentPartyQuestLifecycleRuntime:44) and checkpoints (AgentKpqCheckpointService:21).
  - Admission validates the party: 3-4 unique members, at least one Agent, exactly the authoritative party, the right leader, **everyone on RECRUIT_MAP 103000000** (:254), lv21-30, same channel, and free Use/ETC slots.
  - It registers all Agents in one engagement, which bypasses the directed-lobby deadlock with 2+ Agents.
  - `AgentKpqSessionRegistry.remove` (:61) exists. `AgentPartyQuestLifecycleRuntime.forceStop` (:76) begins recovery and then recovers. `recover` (:101) does nothing unless the engagement is already RECOVERING (verified).
  - The legacy Stage1/5 hooks already stand down when `AgentPartyQuestRuntime.active`.
- **Other PQs (verified).** EPQ, LMPQ, LPQ, OPQ and PPQ expose only a public `admitFromLobby`, and HPQ only a package-private one (:25). None has a public "adopt an existing party" admit.
- **Expeditions.**
  - `server.expeditions.Expedition` is a flat roster of up to 30, independent of parties.
  - The in-progress branch of `scripts/npc/2030013.js` (:83-87) re-enters a registered member through `eim.registerPlayer` while `canJoin == 1`.
  - `AgentExpeditionLobbyService` is a reusable phase machine. Its `execute` is gated on `mayOperate` (:73).
  - `CosmicPartyQuestGateway.runNpc` (:14), `CosmicPartyGateway.createAgentParty` (:29) and `publishAgentOnline` (:64) exist.
- **Boss damage and geometry (verified).**
  - `ServerMobDamageService.applyOrdinaryAttack(Monster, Character, BossAction.OrdinaryAttack)` (server/combat/ServerMobDamageService.java:39) is the real damage path. It applies HP/MP loss, broadcasts `damagePlayer`, and only then calls `AgentCombatDamageRuntime.reactToServerAttack` for a BotClient (:101-121).
  - `reactToServerAttack` (:30) plays only the knockback reaction. It applies no HP loss.
  - `ServerMobActionCatalog.forMob(mobId)` and `BossActionGeometry` (server/life/autonomy) exist.
  - `MoveLifeHandler:157` calls `ServerMobAutonomyService.recordAcceptedClientMovement` after controller validation. It is the only autonomy hook there.
  - A behaviour can force server authority through `BossActorBehavior.forceServerAuthority()`. `PoisonGolemActorBehavior` (:31) and `EpqPoisonedStoneBugBehavior` (:19) do this, and `ServerMobAutonomyService.register` (:602-612) honours it.
- **Citizens can be spawned (verified).**
  - Citizens are ACTIVE `agent_characters`. `CitizenGenerator` registers them through `CosmicAgentIdentityGateway.register`.
  - `living_citizen.flags` is VARCHAR(255). `living_persona_state` has persona_id VARCHAR(64) and `employed_by`.
  - `ResidentProvisioner.ensureCharacter` reads persona_state first (:35-38).
  - RosterService reaches it through `ResidentRuntime.ensureCharacter` (:159-166), which then runs `ResidentLooks.apply`. For a Persona with `Look.NONE`, `ResidentLooks.resolve` (:54-69) draws a random gender, skin, face and hair seeded by the persona id, and writes them to the `characters` row when the character is not loaded.
  - `ProgressionLedger.catchUp`/`tick` (population/ProgressionLedger.java:44-75) reshape ACTIVE citizens and top up their meso.
  - `Churn.step` fills up to TARGET_CITIZENS from `Population.activeCount` (:263), which counts ACTIVE citizens.
- **Party EXP.** Agent party members receive normal EXP (Monster.java:~814), so a full crew splits every kill with the human.
- **Starter parties.** `Party.createParty` refuses below lv10 (Party.java:336) unless `USE_PARTY_FOR_STARTERS`.
- **Two clients on one PC.** kaentake's `CreateMutexA_hook` (kaentake/src/system.cpp:30) makes two-client testing possible.
- **Database.** The last changeset id is `39-agent-clean-slate-reset-audit`, and the living tables use files 029/030. New work therefore uses id `40-...` with file `031-...`.

**Broken or drifting: must be fixed before the full manager UI**
1. **Op 4 spawns before checking.** It calls `AgentInteractionRuntime.spawnAgentForLeader` (PartyOperationHandler:84) before the size check (:96). Any online BotClient that is not a lobby waiter auto-accepts any inviter, including other players' companions. `MessengerHandler:104-110` also spawns and joins.
2. **Leadership can pass to a bot.**
   - Op 6 (:142) has no null check and can hand leadership to a bot.
   - `Client.removePartyPlayer` (Client.java:1043-1063) gives leadership on logout to the highest-level member on the map, which can be a bot.
   - Removing a leader bot disbands the party for everyone.
3. **Authority has no ownership check (verified).**
   - `AgentControlService.ensureCanControl` (:52-70) never checks ownership.
   - The global operator grant `roster::hasCompanions` makes anyone with one recruit an operator.
   - `mayOperate` (AgentAuthorityService:43-48) is the only gate on the whisper bridge. `CosmicAgentWhisperCommandBridge.handleWhisperToAgent` then resolves *any* headless Agent by id.
   - `mayOperate` is also the only gate on the chat ingress (AgentChatRouteCoordinator:51) and `AgentExpeditionLobbyService.execute` (:73).
   - Transfer authorization is hard-wired to allowed (AgentLeaderTransferCoordinator:165-193).
   - As a result, anyone with one recruit can spawn, pull or whisper-command any Resident, citizen or other player's companion. A chat "transfer" can also move a companion's runtime leader away from its roster owner.
4. **The roster drifts from the party.**
   - Expel, leave and disband leave roster rows ACTIVE while bots keep following.
   - `AgentPartyLifecycleService.joinAgentToLeaderParty` (:14) returns void, and `spawnCompanion` (RosterService:288) saves ACTIVE even when the join failed.
   - The stale entry written after `applyAccrued` (:304) can grant banked EXP twice.
   - `spawnCompanion` does check `hasActiveAgentCharacterId` (:295), but then it silently removes whatever runtime holds the character (a bench-live slice, a population session, a citizen standing beside you) and respawns it. The player sees a blink.
   - `BenchSessions` (:172) and `ResidentRuntime` (:320) call `loadOfflineAgent` with no guard at all.
5. **`freeSlots` (:113) is not a real slot authority.** Travellers, other humans, `@spawnbot` and SocialFabric all ignore it.
6. **Channel change strands companions.** The logout hook does not fire during the transition. `AgentInteractionRuntime.reloginAgent` (:112) is reachable only through a GM command path.
7. **Follow is not instance-aware, and it pops in (verified).** `AgentFollowMapSyncService.syncFollowMap` (:31-50) warps any following Agent whose map differs from its anchor to a ground point near the anchor. It has no party, travel or instance check, which causes two problems:
   - An unregistered companion lands in the non-instance duplicate of a PQ or boss map.
   - After every portal, companions appear beside the owner instead of at the portal.
8. **Contracts break a rotation bench.**
   - 13 of 15 contracts end after 4-8 h of party time, and all end on a 10-20 level gap.
   - Dorran walks out after 1 h on the bench.
   - `LEADER_OFFLINE_BENCH_MS` (LivingWorldConfig:68) is unused, although the docs say otherwise.
9. **Benched bots never go live by default.**
   - The default camp is the home town, and `BENCH_LIVE_MAX=4` (:79) is server-wide.
   - `harvest()` sweeps bought potions and quest items into the haul.
   - `BenchSessions` loads only on the living channel (`world.config().CHANNEL`, CHANNEL=1).
   - STALL is a markup formula, not a shop.
10. **Living timers share one thread.** Everything runs on one LOW_PRIORITY thread with synchronous DB loads, and `LivingStore` is uncached. There is no per-owner Agent cap and nothing has been measured for companions. The real ceiling is 4096 scheduler registrations.
11. **Leader-level chat verbs bypass the roster.**
    - "dismiss" leaves the bot online, in the party and unclaimed.
    - An untargeted "logout" + "yes" signs off all of the owner's bots.
    - "transfer" and "follow <other player>" re-point the runtime leader.
    - `beginEscort` (RosterService:556, called from QuestLedger:536) inserts roster rows that bypass `ROSTER_MAX` and slots.
12. **Character deletion leaks Residents.** `living_roster` has no FK and no cleanup on deletion (029:59; `Character.deleteCharFromDB` :2622). A deleted owner keeps its Residents forever.

**Corrections found while synthesizing (verified in source)**
- **Easy Balrog does not damage bots when a friend is present.**
  - `ServerMobAutonomyService` (:602-612, :719-746) starts a boss in NATIVE_CLIENT mode whenever a native-simulation human in the activating Agent's party is on the map. The server behaviour, and so bot damage, runs only in SERVER_STICKY mode.
  - Unless `forceServerAuthority()` is set, Easy Balrog therefore has the same "invincible bots" gap as Zakum once a friend is in the party.
  - Papulatus and Zakum have no behaviour at all (ServerMobBehaviorRegistry:34-45).
- **HPQ, and every PQ except KPQ, has no public adoption seam.** Each needs a NEW public "adopt this party" entry point (P6b).
- **The roster item has no script to hook.** Item 2430011 has spec/script `summonEventNpc` and spec npc 9010000, the Maple Administrator (wz/Item.wz/Consume/0243.img.xml:215-218). There is no script file, so the intercept must run in `ScriptedItemHandler` before `ItemScriptManager.runItemScript` (:44-55). 9010000 must never be used as the Crew window's face.
- **Friend merge is blocked twice.**
  - When the invitee is already in a party, stock op 4 answers the **inviter** with `partyStatusMessage(16)` (PartyOperationHandler:129, where `c` is the inviter).
  - `Party.joinParty` also refuses "already in one" (Party.java:393-396).
- **DISBAND does not lose its member list.** The earlier draft was wrong on this point; see the chokepoint bullet. The pre-mutation snapshot is still kept, but for CHANGE_LEADER's previous leader and so that each event is self-contained.
- **Expedition quota is logged before anyone is warped.**
  - `EventManager.startInstance` calls `exped.start()` before `eim.registerExpedition(exped)` (EventManager:416-417).
  - `start()` logs a daily attempt for every active member (Expedition:196-199, 250-256). `registerExpeditionTeam` then warps only members on the recruit map (EventInstanceManager:422-429). So `start()` cannot know who was warped.
  - Zakum sets `canJoin` to 1 and never resets it (ZakumBattle.js:98). An unlogged member could therefore enter later through 2030013.js without an attempt being logged.
  - `addMember` refuses once registration closes (:207-210).

**Unverified (a live probe or measurement is needed; see P0)**
- None of the 7 Agent PQ systems has been run live. The PRODUCTION admit path has never run at all.
- `reloginAgent` in the owner-follow flow.
- Whether a v83 client that is already in a party shows and accepts an invite.
- Whether the client still sends an invite (op 4) or an accept (op 3) when the party is 6/6.
- Binding a 243 item to a hotkey.
- A Custom.wz leaf override of String names.
- An imitated-NPC portrait in an NPC dialog. Speaker-byte variants (no portrait, the player's own avatar). A stock object NPC (a book or board) used as a portrait.
- Refreshing an open NPC window.
- The char-info "Party" button on a bot.
- The stock trade window with a companion.
- LivingTalk, chat verbs and contract clocks for a companion that is not on channel 1.
- Whether the 38 rendered Resident portraits have already reached friends' clients.
- How Easy Balrog looks and hits a human under forced server authority.
- Per-Agent heap and CPU for companion, bench-live, stall and citizen bots.

## Design

### Principles
1. **Native first.** Every surface through P9 is one the stock v83 client already draws: the party window, char info, the NPC dialog, a USE item, the trade window, whispers, chat bubbles and pink text.
2. **One authority, zero drift.**
   - One slot authority (`CompanionSlots`), one resolver (`CompanionDirectory`) and one serial executor (`CompanionExecutor`) own every companion mutation, including those from chat verbs.
   - Party events arrive asynchronously, after they commit.
   - An auditor proves that the roster, the party and the registry agree.
3. **Ownership stays in the living layer.**
   - `server.agents` stays owner-free, as AGENT_OWNER_REMOVAL_IMPLEMENTATION.md intends.
   - Per-agent routes in `server.agents` ask a small installed guard (`AgentClaimGuard`, like today's `OperatorGrant`). They never ask about owners directly.
4. **Priors now, learning from measurements.** Deterministic priors are used today. Measured yields, run records and arena-checked combat tables drive the choices as they accumulate.
5. **Never a wrong face, never a bot that cannot die, never a pop-in.**
   - Companions walk in from portals, both on recall and on ordinary follow.
   - A portrait appears only when it shows the companion's real face.
   - Boss support waits until bots are seen taking that boss's damage.

### Core concepts

| Concept | Definition |
|---|---|
| Companion | A bot the player owns through a `living_roster` row (owner character_id + persona_id). At runtime it is always a cosmic Agent. It is either a content Persona (a recruited Resident) or a hired hand with persona_id `hire:<characterId>`. |
| Roster cap | `ROSTER_MAX` counts every non-RELEASED row, whatever its state, including hires and escorts. The default of 10 means 5 active plus 5 on the bench. `HIRE_MAX_PER_OWNER` sits inside it. The value is decision 20. |
| Seat state | Stored in `living_roster.state` (VARCHAR(16), no type change). The states are listed below. The legal transitions live in one table (NEW `CompanionSeatState`) that the code and the tests share. |
| Slot / reservation | One of the 6 party positions. `CompanionSlots` counts party members plus HOLD, AWAY and GUEST reservations. TRAVELLING companions are already members. Every path that seats a bot in a party with a human must hold a reservation. |
| Own party | A party where the owner is the only human (`CompanionDirectory.isCompanionParty`). Any party with another human is shared, including one the owner leads. |
| Fair share | With H humans in a party, each human is guaranteed floor((6-H)/H) seats for their companions and guests. Leftover seats go first come, in usual-lineup order. Humans always come first. Through P8 the share is also a cap: seats a friend leaves unused stay theirs (the party window can show 4/6 while your share is used), so they can call their own at any time; lending them is P9 (decision 14). A refusal past your share is in character ("the other seats are your friend's"), and the stock "full" (17) is kept for a party at 6/6. |
| Citizen guest | A citizen that accepted a human's invite. It holds a GUEST seat charged to the inviting human (the host). When the host must give up a seat, guests step out first ("you've got a full crew, catch you later"). A pending hire offer stays open for 10 minutes and remains reachable from the hire list. |
| Make-room | When you invite a human into a full party, your last guest, or else your last usual-lineup companion, stands down when they accept. This needs P0 probe 12; otherwise it is a Crew-window action. |
| Leader cap | When a party leader expels another owner's companion, that owner's seats in this party are capped at one fewer than they were using (never more than their share less one, never below 0), so the emptied seat stays empty. It resets when the party disbands or that owner leaves it, which prevents expel/refill loops. |
| returnWhenRoom | A companion pushed out by a merge or make-room is benched with this flag. It is recalled automatically when its owner has room again, unless a leader cap blocks it. |
| Usual lineup | An ordered list of up to 5 companions (`living_roster.lineup_slot`). It is saved automatically the first time 2 or more companions are out, on logout, and by "Remember this lineup". It is restored on login. Merge, overflow and make-room follow its order. |
| Duty / camp | Duty is the bench assignment: TRAIN, FARM_MESO, FARM_ITEM, GATHER, SELL, STALL, REST, QUEST (optional, P5) or YOUR_CALL. Camp is the map where the duty happens. The default camp becomes a level-appropriate field from `FieldCatalog`. |
| Commitment | An in-memory lock that ties companions to one event instance or expedition. While it holds, bench, swap, contract walk-outs and reconcile are frozen. It is released when the instance ends or the member leaves it. |
| Cause token | A ThreadLocal tag set by companion code before it touches `Party`/`World`. It is copied into the party event so the sync ignores its own echoes. |
| Hired hand | A citizen recruited into the roster through a synthetic Persona that NEW `HiredHands` resolves. Its Look is taken from its own `characters` row. The citizen is flagged `hired` in `living_citizen.flags`, so population scheduling, `ProgressionLedger`, `Churn.activeCount` and PQ sweeps skip it. |
| Run record | A per-companion outcome of each PQ or boss run, stored in the NEW table `companion_performance`. |

**Seat states** (NEW values are TRAVELLING, HOLD and PARKED):
- **ACTIVE.** Loaded, in the owner's current party, following.
- **TRAVELLING.**
  - Loaded at its camp map on the owner's channel with `spawnStationaryAgentForLeaderAt`, which registers it **without follow**. It is already in the party, so the party row shows where it is. `eta_at` is persisted.
  - Follow sync skips it. When the ETA ends it is placed at the owner-map portal nearest the owner, walks into formation and only then switches to follow.
  - If the owner is inside an instance, it becomes HOLD at the entrance instead.
- **HOLD.** Loaded without follow, out of the party, standing by a PQ or boss entrance NPC (`hold_map_id` is persisted). Its seat is reserved, it refuses all invites and it rejoins when the run ends.
- **AWAY.** The existing errand (`delegate`): out of the party, not following, with the seat reserved and credited by the ledger, as today.
- **BENCHED.** Doing its duty, either live or in the ledger. It may carry returnWhenRoom.
- **PARKED.** The owner is offline and the lineup intent is kept (`parked_at`). After `LEADER_OFFLINE_BENCH_MS` it becomes BENCHED on its duty.
- **RELEASED.**

### Threading and party-event model
- **`CompanionExecutor`** (NEW, `server/living/roster/CompanionExecutor.java`).
  - A single serial thread named `companion-ops`, separate from the LOW_PRIORITY thread.
  - It runs every roster mutation, including those that come from chat verbs. It also runs every companion character load (`CosmicAgentOfflineLoader` or a spawn), bench-live starts and ends, channel follow and party reconciliation.
  - Each command reserves, then acts, then commits or rolls back. It has a per-command watchdog and never holds roster state while it waits on map or party locks.
  - Commands to running Agents go through `AgentMailboxRuntime`, never inline on the packet thread.
- **`PartyEvents`** (NEW, `net/server/world/PartyEvents.java`, no living imports).
  - `World.updateParty(int, …)` captures a small *pre-mutation* snapshot (member ids and leader id) before its switch.
  - DISBAND does not need the snapshot, because the Party object keeps its members after `disbandParty`. CHANGE_LEADER does need it, because it loses the previous leader, and the snapshot makes every event self-contained.
  - After the broadcast it publishes `PartyChangeEvent(partyId, op, targetId, actorId, before, after, cause)`.
  - `actorId` comes from a ThreadLocal set by `PartyOperationHandler` (ops 2, 5 and 6) and by `Client.removePartyPlayer`. `cause` comes from `CauseScope`.
- **Listener.** The NEW hook `LivingWorldHooks.partyChanged(event)` submits `CompanionPartySync.onPartyChanged` to the executor. It is never handled inline, so the handler cannot re-enter `World.updateParty` or invert locks.
- **Menus.** They render from the `CompanionView` cache, with no DB read per render. They submit commands and answer by re-opening, a whisper or pink text.

### Components

**Party, ownership and safety**

| Component | Kind | Location | Responsibility / key API |
|---|---|---|---|
| CompanionDirectory | NEW | server/living/roster/CompanionDirectory.java | Answers "who is this character to me?" for every surface. `Relation resolve(viewer,target)` / `resolveName(viewer,name)` returns OWN_COMPANION, OTHERS_COMPANION, RESIDENT, CITIZEN, CITIZEN_GUEST, HIRE_OFFER_PENDING, PLAIN_AGENT, HUMAN or UNKNOWN. It also provides `ownerOfCharacter(cid)` and `isCompanionParty(party)` (the only human is its owner). |
| CompanionSlots | NEW | server/living/roster/CompanionSlots.java | The only slot authority. API: `free(owner)`, `shareFor(owner, party)`, `reserve(owner, key, HOLD\|AWAY\|TRAVEL\|GUEST)`, `release`, `makeRoom(party, invitee)` (guests first, then usual-lineup order), `recordLeaderExpel(party, owner)` and `canAdmit(party, joiningHuman)`. Guests count against the host's share. `RosterService.freeSlots` delegates here. |
| CompanionPartyRouter | NEW | server/living/roster/CompanionPartyRouter.java, called from PartyOperationHandler | **Op 4** resolves the name *before* any spawn and checks slots:<br>• Your own companion becomes a call.<br>• Another player's companion or a Resident gets the stock deny (23).<br>• An unowned plain Agent gets not-found (19) for non-admins.<br>• A citizen decides for itself through `CitizenInviteDecision`. On accept it holds a GUEST seat charged to the inviter.<br>• A full party gets 17 unless make-room applies.<br>• If the invitee's party is a companion party, the invitee receives the stock invite instead of the inviter getting status 16. This depends on P0 probe 1.<br>• A partyless owner below lv10 inviting their own companion gets a party (decision 9).<br>**Op 3** into a full party stands down the inviter's last guest or companion. An owner who is in a companion party first leaves it under a cause token, then joins.<br>**Op 5** on a companion benches it.<br>**Op 2**: your crew leaves with you.<br>**Op 6** gets null and leader checks and refuses a bot target ("nah, you lead"). |
| CompanionPartySync | NEW | server/living/roster/CompanionPartySync.java | Reconciles the roster after every event, on the executor:<br>• Expel of a companion benches it and whispers its owner, applying a leader cap if the leader is someone else.<br>• When the owner leaves or is expelled, the crew re-forms around the owner.<br>• DISBAND re-forms each human's crew around its owner.<br>• A human joining triggers fair-share rebalancing: guests step out first, then overflow is benched with returnWhenRoom.<br>• Events with a cause token are applied to the cache only.<br>• A circuit breaker stops after 6 corrective ops per party per minute and logs `companion.drift`. A corrective op is one reconciled event, accept or make-room that changed something, however many companions it moved (a merge alone moves up to 7), so ordinary play never trips it and a loop does.<br>• While a member is committed to an instance, reconciliation is deferred: an event whose target is committed waits whole; any other is reconciled around the committed characters and finished when the instance ends.<br>• A human logging back on with companions to restore is rebalanced like a joiner; a host who logs off takes their guests out. |
| Party.joinParty seat gate | extend | net/server/world/Party.java (~333-400) | A BotClient joining a party that contains a human must hold a `CompanionSlots` reservation (companion or GUEST) or an exemption. The exemptions are the PQ-lobby DELAY_ACCEPT path in `decidePartyInvite` (which covers a human joining a citizen-led lobby party), bot-only parties, GM `!kpqtest` and expedition overflow parties. Anything else is refused and logged `companion.unseated_join`. |
| Leadership fixes | fix | Client.java:1043-1063; PartyOperationHandler op 6 | The successor is a human while any human remains. If no human is online, the offline owner stays leader. A bot never leads a party that contains a human. |
| Starter party exemption | fix | Party.createParty (:336); CompanionPartyRouter | Implements decision 9: below lv10, a party may be created when every other member will be the creator's own companion. It is refused again the moment another human would join, using the stock message 10. |
| MessengerHandler | fix | MessengerHandler.java:104-110 | A messenger invite never spawns or joins a party. It opens a messenger session only with an already-loaded companion the inviter owns. |
| Bot invite decisions | fix | AgentPartyQuestLobbyRuntime.decidePartyInvite; NEW server/living/population/CitizenInviteDecision.java | Owned, held and bench-live companions reject everyone but their owner. Citizens accept or deny on their own terms (level gap, busy, personality) using stock messages, and reserve a GUEST seat on accept. P1 uses a simple level-gap rule; P2a adds the guest seat; P4 adds personality. |
| Owner-scoped authority | fix | NEW server/agents/auth/AgentClaimGuard.java, installed from server/living/LivingWorld.java; AgentControlService.ensureCanControl; CosmicAgentWhisperCommandBridge; AgentChatRouteCoordinator; AgentLeaderTransferCoordinator | **The operator grant stays**, so owners keep map-chat verbs, whisper verbs and the expedition lobby. **Every per-agent route adds `mayControl(actor, agentCharId)`**, which is true for an admin, for the owner of the roster row, or for the runtime leader of a GM-spawned non-roster Agent. Those routes are:<br>• `ensureCanControl`;<br>• the whisper bridge's target resolver, where a non-controller's whisper is ignored like a stranger's;<br>• chat transfer, dismiss and "follow <name>";<br>• the ADOPT_CLAIMED expedition path, which checks claims instead of `mayOperate`.<br>Chat "transfer" and "follow <other player>" are refused for rostered companions. Runtime-leader moves happen only through `HiredHands` adoption and PQ-scoped delegation. **PQ-scoped delegation:** during a PQ adoption, the party leader may include or exclude any party companion for that run, and owners are whispered. `ClaimGuardBoundaryTest` allowlists every `spawnAgentForLeader` / `joinAgentToLeaderParty` / whisper / transfer call site (RosterService, HiredHands, PQ lobby delay-accept, GM tools). |
| CompanionChatVerbs | NEW | server/living/roster/CompanionChatVerbs.java; hooks in AgentChatRouteCoordinator (leader-level intents) and RosterService.beginEscort | An inventory of every leader-level intent that touches rostered companions. "dismiss" maps to `bench`. An untargeted "logout" + "yes" maps to `park` for companions, and leaves plain GM Agents as today. "park" maps to `park`. All run on the executor. `beginEscort` takes a `CompanionSlots` reservation and counts toward `ROSTER_MAX`, or it is refused with an in-character line. A boundary test lists every intent. |
| JoinOutcome and spawn fixes | fix | AgentPartyLifecycleService.joinAgentToLeaderParty (changed to return NEW `enum JoinOutcome {JOINED, ALREADY_MEMBER, PARTY_FULL, NO_PARTY, FAILED}`); RosterService.spawnCompanion; BenchSessions (:172); ResidentRuntime (:320) | ACTIVE is written only on JOINED or ALREADY_MEMBER. Otherwise the row goes back to BENCHED with a clear line (including "owner is below lv10" when decision 9 is off). The entry is re-read after `applyAccrued`. `spawnCompanion` *refuses* ("give me a moment") when the character holds a lease elsewhere, instead of silently removing that runtime. `BenchSessions` and `ResidentRuntime` check `AgentRuntimeRegistry.hasActiveAgentCharacterId` and the lease before `loadOfflineAgent`. |
| CompanionLeases + AgentAvailabilityGuard | NEW | server/living/roster/CompanionLeases.java; server/agents/runtime/AgentAvailabilityGuard.java (interface) | One materialization lease per character: COMPANION, BENCH_LIVE, BENCH_TOWN, COMMERCE, EXPEDITION, RESIDENT, POPULATION or PQ_BACKGROUND. `AgentKpqPopulationRuntime`, `AgentHpqPopulationRuntime`, `PresenceScheduler`, `SocialFabric` and `ResidentRuntime` skip owned, held or leased characters. |
| Follow guards | fix | AgentFollowMapSyncService#syncFollowMap (:31-50) via its `FollowMapSyncHooks` | **Instance guard:** if the anchor's event instance differs from the bot's, the bot holds at the entrance map (HOLD_AT_ENTRANCE) instead of warping. **Travel guard:** TRAVELLING, HOLD and AWAY entries are never warped. **Portal-true follow:** after the owner changes map, a following bot is placed at the arrival portal the owner used, not at the owner's current position. Bots are staggered 300-900 ms and walk toward the owner. Follow never warps across channels or during an owner's channel transition, and is never "fixed" with forceChangeMap. |
| CompanionBudget | NEW | server/living/roster/CompanionBudget.java (+ config keys) | Always on, independent of `AgentLoadSheddingRuntime`. Pools:<br>• COMPANION: 5 reserved per online human.<br>• BENCH_LIVE: per owner plus global.<br>• BENCH_TOWN: cheap.<br>• COMMERCE: FM stalls, budgeted separately.<br>• EXPEDITION: burst, split by fair share among owners.<br>• POPULATION: elastic; citizens are made virtual first.<br>Every companion load is checked (call, swap, bench-live, stall, hire, ally, channel follow), and a spawn-rate limit applies. Numbers come from P0 measurement against the decision 20 target. Hired hands add continuous load, and so does the Churn backfill they trigger; both are counted. |
| CompanionAuditor + ops endpoints | NEW | server/living/roster/CompanionAuditor.java; AdminControlPort (loopback) | Every 60 s it checks that roster state, party membership and the registered leader agree. It repairs toward the roster and logs `companion.drift` to `living_event_log`. Endpoints: `GET /runtime/companions/{ownerId}` and `GET /runtime/budget` (loaded Agents by kind, heap after GC, scheduler registrations, LOW_PRIORITY lag, executor queue depth). They are for operators and verification scripts only. |
| GrantCompanionCommand | NEW (GM6) | client/command/commands/gm6/GrantCompanionCommand.java | `!grantcompanion <personaId\|citizenName>` adds a roster row with no fixture rewrite. For tests and demos only. |

**Roster lifecycle**

| Component | Kind | Location | Responsibility / key API |
|---|---|---|---|
| RosterService | extend | server/living/roster/RosterService.java | Existing verbs stay the backend and run on the executor. They now return NEW `sealed RosterResult {Ok, Travelling(etaMs, fromMapId), PartyFull, QuotaFull, RosterFull, NotOwned, InEventInstance, BudgetExhausted, Busy, Failed(reason)}`. NEW `RosterVoice` turns results into persona or citizen lines, so the chat regexes in `ResidentDialogue` keep working. New operations:<br>• join-then-travel `call`;<br>• atomic `swap`: outgoing leaves and incoming joins under one cause token, so the party never exceeds 6 and never shows a hole;<br>• `hold` / `rejoinHolds`;<br>• `saveUsualLineup` / `callUsual`;<br>• `park` / `unpark`;<br>• a public `despawn` wrapper;<br>• a per-owner write-through cache.<br>Bench, swap and delegate are refused while committed. |
| Join-then-travel arrival | extend | RosterService.call/arrive; RecallTravel; AgentInteractionRuntime.spawnStationaryAgentForLeaderAt | The companion is loaded without follow at its camp on the owner's channel (any live bench slice ends first). It joins at once, so the stock "X has joined the party" appears and the row shows its map. It whispers the ETA. When the ETA ends, `arrive()` places it at the owner-map portal nearest the owner, it walks into formation, and only then switches to follow. The ETA is capped by `RECALL_MAX_ETA_MS` (decision 7). `eta_at` is persisted. |
| CompanionChannelFollow | NEW | server/living/roster/CompanionChannelFollow.java; NEW hook LivingWorldHooks.playerChangingChannel called from Client.changeChannel before the transition; LivingWorldHooks.playerLoggedIn | ACTIVE and TRAVELLING companions follow through `AgentInteractionRuntime.reloginAgent` (:112), staggered 1-3 s and arriving at the portal. The party row updates through `publishAgentOnline`. After a 30 s deadline the fallback removes the runtime on the old channel and loads the companion on the new one. BENCHED live companions stay on the living channel. |
| Logout / login / boot | extend | RosterService.onOwnerLogout/onOwnerLogin; NEW boot reconciliation; NEW instance-logout handoff | Follows decision 8. **Own party** (a companion party): companions log off with the owner and stay as offline members (PARKED). **Shared party**, even one the owner leads: companions leave at once and PARKED is roster-only. After `LEADER_OFFLINE_BENCH_MS` they move to bench duty, which extends `BenchSessions.benchGoverned` to cover PARKED-past-grace. **Inside a shared instance** (decision 8b): companions finish the run under PQ-scoped delegation to a remaining human (a runtime-leader move only; the roster owner is unchanged), then leave with the eim and park. **Boot:** ACTIVE/TRAVELLING/HOLD become PARKED, and PARKED past its deadline becomes BENCHED. |
| Roster deletion cleanup | fix | NEW hook from Character.deleteCharFromDB (:2622); LivingWorldHooks | Deleting an owner character releases its rows, frees its Residents (decision 18) and returns hired citizens to the population. |
| Contract policy + deferral | fix | ContractService (tick, NEW contractOutlook); LivingWorldConfig.ROSTER_CONTRACT_MODE (NEW) | Follows decision 3. Walk-outs always wait until a commitment ends ("after this run, I'm heading home"). Before a low-tolerance Persona is benched, a Yes/No warning appears. |
| CompanionSettingsApplier | NEW (P3b) | server/living/roster/CompanionSettingsApplier.java. Extends AgentPotionService (NEW `setThresholds(entry,hp,mp)`, replacing the global 0.7/0.5) and AgentLootEligibility (NEW `setPolicy(entry, ALL\|OWN_KILLS\|QUEST_ONLY\|NONE)` with NO_BOSS as a flag). Uses AgentFormationRuntime.setFormationState and AgentControlRuntime.toggleCallbacks. | Settings in `settings_json` are re-applied on every load, so they survive bench, relog and restart. |

**UI (stock NPC window)**

| Component | Kind | Location | Responsibility / key API |
|---|---|---|---|
| LivingTalk | extend | server/living/resident/LivingTalk.java | Adds:<br>• automatic "More..." paging past about 10 options;<br>• number input (`getNPCTalkNum`);<br>• stable option ids with **version stamps**, so a stale click re-renders with "things changed";<br>• back navigation;<br>• speaker modes per P0 probe 5 (no portrait, the player's own avatar, or a stock book/board object NPC).<br>`open()` disposes any active `NPCConversationManager` and honours the 500 ms click throttle. Every path ends in an NPC talk or `enableActions`. |
| CompanionView | NEW | server/living/roster/CompanionView.java | The read model for a card: name, job, level, HP%, MP%, EXP%, map, state, ETA, duty, camp, progress, banked EXP, haul, bond, contract outlook and recent runs. It is built from the live Character when the companion is loaded, otherwise from the roster cache. |
| CompanionManager | NEW | server/living/roster/CompanionManager.java | Typed facade over RosterService, AgentModeService, AgentFormationRuntime, AgentPartyGatherService, AgentMovementCommandRuntime (via AgentMailboxRuntime), AgentControlRuntime, equipment autoEquip and the settings applier. Operations: call, bench, swap, assign, assignAll, setCamp, hold, followMe, waitHere, followAll, waitAll, huntHere, gatherAll, rebuffAll, setFormation, toggleSupport, wearBestFromHaul, openTrade, showGear, release. |
| CompanionMenus | NEW | server/living/roster/ui/CompanionMenus.java | Screens:<br>• card, Crew window, bench card, swap picker;<br>• duty picker with projected rates, and a bulk "Everyone on the bench: …" duty;<br>• FARM_ITEM picker (`#i#t#` drops of the camp's mobs plus the owner's active quest needs, with the count from number input);<br>• camp picker, fighting style, supplies and loot;<br>• haul, with "Wear your best from the haul" under the existing equip rule;<br>• wages, release confirm, hire offer, "Adventurers looking for a crew";<br>• the eligibility picker (PQ bench call and expedition allies);<br>• "Make room for…" (only if probe 12 fails). |
| ResidentDialogue.onCharacterClicked | fix | ResidentDialogue.java:322 (uncommitted) | Replaces `isOwned` with `ownerOf == clicker`. Your own companion opens its card under the portrait policy. Another player's companion or a plain Agent shows the stock char info. A citizen with a pending hire offer opens the offer. |
| Portrait policy | rule | CompanionMenus/LivingTalk | A portrait is shown only when it shows the companion's real face. The options, in order:<br>1. A rendered Resident Npc.wz id 9970000+ that friends' clients already have (checked in P0 against the patcher manifest). Its outfit is as of the render.<br>2. The imitated-NPC route (`getPlayerNPC` on a spare id), if probe 4 passes.<br>3. A probe 5 speaker mode.<br>4. Otherwise, stock char info with the card reached through the Crew window.<br>It is never a borrowed townsperson and never 9010000. |
| Crew window speaker | rule | CompanionMenus | Opened from a companion, that companion speaks under the portrait policy. Opened from the Roster item, in this order:<br>1. The player's own avatar (probe 5b), which reads as "your notes".<br>2. The no-portrait mode (probe 5a).<br>3. A stock book or notice-board object NPC chosen in P0 (probe 5c).<br>4. The lead companion's imitated portrait (probe 4).<br>5. The lead companion's rendered Resident portrait.<br>If none of these exist, the item answers in pink text ("Party 5/5 · Bench 3 · double-click a companion to manage") and decision 6 closes the gap. |
| Adventurer's Roster item | NEW | ScriptedItemHandler (Java intercept before `getScriptedItemInfo`/`runItemScript`, :44-55); config ROSTER_BOOK_ITEM_ID=2430011 | Opens the Crew window anywhere, even with every companion benched. It is not consumed, and it is granted on the first recruit or hire. The client name and description come from a Custom.wz leaf override if probe 3 passes. Otherwise they come from a String.wz rename, built in the same MapleEdit pass as any portrait String.wz edits, because a portrait name without an Npc.wz image crashes the client. |

**Supply, bench, PQ, expeditions**

| Component | Kind | Location | Responsibility / key API |
|---|---|---|---|
| HiredHands | NEW | server/living/roster/HiredHands.java; fallback in LivingContent.persona (:89); skips in PresenceScheduler, Churn/`Population.activeCount`, ProgressionLedger.tick/catchUp and SocialFabric; `hire:` guards in ArcService, RumorScheduler, QuestLedger and ContentLint; ResidentRuntime.ensureCharacter/syncLooks | Builds a synthetic Persona for `hire:<cid>` whose **Look is read from the citizen's current `characters` row**. `ResidentRuntime.ensureCharacter` and `syncLooks` also skip `ResidentLooks` for `hire:` ids. It writes `living_persona_state` (character_id=cid, employed_by=owner), flags the citizen `hired`, and raises offers after party time.<br>`hire(owner, cid)`:<br>• goes through `RosterService.recruit`, so it counts toward `ROSTER_MAX`;<br>• if the citizen is already loaded (usually it is standing beside you as a guest), it is **adopted in place**: its population activity lease ends without a despawn and its runtime is re-registered under the owner through the leader-transfer path (under `AgentClaimGuard`), then it switches to follow;<br>• only an unloaded hire goes through `spawnCompanion`.<br>`onReleased` clears `employed_by` and the flag and returns the citizen to the population. |
| Hire list | NEW | CompanionMenus.hireList; reads `living_acquaintance` | "Adventurers looking for a crew": unpartied, level-matched citizens you have met, buddies first, each with an Accept/Decline terms window. This is what makes 5 active plus a bench plus boss allies reachable in one evening without GM help. |
| Resident allocation | NEW | server/living/roster/ResidentAllocation.java; config RESIDENT_CAP_PER_OWNER, RESIDENT_OWNER_INACTIVE_DAYS | Implements decision 18: a per-friend Resident cap and release after owner inactivity or character deletion. A released Resident goes home with its bond remembered. |
| Living bench | extend | BenchSessions, BenchLedger; NEW BenchDutyDispatcher, BenchYieldModel, BenchDutyChooser | The default camp is a `FieldCatalog` field. Every duty has a visible form:<br>• **TRAIN, FARM_MESO, FARM_ITEM:** `AgentFieldVisitLeaseRuntime` after `AgentInteractionRuntime.registerDirectorIdleAgent` (:67).<br>• **GATHER:** a field lease that picks up drops at the camp, or ledger-only by decision 12.<br>• **STALL** (and **SELL**, when visible): a real FM shop through `AgentFreeMarketStallCapability` / the COMMERCE controller under a COMMERCE lease.<br>• **REST:** TownLife presence on `AgentTownLifeVisitLeaseRuntime` at BACKGROUND_ABSTRACT.<br>• **QUEST** (optional): the QUESTING controller.<br>End rules depend on the duty. Harvest counts kill loot and meso only. Hand-back uses `AgentActivityBootstrap.requestPrimaryStop` (:54) and clears the checkpoint. Live slices stay on the living channel. A per-owner live quota applies under `CompanionBudget`. **Death or empty supplies during a live slice:** stock revive in the camp's return town, the slice ends, and the next slice starts only after a resupply shop visit (existing shop resupply). |
| BenchYieldModel / BenchDutyChooser | NEW | server/living/roster/BenchYieldModel.java, BenchDutyChooser.java | Yields are learned per (job branch, level band, camp) from live slices and shrunk toward the current 0.6-efficiency prior. "Your call" is a bandit over duty × camp, scored on EXP/h, meso/h and deaths, with FieldCatalog priors. The companion says what it picked. |
| Bench combat learning | extend | AgentCombatLearner (LEARN mode), arena check | Implements decision 13: out-of-sight bench grinders (no human on the map) run in LEARN mode. Their tables are adopted for companions near humans only after an arena check shows no regression. |
| Keep my crew fresh | NEW (per decision 15) | server/living/roster/CrewRotation.java | An opt-in per owner. A rested bench companion swaps in (atomic swap with travel) for one that is out of potions, dead outside an instance, or 10+ levels off the owner. The swap is announced in party chat. It never runs while the owner is committed. |
| CompanionPartyQuestService | NEW | server/living/roster/pq/CompanionPartyQuestService.java | `plan(leader, pqKey)` uses `AgentPartyQuestCatalog` plus event-script ranges and scores by level fit, job spread and `companion_performance`. `callFromBench(leader, pqKey, personaIds)` uses the eligibility picker and join-then-travel. `prepareEntry` moves extras to HOLD. `adopt` calls the **public PRODUCTION** `AgentKpqAdmissionService.admit(leader, leader, members, seed, now)` strictly **before** `em.startInstance`. `abort` calls `AgentKpqSessionRegistry.remove(session)` and then `AgentPartyQuestLifecycleRuntime.forceStop(agentId, reason, now)` for each Agent, which begins recovery and then recovers, and rejoins the holds. `onRunEnded` handles follow-owner, rejoin and records. Admit failure messages map to Lakelis lines. Behind the `COMPANION_PQ_ENABLED_KEYS` kill switch. |
| KPQ guidance in PRODUCTION | fix | kpq/AgentKpqHumanDialogueRuntime.promptDelayed (:131-134) | Decided: prompts fire in PRODUCTION for sessions adopted by `CompanionPartyQuestService` (`COMPANION_PQ_GUIDANCE`). They are phrased as companion party-chat lines ("I'll grab the coupons, you talk to Cloto"), never system text. |
| Lakelis branch | extend | scripts/npc/9020000.js | Adds suggested lineup / Let me choose / Call someone from my bench / Never mind. It then calls prepareEntry, adopt, startInstance, and abort on failure. Unchanged when the flag is off or there are no companions. |
| PQ recover and death | fix | AgentPartyQuestLifecycleRuntime.recover (:101), kpq/AgentKpqTerminationService, AgentDeathTickService / AgentDeathConfig | Owner-bound companions follow their owner again instead of doing TownLife at the lobby. PRODUCTION termination and return paths are checked for companions. Inside an instance there is no auto-respawn (decision 10). |
| PQ generalization | NEW (P6b) | NEW public adopt seams in each `Agent<Pq>AdmissionService`; per-PQ rules in CompanionPartyQuestService.plan; NPC branches | HPQ first, then the order from decision 21. Composition rules: LPQ needs a teleport mage, dark sight, ranged and physical; OPQ needs all branches; EPQ needs one per branch. Agent admission is stricter than the scripts (LPQ/OPQ/PPQ need exactly 6). Each PQ has its own kill switch. |
| CompanionExpeditionService | NEW | server/living/roster/expedition/CompanionExpeditionService.java; reuses AgentExpeditionLobbyService phases in a NEW non-destructive ADOPT_CLAIMED mode (claim-checked, not `mayOperate`-gated) | API: `registerParty(member, type)`, `callAllies(member, type, personaIds)`, `onMemberDied`, `onCleared`.<br>• **Any registered human member** can register their own party companions and call their own bench allies (up to `ROSTER_MAX − 5`).<br>• Remaining expedition seats (30) and EXPEDITION burst permits are split among owners by the fair-share rule, and the ally list shows "N seats left for you".<br>• Allies form bot-led overflow parties through `createAgentParty` and register through `runNpc`; registration is verified with `Expedition.contains`.<br>• Quota refusals are spoken in character. Re-entry goes through the 2030013.js in-progress branch. Loot is NO_BOSS by default. |
| Expedition fixes | fix | Expedition.java; EventInstanceManager.registerExpeditionTeam; scripts/npc/2030013.js | `registerExpeditionAttempt` logs only members standing on `getRecruitingMap()` at start, which are exactly the ones `registerExpeditionTeam` warps. The in-progress branch logs an attempt for anyone it re-registers who was not logged at start, which closes the Zakum `canJoin` bypass. NEW hooks at beginRegistration, start and dispose. A test asserts that no fixture rewrite happens when any member is a rostered companion, and `!balrogtest` refuses claimed characters. |
| Easy Balrog server authority | extend (P7) | EasyBalrog*Behavior (`forceServerAuthority()` override, conditional on registered companions in the encounter); config COMPANION_BOSS_SERVER_AUTHORITY | While an Easy Balrog encounter has registered companions, it runs SERVER_STICKY even with a friend present, the same mechanism Poison Golem uses. The existing Balrog behaviours then damage bots through `applyOrdinaryAttack`. P7 checks live that the human sees natural movement and takes correct hits. The fallback is the Balrog-only BossAttackMirror pulled into P7. |
| BossAttackMirror | NEW (P8) | server/life/autonomy/BossAttackMirror.java, hooked in MoveLifeHandler next to the existing :157 call | Runs when a human with NATIVE_MOB_SIMULATION controls a boss part in the *same event instance*. It decodes the reported attack action and skill, builds the attack from `ServerMobActionCatalog.forMob(mobId)`, and tests each Agent with `BossActionGeometry.contains`. It then calls **`ServerMobDamageService.applyOrdinaryAttack(monster, agent, attack)`** once per bot in the hit region per (mob oid, action, attack sequence). That call applies HP/MP loss, broadcasts `damagePlayer` and triggers the reaction itself. Mobs in SERVER_STICKY mode are skipped. Each Zakum part (body plus 8 arms) is handled independently. If no human controller remains, companions disengage and regroup. Validated against WZ ranges. |
| AgentZakumScenario + BossTargetRules | NEW | server/agents/capabilities/expedition/zakum/AgentZakumScenario.java (implements AgentExpeditionScenario); BossTargetRules in the combat targeting area | Arms before the body, and invulnerable parts are skipped. A deterministic prior that `AgentCombatLearner` may refine later. |
| CompanionRunRecorder | NEW | server/living/roster/CompanionRunRecorder.java | Writes a `companion_performance` row per companion per run: activity, outcome, duration, deaths, damage share and party size. |

### Data model
- **Changeset `40-companion-manager`** (file `db/tables/031-companion-manager.sql` + `031-companion-manager-down.sql`, registered in `changelog-tables.xml`):
  - `living_roster` ADD `lineup_slot TINYINT NULL`, `settings_json VARCHAR(1024) NULL`, `eta_at TIMESTAMP NULL`, `hold_map_id INT NULL`, `parked_at TIMESTAMP NULL`.
  - `state` gains the values TRAVELLING, HOLD and PARKED. `persona_id` may be `hire:<cid>`; both columns already fit.
  - Only RosterService, running on the executor, writes `state`. A boundary test enforces this. Every existing reader is audited: `BenchSessions.benchGoverned`, BenchLedger, ContractService clocks and ResidentRuntime.
  - NEW `companion_performance` (id BIGINT AI PK, owner_character_id, companion_character_id, persona_id VARCHAR(64), activity VARCHAR(16), outcome VARCHAR(12), duration_s INT, deaths SMALLINT, damage_share DECIMAL(5,4), party_size TINYINT, recorded_at; INDEX(companion_character_id, activity)).
  - No FK is added to `living_roster`. Deletion cleanup is a hook, so that release logic runs (Residents freed, citizens returned).
- **Changeset `41-companion-crew`** (file `db/tables/032-companion-crew.sql` + `032-companion-crew-down.sql`, P3b): NEW `living_crew` (character_id INT PK, formation VARCHAR(32) NULL, updated_at): the formation an owner set for their whole crew ("SPREAD 80 0"), from the Crew window or a formation order in chat, put back when a companion loads after a relog or a restart. A companion's own place stays in `settings_json`.
- **Changeset `42-companion-bench-learning`** (file `033-...`, P5): NEW `companion_yield` (job_branch, level_band, camp_map_id PK; samples, exp_per_hour, meso_per_hour, items_per_hour, deaths_per_hour, updated_at). This table also holds the bandit's arm statistics.
- **Changeset `44-pq-run-pace`** (file `db/tables/035-pq-run-pace.sql` + `035-pq-run-pace-down.sql`, the PQ clear-time estimate): NEW `pq_run_pace`, one row per companion PQ run that got in (id BIGINT AI PK; pq_key, session_id UNIQUE, leader_character_id indexed, party_size, humans, epoch, outcome; est_p10_s/est_p50_s/est_p90_s/est_records, the entry NPC's estimate at the Yes, NULL when none (an estimate that the lineup could not clear keeps est_records and leaves the quantiles NULL); actual_s, Yes to clear, NULL unless cleared; started_at_ms, cleared_at_ms; spans, members and drawn as JSON; recorded_at in UTC). PqPaceLedger writes it on companion-ops as a run ends and fits the clear-time model from the current epoch's newest rows at boot and after each write; the lineup reads only the fitted snapshot. Coverage of the estimate: `SELECT AVG(actual_s BETWEEN est_p10_s AND est_p90_s), AVG(ABS(actual_s - est_p50_s)) FROM pq_run_pace WHERE pq_key = 'kpq' AND epoch = ? AND outcome = 'cleared'`.
- **`living_persona_state`:** one row per hired hand, with `employed_by` cleared on release. No schema change.
- **`living_citizen.flags`:** a `hired` token. No schema change.
- **`living_event_log`** (existing, `LivingStore.event` :669) gets new kinds: `companion.call/bench/expel/hold/overflow/parked/drift/budget_denied/unseated_join/guest`, `hire.offer/hire.accept`, `resident.released`.
- **Content:** NEW `scripts/config/living-world/contracts/hired-hand.yaml`: durationHours 0, no outlevel or bench walk-out, and a fee and daily wage by level paid through the existing payWages. Amounts follow decision 19.
- **Config** (NEW keys in config.yaml `living_world` + LivingWorldConfig): ROSTER_CONTRACT_MODE, COMPANION_FAIR_SHARE, RECALL_MAX_ETA_MS, COMPANION_BUDGET_CEILING, BENCH_LIVE_PER_OWNER, COMMERCE_LIVE_MAX, EXPEDITION_BURST_MAX, COMPANION_SPAWN_RATE_PER_S, HIRE_OFFER_PARTY_MINUTES, HIRE_MAX_PER_OWNER (≤ ROSTER_MAX), ROSTER_BOOK_ITEM_ID, INSTANCE_DEATH_POLICY, INSTANCE_LOGOUT_POLICY, COMPANION_PQ_ENABLED_KEYS, COMPANION_PQ_GUIDANCE, COMPANION_BOSS_ENABLED_TYPES, COMPANION_BOSS_SERVER_AUTHORITY, COMPANION_EXP_SHARE, RESIDENT_CAP_PER_OWNER, RESIDENT_OWNER_INACTIVE_DAYS, AUTO_ROTATE_MODE, BENCH_COMBAT_LEARN, COMPANION_BUDDY_PROJECTION.
  - The existing `LEADER_OFFLINE_BENCH_MS` (:68) finally gets used.
  - `BENCH_LIVE_MAX` becomes the global bench-live pool.
  - `ROSTER_MAX` (:71) is set by decision 20.
- **In memory, rebuilt or reset at boot:** leader caps (reset on disband), commitments, returnWhenRoom, guest seats, cause tokens, pending hire offers and party snapshots.
- **Client data:** the 2430011 name and description (Custom.wz leaf override or String.wz). No Item.wz ship. Npc.wz ships only as Resident portrait batches you approve (decision 6). A portrait shows the right face, and its outfit is as of its render.

### Protocol
None through P9. Everything uses existing opcodes. The optional P10 adds Recv 0x117 / Send 0x17A with a `HELLO(version)` capability handshake, so the server sends the custom packet only to clients that sent HELLO. It also adds a `PacketRateLimiter` family classification.

## Player experience

- **Companion card.**
  - Double-click Hadrik. The stock NPC window opens with his portrait (portrait policy) and a header: "Hadrik, Lv27 Hunter · HP 812/900 · 38 Red Potions".
  - Options: Stick with me / Wait here / Hunt here while I'm busy / Take a break at camp… / What you do at camp… / Camp somewhere else… / Fighting style… / Supplies & loot… / Your haul… / Your wages… / Let me see your gear (re-sends the stock char info) / Let's trade / The whole crew… / We should part ways.
  - Each click either confirms in character with a chat bubble or whisper, or opens the next page with Back.
  - A friend's companion still opens stock char info.
- **Crew window.**
  - It opens from the card or the Adventurer's Roster.
  - A bold "Party 5/5" block lists name, Lv, job, where and HP%. TRAVELLING rows show "on the way (0:40)", and guests are marked "(guest)".
  - The Bench block lists name, Lv, job, duty @ camp, progress ("143/200 Stolen Fence"), banked EXP% and haul. The block title shows "(3 of 5 bench seats)" so the roster cap is visible.
  - Actions below the blocks: Call my usual lineup / Remember this lineup / Everyone: follow me / Everyone: wait here / Everyone: gather up / Everyone: rebuff / Formation / Everyone on the bench… (bulk duty) / Keep my crew fresh (on/off) / Adventurers looking for a crew….
  - Long lists page with "More...".
- **Rotate.**
  - From a bench card, choose "Swap in for…", pick the outgoing companion, then Yes.
  - The outgoing one says a line and walks to a portal. At the same moment the stock "Mira has joined the party" appears, her row shows Ant Tunnel, and she whispers "omw ~1m".
  - She stays at her camp until the ETA ends, then walks in through your map's portal.
  - The native alternative is Expel plus Invite by name.
- **Party window semantics.**
  - Expel your companion and it goes to camp.
  - Leave and your crew comes with you.
  - Change leader to a bot and it refuses with a whisper.
  - Invite a friend at 6/6 and your last guest, or else your last companion, steps out when they accept. This relies on probe 12; otherwise use "Make room for…" in the Crew window first.
  - Invite someone else's companion and you get the stock "has denied".
- **Following.** When you take a portal, your companions come through the same portal a moment later and walk to you. They never materialize beside you.
- **Duty and camp.** Rates are shown per option ("~1.2% EXP/h", "~4.1k meso/h after upkeep"). FARM_ITEM shows item icons and a count box. "Your call" lets the companion choose and tell you why.
- **Hiring.**
  - Party with a citizen through char info → Party. They accept or deny like a player and take a guest seat.
  - After about 10 minutes together they whisper an offer. Double-clicking them opens an Accept/Decline window with fee and wage.
  - On accept they simply stay: same face, no blink.
  - You can also pick someone from "Adventurers looking for a crew" in the Crew window.
- **KPQ at Lakelis.**
  - She says "Only four of you may go in. Take Hadrik, Casse and Vex?" with Yes / Let me choose… / Call someone from my bench… / Never mind.
  - The others say "we'll wait here" and stand beside her, then rejoin after the run.
  - Inside, your companions tell you in party chat what they are doing.
- **Expedition at Adobis.**
  - Every friend registered in the expedition sees two new options: "My party will register too" and "Call allies from my bench…".
  - The ally list shows level, today's entries, "N seats left for you" and an ETA against the stock clock.
- **Feedback.** Whispers, bubbles, pink text and stock popups only. Nothing uses a non-native overlay.

| Feature | Surface | Client change | Phase |
|---|---|---|---|
| Invite = call (own), deny others', no bot leaders, starter party | Stock party window / char-info Party button | none | P1 |
| Crew window v0 (list + Call / Bench / Swap) | Double-click own companion; Roster item if its data has shipped | none (item: custom-wz or string-wz) | P1.5 |
| Expel = bench, leave keeps crew, make-room, fair share, citizen guests | Stock party window | none | P2a |
| Walk-in arrivals, portal-true follow, channel follow, offline members | The world and the party window | none | P2b |
| Companion card, full Crew window, crew-wide orders, trade, wear best | Double-click own companion → stock NPC window; stock trade window | none | P3 |
| Swap / bench card / duty / camp / FARM_ITEM count | Stock NPC window (LivingTalk + number input) | none | P3 |
| Fighting style, potion %, loot policy | Stock NPC window | none | P3b |
| Adventurer's Roster opener | USE item 2430011 renamed | custom-wz (leaf override) or string-wz | P1.5/P3 |
| Hire a citizen (offer + hire list) | Char-info Party button, whisper, NPC Accept/Decline, Crew window page | none | P4 |
| Visible bench (grinding, gathering, real FM stalls, town life) | The world itself | none | P5 |
| Keep my crew fresh (opt-in rotation) | Crew window toggle, party chat | none | P5 |
| KPQ lineup + bench call + wait outside | Lakelis NPC dialog (9020000.js) | none | P6 |
| HPQ and other PQs | Each PQ NPC dialog | none | P6b |
| Expedition: party + bench allies (>5 bots), several friends | Adobis NPC dialog (2030013.js) + stock clock | none | P7 |
| Bots take Easy Balrog hits | The fight itself | none | P7 |
| Bots take Zakum hits | The fight itself | none | P8 |
| Party Search fills from bench | Stock Party Search window | none | P9 |
| Buddy-list projection (if probe 8 passes) | Stock buddy list groups + Where | none | P9 |
| Native Crew window (optional) | kaentake CUIWnd + Custom.wz sprites | dll+custom-wz | P10 |

## Flows

**1. Rotate a bench bot in and out**
1. You have 5 companions out. Double-click Tobi, choose "The whole crew…" (or double-click the Adventurer's Roster), then click Mira on the bench.
2. Click "Swap in for…", pick Tobi, and click Yes. The option carries a version stamp; if Tobi's state changed meanwhile, the window re-renders.
3. `CompanionManager.swap` runs on the executor.
   - It checks that both companions are yours and not committed, and takes a COMPANION budget permit.
   - It ends Mira's live bench slice.
   - It checks her lease and runtime, refusing if she is leased elsewhere.
   - It loads her *without follow* at her camp on your channel with `spawnStationaryAgentForLeaderAt`.
4. Under one cause token, Tobi leaves the party, says "alright, i'll keep busy", walks to the nearest portal and despawns into his duty. Mira joins through `joinAgentToLeaderParty`.
   - If the outcome is not JOINED, everything rolls back and Tobi stays.
   - Mira's row shows Ant Tunnel, `eta_at` is persisted, and she whispers her ETA. Follow sync ignores her while she is TRAVELLING, even if you change maps.
5. When the ETA ends, `arrive()` places her at the portal nearest you on your current map. She walks into formation and only then starts following. Her settings are re-applied, and she whispers a return report ("+14% EXP, 3,120 meso").
6. **Refusals.**
   - Inside a PQ or boss run you get "after the run".
   - Past the budget you get "it's crowded out here, I'll come when I can".
   - If you are in an instance when she arrives, she waits at the entrance (HOLD).

**2. Bench duty**
1. Open a card or bench row and choose "What you do at camp…". The options show projected rates from `BenchYieldModel`.
2. Pick FARM_ITEM, then Stolen Fence from the icon list, then 200 in the number box. Optionally choose "Camp somewhere else…" (the FieldCatalog top 5, or Right here) or "Your call".
3. `RosterService.assign` runs. For an ACTIVE companion it runs `delegate` instead, which keeps the seat as AWAY.
4. If the owner's live quota and the budget allow, `BenchDutyDispatcher` loads the companion on the living channel at its camp.
   - Field duties (TRAIN, FARM_*, GATHER) run under a BENCH_LIVE lease. The companion is registered as director-idle, and a field lease starts with the duty's end rule.
   - STALL opens a real FM shop under a COMMERCE lease.
   - REST becomes TownLife presence at BACKGROUND_ABSTRACT.
   - Otherwise `BenchLedger` credits it every 10 minutes (6 h per tick, 16 h/day farm cap, upkeep).
5. Live slices update `companion_yield`. The card shows "143/200 (live)" or "(out of sight)".
6. When you call it back, the haul (kill loot and meso only) goes to Your haul…, where you can hand it over, sell it, bank it, or have the companion wear its best gear from it.

**3. PQ with companions (KPQ)**
1. You are lv21-30 with 5 companions in Kerning City (103000000). The follow guards keep everyone on normal maps.
2. Double-click Lakelis and choose "I want to participate". 9020000.js calls `CompanionPartyQuestService.plan(leader, "kpq")`.
   - Eligible means: on 103000000, lv21-30, same channel, free Use/ETC.
   - Ineligible companions are listed with Lakelis's reason ("Dorran is too seasoned for this (Lv32)", "Casse's bags are full").
3. Lakelis proposes the top 3-4 by prior: level fit, job spread, and clear rate once there are 3 or more records.
   - If too few fit, she offers "Call someone from my bench…". The eligibility picker shows level-fit bench companions with ETAs, and they join-then-travel to Kerning City.
   - Lakelis says "I'll wait for them". The proposal re-renders when they arrive.
4. Click Yes or Let me choose…. `prepareEntry` moves the extras to HOLD. They leave the party under a cause token, walk beside Lakelis, say "we'll wait here", keep their seats reserved, and refuse invites.
5. `adopt` calls the public PRODUCTION `AgentKpqAdmissionService.admit(leader, leader, members, seed, now)` while everyone is still on 103000000 and the party is exactly the chosen members. If it fails, the admit message becomes a Lakelis line and the holds rejoin.
6. The script's normal `em.startInstance` warps the party. If that fails (for example, another party is inside), `abort` removes the session and calls `forceStop` for each Agent, which begins recovery and then recovers. No engagement is left behind (`AgentPartyQuestEngagementRegistry.forMember(agent) == null`). The holds rejoin, and Lakelis explains.
7. Commitments freeze the members. The coordinator binds the stage-1 instance during PREPARING. Companions talk to Cloto and take the coupon, rope, platform and barrel roles, and they tell you in party chat what they are doing (PRODUCTION guidance).
8. **Death:** see flow 8.
9. On clear, fail or exit, `recover` sends owner-bound companions back to following their owner.
   - Holds rejoin in usual order; since they are on the same map, they just walk over.
   - Deferred walk-outs execute, and `companion_performance` rows are written.
10. **Two owners.** If Ben leads, his lineup choice covers every party companion under PQ-scoped authority. Ann is whispered "Ben asked me to wait outside".
11. **An owner logs out inside the run** (decision 8b). If another human remains, that owner's companions finish the run under delegation to them, then leave and park. The run is not ended by several members dropping at once.

**4. Boss expedition with more than 5 bots (Easy Balrog in P7, Zakum in P8)**
1. **Prerequisites (Zakum).**
   - You are lv50+, have done the Zakum prequests (100200/100201) and hold an Eye of Fire.
   - You have 5 lv50+ party companions and up to `ROSTER_MAX − 5` lv50+ bench companions (5 by default), from the hire list or from `!grantcompanion` for demos.
   - Companion prequests follow decision 11, and daily entries follow stock rules.
   - `COMPANION_BOSS_ENABLED_TYPES` includes zakum only after P8 passes.
2. Walk to the Door to Zakum and create the expedition at Adobis natively. The 5-minute clock starts.
3. Choose "My party will register too". Each party companion walks to Adobis, `runNpc(agent, 2030013, …)` runs, and registration is verified with `Expedition.contains`. A quota refusal is spoken ("already went today").
4. Choose "Call allies from my bench…" and pick names. Each shows its ETA against the remaining time, and the list shows "N seats left for you".
   - The allies load at their camps under EXPEDITION leases and fair-share burst permits, travel, and arrive at the recruit map at staggered times.
   - They form a bot-led overflow party (6 or fewer, highest level leads, no human inside) and register the same way.
5. **A second friend.** Ben talks to Adobis, registers himself natively, and sees the same two options for *his* companions and allies. Seats and burst permits are split between Ann and Ben by fair share.
6. Press Start as usual. `registerExpeditionTeam` warps members standing on the recruit map, and the fixed `registerExpeditionAttempt` logs daily entries only for them.
   - Late allies say "couldn't make it" and return to duty, with their entry not burned.
   - If one enters later through the in-progress branch, that entry is logged then.
   - Commitments apply to all.
7. **Fight.**
   - *Easy Balrog (P7):* with companions registered, the encounter runs under server authority. The existing Balrog behaviours hit bots through `applyOrdinaryAttack`, so bots lose HP, heal, rebuff and can die.
   - *Zakum (P8):* `AgentZakumScenario` hits the arms first and the body only after all arms die. `BossAttackMirror` turns each arm or body attack reported by the human controller into `applyOrdinaryAttack` hits on bots in range.
   - If every human is dead or gone, bots disengage and regroup at the entrance.
8. **Death:** see flow 8, the expedition branch.
9. On clear:
   - Loot follows NO_BOSS (boss drops are left to humans), and drop credit follows stock droptype rules, which favour the humans' party.
   - Allies say "good fight" and return to their duties. Party companions follow their owners out.
   - Everyone gets a `companion_performance` row.
10. **Easy Balrog specifics.** The minimum is 3. The Balrog NPC's re-entry throws, so dead allies wait at the exit.

**5. Two friends in one party**
1. Ann has 5 companions out and Ben has 2. Ben double-clicks Ann → char info → Party, or types her name. Ann leads a companion-only party, so stock op 4 would answer Ben with status 16. The router instead sends Ann the stock invite. This depends on P0 probe 1.
2. Ann accepts (op 3). Under a cause token she leaves her companion party; the DISBAND echo is suppressed, and her companions follow her without a party for a moment. Then she joins Ben's party.
3. **Fair share** with 2 humans gives 2 seats each. Ben keeps his 2. Ann's first 2 in usual order join, staggered. Her other 3 say "party's full, we'll train at camp" and bench with returnWhenRoom.
4. **If probe 1 fails.** Ann sees the stock "already in a party". Clicking Leave on her own companion party gives her companions a 60 s loose-follow grace window instead of re-forming at once, so she can accept Ben's re-invite.
5. **Make-room.** If instead Ann is at 6/6 and invites Ben, the invite goes out (if probe 12 fails, she first uses "Make room for…"). On Ben's accept, her last guest leaves, or else her last companion says "I'll step out for your friend". Then fair share rebalances.
6. **Leader cap.** Ben, as leader, expels Ann's Vex. Vex benches and Ann gets pink text. Ann's cap for this party drops, and Vex is not auto-returned until the party disbands.
7. **Ownership.**
   - Each owner opens only their own cards.
   - Double-clicking the other's companion shows stock char info. Inviting it is denied, and whispering it orders is ignored.
   - Leadership passes only to humans; if Ben logs out, Ann leads.
8. **Ann leaves.** Her companions leave with her and re-form her party. Her returnWhenRoom companions are recalled with ETAs.
9. **Ann logs out.** Her companions leave Ben's party with a farewell, and Ben's party is unaffected. If Ann had been leading, Ben becomes leader and gets the seats back at once.

**6. Owner changes channel**
1. You pick channel 2. `Client.changeChannel` calls the NEW `LivingWorldHooks.playerChangingChannel(owner, 2)` before the transition, and `CompanionChannelFollow` marks your ACTIVE and TRAVELLING companions. Follow sync stops warping.
2. You log into ch2 and `playerLoggedIn` fires. Staggered 1-3 s, each companion goes through `reloginAgent(agentId, ownerId, world, 2)`. They briefly show offline in the party window, like friends changing channel, then appear on Ch.2 and walk in from the nearest portal.
3. **Fallback.** Any companion that has not arrived within 30 s has its ch1 runtime removed and is loaded on ch2 at the nearest portal.
4. BENCHED live companions stay on the living channel (ch1), and ledger duty is unaffected.
5. **Cash Shop.** Companions idle in place. If you stay longer than `LEADER_OFFLINE_BENCH_MS`, they follow the logout path.
6. Changing channel is blocked inside instances, so HOLD and PQ runs cannot be split.
7. **P0 probe 11** checks that chat verbs, LivingTalk and contract clocks work for companions on channel 2.

**7. Owner logs out** (the default of decision 8)
1. You quit. `playerLoggedOut` calls `RosterService.onOwnerLogout` on the executor. Your usual lineup is saved from the current party order, and one companion says "gn!" in party chat.
2. **In your own party** (you are its only human). Companions are saved and their runtimes removed. They stay as native *offline* party members (state PARKED, `parked_at` persisted). Leadership never passes to a bot.
3. **In any party with another human,** even one you lead. Your companions say bye and leave at once, so the friend gets the seats back. Leadership passes to the friend, and PARKED is roster-only.
4. **Inside an instance.** If another human remains, your companions finish the run under PQ-scoped delegation to that human, then leave with the eim and park (decision 8b). If no other human remains, they leave with you under stock eim rules and their commitments are released.
5. After 20 min (`LEADER_OFFLINE_BENCH_MS`), PARKED becomes BENCHED on each companion's duty and the ledger accrues, so they "kept training". They leave your offline party at that point.
6. **Login.**
   - *Within 20 min:* PARKED companions log back in, staggered 2-8 s, walking in from the nearest portal. `publishAgentOnline` puts them back into the persisted party, or the party is re-formed.
   - *Later:* the usual lineup is called with travel ETAs (join-then-travel).
   - Either way, one whisper summarizes what the bench earned.
7. **Restart.** Boot reconciliation turns ACTIVE, TRAVELLING and HOLD into PARKED, then applies the same login path. The auditor confirms no drift.

**8. Death in an instance** (the default of decision 10)
- **PQ.**
  - An owner-bound companion does not auto-respawn inside an event instance. Today's respawn changes map, which fires eim leftParty and can end a 3-member run.
  - It stays down until a Cleric companion or a human resurrects it, or until the run ends. The eim then moves everyone out, and the companion revives at the exit map and rejoins its owner.
- **Expedition (Zakum/HT/PB).**
  - It revives in town (El Nath), whispers "i'm down, heading back", travels (ETA), walks to Adobis and runs `runNpc(agent, 2030013)`.
  - The script's in-progress branch calls `eim.registerPlayer` while `canJoin == 1` and the bot is still `Expedition.contains`. It logs an attempt if none was logged at start.
  - For Balrog, where re-entry throws, it waits at the exit.
- **Owner dies.** Stock rules apply. If no human controller remains for a mirrored boss, companions disengage so they are never invincible attackers.
- **Outside instances.** Unchanged.

**9. From zero to a crew**
1. **First companion.** A new friend at lv8 double-clicks a Resident and picks "Would you travel with me?" (ResidentDialogue:354-356). `ContractService.askToJoin` shows the terms, and the friend accepts. `ResidentAllocation` checks the per-friend Resident cap (decision 18).
2. `RosterService.recruit` writes the row and calls the Resident. With decision 9 the lv8 owner gets a party with their own companion, and the stock "joined the party" appears. The Resident walks in from a portal.
3. The Adventurer's Roster is granted with the stock item-gain message. One whisper says: "double-click me or the book to manage the crew".
4. **Hiring instead.** The friend parties a citizen through char info → Party. The citizen takes a guest seat. After about 10 minutes it offers to stay, the friend accepts, and HiredHands adopts it in place.
5. The first time 2 or more companions are out, the usual lineup is saved automatically.
6. Live check: P1.5 (Resident path) and P4 (hire path).

**10. Parting ways and contract end**
1. Card → "We should part ways" → a Yes/No confirm showing bond and haul. Any uncollected haul is handed over first.
2. `ContractService.dismiss` runs on the executor. The companion says a farewell, leaves the party under a cause token, walks to a portal and despawns. The row becomes RELEASED.
3. **A Resident** returns to its home routine. It becomes recruitable by any friend after its `cooldownHoursAfterLeave`, subject to decision 18.
4. **A hired hand:** `HiredHands.onReleased` clears `employed_by` and the `hired` flag, the citizen returns to the population, and Churn counts it again.
5. **A contract end,** if decision 3 keeps authored terms, shows a warning first, is deferred until any commitment ends, and then takes steps 2-4.
6. **A deleted owner character, or owner inactivity past the decision 18 limit,** releases all of that owner's rows the same way.
7. Live check: P3 (release) and P4 (hire release).

**11. Party Search fill (P9).** Starting a stock Party Search while you have open seats offers level- and job-matched bench companions first (`CompanionManager.fillFromBench`). They join and travel like a call. Humans who answer the search still come first through make-room.

**12. Bench-live death or empty supplies.** A companion that dies during a live slice revives by stock rules in its camp's return town, and the slice ends. The ledger resumes, and the card shows "resting after a rough hunt". A companion out of potions ends its slice, runs the existing shop-resupply visit, and only then starts the next slice.

## Phases

Rough sizes: S is about 1-3 days, M about 1-2 weeks, L about 2-4 weeks and XL more than 4 weeks of focused agent-assisted work. Live-verification sessions are extra.

Every live phase uses the `maple-client` skill (cua) and two clients on one PC (kaentake `CreateMutexA_hook`). The zero-drift check (`/runtime/companions` plus `companion.drift` events) is an exit gate from P2a on. Every phase compares the full test suite with the recorded failing baseline.

Order: P0 → P1 → P1.5 → P2a → (P2b ∥ P3) → P3b / P4 → P5; P6 after P2b in parallel with P3-P5; P6b after P6; P7 after P2b + P4 + P6's death policy; P8 after P7.

**P0: Checkpoint, probes and measurement**

> **Done 2026-09-27. Results are in [BOT_MANAGEMENT_P0_RESULTS.md](BOT_MANAGEMENT_P0_RESULTS.md).** What changes downstream:
> - **Merge (probe 1).** A partied client sends op 3 on accept, so the merge works.
> - **Make-room (probe 12).** At 6/6 the client refuses to invite locally. Make-room is only the Crew window's "Make room for…", or the friend invites the owner into their party.
> - **Roster item (probe 2).** It opens by double-click in the inventory, not from a hotkey.
> - **Crew window speaker (probe 5).** It is speaker 2 (your own avatar) or the Bulletin Board 9040011. Never send speaker bit 0x04: it crashes the client.
> - **Imitated portraits (probe 4).** They need a per-viewer Player NPC spawn before the look packet, which crashes the client otherwise.
> - **No in-place refresh (probe 6).** A second NPC_TALK while a window is open disconnects the client.
> - **Trade (probe 13)** and **two clients on one PC (probe 10)** work.
> - **Budget v1.** 5 companions per human, 2 live bench per owner and 8 server-wide, a 12-bot expedition burst, and a 300-Agent ceiling until the scheduler is re-measured after the resupply-loop fix.
> - **Deferred probes:** 3 to P1.5/P3, 8 to P9, 9 to P7, 11 to P1.
- **Goal:** Freeze a known baseline, answer the client unknowns that decide which surfaces are possible, and measure Agent cost against the decision 20 target before choosing any cap.
- **Deliverables:**
  - (a) *Only with the user's explicit approval (decision 1):* commit the uncommitted living-world UI and the kaentake sources to branches, record the deployed Kaentake.dll hash against build/Release, and do no DLL rebuild or deploy. Record the known failing tests.
  - (b) Characterization tests of today's behaviour: spawn before the size check, expel leaving ACTIVE, CC stranding, the stale-entry double grant, the silent runtime removal in `spawnCompanion`, and the follow pop-in after a portal.
  - (c) Loopback `GET /runtime/budget` and a read-only `GET /runtime/companions`.
  - (d) Measurement on launch.bat `-Xmx2048m`: MB and CPU per Agent kind at three load points sized from decision 20 (friends online × roster size). The kinds are companion follow/combat, bench-live grind, FM stall, TownLife abstract and citizen. Also LOW_PRIORITY lag, scheduler registrations and tick p95.
  - (e) Read-only data checks:
    - friends' current character levels and jobs, from a `characters` table query, for decision 21;
    - which Npc.wz/String.wz portrait edits friends' clients already have, from the patcher manifest, for the portrait policy and decision 6.
  - (f) Live probes, each with a screenshot:
    1. Does a v83 client already in a party show a party invite and send op 3 on accept?
    2. Does a 243 item on a hotkey send SCRIPTED_ITEM?
    3. Does a Custom.wz leaf override change String/Consume.img/2430011?
    4. Does `getPlayerNPC` on a spare id render a companion's look as an NPC dialog portrait?
    5. Speaker modes:
       - (a) Does an NPC_TALK speaker-byte no-portrait mode work?
       - (b) Does a player-avatar speaker mode work?
       - (c) Does a stock object NPC whose sprite is a book or notice board (not a person) work as the portrait? Pick the id here.
    6. Does re-sending NPC talk while a window is open re-render it in place?
    7. Does the char-info Party button send op 4 for a bot or citizen?
    8. Does the buddy-list Party button send op 4 on an offline buddy?
    9. Does a party-registered Papulatus instance run with companions?
    10. Do two clients run on one PC?
    11. Do chat verbs, LivingTalk and contract clocks work for a companion on ch2 after a manual CC?
    12. With a 6/6 party, do Invite (party window) and the char-info Party button still send op 4? Does an invitee's accept into a now-full party still send op 3?
    13. Does the stock trade window open and complete with a companion?
- **Size:** S-M. **Depends on:** none.
- **Verification:** Characterization tests pass against current behaviour. Each load point is held for 30 min with `/runtime/budget` sampled every 30 s. Video: "before" clips of CC stranding, an expelled bot still following, and a follow pop-in.
- **Exit:**
  - All 13 probes have a recorded yes or no.
  - Budget defaults are written with at least 30% heap headroom, citing the measurements.
  - The level/job table and the portrait manifest state exist.
  - The baseline list exists.

**P1: Safety and party-window correctness (ships on its own)**
- **Goal:** Nobody can steal, duplicate, strand or command someone else's companion, and the party window cannot be driven into a broken state.
- **Deliverables:**
  - `CompanionDirectory`.
  - Op 4 resolves before any spawn and checks slots before any spawn.
    - Your own benched companion becomes a call through the existing `RosterService.call`.
    - Others' companions and Residents get stock deny 23; unowned Agents get 19 for non-admins.
  - Op 6 guards and bot-leader refusal. `Client.removePartyPlayer` picks a human successor. The `MessengerHandler` fix. The starter-party exemption, if decision 9 is (a).
  - `decidePartyInvite`: companions and bench-live bots reject non-owners, and citizens get a simple rule.
  - `AgentClaimGuard` with per-agent `mayControl` on `ensureCanControl`, the whisper bridge, chat transfer/dismiss/"follow <name>", and the refusal of transfer for rostered companions. The operator grant is kept. The boundary test covers every spawn/join/whisper/transfer call site.
  - `JoinOutcome`; ACTIVE only on JOINED/ALREADY_MEMBER; the stale-entry fix. `CompanionLeases` with refuse-not-remove in `spawnCompanion` and guards at the BenchSessions (:172) and ResidentRuntime (:320) load sites.
  - The follow instance guard.
  - `CompanionBudget` v1 (global plus per-owner caps from P0) and `ROSTER_MAX` per decision 20.
  - `!grantcompanion`.
- **Size:** M. **Depends on:** P0.
- **Verification:**
  - Unit: PartyInviteRoutingTest (target kind × party state → outcome and status code), LeaderSuccessionTest, ClaimGuardBoundaryTest (including whisper and transfer routes), JoinOutcomeTest, DoubleLoadGuardTest (a leased character is refused, not blinked), FollowInstanceGuardTest, BudgetTest.
  - Live, two clients:
    - Account B invites A's companion and gets "has denied", with nothing spawned.
    - B whispers "follow" to A's companion and is ignored.
    - B's chat "transfer" of A's companion is refused.
    - `@spawnbot` (GM6) of a rostered character is refused.
    - Change leader to a bot is refused.
    - The leader logs out and leadership goes to the human.
    - A lv8 character invites its own companion and gets a party (if decision 9 is (a)).
    - A GM-started KPQ instance leaves an unregistered companion waiting at Lakelis instead of in a ghost map.
  - Video: "safe companions" (2 min).
- **Exit:** A non-owner cannot spawn, pull or whisper-command any rostered companion, Resident or citizen. No bot ever leads a party with a human. No new failing tests.

**P1.5: Crew window v0 (first no-typing milestone)**
- **Goal:** Friends can call, bench and swap companions with the mouse right after P1, before the core rewrite lands.
- **Deliverables:**
  - The `onCharacterClicked` owner fix, and the portrait policy per probes 4/5.
  - A LivingTalk Crew window v0:
    - Party rows come from live Characters (name, Lv, job, map, HP%).
    - Bench rows come from the roster (name, Lv, job, duty @ camp, banked EXP).
    - Minimal "More..." paging.
    - Call / Bench / "Swap in for…" buttons call the existing `RosterService.call`/`bench`/`swap` behind the P1 guards.
  - Arrivals are placed at the owner-map portal nearest the owner (`spawnStationaryAgentForLeaderAt`, then follow), so v0 has no pop-in.
  - The Roster item intercept, if the probe 3 client data can ship now; otherwise double-click only.
  - Known v0 limits, replaced in P2a/P2b: swap is bench-then-call, so the party shows a hole during the ETA, and nothing runs on the executor yet.
- **Size:** S. **Depends on:** P1.
- **Verification:** Live, mouse only: rotate a bench companion in and out; the Resident path of flow 9; 20 consecutive menu actions with no stuck window. Video: "crew window v0" (60 s).
- **Exit:** Call, bench and swap complete with no typing. No pop-in on arrival. Zero new failing tests.

**P2a: Companion core (slots, party events, sync)**
- **Goal:** The roster and the party window never disagree, including with two friends and with citizen guests. The party window becomes the crew manager.
- **Deliverables:**
  - `CompanionExecutor`. `PartyEvents` with the pre-mutation snapshot, actor and cause. `CompanionPartySync` with echo suppression, circuit breaker and leader caps.
  - `CompanionSlots`: HOLD/AWAY/TRAVEL/GUEST reservations, fair share, and make-room at op 3 (per probe 12, else "Make room for…"). The `CitizenInviteDecision` guest seat.
  - The merge (per probe 1) or the loose-follow grace fallback.
  - The `Party.joinParty` seat gate with exemptions.
  - Changeset 40. `RosterResult`/`RosterVoice`. v0 menu actions move onto the executor.
  - `CompanionChatVerbs`: dismiss, logout+yes and park routed to the roster; `beginEscort` reservation.
  - `COMPANION_EXP_SHARE` per decision 17.
  - `CompanionAuditor` and its endpoints.
- **Size:** L. **Depends on:** P1.
- **Verification:**
  - Unit:
    - CompanionSlotsTest: solo; 2 and 3 humans; HOLD/AWAY/TRAVELLING/GUEST; leader cap; make-room with guests first.
    - CompanionPartySyncTest: every op × actor × ownership × in-instance; CHANGE_LEADER snapshot; echo suppression; no expel/refill loop.
    - PartyInviteRoutingTest additions: a human invites a citizen; a human joins a citizen-led lobby party.
    - ChatVerbBoundaryTest.
    - A property test over 10k random op sequences asserting at most 6 members, one owner per companion, one load per character, and roster == party after settle.
    - TEST_GUIDE CO-02..CO-09 with a fake LivingStore.
  - Live, two clients:
    - rotate by invite and expel;
    - merge or its fallback;
    - make-room at 6/6;
    - leader cap;
    - a citizen guest joins and then steps out on make-room;
    - "logout" + "yes" in chat leaves zero drift.
  - Video: "the party window is your crew manager" and "two friends, one party".
- **Exit:**
  - Every flow above passes live twice.
  - Zero drift events in a 30-min scripted session covering every party op, and the circuit breaker never trips.
  - **The user signs off on immersion from the video.**

**P2b: Companion lifecycle (travel, holds, channels, logout, contracts)**
- **Goal:** Companions arrive, follow, change channel, log out and survive restarts the way real players would, with no pop-ins.
- **Deliverables:**
  - Join-then-travel with non-follow loading and `arrive()` switching to follow. The follow travel guard. Portal-true follow for ordinary map changes. Persisted `eta_at`. Atomic swap.
  - HOLD and the rejoin primitive.
  - `CompanionChannelFollow`.
  - Logout/login/PARKED per decision 8, with the own-party definition, the shared-instance handoff (decision 8b) and the `BenchSessions.benchGoverned` extension. Boot reconciliation. Roster deletion cleanup.
  - `ROSTER_CONTRACT_MODE` and commitments (freeze and deferred walk-outs).
  - Moving RosterService and BenchSessions character loads off LOW_PRIORITY onto the executor.
- **Size:** L. **Depends on:** P2a.
- **Verification:**
  - Unit: TravelArrivalTest / FollowTravelSuppressionTest (the owner changes map during the ETA and the companion is not warped), PortalFollowTest, ChannelFollowTest, LogoutSharedPartyTest (the owner leads a party with a friend), InstanceLogoutHandoffTest, BootReconcileTest, ContractPolicyTest, DeletionCleanupTest, TEST_GUIDE CO-10..CO-16.
  - Live, two clients:
    - The owner changes map twice during a companion's ETA, and the companion still arrives by portal at the ETA end.
    - Ordinary portal follow shows walk-ins at the portal.
    - CC 1→2 with 4 companions arriving within 30 s.
    - Logout/login within and after 20 min.
    - The leader-with-friend logs out, and the friend gets the seats and leadership at once.
    - Restart during a travel.
  - Video: "they walk in like players".
- **Exit:** Every flow above passes live twice. Zero drift. No pop-in in the video. **The user signs off on the arrival clip.**

**P3: Click-only manager (card, Crew window, Roster item)**
- **Goal:** Manage 5 active companions and a bench with the mouse alone.
- **Deliverables:**
  - The LivingTalk extensions: paging, number input, version stamps, back, cm disposal, speaker modes.
  - `CompanionView` with its cache. `CompanionManager`. `CompanionMenus`: every screen listed in Design except hiring and settings.
  - Crew-wide orders: follow me, wait here, gather, rebuff, formation. "Hunt here while I'm busy" (grind/farmHere via `AgentMailboxRuntime`). A bulk bench duty.
  - "Let's trade" through the stock trade window, if probe 13 passed. "Wear your best from the haul" (autoEquip under the existing equip rule).
  - The full portrait policy and Crew window speaker ladder.
  - Usual lineup save/call.
  - The Adventurer's Roster intercept plus client data per probe 3, shipped through the patcher.
  - Gear view through stock char info.
  - Release confirm (flow 10).
- **Size:** M. **Depends on:** P2a. Travelling rows show ETAs once P2b lands.
- **Verification:**
  - Unit: CompanionMenusTest (options per state and ownership, paging, STALE re-render, disabled options while committed, crew-wide orders, trade and wear-best), CompanionViewTest, LivingTalkTest (opening over an NPC script disposes it and sends one enableActions).
  - Live:
    - every card and Crew path by mouse only;
    - the Roster item works with everything benched;
    - the hotkey works if probe 2 passed;
    - flow 10 release;
    - 50 consecutive menu actions with no stuck window;
    - Enter on a number box's default at once (the FARM_ITEM count, the wages hours) acts and leaves no window stuck; End Chat on a number box ends the chat (confirm its reply's action with `!companionprobe log on`);
    - gear view with the companion on another map ("Wait here", then walk off): the card offers no gear or trade, and nothing reaches the client;
    - new probe: "Let's trade" from the card opens the stock trade window (a server-started trade, not REQ TRADE), and a click while a shop, storage or another dialog opens meanwhile gets the companion's "ask me again" line instead.
  - Patcher check: the item name shows on a **second, clean client updated only through the patcher manifest** (not the dev client), and the rollback through the manifest is rehearsed and recorded. This is a release gate: it ships with the P3 server deploy, or P3 deploys with `ROSTER_BOOK_ITEM_ID: 0` until it does.
  - Video: "rotate a bench bot and assign a duty, zero typing" (90 s).
- **Exit:** The rotate, duty, lineup, crew-order and release flows complete click-only. The patched name reaches a clean client. **The user signs off on immersion from the video.**

**P3b: Companion settings**
- **Goal:** Per-companion fighting style, potions and loot that stick.
- **Deliverables:**
  - The NEW per-entry setters `AgentPotionService.setThresholds` and `AgentLootEligibility.setPolicy` (NO_BOSS default).
  - `settings_json` plus `CompanionSettingsApplier` for potion %, loot policy, support toggles and formation.
  - The Fighting style and Supplies & loot screens.
- **Size:** M. **Depends on:** P3.
- **Verification:** Unit: settings round trip; potion threshold and loot policy per entry. Live: settings survive bench, relog and restart; two companions with different potion % behave differently.
- **Exit:** Settings persist across every lifecycle path with zero drift.

**P4: Hired hands (in parallel with P3/P3b)**
- **Goal:** Every friend can reach 5 active plus a bench without GM help.
- **Deliverables:**
  - `HiredHands`:
    - synthetic personas, with the Look from the citizen's `characters` row;
    - `ResidentLooks` skipped for `hire:` ids in `ensureCharacter` and `syncLooks`;
    - the `LivingContent.persona` fallback and persona_state rows;
    - the `hired` flag, with skips in ProgressionLedger, Churn/`activeCount`, PresenceScheduler and SocialFabric;
    - `hire:` guards;
    - in-place adoption of a loaded citizen through leader transfer.
  - Hires count toward `ROSTER_MAX`.
  - Churn backfills towns for hired citizens, and the load is counted in `CompanionBudget`.
  - `CitizenInviteDecision` with personality. Party-time offers. The hire list from `living_acquaintance`. `hired-hand.yaml` with prices per decision 19.
  - `ResidentAllocation` per decision 18.
  - The Roster item granted on the first hire.
- **Size:** M. **Depends on:** P2a and P3's menus. Calling and benching hires uses P2b's travel once it lands.
- **Verification:**
  - Unit:
    - HiredHandsTest: resolve through `ensureCharacter`; the `characters` look columns are unchanged after hire → bench → call; adopting a loaded citizen does not despawn it; release returns the citizen.
    - PopulationSkipTest (ProgressionLedger, Churn, PresenceScheduler).
    - ContentLint with hire ids.
    - DoubleLoad-on-hire test.
    - ResidentAllocationTest.
  - Live:
    - Party a citizen, get the offer and hire with no blink; bench and recall, and the face is the same.
    - Hire from the list.
    - A second account hires at the same time.
    - After 10 hires, the town citizen count is back at target.
  - Video: "from stranger to crew member".
- **Exit:**
  - Two accounts each run 5 active plus at least 2 bench in one session with no GM help.
  - No exceptions from `hire:` ids in 1 h of play.
  - No look changes.
  - Town population at target.
  - The measured load of hires fits the budget.

**P5: Living bench and learning**
- **Goal:** Benched companions are visibly alive (grinding, gathering, real FM stalls, town life) within a measured budget, and they choose work from measured returns.
- **Deliverables:**
  - `BenchDutyDispatcher` on the activity runtimes, with every duty mapped to a visible form: field leases, GATHER pickup, STALL/SELL through `AgentFreeMarketStallCapability`/COMMERCE, REST through TownLife, and an optional QUEST duty.
  - Duty-aware end rules. Harvest limited to kill loot and meso. Clean hand-back. The bench-live death and resupply rule (flow 12).
  - The default FieldCatalog camp. The per-owner live quota, with owners online first. The COMMERCE pool. The population sweep exclusion.
  - Changeset 42, `BenchYieldModel`, "Your call" (`BenchDutyChooser`), and rates shown in the pickers.
  - Bench combat learning in LEARN mode for out-of-sight grinders, with arena-checked adoption (decision 13).
  - "Keep my crew fresh" per decision 15.
  - Wages and upkeep retuned against measured meso/h (decision 19).
  - The optional background-PQ-population duty, if decision 12 includes it.
  - Bench-live stays on the living channel.
- **Size:** L. **Depends on:** P2b and P3.
- **Verification:**
  - Unit: per-duty end rules (town duties survive), HarvestDiffTest, quota fairness, yield shrinkage toward the prior, a bandit regret test on synthetic rates, the rotation trigger rules, and arena-gated table adoption.
  - Live:
    - walk to Ant Tunnel and watch your companion grind;
    - open a STALL companion's real shop in the FM and buy from it;
    - watch a REST companion in town;
    - call one back and read its report;
    - see an auto-rotation happen in party chat.
  - Soak: 2 humans × 5 active + live bench + stalls + citizens for 1 h at `-Xmx2048m`, watching `/runtime/budget`.
  - Video: "my bench is alive".
- **Exit:**
  - No OOM. LOW_PRIORITY lag and tick p95 stay within the P0 budget.
  - Ledger and live EXP/h agree within ±25% after 5 slices per camp.
  - No double loads in the logs.
  - **The user signs off on the video.**

**P6: KPQ with companions (can start right after P2b, in parallel with P3-P5)**
- **Goal:** A friend clears KPQ with 2-3 companions, chosen at Lakelis with one click.
- **Deliverables:**
  - A day-1 **PRODUCTION-mode** smoke test through a NEW GM6 flag `!kpqtest party prod`, which calls the public 5-argument `admit` with real companions, time-boxed. Check the PRODUCTION termination, return, lifecycle and checkpoint paths. If KPQ stage logic needs longer, fall back to HPQ first (P6b's HPQ seam).
  - PRODUCTION guidance prompts for companion-adopted sessions (`AgentKpqHumanDialogueRuntime` gate, `COMPANION_PQ_GUIDANCE`).
  - `CompanionPartyQuestService`: plan, bench call through the eligibility picker, holds, admit before start, abort through registry remove + `forceStop`, failure-reason lines, PQ-scoped authority.
  - The 9020000.js branch. The recover-to-owner change. The instance death policy. Commitments. The shared-instance logout handoff for PQs. `CompanionRunRecorder`. The `COMPANION_PQ_ENABLED_KEYS` switch.
- **Size:** L. **Depends on:** P2b.
- **Verification:**
  - Unit:
    - LineupPlanTest: a 6-member party → the best 4 in range.
    - BenchCallPlanTest.
    - AdmitBeforeStartTest: admit validation reasons become lines.
    - AdoptRollbackTest: startInstance false → session removed, holds rejoined, and `AgentPartyQuestEngagementRegistry.forMember(agent) == null` for every Agent.
    - TwoOwnerAdoptTest, RecoverToOwnerTest, DeathPolicyTest, GuidanceModeTest.
  - Live:
    - a full clear with player + 3 companions;
    - a 6-member party with 2 waiting beside Lakelis who rejoin after;
    - a bench call at Lakelis;
    - a forced failed start that restores everyone;
    - a companion death mid-run that does not end the run;
    - **a two-client run with two owners** (flow 3 step 10), including one owner logging out mid-run while the other finishes.
  - Video: the full run plus a 60 s highlight.
- **Exit:** 3 consecutive clears with no GM intervention. Zero drift. No companion left in TownLife at Kerning. Run records written and reflected in the next proposal.

**Companion restock (decided 2026-10-02; replaces 2026-10-01's "Lakelis checks potions and the companion asks its owner, no auto-buy")**
- **Why:** live on 2026-10-02, after four back-to-back KPQ runs Fenna had 1 HP potion and 155 meso, SilentSin 14 HP / 21 MP potions (103k meso), Garrick 20 MP and Faro723 22 MP. A companion only resupplied below POT_STOP (10) while Lakelis wanted 20 HP and 25 MP, so she held three of them back and the run went in with three.
- **The decision (user, verbatim):** "companions should restock potions themselves before any pq or boss." Asking the owner stays only for a companion that cannot afford its potions.
- **One standard.** `AgentSupplyReadiness.standard(PARTY_QUEST)` = 20 HP potions, plus 25 MP potions for every job past a beginner's (agent-engine.yaml `AgentSupplyReadiness.PARTY_QUEST_*`). Lakelis and the Agent count it the same way (`AgentPotionService.countPotions`).
- **One mechanism: a standing duty.** `CompanionRestock` (the living world's `AgentSupplyReadiness.Rule`) gives it to every companion its owner carries: ACTIVE, led by them and following them (one told to wait, sent somewhere or hunting on its own keeps its owner's order), or HOLD for them, also while the run it waits outside commits it as an extra. On a town map whose nearest Victoria potion shop is at most `STANDING_MAX_HOPS` (2) away, a companion below the standard walks there by itself, buys toward `BUY_TO_PERCENT` (200, so 40 HP / 50 MP) in one visit, and comes back: by follow warp, or on foot while held. That covers the Lakelis desk (1 hop), the HPQ desk (Henesys park, 2 hops), back-to-back runs and HOLD extras during a run. There are no one-off orders yet (they come with P7).
- **It never goes when it cannot pay.** Before a readiness trip, its cost (the ammo top-up at that shop plus the potion shortfall at the shop's cheapest potion) must fit its purse less its wallet reserve (0 for a companion), and its Use tab must hold the new kinds of potion plus the one slot a party quest keeps free (potions that stack onto a carried stack need none). Otherwise there is no trip and no party ask: it says its broke line once (at most every 30 min) and waits until its purse grows by what it lacked (its owner's coin, say), a slot frees, or it lacks fewer potions (its owner handed some over).
- **A critical trip (below POT_STOP) passes a lighter check** (review 2026-10-02: the full estimate left a poor companion dry in the field where it used to buy a few): the potions back to POT_STOP at the cheapest price, without ammo, and no slot check. When even that does not fit it never walks to a shop; in a town it does not ask its party either (its broke line covers it), out in the field it still asks once, since a share is all that helps there.
- **The shop visit reaches the standard first.** A duty holder's visit buys each kind up to the standard (HP first, keeping back only what the mana potions it lacks of the standard cost), then the usual reserve bars, then tops up toward 40/50 with what is left; with at most one Use slot free it buys onto a stack it carries.
- **The owner's order wins.** Any movement order from chat or the card (follow, wait, move here, hunt here, patrol) calls a companion back from its readiness trip: it ends the trip where it stands (follow: by following), and waits 5 min before the next one; two trips from one town cut short in a row end its tries there until it has been elsewhere. It does not set off while its owner is leaving the map.
- **The desk shows it:** "Gone to the store: X (~N s)" with "Are they here yet?" and no Yes while it is away; "X (low on potions, out of mesos)" and one broke line per 5 min when it cannot afford them (also after a run or a walk from another map, while what held it back still holds). A click asks every short companion to look at once; one whose own restock decides on its next tick asks nothing, and the desk's and the companion's own broke line share one memory. `living_world.COMPANION_RESTOCK_ENABLED: false` restores everything as before (the ask for mana included).
- **Bosses today:** the party-boss gates (Papulatus 220080000, King Pepe 106021402) reach no shop, so the pre-boss restock is the standing top-up in Victoria towns, and a short companion says a no-shop line once at the gate (`COMPANION_RESTOCK_BOSS_MAPS`). Entry is never refused. Real pre-boss restocking arrives with P7.

**P6b: More PQs (generalization)**
- **Goal:** The PQs friends can actually enter work with companions, starting with HPQ.
- **Deliverables:**
  - A NEW public adopt seam per PQ: HPQ first, then the others (LPQ, LMPQ, OPQ, EPQ, PPQ) in decision 21 order.
  - Per-PQ composition rules in `plan`, following Agent admission (LPQ/OPQ/PPQ exactly 6, EPQ one per branch).
  - The NPC branches (1012112.js for HPQ first), with the bench-call option reused. Per-key kill switches.
  - Companion restock is inherited (decided 2026-10-02, "Companion restock" above): every desk that goes through `CompanionPartyQuestService.offerAtDesk` gets the status mapping, the lines and the store errands. Each key's waiting reason uses `AgentSupplyReadiness.shortfall(PARTY_QUEST)`, and its own "not at the entrance" reason joins the gone-to-the-store mapping. HPQ (100000200, a town 2 hops from the Henesys shop) is fully covered.
  - **Limitation:** the LPQ, LMPQ, OPQ, EPQ and PPQ desks are off the Victoria route graph, so their companions cannot walk to a shop from there: those desks show "low on potions" and the ask line only. They top up in the Victoria towns they pass through.
- **Size:** S-M for HPQ, M per further PQ. **Depends on:** P6 and decision 21.
- **Verification:** Unit: plan/adopt per PQ, including composition refusals as NPC lines. Live: one clear per enabled PQ, one of them with a bench call. Video per PQ.
- **Exit:** Each enabled PQ has one live clear with zero drift. A PQ that fails stays switched off.

**P7: Expedition management (playable on Easy Balrog)**
- **Goal:** More than 5 companions from one or more friends register, fight, take real boss damage, and return correctly on Easy Balrog.
- **Deliverables:**
  - `CompanionExpeditionService`:
    - registration by any member;
    - an ally picker with ETA against the clock and "N seats left for you";
    - fair-share seats and burst permits between owners;
    - overflow bot parties and contains-verification;
    - quota messaging;
    - re-entry through the 2030013.js branch.
  - The ADOPT_CLAIMED mode (claim-checked) with the no-fixture-rewrite test.
  - The quota fix: recruit-map filter, plus logging on late in-progress entry. Hooks.
  - **Easy Balrog server authority with companions**, evaluated live. If the human's view is wrong, the fallback pulls the Balrog-only `BossAttackMirror` into P7.
  - Loot NO_BOSS and loot credit. Post-clear return. The EXPEDITION budget pool. The Adobis and Balrog NPC options.
  - Pre-boss restock (from the 2026-10-02 decision): Activity BOSS and one-off restock orders, posted at `Expedition.beginRegistration` by `CompanionExpeditionService.registerParty` / `callAllies`. Runner registration waits while a companion is RESTOCKING, bounded by the registration clock minus 60 s. Sleepywood 105040300 (a town, 6 hops to the Henesys shop) is the approach town. The boss standard is tuned from Easy Balrog evidence.
  - Open item from P3b: the "Leave boss drops to you" setting stands aside inside every event instance today (`AgentLootPolicy`), so a companion in a party boss run (Papulatus, King Pepe and the Yetis, Scarga, Pink Bean) still picks up the boss's drops; the card says "outside boss runs". Here NO_BOSS applies inside boss runs too, except where a PQ session or a reward claim (`AgentEasyBalrogRewardClaimRegistry`) assigns the drop, so bots still take the KPQ pass from King Slime.
- **Size:** L. **Depends on:** P2b (commitments, travel), P4 (lv50+ supply; `!grantcompanion` for tests), and P6's instance death policy and run recorder.
- **Verification:**
  - Unit:
    - a registration planner (party + allies, quota refusals, allies ≤ `ROSTER_MAX − 5`);
    - sub-party partitioning;
    - quota logged only for members on the recruit map, and logged on late re-entry;
    - the fixture-rewrite guard;
    - lease release on dispose;
    - TwoOwnerExpeditionTest (seat and permit split);
    - a server-authority test asserting HP loss plus the `damagePlayer` broadcast on bots.
  - Live:
    - Easy Balrog with owner + 5 party + allies, from registration to the end of the run;
    - a **two-client run with both friends bringing companions**;
    - bots visibly losing HP and one dying;
    - the human's own hits and Balrog's movement look stock;
    - `/runtime/budget` sampled at the peak.
  - Video: "expedition management".
- **Exit:**
  - Registration, sub-parties, quota, loot and return are all correct. Zero drift.
  - Bench allies are back on duty afterwards.
  - Bots take Easy Balrog damage with friends present.
  - `COMPANION_BOSS_ENABLED_TYPES` gains easy balrog.
  - **The user signs off on the video.**

**P8: Boss damage mirror and Zakum**
- **Goal:** Bots take real Zakum hits when a human controls the boss, and Zakum is cleared with at least 8 bots.
- **Deliverables:**
  - `BossAttackMirror`:
    - the MoveLifeHandler hook next to :157;
    - a human controller in the same instance;
    - `applyOrdinaryAttack` per bot in the hit region;
    - SERVER_STICKY skip and once-per-action dedup;
    - each Zakum part independent;
    - disengage when no human controller remains.
  - `AgentZakumScenario` and `BossTargetRules` (arms first).
  - If P7 fell back to the Balrog mirror, P8 extends that code.
  - `COMPANION_BOSS_ENABLED_TYPES` flips for zakum only after its live exit. Papulatus stays excluded until mirrored.
  - Pre-Zakum restock: either add the El Nath potion shop and an Ossyria route to the supplier catalog, or rely on the Victoria top-up plus the gate line (the 2026-10-02 decision).
- **Size:** XL. **Depends on:** P7.
- **Verification:**
  - Unit: mirror geometry against WZ ranges; **HP loss plus `damagePlayer` broadcast** for a bot in range, and none out of range; dedup per action; the SERVER_STICKY skip; the Zakum phase classifier; a loot-credit test.
  - Live: Zakum with owner + 5 + at least 3 allies. Bots take arm slams, lose HP, can die, and a dead ally re-enters. Tick and GC are measured during the fight.
  - Video: the full Zakum run plus a 90 s highlight.
- **Exit:**
  - One live clear with at least 8 bots. No body damage while arms live. Correct daily logs. Bots observed taking boss damage.
  - **The user signs off on the video.**
  - If this slips, Zakum stays off for friends; invincible bots are never shipped.

**P9: Polish and learned suggestions**
- **Goal:** Remove the remaining friction and use recorded outcomes.
- **Deliverables:**
  - Party Search fills from the bench (flow 11: `PartySearchStartHandler` calls NEW `CompanionManager.fillFromBench`).
  - Lineup suggestions from `companion_performance`, shown on cards and in the PQ NPC proposals.
  - Named lineups (Hunting/PQ/Boss) in a small NEW table.
  - Buddy projection behind `COMPANION_BUDDY_PROJECTION`. This is a plan decision: only loaded companions show online, groups are Party/Bench/Resting, and "Where" opens the card. It is enabled only if probe 8 passed.
  - Registrar and slot lending, if the user chooses them.
  - Live errands in the party (a restock shown on the shop map), if the budget allows.
- **Size:** M. **Depends on:** P5 and P6.
- **Verification:**
  - Unit: ranking with synthetic records; Party Search level and job matching.
  - Live: Party Search pulls matching bench companions; **the buddy list shows the groups and "Where" opens the card** (when enabled).
  - Video: "crew manager tour".
- **Exit:** Suggestions change after recorded runs. No drift.

**P10 (optional): Native Crew window (DLL)**
- **Goal:** A native-looking v83 window, built only if friends find the NPC-dialog manager clunky.
- **Deliverables:**
  - A kaentake release checkpoint with the version guard re-enabled.
  - A `CClientSocket::ManipulatePacket` receive hook.
  - Recv 0x117 / Send 0x17A with HELLO gating and a rate-limit family.
  - A CUIWnd window: party cards, bench list and drag to swap.
  - A hotkey through `ISMsgProc` that is ignored while chat has focus.
  - Custom.wz sprites, and teardown on OnLeaveGame/ClearFieldUI.
  - The server handler issues only `CompanionManager` commands. A BOT_EQUIP gear tab is optional.
- **Size:** XL. **Depends on:** P3 and a kaentake release checkpoint.
- **Verification:** Packet codec tests. Handshake gating (no custom packet without HELLO). A stale-DLL client still works. A 2-hour soak on the user's client before friends get it. A rehearsed rollback through the manifest. Video: side-by-side with the NPC manager.
- **Exit:** Zero crashes across 2 h with 2 friends, and every action has the same result as in the NPC dialog.

## Risks

| Risk | Mitigation |
|---|---|
| The KPQ coordinator (about 7k lines) and all 7 PQ systems have never been verified live, and the PRODUCTION admit path has never run. | P6 opens with a time-boxed PRODUCTION-mode smoke test (`!kpqtest party prod`), not the TEST_OBSERVATION path. Admit runs in one engagement, which avoids the queue deadlock. There is a kill switch per PQ and an HPQ-first fallback. |
| Reconcile feedback loops or re-entrancy into `World.updateParty`. | Post-commit async dispatch to the executor, pre-mutation snapshots, cause tokens, leader caps, a circuit breaker, a property test and zero-drift gates. |
| Moving character loads onto a serial executor could block on map or party locks, or become a bottleneck. | Reserve, act, then commit or roll back. A per-command watchdog and timeouts. Staggered spawns (which also look natural). Queue depth exported on `/runtime/budget`. |
| The v83 client may not accept an invite while already partied, or may not send an invite at 6/6. | P0 probes 1 and 12 decide it. The loose-follow grace-window fallback and the "Make room for…" action are specified. |
| Heap and CPU on 2 GB: companions per friend, hires plus Churn backfill, live bench, stalls, and expeditions with about 29 bots in one map. | P0 measurement against the decision 20 target. `CompanionBudget` from P1 on, with citizens made virtual first and a separate COMMERCE pool. The expedition burst pool stays at 12 (split by fair share) until P8 measures more. Soaks in P5 and P8. |
| Adding TRAVELLING, HOLD and PARKED confuses existing `living_roster.state` readers. | One writer (RosterService on the executor). A boundary test. An audit of every reader. The down changeset maps the new states back to ACTIVE/BENCHED. |
| `hire:` ids reach code that assumes content personas; hires change looks or keep being reshaped. | Hire ids never enter `content.personas()`. Prefix guards and ContentLint rules. `ResidentLooks` is skipped and the Look is read from `characters`. ProgressionLedger skips hires. HiredHandsTest asserts that the look columns are unchanged. The P4 exit requires 1 h with no exceptions. |
| Forcing server authority on Easy Balrog may look wrong to the human (movement, attack timing, their own hits). | The mechanism is the one Poison Golem already uses. It is live-evaluated in P7 with the human's view filmed. The fallback is the Balrog-only mirror in P7. Balrog stays off for friends until one path passes. |
| The mirror is entirely new combat code, and Zakum has 9 separately controlled parts. | It calls the existing `applyOrdinaryAttack` damage path rather than a reaction-only method. It is scoped to a human controller in the same instance, once per action, with SERVER_STICKY skipped and WZ ranges validated. Tests assert HP loss. Boss support is not shipped until damage is observed. |
| `reloginAgent` is unproven for owner follow. | A 30 s deadline, then a fall back to the known despawn-and-load path. Kept behind a config switch. |
| Living-world systems assume channel 1 (ResidentRuntime whispers, BenchSessions loads). | Bench-live stays on the living channel. P0 probe 11 checks chat verbs and contract clocks off ch1 before companions follow owners across channels. |
| LivingTalk and NPC or item scripts collide (one conversation, 500 ms throttle), and LivingTalk works only while `living_world` is ENABLED. | `open()` disposes the cm first. The item is a Java intercept. PQ/boss NPC edits call Java inside the existing script conversation. Every path ends in a talk or `enableActions`. The manager is documented as needing `living_world` ENABLED. |
| No honest face for the Crew window if probes 4 and 5 all fail, leaving hire-only owners without a click path. | Five-step speaker ladder (Design). A pink-text fallback from the item. Decision 6 can close the gap with one portrait batch. |
| A String.wz patch collides with the portrait edits (a name without an image crashes the client). Resident portraits go stale after gear changes. | Prefer the Custom.wz leaf override (probe 3); otherwise build String.wz once from both sources with a lint. Portraits show the right face, with the outfit as of the render. Refreshes are batched and user-approved (decision 6). |
| Chat verbs, escorts or the whisper bridge mutate companions behind the roster's back. | `CompanionChatVerbs` inventory, per-agent `mayControl` on every route, a boundary test, and a live "logout + yes" zero-drift check. |
| The roster cap (10) limits bench and boss allies. | Decision 20 sets it against measured headroom. The Crew window shows bench seats. The ally picker caps at `ROSTER_MAX − 5`. |
| The dirty working tree and the deployed DLL drift from source. | A user-approved checkpoint in P0. No DLL change before P10. Hook lines re-verified after the commit. |
| Changing contract policy alters Resident stories. | `ROSTER_CONTRACT_MODE` config. Arcs can still end contracts explicitly. Decision 3. |
| Two-human flows are hard to verify alone. | Two clients on one PC (kaentake mutex hook), a scripted drift check, the unit matrix, and two-owner live runs in P2a, P6 and P7. |

## Decisions for the user

1. **May I checkpoint the uncommitted work?**
   - Options: (a) commit the living-world UI baseline and the kaentake sources to branches now, with no DLL rebuild; (b) commit server only; (c) no commit, build on the dirty tree.
   - **Recommended: (a).** Every hook line cited comes from the dirty tree, and any rebuild ships everything to every friend.
2. **Where do companions come from?**
   - Options: (a) Residents only (15 server-wide, one owner each); (b) Residents plus hired citizens (`hire:<cid>` personas); (c) plus durable owner-bound plain Agents (a new table that partly reverses the `bot_owners` removal); (d) admin grants only.
   - **Recommended: (b), plus GM `!grantcompanion` for tests and demos.** Citizens already look and talk like players. Hiring reuses the roster, bench and contracts, and it keeps `server.agents` owner-free.
3. **Should rostered companions be permanent?**
   - Options: (a) authored terms; (b) permanent once rostered (`PERMANENT_ON_ROSTER`), where arcs can still end a contract and hires stay while wages are paid; (c) permanent only at SWORN bond, hires while paid; (d) authored terms, but walk-outs deferred during runs and warned before benching.
   - **Recommended: (b).** A rotation bench is the core of the ask, and a companion quietly quitting on the bench reads as a bug. Walk-out deferral during runs applies whatever you pick.
4. **Where do you hire?**
   - Options: (a) party-time offers only; (b) plus an "Adventurers looking for a crew" page in the Crew window (met, level-matched citizens); (c) plus a Registrar clerk NPC in towns (a reused stock sprite shows that NPC's stock name unless String.wz is edited); (d) an Adventurers' Hall NPC.
   - **Recommended: (b); (c) later, only if a String.wz ship happens anyway.** Offers alone cannot supply a boss evening.
5. **How does the Crew window open when nothing is out, and how does the item look?**
   - Options: (a) the Adventurer's Roster item (2430011) via a Custom.wz leaf override (~311 KB) if probe 3 passes, else a String.wz rename (8 MB); (b) double-click yourself (replaces your own stock char info); (c) the buddy-list "Where" button; (d) a new item with its own icon (474 MB Item.wz).
   - **Recommended: (a).** It feels native, works anywhere, and is a small, data-only patch.
6. **Ship Npc.wz portrait batches?**
   - The card never shows a wrong face whatever you choose. The ladder is: an imitated-NPC portrait (probe 4), a speaker mode (probe 5), then stock char info with the card reached from the Crew window. The only open choice is whether to ship images.
   - Options: (a) no Npc.wz ships; hires and unrendered Residents use the ladder, and the Resident portraits friends already have keep the outfit of their render; (b) ship Npc.wz portrait batches (~580 MB per ship) for Residents and hires, re-rendered at most once per patch day after gear changes.
   - **Recommended: (a).** Revisit (b) only if probes 4 and 5 all fail and the Crew window needs a face.
7. **Recall timing.**
   - Options: (a) keep RecallTravel (20 s + 40 s/hop, 180 s off-island); (b) cap at 60 s on the same continent and keep 180 s off-island; (c) instant; (d) (b) plus a purchasable whistle for instant recall.
   - **Recommended: (b), whistle off.** Join-then-travel makes the wait visible and believable, and the cap keeps rotation from becoming a chore.
8. **Logout and login.**
   - 8a. *Normal logout.* Options:
     - (a) In your own companion party, they log off with you and stay as offline members. In any party with another human, including one you lead, they leave at once. They go to bench duty after 20 min (ledger), and on login they walk in (staggered 2-8 s if still parked, with a travel ETA if they had gone to duty).
     - (b) They go straight to bench duty.
     - (c) They keep playing their duty live while you're away.
     - (d) Instant pop-in beside you on login.
   - **Recommended: (a).** A real party remembers offline members, it never holds a friend's seats hostage, it finally uses `LEADER_OFFLINE_BENCH_MS`, and nobody pops in.
   - 8b. *Logout inside a PQ or boss instance a friend is still in.* Options: (i) your companions finish the run for your friend under delegation, then park; (ii) they leave with you under stock eim rules, which can end the friend's run.
   - **Recommended: (i).**
9. **Party below level 10.**
   - Options: (a) allow party creation below 10 when every other member is your own companion (implemented in P1); (b) keep `USE_PARTY_FOR_STARTERS=false`, so the manager works but fresh characters have no native party.
   - **Recommended: (a).** Otherwise new characters can't use the party-window manager, which players would find odd.
10. **Death inside an instance.**
    - Options: (a) PQ: stay down until resurrected or the run ends; expeditions: revive, walk back and re-enter while `canJoin`; (b) revive out and wait at the exit in both; (c) stock auto-respawn (ends 3-member runs).
    - **Recommended: (a).** It matches what players can do in each mode.
11. **Boss prerequisites for companions (Zakum 100200/100201; the owner still needs them and the Eye of Fire).**
    - Options: (a) waive them for rostered companions, which is today's follow-warp behaviour; (b) an abstract "trial" bench duty that completes them after N hours; (c) companions must really do them; (d) credit companions who were in your party when you did them (needs new tracking; there is no past data).
    - **Recommended: (a) now, (b) as a later option.** (b) and (c) would block tonight's Zakum, and friends never see bots' quest logs.
12. **How alive should the bench be?**
    - Options:
      - (a) Per owner, 2 live field grinders plus cheap town presence for REST and real FM stalls for STALL. GATHER is a visible drop-pickup field lease. The rest go in the ledger, with numbers retuned from measurement and bench-live on channel 1.
      - (b) Ledger only.
      - (c) Materialize bench bots whenever anyone enters their camp.
      - (d) As many live as the budget allows.
      - (e) Add to (a): benched companions may join the background PQ population runs as a duty.
    - **Recommended: (a), with (c) and (e) as later experiments.** It fills towns and the FM cheaply and stays within the 2 GB heap.
13. **Learning scope.**
    - Options:
      - (a) The bench duty/camp bandit and calibrated yields (P5), learned lineup suggestions (P9), and the combat learner in LEARN mode for bench grinders no human can see (P5), adopting tables only after an arena check.
      - (b) The same without unobserved combat learning.
      - (c) Priors only.
    - **Recommended: (a).** It learns from measured play without ever looking worse in front of a player.
14. **Seats when friends share a party.**
    - Options: (a) fair share, humans first (make-room, guests step out first), leader expels stick (leader caps); (b) first come, the leader expels; (c) the leader assigns every seat. Optional slot lending by the leader can be added to any of them (P9).
    - **Recommended: (a), with lending later.** It is predictable, needs no popups, and never blocks a friend from joining.
15. **Rotation: manual or automatic?**
    - Options:
      - (a) Manual only: swap, invite/expel, usual lineup.
      - (b) Plus an opt-in "Keep my crew fresh" per owner. A rested bench companion swaps in for one that is out of potions, dead outside an instance, or 10+ levels off you, announced in party chat.
      - (c) (b), with the triggers later learned from run records.
    - **Recommended: (b) in P5, (c) later.**
16. **Native DLL Crew window (P10).**
    - Options: (a) decide after about 2 weeks of friends using the NPC-dialog manager; (b) build it now in parallel; (c) never.
    - **Recommended: (a).** It is the only step that can crash every friend's client, and the NPC-dialog manager may be enough.
17. **Companion EXP share.**
    - Today, Agent party members take a normal share, so with a full crew you get roughly a sixth of each kill plus the party bonus.
    - Options: (a) the normal split; (b) companions earn their own EXP but do not dilute yours (your share is computed as if they were absent); (c) a reduced companion share.
    - **Recommended: (b).** Levelling several times slower with a full crew would read as a bug, and nobody sees the bots' own EXP.
18. **Sharing the 15 Residents among friends.**
    - Options: (a) first come, forever (today); (b) a per-friend cap (default 5), with release after 14 days of owner inactivity or on character deletion; (c) one owner at a time, released at logout.
    - **Recommended: (b).** Deletion cleanup ships in P2b regardless.
19. **Companion prices.**
    - Options: (a) free; (b) the authored hire fee, daily wages and bench upkeep (30 meso/level/h), retuned in P5 so upkeep stays under about 15% of a friend's measured meso/h; (c) fee only.
    - **Recommended: (b).** It is a gentle meso sink that makes hires feel like real arrangements.
20. **How many friends online at once, and how big a roster per friend?**
    - The current `ROSTER_MAX` is 10 (5 active + 5 bench); the configurable range is 1-50.
    - Options: (a) keep 10; (b) 15 (5 + 10), with extra bench members mostly on the ledger; (c) another number.
    - **Recommended: tell me the expected number of friends online. Keep 10 until P0 measures that load, and raise it only with at least 30% heap headroom.**
21. **Which PQs and bosses first?**
    - P0 reads friends' current levels and jobs.
    - Options: (a) KPQ, then HPQ, then the PQ whose level range covers most friends; (b) straight to the friends' current band after KPQ; (c) bosses before other PQs.
    - **Recommended: (a) for PQs, with KPQ first either way because it is the only existing production seam. Bosses go Easy Balrog → Zakum, unless most friends are below 50, in which case P6b comes before P7.**

## Out of scope / later

- A player-facing web or MapleEdit roster page. Web stays a loopback operator and debug console, because of the immersion rule and MapleEdit's auth gaps.
- Durable ownership of plain `@spawnbot` Agents, or reviving `bot_owners`. They stay GM tools.
- Lending or transferring companions between friends, beyond seat lending in P9.
- PQs with no Agent system: CWKPQ, Scarga and Showa. Also any PQ that P6b leaves switched off after its live check.
- Other bosses: Papulatus (until mirrored), Horntail (HorntailPQ certificates), Pink Bean and Chaos bosses. Horntail is the first follow-on on the same `CompanionExpeditionService` and mirror.
- All-bot PQ or boss runs from the bench, and bots leading expeditions without a human. The background PQ population duty (decision 12e) stays an experiment.
- Learned PQ puzzle roles. The production coordinators' deterministic roles are used, and outcomes are recorded.
- Enabling the World Director or Director bridge in production.
- Bench "layer-2" abstract hunting beyond BenchLedger, BenchSessions and the P5 dispatcher.
- Enforcing the unused contract terms lootShare and ownDropsPercent.
- New Item.wz assets (474 MB ship). Npc.wz assets beyond the user-approved Resident portrait batches of decision 6 (580 MB per ship).
- Emote or F-key triggers, the CLICK_GUIDE tutor opener, and repurposing the MTS button.
- Raising the v83 party cap above 6, or lowering expedition minimums.
- The BOT_EQUIP gear window client, except as part of the optional P10.
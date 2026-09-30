# Bot management P0 results (2026-09-27)

P0 of [BOT_MANAGEMENT_PLAN.md](BOT_MANAGEMENT_PLAN.md): checkpoint, client probes, measurement, data checks.
Branch `feat/companion-manager`. Screenshots are under `docs/agents/evidence/cua/p0-*.png` (untracked, like the rest of
the evidence media).

## Checkpoint

| Repo | Commit | Notes |
|---|---|---|
| server, `integration/cosmic-agents` | `2ec394c9e` | Living-world UI WIP (looks, outfits, portraits, quest markers). It is the build that had been running since 10:35. |
| server | `5746de0f8` | The plan. `feat/companion-manager` branches here. |
| kaentake, `friendstory/checkpoint-2026-09-27` | `2b14c0d` | All FriendStory sources. Nothing was rebuilt or deployed. |

- **Deployed vs built DLL.** `Game Files/Kaentake.dll` has sha256 `6f135ec7e692e07f...`, is 176,128 B and was built 2026-04-27 12:55. Friends run this one. `build/Release/Kaentake.dll` has sha256 `e341d2b9922dc660...`, is 198,656 B and was built 2026-04-27 14:25. It was never deployed.
- **Custom.wz.** `src/Custom.wz` and the live copy are identical (`2bcffb884c8d486d...`).
- **Not under git.** MapleEdit and the FriendServer root are not git repositories, so the portrait and marker scripts have no version history.
- **Evidence media left out.** About 3 GB under `docs/agents/evidence/{video,cua,cua-pilot-2026-09-26}` was not committed.

## Test baseline

| Run | Tests | Failures | Errors | Skipped | Failing set |
|---|---|---|---|---|---|
| Baseline at `5746de0f8` (`-Dtest='!BotManagerTest,!BotCombatManagerTest'`) | 7085 | 25 | 4 | 13 | 29 tests, all pre-existing (listed below) |
| After P0 code (`e970f99b2`) | 7166 | 25 | 4 | 13 | identical |

The 81 new tests all pass.

**The 29 baseline failures:**
- The Agent lint and boundary suites: AgentConfigurationBoundaryTest ×2, AgentArchitectureBoundaryTest ×4, AgentCosmicBoundaryAuditTest, AgentSchedulerBlockingBoundaryTest ×2, AgentRuntimeClassificationTest.
- Content and catalog tests that need Kiwiism's wz_victoria: AgentMushroomKingdomContentTest ×3, AgentVictoriaAdaptiveQuestCatalogTest, AgentNpcInteractionAnchorCatalogTest, AgentCatalogServiceTest, EconomyDemandCatalogIntegrityTest.
- The movement labs: BotMovementManagerTest ×2, BotMovementSimulationLabTest ×2.
- Individual tests: AgentBuildServiceTest, AgentChatRouteCoordinatorTest, AgentWhisperCommandServiceTest, AgentInteractionRuntimeTest, AgentProgressionEventIntegrationTest, AgentResourceEventIntegrationTest, CosmicShopGatewayTest, CharacterPersistenceSectionMutationTest.

## P0 code

- `GET /runtime/budget` (loopback, Bearer) reports:
  - heap, including old-gen after GC; `?gc=1` forces a GC first;
  - CPU, threads;
  - Agents by kind;
  - scheduler registrations, capacity and queue-lag percentiles;
  - the LOW_PRIORITY timer lag from a 5 s probe.

  It lives in `server/admin/AdminBudgetReader.java` and `AdminTimerLagProbe.java`.
- `GET /runtime/companions[/{ownerId}]` is a read-only drift report. The pure classifier is
  `server/living/roster/CompanionDriftReport.java`. The drift codes are ACTIVE_NOT_IN_PARTY, ACTIVE_NOT_LOADED, IN_PARTY_NOT_ACTIVE, LEADER_MISMATCH, CHANNEL_MISMATCH and UNROSTERED_AGENT (the last one is informational).
- `!companionprobe` (GM6) is the probe harness. Its subcommands:
  - `log on|off` decodes the inbound packets from the GM's client.
  - `invite <name>` sends the GM a stock party invite.
  - `talk <speaker> [npc] [msgType]` opens a dialog with the given speaker byte.
  - `refresh` re-sends a dialog while one is open.
  - `imitate` shows a character's look on a dialog portrait.
  - `item` hands out the roster item.
  - `status` shows the harness state.

  It lives in `server/living/probe/*`, with one hook in `Client.channelRead`.
- Characterization tests pin today's bugs so that P1 and P2 flip them deliberately:
  - `RosterServiceCharacterizationTest`
  - `PartyOperationHandlerCharacterizationTest`
  - `AgentPartyQuestLobbyRuntimeCharacterizationTest`
  - `AgentFollowMapSyncCoordinatorCharacterizationTest`
  - `AgentControlServiceCharacterizationTest`

  Not pinned: expel leaving the roster ACTIVE, and channel-change stranding. Both need a live Party/World harness, and P2a's sync tests cover them.
- `tools/cua/maple_client.py` (outside the repo) gained `chat --keep-open`, `dclick`, `drag` (does not work on this client), `type` and `close_chat`.

## Client probes

Run on 2026-09-27 against the live client (Jevelopers, GM6), with `!companionprobe log on` watching the packets.

| # | Question | Result | What it means for the plan |
|---|---|---|---|
| 1 | Does a client that is already partied show an invite and send op 3 on accept? | **Yes.** It shows the stock "Party invite from ..." notice, and O sends `PARTY_OPERATION op=3`. The stock server then answers "You can't join the party as you are already in one." | A merge on accept is feasible. CompanionPartyRouter must handle op 3 from a partied owner. |
| 2 | Does a 243 script item on a hotkey send SCRIPTED_ITEM? | **No.** Pressing the bound key (T, persisted in `keymap`) sends nothing. Double-clicking the item in the inventory sends `SCRIPTED_ITEM slot=1 item=2430011`. | The Adventurer's Roster opens from the inventory, not from a hotkey. Decision 5 stays (a), with no hotkey. |
| 3 | Does a Custom.wz leaf override rename String/Consume.img/2430011? | Deferred. | Testing it means editing the live Custom.wz without the patcher shipping it to friends. It is done together with the item's client data in P1.5/P3. |
| 4 | Can an NPC dialog portrait show a character's look? | **Yes, with a spawn.** A real Player NPC (stock `!playernpc`, which sends spawnPlayerNPC and then IMITATED_NPC_DATA) shows the character's own look and name in the dialog. Sending IMITATED_NPC_DATA alone for an unspawned id (9901000) **crashed the client**. | Portrait ladder step 2 is viable only as a per-viewer spawn, then the look, then the talk, then removal. Never send the look without the spawn. |
| 5 | Which speaker modes work? | **0 and 8:** stock layout, NPC portrait on the left, End Chat shown. **1:** same, with no End Chat. **2:** the player's own avatar on the right, labelled with the player's name. **3:** the avatar with no End Chat. **4:** crashes the client; bit 0x04 expects a template id int. An object NPC works as a portrait: 9040011 "Bulletin Board", or 2111016, a book. | The Crew window speaks as "your notes" (speaker 2), or as the Bulletin Board 9040011. Never send bit 0x04. |
| 6 | Can an open NPC dialog be re-rendered in place? | **No.** A second NPC_TALK while a window is open made the client drop the connection ("Connection reset"). The client then sat on a broken login screen. | There is no live refresh. LivingTalk must never send while a window is open; it closes, then reopens. P1.5 adds a guard and a test. |
| 7 | Does the char-info Party button send op 4 for a bot? | **Yes, when there is room.** Double-clicking a bot gives CHAR_INFO_REQUEST, and the window has REQ PARTY and REQ TRADE buttons. | Char info is a working "invite this bot" affordance. |
| 8 | Does the buddy-list Party button send op 4 for an offline buddy? | Deferred to P9 (buddy projection). | |
| 9 | Do companions work in a party-registered Papulatus instance? | Deferred to P7 preparation. | |
| 10 | Do two clients run on one PC? | **Yes.** A second `Launch Game.exe` reaches the login screen next to the first. | Two-owner live tests need a second game account; none exists besides the owner's test alts. |
| 11 | Chat verbs, LivingTalk and contract clocks for a companion on channel 2? | Deferred to P1. | This needs a roster companion (`!grantcompanion`, P1). |
| 12 | At 6/6, do the party-window Invite and the char-info Party button still send op 4? | **No.** The client refuses locally with "It is impossible to invite someone to the party." and sends nothing. | Make-room cannot hang off op 4 from the full party's side. It is either the Crew window's "Make room for..." (plan fallback) or the friend inviting the owner into *their* party (probe 1), plus a merge. |
| 13 | Does the stock trade window work with a companion? | **Yes.** REQ TRADE sends PLAYER_INTERACTION CREATE and INVITE, the trade window opens, and the bot answers in the trade chat. | "Let's trade" in P3 can use the stock window. |

**Other things seen during the probes:**
- **Seventh bot.** `!spawnbot` of a seventh bot into a 6/6 party leaves it on the map, following, with party size 0 (ProbeDK). This confirms the silent join failure.
- **Bots outlive their owner.** Owner-bound Probe bots stayed online and grinding through three owner disconnects and about 11 minutes with the owner offline, with no safe-mode return. They only went away with "logout" + "yes".
- **Client crash while watching bots.** The client closed by itself about 2 minutes into watching 12 bots grind in the Cave of Light (240050300). No crash dump was written, and nothing was logged server-side beyond the connection reset. The cause is unknown; bot skill effects are a candidate (see the earlier Rapid Fire crash). This must be understood before the P2b/P5 videos and before friends watch large crews.
- **Beginner gear on job bots.** The char-info "Item List" of high-level Probe bots shows beginner clothes: Bronze Koif and T-shirt on the Lv123 Dark Knight. This may only be the cosmetic list, but the equip rule should be checked.

## Measurement

**Setup.** Measured at 16:05-16:32 on the running server (`launch.bat`, `-Xmx2048m`, central-sequential scheduler).
`/runtime/budget` was sampled every 30 s, with a forced GC at the end of each phase. The load was 6 to 12 Probe bots (L120-148,
all job branches) grinding Skelegons at 240050300, on top of about 240 loaded citizens and 8 Residents. The client dropped at
16:19:31, so A12 is split into a watched part and an unwatched part.

| Phase | Samples | Probe bots | Where | Process cores | Old gen after GC (MB) | Queue lag p50 / p95 / p99 (ms) | Cycle avg (µs) |
|---|---|---|---|---|---|---|---|
| base | 5 | 6 | idle in Lith Harbor, owner watching | 1.63 | 517 | 475 / 759 / 2157 | 33,300 |
| A6 | 11 | 6 | grinding, owner watching | 1.58 | 538 | 654 / 1099 / 2245 | 34,700 |
| A12 watched | 4 | 12 | grinding, owner watching | 1.56 | - | 554 / 920 / 2215 | 36,700 |
| A12 unwatched | 7 | 12 | grinding, owner offline | 1.48 | 544 | 585 / 990 / 2216 | 37,600 |
| B12 | 11 | 12 | grinding, owner offline | 1.52 | 550 | 240 / 578 / 2210 | 38,700 |
| end | 7 | 0 | none, owner online elsewhere | 0.90 | 551 | 21 / 74 / 95 | 37,000 |

**Reading:**
- **Heap is not the limit.** Old gen after a full GC stays at about 520-550 MB whether 0, 6 or 12 bots are online, so per-bot live heap is below the noise (a few MB at most). A forced full GC paused about 260 ms. That leaves about 1.5 GB of headroom on `-Xmx2048m`.
- **CPU is not the limit.** The whole process uses about 1-1.6 cores out of 16. The drop from B12 (1.52) to end (0.90) bounds 12 grinding bots at about 0.6 cores, or ≤0.05 cores each. Part of that is the looping citizens (below), so the true cost is lower.
- **The single sequential scheduler is the limit, and today it is dominated by a bug.** Cycle averages of 33-39 ms against a 10 ms budget and queue-lag p50 of 0.5-0.65 s come from agents stuck in a resupply loop at 400-500 ms per tick. The slow agents rotated from 674 and 509 to 513, 810, 795, 772, 784 and 643. Probe bots were slow only once in 27 minutes.
- **Watched vs. unwatched.** In this data the difference between watched (PRESENTATION) and unwatched (BACKGROUND_ACTIVE) grinding is lost in that noise.
- **Budget v1 (P1 `CompanionBudget` defaults, until the scheduler is re-measured after the resupply fix):**

  | Pool | Default |
  |---|---|
  | COMPANION | 5 per online human (hard) |
  | BENCH_LIVE | 2 per owner, 8 server-wide |
  | EXPEDITION burst | 12 |
  | Global Agent ceiling | 300 registered |
  | Spawn rate | 1 companion load every 2 s |

  Revisit the pools once lag p95 is under 100 ms with the fix live. The "end" phase shows about 74 ms is reachable.
- **Decision 20** stays at `ROSTER_MAX` 10. No friend data exists, and heap does not constrain it.
- **Not measured:** FM stalls, TownLife-only bench bots, and a 30-member expedition. They belong to P5 and P7 soaks.

## Data checks

- **No friends have played yet.** There are 855 accounts and 857 characters, and 852 of the characters are Agents. The human characters are the owner's Reluctant and Jevelopers plus three Lv1 test alts on the owner's machine. At most one human has ever been online at once (1,437 "Scale health" samples, and the admin login events).
- **PQ order (decision 21).** Friends will start at Lv1, so the order is:
  1. HPQ, which is the first they can enter
  2. KPQ, the flagship with the only production seam
  3. LPQ
  4. EPQ
  5. LMPQ and OPQ
  6. PPQ

  Bosses stay behind P7.
- **Portraits are already live on the patch manifest.** The manifest was generated 2026-09-27 10:34. Npc.wz holds all 54 Resident portraits (9970000-9970053) plus the hitbox 9970999, and String.wz names every one of them. Every friend's next launch downloads Npc.wz (about 580 MB). Decision 6 (no *further* Npc.wz ships) is unaffected.

## Scheduler saturation (found here, fixed elsewhere)

With about 254 Agents, `/runtime/budget` showed:
- scheduler queue lag p50 about 500-650 ms, p95 about 760-1100 ms and p99 about 2.3-2.5 s;
- cycle average about 33 ms against a 10 ms budget;
- process CPU of only about 1.6 cores out of 16.

**Cause.** Two citizens produced 848 of 852 "Slow central Agent tick" warnings at 400-500 ms each: 674 EvilSin in Mu Lung and 509 HolyPriest in El Nath. The parallel session "Fix two citizens with 400-500 ms Agent ticks" traced it to a resupply loop:
- AgentPlanReattachmentRuntime kills fresh resupply objectives.
- That leaves stale remediation frames.
- AgentSupplyProcurementRuntime.begin() then rescans shops every tick.
- On shop maps an uncached getItemEffect NPE re-parses WZ XML at about 400 ms a time.

The queue-lag numbers above were measured with that bug live and must be re-measured after the fix.

---

# P1 results (2026-09-27)

**Branch and suite.** P1 is merged into `feat/companion-manager` by fast-forward (`844bc9f4a`). The integration and fix stages ran the full suite: 7326 tests, 25 failures and 4 errors, the same 29 tests as the P0 baseline. There are 0 new failures and no new lint or boundary violations.

**What P1 contains:**
- **Ownership.** `CompanionDirectory` is the ownership lookup, and `AgentClaimGuard` (`mayControl`, `mayLoad`, `isClaimed`) enforces it. The guard is wired into:
  - `ensureCanControl`
  - the whisper bridge
  - the chat transfer, dismiss and follow-retarget routes
  - `AgentLeaderTransferCoordinator`
  - `decidePartyInvite`

  `ClaimGuardBoundaryTest` pins all 48 spawn, join, whisper and transfer call sites.
- **Budget and leases.** `CompanionBudget` enforces 5 companions per human, 2 bench-live per owner and 8 globally, a 12-slot expedition burst, a 300-Agent ceiling, and 2 s load spacing. `CompanionLeases` gives each character at most one materialization lease.
- **Party window:**
  - Op 4 resolves the name before any spawn: your own companion becomes a call, while another player's companion or a Resident gets the stock deny 23.
  - Op 6 refuses a bot target, which answers "nah, you lead".
  - Leader succession is human-first (`Party.successor`, and `World.updateParty(CHANGE_LEADER)` fixed).
  - Messenger no longer spawns anyone.
  - A starter party below level 10 is allowed for companion calls.
- **Roster integrity.** `JoinOutcome` means a row is ACTIVE only after a real join. The stale-entry double grant is fixed. `spawnCompanion` refuses instead of removing a runtime held by someone else. The BenchSessions and ResidentRuntime load sites are guarded.
- **Follow.** The follow instance guard stops ghost-map warps.
- **GM tool.** `!grantcompanion <personaId> [name]` / `!grantcompanion release <personaId>`.

**Live checks.** These ran on the live server with two clients: Jevelopers (GM6, owner of Hadrik) and ohboy (Lv15 test account, owner of Brannock).

| Check | Result |
|---|---|
| Owner invites their own companion by name from the party window | **Pass.** Hadrik (benched) joined Jevelopers' party. |
| Friend invites another player's companion | **Pass.** "Hadrik have denied request to the party.", nothing was spawned, and Hadrik stayed in the owner's party. |
| Friend (an operator through its own companion) whispers "follow me" or chats "transfer Hadrik to ohboy" | **Pass on state.** Hadrik stayed ACTIVE, registered to its owner and in its owner's party; `/runtime/companions` showed no drift. The transfer line was caught by the resident dialogue layer (read as ASK_JOIN) before the agent router, so the router-level refusal is covered by unit tests only. |
| GM6 `!spawnbot` of another player's companion | **Pass.** "'Brannock' does not take orders from you." |
| Change leader to a bot | **Pass.** Hadrik whispered "nah, you lead"; the leader was unchanged. |
| Leader logs out in a party with a human and a higher-level bot | **Pass.** ohboy (Lv15 human) became leader, not Hadrik (Lv31). Jevelopers shows in the stock offline section, and Hadrik left with its owner. |
| Starter party below level 10 | Unit tests only. |
| GM-started KPQ keeps an unregistered companion outside the instance | Unit tests only (`FollowInstanceGuardTest`). |

**Findings:**
- **Mushroom Town crash.** The ohboy client quit silently (no dump) a few seconds after entering Mushroom Town (map 10000). After ohboy was moved to Lith Harbor it was stable. Not investigated.
- **The v83 login box truncates passwords to 12 characters.** Test passwords must be 12 characters or fewer.
- **kaentake `rememberpw` shares one saved login.** It saves the last typed login (`HKCU\Software\Wizet\MapleStory` `LastPassword`/`LMA`) for every client on the PC. A second-account login therefore overwrites the owner's remembered password. After the test, the ID was restored and the test password removed. The owner types their password once.
- **Residents route commands through their own dialogue.** A resident persona's "i don't travel" refusal is used even when the persona is already someone's companion. Commands aimed at a named persona go through ResidentDialogue first.

---

# P1.5 results (2026-09-28)

## Branch and suite
P1.5 is merged into `feat/companion-manager` by fast-forward to `fcd6e4f62`.
- **Full suite:** 7415 tests with 25 failures and 4 errors, which is exactly the baseline's 29 failing tests. There are 0 new failures.
- **After merging into the shared tree:** the P1.5 tests and the NPC-script prep tests pass. The P1.5 guard lives in `NPCScriptManager.start(...)`, which the KPQ-script session had left uncommitted. I kept that session's change and re-applied it on top by hand.

## What P1.5 adds
- **LivingTalk safety:**
  - A speaker byte can be set per talk; bit 0x04 is refused.
  - "More..." paging.
  - Version-stamped option ids, so stale clicks and double clicks are ignored.
  - No NPC_TALK is sent while a window is awaiting its reply.
  - Stock NPC, quest and item scripts can't open over a living-world window (`StockWindowGuardTest`).
  - Every path ends in a talk or in enableActions.
- **Companion card.** Double-click your own companion. It shows the companion's rendered portrait and Lv, job and HP, with these options: Take a break at camp, Let someone take your place, The whole crew..., Let me see your gear, Never mind.
- **Bench card:** Come join me, Swap in for..., Never mind.
- **Crew window.** It opens from a card or by double-clicking the Adventurer's Roster (item 2430011) in the inventory.
  - Speaker 2 (your own avatar).
  - A "Party n/6" block.
  - A "Bench (x of 9)" block with duty @ camp and banked EXP.
  - Party rows open the companion card; bench rows open the bench card.
- **Walk-ins and walk-outs.**
  - A called companion loads at the nearest usable visible portal and walks in, instead of popping in.
  - A benched companion walks out.
  - Login restores are staggered.

## Live checks
Run 2026-09-28 on the Jevelopers client with Hadrik and Olwen (granted with `!grantcompanion`), mouse only.

| Check | Result |
|---|---|
| Double-click own companion | **Pass.** Hadrik's own portrait appears, with the header "Hadrik, Lv31 Fighter - HP 2911/2911" and 5 options. |
| The whole crew... | **Pass.** Party 2/6, then Hadrik; Bench (1 of 9), then "Olwen, Lv12 Beginner - train @ Lith Harbor". |
| Bench card, then Swap in for..., then pick the outgoing member | **Pass.** Olwen asks "who should i take over from?". The outcome page reads "Hadrik heads back to camp to train. Olwen is on the way (~2 min)." Hadrik whispers "i'll be on the deck. don't take long.", then the stock "'Hadrik' have left the party.", then "Olwen>> on my way. ~2 min." |
| Arrival | **Pass.** Olwen arrived about 110 s later ("'Olwen' has joined the party.", then "Olwen>> here."). On camera, Hadrik (swapped back) loads at Kerning's west portal (`west00`, the nearest visible portal to the owner) and walks in. |
| 20 consecutive menu actions (5 × card, crew, bench row, close) | **Pass.** 20 NPC_TALK_MORE packets with a fresh stamp each, no stuck window, and the client stayed responsive afterwards. |
| Roster item double-click | **Pass.** `SCRIPTED_ITEM slot=1 item=2430011` opens the Crew window. |
| Login restore | **Pass.** Hadrik was ACTIVE at login and was already back beside the owner and in the party when first checked, a few seconds after login. |

**Video:** `docs/agents/evidence/video/p15-crew-window-v0.mp4` (60 s: menus and swap, then the walk-in), cut from the 170 s raw take `p15-crew-window-v0-raw.mp4`.

## Known v0 limits
- **Swap is bench-then-call.** The party shows a hole during the ETA. P2a/P2b fixes this with join-then-travel and an atomic swap.
- **The item still reads "Agent Summon".** The String rename ships in P3.
- **Bench rows can show stale data while a bot walks out.** A benched companion's row can still show its map for up to 8 s while it walks out.

---

# P2a results (2026-09-28)

## Branch, suite and migration
P2a is merged into `feat/companion-manager` by fast-forward to `ff190d85a`.
- **Full suite:** 7672 tests with 25 failures and 4 errors, exactly the baseline's 29 failing tests. There are 0 new failures.
- **Migration:** changeset `40-companion-manager` applied on boot. `living_roster` gained `lineup_slot` and the other new columns, and the existing rows are intact.
- **Merge:** one uncommitted change from another session (`AdminBudgetReader` headlessNpcScripts) was re-applied on top by `git apply -3`, cleanly.

## What P2a adds
- **CompanionExecutor.** A single serial `companion-ops` thread runs every roster mutation, including chat verbs, menus, bench and contract ticks, and logout.
- **PartyEvents.** Every party change is published after it commits, carrying the state before the change and who made it (actor and cause).
- **CompanionSlots.** The only seat authority:
  - fair share floor((6-H)/H);
  - HOLD, AWAY, TRAVEL and GUEST reservations;
  - leader caps;
  - make-room.
- **Party.joinParty seat gate**, with exemptions.
- **CompanionPartySync:**
  - An expelled companion goes to the bench.
  - When its owner leaves or is expelled, the crew re-forms around the owner.
  - A disband re-forms each human's crew.
  - When a human joins, the party is rebalanced by fair share, with guests stepping out first.
  - returnWhenRoom, echo suppression and a circuit breaker.
- **Merge on accept.** A player standing in their own companion party can accept a friend's invite.
- **Citizen guests.** `CitizenInviteDecision` handles citizen invites, and an accepted citizen takes a GUEST seat.
- **"Make room for..."** is added to the Crew window.
- **Chat verbs.** CompanionChatVerbs routes dismiss and logout+yes through the roster.
- **CompanionAuditor:** a 60 s drift audit with repair, plus `/runtime/companions/audit`.
- **Supporting pieces:** `RosterResult`/`RosterVoice`, and `COMPANION_EXP_SHARE` (the human's EXP is not diluted by companions).

## Live checks
Run 2026-09-28 with two clients: Jevelopers (Hadrik, Jinx, Sadie, Vex, Olwen) and ohboy (Brannock, Garrick).

| Check | Result |
|---|---|
| Call an own companion by party invite (Jinx, Sadie) and by the Crew window (Vex) | **Pass.** Jevelopers reached 5 in party with 0 drift. Vex's 3-letter name can't go in the stock invite box, which needs at least 4 characters, so it has to be called from the Crew window. |
| Merge: ohboy (own party with Brannock) accepts Jevelopers' invite | **Pass.** ohboy left his party and joined Jevelopers'. Brannock followed. Jevelopers went down to his fair share of 2: Sadie and Vex stepped out to camp with returnWhenRoom. |
| The leader expels the other owner (ohboy) | **Pass.** ohboy and Brannock re-formed their own party. Jevelopers' Sadie and Vex came back automatically (returnWhenRoom). |
| The leader expels the other owner's companion (Brannock) | **Pass.** Brannock went to the bench with 0 drift. Jevelopers stayed at share. |
| Owner leaves a friend's party | **Pass.** ohboy led a new party, and the travelling Brannock followed him. |
| A citizen accepts an invite (ohboy invites RedCookie, Lv20) | **Pass.** RedCookie joined as a guest: party ohboy, Brannock, RedCookie. |
| Guest steps out first on a merge over share | **Pass.** RedCookie stepped out first. |
| Auditor | **Pass.** 23 or more runs with driftNow 0, 0 repairs and 0 stuck. Every `/runtime/companions` read during the session showed drift 0. |
| Video | `docs/agents/evidence/video/p2a-two-friends.mp4` (45 s merge take). The "party window is your crew manager" take still needs a clean re-shoot. |

## Bugs found live (fixed in the P2a follow-up)
1. **A merge leaves the joining friend below their fair share.** The accept path rebalances right after the join, while the host's overflow is still seated. It then regroups the joiner's crew. About 8 s later Garrick (ohboy's second companion) walked out even though two seats were free, so ohboy ended with 1 of his 2 seats.
2. **The leader cap works from fair share, not from seats in use.** After the leader expelled Brannock, ohboy (cap 2 → 1, but only 1 companion out) could immediately re-call him ("Brannock is already on the way (~72 s)"). An expel only sticks after two.
3. **"logout" then "yes" in map chat did nothing** for the owner's rostered companions while in a shared party. No bot replied, and Hadrik and Jinx stayed ACTIVE. The chat log confirms both lines reached the server. Separately, a pending logout confirmation survives across the GM-hidden chat channel: the next "logout" cancelled it with Hadrik's "ok nvm, staying!".
4. **The Crew window can show "999% EXP banked"** (Olwen). Banked EXP should be capped, or shown as "ready to level".

## Found live, belongs to other sessions
**Debug text in player chat.** A pink line, "Jinx is warming map navigation cache, using fallback movement.", reaches the player. It comes from the navigation work in this tree, and that session has been told. **Resolved 2026-09-28** by the "Route navigation warmup notice to server log" session: `AgentNavigationWarmupService.notifyWarmup` now writes an INFO server-log line instead of messaging the leader. It is in the working tree (not yet committed) and takes effect at the next restart.

## P2a follow-up: the four live bugs, fixed and re-verified (2026-09-28)
The fix is `0711cfb58`, merged by fast-forward. Full suite: 7705 tests, still only the baseline's 29 failing tests. Each bug has a regression test that fails on the old code. Each fix was re-checked live with two clients (Jevelopers and ohboy):

| Bug | Root cause | Live re-check |
|---|---|---|
| 1. A merge left the joiner under their share | A leader cap outlived the owner leaving the party. Caps were cleared only on disband, and the party id was reused when the owner was invited back. | **Pass.** ohboy was expelled, left, called Brannock again in his own party, and merged back into the same never-disbanded party. The result was Jevelopers, Hadrik, Jinx, ohboy, Brannock, Garrick (6/6) with 0 drift. |
| 2. A leader cap didn't stick | The cap was a count of expels subtracted from fair share, not a cap on seats in use. | **Pass.** After the leader expelled Brannock, ohboy's "call Brannock" was refused with "The party leader sent one of yours out; that seat stays empty here." and Brannock stayed BENCHED. Caps are now absolute, min(share, in use) - 1, and they reset when the owner leaves or the party disbands. |
| 3. "logout" then "yes" did nothing | The yes was answered first by other handlers (a nearby Resident's accept fallback, or the Agent offer route), and filler words ("yeah", "ok") were normalized away before the check. | **Pass.** It worked even from GM-hidden chat. Hadrik and Jinx left the party ("Hadrik heads back to camp to train.", "Jinx>> i don't wait. a day, and you won't find me.") and ohboy's companions were untouched, with 0 drift. The logout question now expires after 30 s, and a newer "logout" asks again. |
| 4. The Crew window showed "999% EXP banked" | The percentage was capped at 999. | **Pass.** The row reads "Olwen, Lv12 Beginner - train @ Lith Harbor - ready to level". |

**Remaining P2a items:**
- **Sign-off.** The user's immersion sign-off on `p2a-two-friends.mp4`.
- **Re-shoot.** A clean re-shoot of the "party window is your crew manager" clip.

---

# P2b results (2026-09-28)

## Branch and suite
P2b is merged into `feat/companion-manager` by fast-forward to `4b1394786`. The live run then found three bugs, fixed as `472efa66d`, `15d17a4bb` and `904fa8748`.
- **Full suite:** 7920 tests at `904fa8748`: 25 failures and 4 errors, the identical 29 baseline tests (set-compared with the P0 list). There are 0 new failures.
- **Living-world suites:** all green after each live fix.

## What P2b adds
- **Join-then-travel.** A called companion loads at its camp on the owner's channel without follow and joins the party at once. The party window lists it on its camp map. Its row is TRAVELLING with `eta_at` saved, and it whispers its ETA. At the ETA it comes into the owner's current map at the portal nearest them, walks over, and only then follows.
  - If the owner is inside an event instance at that point, it waits outside instead (HOLD).
  - The recall ETA is capped at 60 s on Victoria Island (`RECALL_MAX_ETA_MS`, decision 7).
- **Atomic swap.** The incoming companion is loaded first. The outgoing one leaves and the incoming one joins under one cause, so the party never has a gap and never goes over 6.
- **HOLD and rejoin**, plus in-memory **Commitments**. While committed, bench, swap and delegate are refused with "after the run."
- **Follow guard** (`AgentFollowGuard`, an owner-free seam). No follow path may start or warp a TRAVELLING, HOLD or AWAY companion.
- **Portal-true follow.** A companion enters a map at the point its owner came in, a few hundred ms apart, then walks over.
- **Logout and login (decision 8a):**
  - Logout saves the usual lineup.
  - In the owner's own party, companions stay listed as offline members, PARKED. In a shared party they leave at once.
  - After `LEADER_OFFLINE_BENCH_MS`, a sweep benches them. Their bench credit starts at the grace deadline.
  - A login within the grace period brings them back 2-8 s apart, walking in. A later login calls the usual lineup with travel ETAs.
- **CompanionChannelFollow.** Companions move to the owner's new channel 1-3 s apart. A fallback moves any stragglers after 30 s. Cash Shop visits are handled.
- **Boot reconciliation.** ACTIVE, TRAVELLING and HOLD rows become PARKED.
- **Instance-logout handoff seam (decision 8b)** and **CompanionSeatState** (the table of legal row changes).
- **ROSTER_CONTRACT_MODE** defaults to PERMANENT_ON_ROSTER (decision 3). A walk-out that comes due during a run waits until the run ends.
- **Roster deletion cleanup**, and bench loads moved onto the companion-ops thread.

## Live checks
Run 2026-09-28 19:30-20:45 on four restarts. The clients were ohboy (ohboyohboy) and Crewfriend (crewfriend24, a GM6 test account created for this run at the user's request). The first run also used Jevelopers.

| Check | Result |
|---|---|
| Boot reconciliation (restart with companions out) | **Pass**, three times. ACTIVE, TRAVELLING and HOLD became PARKED with `parked_at` = boot time and the lineup order kept. A restart during a trip (Hadrik, TRAVELLING) became PARKED. |
| Owner changes map twice during a trip | **Pass (guard).** Jevelopers warped to Henesys and then to Perion during Hadrik's trip. Hadrik stayed at his camp and was never warped. His arrival exposed bug 1 (below). |
| Arrival at the ETA, walking in from the portal | **Pass** after fix 1. Brannock came in at 19:57:27 (ETA :27) and Garrick at 19:57:31 (ETA :31), each at the portal nearest ohboy, then walked over. Fenna and Ilse arrived on time after a channel change (20:41:29 and 20:41:35). |
| Login after the grace period | **Pass.** ohboy's companions had been PARKED since 19:30 and the sweep benched them at 19:50. On ohboy's login at 19:56, the usual lineup (Brannock, Garrick) was called with join-then-travel ETAs, joined at once, and arrived on time. |
| Logout and login within the grace period (own party) | **Pass** after fix 2. The logout at 20:09:24 made both PARKED with the lineup kept. On relogin they walked back in from the portal. The party was ohboy, Brannock, Garrick: no duplicates and 0 drift. |
| Portal-true follow | **Pass.** ohboy went through Kerning's west portal into 103010000 at (1556, 307). Garrick appeared at that exact point about 2 s later and walked into formation. |
| Channel change with 4 companions (2 ACTIVE, 2 TRAVELLING) | **Pass** after fix 3. At 20:40:58 ohboy changed CH 2 to CH 1, and all four were on CH 1 in the party by 20:41:07 (about 9 s; the target is 30 s). The travellers kept their trips and arrived on time. 0 drift. An earlier two-companion run, CH 1 to CH 2, took 3-5 s. |
| Atomic swap ("swap garrick for ilse") | **Pass.** The party went from ohboy, Brannock, Fenna, Garrick to ohboy, Brannock, Fenna, Ilse with no gap and never above 6. Ilse joined at once as TRAVELLING and arrived at her ETA. |
| Merge with two humans at full party | **Pass.** ohboy (4 out) accepted Crewfriend's invite (Crewfriend with 2 out). The party was Crewfriend, Halvard, Kettle, ohboy, Brannock, Fenna (6/6). Ilse stepped out with returnWhenRoom. 0 drift. |
| Leader logs out with a friend in the party | **Pass.** Crewfriend (leader) logged out at 20:44:45. Halvard and Kettle left the party at once as PARKED roster-only rows. ohboy became leader (star in the party window). ohboy's Ilse was called straight back into the freed seat. 0 drift. |
| Crew in the party window while travelling | **Pass.** Crewfriend's window listed Halvard and Kettle greyed out on their camp maps, and "Kettle is on the way (~60 s)." showed in pink. |
| Video | `docs/agents/evidence/video/p2b-walk-in.mp4` (28 s): Brannock and then Garrick walk in from Kerning's west portal at their ETAs, then Garrick walks in after a login within the grace period. It awaits the user's sign-off. |

## Bugs found live and fixed
1. **Trips never arrived (`472efa66d`).** `arrive()` matches the TRAVELLING row by `eta_at`, but that column is a whole-second MySQL TIMESTAMP. The stored value never equalled the in-memory millisecond `arrivesAt`, so every trip stayed TRAVELLING (Hadrik was still at camp 22 s after his ETA). A new trip is now due on a whole second. The test store now rounds `eta_at` and `parked_at` the way MySQL does, and 11 travel tests fail without the fix.
2. **A reloaded companion lost its party row (`15d17a4bb`).** An Agent load does not restore the party it is still listed in; a human login does, in `loadCharFromDB`. `joinOwner` now gives the companion that row back before joining, so the join is ALREADY_MEMBER and it isn't listed twice. The harness now models this, and 12 channel-follow and login-restore tests fail without the fix.
3. **Channel follow's rejoin had no seat (`904fa8748`).** Live, logging off on the old channel took the companion off the party list, so its join on the new channel was refused by the seat gate (`companion.unseated_join`). Only the 30 s fallback moved it. The move now holds a TRAVEL seat. The harness's fake join now demands a seat like the real gate. Every existing test still passes, and the new test fails without the fix.

## Not verified live
- **HOLD and the instance-logout handoff (8b).** Companions cannot enter an instance until P6, so both are unit-tested only.
- **Cash Shop visit, and a 30-minute scripted drift session.** Not run.
- **"Every flow twice":** met for arrivals, restore, channel change and boot reconciliation, but not for the leader-logout and swap flows.
- **Graceful shutdown skips the logout path.** The rows stayed ACTIVE/TRAVELLING until boot reconciliation parked them. That is the designed restart behaviour, but no goodbye line is said.

## Found live, belongs elsewhere
- **Companions run off to resupply as soon as they arrive,** and wander Kerning's maps for 10+ minutes (Sadie and Vex; later Brannock and Fenna). They go 103000002 → 103020200 → 104010000 → 103020100 without reaching the shop, and follow is off while they do. This is the agents' supply navigation, and it was reported to the Kerning-routes session. It is also an immersion problem: a crew that walks in and leaves at once.
- **GM characters log in hidden, and the v83 client sends no NPC click while hidden.** It is client-side, like Dark Sight, and not a server bug. `!hide` fixes it. It explained the "NPC talk does nothing" report from another session.

---

# P3 results (2026-09-29)

## Branch and suite
P3 is merged into `feat/companion-manager` as `888b21cfc`. The live run then found three bugs, fixed as `f297730c5`, `41af7f5da` and `17f5ee125`, and deployed 04:28.
- **Full suite (P3 fix stage):** 8043 tests: 25 failures and 4 errors, the same 29 baseline tests. There are 0 new failures.
- **Living-world suites:** all green after each live fix.

## What P3 adds
- **LivingTalk:**
  - A number box, the stock `getNPCTalkNum` window, checked with the same rule as `NpcPromptState`.
  - Back: the last option on menus, Prev on OK pages, and the cancel path on number boxes.
- **CompanionViews:** a per-owner view cache, so a render reads no database. Its versions drive the stale-click guard.
- **CompanionManager:** one facade for every menu action. Crew-wide orders reuse the existing agent chat paths (gather, rebuff, formation, follow, wait), and single-companion orders use the stock whisper path.
- **Companion card**, state-aware:
  - Stick with me / Wait here / Hunt here while I'm busy / Take a break at camp / Let someone take your place... / What you do at camp... / Camp somewhere else... / The whole crew... / Your haul... / Your wages...
  - Let me see your gear and Let's trade, offered only while the companion is on your map.
  - We should part ways.
- **Bench card:**
  - Duty picker with projected rates.
  - FARM_ITEM picker with a count box.
  - Camp picker (level-appropriate fields).
  - Haul: hand over / Duey / bank / wear your best.
  - Wages status and pay by number box.
  - Release confirm showing bond and haul.
- **Crew window:**
  - Party n/6: travelling, waiting-outside and offline rows, and guests.
  - Bench (x of y bench seats).
  - Actions: usual lineup call and save, "Everyone: follow me / wait here / gather up / rebuff", Formation..., "Everyone on the bench...", "Make room for...".
  - Speaks in your own avatar from the book, or in the companion's voice (with Back) when opened from a card.
- **Adventurer's Roster:** granted on the first companion, and it cannot be dropped.
  - The Custom.wz leaf rename to "Adventurer's Roster" is **staged, not shipped** (`scratchpad/p3-roster-item/`, RUNBOOK). Probe 3 (kaentake's Serialize hook overrides string leaves) is settled from the source and the live client log, but not yet in a client.

## Live checks (ohboy, all by mouse; 2026-09-29 03:43-04:25)
| Check | Result |
|---|---|
| Roster book opens the Crew window | **Pass.** With 3 out: party and bench blocks, own avatar. With everyone benched: "Party 1/6 (nobody out with me)", "Bench (4 of 10 bench seats)", "Call my usual lineup". |
| Call my usual lineup | **Pass.** From all-benched, Brannock, Fenna and Ilse joined at once as TRAVELLING and arrived at their ETAs. |
| Bench card and swap ("Swap in for...") | **Pass.** Garrick asked "who should i take over from?", then "Ilse heads back to camp to farm meso. Garrick is on the way (~60 s)." Garrick walked in at his ETA. |
| Crew window from a card | **Pass.** It speaks in Garrick's voice and shows "Garrick - on the way (0:45)". Clicking a row opens that companion's card, with Back to the Crew window. |
| Duty picker and camp picker | **Pass.** Rates show: "Farm meso (~56k meso/h after upkeep)", "Rest (no upkeep)". Farm meso from town asks in character "i can't do that in town. where should i go?" and lists level-32 fields. The row became FARM_MESO @ 106000002, then TRAIN. |
| Crew-wide orders | **Pass.** "Everyone: wait here": all 3 stopped following (live probe). "Everyone: follow me": all 3 following again. |
| Card orders and pages | **Pass.** Haul and wages pages. The wages number box ("how many hours? 450 meso an hour; you have 5,325.") paid 6 h. Gear opened the stock Character Info. Trade opened the stock trade window ("what's up"). |
| Release (flow 10) | **Pass.** "Part ways with Ilse for good? Bond: none yet / Haul: nothing" in your notes. No kept her; Yes gave "Ilse heads home to Henesys.", the row was removed and `roster.release` logged. She was then granted back for testing. |
| 55 consecutive menu actions | **Pass.** 11 rounds of book -> row -> More... -> Back -> End Chat. The client stayed online, the log had no errors, and the book opened normally afterwards. |
| Hotkey | Not applicable. Probe 2: a 243 item on a hotkey sends nothing, so the book is opened by double-click. |
| Video | `docs/agents/evidence/video/p3-rotate-and-duty.mp4` (78 s, captioned, zero typing). It awaits the user's sign-off. |

## Bugs found live and fixed
1. **Bench rows named the wrong place (`f297730c5`).** A companion benched while still walking out read "train @ Kerning City Construction Site". The row now names its camp.
2. **Valley recalls took 180 s (`41af7f5da`).** Maps in areas 106 (Deep and Dangerous Valley, Burnt Land) and 107 (Swamp of Despair) were missing from RecallTravel's island graph, so a camp the picker itself offers counted as off-island.
3. **"Things changed" refused a valid click (`17f5ee125`).** The stale-click stamp was owner-wide, so another companion's arrival, or a bench-ledger write, made every open card stale. Cards and their pages now use a per-companion version. The Crew window and swap picker keep the owner-wide one.

## Not done in P3
- **Roster item name ship.** It needs the user's go-ahead, because it changes every friend's client. Order: the RUNBOOK side-folder test, then ship, then a clean second client, then a rollback rehearsal. Until then the book reads "Agent Summon".
- **Open policy for P3b:** companion wallet reserve. Companions carry too few mesos for the journey reserve, so their shop trips buy nothing.

---

# P3b results (2026-09-29)

## Branch and suite
- **Merge:** P3b is merged into `feat/companion-manager` as `6fee7bfdf`, from branch p3b: core `cc5396cd1`, screens `96f991303`, review fixes `2596d0a2a`.
- **Follow-up:** `407512dd1` exempts companions from the journey meso reserve.
- **Migration:** changeset `41-companion-crew`, table `living_crew` (the crew formation per owner), applied on boot.
- **Full suite (fix stage):** 8134 tests, the same 29 baseline failures, 0 new. After the merge the living-world, loot, potion, economy and claim-guard suites pass. The lint suites fail with exactly their baseline tests and do not mention the new classes.
- **Pending work committed:** `be09e3e9b` holds the other sessions' pending work, committed at the user's request before the merge.

## What P3b adds
- **CompanionSettings** in `living_roster.settings_json`. It stores only the values that differ from the defaults (HP 70 / MP 50, loot everything, boss drops left to people, buffs and heals on, buff potions off, crew formation). The parse is defensive.
- **Owner-free agent seams** (null rule = today's behaviour):
  - `AgentPotionThresholds`: every read of the autopot threshold goes through it; Berserk still applies on top.
  - `AgentLootPolicy`: ALL / OWN_KILLS / QUEST_ONLY / NONE plus leaveBossDrops. It only refuses drops, and does nothing inside event instances.
  - `AgentWalletReserve`: companions out with their owner keep no journey reserve.
  - `AgentChatToggleHook` and `AgentFormationHook`: chat toggles and chat formations write through to the settings.
- **CompanionSettingsApplier**: applied on every load and seat path (call and arrive, swap, login restore, channel follow and its fallback, hold and auditor rejoins, boot restore). It answers only while the owner leads the companion.
- **Screens** on the card and the bench card:
  - **Fighting style...**: where I stand (with the others, or five own formations) and support toggles that depend on the job.
  - **Supplies & loot...**: what it carries, HP and MP potion % by number box (10-90), what it picks up, the boss-drop toggle, and "Here's some coin for supplies" by number box. Coin is one economy transaction, and a stale session is refused.

## Live checks (ohboy, all by mouse; 2026-09-29 08:58-09:20)
| Check | Result |
|---|---|
| Pages render | **Pass.** Bench card and card both list "Fighting style..." and "Supplies & loot...". Supplies: "i have 5,727 meso. i'll count my potions when i'm out with you." on the bench; "i carry 1,597 meso, 9 Blue Potions, ..." when out. Fighting style: "how should i fight?"; a Fighter gets no "Party heals". |
| Different potion % per companion | **Pass.** Brannock at 40 ("ok, i'll drink one below 40% HP.") and Fenna at 85 through the number box. Live probe: autopot HP alert Brannock 0.4, Fenna 0.85, Garrick 0.7 (default). |
| Loot policy | **Pass.** Fenna "Nothing": live policy mode=NONE. Her meso rose only by the stock party meso split (+45, the same as the others). |
| Formation | **Pass.** Garrick "Keep to my left": "ok, keeping to your left.", stored `{"formation":"left"}`. |
| Coin for supplies | **Pass.** 500 meso: "You give Fenna 500 meso.", owner -500, Fenna +500, and she whispered "thanks. that'll keep me in potions." |
| Settings survive bench then call | **Pass.** Fenna 0.85 / NONE after the bench and re-call. |
| Settings survive relog | **Pass.** 0.4 / 0.85 / 0.7 and NONE after logout and login within the grace period. |
| Settings survive restart | **Pass.** Same values after a server restart (boot reconcile, then login restore), with 0 drift. |
| Wallet reserve | **Pass.** After deploy, the live reserve for Brannock, Fenna and Garrick is 0; control citizen Mirel is 1300. |

## Not done / notes
- **Loot policy only works outside instances.** Leaving boss drops inside boss runs is deferred to P7 (the lint forbids the imports this would need). The toggle reads "outside boss runs".
- **Stale-owner check not ported:** `RosterService.payWages` (from before P3b) has the same stale-owner pattern that coin now guards against.


---

# P4 results (2026-09-29)

## Branch and suite
- **Merge:** P4 is fast-forwarded into `feat/companion-manager` as `8c335de00`: core `68d2e978e`, offers `38b9971f0`, hire list + allocation `4e6ed6a13`, integration `5aaf7abf2`, review fixes `8c335de00`.
- **Full suite (fix stage):** 8306 tests, the same 29 baseline failures, 0 new. Lint suites: the same failing tests and messages (HashSet ordering aside). No file under `server/agents` changed.

## What P4 adds
- **HiredHands:** a citizen hired into the roster as a synthetic persona `hire:<cid>`. Name and look come from its own `characters` row; `ResidentLooks`, `ResidentWardrobe` and `ResidentShaper` skip hires, so its face and gear never change. Hires count toward `ROSTER_MAX`; `HIRE_MAX_PER_OWNER` defaults to 10 (raised from 5 by the review: without Residents a friend could otherwise not fill 5 active plus a bench).
- **In-place adoption:** a loaded citizen is taken over where it stands (population session handed over without a despawn, runtime re-registered under the owner, a GUEST seat becomes its companion seat with no leave or join).
- **Population skips:** the `hired` flag (one writer, `Population.setHired`; reconciled against the roster at boot and daily) keeps presence, the progression ledger, Churn, the social fabric, market and chatter away from a hire. Churn backfills the town. Release clears the flag and `employed_by` and returns the citizen.
- **Terms window** (Accept/Decline under the Bulletin Board): name, level, job, fee (1,000 meso per citizen level) and wage (the bench upkeep, 30 meso per level per hour). The fee is paid atomically and refunded if no row is written.
- **Invite personality:** recruiters and famers accept anyone; reserved citizens decline strangers; easygoing ones decide on a fixed roll per inviter (familiar faces are welcome).
- **Party-time offers:** after `HIRE_OFFER_PARTY_MINUTES` (10) partied on one map, a guest may offer to stay (one whisper, a 10-minute pending offer at the top of the hire list; double-click or whisper yes/no answers it).
- **Hire list:** "Adventurers looking for a crew..." in the Crew window: citizens you have met, level-matched and welcoming you, pending offers first, then online ones, then "usually around <town>".
- **ResidentAllocation:** at most `RESIDENT_CAP_PER_OWNER` (5) Residents per friend; a daily sweep releases an owner's Residents and hires after `RESIDENT_OWNER_INACTIVE_DAYS` (14), with one pink line on their next login.
- **GM:** `!grantcompanion release hire:<cid>`; `!citizens retire` refuses a hired citizen.

## Live checks (ohboy + Crewfriend, two clients, all by mouse; 2026-09-29 13:33-15:19)
| Check | Result |
|---|---|
| Can't pay | **Pass.** ohboy with 6,092 meso accepts SilentCat's terms (18,000): refused in character, no charge. Test characters then topped up to 300,000 by SQL while offline. |
| Hire from the list | **Pass.** "Adventurers looking for a crew..." lists 13 met, level-matched citizens (online ones by town, others "usually around <town>"). Faro723 hired for 18,000 meso: roster row `hire:744` ACTIVE, `employed_by` 2, `hired` flag set, party 5/5. |
| Look unchanged | **Pass.** Faro723's `characters` look (gender, skin, hair, face) and its five worn items are identical before the hire, after it, and after bench -> call. |
| Bench and call a hire | **Pass.** Card works like any companion's ("Lv18 Thief - 44 Red Potions", Bulletin Board portrait); bench walks out by portal; "Come join me" brings it back with the same face. |
| Hire survives relog and restart | **Pass.** PARKED at logout, restored ACTIVE after login (Faro723 ~25 s into the staggered restore); all hires restored after a server restart. |
| Second account hires at the same time | **Pass.** Crewfriend (party with its own Halvard and Kettle) invites Anvith as a guest, hires it from its own list for 26,000 (300,000 -> 274,000), no leave or rejoin. Both owners' flags match their roster rows exactly. |
| Invite personality | **Pass.** Outgoing citizens (recruiter/famer) accept strangers; LilLuis (reserved) and QuietPig decline ("... have denied request to the party."); a citizen in its own party answers "Already have joined a party." |
| Party-time offer | **Pass.** Liriki (guest since 13:45) offered at 13:54:49 after 10 party minutes ("this was fun. want me to stick around?"), shown on top of the hire list as "(offered to stay)"; accepted for 15,000. |
| Guest row -> hire (clip) | **Pass** after fix 2. SilentSin invited, joins as guest, its Crew-window row opens its terms (14,000 fee, 420 meso/h), accepted: stays in the party and is crew. |
| Release | **Pass.** Crewfriend parts ways with Anvith: confirm shows "Bond: none yet / Haul: nothing / Purse: 214,812 meso (stays with Anvith)"; "Anvith heads home to Henesys.", stock "left the party", row gone, `employed_by` cleared, `hired` flag cleared. |
| Town population at target | **Pass** after fix 1. The hire at 15:17:49.9 was replaced by a newborn citizen (SkyMage) at 15:18:04.8, the next presence sweep; 600 of 600. |
| Drift auditor | **Pass.** 30+ runs, 0 drift, 0 repairs. |
| No exceptions from `hire:` ids | **Pass** for the session (~2 h). The only exceptions were client connection resets and 4 background-KPQ admission rollbacks of unhired citizens (agent layer, older than P4). |

Clip: `docs/agents/evidence/video/p4-stranger-to-crew.mp4` (83 s, captioned): invite from the party window -> guest -> Crew-window row -> terms -> accept -> walks over.

## Bugs found live and fixed (`c273ddfe4`)
1. **Town not backfilled at once.** Churn replaced a hired citizen only at BIRTHS_PER_DAY (about one per 72 min). `Population.setHired` now tells Churn, and the next sweep births one newcomer per hire, never past the target. Tests: `PopulationSkipTest.everyHireIsReplacedAtTheNextSweepNotAtTheBirthsPerDayPace` (fails with the notice removed) and `aHireInATownAlreadyAtTargetIsNotReplaced`.
2. **Guest row drawn over the row above.** The v83 client places a plain line after a `#L` row only about half a line lower, so "Name ... (guest)" overlapped the last party row. A guest is now a row like the others and opens its hire terms (as double-clicking your own guest does). `CrewScreensTest` updated.

## Looked like a bug, is not
- **"Teleport" of a far-away hire.** Two hires far across the map reappeared ~2,000-3,800 px away beside their owner. Cause: the route planner uses the stock hidden in-town portals (Kerning `ph03_1` -> `ph03`, Henesys `hp00_1` -> `hp00`) as cost-0 shortcuts, deliberately (existing navigation tests pin it). Players use the same portals, and the hire comes out of a portal and walks over, as P1.5 arrivals do. A fix forcing on-foot routes (34 s instead of 3.5 s in Henesys) was built on branch `p4-teleport` (`6e2f1e1d3`) and **not merged**.

## Not done / notes
- **Exit criterion "5 active + 2 bench for two accounts" not run to the full count.** ohboy reached 5 out (3 hires) + 2 bench; Crewfriend reached 3 out. Supply is not the limit: each list showed 10+ candidates.
- **Offer cooldowns and pending offers are in memory** (a restart forgets them).
- **Registrar clerk NPC** (decision 4 c) not built, as planned.
- `living_citizen.joined_at` for trickle births (e.g. Givina 18:50:50 while local time was 15:18) looks written in GMT; older population behaviour, not P4.

---

# P5 results (2026-09-29 / 30)

## Branch and suite
- **Merge:** P5 is fast-forwarded into `feat/companion-manager`: core `0418b3605`, duties `5478c5251`, learning `4f2d23195`, rotation `a3a551fdf`, integration `50a640895`, review fixes `f2fdc1b19` (all 28 review findings, 11 major).
- **Live fixes:** `beabaca8a`, `428fff2cb`, `2c38a4336` (below).
- **Migrations:** changesets `42-companion-bench-learning` (`companion_yield`, `living_stall_stock`, `living_roster.your_call`) and `43-companion-crew-fresh`, applied on boot.
- **Full suite** on `beabaca8a`: 8469 tests, 25 failures + 4 errors, the same 29 as the baseline. The living-world tree (1197 tests) passes on `2c38a4336`.

## What P5 adds
- **BenchDutyDispatcher:** one place a benched companion goes live, one form per duty. Pools: BENCH_LIVE (2 per owner, server cap), BENCH_TOWN for REST (1 per owner, 4 total) and COMMERCE for stalls (1 per owner, 2 total). Owners online go first; live forms run on channel 1 only. Harvest counts only what the companion picks up. Flow 12: a death ends the slice after the stock revive and the companion rests one slice; running out of potions ends the hunt and the shop resupply runs before the next slice.
- **Visible duties:** TRAIN / FARM_MESO / FARM_ITEM grind at the camp; GATHER picks up quest drops; STALL (and SELL) opens a real Free Market shop from the haul at market-index prices, with sales back to the haul and stock kept durable across restarts; REST is town life in the camp's town.
- **Learning:** `companion_yield` rates per job branch, level band and camp, shrunk toward the ledger's prior. "Your call" is a standing UCB bandit over duty and camp. Pickers show learned rates; the card shows live, out of sight, resting, stall and resupply states; one call-back report whisper.
- **Upkeep** is capped at 15% of the predicted meso/h; hire wages follow the same rule.
- **Bench combat learning:** LEARN mode only while no human is on the map, into candidate tables that live play never reads; adoption only through `!combatarena <agent> job=<job> policy=candidate`.
- **"Keep my crew fresh"** (off by default, in the Crew window): a rested bench companion swaps in for one that is down, out of potions or 10+ levels off the owner, and says why in party chat.

## Live checks (ohboy + Crewfriend, two clients; 2026-09-29 22:15 - 2026-09-30 05:21)
| Check | Result |
|---|---|
| A companion grinds at its camp | **Pass.** SilentSin farmed at Tree Dungeon, levelled there and ended at its owner's level by the rule. Hadrik and Jinx grind at their camps (clip). |
| Buy from a STALL companion's real shop | **Pass.** Ilse: "blue snail shell and more, fresh from camp" in FM room 4, 3 listings at market-like prices. ohboy bought 40 Orange Mushroom Caps for 400 meso: his purse -400, Ilse's haul +400 exactly, the caps left the stall stock, the rest stayed listed. |
| Watch a REST companion in town | **Pass.** Garrick rests in Perion (his camp's return town) and chats ("cold up here."). |
| Call one back and read its report | **Pass.** "SilentSin>> farmed 21k meso at Tree Dungeon, Forest Up North III." |
| See an auto-rotation in party chat | **Pass.** Seven seconds after the toggle: "Liriki : i'm closer to your level. i'll take Fenna's spot." |
| Duty and camp pickers | **Pass.** "(out of sight)", level-appropriate camp list, "i can't do that in town. where should i go?", "~25k meso/h after upkeep", "(1 hunt measured at Excavation Site I...)". |
| Coin for supplies unblocks a broke companion | **Pass.** Casse "out of potions and meso" on the card and Crew row; 5,000 coin, next tick she went live and farmed. |
| Two accounts, 5 active + bench | **Pass.** ohboy 4 out + SilentSin away (seat kept) + Garrick and Ilse on live duties; Crewfriend 4 out + Casse away + IceCookie and Lioren on the bench (2 hires from its list). |
| Soak, 1 h at -Xmx2048m (04:15-05:19, 129 samples) | **Pass.** Queue-lag p50 29 ms, p95 55 ms median / 59 ms max (target < 100), one p99 spike to 411 ms; LOW_PRIORITY lag p95 20 ms (one 116 ms); old gen after GC 311-421 MB; 0.12 cores; 0 double loads; no OOM. Load was light: at 04:00-05:00 New York time the population's own schedule keeps most citizens offline (14-52 agents vs ~230 in the evening). |

Video: `docs/agents/evidence/video/p5-bench-alive.mp4` (122 s, captioned): a grinder at camp, a real FM stall and a purchase, a rest in town, "Keep my crew fresh".

## Bugs found live and fixed
1. **Meso harvest always 0** (`beabaca8a`). Mob meso on this server goes to the top damager at the kill (`MapleMap.dropFromMonster`) and never through `Character.pickupItem`, so the loot hook never saw it. Both grants now report to it; `MesoLootHookBoundaryTest` pins every meso grant (fails without the fix). Live: "+1326 meso, 2 stacks" for Casse.
2. **Recall to an owner in the Free Market took 180 s** (`beabaca8a`): FM rooms joined the road graph through each town's portal. `RecallCapTest`.
3. **"ok, i'll rest at The Burnt Land I"** while resting in Perion (`beabaca8a`): the confirmation names the rest form's town.
4. **A broke companion died every 20 min for hours** (`beabaca8a`): Sadie, 0 meso and no potions, loaded, failed to buy, died, rested, repeated. Field slices now stay in the ledger until it can supply itself; card "out of potions and meso", Crew row "out of supplies". `BenchDeathResupplyTest`.
5. **Companions loaded at their stock-revive HP and died in seconds** (`428fff2cb`): Jinx 15 deaths in 16 slices, Hadrik and Sadie the same. A slice now loads its companion healed. Test fails without it.
6. **Plain headings drawn over the option above them** (`428fff2cb`, "What I pick up:" over "Drink an MP potion below 50%"): `LivingTalk` puts a blank line before a heading that follows an option. `LivingTalkTest`.
7. **The default camp was the first fit even when measured deadly** (`2c38a4336`): Drake's Meal Table for a Lv50 thief measured 43 deaths/h. The default camp is now the best of the top 5 fits under 2 deaths/h, else the ledger. After it: Hadrik and Jinx 0 deaths in 6 slices.

## Not done / notes
- **Exit "ledger and live EXP/h within +-25% after 5 slices per camp"** is not measured yet: no camp reached 5 slices in the session.
- **A camp the owner (or an old grant) set** is kept even when measured deadly: Casse (Lv24 magician) died 3 of 4 slices at Excavation Site III (20 deaths/h). Only default camps skip deadly fields; "Your call" and the picker's rates show the risk.
- **The Crew row of a REST companion** still says "rest @ <camp>", not the town it rests in (the card and the confirmation are right).
- **The call-back report tally is in memory:** it covers only the time since the last restart (SilentSin's haul held 102k; the report said 21k).
- **Found, not P5:** a citizen save fails when its `retires_at` falls in the US spring-forward gap (2027-03-14 02:28, "Incorrect datetime value"), so that row stops persisting; filed as its own task. The server was also found stopped at 19:43 on 09-29 with no shutdown log; no session or P5 agent stopped it, cause unknown.
- **Test setup:** ohboy's HP restored and ohboy/Crewfriend moved by SQL while offline; Crewfriend granted Casse, Dorran and Lioren (`!grantcompanion`) and hired HappyWolf and IceCookie; stock for Ilse's stall was put in her haul by SQL (3 ETC stacks).

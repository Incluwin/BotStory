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
**Debug text in player chat.** A pink line, "Jinx is warming map navigation cache, using fallback movement.", reaches the player. It comes from the navigation work in this tree, and that session has been told.

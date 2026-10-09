# Companion plan for the other bosses (P11 to P20), version 2

## Result

This plan adds companions to 5 more expeditions, 7 party-instance bosses and about 35 field bosses, in ten phases from P11 to P20. Version 2 folds in the decisions and facts of 2026-10-08.

- **All 25 decisions are taken.** You approved every recommendation. They are listed in "Decisions taken". One answer changed: the potion standards now scale up from live evidence (see "Boss supply standards").
- **Zakum needs far more than 200 potions.** P8 now sets 500 HP and 400 MP potions plus 4000 projectiles. Scarga, Showa, Horntail and Pink Bean need more again.
- **P7 is live and P8 is being built.** P8 builds the mirror core, the control-steal block (G2) and the gear tool (G13). It also builds part of the never-target list (G3), the immunity stop (G4), the run lifecycle (G7), the quota (G8), restock (G9) and loot (G10). P12 shrinks to the rest.
- **P11 (Normal Balrog) runs its live check on crew B (ohboy's test crew) at Lv100.** So it no longer has to come before the P8 Zakum check. Crew A (Bossfriend's test crew) goes straight from Lv70 to Lv160 for P8.
- **I re-checked every boss for a Zakum-like door.** Only Papulatus closes a gate at the start. Scarga and Horntail let a dead member back in by NPC. Showa and Pink Bean NPC re-entry is broken by a name mismatch. Pink Bean revives inside.
- **Every big boss phase now has a client memory gate.** The human's 32-bit client crashes in its sound system when memory runs out. The large-address-aware client passed one 20-minute Easy Balrog fight on 2026-10-08 with no crash (VM peak 3,982 MB). You approved shipping it and the Kaentake exit fix. The deploy session waits for your direct yes before it ships them.
- **Boss supply standards now count HP and MP points (2026-10-08).** The Easy Balrog run was lost when the HP potions ran out. Branch fix/easy-balrog-supplies sets Easy Balrog to 130,000 HP, 32,000 MP and 2,000 projectiles. Later phases write their standards the same way (see "Boss supply standards").
- **There are 3 open questions for you: 1, 5 and 6.** Questions 2, 3 and 4 are settled.
- **The phase names P11 to P20 and the effort sizes do not change.**

## Questions for you

Terms in these questions (the full list is in "Words used"):

- **Mechanics run:** a live check with crew A at Lv160. It proves that the rules work.
- **LAA client:** a large-address-aware copy of MapleStory.exe. It gives the 32-bit client 4 GB of address space instead of 2 GB.
- **Kaentake exit fix:** a small Kaentake.dll fix. It stops a second crash while a failed client closes.
- **msexcr.ini:** the client's own crash log.

1. Should the seven Lv60-77 bosses ship after their mechanics run alone, and be tuned later from friends' first runs? No test crew stays near Lv60-77 now, because crew B goes to Lv100 for P11. The seven are Mushmom, Dyle, Zombie Mushmom, Zeno, Nine-Tailed Fox, Tae Roon and King Sage Cat. The other option is a third test crew. I recommend shipping after the mechanics run. If you do not answer, I keep shipping after the mechanics run.
2. Settled: the LAA test. On 2026-10-08 the LAA client ran one full 20-minute Easy Balrog fight with 9 members and did not crash (VM peak 3,982 MB). Before the change, every Easy Balrog fight crashed the client. Each later big-boss live check still records the client's memory.
3. Settled: you approved shipping the LAA client to friends if the test stops the crashes. The deploy session ships it after your direct yes.
4. Settled: you approved shipping the Kaentake exit fix after a local test. That test was clean. It ships in the same client update.
5. If the first live Scarga run shows companions running dry, may companions that pass a big boss's level gate get a larger Use tab? Horntail and Pink Bean need 27 and 37 supply stacks, more than 24 Use slots hold. Players buy the same expansion. A larger Use tab does not lift the 5000-projectile bound (Question 6). I recommend yes. If you do not answer, I keep yes.
6. If the first live Scarga run shows companions running out of projectiles, may the 5000-projectile bound rise for boss keys only? Horntail's scaled 7000 and Pink Bean's 10000 cannot load under it (agent-engine.yaml:4532). Party quests and hunting keep 5000. I recommend yes. If you do not answer, I keep yes.

## Words used in this plan

- **Mirror:** P8's attack mirror, in two parts.
  - `BossAttackMirrorSeam` (core) decodes a boss attack from the controlling human's MOVE_LIFE packet. It accepts only raw activity 24-41.
  - `AgentBossAttackMirror` (server.agents) applies that attack to bots inside its WZ range, through a new `ServerMobDamageService.applyOrdinaryAttack` overload (p8_plan.md section 2).
- **OBSERVE mode:** a mirror mode (`AgentBossAttackMirror.MODE`). It logs the decoded attack, its shape and the predicted targets, and changes no HP (p8_plan.md:307). APPLY is the mode that deals damage.
- **companion-ops:** the living world's companion worker thread (`CompanionExecutor`). Roster and companion work runs there, off the game threads.
- **Rule A, rule B and rule C:** the three run rules of G7.
  - Rule A: when no human is left in the run, companions leave and rejoin outside (P7).
  - Rule B: when the owner leaves, before or after a clear, the owner's party companions leave too, unless a same-party human stays. Bench allies stay (P8 for Zakum, p8_plan.md step 13 and question 8).
  - Rule C: registered companions stay on the recruit map until the start (P7).
- **Server authority:** the server runs the boss's moves and attacks through a `BossActorBehavior` (SERVER_STICKY). P7 forces it on Easy Balrog while companions are registered.
- **Native control:** a human client runs the boss. Without the mirror, bots then take only touch damage and the skills the controller casts.
- **Control steal:** a bot hit moves an unprotected boss to Agent physics. The boss then stops attacking anyone.
- **G2 guard:** P8's `AgentBossScope.guards`. It names the boss parts that Agent hits may never steal (AgentBossScope.java:35-42, :81-99). It covers mobs. It is not the same as companion boss scope.
- **Expedition type list:** the agent-engine.yaml key `AgentBossScope.EXPEDITION_TYPES`. After P8 it is 'ZAKUM' (agent-engine.yaml:5752).
- **Boss id list:** the agent-engine.yaml key `AgentBossScope.BOSS_IDS`. P8 adds it as 'none' (agent-engine.yaml:5756). Later phases add ids.
- **Companion boss scope:** the Agents that boss rules act on. An Agent is in companion boss scope in three cases. KPQ, HPQ, LPQ and EPQ runs are never in it.
  1. It is a member of a companion boss run, an `AgentBossRunSession` (G7). This includes the party-instance runs of Latanica, Neo City and Papulatus. Their P14/P15 portal hook opens the session (G7).
  2. It is a companion that fights a listed boss on a map with no event instance.
  3. It is a companion on a boss recruit or gate map while its owner is on the same map (G11).
- **G1 to G15:** the 15 shared groundwork items. "Shared groundwork" says which are built, which P8 builds and which later phases build.
- **Boss-run service:** P7's classes that run companions through a boss expedition. They are `AgentBossRunService`, `AgentBossRunSession`, `AgentBossRunRegistry`, `AgentBossRunHost` and `AgentBossRunPorts` in server.agents.capabilities.expedition.boss, and `CompanionBossRuns` in server.living.roster. They are live.
- **Boss-run seam:** P7's `AgentBossRunHost.Rule`, which living installs into server.agents with `AgentBossRunHost.install(Rule)`. G7 extends it. Run membership comes from `AgentBossRunRegistry.forMember` (AgentBossRunRegistry.java:63).
- **Boss rules catalog:** a new file, src/main/resources/agents/catalogs/boss-rules.json. It holds per-boss id lists: never-target ids, target priority, marker items and reflect skills. P11 creates it with the Normal Balrog entry. P12 extends it.
- **Death gate:** a new boss clause `AgentBossDeathDepartureGate.respawnOrHold` in the Agent death chain (G6). Only the revive-inside mode needs it.
- **DIED path:** P7's way of handling a dead member. The member revives outside after the normal 5 s, says one line and waits (AgentBossRunSession.java:368-419). It needs no death-chain change.
- **REENTERING:** a new member phase of `AgentBossRunSession` for the re-enter mode (G6). A dead member in this phase stays in the run.
- **Approach store:** P7's pattern from fix L7. A boss row in `BossEntrance` names an approach map. The store is the catalog supplier on that map (BossEntrance.java:101-115). The owner walks through that map with the boss key on. A short companion that meets the level gate then buys up to the boss standard there and catches up (COMPANION_BOSSING_P7A.md:178-181).
- **Desk trip:** a restock walk from a gate map to the nearest shop and back, with a spoken ETA. Only the Papulatus gate gets one.
- **Boss standard:** the HP and MP potion counts (`BOSS_STANDARDS`, agent-engine.yaml:4577) and the projectile count (`AMMO_STANDARDS`, :4533) that a companion carries into a boss run.
- **Short:** below the boss standard.
- **Pull-out:** the owner's party companions leave a boss run together with their owner (rule B).
- **Never-target list:** boss parts that bots must never hit, such as fakes, dummies, statues and display parts.
- **Client memory gate:** a stop rule for live checks with many bots. It is defined in its own section.
- **LAA client:** a copy of MapleStory.exe with the large-address-aware flag set (scratchpad/laa/MapleStory.exe, PE characteristics 0x12f, p8_plan.md F14). It gives the 32-bit client 4 GB of address space instead of 2 GB.
- **Kaentake exit fix:** a 6-line Kaentake.dll change. It removes a second crash while a failed client closes (kaentake_plan.md section 2, kaentake-scratch/fx/exit-leak.patch). It does not stop the mid-fight crash.
- **Crew A:** Bossfriend (GM6) and his five party companions, Isolde, Thane, Sorrel, Ingrid and Flint.
- **Bench allies:** Bossfriend's bench companions Orrin, Magna and Gunnar.
- **Crew B:** ohboy (gm 0) and his five companions, Brannock, Garrick, Fenna, Ilse and SilentSin.
- **Rung:** one level step of a test crew. A crew's ladder is its rungs in order.
- **Mechanics run:** a live check with crew A at Lv160. It proves that the rules work.
- **Balance run:** a live check with crew B near a friend's real level. It tunes difficulty, potions and deaths.
- **Part A and Part B:** the two P7 hand-offs. Part A is the owner's own party companions. Part B is bench allies and two owners.
- **Effort sizes** come from the plan doc (docs/BOT_MANAGEMENT_PLAN.md:544). S is 1-3 days, M is 1-2 weeks, L is 2-4 weeks, XL is more than 4 weeks.
- **Decision N:** row N of "Decisions taken".
- **Question N:** item N of "Questions for you".
- **User decision N:** item N of your final decisions of 2026-10-06. For example, user decision 9 says that companions leave an Easy Balrog run when every human has died or left.
- **Plan decision 11a:** option (a) of decision 11 in docs/BOT_MANAGEMENT_PLAN.md:1006-1008. Companions skip boss quest prerequisites and follow-warp in.
- **Scarga:** Scarlion and Targa.

## Decisions taken (2026-10-08)

You answered "use all recommended" on 2026-10-08. Each row is now settled. Decision 22 is the one exception: the standards scale up from live evidence.

| # | Question | Answer | Where it acts |
|---|---|---|---|
| 1 | How are ohboy's companions levelled? | (b) Bossfriend runs `!setgmlevel ohboy 6` on test days and `!setgmlevel ohboy 0` before each live check (CommandsExecutor.java:666). | Test crews, P11 |
| 2 | Do boss clears stop counting as party-quest clears? | Yes. Boss events skip the PQ-clear hook and write boss records. This also cleans up P7 and P8 records. It also changes human players' PQ records and friendship PQ points, because the hook runs for every player in the instance. | G12 (P12) |
| 3 | Is the Normal Balrog crew Lv100 3rd job with 60%-scrolled gear? | Yes. It is now crew B. | P11 |
| 4 | Does the Java Normal Balrog encounter run only with companions registered? | Yes, companions only. Runs with only humans keep the stock BalrogBattle.js. | P11 |
| 5 | Does each boss get a mechanics run with crew A, then a balance run on crew B? | Yes. Bosses under Lv60 get no balance run. Question 1 covers Lv60-77. P11 has no crew A mechanics run. Its one live check runs on crew B at Lv100. | Every phase |
| 6 | Do companions spend their own daily boss entry? | Yes, logged only when they enter. | P8 builds it for Zakum. G8, P15, P17, P18, P20 |
| 7 | Does the quota helper apply to companions only? | Yes. Human logging stays stock. | P8 builds it. G8 |
| 8 | Which expedition comes first after Zakum? | Scarga (P17), then Horntail (P18). | Phase order |
| 9 | Where does a dead Papulatus companion wait? | At the gate 220080000. | P15 |
| 10 | May a short companion make one desk trip at the Papulatus gate? | Yes. | G9, P15 |
| 11 | May companions skip Keroben and the Slate for Horntail? | Yes. | P18 |
| 12 | Does `USE_ANTI_IMMUNITY_CRASH` stay off? | Yes. Bots stop or retarget instead. | G4 |
| 13 | Do Pink Bean companion deaths count to the 5-death wipe and cost EXP? | Yes. | P20 |
| 14 | Do Showa companion deaths cancel the slot-machine bonus? | Yes. | P19 |
| 15 | Is the stock Pink Bean reactor bug fixed for every player? | Yes, in P20 (2708000.js:41). | P20 |
| 16 | Is the stock Showa re-entry lookup fixed? | No. Dead companions wait outside. | P19 |
| 17 | Do companions follow the G14 field rule? | Yes. | P13, P16 |
| 18 | After a field-boss death, does a companion rest, then follow? | Yes. | G14 |
| 19 | Do citizens take mirrored field-boss hits but never attack a listed boss? | Yes. | G1, G14 |
| 20 | Do boss spawn points keep their own WZ respawn time? | No. The server-wide 10 s override stays. | G14 |
| 21 | May companions follow into Temple of Time lanes they never unlocked? | Yes. | P16, P20 |
| 22 | Starting potion standards | Replaced. They scale up from live evidence. Zakum is 500/400 plus 4000 projectiles. | "Boss supply standards" |
| 23 | Does a companion boss run give BOSS points once per run? | Yes. Kills outside those runs keep today's rule. | G12 |
| 24 | Do King Pepe and the Yeti Brothers stay out? | Yes. | "Not planned" |
| 25 | Do companion Bishops cast Resurrection in boss runs? | No. Dead companions revive outside, as in P7. | G6 |

P8's own 13 questions also take their recommended answers, with one exception (p8_amendments.md items 2 and 8):

- Q5 is the exception: the Zakum standard is now 500/400 plus 4000.
- Q12 keeps its recommended answer. The LAA client was tested in a copy. You approved shipping it if the test stopped the crashes, and it did (see "Client memory gate").
- Q13 takes its default branch "P11 moves to crew B" (p8_plan.md:634). So crew A goes to Lv160 without waiting, and levels still only rise.

## What is built and what P8 builds

### Live: P7 (on feat/companion-manager)

- **Boss-run service.** Phases REGISTERING, STARTING, FIGHTING, POST_CLEAR and ENDED. Sign-up through the entry NPC's own window, run records, the lost-run sweep and logout delegation (COMPANION_BOSSING_P7A.md:52-59).
- **Part B, `CompanionBossAllies`** (COMPANION_BOSSING_P7B.md:47-61):
  - bench allies called from the entry NPC, with ETAs and a seats-left line,
  - one overflow bot party per owner,
  - two owners (`COMPANION_BOSS_OWNERS_MAX`),
  - 2 list seats kept for players (`COMPANION_BOSS_HUMAN_SEATS`, config.yaml:954),
  - the drop owner of an ally's kill (`Monster.killBy` asks `LivingWorldHooks.bossDropOwner`),
  - ally supplies bought on arrival (`AgentShopProvisioning.provisionAt`).
- **The approach store for Easy Balrog.** Ant Tunnel Park 105070001 and the 24 Hr Mobile Store 1061001 (fix L7, BossEntrance.java:28-34).
- **Fixes L1-L7** (COMPANION_BOSSING_P7A.md:165-181):
  - L1: an Agent clamped by a fight boundary lands on the floor it crosses.
  - L2: ordinal 0 leaves the initial-claw staging within 14 px.
  - L3: a lost fight removes its parts without the kill path.
  - L4: an Agent hit on a server-owned boss raises no DISTANCE_HACK.
  - L5: gun-only skills need a gun.
  - L6: the Easy standard is 100/60.
  - L7: the Ant Tunnel Park approach store.
- **Fix A, Fix B and `!balrogprobe`** (COMPANION_BOSSING_P7A.md:204-224). Fix A stops stale client control of the body. Fix B sends the body's casts without the skill action. `!balrogprobe` is GM6.
- **The no-summon rule for INSIDE members** (P7 step 6b, AgentCombatSummonRuntime.java:154-158).
- **Switches.** `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog"` (config.yaml:940). The Part B switches are off (config.yaml:952-953).

### Being built: P8 (branch feat/companion-bossing-p8)

At my last read, steps 1-7 were committed and steps 8-15 were open. The commit of each step is in "Details".

| Piece | What it does | G item | P8 step |
|---|---|---|---|
| `!bossgear` | GM6 tool. It forges gear where every upgrade slot holds a passed 60% scroll. | G13 | 1, built |
| Zakum data and Adobis hooks | catalog row, entrance row, `zakum` key, Adobis options | – | 2-3, built |
| `AgentBossScope` | the G2 guard, with the DISTANCE_HACK refresh path exempt (AbstractDealDamageHandler.java:247, ServerMobAutonomyService.java:268) | G2 | 4, built |
| Action catalog fixes | a circle with r of 0 or less reads its absolute radius, UOL animation times resolve, `jumpAttack` loads (ServerMobActionCatalog.java:61, :140-164) | G1 | 5, built |
| `BossMirrorGeometry` | pOption region masks, every region when attackCount is at least areaCount, noFlip boxes (BossMirrorGeometry.java:29-64) | G1 | 5, built |
| `BossAttackMirrorSeam` | the core MOVE_LIFE decode seam | G1 core | 6, built |
| `AgentBossAttackMirror` | the Agent-side mirror: open-run gate, dedup, impact on the timer, hit on the mailbox, shown-hit cap, calibration | G1 core | 7, built |
| `!bossmirror` | status, mode and a bounded GM trial on one map | G1 | 8 |
| Never-target fakes, marker-item guard, exit NPC after the clear | fakes are never targets in a run, no bot picks up the Eye of Fire 4001017 | G3, G10 | 9 |
| `BossTargetRules`, `AgentZakumScenario` | pure Zakum targeting with an immunity drop | G3, G4 | 10 |
| Quota hook | companions spend their own entry, logged only on entry, humans stock | G8 | 11 |
| Supplies | zakum 500/400 plus 4000 at Glibber 2030009 on 211040200, trips held in Adobis's room | G9 | 12 |
| Owner-left rule and lines | `Out.OWNER_LEFT`, Zakum lines | G7 | 13 |
| Client-load lever and summary | `ATTACK_INTERVAL_FLOOR_MS`, only inside a run on its battle map (p8_plan.md:577), and a per-minute summary line. `MAX_SHOWN_HITS` came with the Agent mirror in step 7 (agent-engine.yaml:5768). | – | 7 (built) and 14 |

- P8 changes no death chain. A dead Zakum companion waits in El Nath (p8_plan.md section 5).
- P8's step 12 makes the carry and per-visit caps reach the boss standard for boss runs only (p8_amendments.md item 8). Every later boss reuses that.

## Facts that shape this plan

1. **Bots take ordinary boss attacks only under server authority, until P8's mirror ships.** The only caller of `applyOrdinaryAttack` is the server-authority path (ServerMobAutonomyService.java:551). Bots otherwise take three kinds of damage:
   - touch damage (AgentCombatDamageRuntime.java:82-103).
   - skills that the controller casts, inside the skill box (MoveLifeHandler.java:97-113, MobSkill.java:397-425).
   - reflect damage (AbstractDealDamageHandler.java:573-591).
   - After P8 the mirror adds ordinary hits for Zakum only. P12 widens it.
2. **Unconfirmed live: a bot hit can steal an unprotected boss from the human.** The bot hit sends `stopControllingMonster` (Monster.java:1120-1149). Every new bot hit renews a 7000 ms lease (agent-engine.yaml:33). A far Agent hit can also take control away through DISTANCE_HACK (p8_plan.md F2). P8's G2 guard blocks both for Zakum. Every other native boss stays open until its phase lists it.
3. **What the G2 guard covers after P8.**
   - Easy Balrog parts are safe while server-owned (`isActiveInstance`, AgentBossScope.java:36-37).
   - A boss-flagged part in an event of a listed expedition type is guarded (AgentBossScope.java:91-95).
   - A listed id is guarded only on a map with no event (AgentBossScope.java:87-90).
   - So party-instance bosses and non-boss adds inside expeditions are not covered yet. P12 adds a case for them (G2).
4. **With no human on the map, a boss has no controller and stands idle.** Bots can still kill it on the server (Monster.java:2117-2120, MapleMap.java:1436-1484).
5. **A dead bot always leaves the instance.** Its respawn uses the channel map, not the instance copy (MapleMap.java:323-328, CosmicMapGateway.java:74-78). The forced map change unregisters it (Character.java:1803).
6. **After a clear, stock follow never takes a bot out of its instance** (AgentFollowMapSyncService.java:85-86). P7's walk-out and P8's exit-NPC flag handle this per boss. Each new boss sets its own exit path.
7. **The leader of a running expedition can never re-enter.** This is true for Horntail (2083004.js:64-67), Zakum (2030013.js:119-121, P8 branch) and Scarga (9270047.js:66-69). An owner who leads and dies is out for good.
8. **Crash never removes boss immunity on this server** (config.yaml:365, AgentCrashSkillPolicy.java:41-57). Bots never retarget when the boss is immune, except in P8's Zakum rules.
9. **Field bosses that spawn from map data come back 2-4 s after a kill.** `MOB_RESPAWN_TIME_OVERRIDE_SECONDS: 10` (config.yaml:544) replaces every WZ mobTime of 0 or more. The code is SpawnTimeOverridePolicy.java:16-25, :49-57 and MapleMap.java:3412. After a kill, the wait is 10 s divided by 5 (SpawnPoint.java:111-114), on a 2 s respawn tick (config.yaml:186). Script bosses (the AreaBoss scripts) still return 0-3 h after a kill.
10. **Boss parts with no body box deal no touch damage, and the Balrog bodies never touch a bot on the floor.**
    - Touch needs a box from frame 0 of stand, move or fly (AgentMobHitboxProvider.java:104-121). Without one, the touch test is false (AgentMobTouchRuntime.java:17-20).
    - The Pink Bean statues 8820000, 8820019, 8820022 and 8820023 and the Balrog parts 8830004 and 8830006 have no stand box.
    - The Balrog bodies 8830000 and 8830007 do have one: (-138,-429)-(63,-261) (Mob.wz stand/0).
    - Touch uses only the lower half of the box (AgentMobTouchRuntime.java:21). With the body at y 258, that half spans about y -87 to -3.
    - The only foothold under x 274-475 in 105100300 is y 258. The bot sweep is 50 px (agent-engine.yaml:1318). So neither body ever touches a bot on the floor.
    - The claws 8830001 and 8830002 do touch. Their lower halves reach down to about y 228-238.
11. **Fake parts are normal targets for bots outside a companion run.** Target eligibility checks only alive and not friendly (AgentCombatTargetEligibilityPolicy.java:9-13). P8 step 9 adds a never-target clause for fakes inside runs that set the flag.
12. **The server never checks FieldLimit.SUMMON** (FieldLimit.java:29). Bots can summon where players cannot. The bit is also set on PQ maps, so a bot no-summon rule must stay inside companion boss scope:
    - KPQ 103000800-805 (fieldLimit 270460, Map1/103000800.img.xml:14) and the KPQ bonus map 103000890 (8316).
    - LPQ 922010100-922011000 (270972, and 270974 on 922010600) and the LPQ bonus map 922011100 (8828).
    - HPQ 910010000 (270972, Map9/910010000.img.xml:3617).
    - No EPQ map has the bit.
13. **The expedition start logs a daily attempt for every online member.** It warps in only the members standing on the recruit map (Expedition.java:250-256, EventInstanceManager.java:422-430). P8's quota hook changes this for companions of an open run only.
14. **What exists now.**
    - The kill switch `COMPANION_BOSS_ENABLED_TYPES` is live with "easy_balrog" (config.yaml:940). Validation accepts only `CompanionBossKeys.KNOWN` (CompanionBossKeys.java:13-23). P8 adds `zakum` to it.
    - A listed key means "the entry NPC offers My party will register too".
    - The boss-run service is live (P7). The mirror is being built (P8).
15. **The drop owner is the highest damager** (Monster.java:908-909). Damage is credited per attacker, and that includes damage forwarded from Horntail parts (Monster.java:504-508). P7 Part B sends an ally's top-damage kill to its owner. NO_BOSS only stops bots from picking items up.
16. **Every boss clear runs the PQ-clear hook** (EventInstanceManager.java:865-871, LivingWorldHooks.java:284-304). Both Balrog scripts name their instance 'Balrog' plus the lobby id, which is 'Balrogundefined' at run time (BalrogBattle.js:119-120, BalrogBattle_Easy.js:119). Decision 2 removes this for boss events (G12).
17. **"9 of 9" area attacks.** When attackCount equals areaCount, server authority hits only the base box (BossAction.java:32-34). This covers Easy body 8830007 attack1, Normal body 8830000 attack1 and Pink Bean 8820001 attack1.
    - P8's mirror geometry hits every region for such an attack (BossMirrorGeometry.java:36-37).
    - Server authority does not change until the P7 live check reports hits outside x -400 to 400. Unconfirmed: that report has not come in yet.
18. **Self-destruct blasts reach only clients.** The server kills a bomb at its self-destruct HP (MapleMap.java:1471-1477). The blast then reaches only clients, as a TAKE_DAMAGE report (TakeDamageHandler.java:157). It is not a MOVE_LIFE attack, so the mirror never sees it.
19. **`!debuff` skips Agents.** It filters its targets with `CommandTargetPolicy.includeInHumanCommand`, which is false for every Agent (DebuffCommand.java:107, CommandTargetPolicy.java:42-44).
20. **A banish keeps the target on its map.** `changeMapBanish` warps to the character's current map (Character.java:1694-1702). Unconfirmed: how an Agent handles a warp within the same map.
21. **Only Papulatus closes a Zakum-like gate at the start.** The other bosses have no door. Each boss's re-entry depends on its NPC script instead. See "Death modes by boss".
22. **The human's client runs close to its memory limit.** See "Client memory gate".
23. **The Use tab limits how much a companion carries.**
    - A character starts with 24 Use slots (db/tables/002-character.sql:55).
    - Unagi 2022003 and Pure Water 2022000 stack to 100. Bullets 2330000 stack to 800, Subi stars 2070000 to 500 and arrows 2060000 and 2061000 to 2000 (Item.wz Consume, slotMax).
    - The readiness check needs a free Use slot for each new stack, and keeps one more free for ammo (AgentSupplyReadinessRuntime.java:258-263).
24. **A GM6 character can run `!hpmp` on any Agent** (CommandTargetPolicy.java:24-31, AGENT_CONTROL_GM_LEVEL 6 at :14). So Bossfriend can down one of ohboy's companions in a crew B check.

## Death modes by boss (re-checked 2026-10-08)

Every expedition ends only at 1 player or fewer, and bots count (EventInstanceManager.java:1267-1274). A party event also ends when its leader leaves, or when the count falls to its minimum (EventInstanceManager.java:1276-1285).

- Latanica (minimum 1, LatanicaBattle.js:27) and Neo City (minimum 2, TD_Battle1.js:27) use the party-event rule.
- Papulatus uses the expedition rule: it ends only at 1 player or fewer, and the leader's death or exit does not end it. Its event script calls `isExpeditionTeamLackingNow` (PapulatusBattle.js:158, :172, :181).

| Boss | Gate closed at the start? | Can a dead member get back in by NPC? | A dead member revives at | Death mode | Built by |
|---|---|---|---|---|---|
| Easy Balrog (P7) | No | No. Mu Young looks up 'Balrog' plus the channel, but the instance is 'Balrog' plus the lobby id (1061014.js:139-143, BalrogBattle_Easy.js:119). | 105100100 | wait outside | P7, live |
| Zakum (P8) | Yes. Gate reactor 2118002 goes to state 1 at afterSetup, and the door portal refuses everyone (ZakumBattle.js:92-94, :216-218, Zakum05.js:43-47). | No. Adobis's in-progress branch is behind the door. | El Nath 211000000 (Map2/280030000.img.xml:7) | wait in El Nath | P8 |
| Normal Balrog (P11) | No. afterSetup only spawns Balrog (BalrogBattle.js:135-137). | No, the same lookup as Easy (1061014.js:139-143, BalrogBattle.js:119-120). | 105100100 (Map1/105100300.img.xml:8) | wait outside | P7's DIED path |
| Papulatus (P15) | Yes. Reactors 2208001-2208003 on 220080000 go to state 1 at afterSetup, and back to 0 at clearPQ or dispose (PapulatusBattle.js:128-130, :210-214, :232-241). | No. The portal only starts a new run (Populatus00.js:43-45). | Ludibrium 220000000 (Map2/220080001.img.xml:7) | wait at the gate (decision 9) | P7's DIED path, plus a walk to the gate |
| Capt. Latanica (P14) | No (LatanicaBattle.js:128). | No. The portal only starts a new run (captinsg00.js:19-21). | Boat Quay Town 541000000 (Map5/541010100.img.xml:8) | wait outside | P7's DIED path |
| Neo City bosses (P14) | No (TD_Battle1-5.js:130). | No. The portal only starts a new run (TD_Boss_enter.js:20-22). | 240070000, the return map of all five battle maps (Map2/240070203, 240070303, 240070403, 240070503 and 240070603.img.xml:8) | wait outside | P7's DIED path |
| Scarga (P17) | No (ScargaBattle.js:92). | Yes, for a registered non-leader. canJoin is set to 1 and never reset, and the names match (ScargaBattle.js:95-96, 9270047.js:34, :82-93). | Kampung 551000000 (Map5/551030200.img.xml:8) | re-enter by NPC | G6, in P12 |
| Horntail (P18) | No (HorntailBattle.js:92). | Yes, for a registered non-leader (HorntailBattle.js:95-96, 2083004.js:34, :80-91). The recruit map is reached only by the Slate's warp, which needs 4001086 (2083000.js:48-62). | Leafre 240000000 (Map2/240060000.img.xml:1612) | re-enter by NPC, with the decision-11 waiver warp. A re-entered bot lands on 240060000 and walks through hontale_BR to 240060200 (2083004.js:84, HorntailBattle.js:29, :123-126). | G6, in P12 |
| The Boss, Showa (P19) | No (ShowaBattle.js:92). | No. Konpei looks up 'Showa Gang' plus the channel, but the instance is 'Showa' plus the channel. The script then fails on a null instance (9120201.js:33, :83-84, ShowaBattle.js:95). | Showa Town 801000000 (Map8/801040100.img.xml:7) | wait outside (decision 16) | P7's DIED path |
| Pink Bean (P20) | No. afterSetup only schedules the first wave (PinkBeanBattle.js:92-95). | No. The NPC looks up 'Twilight of the Gods' plus the channel, but the instance is 'PinkBean' plus the channel (2141001.js:36, :85-86, PinkBeanBattle.js:98). | 270050200 inside the run, because playerRevive returns true (PinkBeanBattle.js:165-167, Map2/270050100.img.xml:8) | revive inside | G6, in P12 |

- **Papulatus is the only other boss with a Zakum-like gate.** Its portal does not read the reactors. It refuses because the lobby is busy. The effect is the same: nobody comes back in.
- **Each Neo City boss has its own gate map:** 240070202, 240070302, 240070402, 240070502 and 240070602 (TD_Boss_enter.js:4-5, TD_BattleN.js:30).
- **Pink Bean's NPC re-entry is broken too.** This is new. Revive inside is the only way back, for humans as well.
- **No door stands before Scarga, Showa or Pink Bean.** Their recruit maps are reached by plain portals (Map5/551030000.img.xml:3569-3575, Map8/801040003.img.xml:1395-1400, Map2/270040100.img.xml:668-673).
- **Horntail is the one re-entry boss behind a gate item.** The decision-11 waiver warps a companion to 240050400 instead of the Slate.
- **Horntail re-entry does not land at the fight.** `registerPlayer` puts the member on 240060000 (HorntailBattle.js:29, :123-126). The two seal portals of hontale_BR then open only at defeatedHead 1 or more and 2 or more (hontale_BR.js:24, :33). Each pre-head kill adds 1 to defeatedHead (HorntailBattle.js:222-224). So each portal is open once the fight has moved past it.
- Scarga's exit NPC warns that a leaver cannot return (9201134.js:51). The NPC re-entry still works, because canJoin is never reset.
- The human revive path is ChangeMapHandler.java:103-106, then `respawn` to the map's return map. For Pink Bean that is the instance copy, because the player is still registered.

## Client memory gate

**Evidence**

- Live Easy Balrog fights close the human's client mid-fight. The game's own crash log shows SOUND_DX8.DLL asking for a 134,788-byte buffer, getting NULL and writing to it (msexcr.ini 14:00:23, COMPANION_BOSSING_P7A.md:293-296).
- MapleStory.exe is 32-bit and not large-address-aware, so it has 2 GB of address space (kaentake_plan.md:54).
- On a normal field the stock client already shows VM 1,763 MB, about 1.76 GB of its 2 GB (maple_mem_baseline.csv, 16:08-16:10).
- The LAA client runs. maple_mem.csv covers 19:06-20:23. It shows five client processes, one after the other:
  - 69652 from 19:06 to 19:24,
  - 60080 from 19:25 to 20:16,
  - 66984 from 20:16 to 20:17,
  - 59440 from 20:18 to 20:20,
  - 55472 from 20:20 to 20:23.
- Their VM peaks are 3,449-3,982 MB. Only a large-address-aware 32-bit client can pass 2 GB.
- Unconfirmed: whether the process changes at 19:24, 20:16, 20:17 and 20:20 were client exits. Read msexcr.ini for those times before the LAA test. A crash there counts against the LAA client.
- Unconfirmed: what VM_MB counts for a 32-bit process on 64-bit Windows. If it is real address use, 3,982 MB is close to the LAA ceiling of 4,096 MB too. So the gate reads msexcr.ini first and VM second.
- Unconfirmed: whether the crash is memory exhaustion or heap damage (kaentake_plan.md:54).

**The LAA test result (2026-10-08)**

- The deploy session ran a full Easy Balrog companion fight from 19:54 to 20:15 with the LAA client, Bossfriend, his party companions and 3 bench allies.
- The client did not crash. VM peaked at 3,982 MB, past the stock client's 2,048 MB ceiling. Before the change, every Easy Balrog fight closed the client.
- The Kaentake exit fix was tested clean from a client copy (no new WER event).
- The fight was lost when the HP potions ran out. That is a supply problem, not a client problem (see "Boss supply standards").
- Each later big-boss live check still runs the memory logger, because 3,982 MB is close to the LAA ceiling of 4,096 MB.

**Shipping the LAA client (approved; the deploy session waits for your direct yes)**

- **Before you start:** friends' launchers download the new files at their next launch.
1. Back up Game Files\MapleStory.exe as a `.bak` file.
2. Copy the LAA MapleStory.exe into Game Files.
3. Copy the Kaentake exit-fix DLL in the same update (kaentake_plan.md:152-157).
4. Run `python patch-server/gen_manifest.py --root "<Game Files>"`.
- Unconfirmed: that the patch manifest replaces MapleStory.exe the way it replaces Kaentake.dll (kaentake_plan.md:144).

**The gate, for each stage of a live check**

1. Use the client that friends use: the LAA client once it ships, else the stock exe.
2. Start the memory logger before the stage.
3. Stop the stage on a client exit, a new msexcr.ini entry, or VM climbing steadily through the stage. Unconfirmed: the VM threshold is about 150 MB above the stage start (p8_plan.md, live recipe).
4. If a run stage stopped, set `ATTACK_INTERVAL_FLOOR_MS` to 900 or `MAX_SHOWN_HITS` to 3 (P8 steps 7 and 14).
5. If a field stage stopped, set `MAX_SHOWN_HITS` to 3 and reduce the citizens on that map. P8's floor acts only inside a run on its battle map (p8_plan.md:577).
6. Restart and rerun the stage.
7. Grow the stages in this order: owner plus 5, then the bench allies, then a second owner.
8. A boss key goes on for friends only after its largest stage passes.

- After P12, the floor also acts in the G1 field scope, for listed bosses whose field key is on. Step 5 can then set the floor as well.

**Where the gate applies**

- Normal Balrog (P11), Scarga (P17), Horntail (P18), The Boss (P19) and Pink Bean (P20).
- Field bosses with many bots (P13 and P16). These are maps where citizens farm (scripts/config/population/fields.yaml:34-35, :87, :160, :206, :247), and Showa's recruit map 801040004 with Male Boss and 14 other mobs.
- Papulatus (P15) runs the logger as a watch only. Up to about 80 bombs can share the map (MobSkill.java:320). If the watch shows a climb, the gate applies.
- P14 runs have at most 6 members, so they need no gate.

**Kaentake exit fix (client hygiene)**

- It removes the Kaentake.dll+0x98b7 fault, a second crash while a failed client closes (kaentake_plan.md:1-27).
- That fault makes a mid-fight crash look like a Kaentake crash. Removing it keeps the crash records honest.
- It does not stop the mid-fight crash.
- You approved shipping it after a local test. That test was clean. The deploy session waits for your direct yes before it ships both files.
- Build it from kaentake-questcolor with exit-leak.patch. Test it once locally from a client copy. Ship it with the LAA client update, through the patch manifest (kaentake_plan.md:147-157).

## Boss supply standards

**Evidence**

- Easy Balrog live run 1 at Lv70: each companion drank 20-36 White Potions a minute and died within 3-5 minutes (COMPANION_BOSSING_P7A.md:160-162). At 300 HP each, that is about 6-11 Unagi (1000 HP) a minute.
- Zakum: you set 500 HP and 400 MP potions plus 4000 projectiles. P8 estimates a 35-45 minute fight (p8_amendments.md item 8, p8_plan.md R6).
- That is about 12 HP potions, 10 MP potions and 100 projectiles a minute. It matches the top of the Easy Balrog drain.
- Easy Balrog live run 3 (2026-10-08, Lv70, no healer): each melee companion lost 5,200-7,400 HP a minute, and each ranged companion 4,000-4,600. The claws took 5.5 min and the body 15 min. The run was lost when every companion's HP potions ran out. Ingrid's 65 MP potions ran out at 20:01, so nobody recast Hyper Body. Unconfirmed: Flint (gunner) ran out of bullets at the default 500 and did no damage after 20:01 (body_diag_plan.txt).
- So the rule's 12,000 HP a minute is about twice the drain measured on 2026-10-08. It keeps a margin for harder bosses and for parties with no healer.

**Rule**

- Each boss starts at 12 HP potions, 10 MP potions and 100 projectiles for each expected fight minute.
- Counts are rounded up to whole 50s.
- A boss with deadly hits or MP burn gets MP equal to HP.
  - Deadly attacks: the Normal Balrog body (Mob.wz/8830000.img.xml:810), Horntail (8810000.img.xml:792), Pink Bean (8820001.img.xml:976, 8820003.img.xml:85), Nibelung (8220013.img.xml:140) and Papulatus.
  - MP burn: Horntail (mpBurn 3000 on 8810005 and 8810006) and The Boss (mpBurn 50000).
  - Zakum (mpBurn on 8800003 and 8800004) keeps your 500/400. It is the one exception.
- Standards now count HP and MP points for a boss key listed in `BOSS_RECOVERY_STANDARDS` (branch fix/easy-balrog-supplies, 2026-10-08). A companion buys the shop's best potion until it carries that many points. So a shop that sells weaker potions no longer under-supplies.
- Each phase writes its standard as points: the table's HP count times 1,000 (Unagi) and its MP count times 800 (Pure Water). For example, Normal Balrog 300/300 becomes 300,000 HP and 240,000 MP.
- Zakum keeps your 500/400 potion counts in P8. It can move to points after its first live run.
- AMMO_STANDARDS accepts at most 5000 projectiles (agent-engine.yaml:4532). The bound is AMMO_LOW_WARN 500 (:728) times AMMO_TARGET_THRESHOLD 10 (:3187). AgentEngineConfigValidator refuses a larger count at startup (AgentEngineConfigValidator.java:107-113, :165-170).
- The ammo top-up buys toward 200% of the standard into every free Use slot but one (:4541). It stops at 5000, unless the standard itself is higher (AgentShopService.java:586-589).
- A standard must fit the Use tab (fact 23). The supply budget is 18 stacks. That leaves the readiness check's free slot and about 5 more slots.
- Ammo users may fill those 5 slots with extra projectiles from the top-up. Other Use items may then find no room.
- Unconfirmed: how many Use slots companions fill with other items. P8's first live Zakum run shows it.
- Each phase writes its values into `BOSS_RECOVERY_STANDARDS` (points) and `AMMO_STANDARDS` (agent-engine.yaml :4533). Their `# Values:` lines say "tuned from the first live 'PQ potions' lines".
- Unconfirmed: the expected fight minutes are my estimates, until each boss's first live run.

| Boss (phase) | Time limit | Expected fight | Scaled standard (HP/MP, projectiles) | Starting standard | Stacks (bullets at 800) | Potion cost, about |
|---|---|---|---|---|---|---|
| Easy Balrog (P7, live) | 60 min (BalrogBattle_Easy.js:41) | 20 min (claws 5.5 + body 12.4 at the first-half rate, plus margin) | 130,000 HP / 32,000 MP points, 2000 | 130,000 HP / 32,000 MP points, 2000 (fix/easy-balrog-supplies; live was 100/60 counts and 500) | 2+1+3 = 6 | 0.22M |
| Zakum (P8) | 120 min (ZakumBattle.js:37) | 35-45 min | 500/400, 4000. The MP-burn exception. | 500/400, 4000 | 5+4+5 = 14 | 1.26M |
| Normal Balrog (P11) | 60 min (BalrogBattle.js:41) | 25 min | 300/300, 2500. Deadly body attack. | 300/300, 2500 | 3+3+4 = 10 | 0.86M |
| Papulatus (P15) | 45 min (PapulatusBattle.js:37) | 25 min | 300/300, 2500. Deadly attack1 leaves 1 HP and 1 MP. | 300/300, 2500 | 3+3+4 = 10 | 0.86M |
| Capt. Latanica (P14) | 10 min (LatanicaBattle.js:37) | 10 min | 150/100, 1000 | 150/100, 1000 | 2+1+2 = 5 | 0.34M |
| Bergamot and Dunas (P14) | 10 min (TD_Battle1.js:39, TD_Battle2.js:39) | 10 min | 150/100, 1000 | 150/100, 1000 | 2+1+2 = 5 | 0.34M |
| Aufheben and Oberon (P14) | 15 min (TD_Battle3.js:39, TD_Battle4.js:39) | 15 min | 200/150, 1500 | 200/150, 1500 | 2+2+2 = 6 | 0.49M |
| Nibelung (P14) | 20 min (TD_Battle5.js:39) | 20 min | 250/250, 2000. Deadly attack. | 250/250, 2000 | 3+3+3 = 9 | 0.71M |
| Scarga (P17) | 60 min (ScargaBattle.js:37) | 50 min | 600/500, 5000 | 600/500, 5000 | 6+5+7 = 18 | 1.54M |
| The Boss (P19) | 60 min (ShowaBattle.js:37) | 50 min | 600/600, 5000. mpBurn 50000. | 600/600, 4000 | 6+6+5 = 17 | 1.71M |
| Horntail (P18) | 120 min (HorntailBattle.js:37) | 70 min | 850/850, 7000. Deadly attacks and mpBurn 3000. 7000 needs the 5000 bound raised for boss keys only (Question 6). | 600/600, 4800 | 6+6+6 = 18 | 1.71M |
| Pink Bean (P20) | 140 min (PinkBeanBattle.js:37) | 100 min | 1200/1200, 10000. Deadly attacks. 10000 needs the 5000 bound raised for boss keys only (Question 6). | 600/600, 4800 | 6+6+6 = 18 | 1.71M |

- **Easy Balrog moves to 130,000 HP / 32,000 MP points and 2000 projectiles** (fix/easy-balrog-supplies). The next live run, with Isolde healing and no allies, tunes it.
- **Scarga, The Boss, Horntail and Pink Bean need far more than 200 potions.** All four start at 600 HP potions. Horntail and Pink Bean need 850 and 1200 at their scaled standard.
- **Horntail and Pink Bean do not fit the Use tab at their scaled standard.** They need 27 and 37 stacks. They start at the most that fits, 18 stacks. Question 5 covers a larger Use tab.
- **Their scaled projectile counts cannot load today.** 7000 and 10000 are above the 5000 bound. A larger Use tab does not unlock them. Question 6 covers raising the bound for boss keys only.
- The stack column counts bullets (800 a stack). A claw user needs more stacks for stars (500 a stack). A bow user needs fewer for arrows (2000 a stack).
- The stack column counts the standard only. The top-up can raise projectiles to twice the standard, but not past 5000. For example, a 2500 standard can top up to 5000 bullets, 7 stacks instead of 4.
- Costs use about 1,150 mesos per Unagi and 1,700 per Pure Water. These are the Ant Tunnel Park and Glibber prices (src/main/resources/db/data/102-shopitems-data.sql:644-646, :1115-1116). Other shops may differ. Projectiles are extra.
- Unconfirmed: how a companion that cannot afford a big standard reports it. The readiness check has a CANNOT_AFFORD result (AgentSupplyReadinessRuntime.java:216-220).
- An approach map that serves several bosses buys the largest standard among the enabled bosses whose level gate the companion meets (G9). Ant Tunnel Park serves Easy and Normal Balrog. Leafre serves Neo City, Horntail and Pink Bean.
- For Neo City alone, Leafre buys at most 250/250, Nibelung's standard.

## Test crews and the level order rule

**Level order rule.** A test crew's level only rises. Lowering a level drops skills, and `!setlevel ... restore` does not give them back (SetLevelCommand.java:55-57, :278-279). So each crew runs its live checks in rising level order. A lower balance run uses a different crew.

**How companions are levelled:**

- `auto` is refused for companions: "'auto' is for players" (SetLevelCommand.java:170-172). A companion spends its own AP and SP.
- A GM sets only its own companions, and only while they are out with it (CompanionLevelTool.java:27-31, :317-318).
- A companion on a bench slice is refused (CompanionLevelTool.java:324-325). Call it out first.
- A call with a full party returns PartyFull (RosterService.java:879). The party cap is 6.
- A called companion walks over from its camp (RosterService.java:886-890). The level tool refuses it until it arrives, because it is not ACTIVE yet (CompanionLevelTool.java:317-318).
- With "Keep my crew fresh" on, a set that leaves a companion 10 or more levels off its owner is refused (CompanionLevelTool.java:33, LivingWorldConfig.java:234). Set the owner first.
- `!bossgear` follows the same owner rule as `!setlevel` (p8_plan.md section 2, `CompanionGearTool`).

**How ohboy gets to a test map.** ohboy is gm 0 during a live check, so he runs no GM command then. Bossfriend moves him:

1. From Bossfriend, run `!warp <map>` (GM2, CommandsExecutor.java:483).
2. Then run `!warphere ohboy` (GM3, CommandsExecutor.java:511). ohboy's companions follow him.

**Setup for a crew A rung.** P8's live recipe raises crew A and the bench allies to Lv160 with `!bossgear` (p8_plan.md, live recipe steps 5-10). No later phase changes crew A's level.

**Setup for a crew B rung (decision 1):**

1. From Bossfriend, run `!setgmlevel ohboy 6` (GM6, CommandsExecutor.java:666). ohboy must be online on Bossfriend's channel.
2. As ohboy, run `!setlevel me <level> <job> auto`.
3. As ohboy, run `!setlevel <name> <level> <job>` for each of his five companions. Do not add `auto`.
4. If the rung asks for 60% gear, run `!bossgear <name>` for each companion as ohboy. Then run `!bossgear me` and equip the pieces.
5. If a companion is on a bench slice, call it out first. Wait until it arrives.
6. From Bossfriend, run `!setgmlevel ohboy 0` before the live check. The check then runs with a non-GM owner.

**Crews:**

| Crew | Members | Rungs in order |
|---|---|---|
| Crew A | Bossfriend, Isolde, Thane, Sorrel, Ingrid, Flint | Lv70 3rd job (P7) → Lv160 4th job (P8, then the mechanics runs of P12 to P20) |
| Bench allies | Orrin, Magna, Gunnar | Lv70 (P7 Part B) → Lv160 (P8, then P17 to P20) |
| Crew B | ohboy, Brannock, Garrick, Fenna, Ilse, SilentSin | the ladder below |

**Crew B ladder:**

| Rung | Level | Live checks on this rung |
|---|---|---|
| 1 | Lv70 3rd job | P7 Part B run 6, the two-owner check (COMPANION_BOSSING_P7B.md:115-126). |
| 2 | Lv100 3rd job, 60% gear | P11 Normal Balrog (decision 3). Later on this rung: P13 balance for Jr. Balrog, Eliza, Snack Bar, Kimera and Snowman (Lv80-90). P14 balance for Capt. Latanica and the Neo City bosses. |
| 3 | Lv120 4th job | P15 balance and its two-owner check. P16 balance for Pianus, Leafre, Masteria and Zipangu without Female Boss. P17 balance. P19 balance (gate Lv100). |
| 4 | Lv140 | P16 balance for the Temple of Time and Female Boss. |
| 5 | Lv160 | The P18 and P20 two-owner checks. |

- Crew A has no Lv100 rung any more. P11's check runs on crew B, so P8's Lv160 step no longer waits for P11.
- Crew B's rung 1 must pass P7 Part B run 6 before rung 2.
- Crew B stays on rung 2 until the P13 and P14 balance runs there are done or dropped.
- On rung 2 the P14 runs are 15 or more levels above the old Lv80 and Lv85 rungs. They check the floor of the fight, not its balance.
- The seven bosses from Lv60 to Lv77 get no balance run (Question 1). Bosses under Lv60 get none either (decision 5): Mano, Stumpy, Deo, Faust, Giant Centipede, King Clang and the Timers.
- If a phase is not ready when crew B passes its rung, that balance run is dropped and noted in the hand-over.
- Boss levels come from Mob.wz info/level, for example Mushmom 6130101 Lv60 and Jr. Balrog 8130100 Lv80.

## Phase order

| Phase | Plain name | Depends on | Effort | Why in this position |
|---|---|---|---|---|
| P11 | Normal Balrog with companions | P7 (live), and P8 steps 1, 9 and 13 | M | It shares Mu Young, the arena and the approach store with Easy Balrog. Its live check uses crew B at Lv100, so it does not wait for P8's check. |
| P12 | Shared boss groundwork, the remainder | P8 exit: the mirror core and Agent mirror, the G2 guard, `BossMirrorGeometry`, `BossTargetRules`, the quota hook, the never-target and marker guards, the client-load lever. And P11: boss-rules.json, the Normal Balrog filter and the multi-boss approach rule. | L | Every later boss needs the mirror beyond Zakum, the death modes and the run rules. Building them once avoids copies. |
| P13 | Low-level field bosses (Victoria, Ossyria, Ludus Lake, Mu Lung, Nihal) | P12 | M | Friends meet these bosses while hunting. They need no registration. Control steal affects them today. |
| P14 | First party bosses: Capt. Latanica and the five Neo City bosses | P12 | L | It is the cheapest party-instance path. Stock registration already admits companions, and there is no quota. It is the first phase that needs G4. |
| P15 | Papulatus | P14 | L | It reuses the P14 party-instance run. It adds bombs, a flying phase, a closing gate and a 2-a-day quota. |
| P16 | High-level field bosses (Pianus, Leafre, Temple of Time, Zipangu, Masteria) | P13 | L | It reuses the P13 field rules. It adds deadly attacks, area attacks, reflect and banish. |
| P17 | Scarlion and Targa | P12 | L | It is the first expedition NPC after Mu Young and Adobis. It proves the generic expedition flow and the re-enter mode. |
| P18 | Horntail | P17 for the generic expedition flow, and P8 for measured Lv160 damage | XL | It is the biggest fight. It needs the multi-part mirror and measured Lv160 damage. G4 and G6 come from P12. |
| P19 | The Boss (Showa expedition) | P17 and P16 | L | It reuses the Scarga flow. P16 puts Female Boss and Male Boss 9400122 on the field lists, and the Showa recruit map needs that. |
| P20 | Pink Bean | P18 | XL | It has the largest HP pool. It needs P18's multi-part scale. The revive-inside mode is G6, from P12. |

- P11 needs three P8 steps. Step 1 is the G13 gear tool. Steps 9 and 13 give rule B its behaviour.
  - P11 applies rule B, which is P8's two run flags. The flags are only data today (AgentBossRunDefinition.java:26-27).
  - Their behaviour comes in P8 step 9 (exit NPC after the clear) and step 13 (owner-left) (p8_plan.md:525-533, :566-573).
- P11 builds on feat/companion-bossing-p8 after P8 step 13, or after P8 merges.
- Its live check needs `!bossgear` live, so P11 ships with or after P8's merge.
- P11 and P8 append to the same slots (catalog, entrance, keys). Merge by appending (p8_plan.md R14).
- P12 builds on P11. It extends boss-rules.json, which P11 creates. It moves P11's Normal Balrog filter into G3. It builds on P11's multi-boss approach rule (G9 item 2).
- P13 and P14 can be built in parallel. P16 and P17 can be built in parallel.
- Crew B's ladder still sets the order of their balance runs.
- The LAA test runs before P8's first live Zakum fight. Every big phase's live check then uses the client memory gate.

## Bosses by mechanism, and the P7/P8 seams each one reuses

Seam names in the table:

- **Boss-run service:** P7's `AgentBossRunService`, `AgentBossRunSession` and `CompanionBossRuns`.
- **Entrance registry:** P7's `BossEntrance`, the entry NPC or portal per boss, with its approach map (BossEntrance.java:26-44). The store is the catalog supplier on that map (BossEntrance.java:101-115).
- **Server behaviour:** forced server authority through a `BossActorBehavior` (P7).
- **Mirror:** the P8 mirror, with its scope widened in P12.
- **Death mode:** from "Death modes by boss".
- **Restock:** an approach store or the desk trip (G9).
- **NO_BOSS:** companions leave boss drops to humans (P7).
- **Kill switch:** a `COMPANION_BOSS_ENABLED_TYPES` value.
- **Run records:** P7's `CompanionBossRun` plus `CompanionRunRow` rows.

| Boss | Mechanism | Phase | Boss-run service | Entrance registry | Server behaviour | Mirror | Death mode | Restock | NO_BOSS | Kill switch | Run records |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Normal Balrog | expedition | P11 | yes | yes, Normal branch of Mu Young | yes | fallback | wait outside | approach store at Ant Tunnel Park 105070001, store 1061001 | yes | balrog_normal | yes |
| Scarlion and Targa | expedition | P17 | yes | yes, Aldol 9270047 | no | yes | re-enter by NPC | approach store at Trend Zone Metropolis 550000000 | yes | scarga | yes |
| Horntail | expedition | P18 | yes | yes, Mark of the Squad 2083004 | no | yes | re-enter by NPC | approach store at Leafre 240000000 | yes | horntail | yes |
| The Boss (Showa) | expedition | P19 | yes | yes, Konpei 9120201 | no | yes | wait outside | approach store at Showa Town, shop 801000300 | yes | showa | yes |
| Pink Bean | expedition | P20 | yes | yes, 2141001 | no | yes | revive inside | approach store at Leafre, before the ride | yes | pinkbean | yes |
| Capt. Latanica | party instance | P14 | session from the portal hook, stock party registration | yes, portal | fallback | yes | wait outside | approach store at Boat Quay Town 541000000 | yes | latanica | yes |
| Neo City bosses | party instance | P14 | session from the portal hook, stock party registration | yes, portal | no | yes | wait outside | approach store at Leafre | yes | one per boss | yes |
| Papulatus | party instance | P15 | session from the portal hook, stock party registration | yes, portal | no | yes | wait at the gate | desk trip | yes | papulatus | yes |
| Low-level field bosses | field | P13 | no | no | no | yes, field scope | none, G14 rule | standing restock | already outside instances | one per region group | no |
| High-level field bosses | field | P16 | no | no | no | yes, field scope | none, G14 rule | standing restock | already outside instances | one per region group | no |

Outside an instance, companions already leave boss drops to their owner (AgentLootPolicy.java:81-83, CompanionSettings.java:26).

**Why the mirror is the default, not server behaviour:**

- The mirror keeps boss movement native, and the human sees stock behaviour.
- Server authority replaces native movement. That is a poor fit for walking or flying bosses such as the Papulatus Clock, Papulatus, Pink Bean, Furious Scarlion and The Boss.
- Bosses that spawn during instance setup would be server-owned in every run once registered, even in runs with only humans. This applies to the Neo City bosses (TD_Battle1.js:121, ServerMobAutonomyService.java:602-626). The force flags are per-behaviour constants (BossActorBehavior.java:20-27).
- The server region mask covers 16 regions (ServerMobAutonomyService.java:470-478). Horntail attacks reach areaCount 31.
- Normal Balrog is the exception, because its parts are stationary. It follows Easy Balrog's server authority.

## Shared groundwork

**Status of each item:**

| Item | Built in P7 (live) | Built in P8 (building now) | Left for P12 or a later phase |
|---|---|---|---|
| G1 mirror | – | core decode seam, Agent mirror, `BossMirrorGeometry`, catalog fixes, `!bossmirror` | scope beyond Zakum (P12), the blast step (P12) |
| G2 control-steal block | L4 for server-owned bosses | the guard for listed expedition types and for listed ids off events, with the DISTANCE_HACK path | listed ids inside events, the revived-form guard (P12) |
| G3 never-target list | the Easy fake-body guard | fakes in runs, Zakum's `BossTargetRules` | per-boss lists and priority in the catalog (P11 starts, P12 generalizes) |
| G4 immunity and reflect | the Easy reflect stop | the Zakum immunity drop | every boss, skills 143-145 (P12) |
| G5 disabled states | – | – | all of it (P12) |
| G6 death modes | wait outside (the DIED path) | wait in El Nath, no death-chain change | re-enter by NPC, revive inside (P12) |
| G7 run lifecycle | the seam, rule A, rule C | owner-left rule, exit NPC after the clear | the `forEvent` index, seam answers (P12) |
| G8 quota | – | the hook for expeditions | the Papulatus party path (P12, P15) |
| G9 restock | `BOSS_STANDARDS`, the Easy approach store | Glibber, held trips, caps that reach the standard | other approach stores, multi-boss approach maps, the Papulatus desk trip (P11, P12, P15) |
| G10 loot | NO_BOSS in runs, the ally drop owner | the Eye of Fire marker guard | marker list in the catalog, party-instance runs (P12) |
| G11 no-summon | INSIDE members | – | registered members and owner-beside cases, only needed on 801040004 (P12) |
| G12 records and friendship | – | – | all of it (P12) |
| G13 gear tool | – | `!bossgear` | – |
| G14 field rule | – | – | all of it (P13) |
| G15 kill switch | `COMPANION_BOSS_ENABLED_TYPES` | the `zakum` key | one key per later boss or field group |

Rules for every item:

- G3, G4, G5, G11 and the once-per-run BOSS points act only in companion boss scope.
- Per-boss id lists go in the boss rules catalog. The G2 lists are the exception. They are the agent-engine.yaml keys from P8.
- Durations and switches go in agent-engine.yaml, each with Values, Purpose and Technical comment lines.
- No new `private static final` number goes into server.agents. AgentBalrogDefinition.java:9-21 shows the static-id pattern that these lists must not copy.

**G1. Mirror beyond Zakum (P12).** P8 builds the mirror for Zakum. P12 widens its scope.

- **What P8 gives** (p8_plan.md section 4):
  - the decode of raw activity 24-41 and a dedup per part and attack index,
  - the impact on the timer at attackAfter, and the hit on the target's mailbox,
  - the shown-hit cap, OBSERVE mode, calibration and the per-minute summary.
- **Event scope.** P8 mirrors only parts whose event expedition has an open run (`AgentBossRunRegistry.forExpedition`). P12 adds party-instance runs through the new `forEvent` index (G7).
- **Field scope.** P12 adds a second scope: a field map where the controller and the bot both have no event instance.
  - It hits every Agent in range, not only run members. Decision 19 keeps citizens in.
  - It acts only for a listed boss whose field key is on (G15).
  - P12 widens P8's client-load floor, `ATTACK_INTERVAL_FLOOR_MS`, to this scope, for listed bosses whose field key is on. P8's floor acts only inside a run on its battle map (p8_plan.md:577).
- **9-of-9 attacks.** P8's geometry already hits every region when attackCount is at least areaCount (BossMirrorGeometry.java:36-37). This covers Pink Bean 8820001 attack1.
  - Server authority still hits only the base box (BossAction.java:32-34). It changes only if the P7 live check shows hits outside x -400 to 400.
  - If it does, P11 makes the change in the shared `EasyBalrogBehavior`, so it also changes Easy Balrog. BossAction.hasDistributedRegions does not change.
- **Region masks past 16.** pOption is 16 bits, so a mask can name only regions 0-15 (BossMirrorGeometry.java:39-48). Unconfirmed: how the client marks regions 16-30 of a Horntail attack. P18 reads the calibration counts.
- **Self-destruct blasts:** at the self-destruct kill (MapleMap.java:1471-1477), the blast step applies the bomb's own attack1 box and PAD to bots in range.
  - Papulatus bombs 8500003 and 8500004 have attack1 (-65,-100)-(65,0). Bloody Boom 8510100 has attack1 (-180,-106)-(180,0).
  - Unconfirmed: the client blast uses attack1. The Papulatus bombs have selfDestruction action 3.
  - It logs each blast, so that P15 can compare it with a human's hits.
  - If P15 cannot confirm the shape, the blast step stays off. "Bots take no bomb blasts" then stays a known gap.

**G2. Control-steal block (built in P8, extended in P12).**

- **What P8 builds** (AgentBossScope.java:35-42, :81-99). A part is guarded while `AgentBossScope.ENABLED` is true, the server does not own it and no Agent controls it, in one of three cases:
  1. It is boss-flagged and its event belongs to an expedition of a listed type (`EXPEDITION_TYPES`, 'ZAKUM').
  2. Its id is on the boss id list (`BOSS_IDS`, 'none') and its map has no event.
  3. It stands on the GM trial map of `!bossmirror trial on`.
- A guarded part refuses Agent physics on a bot hit (ServerMobAutonomyService.java:268). A far Agent hit on it raises no DISTANCE_HACK and no position resend (AbstractDealDamageHandler.java:247).
- **What P12 adds:**
  - **Case 4:** a listed id inside an event that has an open companion run. Party-instance bosses (Latanica, Neo City, Papulatus) and non-boss adds inside expeditions need it.
  - **Revived-form guard.** Unconfirmed live: a revived form can pass to a bot controller. A revived form goes to the previous controller (Monster.java:839-840), and `aggroSwitchController` has no bot guard (Monster.java:2256-2297). P12 adds a BotClient guard for mobs that the G2 guard covers. EPQ's Poison Golem forms keep today's path.
- **What each phase adds:**
  - P13 and P16 add each field boss to the boss id list.
  - P14 adds the Latanica and Neo City ids. P15 adds 8500000-8500004.
  - P17 adds `SCARGA` to the expedition type list and the Scarga adds to the boss id list. P18 adds `HORNTAIL` and any non-boss Horntail add. P19 adds `SHOWA`. P20 adds `PINKBEAN`.
- Normal Balrog needs no entry. It is server-owned while companions are registered, and the guard never covers a server-owned part.
- **Test:** the guard never covers 9300003, 9300012, 9300039, 9300180-9300182, 9300105-9300107 or 9300119. P8 step 4 already tests this list.

**G3. Never-target list.**

- P8 adds `AgentBossRunRegistry.neverTarget` (p8_plan.md step 9). In a run that sets the flag, a fake mob is never a target. This includes secondary and projectile targets.
- P8's `BossTargetRules` is pure and Zakum-only (p8_plan.md step 10).
- P11 creates the boss rules catalog file with the Normal Balrog entry, and a Normal Balrog filter that reads it.
- P12 generalizes `BossTargetRules` and P11's filter into per-boss lists in the catalog, read through the G7 seam. The list:
  - the Balrog fake body 8830000 until it becomes real.
  - the dummy 8830006.
  - the helpers 8830004 and 8830005.
  - the Horntail display parts 8810010-8810018.
  - the Pink Bean statues 8820000 and 8820019-8820023.
  - fake Low Darkstars 8500004. Low Darkstars spawn fake (MobSkill.java:374-375). Fakes are normal bot targets today (fact 11). P15 sets P8's never-target-fakes flag for the Papulatus run.
- The same catalog holds a target priority list per boss. P19 uses one.

**G4. Immunity and reflect stop (companion boss scope only).**

- P8's `BossTargetRules` drops a target that is immune to the member's damage type, for Zakum.
- P12 makes that rule general. A target may hold an immunity that matches the bot's damage type. The bot then switches to another target or waits.
- P12 generalizes the Easy reflect stop (AgentEasyBalrogScenario.java:146-150) to skills 143, 144 and 145. The ids go in the boss rules catalog.
- `USE_ANTI_IMMUNITY_CRASH` stays off (decision 12).
- P14 is the first phase that needs it (Dunas, Nibelung, Oberon).
- EPQ does not change. The EPQ Poisoned Stone Bug 9300173 on 930000200 has physImmune 140 (Mob.wz/9300173.img.xml:28). It runs under EpqPoisonedStoneBugBehavior and is never in companion boss scope.

**G5. Disabled states (P12, companion boss scope only).**

- Agents read only SEAL and ZOMBIFY (AgentCombatPlanRuntime.java:29, :84). Nothing checks stun, seduce or confuse (Character.java:3219, :3234).
- New rules: a stunned bot stands still. A seduced bot walks toward the caster. A confused bot moves the wrong way.
- **Banish:** Dodo 8220004 and Munin 8820006 banish a target to the spawn point of the same map (Character.java:1694-1702). After such a warp, the bot drops its path and plans again from where it lands. Unconfirmed: how an Agent handles a same-map warp today.
- EPQ does not change. Poison Golem 9300182 has confuse 132 (Mob.wz/9300182.img.xml:52), and EPQ is never in companion boss scope.
- **Test tool:** the stock `!debuff` skips Agents (fact 19). G5 adds a GM6 form, `!debuff <state> <companion>`, which acts only on the named companion. BANISH is one of its states.

**G6. Death modes.**

- **Wait outside is built.** P7's DIED path needs no death-chain change. P8 uses it for Zakum, with the wait in El Nath. P11, P14, P15 and P19 use it too.
- **P12 builds the other two modes.**
- **Re-enter by NPC (Scarga, Horntail):**
  - Today a dead member cannot stay in a run. It revives outside the event and goes through `handBack`, then `memberOut`, then `leftRun`. That sets phase OUT for good (AgentBossRunSession.java:381-382, AgentBossRunMember.java:246-249).
  - The session also ends once no member is REGISTERED, INSIDE or LEAVING (AgentBossRunSession.java:350-353). P8's own re-entry design needed a REENTERING phase too (p8_plan.md:584).
  - So re-enter mode adds a REENTERING member phase to `AgentBossRunSession`. A dead member of a re-enter run stays in the run, and the session does not end while one is REENTERING.
  - Re-enter uses the stock respawn to town. It needs no death gate.
  - The bot walks back and runs the recruit NPC headless. It works only for non-leaders (fact 7), and only while canJoin is 1. Horntail adds the decision-11 waiver warp to 240050400.
  - A Horntail re-entry lands on 240060000, not at the fight. The bot then walks through hontale_BR to 240060200 (P18).
- **Revive inside (Pink Bean):** the death gate is needed only for this mode. It is one class, `AgentBossDeathDepartureGate.respawnOrHold(agent, respawn, nowMs)`, with the same shape as the PQ gates.
  - It goes innermost, directly around `respawnAtNearestTown` inside the EPQ lambda (AgentDeathTickCoordinator.java:35-38). It is a one-line change. I will say so in the hand-over.
  - It holds only for its own runs. For every other Agent it runs the respawn at once.
  - It reads run membership and the death mode from the G7 seam. It never imports server.living.
  - The gate runs only once the respawn is due (AgentDeathTickService.java:49-50).
  - It copies the human path. It calls `eim.revivePlayer`, then respawns into `eim.getMapInstance(returnMapId)`. The human path is ChangeMapHandler.java:103-106, then Character.java:8794-8795 and :1704-1714.
- **No Resurrection.** Companion Bishops never cast it (decision 25). Dead companions revive outside, as in P7.

**G7. Boss run lifecycle and the boss-run seam.**

- **Built in P7:**
  - Each run keeps the run key `boss:<key>:<sessionId>:<ownerId>` with Commitments, like the PQ key. Two owners in one run need the owner part.
  - **Rule A:** when no human is left in the run, companions leave and rejoin outside (`watchHumans`, AgentBossRunSession.java:427). This is user decision 9, applied to every boss, after a clear too. Runs end only at 1 player or fewer, and bots count (EventInstanceManager.java:1267-1274).
  - **Rule C:** registered companions stay on the recruit map until the start (fact 13).
- **Built in P8:** the owner-left rule (`Out.OWNER_LEFT`, `ownerLeavingTakesParty`) and the exit NPC after the clear (`exitNpcAfterClear`). Together they are rule B for Zakum: when the owner leaves, before or after a clear, the owner's party companions leave too, unless a same-party human stays. Bench allies stay (p8_plan.md step 13, question 8).
- **Left for P12, with the portal hooks in P14 and P15:**
  - **Rule B for every boss.** Each boss row sets the two P8 flags. Normal Balrog (P11) needs rule B after the clear, because exit NPC 1061018 warps only the talker (1061018.js:35).
  - **Party-instance runs.** P7's registry is keyed by Expedition (`forExpedition`, AgentBossRunRegistry.java:69). Latanica, Neo City and Papulatus have no expedition.
    - P12 adds a `forEvent(EventInstanceManager)` index to `AgentBossRunRegistry`. P12's G1 event scope, G2 case 4 and G10 party-instance loot all need it.
    - P14 and P15 only add the portal hooks that open the session. Each hook opens one `AgentBossRunSession` for the run, keyed by the EventInstanceManager. Each companion that `registerParty` admits becomes a member.
    - A party event has no sign-up wait. An admitted companion becomes INSIDE once it stands in the event.
    - The hook applies the owner cap, `COMPANION_BOSS_OWNERS_MAX`, per event. `ownerFits` takes an Expedition today (CompanionBossRuns.java:533-537), so P12 adds an event form beside `forEvent`.
    - Without this session, G3, G4, G5, G6, G7 rules A and B, G10 and G11 never act in P14 and P15.
  - **Seam answers.** G7 extends `AgentBossRunHost.Rule` with the death mode, engagement and target exclusions. It adds no second install point. For one Agent, the seam answers four questions:
    - Which boss run is it in, if any? `AgentBossRunRegistry.forMember` answers this.
    - Which death mode applies?
    - May it engage this mob now (G14)?
    - Which extra mobs does this run exclude as targets?
  - The death gate, the G3 filter, G4, G5 and G11 read only this seam and the boss rules catalog.

**G8. Quota.**

- **Built in P8 for expeditions** (decisions 6 and 7). Companions spend their own daily entry, logged only when they enter. Humans keep the stock path.
  - A pre-check on companion-ops refuses a capped companion before it enlists. It says one quota line.
  - At sign-up, `attemptBoss(false)` answers from a per-run cache, so no JDBC runs inside the NPC script lock (p8_plan.md F6, section 5).
  - At the start, companions of the open run are not logged. Each companion's entry is logged once, when it is adopted inside.
  - The hook sits after the boss lookup in ExpeditionBossLog, so Easy and Normal Balrog never reach it (ExpeditionBossLog.java:51-55, :198-201).
- The hook works for any expedition with an open companion run. Scarga, Horntail and Pink Bean get it with no new code.
- **Left for P12, used by P15:** the Papulatus party path.
  - Today one capped member blocks the whole party with a system-style message (Populatus00.js:38-42). The portal then logs every member (Populatus00.js:47).
  - Both stock helpers act on every party member, wherever they stand. `firstPartyMemberOverBossCap` checks each one, and `logPartyBossAttempt` logs each one (AbstractPlayerInteraction.java:1116-1128, :1135-1144).
  - G8 changes both helpers for companions only. A companion that is not on the leader's map is not checked and not logged. Humans keep the stock check and log (decision 7).
  - A capped companion then steps off the gate map before the owner enters. It says one player-like line, for example "already went in today. i'll wait out here."
  - Off the map, registerParty does not register it (EventInstanceManager.java:406-415).

**G9. Restock.**

- **Built in P7:** the `BOSS_STANDARDS` key (agent-engine.yaml:4577), read after `PARTY_QUEST_STANDARDS`. The Easy Balrog approach store at Ant Tunnel Park (fix L7).
- **Built in P8:** the Glibber approach store for Zakum and the held trips in Adobis's room (`holdsTripsAtEntry`). P8 also lets the caps reach a big standard, for boss runs only.
- **Left:**
  1. **Approach stores for every later boss.** A boss row names an approach map. The store is the catalog supplier on that map (BossEntrance.java:101-115). The table below lists them.
     - Boss rows whose shop is on another map (Leafre, Showa Town) need a store NPC field, or a supplier search within STANDING_MAX_HOPS for ally provisioning.
     - The reason: `approachStoreNpcId` returns a store only when a catalog supplier stands on `approachMapId` itself. The record has no store field (BossEntrance.java:26-27).
     - The Leafre supplier stands on 240000002 (NPC 2080001), and the Showa supplier on 801000300 (NPC 9120002) (potion-supplier-catalog.json:26, :36). Without the field, bench allies get no store on arrival.
  2. **Multi-boss approach maps (P11 first).** P8's lookup returns one boss key per approach map. Ant Tunnel Park serves Easy and Normal Balrog, and Leafre serves three bosses. Such a map buys the largest standard among the enabled bosses whose level gate the companion meets.
  3. **Held trips per boss row.** A boss row sets `holdsTripsAtEntry`, as P8 does for Zakum. This replaces adding recruit maps to `COMPANION_RESTOCK_BOSS_MAPS`, which would change behaviour with the switch off (p8_plan.md, judge defects).
  4. **The Papulatus desk trip (P15).**
     - 220080000 is already a boss gate with no trips (config.yaml:904). There, `restocksFrom` returns false (CompanionRestock.java:123-127).
     - A per-map "trips allowed" flag, `COMPANION_RESTOCK_TRIP_MAPS` in config.yaml living_world, lists only 220080000. Only a map on COMPANION_RESTOCK_TRIP_MAPS lets `restocksFrom` return true on a boss gate.
     - A boss desk entry is an agent-engine.yaml key, `server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_DESK_MAPS: '220080000'`, with Values, Purpose and Technical lines.
     - `partyQuestDesk` reads it (AgentSupplyReadinessRuntime.java:501-509), so DESK_MAX_HOPS 7 applies there.
     - The entry does not go into AgentPartyQuestCatalog, because PQ code iterates that catalog.
- A boss desk trip counts hops both ways. Both must be at most DESK_MAX_HOPS, or a slow return would miss the entry.
- An approach store is a standing restock inside STANDING_MAX_HOPS 2 (agent-engine.yaml:4545). It runs only while that boss's kill-switch key is on and the companion meets the boss's level gate.

| Boss | Recruit or gate map | Nearest supplier from the recruit map, hops out and back | Restock |
|---|---|---|---|
| Easy Balrog (P7) | 105100100 | none, no route edge leaves 105100100 | approach store at Ant Tunnel Park 105070001, store 1061001 (built) |
| Zakum (P8) | 211042400 | 22 hops out, no route back (p8_plan.md F10) | approach store at Ice Valley II 211040200, Glibber 2030009 (P8) |
| Normal Balrog | 105100100 | as Easy Balrog | approach store at Ant Tunnel Park, shared with Easy Balrog |
| Papulatus | 220080000 | 220050300, 6 out and 6 back | desk trip (decision 10) |
| Capt. Latanica | 541010060 | 541000000, 6 out and 9 back | approach store at Boat Quay Town 541000000 |
| Neo City bosses | one gate map per boss: 240070202, 240070302, 240070402, 240070502 and 240070602 | 240000002, 7 out and no path back from every gate map | approach store at Leafre 240000000, before the Time Gate |
| Scarlion and Targa | 551030100 | 550000000, 11 out and 11 back | approach store at Trend Zone Metropolis 550000000 |
| Horntail | 240050400 | none, no route edge leaves 240050400 | approach store at Leafre |
| The Boss | 801040004 | none, no route edge leads from 801000000 to the hideout | approach store at Showa Town, shop 801000300 |
| Pink Bean | 270050000 | none, the search reaches only 270xxxxxx maps | approach store at Leafre, before the ride |

- Hops come from a breadth-first search over world-portal-route-graph.json and victoria-portal-route-graph.json, with suppliers from potion-supplier-catalog.json.
- Kampung 551000000 has no supplier. npc-travel-legs.json has no Time Gate 2083006 leg.

**G10. Loot.**

- **Built in P7:** NO_BOSS inside every companion boss run, and the drop owner of an ally's kill. A kill whose top damager is a bench ally goes to that ally's owner, or the heir after a logout handoff (COMPANION_BOSSING_P7B.md:32-33). Damage credit and EXP do not change.
- **Built in P8:** the marker guard. No run member picks up a listed marker item, such as the Eye of Fire 4001017 (p8_plan.md step 9).
- **Left for P12:**
  - The marker list moves to the boss rules catalog. Pink Bean adds 4001193 (PinkBeanBattle.js:260-266). The server ignores the WZ pickUpBlock flag.
  - NO_BOSS and the drop owner also act in party-instance runs, through `forEvent` (G7).

**G11. No-summon rule (companion boss scope only).**

- The rule: a bot skips summon skills on a map whose fieldLimit has the SUMMON bit (FieldLimit.java:29).
- **Built in P7 step 6b:** INSIDE members of an `AgentBossRunSession` (AgentCombatSummonRuntime.java:154-158).
- **P8 needs no more.** Summons need a live monster (AgentCombatSpecialMoveTickRuntime.java:61-64), and Adobis's room holds none (p8_plan.md F11).
- **Left for P12:** a REGISTERED member, and a companion on a boss recruit or gate map beside its owner. Of the recruit and gate maps with the bit, only Showa's 801040004 holds monsters: Male Boss and 14 x 8140500 (Map8/801040004.img.xml). 541010060, 270050000 and 211042400 hold none.
- Maps with the bit, inside a run:
  - Easy Balrog 105100400 (P7), and Normal Balrog 105100300 and 105100301.
  - Zakum's altar 280030000 (P8).
  - Horntail's main map 240060200 (Map2/240060200.img.xml:2374). The pre-head maps 240060000 and 240060100 do not have it.
  - Pink Bean 270050100.
  - Latanica's battle map 541010100.
  - Scarga's battle map 551030200.
  - Showa's battle map 801040100.
  - the Neo City battle maps.
- KPQ, HPQ and LPQ maps have the bit too (fact 12). They are never in companion boss scope, so bots there still summon. A guard test proves it.

**G12. Records and friendship (P12).**

- Boss keys stay in `CompanionBossKeys`. `CompanionRunRecorder.load` also reads the keys of `AgentBossRunCatalog.all()` (CompanionRunRecorder.java:41-42, AgentBossRunCatalog.java:39). `CompanionPqKeys` does not change.
  - The reason: `CompanionPqKeys.KNOWN` is the list that `COMPANION_PQ_ENABLED_KEYS` is checked against (CompanionPqKeys.java:6-12, LivingWorldConfig.java:534). A boss key there would let the PQ kill switch accept it.
  - `CompanionRunRecorder.load` loops over `PqEntrance.keys()` today, not `CompanionPqKeys`.
- Boss events skip the PQ-clear hook and write boss records instead (decision 2). This also stops the 'Balrogundefined' and 'Zakum<channel>' PQ rows of P7 and P8.
- **Human impact:** this also changes human players' PQ records and friendship PQ points. The hook runs for every player in the instance, not only companions (EventInstanceManager.java:865-871, bosses_plan_final.md:60). After P12, a human's boss clear no longer counts as a PQ clear.
- A companion boss run gives BOSS points once per run (decision 23).
- Kills outside those runs keep today's per-kill rule, capped at 3 a day (Friendship.java:177-201). King Slime 9300003 (Mob.wz/9300003.img.xml:43), Alishar 9300012 (:21) and Poison Golem 9300182 (:20) are boss=1 and keep earning points as today.

**G13. Gear tool (built in P8 step 1).**

- `!bossgear <me|name> [preview]` forges one piece per slot with every upgrade slot holding a passed 60% scroll (`FriendForge.recipe` with `ScrollRecipe(60, REST)`, FriendForge.java:117-147).
- Its items are untradeable and tagged "bossgear" (P8 question 9 default).
- It follows the same owner rule as `!setlevel`. ohboy uses it for his own companions while he is GM6 (decision 1).
- GM scrolling is not realistic, because `USE_PERFECT_GM_SCROLL` is true (config.yaml:349).

**G14. Field engagement and stop rule (built in P13).**

- Companions engage a field boss only after the owner's party has hit it. They stop after one kill (decision 17).
- Living installs this rule through the G7 seam, because it needs owner and party knowledge.
- Without it, the 2-4 s respawn makes companions farm a boss without end (fact 9). The respawn time stays (decision 20).
- Dead companions rest before they follow again (decision 18). Follow-warp would send them straight back at 50 HP (AgentFollowMapSyncService.java:33-87).
- A citizen never engages a mob on the boss id list, but takes mirrored hits (decision 19). The same seam carries this rule.
- The rule also applies on a recruit map that holds a field boss. Showa's 801040004 is one (P19).

**G15. Kill switch.**

- There is one `COMPANION_BOSS_ENABLED_TYPES` value per boss or field group. A new key ships off.
- Each phase adds its keys to `CompanionBossKeys.knownKeys()` (CompanionBossKeys.java:18-23) and widens the key's meaning to portal bosses and field groups.
- A startup check refuses an enabled field or party key whose ids are not on the boss id list. It refuses an enabled expedition key whose type is not on the expedition type list. Easy and Normal Balrog are exempt, because they are server-owned.
  - Without that exemption the check would refuse the live config. `easy_balrog` is on, and BALROG_EASY is not on the expedition type list (agent-engine.yaml:5751-5752).
- A field key gates three things for the bosses of its group: the G1 field mirror, the G14 rule and the citizen rule.
- The G2 guard does not read the kill switch. It has its own switch, `AgentBossScope.ENABLED`.
- Keys can depend on each other. The startup check also refuses `showa` without `field_zipangu`, because G14 must hold companions off Male Boss 9400122 on 801040004 (P19).
- Keys per phase:
  - P8: `zakum` (built in step 2).
  - P11: `balrog_normal`.
  - P12: none. Its spot check uses the P8 GM trial instead of a key.
  - P13: `field_victoria`, `field_ossyria`, `field_ludus`, `field_mulung` and `field_nihal`.
  - P14: `latanica`, and `neo_bergamot`, `neo_dunas`, `neo_aufheben`, `neo_oberon` and `neo_nibelung`.
  - P15: `papulatus`.
  - P16: `field_pianus`, `field_leafre`, `field_temple`, `field_zipangu` and `field_masteria`.
  - P17 to P20: `scarga`, `horntail`, `showa` and `pinkbean`.
- A boss whose live check fails stays off. A big boss stays off until its client memory gate passes.

## P11: Normal Balrog with companions

- **Damage: server behaviour, with the mirror as fallback.**
  - Make `EasyBalrogEncounterService` a variant with Normal ids (EasyBalrogEncounterService.java:24-30).
  - With decision 4, BalrogBattle.js asks the service at start whether companions are registered. If none are, it runs the stock code unchanged.
  - Add three `EasyBalrogBehavior` subclasses for 8830000, 8830001 and 8830002 (EasyBalrogBehavior.java:19-40).
  - Add the adds 6400005 and 6400006 to `BalrogSummonedAddBehavior.MOB_IDS` (:21).
  - The force flag is per encounter, because behaviours are singletons. P7 built that seam: a `Predicate<MapleMap>` that `EasyBalrogEncounterService.installServerAuthorityRule` installs. P11 reuses it for the Normal encounter.
  - The Normal body follows the Easy body's Fix A and Fix B, including the `!balrogprobe telegraph` setting (BalrogProbeCommand.java:77-91).
  - The body's attack1 is 9 of 9 regions (fact 17). Server authority keeps the base box unless the P7 live check reports wider hits.
  - If P7 falls back to the mirror, Normal Balrog follows it.
- **Registration:**
  - Use the boss-run service with type BALROG_NORMAL(6,30,50,255,5) (ExpeditionType.java:33). Add a `balrog_normal` slot to `AgentBossRunCatalog` and `BossEntrance`.
  - Pass the key of the chosen expedition type in all five hooks (1061014.js:40, :44, :48, :52, :56). Today each one hard-codes "easy_balrog", and the two offers also test `BALROG_EASY`.
  - Widen the member branch at :122 to BALROG_NORMAL. Today only a BALROG_EASY member gets the companion choices (1061014.js:122-131).
  - P7 hooks must branch on the expedition type, not on the map. Easy and Normal share the NPC and 105100100.
  - The minimum is 6, which is the owner plus 5 (config.yaml:279).
- **Prerequisites:** Mu Young checks Lv50 only for the character who talks (1061014.js:91). `Expedition.addMember` checks no level (Expedition.java:207-229). Living code mirrors the Lv50 gate for companions.
- **Quota:** none (ExpeditionBossLog.java:51-55, :198-201).
- **Death:** wait outside at 105100100 through P7's DIED path. Stock re-entry is broken (see "Death modes by boss").
- **Post-clear:** on Normal, exit NPC 1061018 warps only the talker to 105100301 (1061018.js:35). On Easy it warps the team (:33). P11 sets rule B for this run (G7).
- **No summons:** 105100300 and 105100301 have the SUMMON bit (fieldLimit 271100). P7 step 6b already covers INSIDE members.
- **Restock:** the Ant Tunnel Park approach store, shared with Easy Balrog. The standard is `balrog_normal=300/300` with 2500 projectiles, because the body has a deadly attack ("Boss supply standards").
  - P11 builds the multi-boss approach map rule (G9 item 2). With both keys on, a Lv50+ companion buys to the larger standard.
  - A room trip cannot work. No route edge leaves 105100100. From 105100000 the nearest supplier is the 24 Hr Mobile Store on 105070001, 9 hops away.
- **Targets:** P11 creates src/main/resources/agents/catalogs/boss-rules.json with the Normal Balrog entry. Its filter reads the ids below from that file, not from static constants. P12 extends the file and moves the filter into G3.
  - Claw 8830002 first, then claw 8830001, then the body once it is real. The adds 6400005 and 6400006 are also targets.
  - Never target the fake body 8830000 before it is real, the dummy 8830006, or the helpers 8830004 and 8830005.
  - Bots stop hitting the body during reflect. Skill 145 level 3 reflects 5000 per physical hit and 8000 per magic hit for 7 s, every 60 s, from 95% HP (8830000.img.xml:30-32, MobSkill.img.xml:12620-12633).
- **Touch:** the body never touches a bot on the floor (fact 10). The claws do.
- **Sealed form:** keep no sealed form, which matches what players see today. Unconfirmed at runtime: the sealed-form spawn fails on scope (BalrogBattle.js:148, :157).
- **Test party:**
  - Crew B on rung 2: ohboy and his five companions at Lv100 3rd job, all with 60% gear (decision 3).
  - Bossfriend stays outside the expedition. He only runs GM commands.
  - No bench-ally check and no second owner. P7 Part B already checks both on the same NPC and service.
  - `balrog_normal` stays off COMPANION_BOSS_ALLY_TYPES until an ally stage passes the client memory gate. P11's live check runs only the owner-plus-5 stage. Step 8 of the gate turns a key on for friends only after its largest stage passes.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `balrog_normal`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Do steps 1-5 of "Setup for a crew B rung" at Lv100 with 60% gear.
  2. As ohboy, while still GM6, run `!givemeso <name> 1000000` for each companion. The 300/300 standard costs about 0.86M at Ant Tunnel Park.
  3. From Bossfriend, run `!setgmlevel ohboy 0`.
  4. From Bossfriend, run `!warp 105040300` (Sleepywood). Then run `!warphere ohboy`.
  5. As ohboy, walk down through the Ant Tunnels. Do not warp past Ant Tunnel Park 105070001.
  6. In Ant Tunnel Park, expect each short companion to say a go line, buy at the 24 Hr Mobile Store and catch up.
  7. Walk on to Mu Young's room 105100100.
  8. Start the client memory gate: the logger, and the client that friends use.
  9. As ohboy, talk to Mu Young. Pick Normal, then "Lets get this going!". Pick "My party will register too.".
  10. Expect five walk-ups and five join notices. From Bossfriend, run `!expeditions`. It lists 6.
  11. Start. Check the log for server autonomy on 8830002.
  12. Watch companions lose HP to claw touches, claw attacks and the body's attack3.
  13. Check that bots stop hitting the body while reflect is up.
  14. Check that no companion casts a summon.
  15. From Bossfriend, run `!hpmp Garrick 0` (fact 24). Check his one line and his wait at 105100100.
  16. After the clear, talk to 1061018 as ohboy. It moves only ohboy to 105100301, which is still inside the instance (BalrogBattle.js:32-35).
  17. Check whether the companions follow.
  18. Leave through portal out99 (balog_end). Check that the companions leave too.
  19. Read the "PQ potions ... pq=balrog_normal" lines. Tune the 300/300 standard from them.
- **Unit check:** the Lv50 gate refuses a companion under Lv50. No live check covers it, because no test companion stays under Lv50.
- **Effort:** M.
- **Risks:**
  - Unconfirmed live: if the target filter fails, a bot kill of the fake body counts as a claw kill. Then makeMonsterReal runs on a dead body (BalrogBattle.js:241-268, :262-265).
  - Killing dummy 8830006 releases claw 8830001 early.
  - Reflect kills bots that ignore the stop.
  - A second run can start 10 s after a clear (EventManager.java:250-257).
  - The client can crash as it does in Easy Balrog. The memory gate stops the stage.

## P12: Shared boss groundwork, the remainder

- **Builds:** what P7 and P8 did not build (see the status table in "Shared groundwork"):
  - G1: event scope for party-instance runs, field scope, and the self-destruct blast step.
  - G2: case 4 (listed ids inside events with an open run) and the revived-form guard.
  - G3: per-boss never-target lists and priority in the boss rules catalog, from P8's `BossTargetRules` and P11's filter.
  - G4: the immunity stop for every boss, and the reflect stop for skills 143-145.
  - G5: disabled states, banish and the `!debuff <state> <companion>` form.
  - G6: re-enter mode adds a REENTERING member phase to `AgentBossRunSession`. A dead member of a re-enter run stays in the run, and the session does not end while one is REENTERING. Re-enter uses the stock respawn to town. The death gate is needed only for revive inside.
  - G7: the `forEvent(EventInstanceManager)` index, the event form of the owner cap and the seam answers. P14 and P15 only add the portal hooks that open the session.
  - Client-load lever: `ATTACK_INTERVAL_FLOOR_MS` widened to the G1 field scope, for listed bosses whose field key is on.
  - G8: the Papulatus party path.
  - G9: approach stores per boss row, held trips per boss row, the trip flag and the boss desk entry.
  - G10: the catalog marker list and party-instance loot.
  - G11: the registered and owner-beside cases.
  - G12: boss records and once-per-run BOSS points.
- **Unit checks:**
  - mirror geometry against WZ ranges for the Pianus, Horntail and Pink Bean ids.
  - the field scope: a listed boss with no event hits every Agent in range, citizens included, only while its field key is on.
  - the self-destruct blast step.
  - the REENTERING phase: a dead re-enter member stays in the run, and the session does not end while one is REENTERING.
  - the revive-inside death gate.
  - the field floor: `ATTACK_INTERVAL_FLOOR_MS` acts on a listed field boss only while its field key is on, and never for a KPQ member.
  - G2 case 4: a listed id in an event with an open run is guarded. The same id in an all-human event is not.
  - G11: a REGISTERED member, and a companion beside its owner on 801040004, skip summons.
- **Regression checks for KPQ and EPQ:**
  - The G2 guard never covers 9300003, 9300012, 9300039, 9300180-9300182, 9300105-9300107 or 9300119.
  - The revived-form guard leaves EPQ's Poison Golem forms on today's path.
  - An Agent outside companion boss scope still attacks the EPQ stone bug 9300173 while it is immune, as today.
  - Confuse from 9300182 changes nothing outside companion boss scope.
  - Bots in KPQ 103000800-805, LPQ 922010100-922011000 and HPQ 910010000 still cast summons.
  - BOSS points for 9300003, 9300012 and 9300182 still count per kill.
  - G12 changes the PQ-clear hook that every event uses (EventInstanceManager.java:866, LivingWorldHooks.java:284). A KPQ, HPQ, LPQ and EPQ clear still calls `LivingWorldHooks.partyQuestCleared` and writes its PQ record and friendship PQ points. Only boss events skip it.
  - The guard tests stay green (see "Every phase").
- **GM spot check** (crew A on its Lv160 rung, no restart needed):
  1. Run `!warp 180000000`. Stay visible: do not use `!hide`.
  2. Run `!bossmirror trial on`. The trial guards every part on your map and mirrors hits onto your companions.
  3. Run `!papulatus` (GM3, CommandsExecutor.java:550). It spawns a free walking Clock.
  4. Bring one companion and hit the Clock together.
  5. Run `!botcfg physicsstatus` (GM6). Check that it shows no acquisition.
  6. Watch the companion lose HP from mirrored Clock attacks.
  7. Run `!debuff STUN <companion>` with the G5 form. Check that the companion stands still.
  8. Run `!debuff BANISH <companion>`. Check that it plans again from the spawn point.
  9. Run `!killall`, then `!bossmirror trial off`.
- **Effort:** L.
- **Risks:**
  - Unconfirmed: the v83 pOption region format. P8's calibration counts answer it for Zakum first.
  - Blocking physics on mobile field bosses removes bot knockback, which changes how solo bots fight them.
  - G12 changes human players too. Their boss clears stop writing PQ records and stop giving friendship PQ points (decision 2).

## P13: Low-level field bosses

- **Bosses:**
  - Mano, Stumpy, Faust, Dyle, King Clang, Snack Bar, Mushmom, Zombie Mushmom and Jr. Balrog.
  - Eliza, Snowman, three Timers, Zeno, Nine-Tailed Fox, Tae Roon, King Sage Cat, Giant Centipede, Deo and Kimera.
- **Damage:** the G1 field scope and the G2 guard. Eliza's attack1 is a type-3 area attack, so it uses the region fallback.
- **Boss id list:** field bosses are in no expedition, so P13 adds each one by id:
  - Victoria: Mano 2220000, Stumpy 3220000, Faust 5220002, Dyle 6220000, King Clang 5220001 and 5220000, Unknown Snack Bar 8220008 and Snack Bar 8220009, Mushmom 6130101, Zombie Mushmom 6300005 and Jr. Balrog 8130100.
  - Ossyria, Ludus Lake, Mu Lung and Nihal: Eliza 8220000, Snowman 8220001, Timer 5220003, Zeno 6220001, Nine-Tailed Fox 7220001, Tae Roon 7220000, King Sage Cat 7220002, Giant Centipede 5220004, Deo 3220001 and Kimera 8220002.
  - Unknown Snack Bar 8220008 also lacks the boss flag. The list covers it anyway.
- **Registration:** none. Companions already follow their owner.
- **Prerequisites:** none. Snowman is reached by the hidden portal hiden00 on 211040100 (Map2/211040100.img.xml:3464-3468).
- **Quota:** none.
- **Engagement:** the G14 rule. The quest bosses that NPCs weaken (AbstractPlayerInteraction.java:1314-1324) need only this rule.
- **Citizens:** decision 19. Citizens farm the maps of Faust, Stumpy, Mano, Snack Bar and King Clang (fields.yaml:34-35, :87, :160, :206, :247). They take mirrored hits but never attack those bosses.
- **Death:** G14 rest, then follow. There is no death gate, because the bot is not in an instance.
- **Restock and loot:** standing restock and `leaveBossDrops` already work outside instances.
- **Door dungeons:** add a follow-guard hold on the 677000xxx maps (see "Not planned").
- **Test party:**
  - Mechanics run: crew A on its Lv160 rung, against Jr. Balrog (Lv80). At Lv160 the mirrored hits are small, so the check reads the mirror summary line as well as the HP bar.
  - Balance run: crew B on rung 2 (Lv100), for the Lv80-90 bosses. The Lv60-77 bosses get no balance run (Question 1).
- **Kill switch:** one key per region group (G15). Jr. Balrog and Mushmom are in `field_victoria`.
- **GM-first live check (mechanics run):**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `field_victoria`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!warp 105090900` for Jr. Balrog.
  2. Hit Jr. Balrog once with Bossfriend. Check that companions join only after that.
  3. Check the mirror summary for Jr. Balrog's attacks on companions.
  4. Run `!botcfg physicsstatus`. Check that it shows no acquisition.
  5. Check that companions stop after one kill, because it returns in 2-4 s.
  6. Run `!hpmp Sorrel 0`. Check that he rests in Sleepywood 105040300 until full, then follows.
- **Memory gate stage (field boss with many bots):**
  1. From Bossfriend, run `!warp <map>` for a field-boss map that citizens farm (fields.yaml:34-35).
  2. Start the memory logger.
  3. Hit the boss with Bossfriend while citizens and crew A fight beside it.
  4. Apply the stop rule of the client memory gate.
- **Balance-run check (crew B, rung 2):**
  - If `field_victoria` is not on yet, do steps 0a and 0b above first.
  1. From Bossfriend, run `!warp 105090900`. Then run `!warphere ohboy`. His companions follow him.
  2. Hit Jr. Balrog with ohboy first.
  3. Watch a companion lose HP from mirrored attacks.
  4. Note deaths and potion use for tuning.
- **Effort:** M.
- **Risks:**
  - Jr. Balrog (MAD 605) can one-shot a Lv40 companion once mirrored.
  - Summons multiply targets.
  - Other humans may hold control of the boss, so the mirror must work for any controller.

## P14: First party bosses

### Capt. Latanica

- **Damage: mirror** on 9420513 and the adds 9420511 and 9420512.
  - All three boss attacks are ordinary magic boxes. attack3 covers most of the map (Mob.wz/9420513 attack1-3).
  - Forced authority would change the human's view.
  - Fallback: a forced WZ behaviour on the `EpqPoisonedStoneBugBehavior` template (:15-21).
- **Boss id list:** P14 adds 9420513 and the adds 9420511 and 9420512. Latanica is a party event, not an expedition, so it needs G2 case 4. The adds have no boss flag.
- **Registration:**
  - Stock party registration at portal captinsg00 on 541010060 (captinsg00.js:19-21, EventInstanceManager.java:406-415).
  - Add a living portal hook before startInstance. There is no portal hook today (LivingWorldHooks.java:530-541).
  - The hook opens the `AgentBossRunSession`, keyed by the EventInstanceManager, for the companions that `registerParty` admits (G7). It also writes the run record.
- **Prerequisites:** the owner holds White Essence 4000381 and leads the party (captinsg00.js:4, :13). Companions need nothing.
- **Quota:** none.
- **Death:**
  - The run ends for all when the owner leaves, or revives after a death. This is the stock leader rule of a party event (EventInstanceManager.java:1280-1282).
  - A dead companion revives in Boat Quay Town and waits there. There is no re-entry (see "Death modes by boss").
- **Post-clear:** Bob 9270033 is the only exit, and he warps only the talker (9270033.js:48-65). Rule B pulls companions out (G7).
- **No summons:** 541010060 and 541010100 have the SUMMON bit, so G11 applies inside the run.
- **Restock:** the approach store at Boat Quay Town 541000000 (potion-supplier-catalog.json:33). The gate's way back is 9 hops, over DESK_MAX_HOPS. Standard 150/100 with 1000 projectiles.
- **Special role:** the owner drops the essence on reactor 5411000 (5411000.js:22-27).
- **Test party:** mechanics run with crew A on its Lv160 rung. Balance run with crew B on rung 2 (Lv100).
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `latanica`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!item 4000381` as Bossfriend.
  2. Run `!warp 541000000`. If a companion is short, check that it buys there.
  3. Run `!warp 541010060`.
  4. Enter as party leader.
  5. Drop the essence on the reactor.
  6. Watch bots lose HP to the magic boxes.
  7. Run `!hpmp Flint 0`. Check that he waits in Boat Quay Town.
  8. Leave through Bob. Check the pull-out.
- **Effort:** M.
- **Risks:** Unconfirmed: whether 10 min is enough for 2M HP (LatanicaBattle.js:37).

### Neo City bosses (Bergamot, Dunas, Aufheben, Oberon, Nibelung)

- **Damage: mirror** on each form: 7220003-7220005, 8220010, 8220011, 8220012 and 8220013-8220015.
  - The mirror treats every form on its own. That avoids authority keying across revive chains.
- **Boss id list:** P14 adds 7220003-7220005 and 8220010-8220015. These are party events, not expeditions, so they need G2 case 4.
- **Registration:** stock party registration at portal right00 TD_Boss_enter. It needs 2-6 members at Lv70 or more (TD_Boss_enter.js:20-22, TD_Battle1.js:27-28).
  - Add a portal hook. It opens the `AgentBossRunSession` for the admitted companions, as for Latanica (G7), and writes the run record.
- **Prerequisites:**
  - The owner does the era quests in order (2083006.js:17-21, :86-92).
  - Companions follow-warp in, under plan decision 11a.
- **Quota:** none.
- **Death:**
  - The run ends when the leader leaves, or revives after a death, or fewer than 2 players remain (EventInstanceManager.java:1276-1285).
  - A dead companion revives at 240070000, waits there and follows the owner after the run.
- **Immunity and reflect:**
  - Dunas and every Nibelung form have physImmune, so they need G4.
  - Oberon reflects weapon hits with PCounter 143, which also needs G4.
- **Restock:** the approach store at Leafre 240000000, before the Time Gate. The Leafre shop 240000002 is 7 hops out from each of the five gate maps, and no route leads back.
  - Standards per boss: Bergamot and Dunas 150/100 with 1000 projectiles, Aufheben and Oberon 200/150 with 1500, and Nibelung 250/250 with 2000.
  - Leafre buys the largest standard among the enabled Leafre bosses (G9). For Neo City alone that is at most 250/250.
  - The store NPC stands on 240000002, not on 240000000. G9 adds the store field for this row.
- **Test party:** mechanics run with crew A on its Lv160 rung. Balance run with crew B on rung 2 (Lv100).
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `neo_bergamot`, `neo_dunas`, `neo_aufheben`, `neo_oberon` and `neo_nibelung`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!warp 240000000`. If a companion is short, check that it buys there.
  2. Warp to that boss's own gate map. For Bergamot, run `!warp 240070202`. The others are 240070302, 240070402, 240070502 and 240070602.
  3. Enter Bergamot as party leader.
  4. Watch the companions react to seal and stopMotion.
  5. Repeat steps 2-4 for Dunas, Aufheben, Oberon and Nibelung, each from its own gate map.
  6. On Dunas and Nibelung, check that bots stop or switch targets during immunity.
- **Effort:** M.
- **Kill switch:** one value per boss (G15).

## P15: Papulatus

- **Damage: mirror** on the Clock 8500001, Papulatus 8500002 and the bombs 8500003 and 8500004.
  - The Clock walks and Papulatus flies. Server authority would replace their native movement.
  - attack3 uses positions the client picks (8500001 attack3 randomPos). The mirror can only approximate it, for example with one random spot per bot in its area.
  - Bomb blasts use the G1 blast step.
  - P15 adds 8500000-8500004 to the boss id list. This is a party event, so it needs G2 case 4.
- **Registration:**
  - Stock `registerParty` registers party members standing on 220080000 (EventInstanceManager.java:406-415).
  - Add a portal hook in Populatus00.js before startInstance, for the run record and the kill switch.
  - The hook opens the `AgentBossRunSession` for the companions that `registerParty` admits. It reuses P12's `forEvent` index (G7).
- **Prerequisites:**
  - A party is required, and only the leader may enter (Populatus00.js:29-34).
  - The owner drops the piece 4031179. Companions need nothing.
- **Quota:**
  - 2 a day (ExpeditionBossLog.java:55).
  - G8 changes both helpers for companions only. A companion that is not on the leader's map is not checked and not logged. Humans keep the stock check and log (decision 7).
  - A capped companion steps off the gate map with a player-like line before the owner enters (G8).
- **Death:**
  - The gate closes at the start, like Zakum's door (see "Death modes by boss").
  - Revive is in Ludibrium 220000000. The companion walks to the gate and waits there (decision 9). There is no re-entry.
  - Papulatus uses the expedition rule, so the owner's death does not end the run. G7 rule A takes the companions out.
  - G7 rule A stops companions from finishing the idle boss when no human is left.
- **Post-clear:** NPC 2041025 warps only the talker (2041025.js:33). Rule B handles the pull-out.
- **No summons:** 220080001 has no SUMMON bit (fieldLimit 8440). Bots may summon here, as players may.
- **Restock:** 220080000 gets the trip flag (G9). The shop at 220050300 is 6 hops each way. One desk trip (decision 10). Standard 300/300 with 2500 projectiles.
- **Special roles:**
  - The owner drops the piece while standing on the y=-538 platform, at x -148 to -14 (Map2/220080001.img.xml:205-207, Reactor.java:400-402).
  - Ranged and magic bots take the flying phase. Unconfirmed: whether melee bots reach it.
  - P15 sets P8's never-target-fakes flag for the Papulatus run, so fake Low Darkstars are never targets (G3).
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung.
  - Balance run with crew B on rung 3 (Lv120, its own 4th jobs).
  - Two-owner check on the same rung: Bossfriend and ohboy plus 4 companions. The party cap is 6.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `papulatus`, and `COMPANION_BOSS_OWNERS_MAX` to 2 for the two-owner check. The P15 portal hook applies the owner cap per event (G7, config.yaml:953).
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!resetboss <name> PAPULATUS` for each character. It works only for characters who are online (ResetBossCommand.java:22-26).
  2. Run `!item 4031179` as Bossfriend.
  3. From Bossfriend, run `!warp 220080000`.
  4. For the two-owner check, run `!warphere ohboy` from Bossfriend. His companions follow him.
  5. Start the memory logger as a watch.
  6. Form the party.
  7. Enter, then drop the piece from the platform.
  8. Watch deadly attack1 set bots to 1 HP and 1 MP, and watch them drink.
  9. Check the blast log when bombs self-destruct near bots.
  10. Run `!hpmp Sorrel 0`. Check that he walks to the gate and waits.
  11. Leave through 2041025. Check the pull-out.
  12. Check that the boss log has an entry for each companion that entered, and none for one that stayed out.
- **Effort:** L.
- **Risks:**
  - Up to about 80 bombs, the map cap (MobSkill.java:320), with PAD 700-1000.
  - Unconfirmed: High Darkstars may pull bots off the floor.
  - BOSS points fire for the Time Sphere and for every bomb today (LivingWorldHooks.java:46-50). Decision 23 makes it once per run.

## P16: High-level field bosses

- **Groups:**
  - Pianus L and R with Seruf.
  - Leafre: Leviathan, Manon and Griffey.
  - Temple of Time: Dodo, Lilynouch and Lyka.
  - Zipangu: Black Crow, Blue Mushmom, Bamboo Warrior, Male Boss and Female Boss.
  - Masteria: Headless Horseman and Bigfoot.
- **Damage:** the G1 field scope. Distributed attacks use the region fallback. Pianus attack2 is type 3 (8510000.img.xml:800-807, :972), and Lyka attack1 is type 4.
- **Extra rules:**
  - Drink at once after a deadly hit. Pianus, Manon and Griffey have deadly attacks.
  - Rebuff after a dispel.
  - Lilynouch reflect uses G4. Lyka seduce and Dodo banish use G5.
  - Bloody Boom 8510100 uses the G1 blast step. It is boss-flagged and self-destructs (MapleMap.java:1471-1477).
- **Boss id list:** field bosses are in no expedition, so P16 adds each one by id:
  - Pianus L 8510000, Pianus R 8520000, Bloody Boom 8510100, and Seruf 4220001 and 4220000.
  - Leviathan 8220003, Manon 8180000 and Griffey 8180001.
  - Dodo 8220004, Lilynouch 8220005 and Lyka 8220006.
  - Black Crow 9400014, Blue Mushmom 9400205, Bamboo Warrior 6090002, Male Boss 9400120 and 9400122, and Female Boss 9400121. P19 needs Male Boss 9400122.
  - Headless Horseman 9400549 and Bigfoot 9400575.
- **Prerequisites:** Temple of Time lanes are quest-gated (timeQuest.js:26-55). Companions follow their owner in (decision 21). Pianus is entered by the script portal boss00 (Pianus.js:21-25).
- **Death:** G14 rest, then follow. Walk-back cannot be routed into 230040420, 240020401, 240020101 or 682000001, because they have no route-graph entry edges.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung.
  - Balance run with crew B on rung 3 (Lv120) for Pianus, Leafre, Zipangu and Masteria.
  - Balance run with crew B on rung 4 (Lv140) for the Temple of Time and Female Boss.
- **Client memory gate:** it applies on any P16 map where citizens or a second crew fight beside the boss.
- **Memory gate stage (field boss with many bots):** run it on a Zipangu or Leafre boss map where citizens or a second crew fight. Leviathan's Canyon 240040401 is one, because citizens farm it (fields.yaml:641). Leviathan 8220003 is a script boss there (AreaBossLeviathan.js:47).
  1. From Bossfriend, run `!warp 240040401`.
  2. Start the memory logger.
  3. If Leviathan is not on the map, run `!spawn 8220003` (GM3, CommandsExecutor.java:517).
  4. Hit the boss with Bossfriend while citizens and crew A fight beside it.
  5. Apply the stop rule of the client memory gate.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `field_pianus`, `field_leafre`, `field_temple`, `field_zipangu` and `field_masteria`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!warp 230040420`.
  2. Engage with the owner first.
  3. Watch bots take deadly attack1 and drink.
  4. Check the blast log when Bloody Boom self-destructs near a bot.
  5. Run `!debuff SEDUCE <companion>` with the G5 form. Check that it stops attacking and walks as a seduced player does.
  6. Run `!warp 270010500` for Dodo.
  7. Hit Dodo once with Bossfriend.
  8. Wait for a banish, or run `!debuff BANISH <companion>`. Check that it plans again from the spawn point.
- **Effort:** L.
- **Risks:**
  - Death loops at owner levels under 100.
  - Mirrored multi-region hits stack, because no hit cooldown follows a server hit (AgentCombatDamageRuntime.java:160-190). P8's shared 1.5 s hit window covers mirrored hits (p8_plan.md step 7).

## P17: Scarlion and Targa

- **Damage: mirror** on both 3-form chains, 9420542-9420544 and 9420547-9420549. Furious Scarlion moves.
- **G2:** P17 adds `SCARGA` to the expedition type list. It adds the adds 9420545, 9420550, 9420531 and 9420532 to the boss id list. None has the boss flag, so they need G2 case 4. The six boss forms are boss-flagged.
- **Immunity:** every form has 140 and 141 (Mob.wz info/skill), so G4 from P12 is required.
- **Registration:**
  - The boss-run service on Aldol 9270047, with min 6 (ExpeditionType.java:34).
  - Living code enforces Lv100 for companions, because `addMember` checks no level (Expedition.java:207-229).
- **Prerequisites:** the owner needs 4032246 to create (9270047.js:99-103). One completion of quest 4576 gives 2 (Act.img.xml:30093-30106).
- **Quota:** 1 a day (ExpeditionBossLog.java:54). P8's quota hook covers it.
- **Death and re-entry:**
  - Mode: re-enter by NPC (G6). There is no door (see "Death modes by boss").
  - The bot revives in Kampung 551000000, walks 6 hops back and runs Aldol headless (9270047.js:82-93).
  - Stock never clears canJoin (ScargaBattle.js:96).
  - The owner, as leader, cannot re-enter, so G7 rule A applies.
  - A re-entry must not log a second boss entry. P8 logs one entry per member and run (p8_plan.md step 11).
- **Post-clear:** exit NPC 9201134 gives the reward and warps the talker to 551030100 (9201134.js:58-63). The run sets P8's `exitNpcAfterClear`, so members talk to it and leave.
- **No summons:** the battle map 551030200 has the SUMMON bit (fieldLimit 1020), so G11 applies. The recruit map 551030100 has no bit (fieldLimit 0).
- **Restock:** the approach store at Trend Zone Metropolis 550000000 (potion-supplier-catalog.json:34), 11 hops from the gate. Kampung has none. Standard 600/500 with 5000 projectiles. That is 18 stacks with bullets.
- **Special roles:**
  - The owner drops the two keys on reactors 5511000 and 5511001 (5511000.js:5-9, 5511001.js:5-9).
  - The plan summons one boss at a time.
  - The adds revive into real attackers, 9420545 to 9420532 and 9420550 to 9420531.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung, plus the 3 bench allies on their Lv160 rung.
  - Balance run with crew B on rung 3 (Lv120).
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `scarga`, and `COMPANION_BOSS_ALLY_TYPES` to include `scarga` for the ally stage.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Check that each bench ally is still Lv160 from P8. If one is lower, raise it.
  2. Run `!resetboss <name> SCARGA` for each character.
  3. Run `!item 4032246 2` as Bossfriend.
  4. Run `!givemeso <name> 2000000` for each companion. The 600/500 standard costs about 1.54M.
  5. Run `!warp 550000000`. Check that short companions buy there.
  6. Run `!warp 551030100`.
  7. Start the client memory gate, stage 1: owner plus 5.
  8. Create at Aldol and register the party.
  9. Start, then summon Targa.
  10. Watch immunity stop the bots or make them switch targets.
  11. Run `!hpmp Ingrid 0`. Check that she walks back and registers again.
  12. Read the "PQ potions ... pq=scarga" lines. They decide Questions 5 and 6 for Horntail and Pink Bean.
  13. Repeat with the bench allies as stage 2 of the memory gate.
- **Effort:** L.
- **Risks:** Unconfirmed: whether 600M HP can be cleared in 60 min (ScargaBattle.js:37).

## P18: Horntail

- **Depends on:** P17 for the generic expedition flow. G4 and G6 come from P12.
- **Damage: mirror** on each part, 8810000-8810009, and on the adds 8810019-8810023.
  - noFlip boxes read as facing left (BossMirrorGeometry.java:57-64).
  - Each attack keeps its own PAD.
  - Regions past 15 cannot come from the 16-bit mask (G1). Unconfirmed: whether Horntail's areaCount-31 attacks then sample evenly. Calibration answers it.
- **G2:** P18 adds `HORNTAIL` to the expedition type list, and any add without the boss flag to the boss id list.
- **Never-target list:** 8810010-8810018.
  - Bots hit these display parts through the origin fallback (AgentCombatHitboxIntersection.java:36).
  - Unconfirmed live: killing any of them makes the fight unclearable (MapleMap.java:4553-4561).
- **Registration:**
  - Add the P7 options to Mark of the Squad 2083004 (ExpeditionType.java:37, Lv100).
  - Companions join through a headless runNpc with no selection (PrimitiveCapabilityGateway.java:263).
  - The scenario must cover three maps. `AgentExpeditionSpec` has one battle map (:13).
- **Prerequisites:**
  - The owner needs 4001086 for Keroben and the Slate (2081005.js:28-38, 2083000.js:48-62).
  - Companions get the waiver (decision 11): follow-warp before start, and a server warp to 240050400 for re-entry.
- **Quota:** 1 a day (ExpeditionBossLog.java:52). P8's quota hook covers it.
- **Death and re-entry:**
  - Mode: re-enter by NPC (G6). There is no door (see "Death modes by boss").
  - The bot revives in Leafre, restocks at the Leafre approach store, takes the waiver warp and registers again at 2083004 (2083004.js:80-91).
  - A re-entered bot lands on 240060000 and walks through hontale_BR to 240060200 (HorntailBattle.js:29, :123-126). The seal portals open at defeatedHead 1 or more and 2 or more (hontale_BR.js:24, :33).
  - The owner-leader cannot return (2083004.js:64-67), so G7 rule A applies.
  - A dead owner stays the fallback controller until revive (Monster.java:2131-2145). The mirror treats a dead controller as no human (AgentBossScope.java:48-55).
- **No summons:** 240060200 has the SUMMON bit (Map2/240060200.img.xml:2374). The pre-head maps 240060000 and 240060100 do not (fieldLimit 335992).
- **Loot:** the drop-owner rule matters most here, because forwarded part damage credits the attacker.
- **Special roles:**
  - The owner hits reactor 2401000 six times (2401000.js:29-34).
  - Melee bots fight the legs and tail from the floor. Ranged and magic bots take the heads.
  - Bots switch away from immune heads. The hands, wings, legs and tail never go immune.
  - A Bishop rebuffs after a dispel.
  - Overflow parties need their own healer, because heals are party-only (AgentCombatSupportPolicy.java:208-216).
- **Restock:** the approach store at Leafre. No route edge leaves 240050400. Starting standard 600/600 with 4800 projectiles, which fills the 18-stack budget. The scaled standard is 850/850 with 7000, which needs 27 stacks (Question 5). 7000 also needs the 5000-projectile bound raised for boss keys only (Question 6). MP equals HP because of the deadly attacks and mpBurn 3000.
- **Test party:**
  - Crew A on its Lv160 rung, plus the 3 bench allies on their Lv160 rung.
  - Crew B on rung 5 (Lv160) as the second owner. It reaches rung 5 only after rung 4.
  - Measure damage first with `!horntail`. It spawns the parts without pre-heads (GM3, CommandsExecutor.java:548, HorntailCommand.java:44).
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `horntail`, `COMPANION_BOSS_ALLY_TYPES` to include `horntail` and `COMPANION_BOSS_OWNERS_MAX` to 2. The ally key is for stage 2. The owner cap is for stage 3 (config.yaml:953, CompanionBossRuns.java:533-537).
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!item 4001086` as Bossfriend.
  2. Run `!warp 240000000`. Check that short companions buy there.
  3. From Bossfriend, run `!warp 240050400`.
  4. Start the client memory gate, stage 1: owner plus 5.
  5. Create, register and start.
  6. Kill both pre-heads, then hit the reactor.
  7. Check that bots never hit 8810010-8810018.
  8. Run `!hpmp Flint 0`. Check that Flint reaches 240060200.
  9. Never use `!killall`. It completes the fight (KillAllCommand.java:51).
  10. Repeat with the bench allies as stage 2, then with ohboy as stage 3. For stage 3, run `!warphere ohboy` from Bossfriend on 240050400.
- **Effort:** XL.
- **Risks:**
  - Unconfirmed: whether the crew clears every part within the 120-minute limit (HorntailBattle.js:37).
  - The region decode past region 15 may be biased.
  - Mob mist does no server damage, which is an immersion gap (MapleMap.java:2269-2312).
  - Potions may run out before the end, because the standard is capped by the Use tab.

## P19: The Boss (Showa expedition)

- **Damage: mirror** on 9400112, 9400113 and 9400300. The fight has a deadly doFirst attack and mpBurn 50000.
- **G2:** P19 adds `SHOWA` to the expedition type list. P16 has already put Male Boss 9400122 on the boss id list.
- **Registration:** the boss-run service on Konpei 9120201 at 801040004, with min 3 (ExpeditionType.java:35). Living code enforces Lv100.
- **Prerequisites:**
  - The owner needs the comb 4000138 (9120201.js:36, :98-99).
  - The comb drops from Female Boss on the field map 801040003. P16 lets companions help there.
- **A field boss on the recruit map:**
  - 801040004 spawns Male Boss 9400122 at (511,147), with mobTime 7200 (Map8/801040004.img.xml:180-185). It is boss=1 and Lv95.
  - The map also has 14 x 8140500. With the 10 s override (fact 9), Male Boss returns 2-4 s after each kill.
  - G7 rule C keeps registered companions on this map. G14 applies there, so companions hold and do not engage on their own.
  - This is why P19 depends on P16.
- **Target priority:** the battle map respawns Male Boss every 15 s (ShowaBattle.js:113). Bots target the 9400112 → 9400113 → 9400300 chain first. The battle-map Male Boss and the extras come lower, by the G3 priority list.
- **Quota:** none.
- **Death:**
  - Mode: wait outside in Showa Town (decision 16). Stock re-entry is broken (see "Death modes by boss").
  - Any death cancels the slot-machine bonus (ShowaBattle.js:141-143, :194-205), and bot deaths count (decision 14).
- **Post-clear:** Konpei 9120202 moves the talker to 801040101, which is still inside the run (9120202.js:66). Rule B handles the pull-out.
- **No summons:** 801040004 (fieldLimit 228) and 801040100 (fieldLimit 252) have the SUMMON bit, so G11 applies. 801040004 is the one recruit map where G11's P12 cases matter.
- **Restock:** the approach store at Showa Town, shop 801000300. Standard 600/600 with 4000 projectiles, because of the mpBurn. The store NPC 9120002 stands on 801000300, not on 801000000, so this row needs G9's store field.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung, plus the 3 bench allies, because the forms total 1.05B HP.
  - Balance run with crew B on rung 3 (Lv120, gate Lv100).
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `showa` and `field_zipangu`, and `COMPANION_BOSS_ALLY_TYPES` to include `showa` for the ally stage. The field key keeps the G14 rule on Male Boss 9400122.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!item 4000138` as Bossfriend.
  2. Run `!warp 801000000`. Check that short companions buy at shop 801000300.
  3. Run `!warp 801040004`.
  4. Start the client memory gate on the recruit map. It already holds 15 mobs.
  5. Create and register.
  6. Check that registered companions do not attack Male Boss 9400122 on their own.
  7. Start.
  8. Drop the comb on reactor 8001000.
  9. Watch the deadly hits and the mpBurn on mages.
  10. Check that bots hit the boss chain before the battle-map Male Boss.
  11. Repeat with the bench allies as stage 2 of the memory gate.
- **Effort:** L.
- **Risks:** Unconfirmed: whether 1.05B HP can be cleared in 60 min (ShowaBattle.js:37).

## P20: Pink Bean

- **Depends on:** P18 for the multi-part scale. The revive-inside mode is G6, from P12.
- **Damage: mirror** on 8820001-8820007. Pink Bean moves. Type-3 attacks use the region fallback. Pink Bean's attack1 is 9 of 9 regions, and the mirror hits all of them (fact 17).
- **G2:** P20 adds `PINKBEAN` to the expedition type list.
- **Never-target list:**
  - The 1-HP statue 8820000. No server guard protects it (MapleMap.java:1441-1450).
  - The statues 8820019-8820023. A statue kill breaks startWave (PinkBeanBattle.js:232-244).
- **Registration:**
  - The boss-run service on 2141001 (ExpeditionType.java:43).
  - Living code mirrors the Lv120 check, because only the talker is checked (2141001.js:61).
  - `EXPEDITION_BURST_MAX` 12 caps the allies (config.yaml:814).
- **Prerequisites:** companions follow-warp past the dragon ride and the Temple of Time gates (2082003.js:7-8, timeQuest.js:29-44), under plan decision 11a and decision 21.
- **Quota:** 1 a day (ExpeditionBossLog.java:53). P8's quota hook covers it.
- **Death and re-entry:**
  - Mode: revive inside (G6). It is the only way back, because the NPC re-entry is broken for everyone (see "Death modes by boss").
  - Humans stay in the run when they revive (PinkBeanBattle.js:165-167). Bots do not today, because a dead bot's respawn uses the channel map (fact 5).
  - The gate copies the human path. The bot then walks to the Pinkin portal at x -1156 (Pinkin.js:26-29).
  - Deaths count toward the 5-death wipe (PinkBeanBattle.js:149-163), and every death costs EXP (decision 13).
- **Loot guard:** no bot may pick up 4001193. The reactor fires only if the item is still on the ground (MapleMap.java:2369-2384). This uses P8's marker guard with the catalog list (G10).
- **No summons:** 270050000 and 270050100 have the SUMMON bit (fieldLimit 270972 and 2433660). G11 applies inside the run. 270050000 holds no monster, so it needs no more.
- **Restock:** the approach store at Leafre, before the ride. No shop is reachable from 270050000. Starting standard 600/600 with 4800 projectiles, which fills the 18-stack budget. The scaled standard is 1200/1200 with 10000, which needs 37 stacks (Question 5). 10000 also needs the 5000-projectile bound raised for boss keys only (Question 6). MP equals HP because of the deadly attacks.
- **Fix for every player (decision 15):** the reactor script kills statue 8820000 on the wrong map (2708000.js:41, PinkBeanBattle.js:264-265). P20 fixes it.
- **Special roles:**
  - Waves of Solomon, Rex, Hugin, Munin and Ariel.
  - Ranged bots keep out of Solomon's left deadly box and Rex's right one (8820003 and 8820004 attack1).
  - Munin's banish uses the G5 banish rule.
- **Test party:**
  - Crew A on its Lv160 rung, plus the 3 bench allies.
  - Crew B on rung 5 (Lv160) for the two-owner check.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `pinkbean`, `COMPANION_BOSS_ALLY_TYPES` to include `pinkbean` and `COMPANION_BOSS_OWNERS_MAX` to 2. The ally key is for stage 2. The owner cap is for stage 3 (config.yaml:953, CompanionBossRuns.java:533-537).
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!resetboss <name> PINKBEAN` for each character.
  2. Run `!warp 240000000`. Check that short companions buy there.
  3. From Bossfriend, run `!warp 270050000`.
  4. Start the client memory gate, stage 1: owner plus 5.
  5. Create, register and start.
  6. Watch each wave.
  7. Run `!hpmp Isolde 0`. Check that she revives in the 270050200 instance and walks back through Pinkin.
  8. Check that the stage-5 item stays on the ground.
  9. Repeat with the bench allies as stage 2, then with ohboy as stage 3. For stage 3, run `!warphere ohboy` from Bossfriend on 270050000.
- **Effort:** XL.
- **Risks:**
  - Unconfirmed: whether 7.65B HP can be cleared in 140 min (PinkBeanBattle.js:37).
  - Every death costs EXP. The map has no NO_EXP_DECREASE bit (270050100.img.xml:14).
  - Potions may run out before the end, because the standard is capped by the Use tab.
  - This is the longest fight, so the client memory gate matters most here.

## Not planned

- **HorntailPQ.** It has no boss and only gives access. Any departure ends its exact-6 run (HorntailPQ.js:169-205). The P18 waiver does the same job.
- **Chaos Horntail and Chaos Zakum.** Only enum constants exist (ExpeditionType.java:38-39). There is no mob, map or script data. docs/leftover.txt:51, :56 lists them as post-v83.
- **King Pepe and the Yetis (decision 24).**
  - The party cap is 3 and the boss is Lv35.
  - Every member must be in the same quest mode (KingPepeAndYetis.js:14-52).
  - A party start from 106021402 always fails (KingPepeAndYetis.js:25, 1300013.js:18-19).
- **Prime Minister.** It is a Lv38 story boss. The stock rematch registers nobody (1300013.js:59-61).
- **Crimson Balrog on the boat.** The fight window is about 31-37 s, and it happens on 42% of rides. The instant trip skips it (Boats.js:63-81, 1032008.js:9-11).
- **Demon's Doorway bosses.** These are single-character quest maps behind NPC gates (9201128.js:17-38). P13 keeps companions outside with a follow-guard hold.
- **Solo quest instances.** Save Delli, Elemental Thanatos, Maha, Nine Spirit's Nest, Rock Spirit, Puppeteer, Guardian Nex, Unknown Jr. Balrog and the pirate 4th job tests.
- **CWKPQ and Boss Rush PQ.** They belong on the PQ desk track, not the boss track.
  - CWKPQ needs job-specific stage rooms and ends below 6 players (CWKPQ.js:252, :307).
  - Boss Rush needs 27 boss copies (9500337-9500363).
- **Balrog sealed form 8830003.** It never spawns today. The plan keeps it that way.
- **Resurrection by companion Bishops (decision 25).**
- **Fixing the stock Showa re-entry lookup (decision 16).** The Pink Bean lookup has the same defect, but revive inside makes it harmless there.

## What P7 and P8 settled

These items were open findings in version 1. Each one now has an owner.

- **No summons on Easy Balrog and Zakum maps.** P7 step 6b covers INSIDE members. Adobis's room needs no rule, because it holds no monster (p8_plan.md F11).
- **Post-clear stranding.** P7 walks members out. P8 adds the exit NPC after the clear for Amon.
- **Boss clears written as PQ clears.** Still true for P7 and P8 today. G12 in P12 fixes it for every boss (decision 2).
- **Drop ownership.** P7 Part B built the ally drop owner. P12 does not build it again.
- **The 9-of-9 under-hit.** The mirror hits every region. Server authority waits for the P7 live check report, which has not come in yet.
- **No route out of Mu Young's room.** Fix L7's approach store at Ant Tunnel Park solves it for Easy Balrog. Normal Balrog shares it.
- **The quota filter scope.** P8 builds it for companions only (decision 7).
- **The boss standards key.** `BOSS_STANDARDS` and `AMMO_STANDARDS` exist. P8 raises the caps to the standard for boss runs only.
- **The Zakum leader lockout and death.** P8 keeps a dead companion in El Nath and does not wait for a dead owner-leader (p8_plan.md section 5).
- **The mirror decode, the Zakum arm's r=-1000 and noFlip boxes.** P8 steps 5 and 6.
- **The Zakum physics exclusion.** Built as the G2 guard (P8 step 4).
- **The gear tool.** Built first (P8 step 1). P11 now uses crew B, so the gear tool no longer sets P8's order.
- **Still true:** `!setlevel ... auto` is refused for companions (SetLevelCommand.java:170-172). The Easy body never touches a bot on the floor (fact 10). `!debuff` skips Agents until G5 (fact 19).

## Every phase

- **Guard tests stay green:** AgentKpqIsolationTest, ClaimGuardBoundaryTest, RosterStateWriterBoundaryTest, BenchLoadsOnExecutorTest, AgentLivingImportBoundaryTest, AgentConfigurationBoundaryTest, AgentArchitectureBoundaryTest and AgentSchedulerBlockingBoundaryTest (p8_plan.md section 7). AgentConfigurationBoundaryTest enforces the no-numeric-constants rule.
- **P7 boss tests stay green:** server/agents/capabilities/expedition/**, CompanionBossRuns*Test, CompanionBossAllies*Test, MuYoungHookScriptTest, EasyBalrogEncounter*Test, AgentLootPolicy*Test and CompanionRestock*Test (p8_amendments.md item 6).
- **Full suite:** run the plain suite with no filter: `JAVA_TOOL_OPTIONS=-Xmx6g ./mvnw -o "-Dmaven.repo.local=<the shared .m2 repository>" test` (p8_amendments.md item 3). The baseline at 2432bdb54 is about 11,872 tests, 0 failures and 37 skipped. Any failure is new.
- **Knowledge file:** a phase that edits an NPC script makes `KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed` fail. Never regenerate server-knowledge.json in a phase branch. The integrator runs tools\knowledge-export.bat.
- **House rules:** server.agents never imports server.living. KPQ, HPQ, LPQ and EPQ behaviour does not change. Edits to shared files are small and append-only.
- **Database:** if a phase needs a table, it uses changeset 47 and db/tables/038-*.sql. P7 and P8 left both free.
- **Client memory gate:** every big phase runs it before its key goes on for friends.
- **Hand-over:** each hand-over states the one-line death-chain change, if the phase touches it, and any balance run that crew B dropped.

## Details

- I edited no worktree file. I read the P8 worktree at C:/Users/ganre/AppData/Local/Temp/claude/C--Users-ganre-Desktop-MapleServer/57350396-c11f-43a7-ab35-10105dd00020/scratchpad/wt-fix, branch feat/companion-bossing-p8. Another workflow commits there.
- P8 commits on that branch: step 1 c04831a96, step 2 d3b4f2f3a, step 3 e0479798d and 1d307ee13, step 4 e452f77e0, step 5 9f44389c3, step 6 d3b5c4c26, step 7 4dd36710e. The tip was 4dd36710e at my last read, with a clean working tree.
- The Zakum leader lines (2030013.js:119-121) were read at 9f44389c3 and still hold at 4dd36710e.
- Line numbers I re-checked for this version are at 9f44389c3. Other line numbers are at f5b8c0ab5, as in version 1. Locate code by content where they moved.
- Re-checked for this version:
  - the death and door facts of every boss. These are the event scripts' afterSetup, setup, playerRevive and eventTime lines, and the NPC instance-name lookups.
  - the entry portal scripts, the return maps, and the plain portals into the Scarga, Showa and Pink Bean recruit maps.
  - `isExpeditionTeamLackingNow` and `isEventTeamLackingNow` (EventInstanceManager.java:1267-1285), and the party events' minimum sizes.
  - `AgentBossScope` (:35-42, :81-99), its callers (ServerMobAutonomyService.java:268, AbstractDealDamageHandler.java:247) and its yaml keys (agent-engine.yaml:5748-5756).
  - `BossMirrorGeometry` (:29-64) and the catalog fixes (ServerMobActionCatalog.java:61, :140-164).
  - `CompanionBossKeys` (:13-23), `BossEntrance` (:26-44), config.yaml:940-955, `AgentBossRunSession` (:368-427) and `AgentBossRunRegistry` (:63-109).
  - GM command levels: `!warp` :483, `!warphere` :511, `!horntail` :548, `!papulatus` :550, `!hpmp` :562, `!givemeso` :570, `!setgmlevel` :666 and `!bossgear` :704 (CommandsExecutor.java), and the Agent target rule (CommandTargetPolicy.java:14, :24-40).
  - Mu Young's offers (1061014.js:40, :48), level gate (:91) and re-entry (:139-143).
  - Use slots (002-character.sql:55), stack sizes in Item.wz Consume, shop prices (102-shopitems-data.sql:644-646, :1115-1116) and the readiness slot check (AgentSupplyReadinessRuntime.java:258-263).
  - SUMMON bits and monster counts on the recruit and gate maps 551030100, 240070202, 801040004, 541010060, 270050000, 240050400, 220080000 and 105100100.
- A changed fact from version 1: Horntail's time limit is 120 minutes, not 60 (HorntailBattle.js:37).
- Re-checked in the review pass of 2026-10-08, with the worktree at d3b5c4c26 (P8 step 6):
  - the run session's hand-back and end rules (AgentBossRunSession.java:345-353, :375-402, AgentBossRunMember.java:246-249), and the run-definition flags (AgentBossRunDefinition.java:26-27).
  - the five Neo City event scripts (eventTime at :39, gate maps at :30, afterSetup at :130) and their battle maps' return map.
  - `isExpeditionTeamLackingNow` in PapulatusBattle.js (:158, :172, :181) and `isEventTeamLackingNow` in LatanicaBattle.js.
  - `approachStoreNpcId` (BossEntrance.java:101-115), the supplier rows (potion-supplier-catalog.json:26, :36) and `ownerFits` (CompanionBossRuns.java:533-537).
  - the Papulatus quota helpers (AbstractPlayerInteraction.java:1116-1144) and the Part B switches (config.yaml:952-953).
  - deadlyAttack in Mob.wz 8830000, 8810000, 8820001, 8820003 and 8220013, and mpBurn on 8810005 and 8800003.
  - the five client processes in maple_mem.csv.
- Re-checked in the second review pass of 2026-10-08, with the worktree at 4dd36710e (P8 step 7):
  - the ammo bound and top-up (agent-engine.yaml:728, :3187, :4529-4541, AgentEngineConfigValidator.java:107-113, :165-170, AgentShopService.java:586-589).
  - `CompanionPqKeys` (:6-12), `LivingWorldConfig` (:534), `CompanionRunRecorder.load` (:41-42) and `AgentBossRunCatalog.all()` (:39).
  - the PQ-clear hook call (EventInstanceManager.java:866, LivingWorldHooks.java:284).
  - Mu Young's five hooks (1061014.js:38-57) and member branch (:122-131).
  - Horntail's entry map (HorntailBattle.js:29, :123-126), pre-head count (:222-224), re-entry (2083004.js:83-84) and seal portals (hontale_BR.js:24, :33).
  - the owner-left rule (p8_plan.md:344-347, step 13) and P8 questions 12 and 13 (p8_plan.md:633-634).
  - the restock boss gate (config.yaml:904, CompanionRestock.java:123-127).
  - a breadth-first search over both route graphs: 105100000 reaches 105070001 in 9 hops. Each Neo City gate map reaches 240000002 in 7 hops, and none is reached back from it.
  - Leviathan on 240040401 (AreaBossLeviathan.js:47, fields.yaml:641) and `!spawn` (CommandsExecutor.java:517).
- Client data I read but did not change: scratchpad/maple_mem_baseline.csv, scratchpad/maple_mem.csv, scratchpad/kaentake_plan.md and the P7A/P7B docs in wt-fix/docs.

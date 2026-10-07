# Companion plan for the other bosses (P11 to P20)

## Result

This plan adds companions to 5 more expeditions, 7 party-instance bosses and about 35 field bosses, in ten phases from P11 to P20.

- **P11 comes first.** It is Normal Balrog, built right after P7. Its live check runs before the P8 Zakum check, because a test crew's level may only rise.
- **P12 is shared groundwork, built once after P8.** Every later phase depends on it.
- **The P8 mirror is the default way bots take boss damage.** Normal Balrog is the one exception. It uses forced server authority, the same as P7.
- **Every new boss rule stays out of KPQ, HPQ, LPQ and EPQ, and new guard tests prove it.** The general mirror (G1), the control-steal block (G2) and the citizen rule also act on field bosses for citizens and solo bots (decision 19).
- **Each boss or field group gets its own kill-switch value.** It stays off until its live check passes. Invincible bots never ship.
- **There are 25 decisions for you below.** Decision 1 blocks the test party for P7 Part B, so it comes first.
- **I kept the phase names P11 to P20.** No phase moved to a new number.

## Words used in this plan

- **Mirror:** the P8 `BossAttackMirror`. A human client controls a boss part and reports an attack. The server then applies that attack to bots inside its WZ range, through `ServerMobDamageService.applyOrdinaryAttack`.
- **Server authority:** the server runs the boss's moves and attacks through a `BossActorBehavior` (SERVER_STICKY). P7 forces it on Easy Balrog while companions are registered.
- **Native control:** a human client runs the boss. Bots then take only touch damage and the skills the controller casts.
- **Control steal:** a bot hit moves an unprotected boss to Agent physics. The boss then stops attacking anyone.
- **G1 to G15:** the 15 shared groundwork items. Each one is defined in the section "Shared groundwork". P12 builds most of them.
- **Boss id list:** one new agent-engine.yaml key. It lists the boss ids that the mirror (G1) and the control-steal block (G2) cover by id.
- **Boss scope:** an Agent is in boss scope in three cases. KPQ, HPQ, LPQ and EPQ runs are never in boss scope.
  1. It is a member of a companion boss run, an `AgentBossRunSession` (G7). This includes the party-instance runs of Latanica, Neo City and Papulatus. Their P14/P15 portal hook opens the session (G7).
  2. It is a companion that fights a listed boss on a map with no event instance.
  3. It is a companion on a boss recruit or gate map while its owner is on the same map (G11).
- **Boss-run service:** the P7 Part A classes that run companions through a boss expedition. They are `AgentBossRunService` and `AgentBossRunSession` in server.agents, and `CompanionBossRuns` in server.living (p7a_plan.md:77-203). The plan doc calls this `CompanionExpeditionService` (docs/BOT_MANAGEMENT_PLAN.md:305).
- **Boss-run seam:** P7's `AgentBossRunHost.Rule`, which living installs into server.agents with `AgentBossRunHost.install(Rule)` (p7a_plan.md:77-89). G7 extends it. Run membership comes from P7's `AgentBossRunRegistry.forMember` (p7a_plan.md:96).
- **Boss rules catalog:** a new file, src/main/resources/agents/catalogs/boss-rules.json. It holds per-boss id lists: never-target ids, target priority, marker items and reflect skills. P11 creates it with the Normal Balrog entry. P12 extends it.
- **Death gate:** the boss clause `AgentBossDeathDepartureGate.respawnOrHold` in the Agent death chain (G6).
- **Desk trip:** the restock walk to the nearest shop and back, with a spoken ETA, as P7 plans it.
- **Short:** below the boss potion standard.
- **Approach-town top-up:** some boss gates are too far from a shop. Before such a boss, a short companion buys up to the boss standard in a town on the way.
- **Pull-out:** companions leave a boss run together with their owner.
- **Never-target list:** boss parts that bots must never hit, such as fakes, dummies, statues and display parts.
- **Crew A:** Bossfriend (GM6) and his five party companions, Isolde, Thane, Sorrel, Ingrid and Flint.
- **Bench allies:** Bossfriend's bench companions Orrin, Magna and Gunnar.
- **Crew B:** ohboy (gm 0) and his five companions, Brannock, Garrick, Fenna, Ilse and SilentSin.
- **Rung:** one level step of a test crew. A crew's ladder is its rungs in order.
- **Mechanics run:** a live check with crew A at a generous level. It proves that the rules work.
- **Balance run:** a live check with crew B near a friend's real level. It tunes difficulty, potions and deaths.
- **Part A and Part B:** the two P7 hand-offs. Part A is the owner's own party companions. Part B is bench allies and two owners.
- **Effort sizes** come from the plan doc (docs/BOT_MANAGEMENT_PLAN.md:544). S is 1-3 days, M is 1-2 weeks, L is 2-4 weeks, XL is more than 4 weeks.
- **Decision N:** item N of "Decisions for you" in this plan.
- **User decision N:** item N of your final decisions of 2026-10-06. For example, user decision 9 says that companions leave an Easy Balrog run when every human has died or left.
- **P7's Sleepywood decision:** the open decision 1 of the P7 Part A plan (p7a_plan.md:6). It asks whether companions top up potions in Sleepywood on the way to Mu Young. If you do not answer it, P7 ships only the "no shop out here" line.
- **Plan decision 11a:** option (a) of decision 11 in docs/BOT_MANAGEMENT_PLAN.md:1006-1008. Companions skip boss quest prerequisites and follow-warp in. It is not decision 11 of this plan.
- **Scarga:** Scarlion and Targa.

## Decisions for you

1. How should ohboy's companions be levelled for test parties? ohboy is gm 0, and a GM can set only its own companions while they are out with it (CompanionLevelTool.java:27-31, :350). There are four options.
   - (a) A new GM6 tool may set another owner's companions. It needs new code and a new permission rule.
   - (b) Bossfriend runs `!setgmlevel ohboy 6` on test days and `!setgmlevel ohboy 0` after. `!setgmlevel` is GM6 and sets the level live (CommandsExecutor.java:664, SetGmLevelCommand.java:44-47). ohboy must be online on Bossfriend's channel.
   - (c) A second GM6 owner replaces ohboy.
   - (d) Real play.
   - (b) needs no new code. The G13 gear tool works for ohboy the same way. Set ohboy back to gm 0 before each live check, so the check still runs with a non-GM owner.
   - (a) keeps ohboy a non-GM owner all the time, but it costs a new GM6 tool that can change another player's companions.
   - I recommend (b). If you do not answer, I keep (b).
2. Should boss clears stop counting as party-quest clears? Today every boss clear runs the PQ-clear hook (EventInstanceManager.java:865-871, LivingWorldHooks.java:284-304). It writes PQ records and quest-ledger rows under names such as 'Balrogundefined' and 'Papulatus0'. It also gives friendship PQ points. The hook runs for every player in the instance, so this also changes these records for human players. This affects P7 too. I recommend yes: boss events skip that hook and write boss records (G12) instead. If you do not answer, I keep yes.
3. Should the Normal Balrog crew be Lv100 3rd job with 60%-scrolled gear? Reflect hits for 5000 per physical hit and 8000 per magic hit. I recommend yes. If you do not answer, I keep yes.
4. Should the Java Normal Balrog encounter run only when companions are registered? The other option is every Normal run, which also changes runs with only humans. I recommend companions only. Runs with only humans then keep the stock BalrogBattle.js. If you do not answer, I keep companions only.
5. Should each boss get a mechanics run with crew A and then a balance run on crew B's ladder? Bosses under Lv60 get no balance run, because no test crew stays under Lv70. Normal Balrog's Lv100 crew A run is also its balance run. I recommend yes. If you do not answer, I keep yes.
6. Do companions spend their own daily boss entry, the same as players? This applies to Papulatus 2 a day, and to Scarga, Horntail and Pink Bean 1 a day. I recommend yes, logged only when they enter. If you do not answer, I keep yes.
7. Should the quota helper (G8) apply to companions only, or to every player? The helper checks and logs only the members who enter. Stock code checks every party member and logs every online member, so a change for everyone changes human quotas. This also sets the scope of P8's planned quota filter (BOT_MANAGEMENT_PLAN.md:306). I recommend companions only. If you do not answer, I keep companions only.
8. Which expedition comes first after Zakum: Scarlion and Targa (P17) or Horntail (P18)? Scarga is size L, not XL. It proves the generic expedition flow at a lower cost. I recommend Scarlion and Targa. If you do not answer, I keep Scarlion and Targa first.
9. Where should a dead Papulatus companion wait: at the gate 220080000, or in Ludibrium where it revives? I recommend the gate. If you do not answer, I keep the gate.
10. At the Papulatus gate, may a short companion make one desk trip with a spoken ETA? The shop at 220050300 is 6 hops out and 6 hops back. I recommend yes. If you do not answer, I keep yes.
11. Horntail access: may companions skip Keroben and the Slate, as plan decision 11a does for Zakum? I recommend yes. If you do not answer, I keep yes.
12. Should `USE_ANTI_IMMUNITY_CRASH` stay off, with bots taught to stop or retarget instead? The flag also changes the game for human players. I recommend off. If you do not answer, I keep it off.
13. Pink Bean: do companion deaths count toward the 5-death wipe and cost EXP, the same as player deaths in stock? I recommend yes. If you do not answer, I keep yes.
14. The Boss (Showa): do companion deaths cancel the slot-machine bonus, the same as player deaths in stock (ShowaBattle.js:141-143)? I recommend yes. If you do not answer, I keep yes.
15. Should I fix the stock Pink Bean reactor bug (2708000.js:41) for every player? It kills the statue on the wrong map and can soft-lock any run. I recommend yes. If you do not answer, I keep yes and fix it in P20.
16. Should I fix the stock Showa re-entry lookup (9120201.js:83), so that dead members can come back? I recommend no for now. If you do not answer, I keep no, and dead companions wait outside.
17. Should companions follow the G14 field rule? It means they join a field boss only after the owner's party hits it, and stop after one kill. I recommend yes. If you do not answer, I keep yes.
18. After a field-boss death, should a companion rest in town until full and then rejoin by the normal follow? The other option is to walk back. I recommend rest, then follow. If you do not answer, I keep rest, then follow.
19. Should citizens take mirrored field-boss hits but never attack a listed boss? The other option is a mirror that skips citizens, so that they never get hit. Citizens farm several field-boss maps today (scripts/config/population/fields.yaml:34-35, :87, :160, :206, :247). I recommend hits and no attacks, because that looks like real low-level players. If you do not answer, I keep hits and no attacks.
20. Should boss spawn points keep their own WZ respawn time instead of the server-wide 10 s override (config.yaml:544)? This changes the game for every player. The G14 stop rule already ends companion farming. I recommend no for now. If you do not answer, I keep no.
21. May companions follow their owner into Temple of Time lanes whose quest gates they never passed? I recommend yes, the same pattern as plan decision 11a. If you do not answer, I keep yes.
22. Should these be the starting potion standards (HP/MP potion counts)? Normal Balrog and Papulatus 60/60. Latanica and Neo City 40/50. Scarga, Horntail, Showa and Pink Bean 100/100. If Horntail or Pink Bean is on and the companion meets its level gate, the Leafre top-up buys 100/100. That also covers Neo City (G9). I recommend yes, tuned later from run evidence. If you do not answer, I keep these values.
23. Should a companion boss run give friendship BOSS points once per run? Today every boss-flagged kill counts, dummies and bombs included. Kills outside companion boss runs keep today's rule, so PQ bosses such as King Slime do not change. I recommend once per run. If you do not answer, I keep once per run.
24. Should King Pepe and the Yeti Brothers stay out of the plan? I recommend yes. If you do not answer, I keep yes.
25. Should companion Bishops cast Resurrection on dead party members in boss runs, with a short stay-down? Bots never cast it today (AgentCombatSkillClassifier.java:169-191). It revives only the caster's own party (StatEffect.java:1202-1214), and its cooldown is 600 s. I recommend no for now. If you do not answer, I keep no, and dead companions revive outside, as in P7.

## Test crews and the level order rule

**Level order rule.** A test crew's level only rises. Lowering a level drops skills, and `!setlevel ... restore` does not give them back (SetLevelCommand.java:55-57, :278-279). So each crew runs its live checks in rising level order. A lower balance run uses a different crew.

**How companions are levelled:**

- `auto` is refused for companions: "'auto' is for players" (SetLevelCommand.java:170-172). A companion spends its own AP and SP.
- A GM sets only its own companions, and only while they are out with it (CompanionLevelTool.java:27-31, :317-318).
- A companion on a bench slice is refused (CompanionLevelTool.java:324-325). Call a bench ally out first.
- A call with a full party returns PartyFull (RosterService.java:879). The party cap is 6, so Bossfriend benches three party companions before he calls the three allies.
- A called companion walks over from its camp (RosterService.java:886-890). The level tool refuses it until it arrives, because it is not ACTIVE yet (CompanionLevelTool.java:317-318).
  - A recall whistle skips the walk only if `RECALL_WHISTLE_ITEM_ID` is set. It is 0 today, so there is no whistle (config.yaml:798, RosterService.java:1124-1128).
- With "Keep my crew fresh" on, a set that leaves a companion 10 or more levels off its owner is refused (CompanionLevelTool.java:33, LivingWorldConfig.java:234). Set the owner first.
- ohboy is gm 0. A GM6 character sets ohboy himself. His companions follow the path from decision 1.

**How ohboy gets to a test map:** ohboy is gm 0, so he runs no GM command. In every crew B step, Bossfriend moves him:

1. From Bossfriend, run `!warp <map>` (GM2, CommandsExecutor.java:481).
2. Then run `!warphere ohboy` (GM3, CommandsExecutor.java:509). ohboy's companions follow him.

**Setup procedure for one rung:**

1. Log in as Bossfriend (GM6).
2. Run `!setlevel me <level> <job> auto`.
3. For each of the five party companions, run `!setlevel <name> <level> <job>`. Do not add `auto`.
4. If the phase asks for 60% gear, give it to them with the G13 gear tool.
5. If the bench allies stay at their level, go to step 12.
6. Bench three of the party companions.
7. Call the three bench allies out. Wait until each one arrives.
8. For each ally, run `!setlevel <name> <level> <job>`. Do not add `auto`.
9. If the phase asks for 60% gear for the allies, give it with the G13 gear tool.
10. Bench the three allies.
11. Call the three party companions back. Wait until each one arrives.
12. For ohboy, run `!setlevel ohboy <level> <job> auto` from a GM6 character.
13. For ohboy's companions, use the path from decision 1. With the default (b), do steps 14 to 16.
14. From Bossfriend, run `!setgmlevel ohboy 6`.
15. As ohboy, run `!setlevel <name> <level> <job>` for each of his five companions. Do not add `auto`.
16. From Bossfriend, run `!setgmlevel ohboy 0` before the live check.

**Crews:**

| Crew | Members | Rungs in order |
|---|---|---|
| Crew A | Bossfriend, Isolde, Thane, Sorrel, Ingrid, Flint | Lv70 3rd job (P7, user decision 3) → Lv100 3rd job (P11) → Lv160 4th job (P8, user decision 4, then P12 to P20) |
| Bench allies | Orrin, Magna, Gunnar | Lv70 (P7 Part B) → Lv100 (P11) → Lv160 (P8, then P17 to P20) |
| Crew B | ohboy, Brannock, Garrick, Fenna, Ilse, SilentSin | the table below |

- The P11 live check must run before the P8 Zakum check. P8 then keeps crew A at Lv160 for good.
- P8 also raises the bench allies to Lv160. Its live check uses "owner + 5 + at least 3 allies" (BOT_MANAGEMENT_PLAN.md:907).
- If P8 is ready first, P11's live check moves to crew B, on an extra Lv100 rung that replaces rungs 2-4. Crew B's levelling path is decision 1.
  - In the fallback, P11 drops the bench-ally check, because P7 Part B covers the same option.
  - The fallback drops every rung 1-4 balance run whose phase is not ready. This includes rung 1's P13 balance run, because P13 needs P12, which comes after P8. Each dropped run is noted in the hand-over.
- User decision 2 names three bench allies. ROSTER_MAX leaves room for two more, but no persona is named, so this plan uses three.

**Crew B ladder:**

| Rung | Level | Live checks on this rung |
|---|---|---|
| 1 | Lv70 3rd job | P7 Part B (user decisions 5 and 6). P13 balance: Mushmom, Dyle, Zombie Mushmom and Zeno (Lv60-65). |
| 2 | Lv80 | P13 balance: Nine-Tailed Fox, Tae Roon and King Sage Cat (Lv70-77). P14 balance: Capt. Latanica. |
| 3 | Lv85 | P14 balance: the five Neo City bosses (gate Lv70). |
| 4 | Lv95 | P13 balance: Jr. Balrog, Eliza, Snack Bar, Kimera and Snowman (Lv80-90). |
| 5 | Lv120 4th job | P15 balance and the two-owner check. P16 balance: Pianus, Leafre, Masteria and Zipangu without Female Boss. P17 balance. P19 balance (gate Lv100). |
| 6 | Lv140 | P16 balance: Temple of Time and Female Boss. |
| 7 | Lv160 | P18 and P20 two-owner checks. |

- Every crew B rung, P7 Part B included, uses the levelling path from decision 1.
- Crew B never goes back down. If a phase is not ready when crew B passes its rung, that balance run is dropped and noted in the hand-over.
- P18 comes before P19 in the phase order, but its two-owner check needs rung 7. The P18 and P20 two-owner checks wait until the rung 5 and 6 balance runs are done or dropped. Those are P15, P16, P17 and P19 on rung 5, and P16 Temple of Time and Female Boss on rung 6.
- Bosses under Lv60 get only the mechanics run (decision 5): Mano, Stumpy, Deo, Faust, Giant Centipede, King Clang and the Timers.
- Boss levels come from Mob.wz info/level, for example Mushmom 6130101 Lv60 and Jr. Balrog 8130100 Lv80.

## Phase order

| Phase | Plain name | Depends on | Effort | Why in this position |
|---|---|---|---|---|
| P11 | Normal Balrog with companions | P7 exit, and the G13 gear tool from P8 | M | It shares Mu Young, the arena and the phase graph with Easy Balrog. Its Lv100 live check must come before the Lv160 Zakum check. |
| P12 | Shared boss groundwork | P8 exit | L | Every later boss needs the general mirror, the control-steal block and the run rules. Building them once avoids copies. |
| P13 | Low-level field bosses (Victoria, Ossyria, Ludus Lake, Mu Lung, Nihal) | P12 | M | Friends meet these bosses while hunting. They need no registration. Control steal affects them today. |
| P14 | First party bosses: Capt. Latanica and the five Neo City bosses | P12 | L | It is the cheapest party-instance path. Stock registration already admits companions, and there is no quota. It is the first phase that needs G4. |
| P15 | Papulatus | P14 | L | It reuses the P14 party-instance run. It adds bombs, a flying phase and a 2-a-day quota. |
| P16 | High-level field bosses (Pianus, Leafre, Temple of Time, Zipangu, Masteria) | P13 | L | It reuses the P13 field rules. It adds deadly attacks, area attacks, reflect and banish. |
| P17 | Scarlion and Targa | P12 | L | It is the first expedition NPC after Mu Young and Adobis. It proves the generic expedition flow. |
| P18 | Horntail | P17 for the generic expedition flow, and P8 for measured Lv160 damage | XL | It is the biggest fight. It needs the multi-part mirror and measured Lv160 damage. G4 and G6 come from P12. |
| P19 | The Boss (Showa expedition) | P17 and P16 | L | It reuses the Scarga flow. P16 puts Female Boss and Male Boss 9400122 on the field lists, and the Showa recruit map needs that. |
| P20 | Pink Bean | P18 | XL | It has the largest HP pool. It needs P18's multi-part scale. The revive-inside mode is G6, from P12. |

- P13 and P14 can be built in parallel. P16 and P17 can be built in parallel.
- Crew B's ladder still sets the order of their balance runs.

## Facts that shape this plan

1. **Bots never take ordinary attacks from a boss under native control.** The only caller of `applyOrdinaryAttack` is the server-authority path (ServerMobAutonomyService.java:551). Bots take only three kinds of damage:
   - touch damage (AgentCombatDamageRuntime.java:82-103).
   - skills that the controller casts, inside the skill box (MoveLifeHandler.java:97-113, MobSkill.java:397-425).
   - reflect damage (AbstractDealDamageHandler.java:573-591).
2. **Unconfirmed live: a bot hit can steal an unprotected boss from the human.** This follows from code. The bot hit sends `stopControllingMonster` (Monster.java:1120-1149). Every new bot hit renews a 7000 ms lease (agent-engine.yaml:33). While bots keep hitting, the boss never attacks again.
3. **Only the Easy Balrog parts 8830007-8830009 are protected today** (ServerMobAutonomyService.java:245-251, BossActorBehavior.java:40-41).
4. **With no human on the map, a boss has no controller and stands idle.** Bots can still kill it on the server (Monster.java:2117-2120, MapleMap.java:1436-1484).
5. **A dead bot always leaves the instance.** Its respawn uses the channel map, not the instance copy (MapleMap.java:323-328, CosmicMapGateway.java:74-78). The forced map change unregisters it (Character.java:1803).
6. **After a clear, companions are stranded.** clearPQ stops the timer (EventInstanceManager.java:345-352). Follow never warps a bot out of its instance (AgentFollowMapSyncService.java:85-86). The lobby reopens after 10 s (config.yaml:436).
7. **The leader of a running expedition can never re-enter.** This is true for Horntail (2083004.js:64-67), Zakum (2030013.js:66-69) and Scarga (9270047.js:66-69). An owner who leads and dies is out for good.
8. **Crash never removes boss immunity on this server** (config.yaml:365, AgentCrashSkillPolicy.java:41-57). Bots never retarget when the boss is immune.
9. **Field bosses that spawn from map data come back 2-4 s after a kill.** `MOB_RESPAWN_TIME_OVERRIDE_SECONDS: 10` (config.yaml:544) replaces every WZ mobTime of 0 or more. The code is SpawnTimeOverridePolicy.java:16-25, :49-57 and MapleMap.java:3412. After a kill, the wait is 10 s divided by 5 (SpawnPoint.java:111-114), on a 2 s respawn tick (config.yaml:186). Script bosses (the AreaBoss scripts) still return 0-3 h after a kill.
10. **Boss parts with no body box deal no touch damage, and the Balrog bodies never touch a bot on the floor.**
    - Touch needs a box from frame 0 of stand, move or fly (AgentMobHitboxProvider.java:104-121). Without one, the touch test is false (AgentMobTouchRuntime.java:17-20).
    - The Pink Bean statues 8820000, 8820019, 8820022 and 8820023 and the Balrog parts 8830004 and 8830006 have no stand box.
    - The Balrog bodies 8830000 and 8830007 do have one: (-138,-429)-(63,-261) (Mob.wz stand/0).
    - Touch uses only the lower half of the box (AgentMobTouchRuntime.java:21). With the body at y 258, that half spans about y -87 to -3.
    - The only foothold under x 274-475 in 105100300 is y 258. The bot sweep is 50 px (agent-engine.yaml:1318). So neither body ever touches a bot on the floor.
    - The claws 8830001 and 8830002 do touch. Their lower halves reach down to about y 228-238.
11. **Fake parts are normal targets for bots.** Target eligibility checks only alive and not friendly (AgentCombatTargetEligibilityPolicy.java:9-13). Nothing in the damage path checks for fakes (MapleMap.java:1436-1484).
12. **The server never checks FieldLimit.SUMMON** (FieldLimit.java:29). Bots can summon where players cannot. The bit is also set on PQ maps, so a bot no-summon rule must stay inside boss scope:
    - KPQ 103000800-805 (fieldLimit 270460, Map1/103000800.img.xml:14) and the KPQ bonus map 103000890 (8316).
    - LPQ 922010100-922011000 (270972, and 270974 on 922010600) and the LPQ bonus map 922011100 (8828).
    - HPQ 910010000 (270972, Map9/910010000.img.xml:3617).
    - No EPQ map has the bit.
13. **The expedition start logs a daily attempt for every online member.** It warps in only the members standing on the recruit map (Expedition.java:250-256, EventInstanceManager.java:422-430).
14. **The kill switch exists, but the boss-run service and the mirror do not yet.**
    - `COMPANION_BOSS_ENABLED_TYPES` exists, in config.yaml living_world (config.yaml:940, LivingWorldConfig.java:319). P7 Part A added it.
    - Validation accepts only `config.CompanionBossKeys.KNOWN` (CompanionBossKeys.java:18-23). Today that is `easy_balrog` plus a zakum slot.
    - A listed key means "the entry NPC offers My party will register too".
    - `CompanionExpeditionService` and `BossAttackMirror` do not exist yet. They appear only in docs/BOT_MANAGEMENT_PLAN.md:305-309. P7 Part A builds the boss-run service under other names (see "Words used in this plan").
15. **The drop owner is the highest damager** (Monster.java:908-909). Damage is credited per attacker, and that includes damage forwarded from Horntail parts (Monster.java:504-508). NO_BOSS only stops bots from picking items up.
16. **Every boss clear runs the PQ-clear hook** (EventInstanceManager.java:865-871, LivingWorldHooks.java:284-304). Both Balrog scripts name their instance 'Balrog'+lobby id, which is 'Balrogundefined' at run time (BalrogBattle.js:120, BalrogBattle_Easy.js:119).
17. **"9 of 9" area attacks hit only the base box.** `hasDistributedRegions` is false when attackCount equals areaCount (BossAction.java:32-33). This covers Easy body 8830007 attack1, Normal body 8830000 attack1 and Pink Bean 8820001 attack1. Unconfirmed: whether the client hits wider. P7 makes it a live check (p7a_amendments.md item 2).
18. **Self-destruct blasts reach only clients.** The server kills a bomb at its self-destruct HP (MapleMap.java:1471-1477). The blast then reaches only clients, as a TAKE_DAMAGE report (TakeDamageHandler.java:157). It is not a MOVE_LIFE attack, so a mirror never sees it.
19. **`!debuff` skips Agents.** It filters its targets with `CommandTargetPolicy.includeInHumanCommand`, which is false for every Agent (DebuffCommand.java:107, CommandTargetPolicy.java:42-44).
20. **A banish keeps the target on its map.** `changeMapBanish` warps to the character's current map (Character.java:1694-1702). Unconfirmed: how an Agent handles a warp within the same map.

## Bosses by mechanism, and the P7/P8 seams each one reuses

Seam names in the table:

- **Boss-run service:** `AgentBossRunService`, `AgentBossRunSession` and `CompanionBossRuns` (P7).
- **Entrance registry:** P7's `BossEntrance`, the entry NPC or portal per boss (p7a_plan.md:180-183). It is the boss version of PqEntrance (roster/PqEntrance.java:106-124).
- **Server behaviour:** forced server authority through a `BossActorBehavior` (P7).
- **Mirror:** the P8 mirror, generalized in P12.
- **Death gate:** the boss death clause (G6).
- **Restock:** the desk trip or the approach-town top-up (G9).
- **NO_BOSS:** companions leave boss drops to humans (P7).
- **Kill switch:** a `COMPANION_BOSS_ENABLED_TYPES` value.
- **Run records:** P7's `CompanionBossRun` plus `CompanionRunRow` rows (p7a_plan.md:184-203).

| Boss | Mechanism | Phase | Boss-run service | Entrance registry | Server behaviour | Mirror | Death gate mode | Restock | NO_BOSS | Kill switch | Run records |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Normal Balrog | expedition | P11 | yes | yes, Normal branch | yes | fallback | wait outside | the Sleepywood top-up, if P7's Sleepywood decision adds it, with that decision's hop budget (G9). Otherwise only the no-shop line. | yes | balrog_normal | yes |
| Scarlion and Targa | expedition | P17 | yes | yes, Aldol | no | yes | re-enter by NPC | top-up in Trend Zone Metropolis | yes | scarga | yes |
| Horntail | expedition | P18 | yes | yes, Mark of the Squad | no | yes | re-enter by NPC | top-up in Leafre | yes | horntail | yes |
| The Boss (Showa) | expedition | P19 | yes | yes, Konpei | no | yes | wait outside | top-up in Showa Town | yes | showa | yes |
| Pink Bean | expedition | P20 | yes | yes, 2141001 | no | yes | revive inside | top-up in Leafre | yes | pinkbean | yes |
| Capt. Latanica | party instance | P14 | session from the portal hook, stock party registration | yes, portal | fallback | yes | wait outside | top-up in Boat Quay Town | yes | latanica | yes |
| Neo City bosses | party instance | P14 | session from the portal hook, stock party registration | yes, portal | no | yes | wait outside | top-up in Leafre | yes | one per boss | yes |
| Papulatus | party instance | P15 | session from the portal hook, stock party registration | yes, portal | no | yes | wait at gate | desk trip | yes | papulatus | yes |
| Low-level field bosses | field | P13 | no | no | no | yes, field scope | none, G14 rule | standing restock | already outside instances | one per region group | no |
| High-level field bosses | field | P16 | no | no | no | yes, field scope | none, G14 rule | standing restock | already outside instances | one per region group | no |

Outside an instance, companions already leave boss drops to their owner (AgentLootPolicy.java:81-83, CompanionSettings.java:26).

**Why the mirror is the default, not server behaviour:**

- The mirror keeps boss movement native, and the human sees stock behaviour.
- Server authority replaces native movement. That is a poor fit for walking or flying bosses such as the Papulatus Clock, Papulatus, Pink Bean, Furious Scarlion and The Boss.
- Bosses that spawn during instance setup would be server-owned in every run once registered, even in runs with only humans. This applies to the Neo City bosses (TD_Battle1.js:121, ServerMobAutonomyService.java:602-626). The force flags are per-behaviour constants (BossActorBehavior.java:20-27).
- The server region mask covers 16 regions (ServerMobAutonomyService.java:470-478). Horntail attacks reach areaCount 31.
- Normal Balrog is the exception, because its parts are stationary. P7 checks the forced server path live first, and P11 follows P7's result.

## Shared groundwork (P12 unless noted)

Rules for every item:

- G3, G4, G5, G11 and the once-per-run BOSS points act only in boss scope.
- Per-boss id lists go in the boss rules catalog. The one exception is the G2 boss id list, which is an agent-engine.yaml key.
- Durations and switches go in agent-engine.yaml, each with Values, Purpose and Technical comment lines.
- No new `private static final` number goes into server.agents. AgentBalrogDefinition.java:9-21 shows the static-id pattern that these lists must not copy.

**G1. General mirror.** Generalize the P8 mirror (BOT_MANAGEMENT_PLAN.md:308) into one WZ-driven hook:

- It works for every boss that the G2 scope covers, through `ServerMobActionCatalog.forMob` (ServerMobActionCatalog.java:31-100).
- It has two scopes. One is the same event instance, as P8 has it. The other is a field map where the controller and the bot both have no event instance.
- It hits every Agent in range. Decision 19 keeps citizens in.
- It decodes attacks itself and accepts only raw activity 24-41. The stock range check passes every value at or above the minimum (MoveLifeHandler.java:192-194).
- It applies one hit per bot per mob and attack index during the attack animation. `canUseAttack` spends MP on every call and has no cooldown (Monster.java:1772-1776).
- It evaluates noFlip boxes without mirroring them (LifeFactory.java:275-281). Today `facingFor` tries both facings (GenericWzMobBehavior.java:68-77).
- Unconfirmed: the client sends a region mask in pOption (MoveLifeHandler.java:83). If it does, distributed attacks of type 3 and 4 use it. Otherwise the server samples attackCount of areaCount regions, as its own path does (ServerMobAutonomyService.java:452-478).
- **9-of-9 attacks:** when attackCount equals areaCount, the server hits only the base box today (BossAction.java:32-33). This covers Easy body 8830007 attack1, Normal body 8830000 attack1 and Pink Bean 8820001 attack1.
  - If the P7 live check shows hits outside x -400 to 400, the mirror and the Easy and Normal Balrog behaviours hit all areaCount regions.
  - P11 makes the Balrog change in the shared `EasyBalrogBehavior`. So it also changes Easy Balrog, for humans too, in runs with companions registered.
  - BossAction.hasDistributedRegions does not change. The PQ server-authority bosses share it (p7a_amendments.md item 2).
- It fixes circles with r of 0 or less, which give a null box today (BossActionGeometry.java:21).
- It skips airborne bots for jumpAttack, which the catalog does not load (ServerMobActionCatalog.java:39-70).
- It passes each attack's own PAD (ServerMobActionCatalog.java:66).
- It disengages when no human controls the part.
- **Self-destruct blasts:** at the self-destruct kill (MapleMap.java:1471-1477), it applies the bomb's own attack1 box and PAD to bots in range.
  - Papulatus bombs 8500003 and 8500004 have attack1 (-65,-100)-(65,0). Bloody Boom 8510100 has attack1 (-180,-106)-(180,0).
  - Unconfirmed: the client blast uses attack1. The Papulatus bombs have selfDestruction action 3.
  - It logs each blast, so that P15 can compare it with a human's hits.
  - If P15 cannot confirm the shape, the blast step stays off. "Bots take no bomb blasts" then stays a known gap.

**G2. Control-steal block.**

- Add one predicate to `ServerMobAutonomyService.blocksAgentPhysicsInstance` (:253-259). `MobPhysicsService.eligible` already reads it (:210).
- The predicate is (the mob is boss-flagged AND in an expedition event) OR (its id is on the boss id list).
- Both branches also need two things: the mob has no registered authority, and its controller is not a BotClient.
- The switch and the boss id list are new agent-engine.yaml keys beside the physics keys (agent-engine.yaml:17, :33).
- The scope keeps KPQ unchanged. KPQ is not an expedition, and King Slime 9300003 is not on the list. King Slime has no behaviour at all (ServerMobBehaviorRegistry.java:32-47).
- Scope keeps Alishar, Papa Pixie, Poison Golem and Lord Pirate unchanged. None is in an expedition, and none is on the boss id list.
- Field bosses are in no expedition, so only the list branch covers them. P13 and P16 put each field boss on the list by id. Unknown Snack Bar 8220008 also lacks the boss flag.
- P8 needs this for Zakum, so P8 builds it as this generic predicate. Later phases only add ids.
- P12 adds the Papulatus Clock 8500001 for its spot check. P15 adds 8500000 and 8500002-8500004.
- P14 adds the Latanica and Neo City ids, and P17 adds the Scarga adds (see those phases).
- **Revived-form guard.** Unconfirmed live: a revived form can pass to a bot controller. A revived form goes to the previous controller (Monster.java:839-840), and `aggroSwitchController` has no bot guard (Monster.java:2256-2297). P12 adds a BotClient guard for mobs that the predicate covers. EPQ's Poison Golem forms keep today's path.
- **Test:** the predicate never blocks 9300003, 9300012, 9300039, 9300180-9300182, 9300105-9300107 or 9300119.

**G3. Never-target list.** Generalize P8's `BossTargetRules` into per-boss lists in the boss rules catalog, read through the G7 seam.

- A mob with the fake flag set is never a target (Monster.java:1671). Low Darkstars spawn fake (MobSkill.java:374-375).
- The list:
  - the Balrog fake body 8830000 until it becomes real.
  - the dummy 8830006.
  - the helpers 8830004 and 8830005.
  - the Horntail display parts 8810010-8810018.
  - the Pink Bean statues 8820000 and 8820019-8820023.
  - fake Low Darkstars 8500004.
- Today only the Easy scenario guards the fake body (AgentEasyBalrogScenario.java:140-141). P11 creates the boss rules catalog file with the Normal Balrog entry and builds a Normal Balrog filter that reads it. P12 extends that file and generalizes the filter into G3.
- The same catalog holds a target priority list per boss. P19 uses one.

**G4. Immunity and reflect stop (boss scope only).**

- While a target holds an immunity that matches the bot's damage type, the bot switches to another target or waits.
- Generalize the Easy reflect stop (AgentEasyBalrogScenario.java:146-150) to skills 143, 144 and 145. The ids go in the boss rules catalog.
- P14 is the first phase that needs it (Dunas, Nibelung, Oberon).
- EPQ does not change. The EPQ Poisoned Stone Bug 9300173 on 930000200 has physImmune 140 (Mob.wz/9300173.img.xml:28). It runs under EpqPoisonedStoneBugBehavior and is never in boss scope.

**G5. Disabled states (boss scope only).**

- Agents read only SEAL and ZOMBIFY (AgentCombatPlanRuntime.java:29, :84). Nothing checks stun, seduce or confuse (Character.java:3219, :3234).
- New rules: a stunned bot stands still. A seduced bot walks toward the caster. A confused bot moves the wrong way.
- **Banish:** Dodo 8220004 and Munin 8820006 banish a target to the spawn point of the same map (Character.java:1694-1702). After such a warp, the bot drops its path and plans again from where it lands. Unconfirmed: how an Agent handles a same-map warp today.
- EPQ does not change. Poison Golem 9300182 has confuse 132 (Mob.wz/9300182.img.xml:52), and EPQ is never in boss scope.
- **Test tool:** the stock `!debuff` skips Agents (fact 19). G5 adds a GM6 form, `!debuff <state> <companion>`, which acts only on the named companion. BANISH is one of its states.

**G6. Death gate modes.**

- One class, `AgentBossDeathDepartureGate.respawnOrHold(agent, respawn, nowMs)`, has the same shape as the PQ gates.
- It goes innermost, directly around `respawnAtNearestTown` inside the EPQ lambda (AgentDeathTickCoordinator.java:35-38). It is a one-line change. I will say so in the hand-over.
- It holds only for its own runs. For every other Agent it runs the respawn at once.
- It reads run membership and the death mode from the G7 seam. It never imports server.living.
- It has three modes:
  - **Wait outside:** one line, then wait at the revive map or the gate.
  - **Re-enter by NPC:** the bot walks back and runs the recruit NPC headless. This works only for non-leaders.
  - **Revive inside:** this copies the human path for Pink Bean. It calls `eim.revivePlayer`, then respawns into `eim.getMapInstance(returnMapId)`.
- The gate runs only once the respawn is due (AgentDeathTickService.java:49-50).
- **If decision 25 is yes:**
  - P12 adds "companion Bishops cast Resurrection on dead party members" in boss scope. It revives only the caster's own party (StatEffect.java:1202-1214).
  - The gate holds while a party Bishop with Resurrection ready is on the map.
  - The "HP is back, clear the dead state" check lives inside the gate class. It runs only after the respawn is due, so a revive in the first 5 s shows only at 5 s.
  - Without that check the respawn timer undoes a revive, because dead means `deadUntilMs > 0` (AgentDeathState.java:19-21).

**G7. Boss run lifecycle and the boss-run seam.**

- Each run keeps P7's run key `boss:<key>:<sessionId>:<ownerId>` with Commitments (p7a_plan.md:28), like the PQ key (CompanionPartyQuestService.java:1158). Two owners in one run need the owner part.
- **Rule A:** when no human is left in the run, companions leave and rejoin outside. This is user decision 9 applied to every boss, after a clear too. Runs end only at 1 player or fewer, and bots count (EventInstanceManager.java:1267-1274).
- **Rule B:** when the owner leaves, before or after a clear, the owner's companions leave too.
- **Rule C:** registered companions stay on the recruit map until the start (fact 13).
- **Party-instance runs:** P7's registry is keyed by Expedition. `signedUpIn` reads `event.getExpedition()`, and the index is `forExpedition` (p7a_plan.md:93-96). Latanica, Neo City and Papulatus have no expedition.
  - So the P14/P15 portal hook opens one `AgentBossRunSession` for the run, keyed by the EventInstanceManager. Each companion that `registerParty` admits becomes a member.
  - A party event has no sign-up wait. An admitted companion becomes INSIDE once it stands in the event.
  - P14 adds a `forEvent(EventInstanceManager)` index to `AgentBossRunRegistry`. P15 reuses it.
  - Without this session, G3, G4, G5, G6, G7 rules A and B, G10 and G11 never act in P14 and P15. G4 is needed for Dunas, Nibelung and Oberon.
- **Seam:** G7 extends P7's `AgentBossRunHost.Rule` (p7a_plan.md:77-89) with the death mode, engagement and target exclusions. It adds no second install point. Living installs the rule with `AgentBossRunHost.install(Rule)`, as P7 does.
- For one Agent, the seam answers four questions:
  - Which boss run is it in, if any? P7's `AgentBossRunRegistry.forMember` answers this (p7a_plan.md:96).
  - Which death mode applies?
  - May it engage this mob now (G14)?
  - Which extra mobs does this run exclude as targets?
- The death gate, the G3 filter, G4, G5 and G11 read only this seam and the boss rules catalog.

**G8. Quota helper.**

- One helper checks and logs only the members who actually enter.
- Use it in `Expedition.start`, in Populatus00.js:38-47 and on late re-entry.
- Decision 7 sets its scope. With "companions only", the stock party helpers skip companions (AbstractPlayerInteraction.java:1115-1145). G8 then checks and logs each companion as it enters.
- Today one capped member blocks the whole party with a system-style message (Populatus00.js:38-42).
- With G8, a capped companion steps off the gate map before the owner enters. It says one player-like line, for example "im out of runs today, ill wait here".
- Off the map, registerParty does not register it (EventInstanceManager.java:406-415).
- In an expedition, a capped companion is never added, and it says the same kind of line.
- P8 already plans the recruit-map filter, for every player (BOT_MANAGEMENT_PLAN.md:306, p7a_plan.md:634). Generalize it in the scope that decision 7 sets.

**G9. Restock.**

- Today a boss gate allows no trips. `restocksFrom` is false on every COMPANION_RESTOCK_BOSS_MAPS map (CompanionRestock.java:98-101, AgentSupplyReadinessRuntime.java:112-115).
- `bossGate` measures the default PARTY_QUEST standard (CompanionRestock.java:169-189).
- G9 adds three things:
  1. A per-map "trips allowed" flag: a new key `COMPANION_RESTOCK_TRIP_MAPS` in config.yaml living_world, beside `COMPANION_RESTOCK_BOSS_MAPS` (config.yaml:904). Only a map on the trip list lets `restocksFrom` return true.
  2. A per-boss standard lookup, used by `bossGate` and by the shortfall check. Each boss key and its decision-22 value goes into P7's BOSS_STANDARDS key (agent-engine.yaml:4297 at the tip). That key is read after `PARTY_QUEST_STANDARDS` (p7a_plan.md:142, :379). `PARTY_QUEST_STANDARDS` accepts only `[a-z0-9]+` keys, so a key such as `balrog_normal` fails startup there (AgentEngineConfigValidator.java:140-141).
  3. A boss desk entry for each trip map: a new agent-engine.yaml key, `AgentSupplyReadiness.BOSS_DESK_MAPS`, read by `partyQuestDesk` (AgentSupplyReadinessRuntime.java:501-509). Then DESK_MAX_HOPS 7 applies there (agent-engine.yaml:4255). Today it applies only on AgentPartyQuestCatalog recruit maps (AgentSupplyReadinessRuntime.java:116-117). The entry does not go into AgentPartyQuestCatalog, because PQ code iterates that catalog.
- A boss desk trip counts hops both ways. Both must be at most DESK_MAX_HOPS, or a slow return would miss the entry.
- **Approach-town top-up:** where no trip fits, a short companion buys up to the boss standard in the approach town.
  - It starts when the owner enters that town. It runs only while that boss's kill-switch key is on and the companion meets the boss's level gate.
  - It is a standing restock, inside STANDING_MAX_HOPS 2 (agent-engine.yaml:4251).
  - Leafre serves three bosses. It uses the largest standard among the bosses that pass both checks. So when Horntail or Pink Bean passes them, it buys 100/100, which also covers Neo City.
  - **Balrog is the exception.** Sleepywood 105040300 has no shop, and Henesys is 6 hops from it (hops2.py). So the Balrog top-up uses the hop budget of P7's Sleepywood decision, not STANDING_MAX_HOPS.
  - If P7's Sleepywood decision is unanswered, Easy and Normal Balrog have only the no-shop line. Normal Balrog then never reaches its 60/60 standard.
- Every recruit or gate map in the table except 105100100 joins COMPANION_RESTOCK_BOSS_MAPS (config.yaml:904). P7 owns 105100100. A boss map has no trips (CompanionRestock.java:98-101), so listing 105100100 would switch off P7's Easy Balrog trip on the same map.
- Only 220080000 gets the trip flag.

| Boss | Recruit or gate map | Nearest supplier, hops out and back | Restock |
|---|---|---|---|
| Normal Balrog | 105100100 | none, because no route edge leaves 105100100. From 105100000, Henesys 100000102 is 24 hops away. | the Sleepywood top-up, if P7's Sleepywood decision adds it, with that decision's hop budget (G9). Otherwise only the no-shop line. |
| Papulatus | 220080000 | 220050300, 6 out and 6 back | desk trip (decision 10) |
| Capt. Latanica | 541010060 | 541000000, 6 out and 9 back | top-up in Boat Quay Town 541000000 |
| Neo City bosses | 240070202 | 240000002, 7 out and no path back | top-up in Leafre 240000000, before the Time Gate |
| Scarlion and Targa | 551030100 | 550000000, 11 out and 11 back | top-up in Trend Zone Metropolis 550000000 |
| Horntail | 240050400 | none, because no route edge leaves 240050400 | top-up in Leafre |
| The Boss | 801040004 | none, because no route edge leads from 801000000 to the hideout | top-up in Showa Town, shop 801000300 |
| Pink Bean | 270050000 | none, because the search reaches only 270xxxxxx maps | top-up in Leafre, before the ride |

- Hops come from a breadth-first search over world-portal-route-graph.json and victoria-portal-route-graph.json, with suppliers from potion-supplier-catalog.json.
- Kampung 551000000 has no supplier. npc-travel-legs.json has no Time Gate 2083006 leg.

**G10. Loot.**

- NO_BOSS applies inside every boss run. Today the policy allows everything inside an instance (AgentLootPolicy.java:69-76).
- Add a never-pick-up list for script marker items to the boss rules catalog, such as Pink Bean 4001193 (PinkBeanBattle.js:260-266). The server ignores the WZ pickUpBlock flag.
- **Ownership:** this rule acts in a companion boss run, when the highest damager is a companion. Its owner becomes the drop owner if the owner is on the map. Otherwise the top human damager does.
  - The change sits at Monster.java:908-909, through a LivingWorldHooks call. The damage credit itself does not change.
  - Bot-led overflow parties then never own Horntail, Pink Bean or Scarga drops.

**G11. No-summon rule (boss scope only, built in P7 step 6b, extended in P12).**

- The rule: a bot skips summon skills on a map whose fieldLimit has the SUMMON bit (FieldLimit.java:29). It applies only in these three cases:
  1. an INSIDE member of an `AgentBossRunSession`. P7 step 6b builds this case (p7a_amendments.md item 1).
  2. a REGISTERED member of an `AgentBossRunSession`. P12 adds it.
  3. a companion on a boss recruit or gate map while its owner is on the same map (boss scope case 3). P12 adds it. Living answers this through the G7 seam from the `BossEntrance` maps.
- Step 6b alone does not cover registered companions that wait on a recruit map, because they are not INSIDE.
- The extension matters most on Showa's recruit map 801040004. It holds Male Boss and 14 x 8140500, so registered companions there could summon where players cannot.
- Maps with the bit, inside a run:
  - Easy Balrog 105100400 (P7), and Normal Balrog 105100300 and 105100301.
  - Zakum's altar 280030000 (P8).
  - Horntail's main map 240060200 (Map2/240060200.img.xml:2374). The pre-head maps 240060000 and 240060100 do not have it.
  - Pink Bean 270050100.
  - Latanica's battle map 541010100.
  - Scarga's battle map 551030200.
  - Showa's battle map 801040100.
  - the Neo City battle maps.
- Recruit or gate maps with the bit, covered only by the P12 extension:
  - Zakum's recruit map 211042400 (P8).
  - Latanica's gate 541010060.
  - Showa's recruit map 801040004.
  - Pink Bean's recruit map 270050000 (fieldLimit 270972).
- If P8 ships before P12, P8 needs the extension for 211042400 (see "Findings that also affect P7 and P8").
- KPQ, HPQ and LPQ maps have the bit too (fact 12). They are never in boss scope, so bots there still summon. A guard test proves it.
- P11 needs only Normal Balrog to run as an AgentBossRunSession.

**G12. Records and friendship.**

- Add boss keys to `CompanionPqKeys` (:17-34) and to `CompanionRunRecorder.load` (:41-51).
- Decision 2 decides whether boss clears still run the PQ-clear hook.
- Decision 23 gives BOSS points once per companion boss run.
- Kills outside those runs keep today's per-kill rule, capped at 3 a day (Friendship.java:177-201). King Slime 9300003 (Mob.wz/9300003.img.xml:43), Alishar 9300012 (:21) and Poison Golem 9300182 (:20) are boss=1 and keep earning points as today.

**G13. Gear tool (built in P8).**

- P8 builds the 60%-scroll gear tool (`FriendForge.recipe` with `ScrollRecipe(60, REST)`, FriendForge.java:117-147).
- P8 should build it early. P11's Lv100 check needs it before P8's own Lv160 check.
- It follows the same owner rule as `!setlevel`. Decision 1 covers crew B.
- GM scrolling is not realistic, because `USE_PERFECT_GM_SCROLL` is true (config.yaml:349).

**G14. Field engagement and stop rule (built in P13).**

- Companions engage a field boss only after the owner's party has hit it. They stop after one kill (decision 17).
- Living installs this rule through the G7 seam, because it needs owner and party knowledge.
- Without it, the 2-4 s respawn makes companions farm a boss without end.
- Dead companions rest before they follow again (decision 18). Follow-warp sends them straight back at 50 HP (AgentFollowMapSyncService.java:33-87).
- Citizens follow decision 19. With the default, a citizen never engages a mob on the boss id list. The same seam carries this rule.
- The rule also applies on a recruit map that holds a field boss. Showa's 801040004 is one (P19).

**G15. Kill switch.**

- There is one `COMPANION_BOSS_ENABLED_TYPES` value per boss or field group. It ships empty, so everything is off.
- Each phase adds its keys to `CompanionBossKeys.knownKeys()` and widens the key's meaning to portal bosses and field groups.
- A startup check refuses an enabled key whose ids are not on the boss id list. Expedition keys such as `easy_balrog` need no ids there, because G2's expedition branch covers them.
- A field key gates three things for the bosses of its group: the G1 field mirror, the G14 rule and the citizen rule.
- G2 does not read the kill switch. It has its own agent-engine.yaml switch (G2).
- Keys can depend on each other. The startup check also refuses `showa` without `field_zipangu`, because G14 must hold companions off Male Boss 9400122 on 801040004 (P19).
- Keys per phase:
  - P11: `balrog_normal`.
  - P12: `papulatus`, for the Clock 8500001 only. The P12 spot check turns it off again at its end. P15 widens it to the whole fight.
  - P13: `field_victoria`, `field_ossyria`, `field_ludus`, `field_mulung` and `field_nihal`.
  - P14: `latanica`, and `neo_bergamot`, `neo_dunas`, `neo_aufheben`, `neo_oberon` and `neo_nibelung`.
  - P16: `field_pianus`, `field_leafre`, `field_temple`, `field_zipangu` and `field_masteria`.
  - P17 to P20: `scarga`, `horntail`, `showa` and `pinkbean`.
- A boss whose live check fails stays off.

## P11: Normal Balrog with companions

- **Damage: server behaviour, with the mirror as fallback.**
  - Make `EasyBalrogEncounterService` a variant with Normal ids (EasyBalrogEncounterService.java:24-30).
  - Decision 4 sets its scope. With "companions only", BalrogBattle.js asks the service at start whether companions are registered. If none are, it runs the stock code unchanged.
  - Add three `EasyBalrogBehavior` subclasses for 8830000, 8830001 and 8830002 (EasyBalrogBehavior.java:19-40).
  - Add the adds 6400005 and 6400006 to `BalrogSummonedAddBehavior.MOB_IDS` (:21).
  - The force flag is per encounter, because behaviours are singletons. P7 Part A step 3 built that seam: a `Predicate<MapleMap>` that `EasyBalrogEncounterService.installServerAuthorityRule` installs (server.life.autonomy.balrog). The encounter evaluates it once at start (EasyBalrogEncounterService.java:36-49, :74-85 at the tip). P11 reuses it for the Normal encounter.
  - The body's attack1 is 9 of 9 regions (fact 17). If the P7 live check shows hits outside x -400 to 400, the Easy and Normal Balrog behaviours and the mirror hit all areaCount regions. BossAction.hasDistributedRegions does not change.
  - P11 makes that change in the shared `EasyBalrogBehavior`, so it also changes Easy Balrog.
  - If P7 falls back to the mirror, Normal Balrog follows it.
- **Registration:**
  - Use the boss-run service (`AgentBossRunService` and `CompanionBossRuns`) with type BALROG_NORMAL(6,30,50,255,5) (ExpeditionType.java:33). Add a `balrog_normal` slot to `AgentBossRunCatalog` and `BossEntrance`.
  - Add the P7 options to the Normal branch of Mu Young (1061014.js:87).
  - P7 hooks must branch on the expedition type, not on the map. Easy and Normal share the NPC and 105100100.
  - The minimum is 6, which is the owner plus 5 (config.yaml:279).
- **Prerequisites:** Mu Young checks Lv50 only for the character who talks (1061014.js:38). `Expedition.addMember` checks no level (Expedition.java:207-229). Living code mirrors the Lv50 gate for companions, as P17 and P20 do for their gates.
- **Quota:** none (ExpeditionBossLog.java:51-55, :198-201).
- **Death and re-entry:**
  - Mode: wait outside at 105100100 (Map1/105100300.img.xml:8).
  - Stock re-entry is broken. The NPC looks up 'Balrog'+channel, but the instance is 'Balrogundefined' (1061014.js:60-68, BalrogBattle.js:120).
- **Post-clear:** on Normal, exit NPC 1061018 warps only the talker (1061018.js:35). On Easy it warps the team (:33). So P7's post-clear return does not cover Normal. P11 builds G7 rule B for this run.
- **No summons:** 105100300 and 105100301 have the SUMMON bit (fieldLimit 271100). P7 step 6b builds this rule. P11 needs only Normal Balrog to run as an AgentBossRunSession.
- **Restock:** the Sleepywood top-up, if P7's Sleepywood decision adds it. It uses that decision's hop budget, not STANDING_MAX_HOPS (G9). The standard is `balrog_normal=60/60` in BOSS_STANDARDS (decision 22).
  - If P7's Sleepywood decision is unanswered, Normal Balrog has only the no-shop line, and the 60/60 standard is never reached.
  - A room trip cannot work. No route edge leaves 105100100. From 105100000, the nearest shop, Henesys 100000102, is 24 hops away.
  - That breaks G9's rule (both ways at most DESK_MAX_HOPS 7) and the 5-minute registration clock.
  - P11 does not add 105100100 to COMPANION_RESTOCK_BOSS_MAPS, because P7 owns that map (G9).
- **Targets:** P11 creates src/main/resources/agents/catalogs/boss-rules.json with the Normal Balrog entry. Its filter reads the ids below from that file, not from static constants. P12 extends the file and moves the filter into G3.
  - Claw 8830002 first, then claw 8830001, then the body once it is real. The adds 6400005 and 6400006 are also targets.
  - Never target the fake body 8830000 before it is real, the dummy 8830006, or the helpers 8830004 and 8830005.
  - Bots stop hitting the body during reflect. Skill 145 level 3 reflects 5000 per physical hit and 8000 per magic hit for 7 s, every 60 s, from 95% HP (8830000.img.xml:30-32, MobSkill.img.xml:12620-12633).
- **Touch:** the body never touches a bot on the floor (fact 10). The claws do.
- **Sealed form:** keep no sealed form, which matches what players see today. Unconfirmed at runtime: the sealed-form spawn fails on scope (BalrogBattle.js:148, :157).
- **Test party:**
  - Crew A on its Lv100 rung: Bossfriend, Isolde (Priest), Thane (Crusader), Sorrel (Sniper), Ingrid (Dragon Knight) and Flint (Outlaw), all with 60% gear.
  - The bench allies on their Lv100 rung, for the "Call allies from my bench..." option. In the fallback on crew B, this check is dropped (see "Test crews").
  - No second owner. P7 Part B already checks two owners on the same NPC and service.
  - This live check runs before the P8 Zakum check (see "Test crews").
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `balrog_normal`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Log in as Bossfriend.
  2. Run `!setlevel me 100 <job> auto`.
  3. For each of the five party companions, run `!setlevel <name> 100 <job>`. Do not add `auto`.
  4. Give 60% gear to the five party companions with the G13 gear tool.
  5. Bench three of the party companions. The party cap is 6 (RosterService.java:879).
  6. Call the three bench allies out. Wait until each one arrives.
  7. For each ally, run `!setlevel <name> 100 <job>`. Do not add `auto`.
  8. Bench the three allies.
  9. Call the three party companions back. Wait until each one arrives.
  10. Run `!warp 105100100`.
  11. Talk to Mu Young. Pick Normal and create.
  12. Pick "My party will register too", then "Call allies from my bench...".
  13. Start. Check the log for server autonomy on 8830002.
  14. Watch companions lose HP to claw touches, claw attacks and the body's attack3.
  15. Check that bots stop hitting the body while reflect is up.
  16. Check that no companion casts a summon.
  17. Run `!hpmp Thane 0`. Check his one line and his wait at 105100100.
  18. After the clear, talk to 1061018. It moves only you to 105100301, which is still inside the instance (BalrogBattle.js:32-35).
  19. Check whether the companions follow.
  20. Leave through portal out99 (balog_end). Check that the companions leave too.
- **Unit check:** the Lv50 gate refuses a companion under Lv50. No live check covers it, because no test companion stays under Lv50.
- **Effort:** M.
- **Risks:**
  - Unconfirmed live: if the target filter fails, a bot kill of the fake body counts as a claw kill. Then makeMonsterReal runs on a dead body (BalrogBattle.js:241-268, :262-265).
  - Killing dummy 8830006 releases claw 8830001 early.
  - Reflect kills bots that ignore the stop.
  - A second run can start 10 s after a clear (EventManager.java:250-257).

## P12: Shared boss groundwork

- **Builds:** G1, G3 to G10 and G12, with the boss-run seam from G7.
  - The G10 ownership rule is the exception. If P7 Part B has already built it, P12 does not build it again.
  - G2 and G13 come from P8. G11 case 1 comes from P7 step 6b. P12 adds G11 cases 2 and 3.
  - P12 extends P11's boss rules catalog file, and generalizes P11's Normal Balrog filter (into G3) and P11's rule B (into G7).
  - G14 comes in P13.
- **Unit checks:**
  - mirror geometry against WZ ranges for the Pianus, Horntail and Pink Bean ids.
  - if the P7 live check confirms the wider range, all 9 regions for 8830007 attack1, 8830000 attack1 and 8820001 attack1.
  - the 24-41 decode.
  - dedup per action.
  - noFlip boxes.
  - the self-destruct blast step.
  - each death gate mode.
  - G11 cases 2 and 3: a REGISTERED member, and a companion beside its owner on 801040004, skip summons.
- **Regression checks for KPQ and EPQ:**
  - G2 never blocks 9300003, 9300012, 9300039, 9300180-9300182, 9300105-9300107 or 9300119.
  - The revived-form guard leaves EPQ's Poison Golem forms on today's path.
  - An Agent outside boss scope still attacks the EPQ stone bug 9300173 while it is immune, as today.
  - Confuse from 9300182 changes nothing outside boss scope.
  - Bots in KPQ 103000800-805, LPQ 922010100-922011000 and HPQ 910010000 still cast summons.
  - BOSS points for 9300003, 9300012 and 9300182 still count per kill.
  - The guard tests stay green (see "Every phase").
- **GM spot check** (crew A on its Lv160 rung):
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `papulatus`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!papulatus` on a test field. It spawns a free walking Clock (GM3, CommandsExecutor.java:548).
  2. Bring one companion and hit the Clock together.
  3. Run `!botcfg physicsstatus` (GM6). Check that it shows no acquisition.
  4. Watch the companion lose HP from mirrored Clock attacks.
  5. Run `!debuff STUN <companion>` with the G5 form. Check that the companion stands still.
  6. Run `!debuff BANISH <companion>`. Check that it plans again from the spawn point.
  7. Remove `papulatus` from `COMPANION_BOSS_ENABLED_TYPES` again. Otherwise P15 code is live on its deploy before its own check.
  8. Restart with `start-maple-stack -RestartServer`. This restart also disconnects everyone online.
- **Effort:** L.
- **Risks:**
  - Unconfirmed: the v83 pOption region format.
  - Blocking physics on mobile field bosses removes bot knockback, which changes how solo bots fight them.

## P13: Low-level field bosses

- **Bosses:**
  - Mano, Stumpy, Faust, Dyle, King Clang, Snack Bar, Mushmom, Zombie Mushmom and Jr. Balrog.
  - Eliza, Snowman, three Timers, Zeno, Nine-Tailed Fox, Tae Roon, King Sage Cat, Giant Centipede, Deo and Kimera.
- **Damage:** field-scope mirror (G1) and control-steal block (G2). Eliza's attack1 is a type-3 area attack, so it uses the G1 region fallback.
- **Boss id list:** field bosses are in no expedition, so P13 adds each one by id:
  - Victoria: Mano 2220000, Stumpy 3220000, Faust 5220002, Dyle 6220000, King Clang 5220001 and 5220000, Unknown Snack Bar 8220008 and Snack Bar 8220009, Mushmom 6130101, Zombie Mushmom 6300005 and Jr. Balrog 8130100.
  - Ossyria, Ludus Lake, Mu Lung and Nihal: Eliza 8220000, Snowman 8220001, Timer 5220003, Zeno 6220001, Nine-Tailed Fox 7220001, Tae Roon 7220000, King Sage Cat 7220002, Giant Centipede 5220004, Deo 3220001 and Kimera 8220002.
- **Registration:** none. Companions already follow their owner.
- **Prerequisites:** none. Snowman is reached by the hidden portal hiden00 on 211040100 (Map2/211040100.img.xml:3464-3468).
- **Quota:** none.
- **Engagement:** the G14 rule. The quest bosses that NPCs weaken (AbstractPlayerInteraction.java:1314-1324) need only this rule.
- **Citizens:** decision 19. Citizens farm the maps of Faust, Stumpy, Mano, Snack Bar and King Clang (fields.yaml:34-35, :87, :160, :206, :247). With the default, they take mirrored hits but never attack those bosses.
- **Death:** G14 rest, then follow. There is no death gate, because the bot is not in an instance.
- **Restock and loot:** standing restock and `leaveBossDrops` already work outside instances.
- **Door dungeons:** add a follow-guard hold on the 677000xxx maps (see "Not planned").
- **Test party:**
  - Mechanics run: crew A on its Lv160 rung, against Jr. Balrog (Lv80). At Lv160 the mirrored hits are small, so the check reads the mirror log line as well as the HP bar.
  - Balance runs: crew B rungs 1, 2 and 4 (see "Test crews"). Crew B's levelling path is decision 1.
- **Kill switch:** one key per region group (G15). Jr. Balrog and Mushmom are in `field_victoria`.
- **GM-first live check (mechanics run):**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `field_victoria`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!warp 105090900` for Jr. Balrog.
  2. Hit Jr. Balrog once with Bossfriend. Check that companions join only after that.
  3. Check the mirror log for Jr. Balrog's attacks on companions.
  4. Run `!botcfg physicsstatus`. Check that it shows no acquisition.
  5. Check that companions stop after one kill, because it returns in 2-4 s.
  6. Run `!hpmp Sorrel 0`. Check that he rests in Sleepywood 105040300 until full, then follows.
- **Balance-run check (crew B, rung 1):**
  - If `field_victoria` is not on yet, do steps 0a and 0b above first.
  1. From Bossfriend, run `!warp 100000005` for Mushmom.
  2. Then run `!warphere ohboy` (GM3, CommandsExecutor.java:509). His companions follow him.
  3. Hit Mushmom with ohboy first.
  4. Watch a companion lose HP from the jump attack.
  5. Note deaths and potion use for tuning.
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
- **Boss id list:** P14 adds 9420513 and the adds 9420511 and 9420512. Latanica is a party event, not an expedition, and the adds have no boss flag.
- **Registration:**
  - Stock party registration at portal captinsg00 on 541010060 (EventInstanceManager.java:406-415).
  - Add a living portal hook before startInstance. There is no portal hook today (LivingWorldHooks.java:530-541).
  - The hook opens the `AgentBossRunSession`, keyed by the EventInstanceManager, for the companions that `registerParty` admits (G7). It also writes the run record.
- **Prerequisites:** the owner holds White Essence 4000381 and leads the party (captinsg00.js:4, :13). Companions need nothing.
- **Quota:** none.
- **Death and re-entry:**
  - When the owner dies or leaves, the run ends for all. This is the stock leader rule (EventInstanceManager.java:1280-1282).
  - A dead companion waits at the gate 541010060. There is no re-entry.
- **Post-clear:** Bob 9270033 is the only exit, and he warps only the talker (9270033.js:48-65). G7 rule B pulls companions out.
- **No summons:** 541010060 and 541010100 have the SUMMON bit, so G11 applies.
- **Restock:** 541010060 joins the boss map list with no trips. The Boat Quay Town supplier is 6 hops out and 9 back (potion-supplier-catalog.json:33). The way back is over DESK_MAX_HOPS, so companions top up in Boat Quay Town (G9). Standard 40/50.
- **Special role:** the owner drops the essence on reactor 5411000 (5411000.js:22-27).
- **Test party:** mechanics run with crew A on its Lv160 rung. Balance run with crew B on rung 2 (Lv80). Crew B's levelling path is decision 1.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `latanica`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!item 4000381` as Bossfriend.
  2. Run `!warp 541000000`. If a companion is short, check that it tops up there.
  3. Run `!warp 541010060`.
  4. Enter as party leader.
  5. Drop the essence on the reactor.
  6. Watch bots lose HP to the magic boxes.
  7. Run `!hpmp Flint 0`. Check that he waits at the gate.
  8. Leave through Bob. Check the pull-out.
- **Effort:** M.
- **Risks:** Unconfirmed: whether 10 min is enough for 2M HP at Lv80.

### Neo City bosses (Bergamot, Dunas, Aufheben, Oberon, Nibelung)

- **Damage: mirror** on each form: 7220003-7220005, 8220010, 8220011, 8220012 and 8220013-8220015.
  - The mirror treats every form on its own. That avoids authority keying across revive chains.
- **Boss id list:** P14 adds 7220003-7220005 and 8220010-8220015. These are party events, not expeditions.
- **Registration:** stock party registration at portal right00 TD_Boss_enter. It needs 2-6 members at Lv70 or more (TD_Boss_enter.js:4-16, TD_Battle1.js:27-28).
  - Add a portal hook. It opens the `AgentBossRunSession` for the admitted companions, as for Latanica (G7), and writes the run record.
- **Prerequisites:**
  - The owner does the era quests in order (2083006.js:17-21, :86-92).
  - Companions follow-warp in, under plan decision 11a.
- **Quota:** none.
- **Death:**
  - The run ends when the leader leaves or fewer than 2 players remain (EventInstanceManager.java:1276-1285).
  - A dead companion waits at 240070000 and follows the owner after the run.
- **Immunity and reflect:**
  - Dunas and every Nibelung form have physImmune, so they need G4.
  - Oberon reflects weapon hits with PCounter 143, which also needs G4.
- **Restock:** the five recruit maps join the boss map list with no trips. The Leafre shop 240000002 is 7 hops out from 240070202, and no route leads back. So companions top up in Leafre before the Time Gate (G9). Standard 40/50.
- **Test party:** mechanics run with crew A on its Lv160 rung. Balance run with crew B on rung 3 (Lv85). Crew B's levelling path is decision 1.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `neo_bergamot`, `neo_dunas`, `neo_aufheben`, `neo_oberon` and `neo_nibelung`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!warp 240000000`. If a companion is short, check that it tops up there.
  2. Run `!warp 240070202`.
  3. Enter Bergamot as party leader.
  4. Watch the companions react to seal and stopMotion.
  5. Repeat for each boss.
  6. On Dunas and Nibelung, check that bots stop or switch targets during immunity.
- **Effort:** M.
- **Kill switch:** one value per boss (G15).

## P15: Papulatus

- **Damage: mirror** on the Clock 8500001, Papulatus 8500002 and the bombs 8500003 and 8500004.
  - The Clock walks and Papulatus flies. Server authority would replace their native movement.
  - attack3 uses positions the client picks (8500001 attack3 randomPos). The mirror can only approximate it, for example with one random spot per bot in its area.
  - Bomb blasts use the G1 blast step.
  - P12 already put 8500001 on the boss id list. P15 adds 8500000 and 8500002-8500004, because this is a party event, not an expedition.
- **Registration:**
  - Stock `registerParty` registers party members standing on 220080000 (EventInstanceManager.java:406-415).
  - Add a portal hook in Populatus00.js before startInstance, for the run record and the kill switch.
  - The hook opens the `AgentBossRunSession` for the companions that `registerParty` admits. It reuses P14's `forEvent` index (G7).
- **Prerequisites:**
  - A party is required, and only the leader may enter (Populatus00.js:29-34).
  - The owner drops the piece 4031179. Companions need nothing.
- **Quota:**
  - 2 a day (ExpeditionBossLog.java:55).
  - G8 checks and logs only the members who enter, in the scope of decision 7.
  - A capped companion steps off the gate map with a player-like line before the owner enters (G8).
- **Death and re-entry:**
  - Revive is in Ludibrium 220000000 (Map2/220080001.img.xml:7). The companion walks to the gate and waits (decision 9). There is no re-entry.
  - G7 rule A stops companions from finishing the idle boss when no human is left.
- **Post-clear:** NPC 2041025 warps only the talker (2041025.js:33). G7 rule B handles the pull-out.
- **No summons:** 220080001 has no SUMMON bit (fieldLimit 8440). Bots may summon here, as players may.
- **Restock:** 220080000 gets the trip flag (G9). The shop at 220050300 is 6 hops each way. One desk trip (decision 10), with standard 60/60.
- **Special roles:**
  - The owner drops the piece while standing on the y=-538 platform, at x -148 to -14 (Map2/220080001.img.xml:205-207, Reactor.java:400-402).
  - Ranged and magic bots take the flying phase. Unconfirmed: whether melee bots reach it.
  - Fake Low Darkstars are on the never-target list.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung.
  - Balance run with crew B on rung 5 (Lv120, its own 4th jobs).
  - Two-owner check on the same rung: Bossfriend and ohboy plus 4 companions. The party cap is 6.
  - Crew B's levelling path is decision 1.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `papulatus`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!resetboss <name> PAPULATUS` for each character. It works only for characters who are online (ResetBossCommand.java:22-26).
  2. Run `!item 4031179` as Bossfriend.
  3. From Bossfriend, run `!warp 220080000`.
  4. For the two-owner check, run `!warphere ohboy` from Bossfriend (GM3, CommandsExecutor.java:509). His companions follow him.
  5. Form the party.
  6. Enter, then drop the piece from the platform.
  7. Watch deadly attack1 set bots to 1 HP and 1 MP, and watch them drink.
  8. Check the blast log when bombs self-destruct near bots.
  9. Run `!hpmp Sorrel 0`. Check that he waits at the gate.
  10. Leave through 2041025. Check the pull-out.
  11. Check that the boss log has an entry for each companion that entered, and none for one that stayed out.
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
- **Damage:** field-scope mirror. Distributed attacks use the G1 fallback. Pianus attack2 is type 3 (8510000.img.xml:800-807, :972), and Lyka attack1 is type 4.
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
- **Prerequisites:** Temple of Time lanes are quest-gated (timeQuest.js:26-55), see decision 21. Pianus is entered by the script portal boss00 (Pianus.js:21-25).
- **Death:** G14 rest, then follow. Walk-back cannot be routed into 230040420, 240020401, 240020101 or 682000001, because they have no route-graph entry edges.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung.
  - Balance run with crew B on rung 5 (Lv120) for Pianus, Leafre, Zipangu and Masteria.
  - Balance run with crew B on rung 6 (Lv140) for the Temple of Time and Female Boss.
  - Crew B's levelling path is decision 1.
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
  - Mirrored multi-region hits stack, because no hit cooldown follows a server hit (AgentCombatDamageRuntime.java:160-190).

## P17: Scarlion and Targa

- **Damage: mirror** on both 3-form chains, 9420542-9420544 and 9420547-9420549. Furious Scarlion moves.
- **Immunity:** every form has 140 and 141 (Mob.wz info/skill), so G4 from P12 is required.
- **Registration:**
  - The boss-run service on Aldol 9270047, with min 6 (ExpeditionType.java:34).
  - Living code enforces Lv100 for companions, because `addMember` checks no level (Expedition.java:207-229).
- **Prerequisites:** the owner needs 4032246 to create (9270047.js:99-103). One completion of quest 4576 gives 2 (Act.img.xml:30093-30106).
- **Quota:** 1 a day (ExpeditionBossLog.java:54), handled by G8.
- **Death and re-entry:**
  - Mode: re-enter by NPC. The bot revives in Kampung 551000000, walks 6 hops back and runs Aldol headless (9270047.js:82-91).
  - Stock never clears canJoin (ScargaBattle.js:96).
  - The owner, as leader, cannot re-enter, so G7 rule A applies.
- **Post-clear:** Aldol 9201134 handles the exit, followed by the pull-out.
- **No summons:** the battle map 551030200 has the SUMMON bit (fieldLimit 1020), so G11 applies.
- **Restock:** 551030100 joins the boss map list with no trips. The nearest supplier is Trend Zone Metropolis 550000000 (potion-supplier-catalog.json:34), 11 hops from the gate. Kampung has none. Companions top up in 550000000 (G9). Standard 100/100.
- **Special roles:**
  - The owner drops the two keys on reactors 5511000 and 5511001 (5511000.js:5-9, 5511001.js:5-9).
  - The plan summons one boss at a time.
  - The adds revive into real attackers, 9420545 to 9420532 and 9420550 to 9420531.
- **Boss id list:** P17 adds the adds 9420545, 9420550, 9420531 and 9420532. None has the boss flag, so G2's expedition branch misses them. The six boss forms are boss-flagged and need no entry.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung, plus the 3 bench allies on their Lv160 rung.
  - Balance run with crew B on rung 5 (Lv120). Crew B's levelling path is decision 1.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `scarga`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Check that each bench ally is still Lv160 from P8. If one is lower, raise it as in the setup procedure.
  2. Run `!resetboss <name> SCARGA` for each character.
  3. Run `!item 4032246 2` as Bossfriend.
  4. Run `!warp 551030100`.
  5. Create at Aldol and register the party.
  6. Start, then summon Targa.
  7. Watch immunity stop the bots or make them switch targets.
  8. Run `!hpmp Ingrid 0`. Check that she walks back and registers again.
- **Effort:** L.
- **Risks:** Unconfirmed: whether 600M HP can be cleared in 60 min.

## P18: Horntail

- **Depends on:** P17 only for the generic expedition flow. G4 and G6 come from P12.
- **Damage: mirror** on each part, 8810000-8810009, and on the adds 8810019-8810023.
  - noFlip boxes are not mirrored.
  - Each attack keeps its own PAD.
  - Regions use the G1 fallback when areaCount is above 16 (MoveLifeHandler.java:83).
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
- **Quota:** 1 a day (ExpeditionBossLog.java:52), handled by G8.
- **Death and re-entry:**
  - Mode: re-enter by NPC. The bot revives in Leafre, restocks at NPC 2080001, takes the waiver warp and registers again at 2083004 (2083004.js:80-84).
  - The owner-leader cannot return (2083004.js:64-67), so G7 rule A applies.
  - A dead owner stays the fallback controller until revive (Monster.java:2131-2145).
- **No summons:** 240060200 has the SUMMON bit (Map2/240060200.img.xml:2374). The pre-head maps 240060000 and 240060100 do not (fieldLimit 335992).
- **Loot:** the G10 ownership rule matters most here, because forwarded part damage credits the attacker.
- **Special roles:**
  - The owner hits reactor 2401000 six times (2401000.js:29-34).
  - Melee bots fight the legs and tail from the floor. Ranged and magic bots take the heads.
  - Bots switch away from immune heads. The hands, wings, legs and tail never go immune.
  - A Bishop rebuffs after a dispel.
  - Overflow parties need their own healer, because heals are party-only (AgentCombatSupportPolicy.java:208-216).
- **Restock:** 240050400 joins the boss map list with no trips. No route edge leaves it, so companions top up in Leafre (G9). Standard 100/100.
- **Test party:**
  - Crew A on its Lv160 rung, plus the 3 bench allies on their Lv160 rung.
  - Crew B on rung 7 (Lv160) as the second owner. It reaches rung 7 only after rung 6. Its levelling path is decision 1.
  - Measure damage first with `!horntail`. It spawns the parts without pre-heads (GM3, HorntailCommand.java:44).
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `horntail`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!item 4001086` as Bossfriend.
  2. From Bossfriend, run `!warp 240050400`.
  3. For the two-owner check, run `!warphere ohboy` from Bossfriend (GM3, CommandsExecutor.java:509). His companions follow him.
  4. Create, register and start.
  5. Kill both pre-heads, then hit the reactor.
  6. Check that bots never hit 8810010-8810018.
  7. Run `!hpmp Flint 0`. Check his re-entry.
  8. Never use `!killall`. It completes the fight (KillAllCommand.java:51).
- **Effort:** XL.
- **Risks:**
  - Unconfirmed: whether the crew deals the needed 580k damage per second for 60 min.
  - The region decode may fail.
  - Mob mist does no server damage, which is an immersion gap (MapleMap.java:2269-2312).

## P19: The Boss (Showa expedition)

- **Damage: mirror** on 9400112, 9400113 and 9400300. The fight has a deadly doFirst attack and mpBurn 50000.
- **Registration:** the boss-run service on Konpei 9120201 at 801040004, with min 3 (ExpeditionType.java:35). Living code enforces Lv100.
- **Prerequisites:**
  - The owner needs the comb 4000138 (9120201.js:36, :98-99).
  - The comb drops from Female Boss on the field map 801040003. P16 lets companions help there.
- **A field boss on the recruit map:**
  - 801040004 spawns Male Boss 9400122 at (511,147), with mobTime 7200 (Map8/801040004.img.xml:180-185). It is boss=1 and Lv95.
  - The map also has 14 x 8140500. With the 10 s override (fact 9), Male Boss returns 2-4 s after each kill.
  - G7 rule C keeps registered companions on this map. G14 applies there, so companions hold and do not engage on their own.
  - P16 has already put 9400122 on the boss id list. This is why P19 depends on P16.
- **Target priority:** the battle map respawns Male Boss every 15 s (ShowaBattle.js:113). Bots target the 9400112 → 9400113 → 9400300 chain first. The battle-map Male Boss and the extras come lower, by the G3 priority list.
- **Quota:** none.
- **Death:**
  - Mode: wait outside in Showa Town. Stock re-entry is broken (9120201.js:83 against ShowaBattle.js:95), see decision 16.
  - Any death cancels the slot-machine bonus (ShowaBattle.js:141-143, :194-205), and bot deaths count. See decision 14.
- **Post-clear:** Konpei 9120202 moves each player to 801040101, which is still inside the run. G7 rule B handles the pull-out.
- **No summons:** 801040004 (fieldLimit 228) and 801040100 (fieldLimit 252) have the SUMMON bit, so G11 applies.
- **Restock:** 801040004 joins the boss map list with no trips. Companions top up in Showa Town, shop 801000300 (G9). Standard 100/100.
- **Test party:**
  - Mechanics run with crew A on its Lv160 rung, plus the 3 bench allies, because the forms total 1.05B HP.
  - Balance run with crew B on rung 5 (Lv120, gate Lv100). Crew B's levelling path is decision 1.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `showa` and `field_zipangu`. The field key keeps the G14 rule on Male Boss 9400122.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!item 4000138` as Bossfriend.
  2. Run `!warp 801040004`.
  3. Create and register.
  4. Check that registered companions do not attack Male Boss 9400122 on their own.
  5. Start.
  6. Drop the comb on reactor 8001000.
  7. Watch the deadly hits and the mpBurn on mages.
  8. Check that bots hit the boss chain before the battle-map Male Boss.
- **Effort:** L.
- **Risks:** Unconfirmed: whether 1.05B HP can be cleared in 60 min.

## P20: Pink Bean

- **Depends on:** P18 for the multi-part scale. The revive-inside mode is G6, from P12.
- **Damage: mirror** on 8820001-8820007. Pink Bean moves. Type-3 attacks use the G1 fallback. Pink Bean's attack1 is 9 of 9 regions. G1 hits it in full only if the P7 live check shows hits outside the base box (G1, fact 17).
- **Never-target list:**
  - The 1-HP statue 8820000. No server guard protects it (MapleMap.java:1441-1450).
  - The statues 8820019-8820023. A statue kill breaks startWave (PinkBeanBattle.js:232-244).
- **Registration:**
  - The boss-run service on 2141001 (ExpeditionType.java:43).
  - Living code mirrors the Lv120 check, because only the talker is checked (2141001.js:61).
  - `EXPEDITION_BURST_MAX` 12 caps the allies (config.yaml:814).
- **Prerequisites:** companions follow-warp past the dragon ride and the Temple of Time gates (2082003.js:7-8, timeQuest.js:29-44), under plan decision 11a.
- **Quota:** 1 a day (ExpeditionBossLog.java:53), handled by G8.
- **Death and re-entry:**
  - Mode: revive inside.
  - Humans stay in the run when they revive (PinkBeanBattle.js:165-167). The human path is ChangeMapHandler.java:101-107, then Character.java:8794-8795 (`respawn(null, …)`), then Character.java:1704-1714 (`eim.getMapInstance`). Bots do not stay in today.
  - The gate copies the human path. The bot then walks to the Pinkin portal at x -1156 (Pinkin.js:26-29).
  - Deaths count toward the 5-death wipe (PinkBeanBattle.js:149-163), see decision 13.
- **Loot guard:** no bot may pick up 4001193. The reactor fires only if the item is still on the ground (MapleMap.java:2369-2384).
- **No summons:** 270050000 and 270050100 have the SUMMON bit (fieldLimit 270972 and 2433660), so G11 applies.
- **Restock:** no shop is reachable from 270050000. Companions top up in Leafre before the ride (G9). 270050000 joins the boss map list with no trips. Standard 100/100.
- **Special roles:**
  - Waves of Solomon, Rex, Hugin, Munin and Ariel.
  - Ranged bots keep out of Solomon's left deadly box and Rex's right one (8820003 and 8820004 attack1).
  - Munin's banish uses the G5 banish rule.
- **Test party:**
  - Crew A on its Lv160 rung, plus the 3 bench allies.
  - Crew B on rung 7 (Lv160) for the two-owner check. Its levelling path is decision 1.
- **GM-first live check:**
  - **Before you start:** a restart disconnects everyone online.
  - **0a.** Set `COMPANION_BOSS_ENABLED_TYPES` to include `pinkbean`.
  - **0b.** Restart with `start-maple-stack -RestartServer`.
  1. Run `!resetboss <name> PINKBEAN` for each character.
  2. From Bossfriend, run `!warp 270050000`.
  3. For the two-owner check, run `!warphere ohboy` from Bossfriend (GM3, CommandsExecutor.java:509). His companions follow him.
  4. Create, register and start.
  5. Watch each wave.
  6. Run `!hpmp Isolde 0`. Check that she revives in the 270050200 instance and walks back through Pinkin.
  7. Check that the stage-5 item stays on the ground.
- **Effort:** XL.
- **Risks:**
  - Unconfirmed: whether 7.65B HP can be cleared in 140 min.
  - The reactor-dropper bug can kill the statue on the wrong map (2708000.js:41, PinkBeanBattle.js:265). Decision 15 fixes it.
  - Every death costs EXP. The map has no NO_EXP_DECREASE bit (270050100.img.xml:14).

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

## Findings that also affect P7 and P8

- **No summons on Easy Balrog and Zakum maps.** 105100400 (Map1/105100400.img.xml:15), the Zakum altar 280030000 (Map2/280030000.img.xml:13) and the Zakum recruit map 211042400 have the SUMMON bit. The server never checks it. Companions must not summon there. Scope the rule to companion boss runs, because KPQ, HPQ and LPQ maps have the bit too. P7 step 6b now builds it for every INSIDE member of an `AgentBossRunSession` (p7a_amendments.md item 1). P8 needs only Zakum to run as one.
  - Registered companions that wait on 211042400 are not INSIDE, so step 6b does not cover them. If P8 ships before P12, P8 builds G11 cases 2 and 3 for that map.
- **Post-clear stranding.** clearPQ stops the timer, and follow never warps a bot out of its instance (AgentFollowMapSyncService.java:85-86). User decision 9 must also apply after a clear. Zakum's exit needs the same pull-out (G7 rule B).
- **Boss clears are written as PQ clears.** clearPQ runs the PQ-clear hook for every boss (EventInstanceManager.java:865-871). Easy Balrog writes records under 'Balrogundefined' (BalrogBattle_Easy.js:119). Decision 2 covers P7 and P8 too.
- **Drop ownership.** The highest damager owns the drops (Monster.java:908-909). Bot-led overflow parties in P7 Part B can own boss drops. The G10 ownership rule should land with P7 Part B. If it does, P12 does not build it again.
- **The 9-of-9 under-hit.** Easy body 8830007 attack1 is lt(-400,-600) rb(400,0), start -4, 9 of 9 regions (8830007.img.xml:140-145). The server hits only x -400 to 400 (BossAction.java:32-33). Unconfirmed: the client range. P7 checks it live and does not change BossAction (p7a_amendments.md item 2). If the client hits wider, the Easy and Normal Balrog behaviours and the mirror hit all 9 regions (G1). P11 makes that change in the shared `EasyBalrogBehavior`, so Easy Balrog changes too.
- **`!setlevel ... auto` is refused for companions** (SetLevelCommand.java:170-172). A GM sets only its own companions while they are out with it and not on a bench slice (CompanionLevelTool.java:27-31, :317-325). The P7 Part B test party needs decision 1 for ohboy's crew.
  - For the bench allies, Bossfriend uses this order. The party cap is 6, and a called companion is not ACTIVE until it arrives (RosterService.java:879, :886-890, CompanionLevelTool.java:317-318).
    1. Set the five party companions.
    2. Bench three of them.
    3. Call the three allies. Wait until each one arrives, because the recall whistle is off (config.yaml:798).
    4. Set each ally.
    5. Bench the allies.
    6. Call the three party companions back.
- **No route out of Mu Young's room.** The route graphs have only edges into 105100100 (victoria-portal-route-graph.json:1737, :1741). Its exits are script portals, such as outPerrion_1.js to 105100000.
  - A route leg is not enough. The nearest shop is 24 hops from 105100000. It is Henesys 100000102.
  - Sleepywood 105040300 has no shop. Henesys is 6 hops from it (breadth-first search over both route graphs).
  - So Easy and Normal Balrog both rely on the Sleepywood top-up, if P7's Sleepywood decision adds it. That top-up needs the decision's own hop budget, because STANDING_MAX_HOPS is 2 (G9).
  - If P7's Sleepywood decision is unanswered, both have only the no-shop line. Normal Balrog then never reaches its 60/60 standard.
  - 105100100 stays off COMPANION_RESTOCK_BOSS_MAPS. That list allows no trips (CompanionRestock.java:98-101), and P7 owns the map.
- **P8's quota filter and decision 7.** P8 plans a quota filter that logs only members on the recruit map, for every player (BOT_MANAGEMENT_PLAN.md:306, p7a_plan.md:634). Decision 7's default, "companions only", also sets the scope of that filter. With the default, P8 limits the filter to companions.
- **Boss standards key.** `PARTY_QUEST_STANDARDS` accepts only `[a-z0-9]+` keys (AgentEngineConfigValidator.java:140-141). Every boss standard goes into P7's BOSS_STANDARDS key instead (agent-engine.yaml:4297 at the tip).
- **The Easy body never touches a bot on the floor.** 8830007 has the same stand box as 8830000 (fact 10). P7 tests should expect claw touch, not body touch.
- **`!debuff` skips Agents** (fact 19). A P7 or P8 test that debuffs a companion with it does nothing. Use the G5 form.
- **Zakum leader lockout:** an owner who leads and dies can never re-enter (2030013.js:66-69). The P8 owner-death policy must not wait for that owner.
- **Mirror decode:** check raw activity 24-41 directly. The stock range check passes every value at or above the minimum (MoveLifeHandler.java:192-194).
- **Zakum arm 8800010 attack2:** its radius is -1000, which gives a null box today (BossActionGeometry.java:21).
- **One-sided noFlip boxes.** Unconfirmed in the client: `EasyBalrogBehavior.addAttack` can hit the mirrored side (EasyBalrogBehavior.java:83-91).
- **Zakum physics exclusion:** build it in P8 as the generic G2 predicate, so that later phases only add ids.
- **Gear tool timing:** P8 should build the G13 gear tool early. P11's Lv100 check needs it before the P8 Lv160 check raises crew A.

## Every phase

- **Guard tests stay green:** AgentKpqIsolationTest, ClaimGuardBoundaryTest, RosterStateWriterBoundaryTest, BenchLoadsOnExecutorTest, AgentLivingImportBoundaryTest, AgentConfigurationBoundaryTest and AgentArchitectureBoundaryTest. Add AgentSchedulerBlockingBoundaryTest when new Agent code is added. This is the guard set of p7a_amendments.md item 7. AgentConfigurationBoundaryTest enforces the no-numeric-constants rule.
- **Full suite:** run it with `-Dtest='!BotManagerTest,!BotCombatManagerTest' -Dsurefire.failIfNoSpecifiedTests=false`. The baseline is 0 failures and 40 skipped, in about 10,651 tests. Any failure is new.
- **House rules:** server.agents never imports server.living. KPQ, HPQ, LPQ and EPQ behaviour does not change. Edits to shared files are small and append-only.
- **Database:** if a phase needs a table, it uses changeset 47 and db/tables/038-*.sql.
- **Hand-over:** each hand-over states the one-line death-chain change, if the phase touches it, and any balance run that crew B dropped.

## Details

- I edited no worktree file. The worktree is C:/Users/ganre/AppData/Local/Temp/claude/C--Users-ganre-Desktop-MapleServer/57350396-c11f-43a7-ab35-10105dd00020/scratchpad/wt, branch feat/companion-bossing. Its tip is 377080747 (P7 Part A step 4 review fixes). Under it are 5b30d541c (step 4), fa7550735 (step 3), 23fc98bc5 (step 2) and bbf274d2d (step 1), on the build base c6883f520. It is no longer f5b8c0ab5. The live code is 76c067fd7.
- Step 3 (fa7550735, `installServerAuthorityRule`) makes the force flag a per-encounter `Predicate<MapleMap>` in EasyBalrogEncounterService (server.life.autonomy.balrog). P11's "force flag is per encounter" names that seam.
- Line numbers are at f5b8c0ab5, except refs to P7 Part A code that exists only at the tip: config.yaml:940, LivingWorldConfig.java:319, CompanionBossKeys.java:18-23, agent-engine.yaml:4297 and EasyBalrogEncounterService.java:36-49, :74-85.
- On c6883f520 and at the tip these lines move:
  - agent-engine.yaml: `PARTY_QUEST_STANDARDS` :4239 to :4253, `IGNORED_MOB_IDS` :1314 to :1320, `MOB_TOUCH_SWEEP_HEIGHT` :1318 to :1324.
  - agent-engine.yaml: `PARTY_QUEST_HP_POTIONS` :4231 to :4245, `PARTY_QUEST_MP_POTIONS` :4235 to :4249.
  - agent-engine.yaml: `STANDING_MAX_HOPS` :4251 to :4265, `DESK_MAX_HOPS` :4255 to :4269.
  - `LivingWorldHooks.partyQuestEntry` :530 to :565.
  - CosmicMapGateway.java:74-78 to :77-81.
- I re-checked these claims myself for this revision:
  - fieldLimit SUMMON bits on 103000800-805 and 103000890, 922010100-922011100, 910010000 (and not on 105100100, 910010100-910010300 or any 9300xxxxx EPQ map), 105100300/301/400, 280030000, 211042400, 240060000/100/200, 270050000/100, 220080001, 541010060/100, 551030200, 801040004/100.
  - boss flags in Mob.wz: 9420511, 9420512, 9420545, 9420550, 9420531 and 9420532 have none. Every id listed in P13, P14 and P16 exists in Mob.wz.
  - `!setgmlevel` GM6: CommandsExecutor.java:664 and SetGmLevelCommand.java:44-47.
  - roster calls: RosterService.java:879 (PartyFull), :886-890 (walk unless a whistle), :1124-1128, and RECALL_WHISTLE_ITEM_ID 0 (config.yaml:798).
  - the Normal exit: 1061018.js:35 warps only the talker to 105100301, BalrogBattle.js:32-35 keeps 105100301 inside, and portal out99 runs balog_end.js to 105100100.
  - Dodo 8220004 on 270010500, the `partyQuestDesk` seam (AgentSupplyReadinessRuntime.java:501-509) and the guard tests of p7a_amendments.md item 7.
  - hops both ways from each boss recruit map to the nearest supplier (breadth-first search over both route graphs).
  - the level tools: SetLevelCommand.java:55-57, :170-172, :278-279 and CompanionLevelTool.java:27-33, :317-325, :350.
  - `!debuff` target filter: DebuffCommand.java:107 and CommandTargetPolicy.java:42-44.
  - BossAction.java:32-33, MapleMap.java:1471-1477, Monster.java:908-909, EventInstanceManager.java:865-871, LivingWorldHooks.java:284-304.
  - the Balrog stand boxes 8830000/8830007 and the claws 8830001/8830002, AgentMobTouchRuntime.java:21 and agent-engine.yaml:1318.
  - bomb self-destruct data on 8500003, 8500004 and 8510100.
  - Male Boss 9400122 on Map8/801040004.img.xml:180-185 and ShowaBattle.js:113.
  - the route-graph gap out of 105100100 and outPerrion_1.js, the 24 hops from 105100000 to Henesys 100000102, and the 6 hops from Sleepywood 105040300 (hops2.py).
  - the kill switch and key checks: config.yaml:940, LivingWorldConfig.java:319, CompanionBossKeys.java:18-23 and AgentEngineConfigValidator.java:140-144.
  - `!warp` GM2 and `!warphere` GM3: CommandsExecutor.java:481, :509.
  - the human revive path: ChangeMapHandler.java:101-107, Character.java:8794-8795, :1704-1714.

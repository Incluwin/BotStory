# P11: Normal Balrog with companions

> Built 2026-10-12 on branch `feat/companion-bossing-p11` (base c6edaf025). Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P11. Not deployed, not live-tested yet. The switch ships off.

## Result

An owner at Mu Young (105100100) can now bring up to five of their own party companions into a **Normal** Balrog
expedition, as P7 does for Easy. With a companion signed up, the server runs the Normal fight (the Easy encounter with
Normal's mob ids). With no companion signed up, Normal Balrog is the stock fight, byte for byte (decision 4).
Everything stays off until `COMPANION_BOSS_ENABLED_TYPES` lists `balrog_normal`.

## What the player sees (with the switch on)

- Mu Young's Normal windows offer "My party will register too." in the same places as her Easy windows. Her
  "Call allies from my bench..." stays off for Normal: `COMPANION_BOSS_ALLY_TYPES` does not list `balrog_normal`.
- Each companion walks up beside Mu Young and signs up with her second choice (Normal). The list needs 6 (owner + 5).
- A companion under Lv50 is refused by her own level check and says the P7 refusal line.
- While a signed-up companion is inside, the server moves Balrog and its attacks reach the companions, as in Easy.
  `COMPANION_BOSS_SERVER_AUTHORITY: false` keeps the server's encounter but lets the clients drive it.
- Companions hit the first claw (8830002) while both claws are up, then the released claw (8830001), then the real
  body. They hit the summoned Jr. and Crimson Balrogs (6400005, 6400006). They stop while Balrog reflects.
- They never hit the fake body, the claw helpers (8830004, 8830005) or the seal (8830006), not even with a secondary
  or projectile target.
- They never summon on the Normal maps (P7 step 6b: the maps forbid summons for players).
- The body keeps its stock drops. The HP bars are the stock ones (Easy's combined gauge is Easy only).
- No sealed Balrog spawns after the body dies. This matches the stock fight, where that spawn never worked.
- A dead companion revives at 105100100 after 5 s, says one line and waits for its owner (P7's way).
- After the clear, the exit NPC (1061018) moves only the person who talks to her into the reward room. Companions
  follow their owner there, and leave with the owner by the out99 portal.
- An owner who leaves before the clear takes their party's companions out with them (rule B, as for Zakum).
- Ant Tunnel Park (105070001) and Mu Young's room keep the larger potion standard when both Balrog keys are on.
  At the park, a companion under Lv50 keeps neither standard.

## Config

`config.yaml` (living_world): no new key. `COMPANION_BOSS_ENABLED_TYPES` accepts `balrog_normal`
(`config.CompanionBossKeys`). It ships `"easy_balrog"`, so Normal stays stock.

`agent-engine.yaml` (values appended to existing keys, with their Values notes):

| Key | New entry | Why |
|---|---|---|
| `AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS` | `balrog_normal=300000/240000` | 300 Unagi and 300 Pure Water. The body has a deadly attack, so MP equals HP (decision 22). |
| `AgentSupplyReadiness.BOSS_STANDARDS` | `balrog_normal=300/300` | The count fallback. The points pair wins while it is listed. |
| `AgentSupplyReadiness.AMMO_STANDARDS` | `balrog_normal=2500` | A 25-minute fight at 100 projectiles a minute. |

New catalog file: `src/main/resources/agents/catalogs/boss-rules.json`, loaded by `AgentBossRules`. It holds the
Normal Balrog parts, the target order, the adds and the never-target list. P12 extends it to every boss.

No database change. Changeset 47 stays free.

## How it is built

- **Core (server.life).** `EasyBalrogEncounterService` has a `Variant` (EASY, NORMAL). `startNormal(map, origin)` runs
  the Normal fight only while the installed companion-fight rule says yes. A failed start falls back to the stock
  spawn. Three new behaviours (`NormalBalrogBodyBehavior`, `...ReleasedClawBehavior`, `...InitialClawBehavior`) never
  start on spawn, so a stock fight never becomes the server's. The Normal body reads the Easy body's telegraph switch
  (`!balrogprobe telegraph`), so Fix B holds for both. `BalrogSummonedAddBehavior` covers 6400005 and 6400006.
- **Event script.** `BalrogBattle.js` asks `startNormal` in `afterSetup`. With a companion fight it sets the event
  property `serverEncounter`, skips the stock claw release, counts no claw kills, clears on the real body's death
  (clear effect, both quest counts, victory notice) and stops the encounter at `end` and `dispose`. Without one, every
  function runs its stock code.
- **Mu Young (1061014.js).** The five hooks pass `bossKey()`: "easy_balrog" or "balrog_normal", by the difficulty
  picked. The member join branch covers both types. With the switch off, every window is the stock one.
- **Agent side.** `AgentBossRunCatalog.BALROG_NORMAL` (join choice 2, `neverTargetFakes` and
  `ownerLeavingTakesParty` on, no marker item). `AgentEasyBalrogScenario.forNormalCompanions()` is the Easy fight with
  `AgentBalrogFight.NORMAL` (maps from `AgentBalrogDefinition`, mob ids from the catalog). The two arenas are WZ twins,
  so Easy's stations and walls serve both. `AgentBossRunRegistry.neverTarget` also reads the catalog's list.
- **Living side.** `BossEntrance.BALROG_NORMAL` shares Easy's room, spots, park and portals. Entrances on one map are
  now lists (`allAtRecruitMap`, `allAtApproachMap`). `CompanionBossRuns` picks the larger standard where two listed
  bosses share a map (G9 item 2). `companionFight(map)` is the Normal rule, keyed by battle map, and
  `forcesServerAuthority` now uses it too. `LivingWorld` installs and removes the new rule.

## Live check (crew B on rung 2)

**Before you start:** a restart disconnects everyone online.

0a. In `config.yaml`, set `COMPANION_BOSS_ENABLED_TYPES: "easy_balrog,balrog_normal"`.
0b. Restart with `start-maple-stack -RestartServer`.

Setup (plan section "Setup for a crew B rung", Lv100 with 60% gear):

1. From Bossfriend, run `!setgmlevel ohboy 6`. ohboy must be online on Bossfriend's channel.
2. As ohboy, run `!setlevel me 100 <job> auto`.
3. As ohboy, run `!setlevel <name> 100 <job>` for Brannock, Garrick, Fenna, Ilse and SilentSin. Do not add `auto`.
4. As ohboy, run `!bossgear <name>` for each companion. Then run `!bossgear me` and equip the pieces.
5. As ohboy, run `!givemeso <name> 1000000` for each companion. The 300/300 standard costs about 0.86M at the park.
6. From Bossfriend, run `!setgmlevel ohboy 0`.

The run:

7. From Bossfriend, run `!warp 105040300` (Sleepywood). Then run `!warphere ohboy`.
8. As ohboy, walk down through the Ant Tunnels. Do not warp past Ant Tunnel Park 105070001.
9. In Ant Tunnel Park, expect each short companion to say a go line, buy at the 24 Hr Mobile Store and catch up.
10. Walk on to Mu Young's room 105100100.
11. Start the client memory gate: the logger, and the client that friends use.
12. As ohboy, talk to Mu Young. Pick Normal, then "Lets get this going!". Pick "My party will register too.".
13. Expect five walk-ups and five join notices. From Bossfriend, run `!expeditions`. It lists 6.
14. Start. In the server log, expect `Normal Balrog encounter started map=105100300 ... serverAuthorityForced=true`.
15. Watch companions lose HP to claw touches, claw attacks and the body's attack3.
16. Check that companions hit 8830002 first while both claws are up (the 30 s "Normal Balrog combat decisions" line).
17. Check that bots stop hitting the body while reflect is up ("Normal Balrog body status ... reflect=true").
18. Check that no companion casts a summon.
19. From Bossfriend, run `!hpmp Garrick 0`. Check his one line and his wait at 105100100.
20. After the clear, talk to 1061018 as ohboy. It moves only ohboy to 105100301.
21. Check that the companions follow ohboy into 105100301.
22. Leave through portal out99. Check that the companions leave too.
23. Read the "PQ potions ... pq=balrog_normal" lines. Tune the 300/300 standard from them.

Rollback: set `COMPANION_BOSS_ENABLED_TYPES` back to `"easy_balrog"` and restart. Normal Balrog is then stock.

### Live result 2026-10-12 21:05-21:12 (int/p9ax + a temporary `easy_balrog,balrog_normal`): PASS

Crew B at Lv100: Brannock and Garrick (Crusader), Ilse (Priest), Fenna (Ranger), Corvin (Dragon Knight, in
SilentSin's seat). ohboy Lv100 Hermit, gm 0. Run session=1 cleared in 3 min 20 s.

- Steps 1-6: done. Exception: ohboy wore his Lv70 gear plus the Lv85 cape. The client refused every Lv90-100 thief
  piece from `!bossgear me` with "Your stats are too low": the client's Character.wz asks DEX 140 for Red Craven, and
  the server's copy asks DEX 0. The `auto` build gave ohboy DEX 126. Companions are not affected (no client check).
- Step 9: Fenna and Corvin said a go line, bought at 1061001 and came back. Brannock, Garrick and Ilse were not short
  (301-380 HP potions), so they did not go. They also got 300 Unagi and 300 Pure Water by `!giveitem` before the
  potion line showed that. Companions left in Mu Young's room after the Easy run followed ohboy's `!warphere` only after
  "follow me" in party chat.
- Step 13: `!expeditions` listed 6. Step 14: "Normal Balrog encounter started ... serverAuthorityForced=true".
- Step 16: every companion targeted 8830002, then 8830001 after the seal broke. Claws down in about 90 s.
- Step 17: not observed. No 30 s status line fell in a reflect window.
- Step 18: no companion summon (the only SUMMON lines are claw 8830001's own casts).
- Step 19: `!hpmp Garrick 0` -> one "sorry, balrog got me. waiting outside.", out=DIED, he waited at 105100100.
- Steps 20-22: only ohboy moved to 105100301, the companions followed, left by out99 with him, "we did it! gg.".
- Step 23: `PQ potions ... pq=balrog_normal outcome=cleared`: HP potions used 0-147, MP potions 0-7 per companion.
  The 300/300 standard is far above a 3-minute fight at rung 2; keep it until a slower crew is measured.
- Client memory gate: ohboy's client virtual size 3,664 -> 4,047 MB of 4,096 (private 635 -> 1,034 MB), the same
  level as P7 Easy run 2 (4,040 MB).
- 0 ERROR lines from start to the walk-out.

## Not covered and known gaps

- No bench-ally stage and no second owner. P7 Part B checks both on the same NPC and service.
  `balrog_normal` stays off `COMPANION_BOSS_ALLY_TYPES` until an ally stage passes the client memory gate.
- The body's attack1 hits 9 of 9 regions. Server authority still hits only the base box, as in Easy (fact 17).
- A second run can start 10 s after a clear (EventManager.java:250-257). Unchanged.
- Unconfirmed live: whether the 300/300 standard lasts a 25-minute fight at Lv100.

## Hand-over

- Death chain: not touched. A dead companion takes P7's DIED path.
- Knowledge file: this branch edits `scripts/npc/1061014.js` and `scripts/event/BalrogBattle.js`, so
  `KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed` fails until the integrator runs `tools\knowledge-export.bat`.
- Balance run: the live check above is P11's only check. It is crew B's rung-2 run.
- A graft constant moved: `AgentEasyBalrogScenario.REWARD_REACTOR` is now `AgentBalrogDefinition.REWARD_REACTOR_ID`
  (removed from `RECORDED_GRAFT_CONSTANTS`).

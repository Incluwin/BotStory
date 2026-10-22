# P13: Low-level field bosses

> Built 2026-10-12 on branch `feat/companion-bossing-p13`, on top of P12. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P13. Not deployed, not live-tested yet. Every field key ships off.

## Result

Companions can now fight the 20 low-level field bosses beside their owner, by region group. With every field key off
(as shipped), nothing changes for bosses. One change ships on: followers wait outside the Demon's Doorway maps.

## What the player sees (with a field key on)

Example: `field_victoria` on, the owner at Jr. Balrog (105090900).

- Jr. Balrog stays with the owner's client when companions or citizens hit it. It keeps attacking the players, and
  its attacks now reach every Agent in range too (the P12 field mirror).
- Companions do not attack Jr. Balrog until a player of their party has hit it. After it dies, it respawns about 10 s
  later (decision 20). The companions wait again until a player hits the new one.
- Citizens never attack a listed boss. They still take its hits.
- A companion that dies beside the boss revives in town after 5 s and rests there. It follows its owner again once
  its HP and MP are full, or after 2 minutes.

Shipped on, with no key:

- A following companion never warps into the Demon's Doorway maps 677000000-677000013. It waits where it stands
  until its owner comes back out.

## Field groups

| Key | Bosses (mob ids) |
|---|---|
| `field_victoria` | Mano 2220000, Stumpy 3220000, Faust 5220002, Dyle 6220000, King Clang 5220001 and 5220000, Unknown Snack Bar 8220008, Snack Bar 8220009, Mushmom 6130101, Zombie Mushmom 6300005, Jr. Balrog 8130100 |
| `field_ossyria` | Eliza 8220000, Snowman 8220001 |
| `field_ludus` | Timer 5220003, Zeno 6220001, Nine-Tailed Fox 7220001 |
| `field_mulung` | Tae Roon 7220000, King Sage Cat 7220002, Giant Centipede 5220004 |
| `field_nihal` | Deo 3220001, Kimera 8220002 |

The groups follow each boss's spawn map (the AreaBoss scripts and Map.wz life entries).

## Change from the plan

The plan adds each field boss to `AgentBossScope.BOSS_IDS`. That list is read with no key, so the control-steal guard
would act on these bosses at once, with every field key off. P13 instead guards a field group's boss only while its
key is on (`AgentBossScope.covers` asks `AgentBossField.covers`). `BOSS_IDS` stays `none`, as its yaml note recommends
("none until a field boss has its own live check"). The plan's startup check for field keys is not needed: the ids
come from the same catalog row as the key.

## Config

`config.yaml` (living_world):

| Key | Shipped | Meaning |
|---|---|---|
| `COMPANION_BOSS_ENABLED_TYPES` | `"easy_balrog"` | Now also accepts the five field keys. |
| `COMPANION_FIELD_BOSS_REST_PERCENT` | `100` | A companion that died beside a field boss rests until HP and MP are back to this percent. |
| `COMPANION_FIELD_BOSS_REST_MAX_MS` | `120000` | The longest that rest lasts, from the death. |

`agent-engine.yaml`: `AgentFollowMapSyncService.NO_FOLLOW_MAPS: '677000000-677000013'`, the maps a follower never
warps into.

No database change.

## How it is built

- **Catalog.** boss-rules.json has one row per field group with `fieldBosses`. `config.CompanionBossKeys` knows the
  five keys.
- **Guard (G2).** `AgentBossScope.covers`: on a map with no event, a listed id or a field group's boss whose key is on.
- **Engagement (G14).** `AgentBossTargetGate.refuses` asks the host seam `AgentBossRunHost.Rule.mayEngageFieldBoss`
  for a covered field boss. `CompanionBossRuns` answers: a companion (it follows its owner) only once a player of its
  party is in the boss's damage record; anyone else never. The gate acts on candidates and at attack commit, so a
  refused boss is no primary and no secondary target.
- **Rest (decision 18).** `LivingWorldHooks.playerDied` hands the death map to `CompanionBossRuns.died`.
  Companion-ops notes a companion that died beside a covered boss (`CompanionFieldBossRest`).
  `CompanionFollowGuard.holdsOffFollow` keeps it off follow while it rests.
- **Door hold.** `AgentFollowMapSyncService.mayWarpInto` answers false for a `NO_FOLLOW_MAPS` map.

## Tests

Full suite on 694c570ec: 13,396 tests, 0 failures, 37 skipped (10:51 min).

`CompanionFieldBossTest` (engagement, citizens, rest, follow guard), `AgentBossTargetGateTest` (the gate asks the
host), `AgentBossScopeTest` (the guard by key), `AgentBossRulesCatalogTest` (groups), `FollowInstanceGuardTest` (door
hold), `CompanionBossKeysTest` (keys).

## GM-first live check (mechanics run)

Crew A on its Lv160 rung, from the plan.

**Before you start:** a restart disconnects everyone online.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to `"easy_balrog,field_victoria"`.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!warp 105090900` for Jr. Balrog.
4. Watch the companions. They must not attack Jr. Balrog yet.
5. Hit Jr. Balrog once with Bossfriend. Check that the companions join only after that.
6. Check the mirror summary line for Jr. Balrog's attacks on companions.
7. Run `!botcfg physicsstatus`. Check that it shows no acquisition.
8. After the kill, check that companions leave the respawned Jr. Balrog alone until Bossfriend hits it.
9. Run `!hpmp Sorrel 0`. Check that Sorrel rests in Sleepywood (105040300) until full, then follows.

Memory gate stage and the crew B balance run: as in the plan, section P13.

### Live result 2026-10-12 22:29-22:58 (int/p9ay + a temporary `easy_balrog,field_victoria,field_ossyria,field_nihal`)

Partial, stopped early. Run on crew B at Lv100 (ohboy gm 0), with crew A for step 9. 0 ERROR lines.

- Jr. Balrog, steps 4, 5 and 8: PASS. The companions stood beside it and left it at 100% until ohboy's first hit,
  then killed it. They left the respawn (about 10 s later) at 100% for 50 s, until ohboy hit it again.
- Step 6: partial. The field mirror counted 1 Jr. Balrog attack and applied 0 (the companions kill it in seconds).
- Step 7: `!botcfg physicsstatus` showed 0 handoffs and 0 releases.
- Step 9 (with Sorrel of crew A): PASS. He rested in Sleepywood and rejoined at the 2-minute cap (1838/3406 HP).
- Finding 1, Snack Bar: a player's hits land on the 5,000-HP stand 8220008, which revives as the boss 8220009. The boss
  is a new monster with no player damage, so the companions stood beside it for 2+ minutes and never attacked. Snack
  Bar attacks first, and it killed ohboy. The engagement rule must carry a player's hit over to the revived form.
- Finding 2: ohboy has "Keep my crew fresh" on. When Brannock died beside Jr. Balrog, it benched him at once
  ("despawned: benched") and swapped Garrick in from the bench, so the P13 rest never ran for him.
- Both findings are fixed on feat/companion-bossing-p13-fixes (b53e22af6): a revived form keeps who hit the forms it
  revived from, and Keep my crew fresh leaves a companion alone while its field-boss rest runs.
- Not run: Eliza, Snowman and Kimera, and the crew B balance runs.

### Live result 2026-10-13 05:35-06:09 (int/p9az f510dca73, the two fixes, same temporary keys)

Crew B at Lv100 (ohboy gm 0). 0 ERROR lines. Keys reverted at 06:10.

- Snack Bar: PASS. ohboy broke the stand; the companions then killed the revived boss with him.
- Eliza: PASS. She stayed at 100% for about 3 minutes beside the companions, then died seconds after ohboy's throws.
- Kimera: PASS. She stayed at 100% for about a minute beside them, then died within a minute of ohboy's hit.
- Snowman: the rule before the hit PASS (100% for about 3.5 minutes). After ohboy's hit no companion dealt damage
  for 4+ minutes. Snowman's stun (mob skill 123) hit all five at 05:54:41 ("Boss disabled ... STUN"); three then
  stood still at the map's left edge (-360,274) and two at the foot of a rope (468,231) until ohboy left the map, when
  all five followed him at once. The field mirror worked there (applied=16, the stun reached Corvin and Fenna
  again). Cause not found; first suspects: AgentBossDisabledState holding the tick while a stun lasts (a stun that
  does not wear off), and a STUN on a rope (not grounded) calling stop() every tick. Open.
- Keep my crew fresh: no death this time, so the fix was not exercised live (unit test only).

### Snowman fix and live result 2026-10-13 07:19-07:21 (int/p9ba 5c13f07e9, key field_ossyria only)

Cause: the stun did wear off (the disease expiry task runs for Agents), but AgentBossDisabledState held a stunned
member with a stop order every tick. A stop leaves follow mode and forgets the follow target. In a run the fight gives
orders again once the state ends; beside a field boss nothing does, so the members stood still until ohboy changed
maps. A banish went through the same stop.

Fix (385db240b): the member records whom it followed when its first stun, seduce or confuse began and follows them
again (or its configured target) once none is left, logging "recovered ... following again". A banish now only drops
the path and keeps the mode. Two new tests fail on the old code. Suite on int/p9ba 13,671 / 0 / 37.

- Snowman: PASS. His stun hit two companions at 07:19:54; both recovered at 07:19:57 and followed again. ohboy threw
  at him about 07:20:00 and he died by 07:20:12. Snowman has 120,000 HP and ohboy's five throws deal at most about
  20,000, so the companions did the rest. 0 ERROR lines. Key reverted, server back on the committed config at 07:22.

## Risks

- Jr. Balrog (MAD 605) can one-shot a Lv40 companion once mirrored.
- Other humans may hold control of the boss. The mirror works for any controller.
- The door hold ships on. A companion that used to follow its owner into a Demon's Doorway map now waits outside.

## On by default (2026-10-19)

Every field group ships on (user: turn on what was built). Two guards came with it, because 20 of the field bosses'
spawn maps (scripts/event/AreaBoss*.js) are citizen training fields and the bosses respawn every 3 hours and mostly
stay up. A field boss attacks only while a human client drives it (AgentBossScope.engaged), so both guards matter
while a player fights one:

- **A companion sits a fight out** (`living_world.COMPANION_FIELD_BOSS_SIT_OUT_DEATHS`, 2): after that many deaths
  beside a covered boss on one map it says so once in party chat and waits in town while the boss stands there and
  its owner stays on that map, at most `COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS` (30 min) from its first death there.
  Before, an owner fighting a boss far above their companions' level had them die, rest, walk back and die again.
- **A citizen trains elsewhere** (`population.FIELD_BOSS_AVOID_MS`, 1 h): a citizen that died beside a covered boss
  re-plans at the next sweep and the field picker leaves that map out while the boss stands there.

Tests: `CompanionFieldBossTest` (sit-out, its line, the owner leaving, the boss falling, the kill switch) and
`CitizenFieldBossAvoidTest`.

### Live 2026-10-19 (live tree 6e5b96928, then 89d38f027)

Owner Bossfriend (GM6, Lv202 Hero) with Thane, Isolde, Ingrid, Sorrel and Flint (Lv160), allies Gunnar, Magna, Orrin
and Tamsin. GM queue for warps, spawns and heals. 0 ERROR lines at each boot.

- **Zakum (session 4, 21:17-21:41).** Sign-up with allies, the Eye drop, all eight arms, the first body (66M) and the
  second (88M) went down; the third body (110M) was being fought when the owner died (Zakum's HP-to-1 skill while my
  GM heal script missed a beat). With no human left every companion left and the allies went home, as designed. Not
  cleared. Two allies died mid-fight with no HP potion left: Gunnar arrived with 17,047 mesos and no potion and bought
  nothing at the El Nath store, though he carried about 3 M mesos of loot. Fix d9cfd9b9a: a boss ally's arrival
  provisioning sells its trash equips and the ETC no quest wants before it buys, as a walk-in readiness trip does.
  Gunnar waited in El Nath after his death (PASS).
- **Neo City (sessions 5-7).** Aufheben cleared in 10 s, Oberon in 2 s, Nibelung in 8 s; five companions inside each
  time, all left with the owner and said "gg". Entering: the gate maps are crowded and the knockback pushes the
  leader off the portal, so `!killall`, `!stepto <portal>`, then up.
- **Field groups.** Leafre: Manon engaged once a party player had damaged it (Power Guard's reflect counts); the field
  mirror applied its hits to the companions and they took it from 96.9% to 21.5% in about 90 s. Ludus: Zeno spawned and
  was hit by the companions. Mu Lung: King Sage Cat spawned and was gone within 4 s. The sit-out and the citizens'
  avoidance were not provoked live (unit tests only).
- **Finding, not new (P20):** in Leafre a Lv160 crew restocks to Pink Bean's 1500-potion standard. Isolde spent 5.0 M
  mesos there; Ingrid could not afford it and stayed put.

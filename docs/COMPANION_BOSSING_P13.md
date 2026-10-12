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

## Risks

- Jr. Balrog (MAD 605) can one-shot a Lv40 companion once mirrored.
- Other humans may hold control of the boss. The mirror works for any controller.
- The door hold ships on. A companion that used to follow its owner into a Demon's Doorway map now waits outside.

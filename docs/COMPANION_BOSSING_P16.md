# P16: High-level field bosses

> Built 2026-10-12 on branch `feat/companion-bossing-p16`, on top of P15. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P16. Not deployed, not live-tested yet. Every P16 key ships off.

## Result

Companions now fight 19 more field bosses beside their owner, in five groups with one key each. The P13 rules apply
to them: the boss stays with the player's client, its attacks reach every Agent in range, a companion joins only after
a player of its party has hit that boss, citizens never attack it, and a companion that dies beside it rests in town.
Two new rules come with P16: a group's add (Pianus's Bloody Boom) is fair game once the party fights the group's boss
there, and an Agent puts its buffs back up at once after a mob dispels it.

## What the player sees (with a key on)

Example: `field_pianus` on, the owner at Pianus (230040420) with companions.

- The companions wait until the owner hits Pianus, then fight beside them.
- Pianus's Bloody Booms are fair game for the companions as soon as the owner has hit Pianus. Seruf, a boss of its
  own, still waits for a player's first hit.
- A Bloody Boom that blows up near a companion hurts it (the P12 blast step).
- A companion that loses its buffs to a dispel casts them again on its next buff tick.
- Lilynouch's reflect stops the companions' attacks (P12 G4). Lyka's seduce and Dodo's banish move them as they move
  a player (P12 G5).

## Keys

| Key | Bosses |
|---|---|
| `field_pianus` | Pianus L 8510000, Pianus R 8520000, Seruf 4220000 and 4220001, Bloody Boom 8510100 (an add) |
| `field_leafre` | Leviathan 8220003, Manon 8180000, Griffey 8180001 |
| `field_temple` | Dodo 8220004, Lilynouch 8220005, Lyka 8220006 |
| `field_zipangu` | Black Crow 9400014, Blue Mushmom 9400205, Bamboo Warrior 6090002, Male Boss 9400120 and 9400122, Female Boss 9400121 |
| `field_masteria` | Headless Horseman 9400549, Bigfoot 9400575 |

Every id matches its WZ name and carries the boss flag.

## Changes from the plan

1. **No `BOSS_IDS` entries.** As in P13, each boss is guarded by its group's key (boss-rules.json `fieldBosses`), so
   nothing changes while every key is off. The plan listed them in `BOSS_IDS`, which is always on.
2. **Adds of a group.** P13's rule makes a companion wait for a player's hit on that very boss. For Bloody Boom,
   which Pianus summons over and over, that would leave every Boom to the owner. A group's `adds` are fair game once
   a party player has hit one of the group's own bosses on the same map, while it lives.
3. **Rebuff after a dispel holds everywhere.** The plan lists it as a P16 rule. It is a fix to how every Agent
   tracks its buffs, so it also acts where Zakum's arms dispel.
4. **Drink at once after a deadly hit needed no change.** A deadly hit sets HP and MP to 1 through
   `Character.addMPHPAndTriggerAutopot`, which fires the Agent's autopot on that very hit.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts the five keys.

boss-rules.json: five group rows; `field_pianus` lists Bloody Boom under `adds`.

No new tuning key and no database change.

## How it is built

- **Groups.** Five rows in boss-rules.json, five keys in `CompanionBossKeys`. `AgentBossField`, the mirror's field
  scope, the client-load floor, `AgentBossTargetGate` (G4 and G14) and `CompanionFieldBossRest` read them as they read
  P13's groups.
- **Adds.** `CompanionBossRuns.mayEngageFieldBoss`: when the monster is its group's add, a live boss of the same group
  on its map that a party player has hit is enough (`groupBossHitBy`).
- **Dispel.** `Character.dispel` calls `AgentPresence.dispelled`. For an Agent, `AgentCombatBuffRuntime.dispelled`
  marks it. Its next buff tick calls `AgentCombatBuffStateRuntime.requestRebuff`, which makes every buff skill due now.

## Tests

`AgentBossRulesCatalogTest` (each P16 id in its group, Bloody Boom an add), `CompanionFieldBossTest` (Bloody Boom fair
game once Ann has hit Pianus there, not after Pianus falls; Seruf still waits for her hit), `AgentCombatBuffDispelTest`
(a dispelled Agent's buffs are due once, another Agent's timers stay), `CompanionBossKeysTest` (the keys).

Full suite on 45314d552: 13,435 tests, 0 failures, 37 skipped (12:39 min).

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `field_pianus`, `field_leafre`, `field_temple`, `field_zipangu` and
   `field_masteria`.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!warp 230040420`.
4. Hit Pianus with the owner first. Check that the companions join only then.
5. Watch the companions take the deadly attack1 and drink.
6. Check that the companions also hit the Bloody Booms.
7. Check the blast log when a Bloody Boom self-destructs near a companion.
8. Wait for a dispel. Check that the companions cast their buffs again within a few seconds.
9. Run `!debuff SEDUCE <companion>`. Check that it stops attacking and walks as a seduced player does.
10. Run `!warp 270010500` for Dodo, and hit Dodo once with the owner.
11. Wait for a banish, or run `!debuff BANISH <companion>`. Check that it plans again from the spawn point.

Memory gate stage (field boss with many bots), from the plan: on Leviathan's Canyon 240040401, where citizens farm,
run `!spawn 8220003` if Leviathan is not there, hit it with the owner while citizens and crew A fight beside it, and
apply the client memory gate's stop rule.

## Live result 2026-10-13 16:55-17:20 (int/p9bc 169d48793, temporary keys: the five P16 groups)

Pianus only, stopped early by a server deadlock (below). Owner ohboy (Lv103 Hermit, gm 0) with crew B (Lv100-101,
60% gear). Bossfriend (hidden) warped ohboy in, healed him through the GM queue and gave the debuffs; his own crew
followed him into the cave. Keys reverted, committed config live at 17:23:53. Video (14 min, ohboy's view):
`docs/agents/evidence/video/p16-pianus-dodo-2026-10-13.mp4` (local, not in git).

- Step 4: PASS. Pianus R stayed at 100% for about 4 minutes with companions beside it, then they joined after
  ohboy's first throw at 17:07:17 (98.8%, then 93.4% at 17:10:50 and 84.5% at about 17:15).
- Step 5: FINDING. Pianus's deadly attack1 covers the whole middle of the cave from both sides. Garrick and Corvin
  showed HP 1 several times; Corvin stayed at 1 for over half a second without drinking (1 -> 11 by regen) and died
  on the next hit. Every companion died within 3-5 s of each return from its town rest, so they barely fought. The
  instant drink needs the auto-HP key (91), which `AgentPotionService.setupAutopotForBot` refreshes only every
  `POT_CHECK_INTERVAL_MS` (45 s) and clears when the bag has no HP potion; why Corvin had no drink 6 min after a GM
  gift of 300 Unagi is not known.
- Step 6: not observed: the companions were never alive long enough beside the Bloody Booms.
- Step 7: PASS for this build. One blast at 17:16:38: `Boss mirror blast OBSERVE ... mob=8510100 origin=33,166
  agents=[840, 1010] humans=[2]`. `BLAST_MODE` ships `OBSERVE`, so it logged and hurt nobody.
- Step 8: PASS. Pianus's dispel (skill 127): Corvin lost 6 buffs at 17:12:08 and cast his own again in 0.5-3.2 s, and
  again at 17:12:57 (0.6-1.1 s). Ilse's Holy Symbol came back on three members in 5.9 s.
- Step 9: PASS. `!debuff SEDUCE Garrick` at 17:10:20: "Boss disabled ... state=SEDUCE", he wandered, and at 17:10:31
  "recovered ...; following again".
- Death and rest (P13 rule): every death beside Pianus revived in Aquarium (230000000) at 50 HP and came back after
  its rest. Working, but at this level it is a loop.
- Mirror: about 400 hits applied. Of about 250 calibration misses, about 210 were Bossfriend's: hidden characters are
  skipped by design but his client still reports hits. ohboy had 41.
- Steps 10-11 (Dodo, banish) and the Leviathan memory gate: not run.
- Server deadlock at about 17:18: an Agent's level-up took prtLock under its stat write lock while a party HP update
  held prtLock and read HP. All four TimerManager-Core threads stopped and the admin port froze; the restart had to
  kill java after 120 s (no shutdown save). Fixed in 271126c5f (`StatEffect.isActive` reads the party only for a party
  card buff). Thread dumps: `docs/agents/evidence/deadlock-2026-10-13/` (local).

### Retest 2026-10-13 20:04-20:31 (int/p9bf ecc02f127, crew at Lv130 4th job, same temporary keys)

User decision (2026-10-13): level the bots for what they do; at a boss they should overpower it with big numbers but
not melt it in seconds. ohboy and crew B were set to Lv130 (`!setlevel party 130`, then each to its 4th job: Brannock
and Garrick Hero, Ilse Bishop, Fenna Bowmaster, Corvin Dark Knight; ohboy Night Lord) and given the Lv130
`!bossgear` kit; Bossfriend gave each companion 200 Power Elixirs. Before this run, aa5a7e240 made 4th-job Agents
learn their instructor's skills (they had none). 0 ERROR lines. Video (26 min):
`docs/agents/evidence/video/p16-pianus-lv130-2026-10-13.mp4` (local, not in git).

- Step 4: PASS. Pianus R untouched for about 70 s with all five beside it; after ohboy's throw at 20:05:31 it fell
  to 88% in 35 s and died between 20:15:45 and 20:16:47 (24M HP in about 10.5 min, Pianus healing itself; hits of
  10-18k). Pianus L then stayed at 100% for minutes beside them (never hit by ohboy).
- Step 5: PASS. No companion was seen at 1 HP (lowest HP in 2 min of 4 Hz samples: Ilse 3,171) and one death in the
  whole run (Ilse, 20:19:06, rested and came back). Corvin drank about 15 Power Elixirs and 43 Ice Cream Pops in
  4.5 min ("pot debug": 192 HP / 583 MP pots, from 250 / 598). The run-1 case of Corvin at 1 HP with no drink was
  not reproduced; the likeliest cause is that he had run out of HP potions (he asked the party for some at 17:05).
- Step 6: PASS. Three Bloody Booms blew up beside Ilse, Brannock and Corvin at 20:08:38-45 while ohboy was idle, so
  the companions' hits set them off.
- Step 7: PASS. Each blast logged `Boss mirror blast OBSERVE ... mob=8510100 ... agents=[338, 9, 878]`.
- Step 8: PASS. After a dispel at about 20:07:37 they cast their buffs again in 4-8 s, 4th-job ones among them
  (Garrick's Power Stance 1121002, Corvin's Beholder 1321007).
- Step 10: PASS. A Dodo spawned beside ohboy (`!spawn 8220004`) stayed at 100% for about 4.5 min while the crew
  fought the temple guards around it; ohboy's jump-throw from the top step (y -305) hit it at about 20:25:45 and the
  companions joined at once (12-19k hits). Then Dodo's banish ("You have been ousted by the force of Dodo") sent ohboy
  to the floor below and the crew followed him off it: 88% when the check ended.
- Step 11: PASS. `!debuff BANISH Garrick` at 20:30:11: "Boss disabled: member=835 banished on map=270010500 at=248,-432;
  planning again", then he walked back down to ohboy.
- Client memory: ohboy's client at 3,997 MB virtual of 4,096 at the end (as in the Balrog runs).
- Leviathan memory gate: not run.

Findings: field boss groups have no potion standard (`BOSS_STANDARDS` lists run bosses only), so companions come with
their everyday stock (Corvin: 50 HP potions before the gift); Fenna said "anyone have HP pots? running low" at Dodo.
Melee companions cannot reach Dodo where it hovers (about 220 px above the floor).

### Follow-ups built 2026-10-13 (user decisions after the retest)

- **Field boss potion standard** (`feat/field-boss-potion-standards`, 87088a3c4). `living_world.COMPANION_FIELD_BOSS_TOWNS`
  lists each group's towns: Aquarium (`field_pianus`), Leafre (`field_leafre`, `field_temple`), Mushroom Shrine and
  Showa Town (`field_zipangu`), New Leaf City (`field_masteria`). A companion out with its owner there keeps the group's
  `BOSS_STANDARDS` count while the switch lists the group and its level meets the group's new `minLevel`
  (boss-rules.json: 100, 95, 110, 85, 95). Counts: `field_pianus` 500/300, `field_leafre` 300/200, `field_temple`
  500/300, `field_zipangu` 500/300, `field_masteria` 400/300. Those stores sell nothing stronger than about 1,000 HP a
  potion. The owner walking into such a town nudges each short companion at once, as Mu Young's room does.
- **Mastery books** (`feat/agent-mastery-books`, 67161964a + af4990752). An Agent reads the skill (228xxxx) and mastery
  (229xxxx) books for its job that it carries, one every 10 s, as `SkillBookHandler` does for a player: the book is used
  up, its success roll decides, and SP then flows into the raised master level. Everyone on the map sees the effect;
  each read logs `Agent <name> read book <id>: skill <id> master <from> -> <to> (learned|the book failed)`.
- **Stronger kits** (`tune/bossgear-ten-percent`, 5ba042a56). `!bossgear` now puts a passed 10% scroll in every
  upgrade slot (60% on a piece no 10% scroll fits), so a kit weapon gains +5 attack a slot instead of +2.

## Risks

- Death loops at owner levels under 100.
- Mirrored multi-region hits stack, because no hit cooldown follows a server hit. P8's shared 1.5 s hit window covers
  mirrored hits.
- Unconfirmed: whether companions keep up on the Temple of Time lanes. They follow their owner in (decision 21).

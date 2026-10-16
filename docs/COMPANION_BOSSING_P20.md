# P20: Pink Bean

> Built 2026-10-12 on branch `feat/companion-bossing-p20`, on top of P19. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P20. The statue fix (decision 15) acts for every player as soon as it is deployed. **Live check CLEARED
> 2026-10-16 04:23** on the seventeenth run, 69 minutes, six companions, 4 of the 5 deaths (see "Live runs" below).
> **The `pinkbean` key is on since 2026-10-16** (user; 18d5b66e0), bench allies too (b00fc73cc). Second owners stay
> off (`OWNERS_MAX` 1).

## Result

An owner can now sign their companions up for Pink Bean at the Forgotten Temple Manager, as at Adobis for Zakum. A
companion that dies revives inside, as a player does, and walks back into the fight. Ranged and magic companions keep
out of Solomon's and Rex's deadly boxes. P20 also fixes the statue reactor for every player: Pink Bean now wakes even
when the event's first player lies dead on another map. With `pinkbean` off, the Manager's window and the fight are
stock.

## Live runs (2026-10-15 to 2026-10-16)

Two owners on two clients: Bossfriend (Hero, hidden after the start, leads) and ohboy (Night Lord, parked at x≈0 and
kept alive by a GM heal loop). Their companions are Lv 200 in boss kits. Stage 3 (`OWNERS_MAX` 2) ran from run 1 on.
PinkBeanBattle.js ends the expedition at the fifth death, and companion deaths count. Videos are in
docs/agents/evidence/video/p20-pinkbean-run<N>-2026-10-15.mp4 (runs 15-17: -2026-10-16.mp4; run 17 is the clear).

| Run | Lineup | Damage | End |
|---|---|---|---|
| 1 | 2 owners, 10 companions | ~16.5M HP/min | Stopped: far too slow for 7.65B HP in 140 min |
| 2 | 2 owners, 10 companions + 1 ally, 400% | Wave 1 in ~6 min (~80M/min) | Wiped at 5 deaths: Tamsin ×4 (out of potions), Ilse ×1 |
| 3 | 2 owners, 10 companions + Gunnar (ally) | Waves 1-2 fast | Wiped at 5 deaths, 14:24: Gunnar ×4 with 0 HP potions, Magna ×1 |
| 4 | 2 owners, 10 companions, no ally | 37-60M/min in waves 1-3, ~20M/min in wave 4 | 4 deaths by 15:39 (Magna ×2, Brannock, Corvin), stuck in wave 4; ended by the 15:54 restart |
| 5 | 2 owners, 9 companions (Magna benched), 51d397149, 600% | Wave 1 in ~5 min (~85M/min), no Hero at the wall | Wiped at 16:17:54 in wave 2: Ingrid (Dark Knight) ×5 |
| 6 | same, 3d1e6c741 (attackKeepOut) | ~110M/min | Wiped at 17:00:26 in wave 3: Corvin (Dark Knight) ×3, Thane, Ilse |
| 7 | same, 0fc843f01 (Roar rule, 1000%) | ~180M/min, wave 1 in ~2.5 min | Wiped at 17:34:01 in wave 3: Corvin ×3, Ingrid, Brannock |
| 8 | same, 4005ae51b (Sacrifice, Rex first) | Waves 1-2 in ~10 min | 16 min without a death; then Thane, Ilse and Fenna in 23 s under Hugin; ended by the 18:48 restart |
| 9 | 7 companions (both Bishops benched), 1290bfcc8 (guardian keep-out) | Wave 1 ~3 min, wave 2 ~4 min | Wiped at 19:19:27 in wave 3 (Hugin 33%): Brannock, Fenna, Ingrid ×3 dry |
| 10 | same, restocked at Leafre first | (3 min) | Wiped at 19:38:11 in wave 1: Ingrid ×5, out of HP potions after 3 minutes |
| 11 | same, e139e60ee (potion floor, dry hold), Reindeer Milk for all | Waves 1-3 in 30 min; wave 4 at ~29M/min | Wiped at 20:56:38 in wave 4 (Solomon 55%, Rex 4%, Hugin 90%, Munin 35%): Brannock ×3, Thane, Fenna; no one ran dry |
| 12 | same, e90dbf9a7 (2000%, Hero's Will, 900/600 potions) | Waves 1-4 in 25 min; wave 5 ~75M/min, then ~20M/min | 3 deaths (all Brannock, owner HP setting 40%); ended by the 22:09 restart in wave 5 (Solomon 27%, Rex 37%, Hugin 87%, Munin 93%, Ariel 46%) |
| 13 | same, 584a9c491 (touch gaps), Brannock at 70% | Waves 1-4 in 23 min, 0 deaths until 31 min; Hugin hit from the ledge from wave 3's first minute | Wiped at 23:12:49 in wave 5 with only Hugin left (8%): Orrin ×2, Brannock, Thane, Fenna, all during Munin's 26-minute seduce storm (48 seduces in 7 min) |
| 14 | same, 73ec3fadb (Munin first) | Waves 1-4 in 25 min, wave 5 in 19 min; Pink Bean from 00:15 at ~190M/min | Wiped at 00:19:15 on Pink Bean himself (~75%): Fenna ×4, Garrick; the first run to reach him |
| 15 | same, 91ad957c8 (Pink Bean in attackKeepOut), 900 potions each | Waves 1-4 in 26 min, wave 5 in 19 min; Pink Bean from 01:33 at ~6%/min | Wiped at 01:37:44 on Pink Bean (~67%): Brannock, Garrick (one burst at 01:02), Fenna ×2 (01:34, in the barrage), Orrin dry (0 of 900 HP potions) |
| 16 | 43803f8b0 (barrage keep-out at 65%, 1500 potions), Orrin benched: 6 companions | Waves 1-4 in 25 min, wave 5 in 19 min; Pink Bean from 02:45 at ~7%/min | Wiped at 02:51:20 on Pink Bean (~63%): Brannock, Thane (Solomon's deadly stun, then seduce), Corvin, Fenna ×2 (held back, killed by the adds) |
| 17 | 18b6bf908 (last stand), same 6 | Waves 1-4 in 28 min, wave 5 in 26 min; Pink Bean from 04:07, 98% -> 0 in 16 min | **CLEARED 04:23:41** (69 min): Brannock, Fenna, Garrick, then Fenna at 04:10:50 (the fourth); she waited out the last stand and the five warriors took him from 81% |

Runs 5-7 had 15 deaths, 11 of them Dark Knights. Two DK-only HP costs, both made worse by the boss scale:

- **Dragon Roar (9aeec7a2e):** it costs 20% of max HP (`StatEffect`: `hpR = -x/100`) and the old rule allowed it above
  half HP, so a roar from 51% left ~31% for Solomon's next hit. A roar is now refused unless HP minus its cost stays
  above the Agent's autopot HP alert (every Dark Knight Agent, not only in bosses).
- **Sacrifice (1c0d55a4d):** it costs x% (5%) of its first line in HP (`CloseRangeDamageHandler`), and the line was
  scaled by `companionDamagePercent` first, so each cast cost 30-50k HP and `safeAddHP` left the knight at 1 HP with
  no autopot. Sacrifice's lines now stay as made.

Also from run 6: Rex's attack2 covers the whole floor (x -1200..900 from his origin), so while the party fought Solomon
on the left it took Solomon's band and Rex's floor hit at once (~33k against a 30,000 Hyper Body). Rex now comes
before Solomon in `targetPriority` (a5c81c851): the party fights Rex on the right, out of Solomon's reach. Damage went
to 1000%: deaths grow with time in the fight, and Rex's floor hit cannot be dodged.

Run 5's five deaths were one loop: Ingrid revived inside without Hyper Body (buffs=1-3, 19,776 HP), walked back into
Solomon's stun band (x -765..-820) and died again, every 1.5-2 minutes. Solomon and Rex now sit in Pink Bean's
`attackKeepOut` at the global 80%: a member whose max HP one ~16,600 hit would take 80% of keeps out of their attack
boxes until it is buffed again; with Hyper Body (30,000) it fights inside.

Runs 9 and 10 were lost to potions, not to the fight:

- **Run 9:** Ingrid went in with 167 HP potions (the others had 283-830). The owners had been GM-warped straight to the
  Temple Manager, skipping Leafre, Pink Bean's approach store (no shop is in reach of 270050000). She drank them all by
  19:18, then revived dry and walked back in three times in 46 seconds: deaths 3, 4 and 5. From run 10 the owners go
  through Leafre first, where every companion short of 600 tops up.
- **Run 10:** at Leafre Ingrid bought 556 **White Potions** (300 HP each), the store's cheapest, since Pink Bean had no
  potion floor; she drank all 600 HP potions in under three minutes at a 19,776 maximum and died five times. Horntail
  already had `BOSS_POTION_FLOOR_BPS` `horntail=1500/0` for exactly this (P18: she carried 359 White Potions there).
  **7425b4413:** `pinkbean=1500/0`, so only potions that heal 15% of maximum HP count (Reindeer Milk, 5,000 HP).
- **e139e60ee:** in a run whose deaths count (REVIVE_INSIDE), a member revived with no HP potion waits in 270050200
  instead of walking back through in00, says so once (`boss.dry`), and walks back in once it holds a potion again.

Run 11 was the first lost to the fight itself, 57 minutes in. Damage falls with every part on the field: ~120M/min
with one (wave 1), ~86M with two, ~52M with three, ~29M with four, against 2.1B in wave 5 and Pink Bean's 2.1B after
it. The birds wait: Hugin (wave 3) stayed at 98-88% until Solomon died, then fell in 9 minutes; Munin, the seducer,
stayed above 90% for the first 9 minutes of wave 4 (37 seduces in 4 minutes). Fenna stood at x≈-190, the edge of
Solomon's fixed keep-out, with 0-4 attacks a minute. Reindeer Milk lasted: the heaviest drinkers ended at 60-122 of
600 (about 10 a minute, ~50k HP a minute taken).

Run 12 (2000%, Hero's Will on seduce and confuse) took waves 1-4 in 25 minutes, against run 11's 57 minutes for three
and a half. Two things held it in wave 5:

- **The birds were walled off (443f4632d).** Every part spawns at the statue (5,-43); the birds' bodies sit 97 px or
  more above the floor, out of reach but from the side ledges (left y=-99, right y=-102). The touch band joined every
  deadly touch on a floor into one span, so Solomon's touch and Hugin's (220 px apart) walled off the left ledge, Rex's
  and Munin's the right one: members stood beside Solomon or Rex, and Munin, the seducer, lived (35 seduces in 5
  minutes in wave 5). Areas now join only where their margins overlap, and a member keeps to the gap it stands in.
- **Brannock drank at 40%.** ohboy's companion card had him drink below 40% (`{"hp":40}`); with the compulsory
  autopot he parked at 43-57% HP, one guardian hit from death, and died three times. Set back to 70% on his card
  (Supplies & loot) before run 13. guardHp never raised it: the guardians' non-deadly hits (~6.6-7.7k) stay under
  his alert, and the deadly ones (HP to 1) are left out on purpose.

Run 13 confirmed the touch gaps: Hugin fell 95% -> 63% in wave 3's first minute with Solomon alive, and wave 4 (Munin
killed first there) had no deaths. Wave 5 put Ariel first ("the healer"), but in this WZ data Ariel casts only
Undead (133); Munin lived 26 minutes and seduced the party into the deaths. **73ec3fadb:** Munin first, then Ariel.

Run 14 was the first to reach Pink Bean himself (00:15, 44 minutes in, 3 deaths) and took him 96% -> 78% in three
minutes. Fenna, a Bowmaster with 8,996 HP (the warriors have 19,776, about 31,600 with Hyper Body), died at his feet
twice in two minutes. His attack1 strikes nine areas across x -400..400 for about 11,900 each (MADamage 1,200:
1,200² × 0.008-0.0085), more than she has; attack4 covers the whole floor but is deadly (HP to 1), left to the
potions. **91ad957c8:** Pink Bean in his own `attackKeepOut`: a member one barrage hit would take 80% of keeps outside
it, 521 px from his origin, from where a Bowmaster's Hurricane (400 + Eye of Amazon 120) still reaches his body (484
px). Warriors take 38% and fight on at his feet. Fenna had also revived with 4 buffs against 8-10 for the others: no
Hyper Body until Corvin recasts.

Run 15 reached Pink Bean at 01:33 (45 minutes, 2 deaths) and took him to ~67%. Two things ended it:

- **Fenna still stood in the barrage.** With Hyper Body she had about 14,400 HP, and the barrage (about 11,500 after
  her magic defense) was 80% of it, not over the global 80; stunned twice by it, she died at 01:34:20 and 01:34:58.
  **186545c59:** `attackKeepOutHpPercent` per boss (0 = `keepOutHpPercent`), Pink Bean 65. A warrior without Hyper
  Body (19,776) takes 58-60% and still fights at his feet; the touch keep-out stays at 25. The other parts' attacks
  barely move at 65: Solomon's and Rex's floor-wide attack2 are magic (MAD 770/750, about 4,700-4,900), Hugin's and
  Munin's about 6,700-7,400, and Ariel's 13,900 only within 44 px of her.
- **Orrin ran dry.** The Paladin drank all 900 Reindeer Milks in 49 minutes, about 18 a minute against 6-10 for the
  others, and died with none at 01:37:44. **43803f8b0:** `BOSS_STANDARDS pinkbean=1500/600`. Orrin also hit for little
  (about 165k an attack against Brannock's 1.4M; 1-29 attacks a minute): at Lv200 his Blast is level 10 of 30 and
  Holy Charge 10, and Ariel is immune to Holy (H1), Pink Bean strong against it (H2). Run 16 benches him.

Run 16 (Orrin benched, 1500 potions, barrage keep-out at 65%) reached Pink Bean at 02:45 with 3 deaths and took him to
~63%. The keep-out held Fenna 460-570 px back from the melee at his feet, but from there she landed 0-15 attacks a
minute, and his adds (8820007: magic MAD 800, about 5,300 a hit over x -300..+50) came to her alone: she died at
02:50:04, the fourth death, walked back in and died again at 02:51:20. Every run from 14 to 16 ended the same way, a
member walking back in after the fourth death. **ed8a648e6:** a boss row names its last stand (`lastStand`: Pink Bean
`fallenPlayers`, 4, where PinkBeanBattle.js says "last stand mode everyone!"); from then on a member that revives on
a stage map waits there for the rest of the run (`boss.laststand`), and the living finish the fight.

**Run 17 cleared Pink Bean** at 04:23:41, 69 minutes after the start, with Ingrid, Thane, Brannock, Corvin, Fenna and
Garrick (Orrin benched) and the two owners. Wave 5 was the slow one (26 minutes; Hugin and Ariel stayed above 85% for
its first 15). Pink Bean himself appeared at 04:07 with three deaths spent. Fenna died at 04:10:50, the fourth, revived
and held: "Boss run member holds for the last stand: ... fallen=4", and she said "can't risk another fall now. waiting
here, you've got this." The five warriors took him from 81% to 0 in 13 minutes, about 7% a minute, with no further
death. Everyone carried 1,500 HP potions; the four who died still held 961-1,397 each, so none of the deaths was a dry
one.

What the runs found, and what changed:

- **321b42812 (after run 1):** reach fix (the keep-target check aims at a point on the body), `companionDamagePercent`
  per boss (Pink Bean 400, its six parts only), and `atk=`/`dmg=` per member per minute in the `Boss fight status`
  line.
- **2H swords had 0.1 mastery (28f627ce9).** `CombatFormulaProvider.resolveWeaponType` had no case for 140, so every
  2H sword (the boss kits' Timeless Nibleheim) fell to NOT_A_WEAPON and kept the 0.1 mastery floor instead of Sword
  Mastery's 0.6: about a quarter less damage per line.
- **Heroes Shouted from the wall (51d397149).** Solomon stands at x=5, but his body is x -737..-618 and Rex's 633..751.
  Knockback pushed melee left until Brandish no longer reached; Shout (30% x1, ~1/20 of Brandish) still did, and
  nothing walked them back in while some skill reached. They ended at the map wall x=-899 (or x≈-427 / ≈445 coming
  from the middle) at 11-20k per attack. A close-range plan on a boss whose body sits apart from its origin now holds
  only within ATTACK_RANGE_X of the body. The cost: melee now stand inside Solomon's stun band.
- **Pink Bean 400% -> 600% (51d397149).** Run 4 averaged ~44M/min over waves 1-3 against 7.65B HP in total.
- **Paladins Magna and Tamsin dealt ~0.** Not a formula bug: `!bossgear` took their old kits off on 10-15 and then
  refused the re-wear ("needs 8 free Equip slots ... has 5/6"), so their own gear left them at ~95-110 accuracy against
  the 201 Solomon needs, at the 1% hit floor. Their Equip tabs are full of loot they cannot use. Both are benched for
  now; they need a sell trip, then `!bossgear <name> pinkbean`.
- **Allies buy the potions their approach store sells.** For Pink Bean that is Leafre (2080001), which has no Power
  Elixir: Gunnar bought 522 White Potions (300 HP each) and drank all 600 HP potions in 7 minutes against 16,600 hits.
  Filed as a separate task. Runs 4 and on use no ally.
- **A revived member comes back with few buffs** (buffs=1-4: no Hyper Body), so 19,776 HP against Solomon's ~16,600
  hits; Magna died twice that way in run 4.
- **Wave 4 is a seduce storm.** Munin (8820006) casts Seduce (128): 56 seduces in 5 minutes in run 4, and the members
  were dragged around and barely attacked. Hero's Will cures it but has a 6-10 minute cooldown; Dispel does not cure
  Seduce or Stun on this server (Character.java:3221). Rex casts dispel-buffs (127); Ariel casts Undead (133).
- **Ranged members do not fight.** The keep-out band holds them at x≈-165..-195, 420+ px from Solomon's body, out of
  reach of their skills. Orrin (Paladin, kitted) attacks only ~1-10 times a minute.

## What the player sees (with the key on)

Example: `pinkbean` on, the owner at the Temple Manager (270050000) with 5 companions.

- The Manager offers the owner "My party will register too." after they create or join the expedition, and "Call
  allies from my bench..." when `COMPANION_BOSS_ALLY_TYPES` also lists `pinkbean`. The expedition needs 6 or more, at
  Lv120 or more.
- Inside (270050100), the companions take each wave's guardians, the healer Ariel first, then Munin, Hugin, Solomon and
  Rex. They never hit the statues or the 1-HP statue.
- While Solomon or Rex is up, ranged and magic companions stay between their deadly boxes: they walk in first if they
  stand in one, then hold there while they fight. Melee companions fight as before.
- After the fifth wave, no companion picks up the item that drops by the reactor. Once Pink Bean wakes, the companions
  take it.
- A companion that dies revives on 270050200, as a player does, walks through the portal there and fights on. Every
  death counts toward the stock 5-death wipe and costs EXP (decision 13).
- After the clear (or before it, if the owner leaves), the companions leave by 2141002.
- On the way in, a short companion buys at the Leafre store when the owner passes Leafre, before the dragon ride. At
  270050000 it makes no shop trip.

## Keys and standards

| Key | Boss | Recruit map, fight maps | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `pinkbean` | Pink Bean | 270050000; 270050100, 270050200 | 600/600, 4800 |

600/600 with 4800 projectiles is the plan's starting standard: it fills the 18-stack budget. The scaled standard
(1200/1200, 10000) waits for Questions 5 and 6.

## The statue fix (decision 15, every player)

The reactor 2708000 kills the 1-HP statue 8820000 when the stage-5 item lands on it, and the statue's death wakes Pink
Bean. The script killed the statue on `rm.getMap()`, the map of the player the item drop names. That player is the
event's first player, who may lie dead on 270050200 or stand anywhere else in the event. The statue then stayed, and
Pink Bean never woke. The script now kills the statue on the reactor's own map (`rm.getReactor().getMap()`).

## Changes from the plan

1. **Target order.** The plan names the waves but no order. The healer Ariel comes first, then Munin (banish and
   seduce), Hugin (reflect), Solomon and Rex, then Pink Bean.
2. **The walk back is a stage map.** The plan says the member walks to the Pinkin portal at x -1156. P18's stage maps
   do this: 270050200 is Pink Bean's stage map, and `in00` (Pinkin.js) is its stage portal.
3. **Entry items.** P8 used the marker items for two jobs: the floor guard and the approach check ("is the owner on
   the way to this boss?"). Pink Bean's marker item only ever lies on its battle map, so with one list no owner would
   ever count as on the way, and no companion would buy Pink Bean's standard in Leafre. `boss-rules.json` gains
   `entryItems`, the items an owner carries in. It defaults to the marker items, so Zakum (the Eye) and Showa (the comb)
   are unchanged. Pink Bean names none.
4. **Keep-out boxes are data.** `boss-rules.json` gains `keepOut`; only Pink Bean lists boxes.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts `pinkbean`.

`agent-engine.yaml`:

- `AgentBossScope.EXPEDITION_TYPES` changes to `ZAKUM,SCARGA,HORNTAIL,SHOWA,PINKBEAN`.
- `pinkbean=600/600` joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `pinkbean=4800` joins `AMMO_STANDARDS`.
- New: `AgentBossPriorityFight.KEEP_OUT_MARGIN_PX: '30'`.

boss-rules.json: the `pinkbean` row gains `targetPriority` (8820002, 8820006, 8820005, 8820003, 8820004, 8820001),
`adds` 8820007, `entryItems` [], `stageMaps` 270050200, `stagePortal` in00 and `keepOut` (8820003 from -900 to -200,
8820004 from 200 to 900).

No database change.

## How it is built

- **The Manager's script.** It was Adobis's stock script with its own header and no leader item check. It takes the
  five P8 hooks with the key `pinkbean`. `TempleManagerHookScriptTest` checks that it stays Adobis's twin.
- **Run row.** `AgentBossRunCatalog.PINKBEAN`: expedition PINKBEAN, no join choices, the priority fight, 2141002 before
  and after the clear, owner leaving takes the party, `DeathMode.REVIVE_INSIDE`.
- **Entrance row.** `BossEntrance.PINKBEAN`: companions wait beside the Manager (-665,-41), approach store Leafre
  (store NPC 2080001 on 240000002), allies in and out by `out00`, trips out of the recruit map held.
- **Revive inside.** P12's death gate (`AgentBossDeathDepartureGate`) revives the member into the event's 270050200.
  There `AgentBossPriorityFight` sees a stage map with no monster and walks the member through `in00`.
- **Keep-out.** `AgentBossPriorityFight.keepOutBand`: for each live part with boxes, a box to its left bounds the band
  from below and a box to its right from above, `KEEP_OUT_MARGIN_PX` inside. A ranged or magic member outside the band
  walks to its nearest edge and does not fight that tick. Inside it, `setHorizontalBoundary` holds it, once per change.
  The boundary is cleared once no such part lives.
- **Entry items.** `AgentBossRules.entryItems`; `BossEntrance.approachMeantBy` and `CompanionBossRuns` (the approach
  key and the marker line) read it in place of the marker items.
- **Lines.** `BossRunLines.BY_BOSS` gives Pink Bean its own lines. Its DOWN line covers a failed revive.

## Tests

- `TempleManagerHookScriptTest`: the Manager equals Adobis with his header, names, event, no leader item and the key
  `pinkbean`; the statue reactor kills the statue on its own map.
- `AgentBossKeepOutTest`: the shipped rules; the band past both boxes, past one, and none; a ranged member in
  Solomon's box walks out first, is held once inside, fights, and is freed once the part is dead; a melee member is
  never held; a member on 270050200 walks back through in00.
- `AgentPartyBossesTest`: the Pink Bean row against PinkBeanBattle.js.
- `BossEntranceTest`: an owner is on the way to Pink Bean with no item, to Zakum and Showa only with theirs; Leafre
  serves seven bosses.
- `BossRunLinesTellTest`: Pink Bean's lines name no other boss, and it asks for no item.
- Updated pins: keys, catalog list, death modes, shipped scope types, standards; the "unlisted" examples move to Chaos
  Horntail.

Full suite on bbf4f205b: 13,467 tests, 0 failures, 37 skipped (10:54 min).

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online. The statue fix acts for every player once deployed.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `pinkbean`. For stage 2, set `COMPANION_BOSS_ALLY_TYPES` to include
   `pinkbean`. For stage 3, set `COMPANION_BOSS_OWNERS_MAX` to 2.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!resetboss <name> PINKBEAN` for each character.
4. Run `!warp 240000000`. Check that short companions buy there.
5. From Bossfriend, run `!warp 270050000`.
6. Start the client memory gate, stage 1: owner plus 5.
7. Create the expedition at the Temple Manager, register the party and start.
8. Watch each wave. Check that ranged companions stay between Solomon's and Rex's boxes.
9. Run `!hpmp Isolde 0`. Check that she revives in the 270050200 instance and walks back through the portal.
10. Check that the stage-5 item stays on the ground and that Pink Bean wakes.
11. Repeat with the bench allies as stage 2, then with ohboy as stage 3. For stage 3, run `!warphere ohboy` from
    Bossfriend on 270050000.

## Open items

1. The scaled standard (1200/1200, 10000) waits for Questions 5 and 6.
2. `pinkbean` is on (user, 2026-10-16, 18d5b66e0), and so are its bench allies (`COMPANION_BOSS_ALLY_TYPES`
   `easy_balrog,pinkbean`, b00fc73cc); `OWNERS_MAX` stays 1. Runs 4-17 used no bench allies. An ally tops up at
   Leafre on its way in under the 1500/0 floor (run 3's ally bought White Potions before it); one short of mesos
   arrives short of 1,500 and says so, and the dry and last-stand holds keep it from spending the run's last lives.
3. Warriors still die with full potion bags to a deadly hit (HP to 1) followed by another before the autopot fires:
   Solomon's attack1 stuns as it drops HP to 1 (Thane, run 16), and seduce walks a member into the next hit. Deadly
   attacks are left out of both keep-outs on purpose.
4. Paladins hit for little: Orrin (Lv 200) has Blast at level 10 of 30 and Holy Charge 10, and Ariel is immune to Holy.
   Benched from run 16 on; a separate task looks at Paladin skill builds.
5. A Bowmaster (8,996 HP) cannot fight Pink Bean himself: inside his barrage it dies, and held outside it the adds
   find it alone. With the last stand it costs at most the deaths it already had.
6. A top-up to 1,500 Reindeer Milks costs a companion 3.5-8M mesos; runs 15-17 funded them by GM.

## Risks

- Cleared in 69 minutes of the 140 at `companionDamagePercent` 2000 (run 17).
- Every death costs EXP. The map has no NO_EXP_DECREASE bit.
- Potions may run out before the end, because the standard is capped by the Use tab (1,500 lasted in run 17).
- This is the longest fight, so the client memory gate matters most here.
- Unconfirmed: whether companions follow-warp past the dragon ride and the Temple of Time gates (plan decision 11a).

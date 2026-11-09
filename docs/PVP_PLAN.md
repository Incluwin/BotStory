# Fights between bots and players (plan)

Written 2026-11-08 against main line `b583d9057` (branch `fix/casino-live`). Nothing here is built. The plan covers one
shared combat core and the content on top of it:

| Id | Piece | One line | Size |
|---|---|---|---|
| V0 | Probes | Five client and data facts the core depends on, measured before code | S |
| V1 | Duel core | A server-side fight between any two characters: hits, damage, knock-out, rules | L |
| V2 | Duel brain | Bots that fight a moving character, and learn to | L |
| V3 | Rival challenges | Your rival crew turns up on your field and challenges you | M |
| V4 | Coliseum ladder | Rated 1v1 matches against citizens in Ariant's Battle Arena | M-L |
| V5 | Weekly tournament | An 8-seat bracket per level band; citizens fill the empty seats | M |
| V6 | Team fights | You and your companions against a crew, 2v2 and 3v3 | M |
| V7 | Client polish | Your own damage number when a bot hits you (V0.1: the stock client never shows it) | S-M |

The rules in `docs/BOT_CONCEPT_PLAN.md` section 0 apply to every piece: the immersion test, learn don't script, kill
switches, existing ticks only, `# Technical:` config notes, the split suite before merge.

**This plan replaces D14** ("Capstone duel staged as a race, not PvP"). D14 was decided because "there is no PvP
primitive" (`BOT_CONCEPT_PLAN.md:524`). V1 is that primitive.

---

## 1. What exists

### Things to build on

- **Attacks with no target reach the server.** The v83 client sends an attack packet even when it hits no monster.
  - The three handlers test `numAttacked > 0` explicitly (`CloseRangeDamageHandler:77/105`, `RangedAttackHandler:82`,
    `MagicDamageHandler:63`), and a comment at `AbstractDealDamageHandler.java:272-276` cites captured packets that list
    no monster.
  - Such a packet is still parsed and broadcast, and `applyAttack` (`AbstractDealDamageHandler:327`) still charges MP and
    applies the skill. Its target loop is just empty.
  - So a server-side PvP hit test can hang off the existing handlers. The client never has to know players are
    targets.
- **Skill boxes in world space.**
  - `StatEffect.calculateBoundingBox(Point, boolean facingLeft)` (`StatEffect.java:1284`) mirrors a skill's WZ `lt`/`rb`.
  - `AgentProjectileHitbox.clientProjectileHitBox` (`:39-74`) gives the projectile box: 400 px plus passive range,
    ±50 px vertical.
  - `AgentAttackExecutionProvider.closeRangeWeaponActionHitBox` (`:759`) gives melee boxes from the weapon afterimage.
  - The v83 client hit test is a rectangle intersection (`docs/bot-hitbox-client-gap-analysis.md`), so rectangles are
    faithful.
- **Server-side hits on characters, with every defense applied.**
  - `ServerMobDamageService` (`server/combat/ServerMobDamageService.java:89-241`) rolls a hit, applies Combo Barrier,
    Achilles, High Defense, Magic Guard, Meso Guard and Battleship HP, and broadcasts `damagePlayer` to the map *including
    the victim*.
  - Server-run bosses already use it on human players (`ServerMobAutonomyService:593`).
  - It takes a `Monster` attacker. V1 extracts its defense-and-apply half so a character can be the attacker.
- **A zero-target bot attack.** `AgentCombatAttackRuntime.attackAtNothing` (`:279-334`) runs every readiness gate
  (cooldown, ammo, MP, seal, weapon) and the real handler with no targets. It broadcasts the swing and pays its costs.
  `AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, Point, skillId)` (`:66`) aims a plan at a point. Together they are a
  bot's half of the PvP attack.
- **Bot body bounds.** `AgentMobTouchRuntime.agentTouchBounds` (`:24`) is the box mobs hit. It becomes every combatant's
  hurtbox, humans included.
- **Damage formulas for any character.**
  - `CombatFormulaProvider.resolveDamageProfile` (`:231-282`) gives a min/max line for a skill.
  - It also provides `getTotalAccuracy` (`:116`), `getTotalAvoidability` (`:135`), crit and Shadow Partner.
  - It was written for Agents but takes any `Character`.
- **The combat learner and its arena.**
  - `server.agents.capabilities.combat.learning.AgentCombatLearner` is SMDP Q-learning over skill choice.
  - `AgentCombatArenaService` and `!combatarena` train and evaluate it against dummy mobs, and measure each skill's
    sustained damage rate. V0 reuses that rate for calibration.
- **Private map copies.** `StoryScenes` and `SceneInstance extends EventInstanceManager` (`SceneInstance.java:22`) put two
  rivals in a private copy of a map with no JS. That is the template for a duel ground.
- **The rival crew is live** (`RIVALS_ENABLED: true` since 11-07).
  - `RivalCrews.java` keeps one crew of 3-5 citizens within ±5 levels per account main.
  - It already has banter, megaphones, whispers, a whispered yes/no parser (`NeedLedger.java:757-810`) and `RivalFacts`,
    which builds teases from real records.
- **Arena maps ship in the client.**
  - Ariant's Battle Arena: lobby 980010000, King's Room 980010010, arenas 980010101/201/301 with `Bgm13/FightSand`, and
    exit 980010020.
  - The field limits block doors, pets, mounts and minigames. Potions are not blocked.
  - Cosmic's `AriantColiseum` (jewel hunting, NPCs 2101014/2101017) uses the same maps and must keep working.
- **Reward items that need no client change.**
  - Event Trophy 4000038 is an ETC item with an icon. No drop table or script uses it, and nobody holds one.
  - Medals 1142077 "Absolute Victory Carnivalian", 1142078 "Gifted Carnivalian" and 1142084 "Persevering Challenger"
    are unused.

### Gaps

- **No PvP code at all.** Every bot attack targets a `Monster`: `AgentAttackPlan.targets` is `List<Monster>`, and 63 of
  187 combat files import `Monster`.
- **No player hurtbox.** `MapleMap.getPlayersInRange` tests the foot point only.
- **The attack packet cannot carry a player target.** `addAttackBody` writes monster oids. A player's damage number
  can only come from `damagePlayer` (`PacketCreator.java:2714`).
- **The victim's own client draws no `damagePlayer` about itself** (V0.1, measured 2026-11-08).
  - Other clients do draw it, even with no mob (V0.2).
  - So a human hit by a bot sees its HP bar drop but no number until V7.
  - The same applies to today's server-run boss hits (`docs/current/SERVER_BOSS_COMBAT_RUNTIME_ARCHITECTURE.md:3`,
    "regular-client visual").
- **The server's view of a human is stale.** `chr.getPosition()` is the last movement packet, and a moving client sends
  one every 510 ms (V0.3).
- **Death is real.** HP 0 runs `playerDead` (`Character.java:8770`) and the EXP penalty (`DeathPenaltyService`), for bots
  too. A duel must never reach it.
- **Bot autopot is instant.** `addMPHPAndTriggerAutopot` → `triggerAutopotAfterStatLoss` (`Character.java:10858/10871`)
  drinks on any HP loss. Bots also use items through `AgentUseItemBridge`. Both break any potion rule unless duels gate
  them.
- **Bots never dodge or lead a moving target** (no predict code). Their reaction delay (`AgentImperfectionPolicy:67`,
  180-500 ms) applies only to newly seen mobs.
- **No reward currency exists.** `ItemRestrictionPolicy` only *allows* untradeable items, so it cannot bind one.

---

## 2. Balance: the numbers

### Why raw damage cannot be used

The table below is the live database on 2026-11-08: 1,121 characters at Lv 10+, nearly all citizens. "Base" is the
weapon formula's max line before any skill multiplier, from real equipped gear. Mage base is low because magic
multiplies by the skill's `matk` instead.

| Band | Warrior HP / base | Bowman | Thief | Pirate | Mage HP (MP) |
|---|---|---|---|---|---|
| 30-49 | 2,476 / 386 | 1,778 / 250 | 1,939 / 168 | 2,161 / 170 | 1,176 (3,078) |
| 50-69 | 4,591 / 793 | 2,637 / 606 | 2,593 / 327 | 3,227 / 557 | 1,536 (4,354) |
| 70-89 | 6,595 / 1,370 | 3,674 / 983 | 3,861 / 685 | 4,614 / 877 | 2,172 (6,104) |
| 90-119 | 9,867 / 2,310 | 4,569 / 1,633 | 4,691 / 1,230 | 5,619 / 1,512 | 2,670 (8,157) |
| 120+ | 16,183 / 4,330 | 7,229 / 4,515 | 7,188 / 2,623 | 9,135 / 3,255 | 4,281 (13,199) |

- A base line is already 20-60% of a same-class victim's HP. Skills multiply it 2-6x per attack: Brandish is 2 lines at
  260%, Triple Throw 3 lines.
- Raw PvE damage therefore ends most fights in one to three attacks, and one-shots mages.
- The ratio also grows with level, so one flat "PvP damage = 10%" cannot work across levels.

### The PvP damage model

Every PvP line is rolled by the server. The attacking client's damage numbers are never used, because the client
never computes damage against a player.

1. **Roll the line as PvE would.**
   - Use `resolveDamageProfile` for the skill and level actually used, with crits, Shadow Partner, buffs and the
     skill's lines.
   - Ignore element and every monster-only modifier.
   - Gear, stats, skill levels and skill choice therefore still matter.
2. **Normalise to time-to-knock-out.**
   - Multiply each line by the attacker's PvP coefficient, fixed at match start:

     `c = H_ref(L) / (TTK × D_job(L)) × G^(γ−1)`

   - `H_ref(L)` is the median max HP of all citizens at the attacker's level.
   - `D_job(L)` is the median sustained single-target damage per second of the attacker's job branch at that level.
     It comes from the arena's measured skill rates (V0.5), so fast skills like Hurricane and slow casts like Meteor
     come out equal per second.
   - `TTK` = `PVP_TTK_SECONDS` = **12**: seconds of uninterrupted hitting for a median attacker to knock out a
     median-HP victim.
   - `G` is the attacker's gear ratio: `calculateMaxBaseDamage(totalWatk)` (or `magicDamageBase`) over its job's median
     at that level.
   - `γ` = `PVP_GEAR_EXPONENT` = **0.7** compresses gear gaps without erasing them. Twice the median gear deals 1.62x;
     70% of the median deals 0.78x.
3. **Multi-target.** Each target past the first takes 15% less, with a floor of 60%. A 3-target hit deals 70% to each.
4. **Level gap.** Damage changes 2% per level of difference, capped at ±10%. That is the whole ±5 rival band.
5. **Defense.**
   - Damage is multiplied by `1 − min(0.25, def / (def + 40·L_attacker))`, using WDEF against physical attacks and MDEF
     against magic.
   - At Lv 79 this is 8% for a warrior and 4% for a mage.
6. **Hit chance.**
   - Physical attacks use the existing avoid formula (`calculateBotAvoidChance`), with misses capped at 25% so a luck
     thief is never untouchable.
   - Magic misses are capped at 10%.
7. **Per-attack cap.** One attack never takes more than `PVP_MAX_HIT_PERCENT` = **25%** of the victim's max HP. Its lines
   are scaled down together to fit.
8. **Defenses, through the shared applier:**
   - Combo Barrier, Achilles and High Defense apply as in PvE.
   - **Magic Guard** absorbs half its PvE share, set by `PVP_MAGIC_GUARD_FACTOR` 0.5.
   - **Meso Guard** reduces damage by 25% instead of 50% and still costs mesos.
   - **Power Guard** cuts melee hits by its percentage but reflects nothing.
   - The halving exists because at full strength Magic Guard makes the mage the tankiest class (worked example below).
9. **Knock-out instead of death.**
   - A hit that would take a combatant to 0 HP leaves it at 1 and marks it knocked out.
   - `playerDead`, the EXP penalty, safety charms and death counts never run.
   - A knocked-out combatant takes no more PvP damage, and its attacks stop counting.

### Worked example: Lv 79, equal gear (median G = 1)

- `H_ref` = 4,183, so a median attacker deals 349 HP/s while hitting.
- That is about 260 per 0.75 s attack (6% of `H_ref`), and about 870 for a 2.5 s cast, which is under the 25% cap.

| Victim | HP | After PvP defenses | Seconds of continuous hitting |
|---|---|---|---|
| Warrior | 6,595 | 8% WDEF | **20.6** |
| Pirate | 4,614 | 6% WDEF | **14.1** |
| Thief | 3,861 | 8% WDEF, ~15% misses | **~14** (~19 with Meso Guard) |
| Bowman | 3,674 | 7% WDEF | **11.3** |
| Mage | 2,172 + 6,104 MP | Magic Guard at 0.5 | **10.8** (24.8 with full Magic Guard) |

- Output is level in time, by construction. Toughness keeps its class identity: warriors last twice as long as mages.
  Range, mobility and AoE decide the rest.
- Real fights include missed timing, movement and potions. Measured duels should run about 2.5-3x the continuous time,
  so **about 30-60 s per duel**. The coliseum round timer is 90 s.
- These are priors. V2's sims produce the real win-rate matrix. A per-job `PVP_JOB_SCALE` within ±15% is then tuned by a
  bandit on that matrix (learn, don't script), not by hand.

### Healing rules

Without limits, potions make PvP endless. An Elixir (50%) every 3 s heals about 17% HP/s. A median attacker hitting
without pause takes about 8% HP/s. Bots follow the same rules, so their autopot and `AgentUseItemBridge` go through the
duel gate.

| Mode | Own potions | Drink limit | Healing skills |
|---|---|---|---|
| Field duel (V3) | yes | one per `PVP_POTION_COOLDOWN_MS` 5,000, heals at most `PVP_POTION_HEAL_CAP_PERCENT` 10% of max HP/MP | Heal at most 5% of max HP per cast, 2 s apart |
| Coliseum (V4-V6) | no | three arena tonics per round, 25% each, 8 s apart | same |

- Resurrection, Hero's Will and Dispel: Resurrection does nothing in a duel. Cleanses work as usual.
- After a field duel, both sides return to the HP and MP they had when it started, so a fight costs nothing but time.
- No crowd control in v1 (PD5). Stuns, freezes, seals and blinds from player skills are ignored in PvP. Diseases from
  *mobs* still apply.
- No summon damage in v1 (PD6). Summons still fight mobs, and puppets do not pull bots.

### Fairness when the server's picture is stale

The server sees a human only through movement packets. Ties go to the human both ways:

- **Human hits bot.** The bot's position is kept in a 1 s ring buffer at 50 ms. A hit counts if the bot was inside the
  box at any point from the human's estimated latency + 600 ms ago until now. The 600 ms is from V0.3: the client
  reports movement every 510 ms while moving, so the attacker's own position can be that old too.
- **Bot hits human.**
  - The human must be inside the box at the last reported position *and* at the position extrapolated from its last
    movement fragment.
  - The human's hurtbox is shrunk 6 px on each side.
  - Moving out of the way therefore works.
- **Bots telegraph honestly.** The swing packet goes out at wind-up, and damage lands at the animation's hit frame from
  `AgentAttackTiming`. A human gets the same warning a mob attack gives.
- **Bots react like people.** They see an attack only when its packet would reach a client, then wait their
  `reactionDelayMs` (180-500 ms) before dodging or switching targets. This extends that seam, which today covers new
  mobs only.
- **Rate limit for humans.** A PvP hit counts only if it comes at least the skill's server-side animation time after the
  previous one. A speed hack gains nothing.

### Rewards

- **The unit is the grind-minute (GMin):** the median EXP, or mesos, that a citizen of the same level earns per minute
  on fields. V0.6 measures it from `ExpLogger` and the meso ledger, and the table lives in config.
- On this server's rates (10x EXP, 3x meso; simulated farming books about 1,782·L mesos an hour) a meso GMin is small.
  Items and EXP carry the value.
- **The currency is the Event Trophy (4000038).**
  - It is untradeable by a new server rule: trade, shop, drop and Duey refuse it.
  - It can only be spent at the Arena Quartermaster.

| Source | Win | Loss | Daily cap |
|---|---|---|---|
| Rival challenge (V3) | 20 GMin EXP (×2 on the first win of the day), 3 Trophies, the rival's stake (30 GMin of mesos from its own purse), 25% chance of a scroll from the rival table | 1 Trophy; 5 GMin EXP if you dealt ≥ 50% of their HP | 3 rewarded challenges |
| Ladder match (V4) | 3 Trophies, 6 GMin EXP (first 5 wins) | 1 Trophy | 10 rewarded matches |
| Tournament (V5) | Champion: 40 Trophies, medal 1142077 for 7 days, 50% of the entry pool. Final: 20, 25%. Semis: 10. Quarters: 5 | — | weekly |

- **Rewards scale with the odds.** Every win reward is multiplied by `0.75 + 0.5 × (1 − p)`, where `p` is the
  pre-match win chance from ratings. A win with p > 0.85 pays only Trophies, and beating a favourite pays up to 1.25x.
- **Daily ceiling: about 30 Trophies and 110 GMin of EXP.**
  - That is about 1.8 hours of grinding EXP for an hour of fights: good, but capped.
  - 30 Trophies buy about two 60% weapon-ATT scrolls.
- **Quartermaster prices (priors):**
  - Consumables: 2-5 Trophies.
  - A 60% ATT scroll for your weapon: 15. A 10% ATT scroll: 40.
  - A stock chair: 30. The Persevering Challenger medal: 60.
- **Money flow.**
  - Entry fees (ladder 20·L; tournament 200·L per seat, citizens included) are sinks; the house keeps 25% of each pool.
  - Rival stakes are a booked transfer (`PVP_STAKE`), not a faucet. A citizen's later top-up
    (`ProgressionLedger:71-74`) stays the known faucet it is today.
  - Quartermaster scrolls are an item faucet, booked like drops.

**Closed exploits:**
- Your own companions never pay rewards as opponents.
- GM characters earn nothing.
- Leaving the map or disconnecting for 30 s forfeits.
- Rival crews follow the account's main, so a fresh alt has no rival to farm.
- The odds scaling stops farming weak bots.

---

## 3. V0. Probes (do first)

| Probe | How | Decides |
|---|---|---|
| V0.1 Own damage number | `!pvp probe self`: send `damagePlayer` with your own id, with -1/-2/-3 sources and mob 0 | Whether V7 is needed |
| V0.2 Remote number without a mob | The same about a citizen, with `monsteridfrom` 0 and `oid` 0, on a second client | Which `damagePlayer` fields V1 sends |
| V0.3 Movement cadence | Log the gap between `MOVE_PLAYER` packets for a human walking, jumping and attacking | The lag window in section 2 |
| V0.4 Zero-target audit | For each job's two main attacks, attack empty air; log skill, facing bit (`stance & 0x80`), delay | Which skills need a special case |
| V0.5 Calibration | `!pvp calibrate`: `H_ref` and job median base from the DB; `D_job` from `!combatarena` per job at band midpoints | `scripts/config/pvp/reference.yaml` |
| V0.6 Grind-minute table | Median EXP and meso per field-minute per level band from `ExpLogger` and the ledger | The reward table |
| V0.7 WASM | Repeat V0.1-V0.2 on the WASM client | Whether WASM can join duels in v1 |

*Done when* `docs/PVP_V0_RESULTS.md` records each probe with evidence: screenshots through cua, and log lines.

---

## 4. V1. Duel core

### Design

- **`server.pvp`** has no living-world dependencies, so V3-V6 all sit on top of it.
  - `DuelField`: the combatants, their sides, rules, state (COUNTDOWN, FIGHTING, ENDED), per-round timer, KO set and
    pre-fight HP/MP snapshot.
    - One per map instance.
    - It uses the map's own lock order (`docs/agents/AGENT_COSMIC_THREAD_AFFINITY_AUDIT.md`).
  - `DuelRules`: a record per mode (field, coliseum, team) from `scripts/config/pvp/modes.yaml`.
  - `PvpHitResolver`: attack plus attacker position and facing → victims, using the boxes listed in section 1.
    - Projectile attacks take the first N by distance.
    - The target limit is `min(mobCount, 3)`.
    - The section 2 fairness windows apply.
  - `PvpDamageModel`: section 2 steps 1-7. A pure function with property tests.
  - `CharacterHitApplier`: the defense-and-apply half extracted from `ServerMobDamageService`, so mobs and characters
    share it. Its step 9 KO clamp runs only when a `DuelField` owns the victim.
  - `PvpPackets`: `damagePlayer(-4, 0, victim, damage, ...)` about the victim to the whole map, plus the KO emote and
    the round notices. A dodged hit is sent as source `-2`, which clients draw as MISS (V0.2).
- **Hooks.**
  - **Attacks:** one call, `PvpHooks.onAttack(chr, attack)`, after the animation broadcast in each handler. Bots attack
    through the same handlers, so this one hook covers human-to-bot, bot-to-human and bot-to-bot hits.
  - **Potions:** a potion gate in `UseItemHandler`, `triggerAutopotAfterStatLoss` and `AgentUseItemBridge`.
  - **Forfeit:** a map-leave hook, through `DuelField.leave`.
- **Hard rule: no consent, no damage.** Only two characters in the same `DuelField` on opposite sides can hurt each
  other. A test proves an AFK human next to a fight is never hit.
- **GM tools** (level 5, `CommandsExecutor.registerLv5Commands`):
  - `!duel <name> [mode]` starts a duel with any character, citizen or companion.
  - `!pvp sim <jobA> <jobB> <level> [n]` runs a Monte Carlo of the damage model with real loadouts and prints win rate
    and median duel time.
  - `!pvp calibrate`, plus `!pvp probe` from V0.
- **Config.**
  - New `pvp:` section (`PvpConfig`, validated): `PVP_ENABLED` false, `PVP_TTK_SECONDS` 12, `PVP_GEAR_EXPONENT` 0.7,
    `PVP_MAX_HIT_PERCENT` 25, `PVP_AOE_FALLOFF` 0.15, `PVP_LEVEL_PERCENT` 2, `PVP_DEF_K` 40,
    `PVP_MAGIC_GUARD_FACTOR` 0.5, `PVP_POTION_COOLDOWN_MS` 5000, `PVP_POTION_HEAL_CAP_PERCENT` 10,
    `PVP_HUMAN_HURTBOX_INSET_PX` 6.
  - Each key gets a `# Technical:` note.
- **DB.** Reserve changeset 59 / SQL 050 (`pvp-matches`): `pvp_match` (mode, map, started, ended, winner side, rounds)
  and `pvp_match_side` (character, kind HUMAN/COMPANION/CITIZEN, side, damage dealt and taken, KO).

### Phases

- **V1.1** `CharacterHitApplier` extraction, with existing boss and mob tests unchanged.
- **V1.2** Hurtbox, `PvpHitResolver`, `PvpDamageModel`, with geometry and property tests.
  - The property tests: no attack over the cap, and no KO from 100% HP in under 4 attacks.
- **V1.3** `DuelField`, rules, KO, potion gate, hooks, `!duel`. The bot uses basic attacks only in this phase.
- **V1.4** `!pvp sim`, and the first win-rate matrix.
- *Done when* a GM `!duel` against a citizen ends in a knock-out with no death and no EXP lost, the matrix shows median
  duel times of 10-25 s of continuous hitting at every band, and the full suite passes.

### Status (2026-11-08, branch `feat/pvp-v0`, not deployed)

- **Built:** V1.1-V1.4. `CharacterHitApplier` (mob hits unchanged: Magic Guard 1.0, Meso Guard 0.5), `PvpHitResolver`,
  `PvpDamageModel`, `PositionTrack`, `DuelField` / `DuelRegistry` / `DuelBrain`, `PvpHooks`, the `pvp:` config
  section, `DuelSim`, and `scripts/config/pvp/reference.yaml` (gear medians from the 20:40 calibration).
- **Differs from the design above:**
  - The GM command is `!pvp duel <foe>`, `!pvp duel <a> <b>`, `!pvp duel <a> [b..] vs <c> [d..]` (`me` is you),
    `!pvp duel stop [id]` and `!pvp duels`, not a separate `!duel`. It runs with `PVP_ENABLED` off; that switch gates the
    player-facing ways in from V3.
  - The bot uses its four strongest attack skills (strongest first), not basic attacks only, because the stock attack
    planner already gives each skill's box and cooldown.
  - The potion rule is one healing item per `PVP_HEAL_WINDOW_MS` plus an HP/MP allowance of `PVP_HEAL_PERCENT` per
    window from any source (potions, Heal, regen), which the field takes back when exceeded.
  - Hooks: `applyAttack` (basic attacks before the skill block, skill attacks once MP is paid), `UseItemHandler`,
    `PetAutopotProcessor.runAutopotAction`, and the citizen scheduler's re-plan, logout and move-on gates. A fighter who
    leaves the map forfeits on the next 100 ms tick, so no map-leave hook was needed.
  - No `DuelRules` / `modes.yaml` yet (one mode) and no `pvp_match` tables: both come with V3/V4, which need them.
- **Model-only sim (`DuelSimTest`, median fighters, no defenses):** a mirror match is 50/50 at a 15 s median. A
  one-level edge wins 74%, and a five-level edge wins every round. Duel times only spread from 14 to 16 s. The rolls
  average out over about 20 attacks, so the model alone leaves almost no room for upsets. Live duels add misses,
  movement and reaction; V2's practice duels must measure how much. **Open decision PD14:** if live duels are as
  deterministic, widen the luck (a wider roll, or rarer bigger swings), or soften `PVP_LEVEL_PERCENT`.
- **Live duel #1 (2026-11-08 21:59, build d5198b463):** Crewfriend (Lv 96 Spearman) against SkyMage (Lv 93 Priest
  citizen) in Henesys. Countdown, round clock and the end on time all worked; no errors, no deaths. SkyMage walked
  over and hit Crewfriend (one hit about 12% of his HP; the heal allowance let his potions back in). It then drifted
  2,000 px away and stood there, so Crewfriend dealt 0 and lost the round on HP share (SkyMage dealt 9,297). Two bugs,
  fixed after:
  - The citizen's town-life session kept walking it about on its own tick. A first fix paused town life the way
    an NPC conversation does, but live duel #2 (23:36) showed that pause does not stop its tick (only the
    interaction lease's per-tick stop does). The duel now stops town life and clears its visit lease, as the
    citizen scheduler does when it takes a citizen over, and the scheduler starts a new one at its next plan. A
    field session only pauses: its suspend is honoured.
  - The brain judged reach by the attack planner's box, which for a skill with no lt/rb (projectiles, most magic) is
    a 1x1 point at the aim that never meets a body. So the bot believed it already stood at full reach and never
    closed in. It now asks `PvpHitResolver.attackBox`, the box the duel will judge the swing by.
- **First class matrix (`!pvp sim matrix`, live loadouts, 2026-11-08 22:05):** warriors won 87-99% against every
  other class from Lv 70, and warrior mirrors lasted 28-42 s. The model scaled hits to the class-balanced median
  victim, and warriors carry 1.78x that HP plus Achilles. In a fight with this little luck, any toughness edge is a
  near-certain win.
- **PD15, decided by the user 2026-11-08: even classes.** `PVP_EVEN_CLASSES` (on): a hit is scaled by the victim
  class's median HP ratio (`class_hp_ratio` in the reference table: warrior 1.78, magician 0.50, bowman 0.84, thief
  0.88, pirate 0.97), and Achilles, High Defense, Combo Barrier, Power Guard, Magic Guard and Meso Guard sit out of
  duels (`CharacterHitApplier.Guards.NONE`; mob hits unchanged). Without the second half, Magic Guard would make
  magicians the toughest class once HP is evened. Gear defense, avoid, level and gear damage still count.
- **Live duels on 30933f544 (2026-11-09 00:03-00:16, Henesys):** the bot now crosses the map (through in-map
  portals), closes to its reach and stays. No errors, no deaths.
  - Crewfriend (Lv 96 Spearman, only Slash Blast on his keys) against AngryMoon (Lv 86 Crusader): AngryMoon dealt
    36,199 in 180 s (about 200 per second), Crewfriend 564; neither went down, as the 2%-a-second heal allowance
    covered most of it. Ended on time.
  - Bot against bot: Assassin 57 KO'd Cleric 54 in 23 s (2,168 against 2,035, close); Marauder 112 KO'd Crusader
    110 in 81 s (46,255 against 8,415); Crusader 87 KO'd Crusader 86 in 136 s (38,765 against 0, the loser never
    landed a hit); Brawler 74 against Wizard 79 hung: the Brawler left the map, and a forfeit never ended a duel
    (fixed after: the tick ends a decided duel).
  - **Finding:** what a bot really deals in a duel varies by more than ten times between bots, and is often far
    below the model's assumed rate (gear base x 4.6 a second). So most duels run 3-10 times past the 12 s target.
    V2 must log each fighter's swings tried, swings sent and hits landed, set the rate per job path from duel data,
    and look at Crusader bots (their combo-orb skills are refused by the planner, which leaves weak fallbacks).
  - Potions: an Elixir drunk by autopot reads full HP until the next 100 ms tick takes the excess back, and the
    potion is spent for a 10% heal. V2 should refuse a potion bigger than the allowance instead.
- **Known limit:** a bot with no attack skill (a Beginner) only walks: the stock basic-attack planner needs a monster.
- **Not yet run:** a live duel on the fixed build, and `!pvp sim matrix` at each band on live loadouts.

---

## 5. V2. Duel brain

### Design

- **`AgentDuelModeCoordinator`** sits beside grind mode: its target is a `Character`.
  - It reuses ranged spacing (`AgentGrindRangedEngagementService`), the retreat hold, jump shots and follow-position
    snapping.
  - Attacks go through `planSkillAttackAt` and `attackAtNothing`, so every cooldown, MP and ammo gate still holds.
- **What bots learn.**
  - **Leading a target:** from the last two observed movement steps (`AgentFollowTargetMotionState`), with an aim error.
    A bot can whiff when you jump.
  - **Dodging:** stepping out of a telegraphed box, at human reaction time, and only for attacks a human could see
    coming.
  - **Platform play:** climbing or dropping to break line, and holding a ledge.
- **The learner, extended.**
  - **Duel state:** distance bucket, relative height, own HP%, opponent HP%, own MP%, the opponent's class and range
    kind, whether the opponent is mid-cast, tonics left.
  - **Actions:** each legal attack, approach, retreat, jump-attack, climb or drop, buff, drink, wait.
  - **Reward:** `(damage dealt − damage taken) / H_ref`, plus ±1 at the end.
  - **Training:** bot-against-bot self-play in the arena, headless, where both positions are exact.
    - Priors come from the PvE scorer plus two rules: melee closes, ranged holds 60-80% of its range.
- **Personality, not handicaps.**
  - Persona traits set aggression and caution.
  - Difficulty comes from matchmaking (ratings), never from bonus stats.
  - F8 imperfections apply: an occasional greedy attack at low HP, capped.

### Phases

- **V2.1** Duel mode with priors only: chase, space, attack, drink under the gate.
- **V2.2** Leading targets and dodging telegraphs.
- **V2.3** Learner state, actions and reward; self-play runs per job pair at three bands.
- **V2.4** The `PVP_JOB_SCALE` bandit on the self-play matrix.
- **Status (2026-11-09, started at the user's "Yes, start V2"):** measuring first, because the per-job rate the model
  divides by applies to humans too: calibrating from bots held back by a bot gap would make human players of that job
  far too strong.
  - `DuelStats` / `DuelLog`: per fighter, the brain's thinks, buffs cast, swings sent / deferred / refused (with the
    planner's and the transaction's reasons), and what the attacks met (packets, whiffs, dodges, hits, raw damage
    rolled before scaling and before the dodge roll). One log line per fighter and one row in `logs/pvp/duels.csv`
    when a duel ends.
  - The brain tries up to ten attack skills, strongest first (four left a Crusader with only combo-orb skills it could
    not use), and keeps its own buffs up from the countdown on: the stock buff tick waits while a bot neither follows
    nor grinds and skips any map with no live monster, so in a town duel no bot had buffed, and a Crusader never had
    Combo Attack. Dark Sight and Wind Walk are left out.
  - `!pvp practice [n] [span]` starts bot-against-bot duels between free citizens (not hired, no party, event or duel)
    on maps with no player; `!pvp rates` turns the CSV into the median raw damage per engaged second per job path and
    band, beside the model's gear-based rate, and writes a `d_job` block to `logs/pvp/rates-*.yaml`.
  - Deferred: the potion rule (an Elixir is spent for a 10% heal), the duel-mode movement of V2.1, and learning.
  - **First practice run (66c255902, 2026-11-09 06:17-06:47, 81 duels, 162 rows):** all ended (32 knock-outs, 48 on
    time, 1 forfeit); no errors. Every attack that reached the duel met its target (0 whiffs): the brain swings only
    in reach. Bots wait on their attack delay about four thinks for every swing sent.
    - Measured raw damage per engaged second over the model's rate (gear base x `dps_per_base`), median per class:
      warrior 0.26 (n=60), magician 0.18 (29), bowman 0.18 (23), thief 0.28 (12), pirate 0.16 (20). The model's
      rates are 4-6 times what bots really deal, which is why duels run long.
    - 16 fighters never swung: an Assassin with no throwing stars (`plan:ammo` every think; a citizen supply gap, not a
      duel one), and Clerics, an Assassin and a Gunslinger whose 400 px projectiles had them aim for a spot past the
      platform's edge, drop below, and walk back to it all duel. Fixed after: the stand spot is 70% of the reach
      (the plan's ranged prior) and walks in until it is on the target's level.
    - Next: a second practice run on the fix, then per-class rates from it.
  - **Second practice run (798641098, 2026-11-09 07:16-07:46, 63 duels, 126 rows):** 21 knock-outs, 42 on time; no
    errors. The stand-spot fix worked (every class swung; 0 whiffs), but the rates did not move: measured over model
    per class warrior 0.09 (n=20, of them 12 Arans), magician 0.20 (34), bowman 0.20 (31), thief 0.28 (17), pirate
    0.20 (6).
    - The gap is cadence, not damage: 82-100% of attack packets hit, and a hit is about what the model expects, but
      bots sent 0.23-0.51 swings a second against a player's ~1.3, deferring about five thinks for each swing sent.
    - Cause: on a map no player sees, an Agent runs BACKGROUND_ACTIVE and ticks every 250 ms
      (`DEFAULT_BACKGROUND_ACTIVE_TICK_MS`), but `AgentCombatActionLockRuntime` counts the attack lock down by one
      50 ms movement tick per tick, with no substeps: a 700 ms animation holds the next swing for 3.5 s. Practice duels
      run only on maps with no player, so every one ran at a fifth of its speed. A human's duel map is watched, so
      the GM duels did not show it.
    - **Not calibrated on this run:** rates tuned to a bot at a fifth of its speed would make every player hit about
      five times too hard. Fixed instead (c4692cf4d): a duel holds its bots at full rate
      (`requireFullRateSimulation`, as boss runs and party quests do) and gives back any earlier hold; a swing
      deferred for its skill's own cost or cooldown tries the next skill; wait reasons are counted (`waited=` in the
      log line).
    - Engine-wide, outside PvP: every citizen grinding on a map no player sees swings at the same fifth of its speed.
      That slows citizen levelling and income and the yield table behind V4's grind-minutes (measured mostly on
      unwatched citizens). Left as is here; fixing it changes the economy, so it is its own decision.
    - Arans: 12 of 20 warrior rows. Their combo skills need a combo count that only builds on monster hits, so in a
      duel they swing Double Swing (`plan:aran-combo`). A human Aran is likely stuck the same way (the client counts combo
      on monster hits; not checked live): an Aran fix (combo on PvP hits) waits for V3.
    - Next: a third practice run on c4692cf4d; per-class rates come from that run.
  - **Third practice run (9b23798a7, 2026-11-09 08:28-08:58, 91 duels, 182 rows):** full rate worked. Bots swung
    0.79-0.95 a second (from 0.23-0.51); 77 knock-outs and 14 on time (from 21 and 42); no errors.
    - Measured over model per class: warrior 0.34 (n=35), magician 0.58 (64), bowman 0.66 (29), thief 0.84 (40),
      pirate one row (too few free Pirate citizens on empty maps). Knock-out duels over 5 s: median 25 s (p25 20,
      p75 40), against the 12 s target.
    - Every remaining wait was the attack lock (`waited=ATTACK_COOLDOWN`, 192 of 192): thinking every 300-600 ms
      against a ~700 ms animation, every other think landed mid-swing. A player holding the key swings as the
      animation ends. Fixed (3d2256a9c): after a swing, or a wait on the lock, a bot thinks again 30-90 ms after the
      lock ends.
    - Ten knock-outs took 5 s or less. Eight were one pair, Miruro (Lv 112 Marauder) against Ryro635 (Lv 113 Dragon
      Knight), re-paired every round: Miruro went down after taking 139, 179, 229 ... 429. It entered each duel
      nearly dead, since a duel gives back the HP a fighter started with. Fixed: every fighter starts at full HP
      and MP when the bell rings; its own values still come back after, so the duel heals no one.
    - Not calibrated on this run: the key-repeat fix should lift every class's rate by about half again.
    - Next: a fourth practice run on 3d2256a9c; per-class `dps_per_base` from it, and a pass at the Pirate sample.
  - **Fourth practice run (c5debf1a5, 2026-11-09 10:21-10:51, 97 duels, 194 rows):** 94 knock-outs, 3 forfeits, none
    on time; no errors. (A first try at 09:54 ran no duels: the script called the client tool by a relative path.)
    - Cadence: 0.84-1.23 swings a second (warrior 0.84, magician 1.00, bowman 1.11, thief 1.23, pirate 1.12); waits
      fell to 0.09-0.21 a second. Knock-outs: median 18 s (p10 6, p25 14, p75 30, p90 54).
    - Measured over model: warrior 0.49 (n=24, p25 0.29, p75 0.76), magician 0.75 (51), bowman 0.83 (28), thief
      1.14 (38), pirate 0.83 (5).
    - Lv 100+ Crusaders swung 0.54-0.63 a second at 0.04-0.06 of the model, refusing a finisher every think
      (`plan:combo-orbs`); Lv 70s Crusaders were fine (0.66-0.85). Arans: about 0.01. Cause, for humans as much as
      bots: `CloseRangeDamageHandler` earns an orb (and a Marauder's energy charge) only when the attack lists a
      monster (`numAttacked > 0`), and Aran combo counts only the client's monster hits, so no duel ever built
      either. Fixed (d2945a052): the orb/energy block and the Aran count move into `gainOnHit` / `countHit`, called
      as before for monsters, and a landed duel hit earns the same (after the field's lock). Open: a human Aran's
      client keeps its own combo counter and may still refuse a combo skill the server would allow; check live.
    - Sixteen knock-outs took 7 s or less, all one-sided against a warrior: Lv 100+ Crusaders dealing nothing back,
      and Bishops (232) landing three or four hits at the 25% cap on Dragon Knights. A class rate cannot tell a
      Bishop from a Priest; `d_job` rows (measured per job path and band, ahead of the class rate) can.
    - Next: a fifth practice run on d2945a052; then `class_dps_per_base` per class and `d_job` rows with n >= 3 and
      no refusal dominating, and a sim matrix on the calibrated reference.
- *Done when:*
  - self-play win rates at equal gear sit within 40-60% for every class pair at Lv 50, 80 and 110;
  - a +5-level bot wins at most 65% against equal gear;
  - two filmed duels (melee against ranged, mage against thief) pass the "would a player notice?" review.

---

## 6. V3. Rival challenges

### Design

- **When.** A check rides `RivalCrews.tick()` on the 60 s social minute. All of these must hold:
  - the human main has been on a hunting field for 5+ minutes: not town, FM, an event instance, a boss map or a PQ;
  - their HP is at least 60%;
  - they are not in a dialog or trade;
  - at least two crew members are loaded;
  - none in the last `RIVAL_CHALLENGE_GAP_MINUTES` (120);
  - at most `RIVAL_CHALLENGE_DAILY_MAX` (3) a day.
  - When everything holds, roll for about one challenge per two qualifying hours.
- **Arrival.** The challengers travel in for real through citizen portal travel. They walk in from a portal; they do not
  pop in.
- **The challenge.**
  - The leader says a line built from `RivalFacts`, for example "Saw your Balrog time. Cute. Fight us?".
  - You answer in map chat or a whisper ("yes", "bring it", "no"), read by the existing yes/no parser, or with
    `@duel yes|no`.
  - No answer in 90 s counts as a no. A no only costs a mocking line, and they leave.
- **The ground.**
  - Everyone involved moves to a private copy of the same field with no mobs, using the `SceneInstance` template (PD1).
  - Afterwards everyone returns to the exact spot.
  - Other players on the field never see a fight they did not join.
- **The sides match.** Only those present join: you, your loaded companions and your human party members. The crew
  sends the same count, up to 3. Alone, you face the crew member whose gear ratio is nearest yours, and the rest watch
  and heckle.
- **The match.** Field rules (section 2), one round of 3 minutes. If time runs out, higher remaining HP% wins.
- **After.**
  - The win or loss is booked in `pvp_match` and on the records board as a head-to-head tally per crew member.
  - Rivals quote it later ("3-1. Not for long.").
  - A crew that wins uses one of its shared megaphones (`CHATTER_SMEGA_PER_HOUR` still caps them).
  - A crew that loses whispers a rematch offer later.
  - The letters (F3) pick it up.
- **Lines.** New triggers in `chatter/rivals.yaml`: `rival.challenge`, `accept`, `decline`, `win`, `lose`, `rematch` and
  `heckle`, written in the style of the existing 13.
- **Config.** `RIVAL_CHALLENGES_ENABLED` false (population section), `RIVAL_CHALLENGE_GAP_MINUTES` 120,
  `RIVAL_CHALLENGE_DAILY_MAX` 3, `RIVAL_CHALLENGE_ANSWER_SECONDS` 90.

### Phases

- **V3.1** Trigger, arrival, challenge and answer.
- **V3.2** Private ground, sides, match, return.
- **V3.3** Rewards and stake.
- **V3.4** Records, lines, megaphone, letters.
- *Done when* on the live client a crew walks into the field, challenges you, fights a duel you can win and lose, pays
  out, and quotes the result the next time you meet. Saying no costs nothing.

---

## 7. V4. Coliseum ladder

### Design

- **The place.**
  - An Arena Master in Ariant: a new spawn of a stock NPC template, so AriantPQ's 2101014/2101017 keep their scripts.
  - The Battle Arena lobby is the hall, arenas 1-3 are the fight floors, and the King's Room is the spectator gallery.
  - Arena instances do not spawn the jewel-hunt mobs.
- **Brackets.** Rookie Lv 30-59, Veteran 60-99, Champion 100+.
- **Ratings.**
  - Glicko-2 per character per bracket. Everyone starts at 1500.
  - Citizens have ratings too. Their matches against each other are simulated with `!pvp sim`'s engine on the social
    minute, at most 20 a minute, so the ladder lives without you.
  - A citizen match is fought for real only when a human is watching from the gallery (the watched-citizen rule).
- **Your match.**
  - Queue at the Master, pay 20·L mesos, and get the nearest-rated citizen within ±5 levels.
  - Best of three 90 s rounds under coliseum rules (tonics only).
  - The opponent's line before the fight uses its real record.
- **The Arena Quartermaster** sells for Event Trophies (section 2 prices), from `scripts/config/pvp/quartermaster.yaml`.
- **A board page.** The F1 Bulletin Board gets a "Coliseum" page: the top 10 per bracket, your rating, and this week's
  bracket.
- **Config.** `COLISEUM_ENABLED` false, plus queue, fee and simulation-rate keys.
- **DB (same changeset).** `pvp_rating` (character, bracket, rating, deviation, volatility, wins, losses, season).

### Phases

- **V4.1** The NPCs, arena instance and best-of-three, against a chosen citizen.
- **V4.2** Ratings, queue and citizen background matches.
- **V4.3** Trophies, the Quartermaster and the trade block.
- **V4.4** The board page and spectating.
- *Done when* a human climbs the ladder over a session against citizens near their rating, spends Trophies, and finds
  the top 10 changing while they were away.

---

## 8. V5. Weekly tournament

### Design

- **Schedule.** A weekly bracket per band at `TOURNAMENT_DAY` and `TOURNAMENT_HOUR` (PD12), scheduled with
  `EventManager.scheduleAtTimestamp` (`:183`). Sign-up opens Monday at the Master.
- **Seats.** 8 per bracket. Humans who sign up take seats first; the top-rated citizens fill the rest. Every seat pays
  200·L.
- **Rounds** start every 6 minutes. A human match is fought live. A citizen-only match is fought live in arena 2 or 3 if
  anyone watches, otherwise simulated.
- **Announcements.**
  - System notices announce rounds.
  - Results go out as citizen megaphones, using the citizens' own caps.
  - The champion gets one server notice.
- **The champion** holds medal 1142077 for 7 days (an item with an expiration). It moves to the next champion.
- **Betting (optional, PD13).** Spectators bet casino chips on a match at the cage, with a 10% house cut as a sink. It
  reuses `casino/Chips`.

### Phases

- **V5.1** The bracket, schedule, seats and live and simulated rounds.
- **V5.2** Prizes, medal and announcements.
- **V5.3** Betting.
- *Done when* a scheduled tournament runs start to finish with one human seated, and its results appear on the board
  and in the letters.

---

## 9. V6. Team fights

- **2v2 and 3v3:** you plus up to two companions against a citizen crew, or against your rival crew ("Crew War").
- Companions run the V2 brain on your side, so every companion is a combat partner, not only a grinding partner.
- Your companions cannot be on the other side for rewards.
- Team ratings are kept separately from 1v1.
- Focus fire is a real tactic, so sides are always equal in size.
- *Done when* a 3v3 against the rival crew is filmed and the companions read as teammates in the review.

## 10. V7. Client polish (V0.1 says it is needed)

- **Own damage number.** V0.1 showed that your own client ignores a `damagePlayer` about itself, for every source and
  whether or not you are hidden. The same patch would also show the server-run boss hits that land on you today. So:
  - add server→client **0x189 PVP_HIT** to `docs/CUSTOM_OPCODES.md`;
  - Kaentake draws the number and plays the hit sound on the local user;
  - the HP bar already updates without it.
  - The client function to call is not reverse-engineered yet; it is a separate session.
- **WASM:** a matching handler, if V0.7 shows a gap.
- **Custom.wz, optional:** a proper "Coliseum Mark" and "Coliseum Champion" medal to replace the stock stand-ins, under
  the existing Custom.wz approval.

---

## 11. Order

```text
V0 -> V1 -> V2 -> { V3, V4 } -> V5 -> V6
       \-> V7 (after V0, separate session; client work)
```

- V3 and V4 touch different packages (rivals and coliseum) and can run as parallel sessions once V2 lands.
- V1 and V2 come first and alone: every later piece depends on the damage model and the brain.

## 12. Live checks

1. `!duel` a citizen of your class and level.
   - Expect a 30-60 s fight that ends in a knock-out, then: no death screen, the same EXP, and HP restored.
2. Stand next to a duel without joining it. Expect no damage, ever.
3. Duel a ranged citizen and keep moving. Expect most of its shots to miss while you move.
4. Drink potions quickly in a field duel. Expect one drink per 5 s, healing at most 10%.
5. Rival challenge: say no. Expect a line and their exit, with nothing lost. Next time say yes, win, and check the
   Trophies, EXP and stake.
6. Coliseum: play three ladder matches, then check rating changes, Trophy counts and the board.
7. Film one duel per class pair for the believability pass.

## 13. Decisions

| # | Decision | Recommended |
|---|---|---|
| PD1 | Where a rival challenge is fought | a private copy of your current field, then back to your spot (not in place among mobs and other players) |
| PD2 | Consent | a challenge must be accepted; never a forced ambush |
| PD3 | Human against human | allowed in the coliseum as an unranked mode with no rewards |
| PD4 | Potions | field duels: own potions, capped; coliseum: tonics only |
| PD5 | Crowd control in v1 | off; v2 adds stuns of at most 1 s with diminishing returns |
| PD6 | Summon damage in v1 | off |
| PD7 | Reward currency | stock Event Trophy 4000038, untradeable by server rule (a Custom.wz item later is optional) |
| PD8 | Champion medal | stock 1142077, held for 7 days |
| PD9 | Losing a rival challenge | costs nothing; the crew brags |
| PD10 | Balance priors | TTK 12 s, gear exponent 0.7, cap 25%, half Magic Guard; tuned afterwards from the sims |
| PD11 | D14 | replaced: story-arc capstones may become real duels after V2 |
| PD12 | Tournament day and hour | needs your time zone and evening |
| PD13 | Spectator betting | yes, with casino chips and a 10% house cut |

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
4. **Level gap.** Damage changes 1% per level of difference, capped at ±5% (was 2% / ±10% until practice run seven,
   2026-11-09). A hit is scaled to the victim level's reference HP, not the attacker's (step 2b), so this is the only
   level effect: before, the level's HP was counted twice and a 3-5 level edge won 79% of practice duels.
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
    `PVP_MAX_HIT_PERCENT` 25, `PVP_AOE_FALLOFF` 0.15, `PVP_LEVEL_PERCENT` 1 (2 until run seven), `PVP_DEF_K` 40,
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
- **PD16, decided by the user 2026-11-09: learn the rates live.** `PVP_LEARN_RATES` (on): after every duel, each
  fighter with 15 s of fighting and 5 attacks (players and bots alike) reports its efficiency, raw damage per
  second over the reference rate for its job path, band and gear. `server.pvp.PvpRateLearner` keeps a running mean
  of its log per class (step `PVP_LEARN_CLASS_STEP` 0.05, the table worth 5 fighters at the start) and per job path
  and band (step `PVP_LEARN_JOB_STEP` 0.1, starting from its class, used from `PVP_LEARN_JOB_MIN_FIGHTERS` 8). One
  fighter counts as 1/3-3x at most and moves a mean by at most 0.2 in log (a Huber step, near the median: on runs
  five and six Thieves' median efficiency was 0.85 but their plain log mean 0.75, pulled down by Assassins without
  stars); a learned rate stays within 0.4-2.5x of the table's. `PvpDamageModel.rate` =
  the table's rate x the learned multiplier, for the coefficient and the sim. Kept in `logs/pvp/learned-rates.txt`
  (untracked); `!pvp learned` shows it, `!pvp learned reset` clears it, `!pvp learned replay <csv>` seeds it from a
  duel CSV in logs/pvp. No luck was added: if the leftover edge still decides most duels, revisit.
- **PD16 options as put to the user (2026-11-09, from practice runs five and six):** how class win rates reach 40-60%.
  - *Learn the rates (recommended):* after every duel, nudge the attacker's `d_job` / class rate toward its measured
    raw rate (a slow running median, players' duels included), so the rates follow whoever actually fights instead
    of one run's sample. Bounded steps, written back to a `learned` section and logged; the reference file stays the
    prior. This is the plan's own rule (learn, don't script).
  - *More luck:* widen each attack's roll or add rare big swings, so a 1.2x edge wins about 60%, not 90%. Cheap,
    but it makes skill and gear count less too.
  - Both together are possible: learning closes the class gap, a little luck keeps a small leftover edge from
    deciding every duel.
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
  - **Fifth practice run (1e2e2b18b, 2026-11-09 11:19-11:50, 87 duels, 174 rows):** 85 knock-outs, 2 on time; no
    errors. (Its first round, 90 s after boot, found no free citizens.) Swings 1.00-1.25 a second for every class.
    - Combo orbs work: Lv 87 and 97 Crusaders dealt 0.5-1.1 of the model (from 0.04-0.06 in run four); their
      remaining `plan:combo-orbs` refusals are the thinks spent building orbs, as a player's are. No Aran this run.
    - Measured over model: warrior 0.64 (n=32), magician 0.70 (33), bowman 0.90 (22), thief 1.04 (44), pirate
      0.56 (11). Knock-outs: median 23 s (p25 16, p75 44), against the 12 s target.
    - **Calibrated from this run** (`scripts/config/pvp/reference.yaml`): `class_dps_per_base` for all five classes
      (warrior 2.93, magician 55.9, bowman 4.15, thief 4.76, pirate 2.56; from 4.6 and 80), and `d_job` for the 18
      job-path bands with n >= 5. The earlier runs are left out: each had a bot handicap this one no longer has.
      Rows with n < 5 (and every Lv 100+ band, Bishops included) stay on the class rate until more duels come in.
    - Next: a sim matrix on the calibrated reference and a sixth practice run to see knock-out times and class-pair
      win rates against the done-when.
  - **Sixth practice run (3fb43d5f0, calibrated, 2026-11-09 12:16-12:47, 98 duels, 196 rows):** 87 knock-outs, 10 on
    time, 1 forfeit; no errors.
    - Knock-outs: median 19 s (p10 8, p25 11, p75 31, p90 45): inside the 10-25 s window.
    - Cross-class wins: magician 32-3 (91%), thief 8-6, bowman 8-16, warrior 4-29 (12%), pirate 2-0. Magicians beat
      warriors in 19 of 21. Level barely decided (the higher won 39, the lower 34, equal 14).
    - Raw damage over the calibrated rate, per class (the coefficient's own rate: d_job, else class rate x gear
      median): warrior 0.71 (n=37, p25 0.42, p75 0.76), magician 0.99 (59), bowman 0.86 (20), thief 0.77 (46),
      pirate 1.53 (4). Run five's fighters set the rates; run six drew a different mix of citizens, and a class's
      median moved by up to 30% (warriors most: Fighters, Pages and Spearmen at many levels).
    - The sim matrix cannot show this: its raw rate and its coefficient share one rate, so it cancels and only gear,
      hit chance, defense and level are left. At Lv 90 its warriors won 0-21% against bowmen, thieves and magicians
      on hit chance (low accuracy against high avoid); live, warriors landed 84-96% of attacks.
    - **PD14 confirmed:** a duel has so little luck that a 1.4x damage edge becomes a 90/10 split. A one-off
      calibration cannot hold every class within a few percent across citizen samples, so the 40-60% done-when needs
      either rates that keep learning from every duel, more luck per duel, or both (decision PD16, below).
  - **Seventh practice run (4e42d5896, learned rates seeded from runs five and six, 2026-11-09 13:49-14:20, 108
    duels, 216 rows):** 100 knock-outs, 8 on time; no errors. Learned class rates at the start: warrior 0.75,
    magician 0.95, bowman 0.86, thief 0.80, pirate 1.02 (each near its median); at the end 0.74 / 1.02 / 0.87 / 0.78 /
    1.04.
    - Knock-outs: median 19 s (p10 7, p25 11, p75 26, p90 54).
    - Cross-class wins: warrior 9-24 (27%), magician 24-24 (50%), thief 16-8, pirate 10-1, bowman 2-4. Magicians beat
      warriors 16 of 22 (from 19 of 21), but in 11 of the 16 the warrior was 2-4 levels lower; at equal level
      warriors won 5 of 8 against magicians, and both sides dealt about 1.0 of their learned rate.
    - **Level decided too much:** a 1-2 level edge won 67%, a 3-5 level edge 79% (done-when: +5 at most 65%). Cause:
      the coefficient is set for a victim of the attacker's level, so against a lower victim the level's HP counted
      twice (the attacker's reference HP and the victim's own), on top of step 4's 2% a level; a 4-level gap knocked
      out about 1.3x faster. Fixed: a hit is scaled by the victim level's reference HP over the attacker level's
      (`PvpDamageModel.levelHpShare`, in the duel and the sim alike), and step 4 is 1% a level capped at 5%
      (`PVP_LEVEL_PERCENT` 1, `PVP_LEVEL_CAP_PERCENT` 5). Expected: about 1.1x at +5, near the 65% ceiling.
    - Next: an eighth practice run, for the level edge and equal-level class pairs.
  - **Eighth practice run (ad0d1fa11, level counted once at 1%/5%, 2026-11-09 14:50-15:20, 91 duels, 182 rows):** 86
    knock-outs, 4 on time, 1 forfeit; no errors. Knock-outs: median 17 s (p25 12, p75 24).
    - **Level edge fixed:** a 1-2 level edge won 54%, a 3-5 level edge 64% (done-when: +5 at most 65%).
    - Cross-class wins: warrior 5-35 (12%), magician 16-9, bowman 15-7, thief 23-11, pirate 4-1. Warriors beat
      magicians 3 of 7 but thieves 1 of 19 and bowmen 1 of 10; at a gap of one level or less they went 1-9.
    - Both sides dealt about their learned rate (0.9-1.0). The gap is dodges: warriors' attacks were dodged 25-29%
      of the time by bowmen, thieves and pirates, and 4% by magicians and warriors; nobody dodged a warrior, and no
      class whiffed on position (0%). Physical hit chance pits the attacker's accuracy against the victim's avoid,
      both mostly DEX and LUK, so a warrior sits at the 75% floor against every evasive class. The learned rates
      cannot absorb it: raw damage is counted before the dodge, and the dodge rate depends on the opponent's class.
    - **Open decision PD17:** how avoid counts under even classes. (A, recommended) even it out like HP: each
      fighter's accuracy and avoid are measured against its own class's median at its level, so an Assassin with
      better avoid than other Assassins still dodges more, but the class's base stats no longer decide; (B) no dodge
      roll in duels at all (Dark Sight aside); (C) keep it, evasive classes as a class trait (warriors stay at about
      one win in eight against them).
    - **PD17 decided (the user, 2026-11-09): A, even it out by class.** `server.pvp.PvpHitBalance` takes, per level
      band, each class's median accuracy (magic accuracy for magicians) and avoid from the characters online (GMs
      left out, rebuilt every 10 min; `!pvp calibrate` rebuilds and prints it), and divides it by the band's mean of
      class medians. Under `PVP_EVEN_CLASSES`, a duel hit multiplies the attacker's accuracy and the victim's avoid
      by 1 over their class's ratio, so a median fighter of every class has the band's median accuracy and avoid;
      gear above the class median still counts (1.5x a thief's median avoid still dodges as 1.5x). A class and band
      with fewer than 3 characters takes its nearest band's ratio; a band with fewer than two such classes gives
      none; factors are clamped to 0.25-4x (wide, since a warrior's avoid can sit at a quarter of a thief's).
      `!pvp sim` builds its fighters the same way. The miss caps stay (physical 25%, magic 10%), so a magician keeps
      a small edge when the evened chance falls under 90%; practice round nine checks whether that matters.
    - **Practice round nine (7ebf2a054, 2026-11-09 19:47-20:17, run headless over the admin port; 108 duels, no
      errors).** `!pvp calibrate` read 154 characters: a warrior's median accuracy sits at 0.30-0.56 of its band's
      class-balanced median and its avoid at 0.31-0.49; bowmen's accuracy at 1.42-1.60; magicians' avoid at
      0.34-0.77 too. Every factor fits the 0.25-4x clamp. Results (same tally script on round eight for comparison):
      - warriors' attacks dodged 2% (round eight 21%); no class above 4%;
      - cross-class record: warrior 20-22 (48%, round eight 6-37), magician 27-26, bowman 17-17, thief 10-5, pirate
        4-8 (thieves and pirates on 15 and 12 duels only); warrior against magician 13-13, against bowman 6-5;
      - level edge: the higher level won 54% at a 1-2 level gap (35 duels) and 44% at 3-5 (55), under the 65% cap;
      - 100 of 108 ended in a knock-out (8 forfeits), median 18 s, middle half 13-23 s, inside 10-25 s;
      - no sign of a magician edge from the 10% magic miss cap (51%).
      Open for the finish line: one round spreads 108 duels over 10 class pairs and several bands, so the per-pair,
      per-band 40-60% check (Lv 50, 80, 110) needs a longer run; the two filmed duels need a logged-in client.
    - **Long run (7ebf2a054, 2026-11-09 20:44 to 11-10 01:44, 90 rounds headless; 1,080 duels, no errors).** Round
      nine's balance did not hold. Cross-class: magician 289-88 (77%), bowman 61%, pirate 55%, warrior 31%, thief
      28%; counting each distinct pairing once (319 pairings) gives the same picture (magician 76%, warrior 30%).
      Knock-outs median 16 s (11-23 s); the higher level won 54% at a 1-2 gap and 61% at 3-5; dodges 2-3% for all
      but pirates (10%).
      - Pairings repeat: practice pairs whoever shares an empty map, so the same two citizens met up to 22 times, and
        91 of 151 pairings fought 3+ times went 90%+ one way. With so little luck in a duel (PD14), a 15-20% edge in
        throughput decides nearly every meeting of two given fighters.
      - Per job, the share of a typical opponent's HP removed per second against the 1/12 target: the three magician
        lines 0.079-0.089 and gunslingers 0.105, but pages 0.059, fighters 0.069, brawlers 0.050, assassins 0.071,
        and crossbowmen 0.199.
      - **Cause 1, the learner's filter:** a row counted only with 15+ engaged seconds, above the 12 s target duel, so
        fighters who won or lost fast were never learned: 56 of 59 crossbowman rows were skipped. Fixed (ddaad2b5b):
        5 s and 5 attacks.
      - **Cause 2, ammo:** assassins found no usable attack on 36% of their thinks (`plan:ammo` refusals) and waited
        5.8 s before their first throw (0.7-2.3 s for every other job); citizens short of stars measure the gap, not
        the job. Fixed in the learner and `!pvp rates`: a fighter with no usable attack on more than 30% of its thinks
        does not count. Restocking stars is a citizen supply matter, outside PvP.
    - **Check run (55a064af7, 2026-11-10 02:16-04:13, 36 rounds; 413 duels, no errors)**, learned rates reset and
      replayed from rounds five to nine plus the long run (2,792 fighter rows; class rates warrior x0.68, magician
      x1.08, bowman x0.88, thief x0.76, pirate x1.10). Cross-class: magician 74%, bowman 56%, pirate 43%, warrior
      41%, thief 22%; level edge 48% at 1-2, 61% at 3-5; knock-outs median 16 s.
      - Offense is normalized: landed damage per engaged second over the target (a typical opponent's HP in 12 s)
        is warrior 1.02, magician 0.98, thief 1.02, bowman 0.91, pirate 0.77; landed per raw damage is flat across
        victim classes, so defense and the class HP ratio are not it.
      - **Cause 3, a heal leak:** a knocked-out fighter had absorbed, over its class's typical max HP, warrior 1.07,
        bowman 1.31, thief 1.37, pirate 1.37, magician 1.66 (cleric 1.82). The allowance permits at most about 1.3 in
        a 16 s duel. Every landed hit reset the heal baseline to the victim's HP, so a potion or Heal between a tick
        and a hit passed uncounted. Fixed: a hit runs the allowance check on the victim first.
    - **Check run on that fix (f4c8fe579, 2026-11-10 04:45-06:47; 297 duels, no errors).** Unchanged: magician 83%
      cross-class (75% by distinct pairing), warrior 43%, bowman 49%, thief 6%, pirate 16%; level edge 54% at both
      gaps; knock-outs median 15 s. Against each fighter's own stored max HP (characters table), a knocked-out
      magician had absorbed 1.59x (p75 2.26), bowman 1.29x, warrior 1.02x, thief 1.05x, pirate 1.03x; fire/poison
      and ice/lightning mages (no Heal) as much as clerics.
      - **Cause 4, the real leak:** a bot's loss goes through `addMPHPAndTriggerAutopot`, so its autopot drinks
        inside the hit, after the damage, and the HP after that drink became the new baseline: the drink passed whole,
        an Elixir included. Low-HP classes trip the autopot most. Fixed: after the loss the baseline is what the loss
        left, then the allowance check runs.
    - **Check run on that fix (3fca0d6c2, 2026-11-10 07:32-09:33; 401 duels, no errors).** The leak is closed: a
      knocked-out fighter had absorbed, over its own max HP, warrior 1.11, magician 1.25, bowman 1.23, thief 1.24,
      pirate 1.22 (max 1.63), within the allowance. Cross-class, leaving out the 20 duels with a fighter that found no
      usable attack on more than 30% of its thinks (all with a thief): warrior 45% (44% by distinct pairing),
      magician 60% (60%), bowman 58% (55%), thief 42% (34%, 18 pairings), pirate 25% (44%, 13 pairings). Level edge
      43% at a 1-2 gap, 61% at 3-5; knock-outs median 14 s (11-16 s).
      Still open for the finish line: the per-band, per-pair check (Lv 110 rarely meets a same-map opponent in
      practice), thieves and pirates on small samples, and the two filmed duels.
    - **Filming (2026-11-10, Crewfriend hidden as the camera; clips in `docs/agents/evidence/pvp-v2-film/`).**
      First takes in Henesys found two tells, both fixed and live: a dueling Ranger bragged in public chat about
      its weapon (2dd4fc832: a citizen in a duel says nothing), and an Assassin threw stars from a ladder for
      about 6 s (c37de6fb4: a duel bot never swings from a rope or ladder; it may still climb to reach its foe).
      The camera missed most of those fights, so the takes were redone on c37de6fb4 with fighters picked on one
      floor of a town map and the camera walking after one of them:
      - `duel-mage-v-thief-elnath.mp4`: xTiax (Fire/Poison Wizard, Lv 100) against BunnyCleric (Hermit, Lv 99),
        23 s; the Hermit won.
      - `duel-melee-v-ranged-kerning.mp4`: EvilPirate (Dragon Knight, Lv 112) against WainTheSader (Sniper,
        Lv 110), 29 s; the Dragon Knight won (the same pair's earlier take went to the Sniper in 14 s).
      Seen in both: hits land with purple numbers on both sides, and the fighters say nothing. A possible tell:
      once in range neither fighter moves again; they trade hits standing still (the Dragon Knight fought from
      about 230 px with Dragon Roar, standing on top of an NPC, and never closed to spear range). Filming
      artifacts, not tells: the GM's own "PvP duel N starts" line in chat, Crewfriend's companions in frame.
    - **Footwork (user: "Yes, add footwork"; 489403632 + be45c53dc, LIVE 2026-11-10 18:50; suite 16,309 / 0 / 33).**
      Every 1.8-4.2 s a bot in reach steps or hops instead of its next swing. A step goes to a spot 35-85% of its
      last swing's reach from the foe, on its own side and level, at least 40 px away (half the band, 20-40 px, for
      a short melee reach), so a crowded ranged fighter backs off. One footwork in three is a hop: in from far, out
      from near; a short reach may hop over its foe; never off its level or out of reach. Eight practice duels: 15
      of 16 fighters stepped (up to 5) or hopped (up to 2); duels ran 14-25 s (median ~21 s, was 14 s), since steps
      sometimes leave reach and both walk back in; the learned rates should pull that back toward 12 s.
      Refilmed in Free Market rooms (the whole room fits on screen, no monsters):
      - `footwork-melee-v-ranged-fm.mp4`: DarkMuffin (Hunter, Lv 64) against Neus (Page, Lv 63), 26 s; the Hunter
        won. The Hunter came down a ladder without shooting, backed off as the Page pressed, and hopped away.
      - `footwork-mage-v-thief-fm.mp4`: xFanx (Priest, Lv 60) against Anvith (Assassin, Lv 57), 13 s; the
        Assassin won, backing off about 180 px with a hop while the Priest pressed in.
      Seen while filming, for the per-band check: a Lv 110 Sniper knocked out a Lv 108 Crusader in 6 s (Strafe on
      flat ground, about 20,000 raw a swing, against about 3,600 a swing when its foe stood on a raised pipe), while
      the Crusader swung 3 times (its strongest skills wait on combo orbs).
      **Review (user, 2026-11-10): the two footwork clips pass.** That meets the filmed-duels item of the finish
      line; the per-band, per-pair win rates and the +5-level edge remain.
    - **Check run on footwork (be45c53dc, 2026-11-10 19:54-23:55; 1,152 duels, no errors; `logs/pvp/
      duels-run14-be45c53dc.csv`).** Knock-outs median 14 s (p25 12, p75 18): the learned rates took back the
      footwork's slowdown within the first half hour. Level edge 54% at a 1-2 gap, 62% at 3-5, 63% at exactly 5
      (finish line: at most 65%, met). Cross-class: warrior 39%, magician 68%, bowman 49%, thief 43%, pirate 31%;
      footwork moved warriors down (45%) and magicians up (60%). Per band, 3 of 19 class-pair cells with 20+ duels sit
      inside 40-60%; practice pairs the same two citizens again and again with their own gear, so a cell is mostly
      one pairing, and only 48 duels had both sides' gear within 25%, too few for an equal-gear table.
      - **Why magicians:** per engaged second, as a share of the victim's own max HP, magicians deal 7.8-8.6%,
        warriors 6.2-8.9% (7.4% against magicians, against 8.3% the other way), pirates 5.0-13.1% (small n). Not a
        head start: the first attack comes 1.7 s in for warriors, 1.9 s for magicians (3.7 s for thieves). The
        learned rates even out raw damage, which is rolled before defense; the defense step then cuts a weapon hit
        by the victim's WDEF and a spell by its MDEF (`def / (def + 40 x level)`, at most 25%), and most gear
        carries far more WDEF than MDEF, so spells keep several percent more of every hit. In near-deterministic
        fights that is enough for 68%.
      - Also seen: pirates' attacks are dodged 11% of the time (others 2-3%), and thieves start late (stars).
    - **Defense evened (user: "Even defense by class"; d35dda8d3, LIVE 2026-11-11 04:55).** PvpHitBalance also takes
      each class's median WDEF and MDEF per band; a median victim meets weapon hits and spells with the band's
      common defense (the mean of the two). Check run 15 (stopped after 346 night-time duels, `duels-run15-d35dda8d3
      .csv`): magician 69%, warrior 38%; warriors won 7 of 60 against magicians. The rate gap stayed (magician on
      warrior 8.4% of max HP a second, warrior on magician 7.2%), and a second cause showed: a knocked-out magician
      had absorbed 1.31x its max HP, a warrior 1.16x. Magicians fill the heal allowance (Heal; a potion is a big
      share of a small pool); a bot's autopot drinks only under its own alert.
    - **Bots fill the allowance (user: "Bots fill the allowance"; c7dbf88e4, LIVE 06:33).** Each think a bot at or
      under 90% HP reaches for the potion its autopot would pick, about once a second. Check run 16 (06:39-10:40,
      997 duels, `duels-run16-c7dbf88e4.csv`): warrior 49%, magician 63%, bowman 39%, thief 48%, pirate 35%;
      knock-outs median 14 s; level edge 51% at 1-2, 61% at 3-5, 65% at exactly 5; per band 6 of 15 class-pair
      cells with 20+ duels inside 40-60% (was 3 of 19). Rates are close now (7.2-8.6% of max HP a second in every
      cell with 100+ duels) but absorbed HP is not: magician 1.35x, thief 1.27x, warrior 1.02x, bowman 1.05x.
      59% of warriors never got an HP drink in (magicians 21%): HP and MP potions shared one 5 s window, and a
      warrior's MP autopot drank every few skills. Fixed in b3b09fc72: each resource has its own window.
    - **Runs 17-19 (2026-11-11).** Separate windows did not help (run 17: warriors still 1.00x): at 90% HP the
      chooser picks a potion sized to the gap, a 300 HP White Potion for an 8,700 HP warrior, and that drink shut
      the window on the rest. fbadc6063 gates potions on the allowance itself (a drink passes while the allowance of
      what it restores is at least half full). Run 18 still showed warriors at 1.01x; drink counters (fbf9a2b7d)
      showed why: 18 of 56 warrior and 11 of 14 pirate fighters had no HP potion on any try. Potions heal flat
      amounts, so filling a % allowance costs a big pool three White Potions a window to a magician's one, and
      practice pairs the same citizens every 2.5 minutes. Dry warriors absorbed 1.01x, supplied ones 1.29x.
      edb7ce446: practice skips a citizen whose HP potions heal less than its max HP. Citizen side (21% of warrior
      citizens carry no HP potion, magicians 1%): own session, branch `fix/hp-potion-restock` 74f18ab72 (a full Use
      tab blocked new potion stacks), not merged.
    - **Check run 20 (edb7ce446, 2026-11-11 13:37-17:35, 857 duels, `duels-run20-edb7ce446.csv`).** No errors.
      Rounds skipped 14-35 dry citizens and started 3-12 duels. Healing is even now: absorbed HP before a knock-out
      warrior 1.34x, magician 1.43x, bowman 1.38x, thief 1.37x, pirate 1.45x. Classes are not: warrior 39%,
      magician 64%, bowman 44%, thief 42%, pirate 60% (80 duels); warrior against magician 32-83, 0-13 at equal
      level. Knock-outs median 17 s; level edge 61% at 1-2 and at 3-5; per band 2 of 9 cells with 20+ duels inside.
      The gap is on the victim side: every attacker class takes 8.3-8.6% of a warrior's max HP a second and 7.0-7.5%
      of a magician's, at the same landed hits a second (0.91 against 0.90), each hit 8.8% of a warrior's real HP
      against 7.5% of a magician's. Three parts found:
      1. `class_hp_ratio` is fixed and measured on base HP (`getMaxHp`, 11-08); the fighters carry 0.98 of the HP it
         assumes for warriors and 1.05 for magicians (base + equipped HP), about 7%.
      2. Weapon hits on magicians are dodged 5.8-8.8% (every magician line, not only Bless casters), on warriors 1-5%,
         on thieves 0-1.5%: the avoid evening comes out high for magicians at duel time; cause not found.
      3. Warriors' MDEF ratio is 0.17-0.24 of the band's common defense in four bands, past the 4x factor clamp, so
         spells still meet less defense on a warrior than intended (bowmen and thieves too).
    - **PD18 decided (the user, 2026-11-11): learn toughness** (over fixing the three pieces one by one).
      `server.pvp.PvpToughness`: each hit adds to its victim what it would have taken of a reference victim (class HP
      ratio x H_ref, no defense, never dodging; dodged attacks count) and what it took of the victim's own max HP with
      the learned toughness divided out; after the duel a victim whose hits came to half a reference HP reports
      log(reference / own), and its class's and job path and band's running means move toward it with the learned
      rates' steps, priors, Huber cap and 0.4-2.5x limits. Hits on a victim are multiplied by its mean; the sim too.
      Switch `PVP_LEARN_TOUGHNESS` (with PVP_EVEN_CLASSES), file `logs/pvp/learned-toughness.txt`, the duel CSV gains
      `neutral` and `bare`. The reference pace replaces each class's own: typical defense and dodges no longer slow a
      duel, so knock-outs should move from 17 s toward the 12 s target times the heal allowance.
    - **Check run 21 (15d3e9963, 2026-11-11 20:44 - 11-12 00:47, 910 duels, `duels-run21-15d3e9963.csv`).** No
      errors. Learned toughness by the end: warrior x1.11, magician x1.23, bowman x1.11, thief x1.14, pirate x1.13
      (bands vary). Last two hours (422 duels, the learner settled): warrior 49%, magician 47%, bowman 62%, thief
      46%, pirate 79% (14 duels); warrior against magician 13-16 (whole run 57/46/62/45/47). Every pairing with 25+
      duels now takes 8.6-9.4% of the victim's max HP a second (run 20: 7.0-8.6). Knock-outs median 14 s; level
      edge 48% at 1-2, 59% at 3-5. Absorbed HP warrior 1.25x, others 1.36-1.41x (a fighter going down faster has
      less time to heal). Per band, counted one vote per distinct pairing (practice repeats the same two citizens:
      the Lv 100s warrior-magician cell is 42-17 plain from 14 pairings, 51% by pairing): 5 of 13 cells with 5+
      pairings inside 40-60%; most cells rest on 5-7 pairings whose own gear and level decide them, so the cell
      criterion is not testable at equal gear from practice. `!pvp sim matrix` (model only, 0-14 characters a class
      online at night) disagrees with practice where reach matters (thief over magician 71% at Lv 80) and is not
      used to judge.
    - **Equal-gear run 22 (user: "Equal-gear run"; 15d3e9963 with `PVP_GEAR_EXPONENT` 0.05 in the live checkout's
      config.yaml, uncommitted, 2026-11-12 10:06-14:13; put back to 0.7 and restarted 14:22; 765 duels,
      `duels-run22-equalgear-15d3e9963.csv`).** No errors. Duels where an Assassin's top refusal was `plan:ammo` left
      out (15; the star supply fix is not merged). Cross-class: warrior 40%, magician 47%, bowman 64%, thief 57%,
      pirate 50%; warrior against magician 60-62, against bowman 24-66. Level edge 53% at 1-2, 64% at 3-5. Cells
      with 5+ distinct pairings: 3 of 10 inside 40-60% (Lv 110s warrior-bowman 29% over 10 pairings). So bowmen's
      lead is not their gear. Damage a second: bowman on warrior 9.2% of max HP, warrior on bowman 7.2%, while
      warrior and magician trade 8.5 / 8.3. Warriors swing as often at bowmen (0.73 attacks a second against 0.76 at
      magicians) but roll less raw per attack within one job and band (Lv 110s Crusaders 5,993 against bowmen, 8,529
      against magicians): the bowman backs off when crowded and the warrior swings at the edge of its reach, likely
      with its longest-reaching skill rather than its strongest (not yet measured). Second, smaller: measured
      toughness against weapon hits runs above that against spells for bowmen 1.18 / 1.10, thieves 1.15 / 1.08 and
      pirates 1.14 / 1.03, which one toughness per class averages.
    - **Melee against ranged (user: "Fix melee vs ranged").** Counters (61c63c7d1, LIVE 14:52 11-12; 288 rows):
      warriors swung their strongest skill on 86% of swings against magicians, 100% against thieves, 33% against
      bowmen. By job: the Fighter line (11x) 0 of 57 against bowmen, at 120 px with the strongest reaching nothing
      (plan refused `combo-orbs`), 53% against magicians at 81 px; Pages and Spearmen 88-100%. Cause: the stock orb
      gain skips Shout (`CloseRangeDamageHandler.gainOnHit`, skill 1111008), and a bowman keeping 160-380 px away
      left a Crusader only Shout in reach, so it never built an orb for Panic or Coma. Fixes:
      - **Shout waits for orbs:** while Combo Attack is up and a finisher waits on orbs, a Crusader holds Shout and
        its walk and footwork follow its orb-building swings (`DuelBrain.holdsShout`).
      - **Toughness by attack kind:** spell hits learn under their own keys (`c1m`, `11-8m`), starting from the
        class's plain value (9c8e5695d); CSV gains `neutralSpell`, `bareSpell`.
    - **Check run 24 (a88a49647, LIVE 2026-11-12 ~17:45, run to 21:50, 909 duels, `duels-run24-a88a49647.csv`).** No
      errors; starless-Assassin duels left out. Whole run cross-class: warrior 45%, magician 45%, bowman 54%, thief
      58%, pirate 59% (37 duels), every class inside 40-60%. Warrior against bowman 41-35 (equal-gear run 22: 24-66),
      against magician 54-77, against thief 19-19. Last two hours: 40 / 46 / 60 / 60 / 68 (pirate 22 duels). Level
      edge 51% at 1-2, 57% at 3-5. Knock-outs median 14 s. Crusaders' strongest-skill share against bowmen 17% (was
      0%), swinging from 95 px (was 120). Damage a second, last two hours: warrior and magician 9.0 / 8.9% of the
      other's max HP, warrior on bowman 8.7 against 9.5 the other way (was 7.2 / 9.2); thief on magician 10.3 against
      8.7. Learned toughness weapon / spell: warrior 1.15 / 1.12, magician 1.19 / 1.13, bowman 1.09 / 1.10, thief 1.11 /
      1.08, pirate 1.09 / 1.05. Healing still differs a little: absorbed before a knock-out warrior 1.30x, magician
      1.40x, others 1.33-1.35x. Cells with 5+ distinct pairings: 5 of 10 inside 40-60%; Lv 80s warrior-magician 15%
      over 6 pairings (run 22: 14%) and Lv 100s warrior-bowman 65% over 12 the outliers.
    - **Warrior healing (user: "Even warrior healing"), 2026-11-13: no gap; my measure was wrong.** Absorbed HP had
      been divided by `characters.maxhp` (base HP), while a duel runs on base plus equipped HP, and gear adds 8% to a
      magician's HP against 2% to a warrior's. On real HP, run 24's knocked-out fighters absorbed warrior 1.27x,
      magician 1.29x, bowman 1.27x, thief 1.30x, pirate 1.30x, each using 75-80% of the heal allowance, with no drink
      try short of a potion. No code change. The damage-a-second tables had the same error; on real HP, warrior and
      magician knock each other out in the same time when they lose (13.7 s and 13.6 s), raw output against each
      other is 1.05x their own average both ways, toughness has converged (measured weapon / spell warrior 1.14 /
      1.13, magician 1.21 / 1.18, matching the learned values) and warriors carry 1.22x their job's median gear to
      magicians' 1.00x. What stays: warrior against magician 54-77 over 131 duels in run 24 (60-62 at equal gear),
      about two standard errors from even; per engaged second the magician leads (9.3% of a warrior's real HP against
      8.1%), since a caster starts hitting from range while the warrior is still walking in. Scratchpad heal.py and
      melt_real.py measure on real HP.
    - **Warrior against magician, dug into (user: "Dig into it"), run 24's 131 duels.** Per duel second the magician
      takes 8.5% of a warrior's real HP, the warrior 7.1% of a magician's (1.20x). The parts, none large alone:
      landed hits a second 0.73 against 0.67 (the magician opens at 1.1 s against the warrior's 1.6 s, and weapon
      swings are dodged 6.2% against 2.2% for spells), and each hit 11.9% of real HP against 10.5%. By the model's own
      normalization (scratchpad efficiency.py: raw a second over prior x gear ratio x learned rate) magicians run at
      1.12x their learned rate against warriors but 0.95-0.99x against bowmen and thieves, warriors 0.99x against
      magicians; warriors' gear above the job median (G^0.7 1.11x) gives some of it back. So the gap is a pairwise
      effect: per-class learning (rates by attacker, toughness by victim) cannot see what one class does to one
      other class.
    - **PD19 decided (the user, 2026-11-13): learn matchups** (over calling V2 done). `server.pvp.PvpMatchups`:
      each hit adds to its attacker's pace what it took of the victim's max HP with the matchup correction, the
      attacker's gear edge (`G^gamma`, `PvpDamageModel.gearEdge`) and the level factor divided out, so gear and
      levels keep helping a fighter and are not taken for the class pair's. After a one-against-one duel of two
      classes in which both fighters really fought (`DuelLog.measurable`), the pair's running mean of
      log(lower class's pace / higher class's) moves toward the duel's value, clipped to 1/3-3x, with the class step,
      prior (5 duels), Huber cap and 0.4-2.5x limits of the other learners; one key per class pair (`p12` = warriors
      and magicians), not per band. Once a pair has 5 duels, the lower class's hits on the higher are multiplied by
      `exp(-m/2)` and the higher's on the lower by `exp(m/2)`: the pair's two paces meet, their shared pace stays.
      Toughness's bare share now divides the matchup out too, so neither learner feeds on the other. Switch
      `PVP_LEARN_MATCHUPS` (with PVP_EVEN_CLASSES), file `logs/pvp/learned-matchups.txt`, the duel CSV gains `pace`,
      `!pvp learned` lists the pairs and `reset` clears them; the sim applies them. Not divided out: a victim's own
      HP, defense and avoid above its class's typical, so a pair whose victims are geared unevenly keeps a little of
      that in its mean.
    - **Check run 25 (aa0c4c722, 2026-11-13 07:20-11:40, 563 duels, `duels-run25-aa0c4c722.csv`; 546 after dropping
      duels with a starless Assassin).** Rounds started 5-9 duels (22-35 citizens a round skipped for too few HP
      potions). Learned by the end: p12 x1.07 (60 duels), p23 x0.94 (94), p24 x1.01 (75), p14 x1.02 (30), p13 x1.03
      (22), the rest under 20 duels. Cross-class: warrior 56%, magician 41%, bowman 53%, thief 55%, pirate 61%
      (44 duels); last two hours magician 37%. Warrior against magician 45-25 (run 24: 54-77), but by gear: where the
      two sides' gear edges sit within 5%, magicians won 10 of 12; where the warrior's is over 5% higher, warriors
      won 33-10. Among non-Aran pairs the gear-neutral pace ratio's median is about 0.81 (x1.11 each way), so the
      learned x1.07 is still short. Knock-outs median 14 s. Level edge 52% at 1-2, 70% at 3-5, but 3-5 with the
      two sides' gear within 10% is 66% (run 24: 63%): the jump is repeated pairings in which the higher level also
      carries better gear (77 of 80 won). One error burst 10:13:09-10:14:01 (about 80 failures, then none):
      `AbstractDealDamageHandler` applies Aran Snow Charge's slow whenever the attacker has the weapon-charge buff,
      and a grinding Aran without Snow Charge hit `getEffect(0)`; it began 3 s after the Aran SilentRanger (no Snow
      Charge) left duel 410, not proven to be it; fix: `isBuffFrom(WK_CHARGE, snowCharge)` as the charges below do.
      Two findings:
      1. **The gear medians are stale.** `reference.yaml`'s gear_base (11-08) puts warriors' typical gear edge at
         x1.15 (median x1.17) and thieves' at x1.16, magicians' x1.04, bowmen's x1.00: warriors and thieves have
         geared up since, so a typical warrior or thief deals about 12% more than a typical magician or bowman,
         which no learner corrects (the rates' efficiency divides gear out, the matchups do too).
      2. **Arans barely fight.** Their rows roll 404 raw a second against 6,422 for other warriors, and the Aran
         combo plan is the top refusal in 32 of 52 rows; they count as class 1, so they pull every warrior number.
    - **PD20 decided (the user, 2026-11-13): refresh the gear medians** (before the Aran fix).
      `server.pvp.PvpGearMedians`: the median gear base per job path and band of the characters online (GMs left
      out), refreshed every 10 minutes like the hit balance; a key with fewer than 3 characters uses the reference
      median times its class's live median ratio, else the reference median. `PvpDamageModel.typicalGear` feeds it
      to the coefficient, which now sets the median attacker's rate at today's gear (reference rate x live / reference
      median) and measures G against the live median, and to the gear edge the matchups divide out; the reference
      medians still anchor `d_job` and the learned rates' efficiency, so nothing learned changes meaning. A typical
      fighter of every class then hits at the reference pace, and gear above today's median still helps. Switch
      `PVP_LIVE_GEAR_MEDIANS`; `!pvp calibrate` prints each class's live ratio. Also: Snow Charge's slow in
      `AbstractDealDamageHandler` now needs the charge to come from Snow Charge (`isBuffFrom`) and a skill level.
    - **Check run 26 (67b1b9cb4, 2026-11-13 12:42-16:47, 832 duels, `duels-run26-67b1b9cb4.csv`; 819 after
      starless Assassins).** No errors, no Snow Charge failures. Live gear over the reference medians (`!pvp
      calibrate`, 190 characters): warrior x1.05, magician x0.95, bowman x1.04, thief x1.09, pirate x0.93 (the
      practice fighters ran higher than the population: run 25's warriors sat at 1.22x). Cross-class: warrior 56%,
      magician 46%, bowman 63%, thief 47%, pirate 16% (56 duels). Warrior against magician 68-60. Level edge 59% at
      1-2 and 56% at 3-5; 58% at 3-5 with the two sides' gear within 10%. Knock-outs median 14 s. Learned matchups
      by the end: p12 x1.04 (169 duels), p13 x1.06, p14 x0.96, p23 x1.02 (172), p24 x1.05 (169), the rest under 40.
      One vote per distinct pairing (the lower class ahead): p12 57% (38 pairings), p13 43% (23), p14 62% (20), p24
      57% (26), p23 28% (19); the pirate cells have 1-7 pairings. Findings:
      1. **Practice repeats pairings.** A class pair's duels come from 10-40 distinct pairings, and one pairing can
         run 6-10 times in four hours (run 26: Keus233 against Soro 10 times, Soro won 9). The matchup learner counts
         each repeat as a new duel, so it learns individuals: p23 went to x0.94 in run 25 and back to x1.02 here,
         while magicians against thieves' gear-neutral pace was x1.0 in run 25 and x0.63 two hours into run 26.
      2. **Magicians against bowmen** (bowmen ahead in 14 of 19 pairings): per duel, bowmen took 1.23x the share of
         HP magicians took; gear-neutral it is 1.07x, so these bowmen out-gear these magicians by about 12% and the
         class gap is 7%, which the learner's x1.02 has not caught up with.
      3. **Pirates and Arans are thin or broken.** Pirates in practice are mostly second job (Brawlers won 1 of 16,
         1,487 raw a second); Arans rolled 567 raw a second against 5,592 for other warriors.
    - **PD21 decided (the user, 2026-11-13): vary practice pairings** (before the Aran fix).
      `server.pvp.PvpPairings`: every finished duel notes its opposing fighters; `!pvp practice` takes the nearest
      level within the span that has not fought the citizen within `PVP_PAIRING_REPEAT_MINUTES` (60), and its report
      counts the citizens "kept from a recent partner". The learned matchups keep their own note and count a pairing
      at most once in that window. In memory only, so a restart forgets it. 0 turns both off.
    - **Check run 27 (cc1fa2407, 2026-11-13 22:00 - 11-14 02:00, 431 duels, `duels-run27-cc1fa2407.csv`; 428 after
      starless Assassins).** No errors. 403 distinct pairings, none fought more than 3 times (run 26: 832 duels from
      about 150 cross-class pairings). Rounds started 1-7 duels: 10-24 citizens a round had only recent partners in
      reach, 16-19 too few HP potions; a night run. Cross-class: warrior 44%, magician 53%, bowman 47%, thief 54%,
      pirate 62% (26 duels). Pairs with 30+ duels: warrior-magician 43% (78), warrior-bowman 51% (35), warrior-thief
      39% (38), magician-bowman 51% (31; run 26: 28% by pairing), magician-thief 50% (56); the pirate pairs have 3-14.
      Level edge flat at 55-60% for gaps 1 to 5 (gap 5: 47 of 79, 59%). Knock-outs median 16 s. Learned matchups by
      the end: p12 x1.13 (237), p13 x1.09 (111), p14 x1.01, p23 x1.02, p24 x1.02. Per-band cells (Lv 50s, 80s, 110s)
      have 1-8 duels each, too few to judge; only Lv 90s and 100s warrior-magician pass 20 (39%, 45%).
    - **Arans (2026-11-14):** third-job Aran bots rolled about 60 raw a hit against about 2,000 for a second-job one
      (duel 409: 89 hits for 5,609 raw). Two readings in the bot-only formula (`CombatFormulaProvider`), monsters and
      duels alike: Combo Critical was read like Critical Shot (no prop in its WZ, so a 100% crit, at its damage field
      2-10 as the whole multiplier, 0.02-0.10x), and Body Pressure's damage (its own touch hit's, 55 at level 11) sat
      in the damage-buff sum (x0.55). Fixed in 3dfab14ea: Combo Critical gives y% rate and damage% crit damage per 10
      combo, up to x; Body Pressure joins the proc buffs. Third-job Arans now roll 5,200-5,800 per Full Swing. The Aran
      rate keys learned while broken (211-8 at the 0.4x floor from 217 rows, a 2.5x boost once fixed; 211-4/6 at the
      Body Pressure 0.55) were cut from `logs/pvp/learned-rates.txt` on a restart before any duel
      (`learned-rates-before-run29.txt` kept); 211-8 relearned to x0.69 within 13 rows, the warrior class's own.
      Run 28 (330 rows, `duels-run28-aranfix-boosted-3dfab14ea.csv`) ran with the boost and is set aside.
    - **Check run 29 (3dfab14ea, the Aran rates relearned, varied pairings):** 371 duels, no errors. Cross-class:
      warrior 49%, magician 54%, bowman 54%, thief 42%, pirate 38% (13 duels). Pairs: warrior-magician 52% (59),
      warrior-bowman 40% (35), warrior-thief 50% (24), magician-bowman 56% (41), magician-thief 61% (52),
      bowman-thief 61% (13). Level edge: 56% at a 1-2 gap, 61% at 3-5. Knock-outs median 16 s. Arans 16 rows in 11
      duels (mostly Aran against Aran, all in Rien), 5 won.
    - **V2 closed (the user's call, 2026-11-14):** filmed duels passed, the level edge holds (at most 61-64% at 3-5
      in every run since run 24), every class wins 42-59% across classes over a run (pirates thin); the per-band cells
      at Lv 50, 80 and 110 cannot be judged from practice (too few distinct pairings a band). Open, not blocking: the
      thin pirate sample, `fix/assassin-star-supply` and `fix/hp-potion-restock` (both thin out practice).
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

### Status (2026-11-14, branch `feat/pvp-v0`)

- **V3.1-V3.2 built** (24fc856fe; split suite 16,348 / 0 / 33), off by default (`RIVAL_CHALLENGES_ENABLED` with
  `pvp.PVP_ENABLED`); a GM's `!pvp challenge [status]` brings its own crew whatever the minute says.
  - `rivals.RivalChallenges` rides `RivalCrews.tick` (the social minute): the trigger rules above, rolled one minute in
    120. Up to three loaded members on the main's channel walk in through `PresenceScheduler.travel`, held there by the
    new `PresenceScheduler.hold` (no re-plan, no crowd trim, not free for practice) until the challenge lets them go.
  - The leader says `rival.challenge`; the answer is map chat (`PopulationHooks.rivalChallenge`, asked before the
    casino, companions and Residents), a whisper to any member (`CitizenWhispers.expect` opens the threads), or
    `@duel yes|no`. The hooks only queue; every change happens on the challenge's own 1 s step.
  - A yes: the main, its active companions and its human party on the field (up to three), against as many members,
    those whose gear edge (`DuelField.gearEdgeOf`) is nearest the player side's; the rest watch and heckle. Everyone
    enters `server.living.story.DuelGround` (a `SceneInstance` copy of the field, monsters cleared) at the spot they
    stood on, and `DuelField.onEnd` (new) sends them back to that spot after the duel; the leader says `rival.win`,
    `rival.lose` or `rival.draw`.
  - The bookkeeping (gap, daily count, field stint) is memory only: a restart forgets it.
- **Live check 1 (2026-11-14 15:21, b41a03dfe; Crewfriend on Henesys Hunting Ground I, `!pvp challenge`):** pass.
  Equinox's Nora404 and xTanx walked in (31 s; Blade520 was not eligible), grinding on arrival; the leader asked
  "crewfriend, a duel. yes or no?"; map-chat "ya lets go" was taken. 2-on-2 (Crewfriend + companion Dorran, the one
  standing with it) on the private copy: no monsters, no other citizens, the 3-2-1 count and the 3:00 clock. Equinox won
  in 14 s (Crewfriend stood idle). Everyone came back to the field at their own spot with HP full; IceCookie (not picked)
  stayed on the field; Nora404 said "gg Crewfriend :P better luck next time" and the crew kept grinding there. Film:
  `docs/agents/evidence/pvp-v3-film/challenge1-cut.mp4` (raw recording s 28-96).
  - Polish noted, not fixed: "yes or no?" reads like a prompt; the system line "... won the duel in 14 s (the other
    side is down)" reads like a log. Two stray GM-side lines in the take ("PvP: Equinox will walk in ...", the
    trigger's reply) are filming artifacts.
- **V3.3 built** (44b5ec062; split suite 16,360 / 0 / 33), live on the main checkout from 16:50.
  - `rivals.RivalRewards` pays each human fighter when the duel ends, by the section 2 table: a win 20 GMin EXP (x2 on
    the account's first win of the day), 3 Event Trophies, the crew's stake (30 meso GMin at the main's level, taken
    in even shares from the crew fighters' purses, booked `MesoAccount.PVP_STAKE`, split between the human winners)
    and a 25% chance of the 60% attack scroll for the winner's weapon (`attackScrollFor`: 2043001-2044901). EXP, stake
    and scroll chance are scaled by `0.75 + 0.5 x (1 - p)`, and p > 0.85 pays the Trophies alone. A loss or draw: 1
    Trophy, plus 5 GMin EXP when the crew fighters took at least half their max HP.
  - **The odds p** come from the sides' mean log gear edge (`DuelField.gearEdgeOf`) and the level factor between their
    mean levels: p = 1 / (1 + r^-4), where r is the pace ratio. The slope 4 matches practice runs 24-27 (a 3-5 level
    lead at matched gear won 57-60%). Ratings (V4) can replace it.
  - **Grind-minutes** (`server.pvp.PvpGrindMinutes`, `scripts/config/pvp/grind-minutes.yaml`): the V0.6 table. EXP is
    the band's median a minute (below Lv 30 scaled by EXP to the next level); a meso GMin is max(5 x measured mob mesos,
    1,782 x L) an hour, over 60. A missing file pays nothing.
  - **Exploits closed:** a fighter who left the duel earns nothing; a GM earns nothing unless a GM's `!pvp challenge`
    brought the crew (a test; logged "GM test"); an account is paid for at most 3 challenges a day (in memory, like
    the rest of the challenge bookkeeping).
  - **The Event Trophy is bound** (`ItemRestrictionPolicy.isServerBound`, PD7; pulled forward from V4.3): it counts as
    untradeable for trade, player shops and Duey, no world allowlist frees it, NPC shops will not buy it (its WZ price
    is 30,000), and a drop is refused rather than destroyed (the client does not know it is bound).
- **Live check 2 (2026-11-14 17:01-17:10, 44b5ec062, Crewfriend on Henesys Hunting Ground I, `!pvp challenge` x4):**
  the loss path and the daily cap pass (the win: live check 3).
  - Duel 1, 1-on-1 against xTanx (odds 0.03), answered in map chat: Crewfriend dealt nothing (the fight script pressed
    Ctrl, which is not bound) and lost in 38 s. Paid 1 Trophy, no EXP. Chat: "You have gained an item in the Etc tab
    (Event Trophy)", "You lost to Equinox, but earned 1 Event Trophy."
  - Duel 2, 2-on-2 with Dorran against Nora404 and xTanx (odds 0.15), answered `@duel yes`: lost in 13 s, the crew
    took 43% of its HP. 1 Trophy, no EXP.
  - Duel 3, the same sides: lost in 12 s, the crew took 63%. 1 Trophy and 277,300 EXP (5 GMin at Lv 96).
  - Duel 4: the account's fourth challenge of the day paid nothing.
  - **Why no win here:** both crews' mains (Crewfriend Lv 96, MenteeYes Lv 67) sit far below their crews' gear
    (odds 0.03-0.15), and `!kill` refuses citizens (`CommandTargetPolicy`), so a GM cannot knock a crew fighter out.
    Hence the GM knockout in live check 3.
- **Live check 3, a win (2026-11-14 18:26, 2faecfdfe; suite 16,361 / 0 / 33):** pass. 2faecfdfe adds the GM test
  knockout `!pvp duel ko <name>` (`DuelField.knockOutByGm`: out as if hit down, so the duel ends by sides as after a
  real knockout). The restart cleared the in-memory daily count.
  - Crewfriend and Dorran against xTanx and Nora404 (odds 0.13, multiplier 1.19); both crew fighters knocked out by
    the GM; won after 6 s. Paid 2,629,144 EXP (20 GMin at Lv 96, doubled as the day's first win; Crewfriend went to
    Lv 97), 3 Trophies and a 101,373-meso stake; the 30% scroll roll missed. Chat: "You beat Equinox: 3 Event
    Trophies and their stake of 101,373 mesos.", then the crew: "congrats!", "ok u got us. this time".
  - The stake moved: `economy_flow_daily` PVP_STAKE CITIZEN out 101,373, GM in 101,373; Crewfriend's purse 6,548,846
    (saved at the restart) to 6,650,479 (the stake plus party mob mesos).
  - The trophy rule: dropping the stack of 6 asked "How many will you drop?", then the server answered "That item
    can't be dropped." and the stack stayed. Trade, shop and Duey refusals are not checked live.
  - Evidence: `docs/agents/evidence/pvp-v3-film/win1-raw.mp4`, `win-payout-chat.png`, `trophy-drop-refused.png`.
  - **Seen, not fixed:** in duel 4 Dorran was counted out at once ("KO left": not on the copy or not alive at the
    start). Duels 2 and 3 had Dorran fighting, so it is intermittent; V3.2 follow-up.
- **V3.4 built** (d2cb8058f + 812cae096; split suite 16,368 / 0 / 33; live 20:29 2026-11-14), behind the same switches as V3.1-V3.3.
  - **Booking.** Every finished challenge duel is a `pvp_match` row (kind `RIVAL`, crew, map, seconds, winner side, why,
    the player side's odds) and a `pvp_match_fighter` row per fighter (side, human, knocked out, dealt, taken): changeset
    59, `db/tables/050-pvp-match.sql` (rollback `050-pvp-match-down.sql`). `rivals.RivalMatchStore` is the JDBC;
    `rivals.RivalRecord` counts a human's wins, losses and draws against the crew (side 0 is the player side).
  - **Quoting.** Once a human and its crew have dueled, the challenge line quotes the record
    (`rival.challenge.ahead|behind|even`, slot `{record}` = the score leader first, "3-1") and every other banter does
    (`rival.record.ahead|behind|even`). The record is cached per crew and human, read from `pvp_match` once.
  - **Megaphone.** A crew that won says `rival.win.smega` on one of the shared megaphones (the `CHATTER_SMEGA_PER_HOUR`
    cap and 10-minute gap still apply; nothing is said when the cap is full).
  - **Rematch.** A crew that lost whispers `rival.rematch` 10-20 minutes later (a GM's `!pvp challenge rematch` makes it
    due now). A whispered yes (`rival.rematch.yes`, or `.later` when the day's challenges are used up) brings the crew
    the next minute the main qualifies on a field, without the gap or the 1-in-120 roll; a no (`rival.rematch.no`) ends
    it. A rematch of a GM's test duel is a test too (it comes with the switches off). Offers are memory only.
  - **Letters.** Each duel writes a `rival.news` event (`crew|duel|winner|wins|losses|draws`); the next letters say
    the record from the crew's side ("duels are 2-0 to us now, that last one was fun :P. come get some").
  - **Records page.** `@records` (the whole page) ends with "Duels vs Equinox: 1-3 (Nora404 1-2, xTanx 0-2)".
  - The two challenge lines that ended "yes or no?" now end "?" and "you in?".
- **V3.4 live check 1 (2026-11-14 19:52-20:00, d2cb8058f; Crewfriend on Henesys Hunting Ground I):** mostly pass.
  - `!pvp challenge`: xTanx alone walked in; a map-chat yes, `!pvp duel ko xTanx`, a win. Booked as `pvp_match` 1 (odds
    0.04, 16 s) with both fighter rows, and the news row `Equinox|duel|0|1|0|0`.
  - `!pvp challenge rematch`: within a minute Nora404 whispered "rematch crewfriend? still salty about that duel lol". The
    whispered "ya bring it" (sent through the chat target menu: the client never sends a typed "/w") was taken; she
    answered "k bet. go train somewhere, we'll find u". Two minutes later, with no further command, Nora404 and xTanx
    walked back in on their own and asked. A 2-on-2 the crew won in 21 s ("ez. jk gg crewfriend"); booked as match 2;
    `@records` ended "Duels vs Equinox: 1-1 (xTanx 1-1, Nora404 0-1)".
  - **Fixed after it:** the rematch's challenge line was never said. `ChatterEngine.say` drops a line inside the
    speaker's 15 s self cooldown, and Nora404 had just said "need MP pots!! anyone?". Every challenge beat now goes
    through the new `ChatterEngine.sayNow` (said anyway; it restarts the cooldown); heckles may still drop.
  - **Not seen:** the crew's win megaphone ("no megaphone slot": the shared 4-an-hour, 10-minutes-apart cap of every
    citizen megaphone, or no line; the log now says which). The letters' duel line is covered by unit tests only.
- **V3.4 live check 2 (2026-11-14 20:32-20:34, 812cae096, the `sayNow` fix; same field):** pass.
  - `!pvp challenge`: xTanx walked in and, right after its own ambient "is this map taken", asked "1-1, crewfriend. time
    to settle it" (`rival.challenge.even`, the record quoted).
  - A map-chat yes; Crewfriend stood idle and lost in 18 s. xTanx said "told u. gg", and its Super Megaphone went out
    on the channel: "gg crewfriend, equinox takes this one" (log: "Equinox beat Crewfriend (1-2): megaphone"). The
    loss paid 1 Trophy and no EXP (the crew took under half its HP). Film: `quote-raw.mp4`; the first rematch:
    `rematch-cut.mp4`.
- **Switched on 2026-11-14** (user: "Turn them on"): `population.RIVAL_CHALLENGES_ENABLED` and `pvp.PVP_ENABLED` are
  true in config.yaml. PVP_ENABLED gates only the challenges today (`RivalChallenges.on`); practice and `!pvp` were GM
  tools with it off and stay so.




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

### Status

- **V4.1-V4.2 built (2026-11-16, 8a3f2e07c on feat/pvp-v4, off main line 5d9fcfc58).** Not deployed yet.
  - **Where.** The Battle Arena lobby (980010000) is reachable only through the stock Lv 20-30 transports (9010022,
    2101018), so the coliseum's people stand on Ariant's main street (260000000) instead: Tigun (2101004, the palace
    guard) as the Arena Master at x 560, and Jano (2101010) as the Quartermaster at x 440 (V4.3). They are extra spawns
    of stock templates, reached by object id before any script, the needs board's way; their stock spawns keep their
    scripts. A match fights on a private copy of a Battle Arena floor (980010101/201/301 in turn): the copy drops the
    floor's 20 jewel-hunt mobs and never respawns, so AriantPQ is untouched. The stock arena clock still shows on
    entry; the round's own clock replaces it at the bell.
  - **Your match.** Tigun's window: fight (fee 20·L, `MesoAccount.PVP_FEE`), the bracket's top 10, the rules. The
    opponent is the free citizen (loaded, unhired, nothing holding it, no party, no event) on your channel in your
    bracket within 5 levels whose rating is nearest. It is held, warped onto the copy, quotes its real record
    (`chatter/coliseum.yaml`, "12-7 on the veteran ladder..."), and you fight best of three 90 s rounds, 5 s apart.
    Leaving the copy or logging off forfeits; a citizen that logs out voids the match and refunds the fee.
  - **Coliseum rules on the duel.** `DuelField.Rules(roundSeconds, tonics, tonicPercent, tonicGapMs)`: own potions
    are refused, and the potion key (or an autopot) drinks an arena tonic instead: 3 a round, 25% of max HP and MP, 8 s
    apart, outside the heal allowance. A bot drinks one at 60% HP or lower.
  - **Ratings.** Glicko-2 (`server.pvp.Glicko2`, Glickman's worked example as its test) per character per bracket, in
    `pvp_rating` (changeset 60 / SQL 051); an idle rating widens by the days since its last match. Each social minute
    up to `COLISEUM_SIM_PER_MINUTE` (20) pairs of free citizens play a simulated best of three (`DuelSim.fighterOf`,
    moved out of `!pvp sim`, with the 90 s round), the same pair at most once an hour.
  - **Pay** (section 2): a win 3 Trophies and 6 odds-scaled grind-minutes of EXP for the day's first 5 wins, a loss 1
    Trophy, 10 rewarded matches a day; a forfeit, and a GM outside a test, earn nothing. Booked in `pvp_match` as kind
    LADDER.
  - **GM.** `!pvp coliseum` (nearest rating), `!pvp coliseum <citizen>`, `status`, `sim [n]`, `rating [name]`; they run
    with `COLISEUM_ENABLED` off (it ships false).
  - **Not yet:** the Quartermaster's shop (V4.3), the board page and spectating from the King's Room (V4.4, which also
    holds back the watched-citizen rule).
- **Live check (2026-11-16, 90d1d83c2 live from 06:35; full suite 16,371 / 0 / 33; `COLISEUM_ENABLED` still off).**
  Crewfriend (Lv 97 Spearman) through `!pvp coliseum`; recording `docs/agents/evidence/pvp-v4-film/ladder1-raw.mp4`.
  - **Background matches.** `!pvp coliseum sim 20` on the headless operator: 20 matches among 40 distinct citizens,
    20-20, ratings 1338-1662 (a first win from 1500/350 lands on 1662, as Glicko-2 should). No errors.
  - **Matches 1-4, Crewfriend standing still or a test script swinging:** lost 0-2 each to Leaf749 (Lv 98 Crusader),
    GrumpyDit (twice) and xRyusx. Rounds ended in 6-15 s. Each paid 1 Trophy; the rating fell 1500 -> 1338 -> 1208 ->
    1155 -> 1119. The match ran on a private copy of Battle Arena 1/2/3 in turn, with the 90 s clock; afterwards
    Crewfriend stood on its own spot on Henesys Hunting Ground I with HP full.
  - **Match 5, a win** (knock-outs through the headless operator's `!pvp duel ko`, which acts within the 8 s rounds;
    typed in the client it came too late): beat LilGius 2-0 at odds 0.21. Paid 381,427 EXP and 3 Trophies; the
    rating went 1119 -> 1246 and LilGius's 1500 -> 1122 (a new rating, deviation 350, moves far).
  - **Tonic.** A White Potion double-clicked mid-round: "Arena tonic: 25% HP and MP back (2 left this round).", and
    Crewfriend absorbed 11,032 of a 8,806 max HP before going down. A second click landed just as the round ended
    (06:57:09.8) and drank a White Potion normally (90 -> 89): between rounds potions work, and do nothing useful as
    each round ends with HP and MP full.
  - **Lines.** "Tigun: you fight Leaf749 (Lv 98 Crusader), rated 1500. To the arena!", then Leaf749 "first ladder
    match, go easy lol" (`ladder.intro.new`), "again?" between rounds, "gg crewfriend", "gg wp. rematch anytime", and
    on the win "gg crewfriend, u got me". The result line: "Ladder: You beat LilGius (2-0). Rating 1119 -> 1246
    (Veteran, 1-4)."
  - **Notes.** The duel's own opening line ("Duel: ... No deaths: a knock-out ends it ...") repeats every round. A
    companion that isn't fighting steps out of the party with its "i'll wait out here" line and rejoins after (the
    existing companion hold, as on any instance). Not seen live: Tigun's window and fee (only spawned with the switch
    on), and a citizen logging out mid-match.
- **V4.3 built (2026-11-16, 54e9b14d5 on feat/pvp-v4; full suite 16,377 / 0 / 33; live from 12:54).** The Arena
  Quartermaster: Jano (2101010) beside Tigun, spawned with the coliseum switch. Stock and prices in
  `scripts/config/pvp/quartermaster.yaml`: Elixir x10 (3), Power Elixir x5 (5), All Cure Potion x5 (2), the 60% (15)
  and 10% (40) attack scroll for the weapon held (`server.pvp.PvpScrolls`, shared with the rival rewards), Male Desert
  Rabbit Cushion 3010064 (30), Persevering Challenger Medal 1142084 (60; tradeBlock, one per character). A trade takes
  the Trophies only once the item fits and gives them back if it does not arrive; each refusal says why (no weapon with
  a scroll, too few Trophies, no room, already owned). The trade block itself shipped with V3.3. GM: `!pvp coliseum
  shop` opens the window with the switch off.
  - **Live check (Crewfriend, 18 Trophies, holding a one-handed axe):** the window listed the stock with icons and
    "You have 18 Event Trophies"; the scroll line named "Scroll for One-Handed Axe for ATT". Bought it (log: "traded 15
    Trophies for 2043101 x1 (3 left)", chat: "You have gained an item in the Use tab"), then Elixir x10 ("traded 3
    Trophies for 2000004 x10 (0 left)"); a further pick was refused: "That's 3 Event Trophies, and you have 0." No
    server errors. The client draws a dialog's text over about a second, so a capture right after a click can look cut.
  - **Not seen live:** Jano standing in Ariant (needs `COLISEUM_ENABLED`), and the room and one-per-character refusals
    (unit-tested).
- **V4.4 built (2026-11-16, b7e5f4559 on feat/pvp-v4).** The board page and the gallery.
  - **The board** (`ColiseumBoard`): your bracket, rating, W-L, place among the listed (3 matches to be listed) and
    this week's move; each bracket's top 10 with each one's move this week; and the week's three biggest climbs a
    bracket. Tigun's "The ladder board" opens it, and the F1 Bulletin Board gets a "Coliseum" page beside Records while
    `COLISEUM_ENABLED` is on. "This week" is UTC, from Monday: `pvp_rating` gains `week_of` and `week_rating`
    (changeset 61 / SQL 052), the rating a row went into its week's first match with.
  - **The gallery** (`Gallery`), the watched-citizen rule: Tigun's "Watch a match from the gallery" picks the best-rated
    free citizen of your bracket (else the busiest one) with a foe within 5 levels, and its nearest-rated foe. They
    fight a real best of three under coliseum rules on a closed arena floor while you stand ringside at the floor's
    right end (a seat on a platform above put the fighters under the chat bar: the client draws you three quarters
    down the screen); you hear each round and the rating change, and the two talk before, between and after. The
    result counts on the ladder like a simulated one. Leaving the floor or logging out calls the bout off, unrated, and
    everyone goes back where they stood. Unwatched citizen matches stay simulated. Under Lv 30 you can still watch.
  - **The King's Room** stays as AriantPQ left it: the gallery is the arena floor's platform, because a client sees only
    the map it is on.
  - **GM:** `!pvp coliseum board` and `!pvp coliseum watch [rookie|veteran|champion]`, both with the switch off.
- **V4.4 live check (2026-11-16, Crewfriend; full suite 16,388 / 0 / 33 on each build).**
  - **Board** (after 30 simulated rounds filled the brackets): "Your bracket: Veteran (Lv 60-99), rated 1246, 1-4.
    52nd of 63."; the Veteran top 10 ("1. Wavaki Lv 76 2070 (8-0) +570") and the climbers page each rendered.
  - **Gallery, three bouts, no errors, the watcher home at its own spot each time:** Wavaki beat LilTax 2-0
    (2070 -> 2112, 1950 -> 1918), Sky706 2-0 (2112 -> 2126, the smaller gain against a lower-rated foe) and
    ArrowMage 2-0 (2126 -> 2141). Rounds ran 7-14 s.
  - **The seat took three tries.** A middle-platform seat (y -85) left the floor below the screen; the lower-left
    platform (y 95, 1a952b4de) still put the fighters under the chat bar; ringside at the floor's right end (250, 260;
    f793c1daf) shows the whole fight. Clip: `docs/agents/evidence/pvp-v4-film/gallery3-cut.mp4`.
  - **Fixed after:** the lines named jobs from the enum ("Lv 80 Whiteknight"); bd5baf43a uses
    `CompanionView.jobName` ("White Knight", Cygnus and Aran without the tier digit).
  - **Fixed after:** the watcher saw the stock 10-minute arena clock through the whole bout, since the duel sends its
    round clock to fighters only; the gallery now sends the watcher one at each round (countdown plus round).
  - **Recheck on 64dae659f (live 20:55):** a fourth bout showed the watcher the round clock (1:28 mid-round); Ormael
    upset Wavaki 2-1 (2141 -> 2052, 1761 -> 1854). Both were Crusaders, so the job-name fix was not seen live (unit
    tests only). No errors.

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

### Status (2026-11-17)

V5.1 and V5.2 are built on `feat/pvp-v4` (b55e0407e, score wording 26916d19c), switch `pvp.TOURNAMENT_ENABLED` off.
V5.3 (betting) is not built. Full suite on b55e0407e: 16,399 / 0 / 33.

- **Where it differs from the design above.**
  - **Schedule:** the social minute checks the clock, not `EventManager.scheduleAtTimestamp`: a reminder 5 minutes
    before the start, the start itself (up to 3 hours late, after a restart), and a run left RUNNING by a restart is
    cancelled with fees owed back.
  - **Watching:** a citizen-only bout is fought live only when a player asked Tigun to watch it; the watchers sit
    ringside as in the V4 gallery. Every other citizen bout is simulated (`DuelSim`). Tournament bouts are booked in
    `pvp_match` but do not move ladder ratings.
  - **Draw:** seeded by ladder rating over everyone seated (1 v 8, 4 v 5, 2 v 7, 3 v 6); empty seats are byes. A drawn
    bout sends the better seed on. A seat not free on the tournament channel when its bout opens forfeits it, and a
    player who never fought takes no prize.
  - **Prizes:** citizens take only their pool shares, no Trophies. Prizes for a player who is offline or has a full
    Etc tab, and fees from a called-off run, wait with Tigun (`pvp_tournament_seat.paid`) and are paid from his window.
  - **Medal:** granted with a `TOURNAMENT_MEDAL_DAYS` expiry; last week's champion hands theirs back if online.
- **Pieces.** `coliseum.Tournament` (controller), `TournamentBout` (a live best of three), `TournamentDraw` and
  `TournamentRules` (pure), `TournamentStore`; tables `pvp_tournament` / `pvp_tournament_seat` (053); bookings in
  `pvp_match` with kind TOURNAMENT; ledger accounts `PVP_FEE` / `PVP_PRIZE`; lines `tournament.*` in
  chatter/coliseum.yaml; the letters' news kind `coliseum.tournament`; the board's tournament page. GM:
  `!pvp tournament [status|signup|watch [bracket]|start <bracket> [seats] [minutes] [me]]` (start works with the
  switch off).
- **Live check (2026-11-17, on b55e0407e).** `!pvp tournament start veteran 4 1 me` seated 1. Wavaki, 2. LilNeka,
  3. FoxSniper, 4. Crewfriend (pool 50,400 from the three citizens' fees; a GM pays none). Crewfriend's two bouts were
  fought live (the opponent knocked out with `!pvp duel ko` through the headless operator); LilNeka v FoxSniper was
  simulated. Seen:
  - The semi-finals and final notices, Tigun's "the final starts in 1 minute", Crewfriend back on its own spot after
    each bout.
  - Crewfriend champion: 40 Event Trophies, 25,200 mesos, the Absolute Victory Carnivalian Medal ("yours for 7 days"),
    the notice "Crewfriend is this week's Veteran champion, beating FoxSniper in the final!". FoxSniper took 12,600
    and megaphoned "lost the final to Crewfriend. next week tho". The house kept 12,600.
  - `pvp_tournament` DONE, every seat's row paid; Tigun's window reads "Veteran: won by Crewfriend, over FoxSniper."
  - Fixed after it: scores read from the top seed's side ("Crewfriend beats Wavaki (0-2)"); 26916d19c reads them from
    the winner's.
  - Not seen live: a scheduled start (only a GM start), the letter to an away player (unit-tested), a forfeit, a
    watched citizen bout, and a prize waiting with Tigun.

---

## 9. V6. Team fights

- **2v2 and 3v3:** you plus up to two companions against a citizen crew, or against your rival crew ("Crew War").
- Companions run the V2 brain on your side, so every companion is a combat partner, not only a grinding partner.
- Your companions cannot be on the other side for rewards.
- Team ratings are kept separately from 1v1.
- Focus fire is a real tactic, so sides are always equal in size.
- *Done when* a 3v3 against the rival crew is filmed and the companions read as teammates in the review.

**V6 status (2026-11-17, feat/pvp-v4 254c56205, LIVE as d319a27af 08:40, switch off):**
- **Two kinds, both from Tigun's new "Fight with my companions" page:** a team ladder match (2v2 or 3v3, as many of
  your companions as stand with you, the 1v1 fee) or a Crew War against your rival crew (no fee). Best of three under
  coliseum rules on a private arena floor; sides stand apart on the bottom floor (TeamRules.spot) and are always equal.
- **Who you face:** a ladder match takes a citizen team in your bracket within 5 levels, one guild's when a guild has
  enough free members (the guild team rated nearest yours), else the nearest-rated free citizens as "a pickup team"
  (TeamRules.pick). A Crew War takes the crew's leader first, then its members, free on your channel; its size is what
  both sides can field. Hired citizens are never free, so your companions are never on the other side.
- **Team brain (server.pvp.TeamTactics, in DuelField):** a side's bots pick one target together, the standing foe
  with the lowest HP share (a far foe counts healthier, 1,200 px = one whole share); a call stands 2.5 s and switches
  only for a foe 0.25 share weaker; while you landed a hit in the last 4 s your bots assist your target; a called foe
  more than 650 px from a bot is left for the foe at hand. Your bots make short party-chat calls (TeamCalls: "on
  Wavaki", "Wavaki low", "with u", "1 down", "im out"), at most one a side every 6 s; a side with no player is silent.
- **Ratings:** team ratings apart from 1v1, under TEAM_ brackets in pvp_rating. A team rates as its members' mean
  (rating and volatility mean, deviation root-mean-square); every member is rated against the other team's rating, so
  your companions climb the team ladder with you. Citizen teams (guild teams first, then pickup teams, 2 or 3 a side)
  play COLISEUM_TEAM_SIM_PER_MINUTE (6) simulated best-of-three team matches a social minute (DuelSim.teamRound:
  everyone goes for the weakest standing foe; at the bell the side keeping more summed HP share wins).
- **Pay and records:** the player only, as a ladder match, sharing the ladder's 10 rewarded matches a day; companions
  get nothing. A team match is booked in pvp_match as TEAM; a Crew War is booked with the crew's id as a challenge is,
  so the head-to-head, the crew quoting it, `@records` and the letters count it, and a crew win takes a megaphone slot.
- **Lines:** team.intro/win/lose and crewwar.intro/win/lose in chatter/coliseum.yaml, said by the citizen side's first
  member; between rounds the ladder's own round lines.
- **GM:** `!pvp coliseum team [2|3]`, `!pvp coliseum crewwar`, `!pvp coliseum teamsim [n]`; status lists team
  matches too. All run with the coliseum switched off.
- **Tests:** TeamTacticsTest (7), TeamRulesTest (10: team picking, spots, team ratings, a mirror 2v2 even at 0.50 and
  3v2 won over 80% in the sim, the best of three), ColiseumContentTest's new slots.
- **Live check (2026-11-17 08:45-08:57, d319a27af, full suite 16,417 / 0 / 33):** Crewfriend (Lv 97 Spearman) with
  its companions Dorran and IceCookie on Henesys Hunting Ground I; all by GM command, nobody fought for Crewfriend (it
  stood still), so this checks the flow and the bots, not balance.
  - Crew War x2 (`!pvp coliseum crewwar`): both were 2v2, Crewfriend + Dorran against Nora404 + xTanx. Correct: Equinox's
    third member, Blade520 (Lv 109), was online but grinding Ulu Estate III and not free, and a Crew War sends only free
    members. Equinox won 2-1 then 2-0; team rating 1500 -> 1338 -> 1280; Nora404 "gg crewfriend, that's 1-4 now" (the V3
    head-to-head counts Crew Wars); Tigun paid 1 Event Trophy each.
  - 3v3 ladder x3 (`!pvp coliseum team 3`): Crewfriend + Dorran + IceCookie against pickup teams (SilentRanger, Sora867,
    Leaf749; xMoelx, Nona, Soa367; GrumpyDit, Gira74, xYuox), lost 1-2, 0-2, 0-2. Six fighters on the bottom floor,
    round clock, "3v3 round 2 of 3 against a pickup team. Score 0-1." between rounds, knock-out lines ("Dorran is knocked
    out by GrumpyDit"), and the companions' party calls: "go xYuox", "GrumpyDit first", "got em", "im out", "sry im
    out". Everyone back on the field after each, no server errors.
  - Film: docs/agents/evidence/pvp-v6-film/team3v3-cut.mp4 (58 s, the third 3v3); crewwar1-raw.mp4 (both Crew Wars).
  - Seen, not a bug: a citizen from the other side sent Crewfriend a buddy request after a match (the citizens' own
    social behaviour).
- **Not yet:** a filmed 3v3 Crew War needs a crew with three members free on the player's channel; Equinox had two.
  A team win (every player bout lost because Crewfriend stood still); the guild-team pick (every pickup this run).

## 10. V7. Client polish (V0.1 says it is needed)

- **Own damage number.** V0.1 showed that your own client ignores a `damagePlayer` about itself, for every source and
  whether or not you are hidden. The same patch would also show the server-run boss hits that land on you today. So:
  - add server→client **0x189 PVP_HIT** to `docs/CUSTOM_OPCODES.md`;
  - Kaentake draws the number and plays the hit sound on the local user;
  - the HP bar already updates without it.
  - **Built 2026-11-09, not deployed or shipped** (server `feat/pvp-v7-own-hit`, kaentake `feat/own-hit` on the
    shipped `ship/2026-10-31-quickslot-relogin`). The client routine is `0x0092EC50`: the one that draws a number
    over a CUser, called by `CUserLocal::SetDamaged` and `CUserRemote::OnHit` alike. Sounds come from
    `play_mob_sound` (CharDam1/2/F by attack index) and `play_skill_sound` (type 49, Hit). Addresses and evidence
    are in kaentake `src/v83_offsets.h`, namespace `ownhit`. Number and sound only: no knockback, invincibility or
    TAKE_DAMAGE.
  - Server: `server.combat.OwnHit` sends PVP_HIT only to the victim, only with the `own_hit` mod on and `own_hit`
    active in its feature report. DuelField sends each shown line (the skill's Hit sound) and each dodge (MISS).
    ServerMobDamageService sends each broadcast hit (the mob's CharDam sound for its attack index), so a
    server-run boss's hits on you now show too. `!pvp probe ownhit` checks it on your own client.
  - **Client drawing checked live 2026-11-09** before any restart. A test-only build (KAENTAKE_OWN_HIT_PROBE) also
    draws a DAMAGE_PLAYER about one character id. On a private test client as Bossfriend, the live
    `!pvp probe self` with src -4, 0 (mob 8130100), -1 and -3 drew the violet 1234 over your own character
    every time, with no failure logged. Evidence is in `docs/agents/evidence/own-hit/` (clip, contact sheet,
    frame). Split suite on 452210600: 16,295 / 0 / 33. Still to do: the 0x189 path itself (`!pvp probe
    ownhit`) after a deploy. The sound cannot be heard in the clip (video only).
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

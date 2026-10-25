# Cygnus Knights and Aran bots (plan, 2026-10-24)

Branch `feat/cygnus-aran-bots` (off `feat/companion-manager` bc6f46d8d). Goal: bots that play the Cygnus Knight and
Aran lines the v83 client has, as players of those classes did: born in Ereve or Rien, advancing at their own
instructors, using their own skills. Today every bot is an Explorer.

## 1. What v83 really has (checked against `wz/` and `scripts/`)

| Line | Beginner | 1st (Lv10) | 2nd (Lv30) | 3rd (Lv70) | 4th | Level cap |
| --- | --- | --- | --- | --- | --- | --- |
| Dawn Warrior | Noblesse 1000 | 1100 | 1110 | 1111 | none | 120 |
| Blaze Wizard | Noblesse 1000 | 1200 | 1210 | 1211 | none | 120 |
| Wind Archer | Noblesse 1000 | 1300 | 1310 | 1311 | none | 120 |
| Night Walker | Noblesse 1000 | 1400 | 1410 | 1411 | none | 120 |
| Thunder Breaker | Noblesse 1000 | 1500 | 1510 | 1511 | none | 120 |
| Aran | Legend 2000 | 2100 | 2110 | 2111 | 2112 (Lv120) | 200 |

- `Skill.wz/1112.img` ... `1512.img` are empty (268 bytes): the client has the 4th-job ids in `client.Job` but no
  skills, so Cygnus stop at 3rd job and Lv120 (`JobProgressionPolicy.classLevelCap`). `Skill.wz/2112.img` has the
  Aran 4th job (Maple Warrior, Final Blow, Over Swing, Combo Tempest, Combo Barrier, High Mastery, Freeze Standing,
  High Defense, Hero's Will).
- Instructors (end NPC of the stock advancement quests in `Quest.wz/Check.img`):
  - Cygnus, all three advancements, on Ereve 130000000: Mihile 1101003 (Dawn Warrior), Oz 1101004 (Blaze Wizard),
    Irena 1101005 (Wind Archer), Eckhart 1101006 (Night Walker), Hawkeye 1101007 (Thunder Breaker). Quests 20101-20105
    "Path of a ...", 20201-20205 "Knighthood Exam" (30 exam proofs), 20311-20315 "Shinsoo's Teardrop".
  - Aran, on Rien 140000000: 1st job at the Giant Polearm 1201001 (21101 "The Polearm-Wielding Hero"); 2nd, 3rd and
    4th at Maha 1201002, the Spirit of Polearm (21201 "The Mirror of Desire", 21302 "Making Red Jade", 21401 "Taming
    the Polearm"; Lilin 1201000 starts the first two).
- Starting areas: Noblesse are made on `STARTING_MAP_NOBLESSE` 130030000 (Forest of Beginning 1-5 -> Small Bridge
  130030006 -> Ereve), then Training Forest I/II (130010000/130010100). Legends are made on the Black Road tutorial
  914000000 (scripted cutscene maps), wake in the Ice Cave 140090000, walk Cold Forest 1-5 to Rien 140000000, train on
  the Snow-covered Fields 140020000-140020200. Ereve's Sky Ferry 130000210 sails to Ellinia and Orbis; Rien's port
  140020300 sails to Lith Harbor.
- Skills a v83 player of these lines only gets from a quest (WZ `invisible`, taught by `Quest.wz/Act.img` or the
  stock scripts):
  - Cygnus, Lv100 and Lv110 quests at their Chief Knight (20601-20605, 20611-20615): Brandish and Advanced Combo (DW),
    Ifrit and Meteor Shower (BW), Hurricane and Bow Expert (WA), Triple Throw and Venom (NW), Barrage and Speed
    Infusion (TB).
  - Aran (`USE_FULL_ARAN_SKILLSET: false` on this server): Combo Ability and Polearm Booster (Rien training quests,
    Lv10-22), Polearm Mastery (21733, Lv31), Combo Drain (21734, Lv37), Combo Smash (21740, Lv45), Final Charge (21748,
    Lv54), Full Swing (21758, Lv70); Over Swing, High Mastery and Freeze Standing come with master level 10 at 4th job,
    the other 4th-job skills from mastery books (Maple Warrior: book 2280003 from Maha's 4th-job quest).
- Aran combo: the client sends ARAN_COMBO_COUNTER (0xA3) and `AranComboHandler` adds one per packet, resets after 3 s
  idle and applies the Combo Ability attack buff every 10. Combo Smash, Combo Fenrir and Combo Tempest are ranged
  attacks (`RangedAttackHandler`) that need 30 / 100 / 200 combo and reset it to 0. No bot code sends 0xA3 today.

## 2. What blocks them today

- **Engine.** `JobProgressionPolicy.isLegalAdvancement` allows Explorer jobs only (id < 1000), and
  `Character.changeJob` goes through it, so no character (bot or player) can become a Cygnus or Aran; the stock quest
  scripts above are refused too. `JobProgressionPolicyTest` pins Noblesse -> Dawn Warrior as illegal.
  `CreateCharHandler` creates Adventurers only ("Phase 0"), so players cannot make these characters either.
- **Creation.** `BotCreator` makes every bot a Beginner in Henesys (Residents, citizens, hires, `!spawnbot`).
- **Builds.** Career bundles, AP profiles and SP profiles key on job families WARRIOR..PIRATE (Jackson enums), and
  `compatibleChoices` hands a Noblesse or Legend one of the 7 Explorer bundles. No SP profile names a Cygnus or Aran
  skill. `AgentCharacterShaper.assignAp` and the probe/arena weapon pickers switch on families 1-5. Starter kits are
  Explorer-only and need an old job of BEGINNER.
- **Advancement.** `AgentJobPath.next` knows only BEGINNER as a root; `JobErrands.pathFor` and `CitizenJobPath.line`
  end every line at `id/10*10+2` (a Cygnus 4th job that has no skills); `JobInstructors.of` returns null for id >= 600.
- **Combat.** Mostly data-driven already: the skill cache classifies from WZ, Blaze Wizard takes the magic route
  (family 12), Wind Archer and Night Walker the ranged route by weapon, Thunder Breaker's Spark/Shark Wave and Aran's
  combo finishers are in the ranged list, Dawn Warrior orbs and Thunder Breaker energy already branch on `isCygnus`.
  Missing: the Aran combo counter, Cygnus/Aran entries in the Explorer-only skill tables (weapon gates, charges, dash,
  combo finishers kept by the learner), `isMageJob` without Blaze Wizard, the probe's weapon per family.

## 3. Plan

1. **Engine.** Allow Noblesse -> 1x00 -> 1x10 -> 1x11 and Legend -> 2100 -> 2110 -> 2111 -> 2112 in
   `isLegalAdvancement` (never 1x12: no skills in v83; Evan stays out). Player creation stays closed (question 5).
2. **Creation.** `BotCreator` takes the line: a Noblesse made in Ereve's Forest of Beginning, a Legend made in Rien (the
   Black Road tutorial is a cutscene; a bot wakes on Snow Island as the story has it), in the line's own starting
   clothes (MakeCharInfo: Aran top 1042167, bottom 1062115, shoes 1072383, Basic Polearm 1442079).
3. **Builds.** Six bundles (`dawn-warrior`, `blaze-wizard`, `wind-archer`, `night-walker`, `thunder-breaker`,
   `aran`), offered only to their own beginner (Noblesse: the five Cygnus bundles; Legend: Aran); Explorer Beginners
   never draw them. AP profiles by line (DW/Aran STR + DEX, BW INT + LUK, WA DEX + STR, NW LUK + DEX, TB STR + DEX),
   job families added to both enums. SP profiles in the MapleRoyals ordered-segment format for all 19 jobs (Cygnus
   3rd job to Lv120). Quest skills (above) are capped at their master level like a 4th-job skill and the master level
   comes at the quest's level (question 4). Starter kits from the stock first-job quests (DW 1302077, BW 1372043, WA
   1452051 + 2000 arrows, NW 1472061 + 800 stars, TB 1482014).
4. **Advancement.** One "line end" for every job (Explorer 4th, Cygnus 3rd, Aran 4th) used by `JobErrands.pathFor`,
   `CitizenJobPath.line` and `AgentJobPath`; Noblesse and Legend as roots; `JobInstructors` names the Chief Knights and
   the Giant Polearm / Maha. Companions keep the errand (leave, road time to Ereve or Rien, walk up, advance, come back);
   citizens take 1st/2nd job off-screen with their catch-up and 3rd/4th at the instructor, as Explorers do.
5. **Combat.** Aran combo counter driven from the bot's own hits (question 2); Cygnus/Aran skills in the Explorer-only
   tables where the evidence says so (weapon gates, Soul Charge/Snow Charge/Lightning Charge as charges, Corkscrew
   Blow/Final Charge dashes, combo finishers kept by the learner); probe and arena weapon per line; tests per skill.
6. **Population, Residents, hires.** Careers for the six lines in `careers.yaml`, Resident personas allowed to name
   these jobs, hires carrying them: all behind one switch (question 1).

## 4. Design decisions

The user's answers (2026-10-24), each to a question put with a recommendation:

1. **Share and switch: about 25% of new citizens, switch ships ON.** Aran 10%, each Knight 3%; new Cygnus/Aran
   citizens below Lv10 live in Ereve or Rien. (Recommended: the same share with the switch off until the live check;
   the user chose on.)
2. **Aran combo: counted from its own hits (recommended).** One combo per monster its attack hits, through the same
   server path as the client's combo packet (3 s decay, Combo Ability buff every 10, finishers spend it); the combat
   learner sees the combo in its state and learns when to spend it.
3. **Companions: hires plus new personas (recommended).** Cygnus/Aran citizens can be hired, and a few new Cygnus/Aran
   personas are homed in Ereve and Rien (coordinated with the Residents-beyond-Victoria session).
4. **Quest skills: at the quest's level (recommended).** A bot gets each quest skill when it reaches the level its quest
   allows (the quest done off-screen, as the 4th-job instructor's skills are today), so a Lv35 Aran has no Combo Smash.
5. **Players: creation stays closed** (not asked; my default). Bots only; opening player creation is a separate change
   (the advancement fix already lets the stock scripts advance a player who has such a character).

## 5. Verification (filled in as it lands)

Offline: TBD. Live checks after deploy: TBD.

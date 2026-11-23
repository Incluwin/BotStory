# N6 bounties and field bosses: report (feat/world-bounties, 2026-11-23)

Branch `feat/world-bounties` from `feat/companion-manager` 3d04e47df. Not merged, not pushed, not deployed. No schema
change. All three switches ship off: `population.FIELD_BOSS_RECORDS_ENABLED`, `BOUNTIES_ENABLED`,
`CITIZEN_FIELD_BOSS_ENABLED`. Check scripts (written, not run): `MapleServer\tools\checks\checks\n6-bounties` (B1-B6).

| Commit | Phase |
|---|---|
| 0502c9e11 | N6.0 boss catalogue `scripts/config/population/field-bosses.yaml` with the respawn source of each boss |
| 97beb6eff | N6.1 field-boss kills on the clear records (+ the bounty config keys) |
| 664718dd4 | N6.2 bounties |
| fe1549002 | the N2 paper stories (town.friends, town.grudge, records.race) |
| b5874c389 | N6.3 citizens camping field bosses |

## N6.0: how field bosses respawn here (from the code, not changed)

- **Script bosses**: the 27 `scripts/event/AreaBoss*.js`. Every channel loads every event script (`Channel.java:142-148`,
  `EventScriptManager.init`). Each script spawns at boot and re-arms every 3 h (`em.schedule("start", 3 h)`). It
  spawns only when the boss is absent from the map, and it does not check for players. So a script boss is back
  0 to 3 h after a death, also on an empty map, with one copy per channel.
  These are Mano, Stumpy, Deo, Seruf, Faust (two maps), Giant Centipede, King Clang, Timer (three maps), Dyle, Zeno,
  Bamboo Warrior, Nine-Tailed Fox, Tae Roon, King Sage Cat, Eliza, Snack Bar, Kimera and Leviathan.
- **Map-life bosses**: Mushmom, Zombie Mushmom, Jr. Balrog, Snowman, Blue Mushmom, Male and Female Boss, Headless
  Horseman, Manon, Griffey, Pianus, Bigfoot, Black Crow, Dodo, Lilynouch and Lyka.
  - `MapleMap` passes the WZ `mobTime` through `SpawnTimeOverridePolicy`, and `config.yaml`
    `server.MOB_RESPAWN_TIME_OVERRIDE_SECONDS: 10` replaces every mobTime >= 0.
  - `SpawnPoint` waits `mobTime / 5` after its last mob died, which is 2 s. `MapleMap.respawn` (the 2 s
    RespawnTask) skips maps with no player.
  - So these bosses are back about 2-4 s after a death, but only while someone is on the map. The WZ mobTime
    (1800-129600 s) does not apply.
- **P13 decision 20** ("about 10 s after death") is the config value, not the computed delay. P13's "every 3 hours"
  holds for the script bosses only. Both rest on code reading; one live timing at Jr. Balrog would settle the 2 s.
- Left out of the catalogue:
  - the sibling ids with no spawn anywhere (King Clang 5220000, Seruf 4220000);
  - Bloody Boom (an add of Pianus);
  - the six Amoria "Door" bosses of 677000xxx (AreaBossDoor1-6.js, Lv25-32), which no P13/P16 list names (add them
    there if wanted).

## Design as built

- **N6.1** `records.FieldBossKills` is called from `PopulationHooks.mobKilled`, which `LivingWorldHooks.mobKilled`
  already calls, so N6.1 needs no `LivingWorldHooks.java` hunk. Memory only on the killing thread.
  - The record goes through `ClearLog.cleared` with quest = the boss key (`field.stumpy`) and instance `field:<map>`.
  - Members are the top damager still on the map plus its party members on the map.
  - `QuestLabels.load` appends the catalogue after `rivals.yaml`, so the records page, `@records stumpy`, the
    paper's records stories (PaperCompose reads living_clear through the labels) and `RivalCrews` read it with no
    special case.
- **N6.2** `needs.Bounties` holds `NeedKind.BOUNTY` rows:
  - boss bounty: `item_id` = the boss mob, quantity 1, detail `boss:<key>`;
  - hunt bounty: `item_id` = the monster, quantity = kills, `delivered` = kills counted, `offer` = the price per kill,
    detail `hunt:<item>`.
  - Payment is `NeedLedger.payBounty` -> `Hands.payBounty`: one NEED_FULFILLED transaction. `MesoAccount.NEEDS` books
    out of the citizen and into the player, and an away citizen's stored row is debited with `meso >= ?`.
  - The citizen records `FULFILLED_BOARD` (a deed hearsay tells), whispers `need.bounty.paid` and the player gets a
    pink line.
  - The board has a "Take the bounty (3 hours)" option and its own page.
  - The per-kill check reads an index of held bounties by mob id, rebuilt each minute and at each claim. A monster
    no bounty names costs one map lookup.
- **N6.3** `population.FieldBossCamps` is described in its class comment and the config note. The gate seam
  `AgentFieldBossSeats` is installed by `Population.start` and cleared at stop.
- **N6.4** follows from N6.1: `FieldBossKillsTest.aCitizenCrewsKillCountsForItsCrewLikeAnyClear`. The only way a crew
  gets a field-boss time is N6.3. An unwatched roll books every camper, so `creditFor` finds the crew's two or more
  members. A real, watched citizen fight books the top damager alone, because campers form no game Party (see below).

## Decisions the plan did not make (please review)

1. **Duration = first hit to death**, not spawn to death (`Monster.firstHitAtMs`, a new field set in
   `applyDamage`). A script boss can stand for hours before anyone comes, so spawn-to-death times would be
   meaningless on the records page.
2. **Members = the top damager on the map and its party on the map.** If the top damager left or died, the next
   damager still there counts. Party members elsewhere are not members.
3. **No separate PaperDesk filing for a field boss's first kill or record.** The paper's records section already
   composes `records.first` and `records.record` from living_clear for every labelled quest, and field bosses are
   labelled now. Filing again would print each story twice.
4. **Boss bounty sources**:
   - a citizen that dies on a map where a catalogued boss stands posts at once (from `PopulationHooks.died`, on the
     executor);
   - one that trains on a boss map posts with `BOUNTY_CHANCE` at a need derivation.
   - One open bounty per citizen, `BOUNTIES_OPEN_MAX` (12) on the board.
5. **Price**: `level x BOUNTY_BOSS_PER_LEVEL` (1,500) for a boss, `kills x mob level x BOUNTY_HUNT_PER_LEVEL` (20) for
   a hunt. Either is capped at `BOUNTY_PURSE_SHARE` (10 %) of the purse when posted, and nothing is posted below
   `BOUNTY_MIN_OFFER` (5,000). The meso stays in the purse until paid. If the purse is short at the kill, the count
   is kept and the next kill tries again.
6. **"Drops it needs and cannot farm"**: an Etc good of the price basket (what citizens gather and sell) whose wild,
   non-boss, non-quest dropper on an open field is 6-30 levels above the citizen.
7. **"The claimant's kills"** means kills where the claimant is the highest damager (the loot owner). A party member's
   or a companion's kills do not count.
8. **The 30 % rule** counts the claimant plus its party's members wherever they stood, against the whole damage. The
   claimant must be online (anywhere) at the kill to be paid. Otherwise the bounty stays held, and they can take the
   next spawn.
9. **A bounty claim holds `BOUNTY_CLAIM_MINUTES` (180)** and counts toward `NEEDS_CLAIMS_PER_PLAYER`.
10. **N6.3 unwatched** (with the coordinator's conditions, 2026-11-23). Campers never hit the boss without a human on
    the map. A roll every `FIELD_BOSS_ROLL_MINUTES` (5) decides it: 20 % for one camper at the boss's level, +12 % per
    extra camper, +2 % per level above the boss, held to 5-90 %.
    - A win removes the boss with `map.killMonster(boss, null, false, 0)`. With no killer that path drops nothing and
      gives no EXP or meso, while the spawn point's or script's respawn still runs.
    - The kill is booked **untimed** (started 0, so `duration_ms` 0, which `Clear.timed()` reads as none). It counts
      as a kill by that party: `records.first` and the records page's first clear. It never enters fastest times,
      player or crew bests, or the paper's record and fastest stories. A crew's rolled kill can never beat a time:
      `crewCleared` returns on an untimed clear. Its only news is the letters' "while u were gone we took down stumpy".
    - Rate: at most `FIELD_BOSS_ROLLED_KILLS_PER_DAY` (2) rolled kills per boss per UTC day, all channels together.
      With campers present and p >= 20 % a boss falls within about 25 minutes, so the cap binds: **expected rolled
      kills <= 2 per boss per day**. A script boss spawns up to 8 times a channel a day, 24 on three channels.
    - No roll while an online human holds a claimed bounty on that boss, or while a human stands on a map one portal
      away.
    - Respawn wait for a human who arrives just after a rolled kill (from N6.0): a script boss is back 0-3 h later
      (1.5 h on average) on that channel, and the other channels have their own copy. A map-life boss is back about
      2-4 s after the human steps onto the map, because its spawn point only respawns while a player is there.
    - A loss changes nothing, and nobody dies of a roll.
    - Bounties never pay on a rolled kill, since no human was there.
11. **N6.3 "first"** is decided the first time a camper's gate is asked while a human is on the map. If a human has
    damaged the boss by then, humans were first; otherwise citizens were.
11b. **Drops after a yes** (coordinator condition 4). `Monster.killBy` gives the drops to `getHighestDamagerId` on the
    map, then to `LivingWorldHooks.bossDropOwner`. That hook now also asks `FieldBossCamps.dropOwner`. When a citizen
    tops a field boss in a fight a human started (the campers joined on a yes), the top human damager on the map owns
    the drops and mesos instead. When the campers started first they keep what they earned, as any first party would.
    The record of that fight still follows damage (FieldBossKills), so a citizen top damager can head the record of a
    fight whose loot went to the human.
11c. **No seat promise** (condition 5). Campers form no game Party, so the arrival line only says they were here first
    and the human is welcome to join in (`fieldboss.seat`). The human keeps what their damage earns.
12. **The potion floor** (`FIELD_BOSS_POTION_FLOOR`, 50 HP potions) makes the gate refuse the camper, so it fights the
    ordinary mobs until its existing supply rules send it to restock. It does not walk away at once.
12b. **How the defaults were chosen.**
    - `FIELD_BOSS_POTION_FLOOR` 50 HP potions: the companion Easy Balrog standard was 40/50 (P7, decision 8), and a
      field boss hits softer than Balrog. 50 is the same reserve, and it is above the grind's own POT_STOP, so a camper
      leaves the fight before its potion trip would fire mid-fight.
    - `FIELD_BOSS_CAMP_CHANCE` 0.1: one TRAIN plan in ten, for a citizen whose band fits a boss on its continent. About
      240 citizens spread over roughly 35 boss bands gives at most a few campers per boss map at once. That is enough
      for a camp to be seen, and too few to crowd a boss map.
    - `FIELD_BOSS_ROLLED_KILLS_PER_DAY` 2 is deliberately low (see 10).
13. **Camping picks** are only on the citizen's home continent and only on maps with ordinary monsters
    (`WorldKnowledge.fieldCapacity > 0`), so campers never idle while they wait.

## Not done / open

- **Follow-up: a real party for campers.** Campers would form a citizen Party when they camp (as CitizenPartyQuests does
  for PQs). Then the record lists the whole camp, and crew credit works for watched fights. The arrival line could then
  offer a real seat: a party invite to the human, with the loot rule unchanged (the human's party keeps drops after a
  yes). Until then the line promises nothing.
- With `KS_MEMORY_ENABLED` on, N2's grind manners also leave a mob alone that a human hit and the bot has not. A camper
  invited after the human started may therefore still skip the boss in the grind target pick. This is not verified;
  B5 will show it.
- Two N2 paper items are skipped:
  - "word travels" needs a once-a-week marker per human, more than one call;
  - the kill-stealer item needs a D2 ruling on naming.
  N3 listed none.
- The live NEED_FULFILLED meso move for a bounty (`LiveNeedHands.payBounty`) has no unit test (it needs a client).
  B2 checks the flow table.

## Hot files touched (every hunk)

- `NeedKind.java`: the BOUNTY constant, and `item()` excludes it.
- `NeedLedger.java`:
  - `Hands.payBounty`;
  - the `bounties` field, its construction and `bounties()`;
  - `lock`, `replace` and `deadlineOf`/`purse` made package-visible;
  - `forWords`, `what` and `slots` delegate for BOUNTY;
  - `derive` calls `bounties.derive`;
  - `claim` accepts BOUNTY, uses `BOUNTY_CLAIM_MINUTES` and calls `bounties.claimed`;
  - `tick` calls `bounties.minute`;
  - the new `payBounty`/`bountyPaid` section before "market".
- `NeedScreens.java`:
  - the "Take the bounty" option in `details`;
  - `claimedText`, used by `claim`'s OK text;
  - the bounty line in `label`;
  - `describe` -> `bountyPage`.
- `PresenceScheduler.java`: `mapFor` TRAIN asks `campFor` first, plus the new private `campFor`.
- `config.yaml`: one population block after `PAPER_RETENTION_DAYS` (15 keys).
- `LivingWorldHooks.java`: one hunk in `bossDropOwner`. When the boss-run owner is null, it asks
  `PopulationHooks.fieldBossDropOwner` (N6.3 drops).
- Not touched: `ChatterEngine.java`, `SocialFabric.java`, `Market.java`, `CitizenSpots.java`,
  `CompanionCardScreens.java`, `banter.yaml`, `changelog-tables.xml`.
- Other shared files:
  - `Population.java` (fields, construction, the whisper topic, the seat install and uninstall, the social-lane minute,
    accessors);
  - `PopulationHooks.java` (mobKilled: two calls; died: one);
  - `PopulationLoader.TRIGGERS` (+7);
  - `PopulationConfig.java`;
  - `Monster.java` (`firstHitAtMs`, `totalDamageTaken`);
  - `AgentBossTargetGate.java` (one condition);
  - `QuestLabels.java`;
  - `CitizenTies.java` and `RivalCrews.java` (the paper calls; RivalCrews also turns an untimed crew kill into letter
    news);
  - `chatter/needs.yaml`, the new `chatter/fieldboss.yaml` and `paper.yaml`.

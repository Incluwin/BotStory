# Residents and citizens for the Community Repack towns

Branch `feat/repack-residents` from `feat/companion-manager` 5c5f19b3c. Asked for 2026-11-08: "expand the citizen/resident
content to every town in the community repack that isn't covered currently".

## 1. Where things stood

- The Repack import (`docs/REPACK_IMPORT_PLAN.md`) gave 15 Repack towns a TownLife profile (`MANUAL_ONLY`), and no
  Residents. Section 6 there reserved quest-log ids 32150-32599 for them (`quest-log-blocks.yaml`).
- Citizens skip every Repack map on purpose (`FieldCatalogMain`: a stock-client player can never see who stands in them).
- Crack in Time 272000000 is in `mods/repack-incomplete-maps.txt`: the Repack client cannot draw it and `RepackMaps`
  keeps players out. It gets no Residents (only bots would ever see them).
- Players reach every other town: Cokebear operator 9001000 (Henesys -> Coke Town), Perry 9110000 (Mushroom Shrine ->
  Outside Ninja Castle), the Temple Keeper 2140000 at Three Doors (Gate to the Future, Twilight Perion, the nine Arcane
  River towns). Bot travel legs exist for all of them (`agents/catalogs/npc-travel-legs.json`).
- Only a player on the Repack game files can stand in these towns, so only such a player meets these Residents, talks
  to them and finishes their quests.

## 2. Decisions (the user, 2026-11-08)

- Cast, tiered (about 55): Coke Town 7, Outside Ninja Castle 7, Henesys Ruins 5, Twilight Perion 5, Tribal Refugee
  Camp 4, three in each of the nine Arcane River towns (27).
- Citizens: yes, they get regions and fields in the Repack hunting grounds (Coke Town, Ninja Castle, Ulu City, Lion
  King's Castle, the Future), with a small seed. Only Repack-client players see them; they come out of the online cap.
- Recruits: a fourth weapon tier from the Future towns' recruits. Stock weapons stop at L120 for all sixteen lines (the
  only higher ones on the server are Repack-only knuckles and guns, which close a stock client that equips them), so
  (follow-up question, same day) the fourth tier is **the Timeless and Reverse L120 pieces, scrolled harder than any
  tier-3 reward**, for the eight lines whose regional run stops at L100-110.

## 3. Places and casts

| Town (profile id, map) | Residents | Recruits | Level of its quests |
|---|---|---|---|
| Coke Town (`coke-town`, 219000000) | 7 | 2, pool armour | 15-70 (Coke Ski Camp 219010xxx, Coke Valley 219020xxx) |
| Outside Ninja Castle (`ninja-castle`, 800040000) | 7 | 2, pool armour | 25-100 (Castle Corridor 800040200-207) |
| Henesys Ruins (`henesys-ruins`, 271010000) | 5 | 3, tier 4 (bow, crossbow, wand) | 120-165 |
| Twilight Perion (`twilight-perion`, 273000000) | 5 | 3, tier 4 (two-handed axe, polearm, one-handed blunt) | 120-175 |
| Tribal Refugee Camp (`tribal-refugee-camp`, 273050000) | 4 | 2, tier 4 (dagger, gun) | 120-175 |
| Nameless Town, Chu Chu Village, Lachelein, Arcana, Morass (Trueffet Square), Esfera (Base Camp), Moonbridge (Outpost), Labyrinth of Suffering (Crash Site), Limina (World's Sorrow) | 3 each | none | 90-130 (errands: no mob spawns in Arcane River) |

### 3.1 Coke Town

Always winter, at the end of a hidden street from Henesys; the polar bears Poch, Nell (the Sundry Goods Shop), Lolo
and Puch keep house there. Mobs: Coke Snail 9500144 lv10, Coke Slime 9500151 lv15, Coke Mushroom 9500152 lv20, Coketump
9500153 lv25, Coketump Lite 9500154 lv30, Coke Pig 9500143 lv35 (the Ski Camp, 219010000-219010300); Coke Seal 9500145
lv38, Play Seal 9500146 lv42, Igloo Turtle 9500148 lv45, Coke Golem 9500149 lv50, Ice Golem 9500150 lv60 (Forest of
Coke Golem 219020301), Yeti and Coketump 9500147 lv70 (Coke Valley IV 219020300).

Drops (section 6.1): the Coke mobs now drop the Coca-Cola event's own etc items (60%), which both clients have: Coke
Bottle Lid 4000221 (Coke Snail, Coke Slime), Coca-Cola Can 4000216 (Coke Mushroom, Coketump), Coca-Cola Light Can
4000217 (Coketump Lite), Coca-Cola Card 4000213 (Coke Pig), CokePLAY Cube 4000212 (Coke Seal, Play Seal), Coke Ice Block
4000219 (Igloo Turtle, Ice Golem), Coca-Cola Cube 4000211 (Coke Golem), Coca-Cola Gem 4000214 (Yeti and Coketump).

| Resident | Role (job, level) | Recruit | Friendship gear |
|---|---|---|---|
| Marit | keeper of the Empty House's key, arc owner | - | - |
| Toivo | instructor of the Coke Ski Camp (Fighter 32) | - | - |
| Inga | knitter of the Sundry Goods Shop door (Beginner) | - | knitted hats L35/L40/L50 by class (pool) |
| Nils | boy of the sled run | - | - |
| Bartolo | collector of lids and cans | - | - |
| Aksel | ice-golem hunter (Crusader 62) | yes | capes L50/L60/L65 |
| Ylva | archer of the Coke Valley (Hunter 58) | yes | earrings L50/L55/L60 |

### 3.2 Outside Ninja Castle

The castle's ninja clan holds Zipangu's hill fort; outside its gate a few people live off the castle's comings and
goings (the stock NPCs Hamadaya, Akai, Sentsu, Daisuke, Sasuke, Hoshi; inside the gate Sai, Fuji, Hyottoko, Princess
Suzume, Nobuo). Mobs: Genin 9400400 lv25, Ashigaru 9400404 lv30, Chunin 9400401 lv50, Kunoichi 9400406 lv60, Jonin
9400402 lv80, Ninto 9400403 lv100 (Castle Corridor 800040200-800040207; Inside the Castle Gate 800040100 is Genin
only). The Phantom's Rooms (800040300-315) and the Boudoir hold a handful of mobs each, and the corridor 800040210 is
not reached on foot: steps use the corridors 800040100-800040207. Ninja Castle mobs drop no etc of their own: collect
steps there use what Zipangu's fields drop (wave 3's `quests/zipangu.yaml` header lists them) or are kill counts.

| Resident | Role (job, level) | Recruit | Friendship gear |
|---|---|---|---|
| Oharu | keeper of the tea stall at the gate, arc owner | - | - |
| Kenta | boy who wants to be a ninja | - | - |
| Goro | old gate porter, retired | - | - |
| Shinobu | cartographer of the corridors (Beginner) | - | headbands/hats L60/L70/L80 by class (pool) |
| Fumiko | herbalist of the hill path | - | - |
| Hayate | shinobi who walked out of the castle (Hermit 78) | yes | pool armour L70-80 |
| Masaru | ashigaru who threw down his spear (Dragon Knight 82) | yes | pool armour L70-80 |

### 3.3 The Future: Henesys Ruins, Twilight Perion, Tribal Refugee Camp

The Temple Keeper's door opens on years that may come: Ereve dark, Henesys in ruins, Perion at the end of days. The
people there live in that future and know it might not happen; a few remember the stories of the present the player
comes from. Mobs: Henesys Ruins Market 271010100 and Park 271010200 Mutant Snail 8600000 lv162; Hill North of Henesys
Ruins 271010300 Mutant Slime 8600002 lv166; Suspicious Hill 271010301 + Mutant Ribbon Pig 8600003 lv168; Dark Spore Hill
271020000 and Creepy Humming Forest Trail 271020100 Mutant Orange Mushroom 8600001 lv164; Dark Ereve 271000100/200/210
Mutant Tino 8600004 lv163, Tiru 8600005 lv165, Tiguru 8600006 lv167. Twilight Perion: Swollen Stump 8620000 lv190
(273010000, 273020000), Swollen Dark Stump 8620001 lv191, Swollen Axe Stump 8620002 lv192 (273020200-273020400),
Pillaging Wild/Iron/Fire Boar 8620003-8620005 lv192-194 (273030000-273030300), Sinister Wooden/Rocky/Steel Mask
8620006-8620008 lv194-196 (273040000-273040300), Ancient Golem/Dark Golem/Mixed Golem 8620009-8620011 lv196-198
(273060000-273060300). Knight Stronghold (271030000 on) is not reached on foot. These mobs drop only what their
stock donors do (potions, scrolls): Future quests count kills, carry items from the present (the Temple of Time's
roads, `quests/three-doors.yaml` header), or send the player on errands.

Level design: the tier-4 pieces are L120, the Future's mobs lv162-198, and few players stand that high. So each
recruit's first two friendship quests are walked in the present or in the Future's lowest fields, and only the last
asks for its own town's fields:

| Tier-4 step | Friendship | Player level | Where the work is | Reward |
|---|---|---|---|---|
| 4a | 5 | 120 | Temple of Time roads (lv 80-131) or errands | the Reverse piece, every slot 60% |
| 4b | 7 | 140 | Henesys Ruins Market/Park, Dark Ereve (lv162-167) | the Timeless piece, 10% x2, the rest 60% |
| 4c | 10 | 160 | the recruit's own Future fields | the Timeless piece, every slot 10% |

Every tier-3 top reward is 10% x2 + 60% on an L100-120 piece, so 4b already beats it and 4c (seven 10% scrolls) is
the best scrolled weapon any Resident gives. The Reverse pieces have slightly less attack than the Timeless ones,
hence Reverse first.

| Line (tier 3 ends with) | 4a Reverse | 4b and 4c Timeless | Recruit |
|---|---|---|---|
| bow (Seori's Dragon Shiner Bow L110) | Reverse Engaw 1452059 | Timeless Engaw 1452057 | Dagny, Bowmaster 162, Henesys Ruins |
| crossbow (Kael's Dragon Shiner Cross L110) | Reverse Black Beauty 1462051 | Timeless Black Beauty 1462050 | Thorne, Marksman 164, Henesys Ruins |
| wand (Delphine's Dragon Wand L108) | Reverse Enreal Tear 1372045 (L118) | Timeless Enreal Tear 1372044 (L118) | Seraphine, Bishop 166, Henesys Ruins |
| two-handed axe (Rourke's Dragon Battle Axe L110) | Reverse Tabarzin 1412034 | Timeless Tabarzin 1412033 | Brand, Hero 172, Twilight Perion |
| polearm (Gwang's Dragon Chelbird L110) | Reverse Diesra 1442067 | Timeless Diesra 1442063 | Eskil, Dark Knight 174, Twilight Perion |
| one-handed blunt (Brody's Battle Hammer L100) | Reverse Allargando 1322061 | Timeless Allargando 1322060 | Ottmar, Paladin 176, Twilight Perion |
| dagger (Vance's Dragon Kreda L110) | Reverse Killic 1332076 | Timeless Killic 1332074 | Yrsa, Shadower 168, Tribal Refugee Camp |
| gun (Vesper's Dragonfire Revolver L110) | Reverse Blindness 1492025 | Timeless Blindness 1492023 | Vidar, Corsair 170, Tribal Refugee Camp |

Each recruit's loadout carries its line's tier-3 top weapon (section 5). All sixteen ids exist in the server's and the
stock client's Character.wz; none is on `mods/botstory-wz-equips.txt`.

| Town | Resident | Role | Recruit |
|---|---|---|---|
| Henesys Ruins | Edda | medic of the ruins, arc owner | - |
| Henesys Ruins | Lumi | scavenger girl of the ruined market | - |
| Henesys Ruins | Dagny, Thorne, Seraphine | the ruins' last watch | tier 4 |
| Twilight Perion | Heddwyn | storyteller of the last Perion, arc owner | - |
| Twilight Perion | Ashe | keeper of the last forge fire | - |
| Twilight Perion | Brand, Eskil, Ottmar | warriors of the end of days | tier 4 |
| Tribal Refugee Camp | Ama | cook of the camp | - |
| Tribal Refugee Camp | Kasper | boy of the camp, runs messages | - |
| Tribal Refugee Camp | Yrsa, Vidar | the camp's scouts | tier 4 |

Contracts for the tier-4 recruits use `maxLevelGap: 20` (their levels are far above the usual companion).

**The camp's road** (found by the content review): the Tribal Refugee Camp has no warp, and on foot it lies past five
maps of lv190-194 mobs from Twilight Perion, while its quests are lv115-160. Cobie 2142112, who stands in both towns,
now takes a player the scouts' way and back (`RepackGuide.ESCORTS`, `scripts/npc/repack_talk.js`; config.yaml
`NPCS_SCRIPTABLE` lists him in case the Repack client's own Quest.wz gives him quests), and the bots have the same two
legs in `npc-travel-legs.json`. The Twilight Perion and camp recruits' last weapon quests (4c) send the player to the
Future's lv163-168 fields, not their own lv190+ ones.

### 3.4 Arcane River

Beyond the Temple Keeper's third choice runs a river at the edge of the world. Its towns stand, their monsters are
gone ("nothing hunts there now"), and a few travellers have settled. No mob spawns there, so every Arcane quest is an
errand: TALK_TO and DELIVER between Residents, VISIT_MAP on the river's walkable maps, items carried from the Temple of
Time's roads, Leafre or anywhere else. Player levels 90-130 (anyone who reaches Three Doors; Temple of Time roads open at
L91+). Maps a player cannot load (`mods/repack-incomplete-maps.txt`: the 5th-job horizons 4500000xx-4500002xx and Limina
450013xxx) are never step targets.

| Town (profile) | Residents |
|---|---|
| Nameless Town (`nameless-town` 450001000) | Ottoline (cartographer of the river, arc owner of the River Atlas), Oren (writes names on stones), Ilka (a traveller who goes by the name on her boots) |
| Chu Chu Village (`chu-chu-village` 450002000) | Pomona (cook), Bodhi (cartwright with an appetite), Lark (girl who feeds the Chu Chus) |
| Lachelein (`lachelein` 450003000) | Minna (musician who plays in her sleep), Dorian (clockmaker), Celeste (dreamer) |
| Arcana (`arcana` 450005000) | Sylvie (listens to the spirits), Briar (woodcarver), Fennimore (hermit) |
| Morass (`morass` 450006130) | Corin (writer chasing shadows), Anouk (keeps the square's inn), Lucan (shadow-catcher) |
| Esfera (`esfera` 450007040) | Nerissa (diver-scientist), Adler (quartermaster of the base camp), Iris (her assistant) |
| Moonbridge (`moonbridge` 450009050) | Hadrian (old soldier of a war nobody remembers), Kaito (wandering swordsman), Bryn (scout) |
| Labyrinth of Suffering (`labyrinth-of-suffering` 450011120) | Cassius (pilot of a ship that came down), Sorcha (his mechanic), Tilde (lamp-keeper) |
| Limina (`limina` 450012000) | Mourne (watcher at the end of the world), Saoirse (singer), Tam (boy who keeps what washes up) |

### 3.5 Threads

- *Coke Town: the Empty House* (`marit-the-empty-house`). Marit keeps the key of the Empty House in Coke Town, whose
  owner went out to the Coke Valley one winter and never came back. Chapters move on world flags set by her quests
  and one of Aksel's (the Ice Golems took the house's owner's sled) and end with the house lit again. Its last beat is
  once per server (`requires.notFlags`).
- *Outside Ninja Castle: the boy who went in* (`oharu-the-boy-who-went-in`). Oharu's son was taken into the castle as
  a genin trainee years ago; she sends a cup of tea in with every visitor. The player finds his headband among the
  Chunin, Hayate (who walked out) knows him, and a note comes back out. Light tie to wave 3's Zipangu (a TALK_TO to Isamu
  the fox-mask carver, or Chiyo, with its own `say` lines; their persona files are not edited). Last beat once per
  server.
- *The Future: letters across the end of days* (`edda-letters-to-the-camp` and `heddwyn-the-last-story`). Edda
  in Henesys Ruins and Heddwyn in Twilight Perion each keep a thread; the two meet in the middle: Edda has written to
  the Tribal Refugee Camp for a year and no answer has come; the player carries the letters (Three Doors in between) and
  finds why; Heddwyn's last story is about the present the player comes from. Light tie to the present: the
  Temple of Time's Three Doors cast (Solveig's or Osmund's lines in TALK_TO `say`, their files not edited).
- *Arcane River: the River Atlas* (`ottoline-the-river-atlas`). Ottoline maps the river town by town and needs a page
  from each: one quest per Arcane town (given by one of that town's Residents) sets a world flag, and her chapters open
  on three pages at a time, upstream first: chapter 2 on `arcane.page.nameless`, `arcane.page.chuchu`,
  `arcane.page.lachelein`; chapter 3 adds `arcane.page.arcana`, `arcane.page.morass`, `arcane.page.esfera`; chapter 4
  adds `arcane.page.moonbridge`, `arcane.page.labyrinth`, `arcane.page.limina`. Ottoline's own quests read the same
  flags. Each page quest is the town's own errand (its Resident's story), ending with the page handed to the player's
  keeping; it is replayable by other players but sets the world flag once (`requires.notFlags` on the flag is not used:
  any player's run counts, the flag simply stays set). The other Arcane Residents keep their own small stories.

## 4. Authoring rules (all casts)

The format is waves 1-4's (`scripts/config/living-world/{personas,quests,arcs,rumors,contracts}/<town>.yaml`); the
Temple of Time files (`three-doors.yaml`) and wave 3's (`zipangu.yaml`) are the nearest models. Gotchas the earlier
waves learned:

- `gift.favorite` is one line for every id in `giftItemIds`: never name an item in it.
- An arc's `dialogueOverrides` apply only to its own persona; other Residents' `about.*` lines must read true before and
  after the arc turns. Rumors have no end gate: their lines must read true after their beat.
- `QuestLedger.complete` reads `questComplete.<quest>` BEFORE it sets flags and advances the arc (since wave 4's
  fix): a chapter opened by quest Q that overrides `questComplete.Q` is heard only by the players after the first.
- Relationships between two recruitable Residents use a kind `TieKind` names (`FRIEND_KINDS`, rival, owes...); two who
  never met (a Future recruit and the present-day Resident whose weapon line they continue) are `HEARD_OF`, so they do
  not banter as old friends in one crew (`BanterCatalogTest`).
- `VISIT_MAP` advances only on entering its map while it is the current step; a kill step on the same map leaves it open.
- `DELIVER` completes only at the quest's own giver (an errand to someone else is `TALK_TO` with `say` lines).
- Contract kill proofs count only after the offer; keep the recruits' contract shapes varied (item price, kill proof,
  day rate, rumor alone, rumor + intro); every `refuse.flags` line names what the contract still needs.
- A quest with gear ends in a TALK or a DELIVER to its giver. Gear quests need `friendship` 4+; the weapon line's three
  rewards use `itemId` (owned), pool gear uses `pool`.
- Never reward a pet; untradeable items may be rewards, never hand-ins. Every collect step names an item the named mob
  really drops (`src/main/resources/db/...` drop rows, or the Coke drops of section 6.1).
- Persona ids are unique across all content (`personas/*.yaml`, `quests/friendship.yaml`'s cast, stories): check before
  adding one. Use `look: { gender: ... }` for every persona so pronouns are right.
- Routines use the venues of the town's TownLife profile (`src/main/resources/agents/town-life/<profile>.json`), with
  `materialize: false` gaps so not everyone stands about at once.
- Quest ids: `<persona>-<slug>`; flags: `<town-short>.<thing>.<state>`; world flags for arc beats.

## 5. Shared files (written once, by the coordinator of this branch)

- `loadouts.yaml`: one loadout per recruit and per townsperson with a job (section 3 tables).
- `quest-log-ids.yaml`: every new quest takes the next id from 32150, appended in file order (the block is in
  `quest-log-blocks.yaml`).
- `ContentLintFriendshipTest`: 53 weapon Residents -> 61. `ContentLintLootTermsTest`: 100 contracts -> 112.
  `ArcFinalBeatOncePerServerTest`: the nine once-per-server beats below.

## 5.1 As built

| Town | Residents | Recruits | Quests | Rumors | Contracts | Arc |
|---|---|---|---|---|---|---|
| Coke Town | 7 | 2 | 24 | 3 | 2 | `marit-the-empty-house` |
| Outside Ninja Castle | 7 | 2 | 22 | 3 | 2 | `oharu-the-boy-who-went-in` |
| Henesys Ruins | 5 | 3 | 21 | 3 | 3 | `edda-letters-to-the-camp` |
| Twilight Perion | 5 | 3 | 20 | 3 | 3 | `heddwyn-the-last-story` |
| Tribal Refugee Camp | 4 | 2 | 14 | 2 | 2 | - |
| Nameless Town | 3 | - | 8 | 2 | - | `ottoline-the-river-atlas` |
| Chu Chu Village, Lachelein, Arcana, Morass | 3 each | - | 6 each | 2 each | - | - |
| Esfera, Moonbridge | 3 each | - | 7 each | 1 each | - | - |
| Labyrinth of Suffering, Limina | 3 each | - | 6 each | 1 each | - | - |
| **Total** | **55** | **12** | **159** | **28** | **12** | 5 |

Quest-log ids 32150-32308 (Coke Town first, Limina last, in file order). Once per server (`requires.notFlags` on the
world flag each sets): `aksel-the-sled-in-the-ice`, `marit-the-house-lit`, `oharu-the-crooked-hem`,
`oharu-the-one-who-walked-out`, `oharu-a-note-comes-out`, `kasper-the-door-goes-everywhere`, `edda-a-year-of-letters`,
`heddwyn-the-last-story`, `ottoline-the-river-atlas`. River Atlas pages: Oren (Nameless Town), Pomona (Chu Chu), Minna
(Lachelein), Sylvie (Arcana), Corin (Morass), Nerissa (Esfera), Hadrian (Moonbridge), Cassius (the Crash Site), Mourne
(Limina). Coke Town's forest quests start at L62 (the Forest of Coke Golem's only door is past the lv70 Yetis of Coke
Valley IV). Moonbridge's Outpost has no walkable portal: its quests go by the Temple Keeper to the Crash Site and the
White Spear decks. New loadouts: the eight `future-*` and Hayate's `ninja-claw-l70` (Blue Scarab, Blue Nightfox).

An independent read-only review of each half (2026-11-08) found the camp's road (fixed with Cobie, section 3.3), the 4c
fields, and lines that read false after a once-per-server beat or contradicted another file; all MEDIUM and most LOW
findings were fixed by the authors.

## 6. Data changes outside the content files

### 6.1 Coke Town drops

`db/extensions/2026-11-08-coke-town-drops.xml`: each Coke mob gets its own Coca-Cola etc item (they had only the rows of
a stock donor since the Repack import), as in the Coke event they came from.

### 6.2 Citizens

A citizen's home region is fixed at birth from its level, and no career reaches past about lv86 when seeded (career
level caps 70-85, `CitizenGenerator.seedCeiling`; a full life tops out near lv99). So citizens can be *born* in Coke
Town and Zipangu, while the higher Repack grounds serve the citizens of the regions they open from, as they outgrow
their own fields. Nobody is born in the Future, and raising that ceiling would change every citizen's life curve.

- `FieldCatalogMain` lists the Repack maps (it skipped them all): only the maps even the Repack client cannot draw
  (`RepackMaps.incomplete`), Crack in Time (272), the Knight Stronghold behind its quest portals (271030000-271049999)
  and the Ruins of Krexel (541020700-541020899) stay out; Arcane River has no mobs and was never scanned (Map4).
  Region by prefix: 219 Coke Town -> `coke-town`; Ninja Castle stays `zipangu`, Ulu City `singapore`, Lion King's
  Castle `el-nath`; 271 and 273 (the Future, behind Three Doors) -> `temple-of-time`. Its mob level now reads the mob's
  own `info/level`: ten Mob.wz files (the Future's mutants among them) list a skill's level first, which made Henesys
  Ruins' fields lv1. `fields.yaml` regenerated: 84 maps added, nothing else changed (the Mushroom Castle hand comment,
  which the generator drops, put back).
- `regions.yaml`: new region `coke-town` (lv15-55, town 219000000, weight 1); Outside Ninja Castle 800040000 is
  Zipangu's third town (weights 45/40/15); the temple region's comment names the Future fields.
- TownLife: `coke-town` and `ninja-castle` are `AMBIENT` now (the routes have their travel legs), so their citizens
  have town life there. The Future and Arcane River profiles stay `MANUAL_ONLY`: nobody lives there.
- `config.yaml` `TARGET_CITIZENS` 717 -> 737 for the seed after the deploy: `!citizens seed coke-town 12` and
  `!citizens seed zipangu 8` (gm5; the queue is in memory, so run it once the new build is up).
- Only a player on the Repack game files can see a citizen standing in these maps, and they count toward the online
  cap like any other (the user's choice).

## 7. Checks

Tests:

- `tools\living-world-lint.bat`: OK, the same 54 warnings as the main line (none names a new file); 0 errors.
- `tools\population-lint.bat`: OK, the same 4 warnings (Ereve's forests).
- Moved pins: `ContentLintFriendshipTest` (61 weapon Residents), `ContentLintLootTermsTest` (112 contracts),
  `ArcFinalBeatOncePerServerTest` (+9 beats), `SeedDataTest` (the Coke changeset, +12 rows),
  `CitizenGeneratorSeedTest.untargetedBirthsDrawWhatTheyDrewBeforeSeeding` (four births change home region only: the
  new weight-1 region shifts the weighted roll; names, careers, paces and lifetimes are unchanged).
- New: `FieldCatalogMainTest` (the Repack regions, the places left out, `info/level`),
  `RepackTravelScriptTest.cobieTakesTheScoutsWayBetweenTwilightPerionAndTheCamp`, and `RepackNpcScriptableTest` covers
  the escorts.
- `server-knowledge.json` re-exported (the Coke drops, `repack_talk.js`).

GM live checks after the deploy (they need a Repack client):

1. Coke Town: Marit, Toivo, Inga, Nils, Bartolo, Aksel and Ylva materialize on their routines; a Coke Snail drops a Coke
   Bottle Lid; `bartolo-the-lid-wall` completes.
2. Outside Ninja Castle: Oharu's first quest; Hayate's intro at L60; the contract offers after the rumor.
3. Henesys Ruins (Temple Keeper -> Gate to the Future, swim through to the ruins): Dagny's intro; `!setlevel` 140 and
   friendship 7 (GM), `dagny-timeless-engaw` gives a Timeless Engaw with two 10% scrolls taken.
4. Twilight Perion: Cobie offers the scouts' way to the Tribal Refugee Camp and back; Ama and Kasper are there.
5. Arcane River: Temple Keeper -> Nameless Town; Ottoline's first quest; one page quest sets its `arcane.page.*` flag.
6. Citizens: `!citizens seed coke-town 12` and `!citizens seed zipangu 8`; after a sweep or two `!citizens` lists them;
   a citizen trains in a Coke Ski Camp field while a Repack client watches.
7. Quest log: the 159 quests show nameless in the client's quest window until the held Quest.wz patch ships
   (`tools\living-world-client.bat`), as with every Resident wave since the patch was held.

# iTCG items and the rest of Masteria

Chunk 3 of the town audit (2026-10-25): the iTCG forging loop, a reward path that hands out real pets, Cryssea, and
Residents for Crimsonwood Keep, the Haunted House and two Masteria outposts. New Leaf City's own cast, and its iTCG
gear handed out as friendship gear, belong to Regional Residents wave 3 (`feat/regional-residents-3`); this branch
builds the machinery around it and does not touch NLC's persona files. Branch `feat/itcg-masteria` off
`feat/companion-manager` ff6fbe48a.

## 1. Where things stood

- **65 iTCG items (68 ids) exist in the server WZ and in the stock Cosmic client; 46 had no source.**
  `wz/Etc.wz/Commodity.img.xml` SN 80000004-80000065 is the server's own code-redemption list (all `OnSale=0`), and no
  code data ships.
- **The forging NPCs were placeholders.** Professor Foxwit 9201052, The Glimmer Man 9201083, Corine 9201094,
  Stirgeman 9201102 and Lita Lawless 9201054 (NLC Town Center) and John Barricade 9201051 (Bigger Ben Lobby 600020000)
  each said one line ("The patrol in New Leaf City is always ready..."); Spindle 9201082 (Omega Sector) asked "have you
  heard of the iTCG?"; T-1337 9201101 (NLC) showed its default line. The forging manuals 4031822-4031829 /
  4031907-4031912, the Materia Orb 4031761 and every forging result are in the server WZ. Quest data already holds
  Glimmer Man's Materia Orb quests 4924/4925 (White Versal Materia + 7 Black Crystals 4021008 -> orb, 20,000 EXP) and two
  quest-log shells, 4926 "Interstellar Relics" (Foxwit) and 4927 "Ancient Artifacts" (Barricade), whose completion check
  is an impossible 1000 x 1002140: GMS drove them from the NPC scripts.
- **Resident rewards could not hand out a pet.** `QuestLedger.complete` grants `rewards.items` with
  `InventoryManipulator.addById`, which creates no `pets` row, so a pet item arrives as a dead cash item.
  `AbstractPlayerInteraction.gainItem` and `CashShop.CashItem.toItem` call `Pet.createPet` first.
- **Cryssea 9010018 has a String.wz name and no Npc.wz image in any WZ set**, so a client crashes when she is spawned.
  User decision 2026-10-25: give her a look with a Custom.wz `link` to another NPC's image and ship it with the client
  patch the user is holding.
- **Crimsonwood Keep had a profile (4 venues) and no shop or Residents; the Haunted House and the outposts had no
  profile.** User decision: sub-areas get Residents only on safe outpost maps (few or no mobs). Bigger Ben Lobby 600020000
  (John Barricade, no mobs) and Valley of Heroes 1 610020000 (two Sages, no mobs) are such maps.

## 2. The recipes (sources)

The GMS recipes come from two fan guides that agree where both list a recipe: Hidden Street's "iTCG Equipment Forging
and Upgrading" (read through the Wayback Machine copy of 2017-01-18,
`http://global.hidden-street.net/item-synthesis/itcg`; the live site sits behind a bot check) and MapleSecrets'
"Abridged Guide to iTCG Items" (2011). Hidden Street has every recipe MapleSecrets leaves blank (Tiger's Fang, the Set 3
upgrades, the Set 4 materials, T-1337, Ridley's Stone, Adonis). Neither gives meso fees, so there are none. Odds the
guides do not give (Stirgeman, Adonis) are BotStory's and are marked as such.

Who does what follows MapleSecrets and quest 4924's own text: Foxwit and Barricade draw a manual from the artifacts;
Spindle in Omega Sector forges from a manual or a Materia Orb; Barricade forges the Bosshunter set himself (it needs no
manual).

### Set 1 (Professor Foxwit draws, Spindle forges)

| Result | Manual from | Spindle takes |
|---|---|---|
| Sirius Cloak 1102145 (L50) | Blinking Dingbat 4031752, Dark Matter 4031750, Black Versal Materia 4031754 -> 4031827 | manual, 5 Gold Plate 4011006, 5 Sapphire 4021005, 2 Diamond 4021007 |
| Zeta Cape 1102146 (L60) | Dark Matter, Vorticular Gyro 4031751 -> 4031826 | manual, 5 Power Crystal Ore 4004000, 5 Gold Ore 4010006, 5 Opal 4021004 |
| Black Phoenix Shield 1092052 (L20) | Vorticular Gyro, Zeta Residue 4031753, Black Versal Materia -> 4031829 | manual, 10 Mithril Plate 4011002, 2 Sapphire, 1 Dark Crystal Ore 4004004 |
| Dark Shards 1032049 (L50) | Zeta Residue, Blinking Dingbat -> 4031828 | manual, 10 Black Crystal Ore 4020008, 10 Power Crystal Ore |

### The Glimmer Man's Versal weapons

White Versal Materia 4031760 + 7 Black Crystal 4021008 -> Materia Orb 4031761 (+20,000 EXP; the first time also
completes quest 4924 in the quest log). Spindle turns one orb into the player's choice of the eleven L70 weapons:
Astral Blade 1302079, Cosmic Scepter 1322059, Crescent Moon 1412032, Sunspear 1432045, Heavenly Messenger 1442060,
Celestial Staff 1382053, Andromeda Bow 1452052, Void Hunter 1462046, Black Hole 1472062, Nebula Dagger 1 (LUK) 1332064,
Nebula Dagger 2 (STR) 1332065.

### Set 2 (John Barricade draws or forges, Spindle forges from his manuals)

| Result | Manual from | Spindle takes |
|---|---|---|
| Crystal Leaf Earrings 1032048 (L40) | Taru Totem 4031755, Naricain Jewel 4031758, Mystic Astrolabe 4031756 -> 4031825 | manual, 2 Diamond |
| Antellion Miter 1002675 (L35) | Antellion Relic 4031757, Mystic Astrolabe -> 4031823 | manual, 5 Gold Plate, 5 Steel Plate 4011001, 20 Screw 4003000 |
| Infinity Circlet 1002676 (L50) | Naricain Jewel, Subani Ankh 4031759 -> 4031822 | manual, 5 Silver Plate 4011004, 1 Power Crystal 4005000, 1 Wisdom Crystal 4005001 |
| Stormcaster Gloves 1082223 (L50) | Antellion Relic, Subani Ankh, Taru Totem -> 4031824 | manual, 15 Leather 4000021, 2 Power Crystal |
| Glitter Gloves 1082230 (L50, mage) | LeFay Jewel 4031915, Pharoah's Wrappings 4031916 -> 4031910 | manual, Work Gloves 1082002, 6 Diamond, 6 Wisdom Crystal |
| Facestompers 1072344 (L50) | Taru Totem, Stone Tiger Head 4031913 -> 4031911 | manual, 50 Steel Plate, 25 Leather, 50 Dragon Skin 4000030, 25 Screw |
| Winkel 1452053 (L55, bow) | LeFay Jewel, Typhon Crest 4031914 -> 4031909 | manual, Bow Production Manual 4131010, 50 Screw, 6 DEX Crystal 4005002, 6 Black Crystal |
| Neva 1472064 (L50, claw) | Pharoah's Wrappings, Typhon Crest -> 4031908 | manual, Meba 1472007, 6 Black Crystal, 6 LUK Crystal 4005003 |
| Crystal Ilbi Throwing-Stars 2070016 | Crystal Shard 4031917, Naricain Jewel -> 4031912 | manual, 1 Ilbi Throwing-Stars 2070006, 7 LUK Crystal, 1 Dark Crystal 4005004 |
| Tiger's Fang 1402045 (L50, 2H sword) | Stone Tiger Head, Crystal Shard -> 4031907 | manual, 6 Power Crystal, 6 Black Crystal |

Bosshunter set (L70, Barricade forges from Ridley's Book of Rituals 4031900 + the Ancient piece + two boss trophies):
Helm 1002739 (Ancient Helm 4031897, Ergoth's Jawbone 4031904, Tengu Nose 4031902), Armor 1052148 (Ancient Armor
4031898, Papulatus Curl 4031901, Pianus Scale 4031905), Greaves 1072342 (Ancient Greaves 4031899, Balrog Claw 4031906,
Jack O'Lantern 4031903) for warriors, bowmen and pirates; Faceguard 1002740 (Ancient Faceguard 4031918, Tengu Nose, Jack
O'Lantern), Gi 1052149 (Ancient Gi 4031919, Papulatus Curl, Balrog Claw), Boots 1072343 (Ancient Boots 4031920,
Ergoth's Jawbone, Pianus Scale) for magicians and thieves. Ergoth's Jawbone drops from nothing on the server; the
Crimsonwood Keep cast hands it out (section 6).

### Set 3 (Corine, the Taru spirit upgrades)

Akha 1452054, Xaru 1462047, Kuma 1472065, Maku (LUK) 1332067, Maku (STR) 1332070, Umaru 1382054 (L10) -> -suma (L40):
the weapon, 1 Taru Spirit Feather 4031936, 5 Wisdom Crystal, 10 Jungle Lily 4031937 and a summoned pet of level 15+;
-suma -> -magna (L80): 2 feathers, 15 Wisdom Crystal, 50 Jungle Lily and a summoned pet of level 30+. The weapon in the
inventory is taken (never an equipped one) and the new one is a fresh item.

### Set 4 (Stirgeman, T-1337, Ridley)

Stirgeman upgrades from the Haunted House drops; each attempt costs:
cape 12 Stretchy Material 4032030, 5 Duct Tape 4032029, 8 Coat Hanger 4032027; pants or skirt 8 Stretchy Material,
8 Wad of Gum 4032028, 15 Loaded Spring 4000399, 20 Screw; whip 15 Coat Hanger, 10 Loaded Spring, 5 Duct Tape, 8 Wad of
Gum. The ladders: cape 1102176 -> Raggedy 1102177 -> Mk II 1102178 -> Mk III 1102179 -> Mk IV 1102180 -> Cloak of
Wiliness 1102181 or Cloak of Darkness 1102182 -> Cloak of Justice 1102183; pants 1060127 -> 1060128 ... -> Power Pants
Mk II 1060133; skirt 1061149 -> ... -> Power Skirt Mk II 1061155; whip Stirge-on-a-String 1302088 -> on-a-Rope 1302089
-> o-Whip 1302090 -> Grappler 1302091 -> Swooping 1302092 or Frantic 1302093 -> Angry Stirge 1302094. The guides say an
attempt moves the item up, down, to a random rung or destroys it, without odds; here every attempt moves it one rung up
(section 9).

T-1337: 1 Diferium Fuel Cell 4032022 (or 1 Triferium Fuel Cell 4032021 for half as many again), 50 Boomer Core
4000391, 1 Jumper Cable 4032024, 1 Sparkplug 4032023, 1 T-1 Socket Adapter 4032025 -> a random T-1337 juice
(VitroJuice 2022338, NitroJuice 2022339, ElectroJuice 2022341, MegaJuice 2022342, GigaJuice 2022343, JigaJuice 2022344)
and 2,500 EXP.

Ridley 9201103 (outside the keep, 610020006): 1 Ridley's Stone 4032026 + one boss trophy (Balrog Claw, Bigfoot's Toe
4032013, Ergoth's Jawbone, Jack O'Lantern, Papulatus Curl, Pianus Scale, Tengu Nose) -> one of the guide's 25 random
items (mastery books Advanced Combo Attack 30, Ifrit 30, Mana Reflection 30, the Burning Book of Fire, earrings, umbrellas,
Dimon/Dragon wands, Doomsday/Dragon staves, Kage, ...); ids in the script.

### Set 5 (Adonis, El Nath)

Upgrades (the guide's chance to destroy the item for 50,000 EXP is left out, section 9):
Cloak of Corruption 1102193 -> Shroud of Zakum (+ Ridley's Book of Rituals, 50 Dark Crystal, Naricain Jewel, LeFay
Jewel, Zakum Diamond 4032133); Crystal Blade 1302106 -> Black Crystal Blade 1302107 (+ One-Handed Sword Forging Manual
4131000, One-Handed Sword Forging Stimulator, 50 Black Crystal, Zakum Diamond); El Nathian Cape 1102191 -> Wrath of El
Nath (+ 50 Firebomb Flame, 10 Bain's Spiky Collar, Pharoah's Wrappings, 10 Dark Pepe Beak, 10 Dark Yeti's Horn, Zakum
Diamond); Miner's Hat 1002856 -> Hard Hat (+ 10 Boomer Core, 5 Batteries, 1 Power Crystal, 10 Silver Plate, Zakum
Diamond). Stone Denari 4032134 -> one random item of eight (Dragon Toenail, Maple Syrup, Moon Star Chair, One-Handed Axe
Forging Manual, One-Handed Blunt Weapon Forging Stimulator, Pole Arm Forging Manual, Power Elixir, Pumpkin Spear).

## 3. The NPC scripts

Every script keeps the NPC's own voice from String.wz, shows the full recipe with the player's counts (`#c<id>#`),
checks room before taking anything, takes inputs and gives the result in one step, and never takes an equipped item.
The recipe tables and the take-and-give step live in Java, `server.itcg.ItcgRecipes` and `server.itcg.ItcgForge`
(called through `Java.type`, like the CWKPQ masters call `CwkpqRelicWeapons`; the script engine has no file access for
`load()`), so every NPC reads the same numbers as this document and the tables are unit-tested.

| NPC | Where | Script does |
|---|---|---|
| Professor Foxwit 9201052 | NLC Town Center | draws the four Set 1 manuals; starts quest 4926 on first talk and completes it with the first manual (the quest log's "Interstellar Relics" text then reads true) |
| The Glimmer Man 9201083 | NLC Town Center | Materia Orb synthesis (quest 4924 the first time, the same exchange after); points to Spindle |
| Spindle 9201082 | Omega Sector | forges every manual item and the eleven Versal weapons |
| John Barricade 9201051 | Bigger Ben Lobby | draws the ten Set 2 manuals (quest 4927 like Foxwit's 4926), forges the six Bosshunter pieces; his stock quests are untouched |
| Corine 9201094 | NLC Town Center | the -suma and -magna upgrades |
| Stirgeman 9201102 | NLC Town Center | the Set 4 upgrade attempts |
| Lita Lawless 9201054 | NLC Town Center | the sheriff's bounty board: Masteria trophies for confiscated iTCG goods (section 4), once per day per tier |
| T-1337 9201101 | NLC Town Center | juice synthesis |
| Ridley 9201103 | Crimsonwood Keep exterior | Ridley's Stone exchange; his CWKPQ directions stay as the first menu line |
| Adonis 9201106 | El Nath | Set 5 upgrades and the Stone Denari; his expedition line stays as the first menu line |
| Cryssea 9010018 | NLC Town Center, behind `cryssea` (section 5) | the card swap: three iTCG materials of one set for one of the player's choice from that set |

## 4. Where the materials come from

- **Residents.** Wave 3's NLC cast hands out Set 1-5 materials, the Red Relaxer and much of the forged gear. This
  branch's Masteria cast adds the trophies nothing drops (Ergoth's Jawbone), Ridley's Book of Rituals and Stone, Taru
  Spirit Feathers and Totems, Ancient pieces and the five pets (section 6).
- **Lita Lawless's bounty board** (repeatable, so the loop never runs dry): she has impounded a smuggler's crate of
  card-code goods and pays them out for trophies off the streets she is cleaning. Three tiers, each once per server
  day per character (a quest record, like the daily quests): 30 Street Slime / Urban Fungus drops (Urban Underground,
  L19-21) for a random Set 1 artifact; 30 Boomer Cores or Killa Bee stingers (jungle, MesoGears, L25-40) for a random
  Set 2 piece (Ancient pieces, Stone Tiger Head, Typhon Crest, Crystal Shard, LeFay Jewel, Pharoah's Wrappings); 30
  Windraider or Stormbreaker badges (Phantom Forest, Crimsonwood Mountain, L70-80) for a Taru Spirit Feather, a White
  Versal Materia or a Set 4 part (Ridley's Stone, Triferium Fuel Cell). Drop ids and counts are checked in the drop data
  before the script is written.
- **Drops already on the server**: the Haunted House parts (Glutton Ghoul, Psycho Jack, Sophilia Doll), T-1337 parts
  (Boomer, Electrophant, I.AM.ROBOT, Wolf Spider), the boss trophies (Tengu, Papulatus, Pianus, Balrog, Headless
  Horseman, Bigfoot), Jungle Lily (Mighty Maple Eater, Gryphon), ores and crystals.

## 5. Cryssea (held for the client patch)

- **Client side, prepared and not run.** `tools/wz-patcher/botstory_wz.py` gains a `masteria` step that adds
  `Npc/9010018.img` to a client's Custom.wz: an `info` node whose `link` names The Glimmer Man's image (9201083),
  the same `link` 35 stock Npc.wz images use. kaentake's Custom.wz overlay already serves whole images the base WZ lacks
  (`UI/UIWindowEx.img`, `resman.cpp` `raw__OnGetLocalObject_hook`), and String.wz already names her in every base.
  Unit test in `tools/wz-patcher/tests`; it writes only to a temporary folder. Nothing in this branch runs it or touches
  a client folder or Game Files.
- **Server side, off.** A new server mod `cryssea` (default off, boot only, `MOD_CRYSSEA` in .env): on, map 600000000
  gets Cryssea at the Town Center when it loads, and `scripts/npc/9010018.js` runs her card swap. Off (the shipped
  default), she is never sent to any client. Turn it on only after every client has the patch: a client without it
  closes on entering New Leaf City.

## 6. Masteria Residents

Profiles: `crimsonwood-keep` (exists), new `haunted-house` (682000000, 4 venues), `bigger-ben` (600020000, 10 venues)
and `valley-of-heroes` (610020000, 14 venues), generated by `tools\town-life-profiles.bat generate` and appended to
`index.json`. None joins `ambient-deployment.json` (ambient town life stays Victoria-only) or `regions.yaml`.

Quest-log ids 31450-31599 (this branch's block), appended to `quest-log-ids.yaml` by hand. Level bands from the server's
own data: Crimsonwood Mountain 70-120 (Crimson Guardians at the keep gate 120), the Haunted House rooms 20-70 (Mirror
Ghost 100), MesoGears 25-80.

Weapon lines: wave 3 takes the third regional tier of the gun, two-handed axe, polearm, bow, staff, crossbow, mace,
dagger, wand and knuckle lines. The five recruits at the top of Masteria take five of the six left, L100 / L110 / L120:
spear, two-handed sword and claw at the keep, two-handed blunt and one-handed sword in the Valley of Heroes. The
Haunted House and Bigger Ben recruits fight at L40-60, where every line already has a regional tier: their friendship
rewards are iTCG pets and materials instead.

| Place | Residents (recruits) | Arc |
|---|---|---|
| Crimsonwood Keep | 7 (3): the keeper of the empty sixth plinth (arc owner), the keep's cook, Ridley's copyist, a page; a Dragon Knight gate warden, a Hero drill mistress, a Hermit scout | **The Sixth Banner**: the keep had six Masters; the Banner Master carried the Crimsonwood standard into the Inner Sanctum and never came out, and his stone in the Valley of Heroes is blank. Shared with the Valley. |
| Valley of Heroes 1 | 4 (2): the stone-carver, a Taru pilgrim (Corine's cousin); a Paladin warden, a Crusader of the valley road | the Sixth Banner's last beat: the name carved (world flag, once per server) |
| Haunted House | 7 (2): the last Prendergast (arc owner), a ghost-tour hawker, the gravekeeper of Hollowed Ground, a costume seamstress, a boy on a dare; a Chief Bandit ghost-breaker, a Cleric exorcist | **Where Are You?**: the house's halls loop ("Where am I?", "Where are you?", "This again?") because Sophilia never had her birthday; finale, once per server, the party is held |
| Bigger Ben Lobby | 4 (1): Barricade's first crew's cartographer (arc owner), Foxwit's old student, a lobby porter; a Gunslinger of the crew | **The Enigma Chamber**: the tombstone in MesoGears answers the old crew's door song; finale once per server |

Light ties only: gossip about wave 3's NLC cast (no persona references, which would not resolve before the merge),
Corine and Foxwit by name, the NLC Taxi. Arc finales name their world flag in `requires.notFlags`
(`ArcFinalBeatOncePerServerTest` covers them).

## 7. Shared files and merge notes

| File | Change |
|---|---|
| `src/main/resources/agents/town-life/index.json` | 3 profiles appended (wave 3 appends `aquarium-hall`, `aquarium-store`: keep both) |
| `scripts/config/living-world/loadouts.yaml` | one block appended at the end after `# Masteria Residents (docs/ITCG_MASTERIA_PLAN.md)` |
| `scripts/config/living-world/quest-log-ids.yaml` | 31450+ appended at the end |
| `ResidentDialogue.PORTRAITS` | 4 entries appended (crimsonwood-keep, haunted-house, bigger-ben, valley-of-heroes) |
| `ContentLintFriendshipTest` | weapon pin +5 (the coordinator resolves the pin conflict with wave 3) |
| `server/mods/ServerMods.java` | one new mod line, `cryssea` |
| `server/maps/MapFactory.java` | one call after the map's life loads (gated NPCs) |
| `scripts/config/knowledge/server-knowledge.json` | regenerated for the new scripts (sources fingerprint) |

Routes: none needed. Bigger Ben Lobby and the Valley of Heroes are on both route graphs; the Haunted House is on the
knowledge graph and reached by the NLC Taxi. The audit marks 682000000 as reachable on the agent graph.

## 8. Tests

- `QuestLedgerPetRewardTest`: a pet reward creates a pet (pet id on the item, expiry from section 9), a non-pet keeps
  `addById`, a full cash tab gives nothing and says so.
- `ItcgRecipesTest`: every recipe id exists in the server data (item names), every manual a manual NPC draws is one
  Spindle forges, every Stirgeman ladder is unbroken, every recipe in section 2 is in the table; `ItcgForgeTest`: a
  make takes the inputs and gives the result, refuses without room or inputs, never takes an equipped item, and the
  Corine pet-level gate.
- `CrysseaGateTest`: with the mod off, map 600000000 has no 9010018; on, it does.
- wz-patcher: `tests/test_masteria.py` builds the Custom.wz image into a temporary folder and reads it back.
- Content: lint 0 errors, `QuestLogIdsShippedTest`, `ArcFinalBeatOncePerServerTest`, `PersonaKnowsResolveTest`,
  KnowledgeLint with the refreshed fingerprint, then the full split suite.

## 9. Decisions (the user, 2026-10-25)

| Question | Chosen |
|---|---|
| How long a reward pet lives | like an iTCG code on this server (`CashShop.generateCouponItem`): 30 days of magic time, then a doll that Water of Life revives |
| Stirgeman's upgrade risk | none: every attempt succeeds and moves one rung up; where a ladder forks (cape Mk IV, whip Grappler) the player picks the branch |
| Residents per outpost | 4 each (Crimsonwood Keep and the Haunted House 7 each): 22 Residents, 8 recruitable |
| Extra NPCs beyond the seven | T-1337 (juices), Ridley (Ridley's Stone), Adonis (Set 5) |

Following the Stirgeman answer, Adonis's Set 5 upgrades always succeed too (the guide's destroy-for-50,000-EXP failure
is left out); his Stone Denari stays a random item, which is a draw, not a risk to an item the player owns.

## 10. Live checks after deploy

To be written with the content.

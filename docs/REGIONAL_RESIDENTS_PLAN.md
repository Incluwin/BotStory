# Regional Residents: Residents beyond Victoria Island

Phase F ("regional Residents") of `docs/LIVING_RPG_PLAN.md` and `docs/POPULATED_SERVER_PLAN.md` section 8.
How Residents are authored is in `docs/LIVING_WORLD_GUIDE.md`; this plan says what changes off Victoria, what wave 1
(Orbis, El Nath, Ludibrium) and wave 2 (Leafre, Mu Lung, Herb Town, Ariant, Magatia) contain, and what to check after
each is deployed.

## 1. Where things stood

All 72 named Residents (24 recruitable), 197 Resident quests (118 of them friendship quests), 25 contracts and 9
story arcs lived in the seven Victoria towns. Ossyria and the rest of the world already had citizens, shops, TownLife
profiles (generated 2026-10-03), party quests and field bosses, but no Residents.

The Resident runtime does not care which island a town is on: a persona's `home.town` names a TownLife profile, the
profile gives the map and its venues, presence wakes the town when a human is on that map, and bench camps, recall
travel (server-knowledge routes: Orbis to El Nath on foot, a ship is 3 min) and town names all go through
`ResidentSpots.townMapId`. What still named the seven Victoria towns:

| Where | Before | Now |
|---|---|---|
| `ContentLint.KNOWN_TOWNS` | the 7 Victoria profile ids: any other home town was a lint ERROR | every profile id in `agents/town-life/index.json` |
| routine and rumor spots | never checked: a misspelt or renamed venue put the Resident at the profile's fallback spot without a word | `ContentLint.unresolvedSpot`: every routine, chapter-routine and rumor spot must resolve in its town's profile (ERROR) |
| `ResidentDialogue.PORTRAITS` | borrowed townsperson portraits for the 7 towns, else the Maple Administrator | every Resident town: wave 1 added Orbis, El Nath and Ludibrium; wave 2 adds Leafre, Mu Lung, Herb Town, Ariant, Magatia and the Cygnus/Aran branch's Ereve and Rien. `Map.of` stops at 10 entries, so the map is `Map.ofEntries` now |
| `TownLifeProfileMain.HAND_AUTHORED` | the 7 Victoria profiles, so `generate` never rewrites venue ids persona YAML uses | unchanged: the spot check catches a regenerated profile that drops a venue a Resident uses, and keeping the regional towns out of `HAND_AUTHORED` keeps them in `TownLifeGeneratedProfilesTest`'s geometry and reachability audit |

## 2. Decisions (the user, 2026-10-24)

| Decision | Chosen |
|---|---|
| Residents per town | 7 (Sleepywood/Nautilus size; 21 in wave 1, 35 in wave 2) |
| Towns first | Orbis, El Nath, Ludibrium together (one connected band, levels 30-100) |
| Recruitable share | about a third, as in Victoria: each recruit earned a different way |
| Regional arcs and Victoria | tied to each other, lightly to Victoria (gossip, one errand), never gated by a Victoria arc |

Wave 2 (asked for 2026-10-25) keeps all four: the next five towns from section 3, 7 Residents each, 2 recruits per
town, arcs tied to each other in pairs and to wave 1 only by gossip.

## 3. Regions and waves

Level bands are the fields around each town in the server's own data (`scripts/config/knowledge/server-knowledge.json`).

| Wave | Town (profile) | Fields and levels | Theme |
|---|---|---|---|
| 1 | Orbis (`orbis`) | Orbis Tower 23-36, Cloud Park pixies 35-52, Strolling Path 42-47, Gardens of colours 33-53, Garden of Darkness 73 | the city that waits: the station's ledger, a mother meeting the evening ship |
| 1 | El Nath (`el-nath`) | Icy Path, Cold Field 50-58, Ice Valley, Sharp Cliff 56-82, Wolf Territory 75-80, Dead Mine 41-57, Cave of Trial 43-90 | what the mountain keeps: the dead mine's roll, the door to Zakum, a sentry who walked off a ship |
| 1 | Ludibrium (`ludibrium`) | Terraces 30-39, Toy Factory 36-44, Path and Lost Time 34-46, Warped and Forgotten Path of Time 61-99 | broken time: the tower that lost an hour, the factory that will not stop for it |
| 2 (this branch) | Leafre (`leafre`) | Minar Forest 70-78, Dragon Forest and the Kentaurus fields 80-88, turtles and Rextons 90-97, Wyvern Canyon 97-103, Cornians 100-105, the dragon nests 105-113 | what the dragons left: an egg kept warm forty winters, and a nest that never died |
| 2 (this branch) | Mu Lung (`mu-lung`) | Sky Forest chipmunks and porkies 40-43, the practice field 50-51, Wild Bear area 56-60, peach farm 62, sage cats 66, the walking library 55 | the Book of Forms: a library that walked into the fog, and the one page that didn't |
| 2 (this branch) | Herb Town (`herb-town`) | flower serpents 45, jars 47-48, bellflower fields 53-54, the red-nose pirate den 64-70 | the hundred-year root, and the borrowed page round its marker |
| 2 (this branch) | Ariant (`ariant`) | the Burning Sands 20-29, the north desert road 29-32, the sunset giants 40, the Sahel road 24-32 | the town that carries its water: a well gone to sand in a week |
| 2 (this branch) | Magatia (`magatia`) | Zenumist institute 32-80, Alcadno institute 42-75 | the city of two doors, and a bench between them |
| 3 | Omega Sector, Korean Folk Town, Singapore, New Leaf City, Aquarium | 25-80 | Aquarium has no TownLife profile (the generator refuses swim maps): it needs a hand-authored profile first |

## 4. Wave 1 cast

21 Residents, 7 recruitable, 83 quests (all with quest-log ids 30197-30279), 7 contracts, 3 arcs, 6 rumors, 7 loadouts.
Every Resident has the full Victoria kit: a voice for every intent the runtime asks for, an hourly routine on its
town's venues (aligned so each rumor's cast is awake at its spot), relationships and `about.<persona>` gossip, gifts
(`giftItemIds`), fame lines and friendship lines.

Friendship gear continues the Victoria lines one tier up (Victoria's weapons end at L40-70, its armour at L40-75): the
recruits give their own weapon at friendship 5 / 7 / 10 (7 x 100%, 7 x 60%, 2 x 10% + 5 x 60%, every scroll taking), the
armourers a piece made for the player's class at friendship 5 / 7 / 10 (all 100%, all 60%, two 10% and the rest 60%).

| Town | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| Orbis | Corliss | stationmaster (townsfolk), arc owner | - | - |
| Orbis | Ottilie | keeper of the hill steps, Edric's mother | - | - |
| Orbis | Fitch | letter runner to El Nath (Assassin 44-46) | - | - |
| Orbis | Vesna | cloud park warden (Ice/Lightning Wizard 60-62) | kill proof: 60 luster pixies + 30k | wands Evil Tale L58 / Angel Wings L68 / Phoenix Wand L78 |
| Orbis | Ansel | garden hunter (Hunter 60-62) | meso: 90k day rate | bows Asianic L60 / Golden Hinkel L70 / Marine Arund L80 |
| Orbis | Aster | milliner | - | hats L60 / L70 / L80 |
| Orbis | Perrin | jeweller | - | earrings Pink-Flowered L60 / Metal Silver L70 / Metal Heart L80 |
| El Nath | Edric | snowfield sentry (White Knight 70-72), arc owner | arc and rumor: his letter sent, Sigrun heard, TRUSTED, 45k | - |
| El Nath | Sigrun | warden of the door to Zakum (Crusader 70-73) | kill proof: 60 Jr. Cerebes + 20k | two-handed swords Doombringer L70 / Blue Screamer L80 / Berzerker L90 |
| El Nath | Bjarke | wolf hunter (Dragon Knight 70-73) | items: 30 White Pang Tails + 30k | spears Redemption L70 / Omega Spear L80 / Fairfrozen L90 |
| El Nath | Ulla | furrier | - | gloves L60 / L70 / L80 |
| El Nath | Oskar | bootmaker (Gordon's old apprentice) | - | shoes L70 / L80 / L90 |
| El Nath | Maud | keeper of the dead mine's roll | - | - |
| El Nath | Kalle | 3rd-job hopeful (Fighter 58-60) | - | - |
| Ludibrium | Ignatz | clockwinder, arc owner | - | - |
| Ludibrium | Prue | toy factory forewoman | - | - |
| Ludibrium | Benno | retired terrace guard | - | - |
| Ludibrium | Saskia | path runner (Assassin 60-63) | rumor: hear her terms at the weapon store + 40k | claws Sapphire Gigantic L60 / Blue Scarab L70 / Black Mamba L80 |
| Ludibrium | Jasper | warped-path bounty hunter, ex-Nautilus (Gunslinger 60-63) | kill proof: 50 master chronos + 20k | guns Burning Hell L60 / Abyss Shooter L70 / Infinity's Wrath L80 |
| Ludibrium | Della | toy tailor | - | coats (an overall where the class wears robes, else a top) L70 / L80 / L90 |
| Ludibrium | Marlow | banner keeper of the old guard | - | capes Blue Giles L85 / Turquoise Musketeer L95 / Ludibrium Cape L100 |

Quests use the region's own fields: each mob's signature drop (its 60% etc item, so collect steps are honest; one
`dropFrom` overlay, Maud's gold teeth), kill steps on the maps where those mobs spawn, VISIT_MAP to named places
(Old Man's House, the Door to Zakum, the Whirlpool of Time) and TALK_TO errands between Residents, several across towns
(Orbis to El Nath on foot, Orbis to Ludibrium by ship). Area bosses appear once each as a single kill: Riche (Vesna),
the Snow Witch (Ulla) and the Snowman (Bjarke).

**The regional thread.** Eleven years ago the crimson balrog boarded the evening ship from Ellinia. Corliss, Orbis's
stationmaster, never closed that page of his ledger: eleven boarded, one never landed. `corliss-the-unclosed-page`
sends the player to Oda, Ellinia's station master, who remembers the boy in the green hat (the one Victoria errand),
and sets the world flag that opens El Nath's arc: Edric, the sentry who froze on that ship, got off in Orbis
unrecorded and walked down the mountain. His letter (`edric-the-unwritten-letter`, which needs Corliss's quest) goes up
to his mother Ottilie; Corliss then closes the page in red (`corliss-the-last-name`), and Edric, once he has told Sigrun
at the door (rumor), can be recruited. Ludibrium's arc stands alone: Ignatz's tower has struck eleven twice for nine
years; the factory has to stop for one hour (`ludibrium-the-hour-argument`, then `ignatz-the-twelfth-strike`).

| Arc | Chapters (gate) |
|---|---|
| `corliss-the-unclosed-page` | Every Name Written / The Unclosed Page (his quest) / Read Aloud (rumor with Ottilie) / Arrived (world flag `orbis.page.closed`) |
| `edric-the-unwritten-letter` | Sentry / A Name in the Ledger (world flag `orbis.page.opened`) / Sent (his letter quest; unlocks his contract and the rumor) / The Step Is Swept (rumor with Sigrun) |
| `ignatz-the-lost-hour` | Wound Nightly / Eleven Strikes (his quest) / The Hour Argued (rumor with Prue) / Twelve (world flag `ludibrium.hour.found`) |

| Rumor | Cast, spot, hours (ROUTINE_ZONE) | Sets on listeners |
|---|---|---|
| `orbis-the-page-read-aloud` | Corliss, Ottilie at the Top of the Hill door, 19-21, from arc chapter 2 | - |
| `orbis-cloud-park-at-dusk` | Vesna, Ansel on the lower central street, 18-20, every 2 days | - |
| `el-nath-what-the-ship-took` | Edric, Sigrun at the Chief's Residence door, 18-19, from Edric's chapter 3 | `el-nath.heard-the-ship` (Edric's contract) |
| `el-nath-the-roll-call` | Maud, Bjarke, Kalle by Jade, 19-21, every 2 days | - |
| `ludibrium-the-hour-argument` | Ignatz, Prue at the lower central benches, 19-21, from Ignatz's chapter 2 | `ludibrium.heard-hour` (Ignatz's last quest) |
| `ludibrium-the-runners-cut` | Saskia, Jasper at the weapon store door, 18-21 | `ludibrium.heard-runner` (Saskia's contract) |

Victoria is touched in one place: Oda gained an `about.corliss` line.

## 5. Wave 2 cast

35 Residents, 10 recruitable, 124 quests (quest-log ids 30285-30408, appended after the Cygnus/Aran branch's
30280-30284), 10 contracts, 5 arcs, 10 rumors, 10 loadouts. The kit and the gear rules are wave 1's. Each recruit gives
the next tier of a Victoria weapon line that wave 1 did not take, and carries the tier below it in its loadout; three
armourers continue wave 1's hat, glove and shoe lines one tier up.

| Town | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| Leafre | Hessa | keeper of the cold egg (townsfolk), arc owner | - | - |
| Leafre | Arlo | would-be dragon rider, Hessa's grandson | - | - |
| Leafre | Bastian | knight of the west border (White Knight 70) | kill proof: 60 dark rashes + 30k | one-handed swords Red Katana L70 / Khan L80 / Fraute L90 |
| Leafre | Runa | axe of the canyon road (Crusader 80) | meso: 120k day rate | one-handed axes Gaea L80 / Vifennis L90 / Tomahawk L100 |
| Leafre | Mirelle | hatter | - | helmets for the player's class L90 / L100 / L110 |
| Leafre | Dovan | cobbler of wyvern hide | - | boots L100 / L110 / L120 |
| Leafre | Elsk | porter of the Orbis ship, Corliss's letter-friend | - | - |
| Mu Lung | Meiwen | teacher of the forms, arc owner | - | - |
| Mu Lung | Qing | keeper of the walking library | - | - |
| Mu Lung | Baojun | guard of the sky road (Spearman 60) | kill proof: 60 black porkies + 25k | polearms Skylar L60 / The Gold Dragon L70 / Eclipse L80 |
| Mu Lung | Suyin | brawler of the practice field (Marauder 70) | intro quest and rumor: warm up for her, hear her challenge to Xiaobo, 50k | knuckles Beia Crash L70 / Steelno L80 / White Fangz L90 |
| Mu Lung | Fengzi | glover of the forms | - | gloves for the player's class L90 / L100 / L110 |
| Mu Lung | Lai | peach farmer | - | - |
| Mu Lung | Xiaobo | pupil of the practice field | - | - |
| Herb Town | Oksun | keeper of the hundred-year garden, arc owner | - | - |
| Herb Town | Dohyun | root-digger, Oksun's grandson | - | - |
| Herb Town | Taejin | woodcutter of the old swamp (Fighter 60) | items: 30 bellflowers + 20k | two-handed axes The Shining L60 / Chrono L70 / Helios L80 |
| Herb Town | Maren | hunter of the red-nose pirates, ex-Aquarium cutters (White Knight 70) | meso: 70k day rate | two-handed blunts Gigantic Sledge L70 / The Morningstar L80 / Leomite L90 |
| Herb Town | Sookja | keeper of the tea house | - | - |
| Herb Town | Gyuwon | radish farmer, formerly a red-nose | - | - |
| Herb Town | Aeri | apprentice herbalist | - | - |
| Ariant | Nadira | water-seller of the market, arc owner | - | - |
| Ariant | Farid | jar-runner | - | - |
| Ariant | Sefa | once a sand bandit (Bandit 60) | rumor: hear her terms to Farid + 40k | daggers Deadly Fin L60 / Kandine L70 / Dragon's Tail L80 |
| Ariant | Harun | guard of the palace gate (Page 50) | kill proof: 40 desert giants + 15k | one-handed blunts Knuckle Mace L50 / Tamus L60 / The Judgement L70 |
| Ariant | Imran | cactus farmer | - | - |
| Ariant | Yasmin | dyer of sunset scarves | - | - |
| Ariant | Tariq | caravan master of the Sahel road | - | - |
| Magatia | Gilda | clockmaker of the Alcadno, arc owner | - | - |
| Magatia | Aurel | archivist of the Zenumist | - | - |
| Magatia | Vael | alchemist of the Zenumist (Fire/Poison Wizard 65) | meso: 80k day rate | staves Evil Wings L65 / Dark Ritual L75 / Kage L85 |
| Magatia | Kipp | watchman of the Alcadno labs (Crossbowman 60) | items: 30 pieces of steel + 20k | crossbows Golden Crow L60 / Gross Jaeger L70 / Marine Raven L80 |
| Magatia | Pellam | apprentice of the archive | - | - |
| Magatia | Mott | sweeper of both houses | - | - |
| Magatia | Hedda | builder of mechanical birds, retired Alcadno engineer | - | - |

Day-rate, item and kill-proof recruits need their intro quest first (the `<id>.intro.done` flag); Sefa needs only her
rumor, Suyin both. Each recruit's `refuse.flags` line tells the player how to get every flag its contract needs. With wave 1's six lines (wand, bow, two-handed sword, spear, claw, gun) and these ten, every one of
Victoria's sixteen weapon lines now has a regional tier.

Single kills of rare spawns: Rurumo (Vael's Evil Wings), Deet and Roi (Kage), the Security Camera (Marine Raven).
The Critical Error room behind Alcadno area C-1 is a mini-dungeon (`scripts/portal/MD_error.js` opens the first free
copy of 261020301-261020330), so the steps that use it list all thirty copies.

**The regional threads.** Two pairs of towns share a story; Leafre's stands alone.

- *Mu Lung and Herb Town.* Mu Lung's library walked into the foggy forest with every book but one page: the seventh
  form of Meiwen's Book of Forms. Qing's library card names the borrower, thirty years ago: "Oksun, Herb Town. to wrap
  a root marker. will return." (`meiwen-the-library-card`, world flag `mu-lung.page.traced`). The marker is on her
  mother's hundred-year bellflower root. Her root comes up only when Dohyun is ready (`herb-town-dig-it-this-year`, then
  `oksun-dig-the-root`, world flag `herb-town.root.dug`), and Meiwen's last quest takes the page home
  (`meiwen-the-seventh-form`, needs both the Mu Lung rumor and the dug root; world flag `mu-lung.forms.whole`, which
  also closes Oksun's arc).
- *Ariant and Magatia.* Ariant's market well went to sand in a week, seven years ago; sand dwarves cut its vein
  digging under the sunset giant (`nadira-empty-sacks`, `nadira-the-tunnel-mouth`, world flag `ariant.well.traced`).
  Only an Alcadno pump under a Zenumist still could lift and clean it, and the societies have not built anything
  together in a hundred years. In Magatia, Gilda and Aurel have shared a bench and a tin of notes for twenty years
  without a word aloud; once Gilda's clock is mended they speak in the square (`magatia-out-loud`), and her errand to
  Aurel sends each through the other's door (`gilda-the-other-door`, world flag `magatia.bench.crossed`). Nadira's
  last quest asks them both (`nadira-a-new-spring`, needs the Ariant rumor and Magatia's flag; world flag
  `ariant.well.flowing`, which opens the last chapter in both towns).
- *Leafre.* Hessa was nine when she carried an egg down from the burning dragon nest; she has kept it warm forty
  winters. A matching shell from the nest's newties shows the nest never died (`hessa-the-shell-that-matches`, world
  flag `leafre.nest.alive`), Arlo argues it should go home (`leafre-should-it-go-home`), and the player carries it
  there (`hessa-carry-it-home`, L105).

| Arc | Chapters (gate) |
|---|---|
| `hessa-the-cold-egg` | Kept Warm / Forty Winters (her first quest) / The Nest Remembers (world flag `leafre.nest.alive`; unlocks the rumor) / Home (world flag `leafre.egg.home`) |
| `meiwen-the-book-of-forms` | Six Forms / Pages in the Fog (her first quest) / Lent, Not Lost (world flag `mu-lung.page.traced`; unlocks the rumor) / The Seventh Form (world flag `mu-lung.forms.whole`) |
| `oksun-the-hundred-year-root` | Planted Before Me / A Hundred Springs (her first quest; unlocks the rumor) / Up (world flag `herb-town.root.dug`) / Returned (world flag `mu-lung.forms.whole`) |
| `nadira-the-dry-well` | Seven Dry Years / Wet Sand (her first quest) / Lost Is Not Gone (world flag `ariant.well.traced`; unlocks the rumor) / The Well Sings (world flag `ariant.well.flowing`) |
| `gilda-the-bench-between` | Twenty Years of Notes / On Time (her first quest; unlocks the rumor) / The Other Door (world flag `magatia.bench.crossed`) / Down the Sahel Road (world flag `ariant.well.flowing`) |

| Rumor | Cast, spot, hours | Sets on listeners |
|---|---|---|
| `leafre-should-it-go-home` | Hessa, Arlo on the lower central street, 19-20, from Hessa's chapter 3 | `leafre.heard-home` (Hessa's last quest) |
| `leafre-the-canyon-watch` | Runa, Bastian by Koscu, 18-20, every 2 days | - |
| `mu-lung-the-borrowed-page` | Meiwen, Qing on the lower central street, 19-20, from Meiwen's chapter 3 | `mu-lung.heard-page` (Meiwen's last quest) |
| `mu-lung-the-challenge` | Suyin, Xiaobo on the lower west street, 17-19 | `mu-lung.heard-challenge` (Suyin's contract) |
| `herb-town-dig-it-this-year` | Oksun, Dohyun on the middle east street, 19-20, from Oksun's chapter 2 | `herb-town.heard-root` (Oksun's dig) |
| `herb-town-the-old-crew` | Maren, Gyuwon on the middle east street, 19-21, every 2 days | - |
| `ariant-the-well-argument` | Nadira, Tariq on the middle central street, 19-21, from Nadira's chapter 3 | `ariant.heard-well` (Nadira's last quest) |
| `ariant-sefas-terms` | Sefa, Farid at the station platform door, 17-19 | `ariant.heard-sefa` (Sefa's contract) |
| `magatia-out-loud` | Gilda, Aurel on the lower central street, 19-21, from Gilda's chapter 2 | `magatia.heard-bench` (Gilda's errand) |
| `magatia-the-sweepers-news` | Mott, Hedda on the lower east street, 18-20, every 2 days | - |

A rumor repeats on its schedule with no end gate, so every rumor line reads true both before and after the beat it
sets up; base dialogue that a chapter would make untrue is either worded to hold in every chapter or overridden.

Wave 1 is touched in two lines: Corliss gained `about.elsk` (Orbis), Jasper `about.maren` (Ludibrium). Errands cross
towns along the real routes: Leafre to Orbis by ship, Mu Lung to Herb Town on foot (and Mu Lung to Orbis by crane),
Ariant to Magatia up the Sahel road.

## 6. Portraits and the client (after deploy)

Nothing in either branch ships an image or a WZ file. Two client steps follow each deploy. **For wave 2 the user is
holding the client patch (2026-10-25): the steps below wait until it is released; the export data in this branch is
complete for when it is.**

1. **Quest log, with the deploy.** `living_world.QUEST_LOG_MIRROR` is already on, and the new quests have quest-log
   ids (wave 1: 83, 30197-30279; wave 2: 124, 30285-30408), so a player who takes one before their client has the
   matching Quest.wz entries sees it nameless in the quest log (the server itself needs no Quest.wz entry). Run
   `tools\living-world-client.bat "<client folder>"` (or MapleEdit `living_world_quests.py` and deploy Quest.wz + Etc.wz)
   right after the restart and ship the patch. Since the quest-step-progress merge the exporter also writes each step's
   live counters (`#a`/`#c` codes) into the quest text, so wave 2's quests get counters with no extra work. That run
   also renders the portraits of every Resident that already has a character.
2. **Portraits, once the new Residents exist.** A Resident's character is created the first time its town wakes, so
   the regional Residents have no portrait until then and borrow a townsperson's (section 1). Wake each town once
   within its cast's routine hours, then re-run `tools\living-world-client.bat` (or MapleEdit
   `living_world_portraits.py --install`): it assigns the next ids in `portrait-npc-ids.yaml` (wave 1's from 9970054),
   renders Npc.wz/String.wz from the client's own Character.wz, and `!world reload` makes the server use them. Npc.wz is
   ~580 MB, so friends download it again.
   - Wave 1: `!world wake orbis`, `el-nath`, `ludibrium`; Fitch and Bjarke are away 10-16, Prue 12-16.
   - Wave 2: `!world wake leafre`, `mu-lung`, `herb-town`, `ariant`, `magatia` at 18-19 server time, when every one of
     the 35 is in town (Mott is away 9-17, Suyin 10-16, the recruits mostly 12-18).

`portrait-npc-ids.yaml` held 54 ids before wave 1, so the 18 friendship-only Victoria Residents added 2026-10-02
(Isolde, Mirabel, ...) still borrow portraits too; the same re-export fixes them. The new ids file and the
quest-log-ids file the tool writes belong in a commit afterwards.

## 7. Live checks after deploy

GM commands (`!world`, `!warp`, `!level`, `!item`) are fine for setup; the behaviour itself is played, not skipped.

### Wave 1

1. **Boot.** `!world status` / `!world census`: 93 personas, towns include orbis=7, el-nath=7, ludibrium=7, no lint
   errors in the log.
2. **They live there.** `!world wake orbis` (and el-nath, ludibrium) inside the routine hours, `!world residents`:
   each Resident materializes at its venue (log `Resident <id> (<Name>) materialized in orbis at x,y`), dressed (job
   Residents in their loadout, Vesna/Ansel/Sigrun/Bjarke/Saskia/Jasper carrying their tier-1 friendship weapon from the
   kit). Walk the town at a slot change and watch one walk to its next venue.
3. **Talk.** `hi Corliss`, `who are you`, `tell me about Ottilie`, `Corliss talk`, double-click a Resident: the NPC
   window shows its name, a borrowed Orbis/El Nath/Ludibrium portrait (own portrait after section 6.2) and its menu.
4. **Quest log and markers.** On a level 30-40 character in Orbis: the lightbulb over Corliss, `Corliss quest`,
   accept `corliss-lamps-for-the-platform` (Accept/Decline window with rewards), kill 25 jr. sentinels on Orbis Tower
   20F: the quest is in the client quest log under "Residents" with its name (after the Quest.wz patch) and the kill
   count moves; the book over Corliss once 15 shellpieces are in the bag; `done` completes it.
5. **The thread across towns.** `corliss-the-unclosed-page`: ride to Ellinia, talk to Oda (her TALK_TO line), back to
   Corliss; Corliss's greeting changes (chapter 2) and Edric's in El Nath too (world flag). Level 50: `edric-the-watch`,
   then `edric-the-unwritten-letter` up to Ottilie and back (Edric's chapter 3: his contract unlocks);
   `corliss-the-last-name` becomes available and closes the page (Corliss's chapter 4 once the read-aloud rumor has
   played, evenings 19-21 on the hill steps).
6. **A recruit.** Quickest: Ansel. `ansel-the-colour-gardens` (L35), then `Ansel join me`: he states 90,000; pay it,
   he joins the party; `follow me`, `Ansel bench`, `call Ansel` from Victoria (3 min ship ETA whisper), `release
   Ansel`. A kill-proof one: Jasper (`jasper-the-platoon`, then 50 master chronos on the path of time). A rumor one: Saskia
   (be at the weapon store door 18-21 for `ludibrium-the-runners-cut`).
7. **Friendship gear.** `!world bond <player> vesna 110` (friendship 5), level 58, `vesna-evil-tale`: the hand-in gives
   Evil Tale with seven 100% scrolls taken; `!world bond ... aster 110` and `aster-yellow-plumes` on two classes gives
   the class's L60 hat.
8. **Rumors.** At their hours with a player in range: `orbis-cloud-park-at-dusk`, `el-nath-the-roll-call`,
   `ludibrium-the-runners-cut` play (`!world rumor play <id>` forces one); listeners get the flags in section 4.

### Wave 2

1. **Boot.** `!world census`: 131 personas (93 + Cygnus/Aran's 3 + 35), towns include leafre=7, mu-lung=7,
   herb-town=7, ariant=7, magatia=7; the boot log has no living-world lint errors.
2. **They live there.** `!world wake magatia` (and the other four) at 18-19 server time, `!world residents`: each
   Resident materializes at its venue; Vael, Kipp, Sefa, Harun, Bastian, Runa, Baojun, Suyin, Taejin and Maren are
   dressed in their loadout with the weapon one tier below their first friendship gift. Watch Gilda and Aurel walk to
   the lower central street at 19:00.
3. **Talk.** `hi Nadira`, `who are you`, `tell me about Tariq`, double-click Hedda: the NPC window shows the name, a
   borrowed Ariant/Magatia portrait (own portrait after section 6.2) and the menu.
4. **Quest log and markers.** A level 30-35 character in Magatia: the lightbulb over Aurel, accept
   `aurel-gelatin-seals`, kill 25 cube slimes in Zenumist units 101/102: the quest shows in the quest log with its live
   counters (after the Quest.wz patch), the book over Aurel at 15 gelatins, `done` completes it. Also take
   `hedda-a-mechanical-bird` and enter Critical Error from area C-1: the visit step completes in whichever copy the
   portal opens.
5. **A thread across towns.** Level 44 in Ariant: `nadira-empty-sacks` (Nadira's greeting changes: chapter 2),
   `nadira-the-tunnel-mouth` (world flag; the well argument plays 19-21 on the middle central street). In Magatia:
   `gilda-mithril-for-the-clock`, then stand on the lower central street 19-21 for `magatia-out-loud`, then
   `gilda-the-other-door` (Gilda's chapter 3). Back in Ariant `nadira-a-new-spring`: talk to Gilda and Aurel, return;
   Nadira's and Gilda's last chapters open together. The Mu Lung/Herb Town thread is the same shape
   (`meiwen-the-library-card`, `oksun-the-old-gardens`, the two rumors, `oksun-dig-the-root`, `meiwen-the-seventh-form`).
6. **A recruit.** Quickest: Sefa. Stand by Ariant's station platform door 17-19 until `ariant-sefas-terms` plays, then
   `Sefa join me`: 40,000; `follow me`, `Sefa bench`, `call Sefa` from Magatia, `release Sefa`. A kill-proof one: Harun
   (`harun-the-sunset-giants`, then 40 desert giants at the ruins). An item one: Kipp (`kipp-the-rounds`, then 30
   pieces of steel from the iron mutae).
7. **Friendship gear.** `!world bond <player> vael 110`, level 63, `vael-evil-wings` (one rurumo for the purple
   liquid): the hand-in gives Evil Wings with seven 100% scrolls taken. `!world bond ... fengzi 110` and
   `fengzi-kentaurus-marrow` on two classes gives each class its L90 glove.
8. **Rumors.** `leafre-the-canyon-watch`, `mu-lung-the-challenge`, `herb-town-the-old-crew`,
   `magatia-the-sweepers-news` at their hours (or `!world rumor play <id>`); listeners get the flags in section 5.

## 8. Later waves

The same shape per town: 7 Residents, 2 recruits continuing a weapon line where the earlier waves stop (every Victoria
line has a regional tier now; wave 3 takes the next one up), armourers for top/bottom/shield and the next
hat/glove/shoe tiers, one arc per town, rumors at venues where the cast is awake. Wave 3: Omega Sector, Korean Folk
Town, Singapore, New Leaf City; Aquarium after its hand-authored TownLife profile exists.
